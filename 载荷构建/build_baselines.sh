#!/bin/bash
# 重编 KSuRoot 的「族基线」载荷 .so。
#
# ── 在哪儿跑 ──────────────────────────────────────────────────────────────
# **在 Termux 宿主上直接跑**，不要自己 chroot 进去：
#
#     bash /storage/emulated/0/ksuroot项目/02-载荷源码/载荷构建/build_baselines.sh
#
# 原因：NDK 是 x86_64 的，只能在 Ubuntu chroot 里跑；而 **chroot 看不到
# /storage/emulated/0**（FUSE 没挂进去）。所以本脚本负责
# **暂存 → 进容器（build_in_container.sh）→ 取回产物**。
#
# ── 产物 ──────────────────────────────────────────────────────────────────
#   $OUT_DIR/preload_<target>.so          默认 $OUT_DIR 是本目录下 out/
# 要直接覆盖 App 里的内置载荷：
#   OUT_DIR=<…>/jniLibs/arm64-v8a \
#   RENAME="baseline-6.1-tokay=libbaseline_6_1.so baseline-6.12-gki=libbaseline_6_12.so" \
#     bash build_baselines.sh
#
# ── 它会自己验产物 ────────────────────────────────────────────────────────
# 2026-10 那次事故：6.1 的 VR_TAG_A_OFF 填成 0x06（该是 0x04），载荷在真机上
# 必然失效 —— 而当时的自检只查了 `vr detag` 字符串在不在，**根本没看偏移**。
# 现在对产物做反汇编断言：tag B 当锚点，tag A 必须与之同寄存器、同函数窗口配对。
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ARCHIVE=$(cd "$HERE/../.." && pwd)
TARGETS=${TARGETS:-"baseline-6.1-tokay baseline-6.12-gki"}
OUT_DIR=${OUT_DIR:-$HERE/out}
BUILD_ROOT=${BUILD_ROOT:-/root/vrbuild}
STAGE=${STAGE:-$HOME/.dsh/payload-build-stage}
API=${API:-35}

SRC_ROOT=${SRC_ROOT:-}
if [ -z "$SRC_ROOT" ]; then
  # 三种布局都认：
  #   ① 打包后：载荷构建/ 与 载荷源码/ 平级
  #   ② 归档里：02-载荷源码/bsrc/exploit/src
  #   ③ 分类前：bsrc/exploit/src 直接摆在根下
  for p in "$HERE/../载荷源码" \
           "$ARCHIVE/02-载荷源码/bsrc/exploit/src" \
           "$ARCHIVE/bsrc/exploit/src"; do
    [ -d "$p" ] && { SRC_ROOT=$p; break; }
  done
fi

echo "[0/4] 路径"
echo "  归档根   $ARCHIVE"
echo "  载荷源码 ${SRC_ROOT:-（未找到）}"
echo "  构建配方 $HERE"
echo "  暂存区   $STAGE"
echo "  产物目录 $OUT_DIR"
echo "  目标     $TARGETS"

[ -n "$SRC_ROOT" ] && [ -d "$SRC_ROOT" ] || { echo "  !! 找不到载荷源码（bsrc/exploit/src）" >&2; exit 2; }
[ -f "$HERE/Makefile" ] || { echo "  !! 找不到 $HERE/Makefile" >&2; exit 2; }
[ -f "$HERE/build_in_container.sh" ] || { echo "  !! 找不到 $HERE/build_in_container.sh" >&2; exit 2; }
[ -f "$HERE/assets/wallpaper.webp" ] || { echo "  !! 找不到 assets/wallpaper.webp" >&2; exit 2; }
for t in $TARGETS; do
  [ -f "$HERE/targets/$t/target.h" ] || { echo "  !! 缺 targets/$t/target.h" >&2; exit 2; }
done

echo "[1/4] 暂存到 $STAGE"
rm -rf "$STAGE"
mkdir -p "$STAGE/recipe"
cp -a "$SRC_ROOT" "$STAGE/src"
cp "$HERE/Makefile" "$HERE/build_in_container.sh" "$STAGE/recipe/"
cp -a "$HERE/assets" "$STAGE/recipe/assets"
for t in $TARGETS; do
  mkdir -p "$STAGE/recipe/targets/$t"
  cp "$HERE/targets/$t/target.h" "$HERE/targets/$t/root.c" "$STAGE/recipe/targets/$t/"
done

# 实测真值表 —— 必须**在 targets/ 建好之后**再拷。
# 原来放在循环前，那时 targets/ 还不存在，cp 失败又被 `|| true` 吞掉，
# 于是容器里找不到真值表 → awk 报错 → set -e 直接把构建打成 rc=2，
# 而屏幕上只看到「[校验]」之后就没了，完全不知道发生了什么。
[ -f "$HERE/targets/expected_vr_tags.tsv" ] || { echo "  !! 缺 targets/expected_vr_tags.tsv（构建闸门的真值来源）" >&2; exit 2; }
cp "$HERE/targets/expected_vr_tags.tsv" "$STAGE/recipe/targets/"

echo "[2/4] 进容器编译 + 校验"
chroot-distro login ubuntu -- env \
  STAGE="$STAGE" BUILD_ROOT="$BUILD_ROOT" TARGETS="$TARGETS" API="$API" \
  bash "$STAGE/recipe/build_in_container.sh"

echo "[3/4] 取回产物到 $OUT_DIR"
mkdir -p "$OUT_DIR"
for t in $TARGETS; do
  chroot-distro login ubuntu -- bash -c "cp '$BUILD_ROOT/build/$t/bin/preload.so' '$STAGE/preload.so'"
  name="preload_$t.so"
  for pair in ${RENAME:-}; do
    [ "${pair%%=*}" = "$t" ] && name="${pair##*=}"
  done
  cp "$STAGE/preload.so" "$OUT_DIR/$name"
  printf "  %-20s -> %s  (%s B)\n" "$t" "$OUT_DIR/$name" "$(stat -c%s "$OUT_DIR/$name")"
done
rm -f "$STAGE/preload.so"
echo "[4/4] OK"
