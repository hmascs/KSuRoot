# KSuRoot

> ⚠️ **温馨提示**：蓝厂机型调度策略较为严格，提权时子进程易被系统回收。请先在「开发者选项」中开启「停止限制子进程」后再执行提权，成功率更高。

基于 **CVE-2026-43499（GhostLock）** 内核漏洞的一键 KernelSU 提权工具。

本分支以 KSuRoot 为蓝本，完整同步 [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy) v0.2.6 主线更新，并在此基础上增加了**载荷构建**、**厂商载荷内置**与一整套液态玻璃 UI。

> Mod by **hmascs** · 版本 **4.0.0** · Apache-2.0
> 仓库：<https://github.com/hmascs/KSuRoot>

---

## 4.0.0 有什么变化

### 内核覆盖：从「一条 6.6」到「三族 6.1 / 6.6 / 6.12」

3.2.0 的内核支持是 **6.6 一条线**（外加 6.12 的口径声明）。4.0.0 扩到**三个结构体族**，
并且不是靠"版本号外推"，而是**每一族都有自己的基线库**：

| 结构体族 | `pi_lock` | `cred` | `tasks` | 基线库 |
|---|---|---|---|---|
| `6_1` | `0x924` | `0x838` | `0x550` | `libbaseline_6_1.so`（自编） |
| `6_6` | `0x90C` | `0x820` | `0x550` | `libionstack.so` / `libbs.so`（沿用） |
| `6_12` | `0x9EC` | `0x900` | `0x638` | `libbaseline_6_12.so`（自编） |

**为什么必须按族选库**：结构体偏移是**编译期烤死的，patch 改不了**。
拿 6.6 的库去打 6.1 的内核，符号就算全部 patch 对，写进去的也是**错位置的字段** ——
比"符号对不上"更隐蔽。所以 `BaselineLibraries.resolve` 先由内核串推出族，再选库。

> **6.1 的口径变更留档**：6.1 曾在 2026-09-12 被移出主线，理由是"它是 flat 形态
> （88 字节 / task@0x30），与 6.6/6.12 的 nested（112 字节 / task@0x50）不是一套"。
> **这个理由本身是对的**；变的是我们后来为 6.1 编出了专属基线，
> 于是"拿 6.6 的偏移硬打 6.1"这个危险不再存在。
> ⚠️ 两条要一起读：**6.1 能进主线的唯一前提是它有自己的族基线** —— 那份基线若被拿掉，6.1 必须同时退回拒绝。

### 登记档位：51（通用）+ 52（蓝厂）= **103 档**

引入上游 GhostLock 源码登记的 **50 个内核适配**（每档 9 个符号地址 + 结构体族），
让"通用方案"从 1 档扩到 51 档；蓝厂方案把这 51 档全部挂上 vr.ko 绕过，成为 52 档
（含手写实测的 PD2520）。

> ⚠️ 这 50 档一律标 `UPSTREAM_TARGET_H` + `beta`：**数据是真的（来自上游源码），
> 但我方未在真机上验证过**。界面上与实测档分开显示，不谎报验证状态。

### 双向符号对齐闸门（新的安全闸）

打补丁只能改写**基线 `.so` 里本来就有的字面量**。所以"基线有哪些键"和
"boot.img 解出哪些键"必须**双向对齐**，缺任何一边都会让产物里留下一批
"为别的内核烤死的旧值"—— 而那是**静默**的：不报错、也不表现为失败，装机后才炸。

原先的写法是 `baseline.symbolOffsets[key] ?: continue`（直接跳过），
于是"看起来支持、实际写错内存"。现在 `SymbolAlignment` 对不齐就**阻断打包**。

### 与 GhostLock 的 offsets.json 互通

采用 GhostLock 的 `offsets.json` 作为**互通格式**（字段命名与结构已逐键核对，见下节），
实现读 / 写 / 合并（三种冲突策略），并且**往返不丢未知键**——
别人文件里我们不认识的键会原样保留，不会被静默丢掉。

### 设置页新增「基线覆盖面」

把注册表的真实覆盖面摊开给用户看，而不是让人猜：

- 三个主线系列 × 两个方案各自**已登记多少档**（实测 / beta 分开计数）
- **上游清单对账**：GhostLock 源码登记的 50 档 vs 我方已登记档位（必须零差异）
- 偏移可信度直方图（已验证 / 交叉参考 / 占位符）

### 内置厂商载荷：39 → **122 条**

新增并入批次 **83 份**（Pixel 全系、三星全系、OPPO / realme / 一加 / 华硕等），
随包 `.so` 共 **126 个**。读不出内核版本的 77 份一律 `kernelUnknown`，
**只允许手动选择、绝不参与自动匹配** —— 载荷靠编译期常量寻址内核符号，拿错一份就是提权失败。

### 修掉的两处「基线 ABI 冲突」误报（4.0.0 发布前）

用户实测 6.1.145 的 boot.img 时被拒，提示「基线 ABI 档位是 GKI 6.6，而 boot.img 是 6.1」。
根因有**两层**，都是"两套东西对不上"：

| | 症状 | 根因 | 修法 |
|---|---|---|---|
| 第 1 层 | **两张表** | 路由查 `BaselineRegistry.allEntries`（103 档），打包却查 `BaselineProfiles.byId`（只有 2 档 6.6）→ 查不到就兜底回落 PD2520 | 统一到 `BaselineRegistry.byId` / `findByBytes` |
| 第 2 层 | **两套词汇** | 上游档的 `abi.kernelSeries` 填的是**三段小版本**（`6.6.118`），闸门比的却是**两段大系列**（`6.6`）→ **50 档一档都构建不出来** | 拆开两个语义：路由键保留三段，ABI 比较键压成两段 |

顺带修掉同源的第 3 处：`lookup()` / `coverageReport()` / `availableLabels()` 也只查那 2 档，
导致**报缺文案会在明明有档位时说"没有基线"**。

并且**不再兜底猜系列**：认不出基础 `.so` 就如实报缺并停止打包，
而不是"取第一档顶上"—— 那等于拿 6.6 的旧值去打 6.1 的库。

### 其它

- 删除孤儿库 `libgl_universal.so`（全工程 0 引用）与重复载荷 `libksu_vivo_sixmodels_a.so`
- 单测 **304 条**（3.2.0 为 154 条），debug / release 双变体编译通过

---

## 参考了 GhostLock 的哪些源码

**上游仓库**：[`YuKongA/ghostlock-app`](https://github.com/YuKongA/ghostlock-app)（**Apache-2.0**）。
本项目**只取方法与数据，不复制其 C 代码**；其许可证随包携带于
`app/src/main/assets/GL_LICENSE_Apache2.txt`。

### 逐文件引用清单

| 上游路径 | 取用了什么 | 落在本项目哪里 |
|---|---|---|
| `src/kernels/<内核串>/offsets.h` | **50 个内核**各自的 9 个符号地址 | `GhostLockKernelOffsets.KERNELS`（50 档 × 9 符号） |
| `src/kernels/offsets.h` | **3 个结构体族**（6_1 / 6_6 / 6_12）的字段偏移 | `GhostLockKernelOffsets.STRUCT_FAMILIES` |
| `src/core/main.c` | `_RSO(field, fallback)` **覆盖模型**：运行时值 > 编译期基线 > 载荷兜底常量 | `UnifiedOffsetResolver`（三级优先级 + 逐字段来源标注） |
| `src/core/offsets_json.c` / `.h` | `offsets.json` 的读写与字段命名 | `GhostLockOffsets`（schema）· `GhostLockOffsetsIo`（读写合并）· `MiniJsonCodec` |
| `src/core/runtime_struct_offsets.h` | 运行时结构体偏移的键名 | `SymbolKeyMapping`（上游 9 键 ↔ 我方 11 键，一对多映射） |
| `src/core/target.h` | 目标描述形态 | `BaselineProfile` / `AbiProfile` 的字段对照 |
| `buildSrc/.../GenerateSupportedKernelsTask.kt` | 内核清单由**扫描源码树生成**的做法 | `GhostLockKernelCatalog`（50 条内核串 + 出处标记） |

### 反向核对：它的数据经我们独立验证过（4 处零差异）

不是"抄了就信"。三族结构体偏移与手写档全部拿**独立来源**对过：

| 上游数据 | 独立佐证 | 结果 |
|---|---|---|
| `STRUCT_OFFSETS_6_6` | `frankel-CP2A.260605.012/target.h` | 一致 |
| `STRUCT_OFFSETS_6_12` | `pyyyc` 荣耀 YLP-W00 **6.12.38 BTF 实测** `offsets.json` | 15 字段 **0 差异** |
| 编译期兜底常量（11 项） | 荣耀 `struct_fields` | 一致 |
| `STRUCT_OFFSETS_6_1` | `tokay-CP2A.260605.012/target.h` | 一致（`pi_lock=0x924`、`compact_waiter=1`） |
| 手写档 `PD2520` | `boxiaolanya2008` `PD2520-BP2A.250605.031.A3/target.h` | **28/28 键 0 差异** |

### 明确**没有**引用（高风险，拒绝照搬）

- **`libextract.so`**（3.88 MB Rust 黑盒）：它的 kallsyms 提取口径我方没审过，
  且**具备 LZ4 重压缩回写 boot.img 的能力**。在没验证其提取结果与我方口径一致之前，
  引入它等于把校验权外包 —— **不得用于生成可刷写产物**。
- **`libghostlock.so` 的运行时架构**：它是"1 份通用载荷 + 运行时读 json"，
  与我方"编译期常量写进 `.so`"根本不同。我们只采用它的**格式与覆盖模型**，
  主线仍是**直接写入 `.so`**（本项目主打方案）。

---

## 功能亮点

### 提权与载荷

- **一键提权**：基于 CVE-2026-43499 内核漏洞完成提权并安装 KernelSU，无需解锁 Bootloader
- **免 ADB**：**内核 6.1 / 6.6 / 6.12 三族**可直接在设备上完成提权，不需要电脑、不需要 ADB；更早的内核才需要 Shizuku（ADB）授权
- **两种载荷来源，主页自由切换**（切换即时生效，无需重启）：
  - **内置厂商载荷** —— 随包携带 **122 条**登记（126 个 `.so`），**完全离线**，按设备型号 + 内核版本 + 内核 commit 自动匹配
  - **自定义导入 / 构建产物** —— 导入任意 `.so`（ELF 魔数校验、256MB 上限、SHA-256 指纹），随时移除
- **双执行模式**：默认原生执行，可选 Shizuku 模式

### 载荷构建（新增页面）

把「换机型就要重新编译」这件事搬到手机上：

```
本机 boot.img  ──►  内核符号表（kallsyms）  ──►  这条链需要的偏移  ──►  打好补丁的动态库
```

- 点「开始构建」会先问**用哪套方案**：
  1. **通用方案（推荐）** —— IonStack 上游分支，不带厂商适配，适用于大部分 GKI 设备
  2. **vivo / iQOO（vr.ko 反 su 绕过）** —— 额外做厂商反 root 绕过，蓝厂机型必须选它
- **三级路由选档**：完整内核串 → 小版本 → 大系列，**每一级都要求精确相等**，
  认不出来就如实报缺，**绝不拿邻近版本顶替**
- 产物**自动**做两件事：写进 `/storage/emulated/0/Download/`（不需要任何存储权限），并写入「自定义动态库」并切换载荷源
- 全程离线，构建日志实时上屏；界面上直接摊开"打的是哪一份库"（文件名 / 大小 / SHA-256 / 来源版本）
- 算法来自独立的纯 Kotlin 模块 `com.kernelpack`：boot.img 头 v0~v4 / 裸 Image / Image.gz、
  Linux 6.4 前后两种 kallsyms 排布、按寄存器数据流改写 `movz/movk/movn` 常量，
  **原地覆盖、不增删字节**，改完重新扫描自证（旧值残留必须为 0）

### 载荷匹配：三级判定 + 同源机型提示

同一机型往往有多个内核版本，而**内核 commit** 才是真正的区分维度。因此匹配按三级降级：

| 等级 | 判据 | 含义 |
|---|---|---|
| `Exact` | 机型 + 内核版本 + **内核 commit** 全中 | 精确匹配，直接用 |
| `SameKernel` | 机型 + 内核版本命中，commit 不同 | 大概率可用 |
| `SimilarDevice` | 仅机型命中 | 兜底，日志里会说明 |

命中后两档时安装页与载荷列表都会标注**「可能可用」**，由用户自己决定是否继续 ——
而不是默默塞一份不匹配的库、也不是直接判"不支持"。

### 界面与记录

- **液态玻璃 UI**：悬浮玻璃底栏 + 滑块（backdrop 折射/模糊）、MIUIX 卡片与分组、跟随主题的配色
- **设备信息**：设备 / 固件 / 系统 / 系统 ABI / **内核版本**（并直接给出"是否支持免 ADB 提权"的判定）
- **运行记录只记过程，不判成败**：蓝厂机型上"跑完了但没拿到 root"与"真的跑挂了"无法区分，
  因此不再显示成功/失败，只保留「进行中 / 已记录」；是否真的装上以主页 KernelSU 状态为准
- **日志**：安装页实时日志与运行记录详情都支持**一键复制**与**保存到指定目录**
- **基线覆盖面**（设置 → 内核支持）：注册表真实覆盖面 + 上游清单对账 + 偏移可信度直方图

## 支持设备

### 内核支持范围

| 系列 | 级别 | 说明 |
|---|---|---|
| **6.1** | 主线 | 有专属族基线 `libbaseline_6_1.so` |
| **6.6** | 主线 | 内置载荷主力（vivo / iQOO / 小米 / 三星 / Pixel 等） |
| **6.12** | 主线 | 有专属族基线 `libbaseline_6_12.so` |
| 5.10 / 5.15 | 测试（beta） | 需在设置里显式打开「5.x 内核支持」；布局锚点只有上游 `target.h` 一条腿 |
| 其它 6.x（6.2 / 6.5 / 6.7…） | 拒绝 | 没有专属基线，硬按主线偏移构建会打到错误的结构体字段 |

### 内置厂商载荷（122 条登记 / 126 个 `.so`）

| 来源 | 份数 | 说明 |
|---|---|---|
| vivo / iQOO | 34 | 6.6.30 / 6.6.57 / 6.6.89 / 6.6.127 分组互认 |
| 三星 Galaxy | 31 | 按 `support/targets-v3.json` 的权威机型↔内核对照登记 |
| Google Pixel | 29 | Pixel 6 ~ 11 全系 |
| 其它 | 14 | 一加 / 华硕 / 魅族等 |
| 小米 / Redmi / POCO | 6 | 按 SoC 家族分组（SM8550 / SM8650 / SM8750 / 天玑 6895） |
| OPPO | 5 | — |
| realme / 小米（其它） | 2 | — |

> 读不出内核版本的 **77 份**一律标 `kernelUnknown`：**只能手动选择，绝不参与自动匹配**。

### 其它机型

不限品牌：**内核 6.1 / 6.6 / 6.12** 即可用「载荷构建」把自己机器的 `boot.img` 解析成偏移，
打出适配本机的载荷。

## 使用说明

1. 安装 APK（minSdk 33，即 Android 13+）
2. 在主页选择载荷来源：
   - iQOO / vivo / 小米 / Redmi / POCO / 三星 / Pixel 等 → 内置厂商载荷（自动匹配，或从列表手动点选）
   - 其它机型 → 先到「载荷构建」用本机 `boot.img` 生成载荷（产物会自动进入「自定义动态库」）
3. 点击"安装"并确认，等待提权与 KernelSU 加载完成
4. 按提示安装 KernelSU Manager 管理模块

## 从源码构建

```bash
./gradlew assembleRelease
```

- JDK 21 · Android Gradle Plugin 9.2 · compileSdk 37 · NDK **30.0.16248370** · CMake 3.22.1
- 产物位于 `app/build/outputs/apk/release/`
- 签名参数从 `GRADLE_USER_HOME/gradle.properties` 读取（`KSU_ROOT_STORE_FILE` 等），不写入仓库

### 载荷构建模块（`com.kernelpack`）

载荷引擎是**独立的纯 Kotlin 模块**，不依赖 Android 框架，可以单独拿出来读：

```
app/src/main/java/com/kernelpack/
├── boot/      boot.img 头 v0~v4 / 裸 Image / Image.gz；LZ4 / zstd / gzip 解压
├── kallsyms/  Linux 6.4 前后两种 kallsyms 排布
├── patch/     SharedObjectPatcher：按寄存器数据流改写 movz/movk/movn，原地覆盖
├── resolve/   OffsetResolver：符号 → 偏移，推导值单独标注
├── model/     KernelImageAnalysis / TargetProfile / OffsetEntry
├── policy/    BuildGate（硬闸门）· KernelSchemeSelector（选线）
├── profile/   BaselineRegistry（103 档）· GhostLockKernelOffsets · GhostLockKernelCatalog
├── offsets/   GhostLock offsets.json 互通：schema / IO / 合并 / 键映射
├── offset/    OffsetProvenance：逐条偏移的来源与可信度
├── vivo/      VrKoBypass：蓝厂 vr.ko 反 root 绕过（数据层）
├── export/    target.h / offsets.json 导出
└── SymbolAlignment.kt  双向符号对齐闸门
```

对应的单测在 `app/src/test/java/com/kernelpack/`（21 个文件）。

## 已知限制（如实说明）

- **50 档上游内核 + 两份自编族基线均未在真机验证**（一律标 beta）。
  数据来源可核查，但"未实测"就是未实测。
- **6.12 基线的符号是 `CROSS_REFERENCE`**（取自荣耀 YLP-W00 6.12.38）——
  它是**别的机型**。构建时会被 boot.img 解析出的新值逐项改写，
  且 `SymbolAlignment` 保证改写不齐就阻断。结构体偏移才是编译期烤死、决定成败的那一半。
- **蓝厂 vr.ko 绕过尚未接入提权路径**：数据层（`VrKoBypass`，12 条单测）已完成，
  但"清零 `0x28-0x2f`"这一步需要在你的设备上先确认安全才敢接线。
- **`offsets.json` 导入目前是阶段 1（读取与呈现）**：`UnifiedOffsetResolver`
  的合并流水线已实现并有 10 条单测，但尚未接入构建路径。

## 致谢

- [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy) by BuSung-dev —— 本项目的应用架构（Apache-2.0）
- [ghostlock-app](https://github.com/YuKongA/ghostlock-app) by YuKongA —— **50 档内核偏移、三族结构体偏移、`offsets.json` 格式与 `_RSO` 覆盖模型**（Apache-2.0，许可证随包携带）
- [CVE-2026-43499-Neo11Plus](https://github.com/boxiaolanya2008/CVE-2026-43499-Neo11Plus) by boxiaolanya2008 —— 内置动态库、`PD2520` 基线与 6.1 族载荷源码
- [CVE-2026-43499-so-build](https://github.com/ctnBobong32/CVE-2026-43499-so-build) by ctnBobong32 —— `tokay` 6.1 族 `target.h`（25 键齐全）
- [Root-My-Galaxy-Payloads](https://github.com/BuSung-dev/Root-My-Galaxy-Payloads) · [Root-My-Pixel](https://github.com/alex193a/Root-My-Pixel) —— 三星 / Pixel 机型↔内核对照
- [IonStack / CyberMeowfia](https://github.com/NebuSec/CyberMeowfia) by NebuSec —— 通用方案载荷的上游源码
- [KernelSU](https://github.com/tiann/KernelSU) —— 内核级 root 方案
- [miuix](https://github.com/compose-miuix-ui/miuix) · [Backdrop](https://github.com/Kyant0/Backdrop) —— MIUIX 组件与液态玻璃效果
- CVE-2026-43499（GhostLock）漏洞研究

## 免责声明

本项目仅用于安全研究与学习目的。提权操作可能导致设备保修失效、数据丢失或设备损坏，
请仅在自有且受控的设备上使用，风险自负。

## 许可证

[Apache License 2.0](LICENSE)

---

## English Summary

KSuRoot is a one-click KernelSU rooting tool built on the **CVE-2026-43499 (GhostLock)**
kernel vulnerability. This branch syncs [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy)
v0.2.6 and adds a **payload builder**, **bundled vendor payloads** and a liquid-glass UI.

Version **4.0.0**. Highlights:

- **Three struct families: 6.1 / 6.6 / 6.12.** Not inferred from version numbers — each family has its
  own baseline library (`libbaseline_6_1.so`, `libionstack.so` / `libbs.so`, `libbaseline_6_12.so`),
  because struct offsets are **baked in at compile time and cannot be patched**.
- **103 registered baseline entries** (51 universal + 52 vivo), including **50 kernel adaptations
  taken from [ghostlock-app](https://github.com/YuKongA/ghostlock-app)** — data and method only,
  its C code is not copied (Apache-2.0, license shipped in `app/src/main/assets/`).
  All 50 are marked `UPSTREAM` + `beta`: the data is real, but **we have not verified it on hardware**.
- **Bidirectional symbol-alignment gate**: patching can only rewrite literals already present in the
  baseline `.so`, so both sides must line up — otherwise the build is **blocked** instead of silently
  shipping stale constants for the wrong kernel.
- **`offsets.json` interoperability** with GhostLock: read / write / merge, round-trip safe for unknown keys.
- **122 bundled vendor payloads** (126 `.so`), fully offline, matched by model + kernel version + commit
  with a three-tier fallback that labels the weaker tiers as "may work" so you decide.
- **304 unit tests**, debug + release both compile.

For research and educational use only. Licensed under Apache-2.0.
