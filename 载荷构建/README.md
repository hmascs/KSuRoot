# 载荷构建配方（`libbaseline_6_1.so` / `libbaseline_6_12.so`）

本目录是 KSuRoot 那**两份自编族基线**的**可重放构建配方**。

它存在的理由很具体：这两份 `.so` 原本是**在别处编好、只把产物拷进来**的，
仓库里没有配方、没有源清单、没有编译命令 —— 于是没人能回答
"它到底是怎么编出来的"，更没人能把它重编一遍来加东西。
本目录把这件事补齐：**照着这里跑一遍，能得到逐字节同源（同尺寸、同特征）的产物。**

> ⚠️ 先说清楚**没有**做的事：**没有任何真机验证**。
> 下面所有结论都来自源码阅读、二进制审计与编译期宏核对，没有一条来自跑过的设备。

> ⚠️ **随仓时只带了「判据」那一半，没带源码树。**
> `bash build_baselines.sh` 需要 `bsrc/exploit/src`（约 2.2 MB，
> 自上游 `boxiaolanya2008/CVE-2026-43499-Neo11Plus` 派生的 exploit C 源码）——
> **那份不在本仓库里**，所以照原样直接跑会停在"找不到载荷源码"。
> 随仓的是**能把产物验伪的那一半**：
>
> | 随仓 | 作用 |
> |---|---|
> | `targets/baseline-6.1-tokay/target.h` | ★ `VR_TAG_A_OFF = 0x04` 的声明处（曾经的缺陷就在这一格） |
> | `targets/baseline-6.12-gki/target.h` | ★ `VR_TAG_A_OFF = 0x06` + `TARGET_ASHMEM_FOPS_UNSYMBOLED` |
> | `targets/expected_vr_tags.tsv` | ★ **独立真值表**（真机实测值，不是从 `target.h` 抄的） |
> | `targets/*/root.c` | `patch_task_vr_tag()` 全貌（两份逐字节相同） |
> | `build_baselines.sh` · `build_in_container.sh` · `Makefile` | 流程本身；`Makefile` 逐字节等于上游 |
>
> 缺的那份源码可以按 §2 的出处表重建，也可以直接拿本目录的 `target.h` 去对
> `app/src/main/jniLibs/arm64-v8a/libbaseline_*.so` 的反汇编 —— 后者不需要源码。

---

## 1. 一句话

```
bash build_baselines.sh          # 在 Ubuntu chroot 里跑
```

产物：

| 目标 | 输出 | 装到哪 |
|---|---|---|
| `baseline-6.1-tokay` | `build/baseline-6.1-tokay/bin/preload.so` | `app/src/main/jniLibs/arm64-v8a/libbaseline_6_1.so` |
| `baseline-6.12-gki` | `build/baseline-6.12-gki/bin/preload.so` | `app/src/main/jniLibs/arm64-v8a/libbaseline_6_12.so` |

当前已装进仓库的两份产物：

| 文件 | 大小 | sha256（前 16） | `strings -a \| grep -c "vr detag"` |
|---|---|---|---|
| `libbaseline_6_1.so` | 175776 B | `835f25ff3f27fee4` | **2** |
| `libbaseline_6_12.so` | 175760 B | `b247909fed6a0e80` | **2** |

---

## 2. 这套配方是怎么被"还原"出来的

原始配方丢了，所以它是**重建**的，每一块都有出处。**出处等级**标注如下：

| 等级 | 含义 |
|---|---|
| `MEASURED` | 在本机实测得到（读二进制 / 跑编译器 / 对宏） |
| `UPSTREAM` | 从上游仓库原样取得，附 URL |
| `CROSS_REFERENCE` | 由多份产物交叉比对推得 |
| `PLACEHOLDER` | 占位，**未经证实** —— 本目录里没有这一级，有的话会显式标出来 |

| 配方成分 | 内容 | 出处 |
|---|---|---|
| `Makefile` | 上游 `exploit/Makefile`，**逐字节原样** | `UPSTREAM` · `https://raw.githubusercontent.com/boxiaolanya2008/CVE-2026-43499-Neo11Plus/main/exploit/Makefile` |
| 源码树 | `bsrc/exploit/src`（`main.c util.c slide.c fops.c pipe.c root.c posture.c preload.c io_daemon.c su_blob.S wallpaper_blob.S`） | `MEASURED`（旧产物 `.comment` 与符号表） |
| 编译器 | NDK **r30** `30.0.16248370`（clang 21.0.0 + LLD 21.0.0），`API=35` | `MEASURED`（见 §4） |
| `targets/baseline-6.1-tokay/target.h` | `targets_6_1/tokay.h` + 末尾 VR 块 | `CROSS_REFERENCE` |
| `targets/baseline-6.12-gki/target.h` | `target_6_12.h` + 末尾 VR 块 | `CROSS_REFERENCE` |
| `targets/*/root.c` | `bsrc/exploit/src/targets/PD2520-BP2A.250605.031.A3/root.c`（758 行，带 `patch_task_vr_tag`），**逐字节一致** | `MEASURED`（`diff` 为空） |
| `assets/wallpaper.webp` | 23372 B，从 `libbs.so` 的 PT_LOAD 映射里 `vaddr 0xe540..0x1408c` 抽出来的 | `MEASURED` |

### 2.1 为什么 `target.h` 用这两份，而不是 `bsrc/.../tokay-CP2A.260605.012/target.h`

任务书 §6.2 提到过 `bsrc/exploit/src/targets/tokay-CP2A.260605.012/target.h`。
**实测对不上**：那份头文件是 `cred 0x820 / pid 0x618` 的布局，而仓库里
6.1 族基线用的是 `cred 0x838 / pi_lock 0x924 / tasks 0x550`。

判定依据（`MEASURED`）：`targets_6_1/tokay.h` 与旧构建现场 `/root/b6/target.h`
**逐字节相同**，`target_6_12.h` 与 `/root/b12/target.h` 同样逐字节相同 ——
也就是说这两份头就是当初编出那两份产物时用的输入。

**偏移只能来自真实产物，不能按内核版本外推。** 所以这里用实测对上的那两份。

### 2.2 编译期宏核对（`clang -dM -E`，`MEASURED`）

| 宏 | 6.1 族 | 6.12 族 |
|---|---|---|
| `TASK_CRED_OFF` | `0x838` | `0x900` |
| `TASK_PI_LOCK_OFF` | `0x924` | （该头未定义；`FAKE_TASK_PI_LOCK_OFF = 0x9EC`） |
| `TASK_TASKS_OFF` | `0x550` | `0x638` |
| `TASK_SECCOMP_OFF` | `0x8e8` | `0x9C8` |
| `TASK_THREAD_INFO_FLAGS_OFF` | `0x00` | `0x00` |
| `VR_TAG_A_OFF` | `0x06` | `0x06` |
| `VR_TAG_B_OFF` | `0x2c` | `0x2c` |
| `VR_SYSCALL_TP_FLAG` | `0x400` | `0x400` |
| `SYS_EXIT_TP_OFF` / `RVH_COMMIT_CREDS_TP_OFF` | **未定义** | **未定义** |

> `TASK_THREAD_INFO_FLAGS_OFF = 0x00` 是**和 W3 seccomp 绕过共用**的，不要动它。
> `TASK_SECCOMP_OFF` 必须**逐族**给（6.1 = `0x8e8`，6.12 = `0x9C8`），
> 不能从 PD2520 抄。

---

## 3. 重编修掉的两个缺陷

旧的两份产物有两个**互相独立**的问题。第二个是这次重编才发现的。

### 3.1 缺陷一：不带 `vr.ko` 反 root 绕过

```
$ strings -a libbaseline_6_1.so | grep -c "vr detag"     # 旧产物
0
```

蓝厂机型上这意味着：**提权成功之后**，子进程被 `vr.ko` 的 `sys_exit` tracepoint
探针杀掉 —— 看起来像"提权失败"，其实标记根本没抹。

修法：`root.c` 换成带 `patch_task_vr_tag()` 的那份（`PD2520` 的 `root.c`），
并在两份 `target.h` 末尾补上 VR 常量块。重编后 `vr detag` = **2**
（`root vr detag ok=…` 与 `root vr detag incomplete …` 两条日志）。

### 3.2 缺陷二：**这两份 `.so` 根本 load 不起来**（比缺陷一更严重）

拿旧产物查动态符号表：

```
$ readelf -sW libbaseline_6_1.so | awk '$4=="FILE"'
... aarch64.c fops.c main.c pipe.c posture.c preload.c slide.c util.c
```

**没有 `root.c`、没有 `io_daemon.c`。** 后果：

- `install_android_root`、`io_daemon_main` 是 `R_AARCH64_JUMP_SLOT` 重定位到 **UND**；
- `embedded_wallpaper_start/_end` 是 `R_AARCH64_GLOB_DAT` 重定位到 **UND**。

`dlopen` 遇到解析不了的 `GLOB_DAT` 会直接失败 —— 也就是说这份基线在真机上
**加载阶段就会挂**，压根走不到"抹不抹标记"那一步。

根因：旧构建只编了 `fops.c main.c pipe.c posture.c preload.c slide.c util.c`，
既没把 `root.c` / `io_daemon.c` 编进去，也没提供 `wallpaper_blob.S` 需要的
`assets/wallpaper.webp`（文件缺失 → `.incbin` 出来的符号指向 UND）。

修法：按上游 `Makefile` 的 `PRELOAD_SRCS` / `CORE_SRCS` 全量编译，
并补上 `assets/wallpaper.webp`（从 `libbs.so` 里抽出来，见 §2）。

重编后自证：

```
$ readelf -dW libbaseline_6_1.so | grep NEEDED
 (NEEDED)  Shared library: [libdl.so]
 (NEEDED)  Shared library: [libc.so]
$ readelf -sW libbaseline_6_1.so | awk '$7=="UND"'      # 只剩 libc/loader 的符号
```

`STT_FILE` 现在包含 `root.c` 与 `io_daemon.c`。

---

## 4. 为什么是 NDK r30（而不是 r27d）

旧产物的 `.comment` 段有三条：

```
clang version 22.0.1 ...
clang version 21.0.0 ...
LLD 21.0.0
```

本机没有 clang 22。追下去发现第一条来自 NDK **r30** 预编译的
`libclang_rt.builtins`（compiler-rt 用 clang 22 编的），而 21.0.0 / LLD 21.0.0
是 r30 自带的 clang/LLD 版本。

用 NDK r30 重编，`.comment` 的**三条一模一样**（`MEASURED`）——
所以旧产物就是用 r30 编的，本目录固定用 r30。

---

## 5. 怎么跑

```bash
# 在 Termux 宿主上（一次只跑一个 chroot 操作）
chroot-distro login ubuntu -- bash -lc \
  'bash /data/data/com.dsharnessmobile.shell/files/home/.dsh/workspaces/incoming/载荷构建/build_baselines.sh'
```

脚本做四件事：

1. 把 `$WS/bsrc/exploit/src` 拷到 `/root/vrbuild/src`，放进 `Makefile` 与 `assets/`；
2. 把本目录两份 `targets/<t>/{target.h,root.c}` 放进 `src/targets/<t>/`
   （上游 `Makefile` 的 `pick_src` 会优先取 `src/targets/$(PROJECT)/` 下的文件）；
3. `make PROJECT=<t> NDK_ROOT=/opt/android-sdk/ndk/30.0.16248370 API=35`；
4. **自证**：`strings -a | grep -c "vr detag"` 必须 `>= 1`，否则脚本以非零退出。

可覆盖的变量：`WS` / `BUILD_ROOT`（默认 `/root/vrbuild`）/ `NDK_ROOT` / `API` / `SRC_ROOT`。

### 5.1 装进仓库

```bash
cp /root/vrbuild/build/baseline-6.1-tokay/bin/preload.so \
   app/src/main/jniLibs/arm64-v8a/libbaseline_6_1.so
cp /root/vrbuild/build/baseline-6.12-gki/bin/preload.so \
   app/src/main/jniLibs/arm64-v8a/libbaseline_6_12.so
```

装完**必须**跑一次：

```bash
bash ksu-toolchain/compile_check.sh <merge-repo 绝对路径>
```

`BundledPayloadVrKoAuditTest` 会拿真实字节复核这两份产物带没带抹标记 ——
"建好没接线"（编出来了但没带上/没装上）在这条用例上过不去。

---

## 6. 上游 `Makefile` 的关键点（照抄备忘）

```make
pick_src = $(if $(wildcard src/targets/$(PROJECT)/$(1)),src/targets/$(PROJECT)/$(1),src/$(1))
TARGET_CFLAGS := -DTARGET_CONFIG_H=\"targets/$(PROJECT)/target.h\"
COMMON_CFLAGS := -O2 -g0 -Wall -Wextra -Isrc
CORE_SRCS     := main.c util.c slide.c fops.c pipe.c root.c posture.c
PRELOAD_SRCS  := $(CORE_SRCS) src/preload.c src/io_daemon.c src/su_blob.S src/wallpaper_blob.S
```

三个容易踩的点：

- `pick_src` 是**逐文件**的：`src/targets/<PROJECT>/` 下**存在**的同名文件覆盖 `src/` 下的。
  本目录只放 `target.h` 与 `root.c` 两个，其余仍取 `src/` 的。
- `TARGET_CONFIG_H` 是**字符串**，`#include TARGET_CONFIG_H` 才生效 —— 路径相对 `src/`。
- `PRELOAD_SRCS` 里带 `src/` 前缀，`CORE_SRCS` 不带（`vpath` 到 `src/`）。
  少写一个源文件**不会报错**，只会静默留下 UND 符号 —— 缺陷二就是这么来的。

---

## 7. 已知限制（如实列出）

1. **没有任何真机验证**。`vr detag` 出现 2 次只证明**代码编进去了**，
   不证明它在真机上抹对了标记。
2. **只编了 per-task 抹标记（Option A）**，**没有**编 `neutralize_vr()`
   那个全局关闭开关（Option B）：`SYS_EXIT_TP_OFF` / `RVH_COMMIT_CREDS_TP_OFF`
   这两个 tracepoint 偏移在 6.1 / 6.12 两族上**没有可核实的来源**，
   按"不许猜"的规矩留空（宏未定义 → `neutralize_vr()` 不参与编译）。
3. `wallpaper.webp` 是从 `libbs.so` 里**抽**出来的，不是原始素材文件。
   它能满足 `.incbin`、且产物 `dlopen` 得起来（NEEDED 只剩 libc/libdl），
   但严格说它是 `CROSS_REFERENCE` 级证据，不是 `UPSTREAM` 级。
4. 本配方还原的是**旧产物是怎么编的**；旧产物同时还有缺陷一与缺陷二，
   所以"编出来能对上"指的是**同一套输入 + 同一套工具**，
   不是"逐字节等于旧产物"（缺陷修掉之后尺寸必然变：135184 B → 175776 B）。
