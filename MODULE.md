# 载荷构建模块（`com.kernelpack`）源码说明

本文件说明这个压缩包里的**构建与模块相关源码** —— 即"把 boot.img 变成可用载荷"这件事的全部实现，
以及它与 App 外壳（UI / 提权流程 / 厂商载荷目录）的边界。

---

## 一、这个模块做什么

```
boot.img ──► 内核镜像 ──► 内核符号表(kallsyms) ──► 这条链需要的偏移 ──► 打好补丁的 .so
  ①            ②                ③                        ④                 ⑤
```

| 阶段 | 实现位置 | 关键点 |
|---|---|---|
| ① boot.img 解析 | `boot/boot/` | 头 v0~v4、裸 `Image`、`Image.gz`；LZ4（含 legacy）/ zstd / gzip 解压 |
| ② 内核镜像 | `boot/KernelDecompressor.kt` | 压缩格式自识别；解不出来就报诊断，不猜 |
| ③ kallsyms | `kallsyms/KallsymsFinder.kt` | Linux **6.4 前后两种排布**；`_text` 基址自检 |
| ④ 偏移解析 | `resolve/OffsetResolver.kt` + `model/` | 符号 → 偏移；**推导值单独标注**，不与实测混为一谈 |
| ⑤ 打补丁 | `patch/SharedObjectPatcher.kt` | 按**寄存器数据流**改写 `movz/movk/movn` 常量；**原地覆盖、不增删字节**；改完重扫自证 |

**这个模块不依赖 Android 框架**（没有 `Context` / `Activity` / `R` 引用），
是纯 Kotlin + JVM 标准库。可以直接复制到别的工程里用，也可以单独跑单测。

---

## 二、目录结构

```
app/src/main/java/com/kernelpack/
├── KernelPack.kt              总入口：pack(PackRequest) → PackResult
├── SymbolAlignment.kt         ★ 双向符号对齐闸门
├── Hex.kt
├── boot/                      boot.img 头解析 + 压缩格式
├── kallsyms/                  kallsyms 扫描（两种排布）
├── elf/                       ELF 只读解析
├── patch/                     SharedObjectPatcher + PatchSpec + PatchReport
├── resolve/                   KernelImage + OffsetResolver
├── model/                     KernelImageAnalysis / TargetProfile / OffsetEntry / ResolveSource
├── policy/                    ★ BuildGate（硬闸门）· KernelSchemeSelector（选线）
├── profile/                   ★ BaselineRegistry（103 档）· GhostLockKernelOffsets
│                              · GhostLockKernelCatalog · Profiles / AbiProfile
├── offsets/                   ★ GhostLock offsets.json 互通
│                              （schema / IO / 合并 / 键映射 / 统一解析）
├── offset/                    OffsetProvenance（逐条偏移的来源与可信度）· PselectFeasibility
├── vivo/                      VrKoBypass（vr.ko 判定）· VrKoPayloadCheck（字节判据）
│                              · VrKoPayloadGate（蓝厂方案专属闸门）
├── ota/                       ★ 「解析完整包链接」：HTTP Range 只取需要的块
│                              HttpRangeReader · ZipCentralDirectory · PayloadBinUtils
│                              · XzDecoder · OtaPayloadExtractor
└── export/                    target.h / offsets.json 导出

app/src/test/java/com/kernelpack/    对应的单测（26 个文件）

app/src/main/jniLibs/arm64-v8a/
├── libbaseline_6_1.so         ★ 自编 6.1 族基线（带 vr.ko 抹标记，配方见 载荷构建/）
├── libbaseline_6_12.so        ★ 自编 6.12 族基线（同上）
├── libionstack.so             通用方案 6.6 基线（**不带** vr.ko，本就不该带）
└── libbs.so                   蓝厂方案 6.6 基线（all-in-one，自带 vr.ko 抹标记）

app/src/main/assets/
└── GL_LICENSE_Apache2.txt     GhostLock 上游许可证（Apache-2.0）
```

UI 侧的对接点只有三个文件，它们**不在**本模块的"纯逻辑"范围内，但一起放进来了以便对照：

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/ting/root/PayloadBuilderViewModel.kt` | 构建页状态机；把 UI 事件翻译成 `PackRequest` |
| `app/src/main/java/com/ting/root/BundledPayloadCatalog.kt` | 安装页的**厂商载荷目录**（与构建模块是两套机制） |
| `app/src/main/java/com/ting/root/BundledPayloadImported.kt` | 批量并入的 83 份载荷登记表（自动生成，勿手改） |

---

## 三、四条不能违反的规则

这个模块的注释里反复出现四条规则，改代码前请先读：

1. **偏移只能来自真实产物。** 认不出来就报缺，**绝不按内核版本外推**。
   每条偏移都带 `OffsetNote`（来源层级 + 量自哪个内核 + 出处），
   由 `OffsetSet.status` 取**最弱一环**作为整组状态。
2. **能力和它的闸门是一对。** 例如 `MAINLINE_SERIES` 里能有 `6.1`，
   唯一前提是 `libbaseline_6_1.so` 存在；那份基线被拿掉，6.1 必须同时退回拒绝。
3. **双向对齐才能打补丁。** 基线 `.so` 里有的字面量 ∩ boot.img 解出的符号，
   缺任何一边都会被 `SymbolAlignment` 阻断 —— 因为"少改一项"是**静默**失败。
4. **不猜。** 认不出内核串 / 认不出基础 `.so` / 认不出结构体族，一律如实报缺并停止，
   **不拿邻近版本或别的系列顶替**。本工程两次勘误都是"猜"出来的。

---

## 四、单测怎么跑

```bash
# 只跑载荷模块的单测（不需要连设备）
./gradlew :app:testDebugUnitTest --tests 'com.kernelpack.*'
```

`BaselineRoutingLookupTest` 值得单独提一句：它把"路由用的表/词汇"与"打包查的表/词汇"
必须一致这件事写成了断言 —— 因为 4.0.0 发布前那次「基线 ABI 冲突」误报，
根因就是这两者分叉（一张表只有 2 档、一个字段有两套语义）。
