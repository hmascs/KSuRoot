# KSuRoot

基于 **CVE-2026-43499（GhostLock）** 内核漏洞的一键 KernelSU 提权工具。

本分支以 KSURoot 为蓝本，完整同步 [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy) v0.2.6 主线更新，并新增**自定义导入动态库**功能。

> Mod by **hmascs** · 版本 2.2.0（versionCode 220）· Apache-2.0

---

## 功能亮点

- **一键提权**：基于 CVE-2026-43499 内核漏洞完成提权并安装 KernelSU，无需解锁 Bootloader
- **主线同步**：对齐 Root-My-Galaxy v0.2.6 —— Jetpack Compose 界面、在线设备清单（schema v3）自动匹配、安装历史记录、主题与多语言
- **三种载荷来源，主页自由切换**（切换即时生效，无需重启）：
  - **官方在线源** —— 按设备型号与内核版本自动匹配，从 Root-My-Galaxy-Payloads 仓库下载，支持三星机型
  - **内置动态库（libbs.so）** —— 分支内置一体化提权载荷，离线可用，支持 iQOO 骁龙 8 至尊版机型
  - **自定义导入** —— 从本地导入任意 `.so` 载荷（ELF 魔数校验、256MB 大小限制、SHA-256 指纹记录），随时移除
- **支持机型详情页**：两种来源均内置支持机型列表（型号代码 + 内核版本）
- **双执行模式**：默认原生执行，可选 Shizuku 模式
- **安装确认文案随来源动态变化**，本地载荷全程不联网

## 支持设备

### 官方在线源（三星，数据同步自官方载荷清单，2026 年 8 月）

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

## 使用说明

1. 安装 APK（minSdk 33，即 Android 13+）
2. 在主页选择载荷来源（默认官方在线源；iQOO 用户选内置动态库，也可导入自定义库）
3. 点击"安装"并确认，等待提权与 KernelSU 加载完成
4. 按提示安装 KernelSU Manager 管理模块

## 从源码构建

```bash
./gradlew assembleDebug
```

- JDK 21 · Android Gradle Plugin 9.2 · NDK 28.2.13676358
- 产物位于 `app/build/outputs/apk/debug/`

## 致谢

- [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy) by BuSung-dev —— 本项目的应用架构与在线载荷体系（Apache-2.0）
- [KernelSU](https://github.com/tiann/KernelSU) —— 内核级 root 方案
- CVE-2026-43499（GhostLock）漏洞研究

## 免责声明

本项目仅用于安全研究与学习目的。提权操作可能导致设备保修失效、数据丢失或设备损坏，请仅在自有且受控的设备上使用，风险自负。

## 许可证

[Apache License 2.0](LICENSE)

---

## English Summary

KSuRoot is a one-click KernelSU rooting tool based on the **CVE-2026-43499 (GhostLock)** kernel vulnerability. This branch syncs all updates from [Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy) v0.2.6 and adds **custom payload (.so) import** on the home page. Three payload sources are available: the official online feed (Samsung devices), the bundled `libbs.so` (iQOO Snapdragon 8 Elite devices: Neo 10 Pro+, Neo 11, iQOO 13), and user-imported libraries with ELF validation and SHA-256 fingerprinting. For research and educational use only. Licensed under Apache-2.0.
