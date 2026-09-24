# KSuRoot

> ⚠️ **温馨提示**：蓝厂机型调度策略较为严格，提权时子进程易被系统回收。请先在「开发者选项」中开启「停止限制子进程」后再执行提权，成功率更高。

基于 **CVE-2026-43499（GhostLock）** 内核漏洞的一键 KernelSU 提权工具。

本分支以 KSuRoot 为蓝本，完整同步 [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy) v0.2.6 主线更新，并在此基础上增加了**载荷构建**、**厂商载荷内置**与一整套液态玻璃 UI。

> Mod by **hmascs** · 版本 **3.1.1** · Apache-2.0
> 仓库：<https://github.com/hmascs/KSuRoot>

---

## 功能亮点

### 提权与载荷

- **一键提权**：基于 CVE-2026-43499 内核漏洞完成提权并安装 KernelSU，无需解锁 Bootloader
- **免 ADB**：**内核 6.6 及以上**（6.6 / 6.12 两系均已验证，6.7~6.11 同理）可直接在设备上完成提权，不需要电脑、不需要 ADB；低于 6.6 的内核才需要 Shizuku（ADB）授权
- **两种载荷来源，主页自由切换**（切换即时生效，无需重启）：
  - **内置厂商载荷** —— 随包携带 vivo / iQOO 与小米各机型 × 各内核版本的载荷，**完全离线**，按设备型号 + 内核版本 + 内核 commit 自动匹配
  - **自定义导入 / 构建产物** —— 导入任意 `.so`（ELF 魔数校验、256MB 上限、SHA-256 指纹），随时移除
- **双执行模式**：默认原生执行，可选 Shizuku 模式
- **提权过程增量读取日志**：载荷输出边写边读，不再整文件反复重读；看门狗按 500ms 节奏判定而非每 250ms 一次

### 载荷构建（新增页面）

把「换机型就要重新编译」这件事搬到手机上：

```
本机 boot.img  ──►  内核符号表（kallsyms）  ──►  这条链需要的偏移  ──►  打好补丁的动态库
```

- 点「开始构建」会先问**用哪套方案**：
  1. **通用方案（推荐）** —— IonStack 上游分支，不带厂商适配，适用于大部分 GKI 6.6+ 设备
  2. **vivo / iQOO（vr.ko 反 su 绕过）** —— 额外做厂商反 root 绕过，蓝厂机型必须选它
- 产物**自动**做两件事：写进 `/storage/emulated/0/Download/`（不需要任何存储权限），并写入「自定义动态库」并切换载荷源
- 全程离线，构建日志实时上屏；界面上直接摊开"打的是哪一份库"（文件名 / 大小 / SHA-256 / 来源版本）
- 算法来自独立的纯 Kotlin 模块 `com.kernelpack`：boot.img 头 v0~v4 / 裸 Image / Image.gz、Linux 6.4 前后两种 kallsyms 排布、按寄存器数据流改写 `movz/movk/movn` 常量，**原地覆盖、不增删字节**，改完重新扫描自证（旧值残留必须为 0）

### 载荷匹配：三级判定 + 同源机型提示

同一机型往往有多个内核版本，而**内核 commit** 才是真正的区分维度（同一版本的两次编译，符号偏移可能不同）。因此匹配按三级降级：

| 等级 | 判据 | 含义 |
|---|---|---|
| `Exact` | 机型 + 内核版本 + **内核 commit** 全中 | 精确匹配，直接用 |
| `SameKernel` | 机型 + 内核版本命中，commit 不同 | 大概率可用 |
| `SimilarDevice` | 仅机型命中 | 兜底，日志里会说明 |

命中后两档时安装页与载荷列表都会标注**「可能可用」**，由用户自己决定是否继续 —— 而不是默默塞一份不匹配的库、也不是直接判"不支持"。

> **特别说明**：iQOO Neo10 Pro+、iQOO 11（与 Neo11 同一份构建）、iQOO 13 的 **6.6.89** 内核版本，一律使用随包内置的 all-in-one 库 `libbs.so`，不走上述机型/版本匹配。

### 同厂商 + 同平台机型可互相通用

同一厂商、同一 SoC 平台的机型，内核符号布局高度一致，载荷基本上可以直接换用。列表里对这类条目做了分组标注，方便判断"我这台会不会也能用"：

- **vivo / iQOO（高通）**：Neo10 Pro+、Neo11、iQOO 13、Neo10 Pro、Z10 Turbo+、X200、X200S、Pad5 Pro 之间按 `6.6.30` / `6.6.57` / `6.6.89` / `6.6.127` 分组互认
- **小米（高通 / 联发科）**：按 SoC 家族分组 —— SM8550 / SM8650 / SM8750 / 天玑 6895 各自成套，同组内跨机型通用

> 这些条目在列表中标为「可能可用」，属于**经验性适配**而非官方保证，能否成功仍需实测。

### 打包与权限（3.0.1 修掉的两个卡点）

「内置动态库不可用」其实是**三层**原因叠在一起，前两层是隐患，第三层才是真正卡住安装的那一步：

1. **版本**：内置 all-in-one 库为上游 **v1.3.0**（`libbs.so`，176544 字节，SHA-256 `8c3410cb…95d9`）
2. **打包被 strip**：AGP 默认会 strip `jniLibs` 里的 `.so`（实测 162328 → 142848 字节），预编译载荷被隐式改写；现已用 `keepDebugSymbols` 保住原件，并加了 `**/libksu_*.so` 通配保护，避免逐条枚举漏项
3. **一行多余的 `chmod`（真凶）**：内置载荷住在 `applicationInfo.nativeLibraryDir`，那里的文件是安装器**以 system 身份**提取的：

   ```
   -rwxr-xr-x system system 176544 libbs.so
   ```

   属主是 `system` 而非应用自己 → 应用执行 `Os.chmod` 直接 `EACCES`，而旧代码没兜异常，安装链在打印完「使用内置动态库」后立刻断掉：
   `[-] chmod failed: EACCES (Permission denied)` → 安装失败。这行自 2.2.0 起就在，而它本来就 `r-xr-xr-x`（载荷只被 LD_PRELOAD 映射，不需要额外权限），所以那次 chmod 纯属多余。自定义载荷之所以没事，是因为那份文件由应用自己写入私有目录、属主是自己。

   现由 `PayloadStaging` 统一处理：**已经够用就一次 chmod 都不做**；不够用才就地 chmod，再不行复制到应用私有目录补权限。

### 界面与记录

- **液态玻璃 UI**：悬浮玻璃底栏 + 滑块（backdrop 折射/模糊）、MIUIX 卡片与分组、跟随主题的配色；统一的圆角阶梯（12 / 16 / 24 / 32dp）与排版尺度
- **设备信息**：设备 / 固件 / 系统 / 系统 ABI / **内核版本**（并直接给出"是否支持免 ADB 提权"的判定）
- **运行记录只记过程，不判成败**：蓝厂机型上"跑完了但没拿到 root"与"真的跑挂了"无法区分，因此不再显示成功/失败，只保留「进行中 / 已记录」；是否真的装上以主页 KernelSU 状态为准
- **日志**：安装页实时日志与运行记录详情都支持**一键复制**与**保存到指定目录**
- **内核门槛提示**：内核低于 6.6 时按安装会先提示"免 ADB 走不通，请在设置中打开 Shizuku 授权"，确认按钮带 3 秒倒计时

## 支持设备

### 内置厂商载荷

随包携带 **39 份**去重后的载荷（源包 47 份，按 SHA-256 去重）。

**vivo / iQOO**

| 机型 | 覆盖内核版本 |
|---|---|
| iQOO Neo10 Pro+ | 6.6.57 / **6.6.89（走 `libbs.so`）** |
| iQOO Neo11 / iQOO 11 | **6.6.89（走 `libbs.so`，两者同一份构建）** |
| iQOO 13 | 6.6.30 / 6.6.57 / **6.6.89（走 `libbs.so`）** |
| iQOO Neo10 Pro | 6.6.30 / 6.6.57 / 6.6.89 |
| iQOO Z10 Turbo+ | 6.6.57 / 6.6.89 |
| vivo X200 | 6.6.30 / 6.6.57 / 6.6.89 |
| vivo X200S | 6.6.30 / 6.6.57 / 6.6.89 |
| vivo Pad5 Pro | 6.6.127 / 6.6.30 |
| iQOO 12 (A15) | 不限版本 |

> iQOO Neo10 Pro（无 `+`）为天玑 9400，与 Neo10 Pro+ **不是**同一平台，各自的载荷不可互换。

**小米 / Redmi / POCO**（按 SoC 家族分组，同组内跨机型通用）

| 分组 | 载荷 |
|---|---|
| SM8550 | `libksu_mi_sm8550_a.so` |
| SM8650 | `libksu_mi_sm8650_a.so` |
| SM8750 | `libksu_mi_sm8750_a.so` |
| Redmi K70 Ultra (OS3) | `libksu_mi_k70u_os3.so` |
| 8Gen2 六机型通用 | `libksu_mi_8g2_6m.so` |
| 天玑 6895 | `libksu_mi_mt6895_43499.so` |

> 小米系列载荷均为**手动选择**（不做自动匹配）—— 同 SoC 跨机型的可用性需要用户自己判断，列表中标为「可能可用」。

### 其它机型

不限品牌：**内核 6.6 及以上**即可用「载荷构建」把自己机器的 `boot.img` 解析成偏移，打出适配本机的通用方案载荷。

## 使用说明

1. 安装 APK（minSdk 33，即 Android 13+）
2. 在主页选择载荷来源：
   - iQOO / vivo / 小米 / Redmi / POCO → 内置厂商载荷（自动匹配，或从列表手动点选）
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
- 推送 `main` 会自动触发 GitHub Actions 构建并上传 APK 产物（见 `.github/workflows/build.yml`）

## 致谢

- [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy) by BuSung-dev —— 本项目的应用架构（Apache-2.0）
- [CVE-2026-43499-Neo11Plus](https://github.com/boxiaolanya2008/CVE-2026-43499-Neo11Plus) by boxiaolanya2008 —— 内置动态库与 `target.h` 基线
- [IonStack / CyberMeowfia](https://github.com/NebuSec/CyberMeowfia) by NebuSec —— 通用方案载荷的上游源码
- [KernelSU](https://github.com/tiann/KernelSU) —— 内核级 root 方案
- [miuix](https://github.com/compose-miuix-ui/miuix) · [Backdrop](https://github.com/Kyant0/Backdrop) —— MIUIX 组件与液态玻璃效果
- CVE-2026-43499（GhostLock）漏洞研究

## 免责声明

本项目仅用于安全研究与学习目的。提权操作可能导致设备保修失效、数据丢失或设备损坏，请仅在自有且受控的设备上使用，风险自负。

## 许可证

[Apache License 2.0](LICENSE)

---

## English Summary

KSuRoot is a one-click KernelSU rooting tool built on the **CVE-2026-43499 (GhostLock)** kernel vulnerability. This branch syncs [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy) v0.2.6 and adds a **payload builder**, **39 bundled vendor payloads** and a liquid-glass UI.

Version **3.1.1**. Highlights:

- **No ADB needed on kernel 6.6+** (the 6.6 and 6.12 series are verified, 6.7–6.11 likewise); kernels below 6.6 fall back to a Shizuku (ADB) authorization, and the app says so before you install.
- **Bundled vendor payloads, fully offline**: vivo / iQOO and Xiaomi payloads for each device × kernel version, matched automatically by model + kernel version + kernel commit, with a three-tier fallback (`Exact` / `SameKernel` / `SimilarDevice`) that labels the latter two as "may work" so you decide.
- **Same vendor + same SoC devices are interchangeable**: grouped by SoC family (SM8550 / SM8650 / SM8750 / Dimensity 6895, and the vivo `6.6.x` families) and marked "may work" in the list.
- **iQOO Neo10 Pro+, iQOO 11 (same build as Neo11) and iQOO 13 on 6.6.89** always use the bundled all-in-one `libbs.so` instead of version matching.
- **Payload builder page**: it reads the kernel symbol table straight out of your `boot.img`, resolves the offsets this chain needs, and rewrites the chosen base library into a build matching your kernel. Two schemes: *Universal* (IonStack upstream, most devices) and *vivo / iQOO* (adds the `vr.ko` anti-root bypass). Output is written to `Download/` and registered as the custom payload automatically — everything on-device, no network.
- **Incremental log tailing during escalation**: the exploit log is read by offset instead of re-reading the whole file every 250ms, and history writes are coalesced rather than `fsync`-ed per log line.
- **History records the process, it does not judge** success or failure (that verdict is meaningless on vivo/iQOO); logs can be copied to the clipboard or saved to any directory.
- Liquid-glass floating navigation bar, MIUIX cards, kernel check row, and multi-language UI.

For research and educational use only. Licensed under Apache-2.0.
