# KSuRoot

> ⚠️ **温馨提示**：蓝厂机型调度策略较为严格，提权时子进程易被系统回收。请先在「开发者选项」中开启「停止限制子进程」后再执行提权，成功率更高。

基于 **CVE-2026-43499（GhostLock）** 内核漏洞的一键 KernelSU 提权工具。

本分支以 KSuRoot 为蓝本，完整同步 [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy) v0.2.6 主线更新，并在此基础上增加了**载荷构建**、**内置动态库修复**与一整套液态玻璃 UI。

> Mod by **hmascs** · 版本 **3.0.2**（versionCode 302）· Apache-2.0
> 仓库：<https://github.com/hmascs/KSuRoot>

---

## 功能亮点

### 提权与载荷

- **一键提权**：基于 CVE-2026-43499 内核漏洞完成提权并安装 KernelSU，无需解锁 Bootloader
- **免 ADB**：**内核 6.6 及以上**（6.6 / 6.12 两系均已验证，6.7~6.11 同理）可直接在设备上完成提权，不需要电脑、不需要 ADB；低于 6.6 的内核才需要 Shizuku（ADB）授权
- **三种载荷来源，主页自由切换**（切换即时生效，无需重启）：
  - **官方在线源** —— 按设备型号与内核版本自动匹配下载，支持三星机型
  - **内置动态库** —— 分支内置一体化提权载荷，离线可用，支持 iQOO 骁龙 8 至尊版机型
  - **自定义导入 / 构建产物** —— 导入任意 `.so`（ELF 魔数校验、256MB 上限、SHA-256 指纹），随时移除
- **双执行模式**：默认原生执行，可选 Shizuku 模式

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

### 内置动态库（3.0.1 修掉了真正的卡点）

「内置动态库不可用」其实是**三层**原因叠在一起，前两层是隐患，第三层才是真正卡住安装的那一步：

1. **版本**：内置的是上游 **v1.3.0**（`preload.so`，176544 字节，SHA-256 `8c3410cb…95d9`）
2. **打包被 strip**：AGP 默认会 strip `jniLibs` 里的 `.so`（实测 `libbs.so` 162328 → 142848 字节），预编译载荷被隐式改写；现已用 `keepDebugSymbols` 保住原件
3. **一行多余的 `chmod`（真凶）**：内置载荷住在 `applicationInfo.nativeLibraryDir`，那里的文件是安装器**以 system 身份**提取的：

   ```
   -rwxr-xr-x system system 162328 libbs.so
   ```

   属主是 `system` 而非应用自己 → 应用执行 `Os.chmod` 直接 `EACCES`，而旧代码没兜异常，安装链在打印完「使用内置动态库：libbs.so」后立刻断掉：
   `[-] chmod failed: EACCES (Permission denied)` → 安装失败。**这行自 2.2.0 起就在**，而它本来就 `r-xr-xr-x`（载荷只被 LD_PRELOAD 映射，不需要额外权限），所以那次 chmod 纯属多余。自定义载荷之所以没事，是因为那份文件由应用自己写入私有目录、属主是自己。

   现由 `PayloadStaging` 统一处理：**已经够用就一次 chmod 都不做**；不够用才就地 chmod，再不行复制到应用私有目录补权限。

### 界面与记录

- **液态玻璃 UI**：悬浮玻璃底栏 + 滑块（backdrop 折射/模糊）、MIUIX 卡片与分组、跟随主题的配色；卡片与页面按 miuix 语义色分层（页面 `surface`、卡片 `surfaceContainer`）
- **设备信息**：设备 / 固件 / 系统 / 系统 ABI / **内核版本**（并直接给出"是否支持免 ADB 提权"的判定）
- **运行记录只记过程，不判成败**：蓝厂机型上"跑完了但没拿到 root"与"真的跑挂了"无法区分，因此不再显示成功/失败，只保留「进行中 / 已记录」；是否真的装上以主页 KernelSU 状态为准
- **日志**：安装页实时日志与运行记录详情都支持**一键复制**与**保存到指定目录**
- **内核门槛提示**：内核低于 6.6 时按安装会先提示"免 ADB 走不通，请在设置中打开 Shizuku 授权"，确认按钮带 3 秒倒计时

## 支持设备

### 官方在线源（三星，数据同步自官方载荷清单）

| 机型 | 型号代码 | 内核版本 |
|---|---|---|
| Galaxy S25 | SM-S931x / SC-51F / SCG31 | 6.6.98 |
| Galaxy S25+ | SM-S936x | 6.6.98 |
| Galaxy S25 Edge | SM-S937x（含国行 S9370） | 6.6.98 |
| Galaxy S25 Ultra | SM-S938x / SC-52F / SCG32 | 6.6.98 |
| Galaxy Z Fold 7 | SM-F966U/U1 | 6.6.98 |
| Galaxy S24 Ultra | SM-S928U | 6.1.145 |
| Galaxy S24+ | SM-S926B | 6.1.157 |
| Galaxy S24 | SM-S921B/N | 6.1.157 |
| Galaxy S23 Ultra | SM-S918x | 5.15.189 |
| Galaxy A56 5G | SM-A566x | 6.6.102 |
| Galaxy A36 5G | SM-A366W | 6.6.46 |

> 内核版本必须完全一致才可匹配。清单实时更新，以 [Root-My-Galaxy-Payloads](https://github.com/BuSung-dev/Root-My-Galaxy-Payloads) 为准。

### 内置动态库（iQOO · 骁龙 8 至尊版）

| 机型 | 平台 |
|---|---|
| iQOO Neo 10 Pro+ | 骁龙 8 至尊版 |
| iQOO Neo 11 | 骁龙 8 至尊版 |
| iQOO 13 | 骁龙 8 至尊版 |

> 注意：iQOO Neo 10 Pro（无 +）为天玑 9400，**不受支持**。

### 其它机型

不限品牌：**内核 6.6 及以上**即可用「载荷构建」把自己机器的 `boot.img` 解析成偏移，打出适配本机的通用方案载荷。

## 使用说明

1. 安装 APK（minSdk 33，即 Android 13+）
2. 在主页选择载荷来源：
   - 三星机型 → 官方在线源
   - iQOO / vivo → 内置动态库
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

- [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy) by BuSung-dev —— 本项目的应用架构与在线载荷体系（Apache-2.0）
- [CVE-2026-43499-Neo11Plus](https://github.com/boxiaolanya2008/CVE-2026-43499-Neo11Plus) by boxiaolanya2008 —— 内置动态库（v1.0.0）与 `target.h` 基线
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

KSuRoot is a one-click KernelSU rooting tool built on the **CVE-2026-43499 (GhostLock)** kernel vulnerability. This branch syncs [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy) v0.2.6 and adds a **payload builder**, a fixed bundled payload and a liquid-glass UI.

Version **3.0**. Highlights:

- **No ADB needed on kernel 6.6+** (the 6.6 and 6.12 series are verified, 6.7–6.11 likewise); kernels below 6.6 fall back to a Shizuku (ADB) authorization, and the app says so before you install.
- **Payload builder page**: it reads the kernel symbol table straight out of your `boot.img`, resolves the offsets this chain needs, and rewrites the chosen base library into a build matching your kernel. Two schemes: *Universal* (IonStack upstream, most devices) and *vivo / iQOO* (adds the `vr.ko` anti-root bypass). Output is written to `Download/` and registered as the custom payload automatically — everything on-device, no network.
- **Bundled payload pinned to release v1.0.0** (slow but stable) and protected from AGP stripping, so what ships is byte-identical to the upstream artifact.
- **History records the process, it does not judge** success or failure (that verdict is meaningless on vivo/iQOO); logs can be copied to the clipboard or saved to any directory.
- Liquid-glass floating navigation bar, MIUIX cards, kernel check row, and multi-language UI.

For research and educational use only. Licensed under Apache-2.0.
