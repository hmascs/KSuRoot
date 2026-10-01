#!/bin/bash
# 容器内构建 + 验收。**由 build_baselines.sh 调用**，不要自己 chroot 进来跑。
# 输入（环境变量）：
#   STAGE       暂存目录（含 src/ 与 recipe/）
#   BUILD_ROOT  容器内构建目录
#   TARGETS     目标列表
#   API         NDK API level
set -euo pipefail

NDK=${NDK_ROOT:-$(ls -d /opt/android-sdk/ndk/*/ 2>/dev/null | sort -V | tail -1)}
NDK=${NDK%/}
[ -d "$NDK" ] || { echo "  !! 容器里找不到 NDK" >&2; exit 2; }
echo "  NDK $NDK"

rm -rf "$BUILD_ROOT"
mkdir -p "$BUILD_ROOT/assets" "$BUILD_ROOT/build/embed"
cp -a "$STAGE/src" "$BUILD_ROOT/src"
cp "$STAGE/recipe/Makefile" "$BUILD_ROOT/Makefile"
cp -a "$STAGE/recipe/assets/." "$BUILD_ROOT/assets/"
for t in $TARGETS; do
  mkdir -p "$BUILD_ROOT/src/targets/$t"
  cp "$STAGE/recipe/targets/$t/target.h" "$STAGE/recipe/targets/$t/root.c" "$BUILD_ROOT/src/targets/$t/"
done

cd "$BUILD_ROOT"
for t in $TARGETS; do
  echo "  [编译] $t"
  make PROJECT="$t" NDK_ROOT="$NDK" API="$API" >/dev/null
done

OBJDUMP=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump
echo "  [校验]"
fail=0
for t in $TARGETS; do
  f="$BUILD_ROOT/build/$t/bin/preload.so"
  [ -f "$f" ] || { echo "     !! $t 没产出" >&2; fail=1; continue; }

  decl_a=$(sed -nE 's/^#define[[:space:]]+VR_TAG_A_OFF[[:space:]]+(0x[0-9a-fA-F]+).*/\1/p' \
           "$STAGE/recipe/targets/$t/target.h" | head -1)
  decl_b=$(sed -nE 's/^#define[[:space:]]+VR_TAG_B_OFF[[:space:]]+(0x[0-9a-fA-F]+).*/\1/p' \
           "$STAGE/recipe/targets/$t/target.h" | head -1)

  # 期望值来自**独立实测表**，不是 target.h —— 否则就是拿自己比自己，永远为真。
  exp_a=$(awk -F'\t' -v t="$t" '$1==t {print $3}' "$STAGE/recipe/targets/expected_vr_tags.tsv" 2>/dev/null | head -1)
  exp_b=$(awk -F'\t' -v t="$t" '$1==t {print $4}' "$STAGE/recipe/targets/expected_vr_tags.tsv" 2>/dev/null | head -1)
  prov=$(awk -F'\t' -v t="$t" '$1==t {print $5}' "$STAGE/recipe/targets/expected_vr_tags.tsv" 2>/dev/null | head -1)
  if [ -z "$exp_a" ]; then
    echo "     !! $t 不在 expected_vr_tags.tsv 里 —— 没有实测真值就不许构建" >&2
    fail=1; continue
  fi
  # **两边都归一化**前导零再比。
  # 踩过一次：真值表写 `0x04`、声明侧归一化成 `0x4`，看着一模一样却判不符。
  na_decl=$(printf '0x%x' $(( decl_a )) 2>/dev/null || echo "$decl_a")
  nb_decl=$(printf '0x%x' $(( decl_b )) 2>/dev/null || echo "$decl_b")
  na_exp=$(printf '0x%x' $(( exp_a )) 2>/dev/null || echo "$exp_a")
  nb_exp=$(printf '0x%x' $(( exp_b )) 2>/dev/null || echo "$exp_b")
  if [ "$na_decl" != "$na_exp" ] || [ "$nb_decl" != "$nb_exp" ]; then
    echo "     !! $t 的 target.h tag 偏移与实测不符：" >&2
    echo "        声明  A=$decl_a B=$decl_b" >&2
    echo "        实测  A=$exp_a B=$exp_b   （$prov）" >&2
    fail=1; continue
  fi
  want_a=$na_exp; want_b=$nb_exp
  # 归一化前导零：target.h 写 0x04，objdump 输出 0x4
  ha=$(printf '0x%x' $(( want_a )) 2>/dev/null || echo "$want_a")
  hb=$(printf '0x%x' $(( want_b )) 2>/dev/null || echo "$want_b")
  detag=$(strings -a "$f" | grep -c 'vr detag' || true)

  if [ -x "$OBJDUMP" ]; then
    dis=$("$OBJDUMP" -d "$f" 2>/dev/null || true)
    # tag B（#0x2c）在产物里极少见 → 用它当锚点定位 patch_task_vr_tag；
    # tag A 只在**同一基址寄存器 + 同一函数地址窗口**内数。
    # 不能全局数 #0x4：那是常见小立即数，6.1 那份里 5 处，只有 3 处是 tag。
    counts=$(printf '%s\n' "$dis" | awk -v ha="$ha" -v hb="$hb" '
      function hex(s,  i,c,v){ v=0
        for(i=1;i<=length(s);i++){ c=tolower(substr(s,i,1))
          k=index("0123456789abcdef", c); if(k==0) return -1; v=v*16+k-1 }
        return v }
      /add[ \t]+x1, x[0-9]+, #0x[0-9a-f]+/ {
        line=$0
        sub(/^[ \t]*/, "", line)
        addr=line; sub(/:.*/, "", addr)
        reg=line; sub(/^.*add[ \t]+x1, /, "", reg); sub(/,.*/, "", reg)
        # 只取 #0x… 里的十六进制部分，**不要**把开头的 # 也剥掉
        # （原来写的是 sub(/[^0-9a-fx].*$/,"",imm)，连 # 一起吃了，
        #  imm 恒为空串，断言永远数出 0）
        imm=line; sub(/^.*#/, "", imm); sub(/[^0-9a-fx].*$/, "", imm)
        if (imm==hb) { nb++; breg[reg]=1; ba[nb]=hex(addr) }
        else if (imm==ha) { na++; areg[na]=reg; aa[na]=hex(addr) }
      }
      END {
        if (nb==0) { print "0 0"; exit }
        lo=ba[1]; hi=ba[1]
        for(i=2;i<=nb;i++){ if(ba[i]<lo) lo=ba[i]; if(ba[i]>hi) hi=ba[i] }
        lo-=512; hi+=512
        good=0
        for(i=1;i<=na;i++) if (areg[i] in breg && aa[i]>=lo && aa[i]<=hi) good++
        printf "%d %d\n", good, nb
      }')
    a_hits=${counts% *}; b_hits=${counts#* }
  else
    a_hits=-1; b_hits=-1
  fi

  printf "     %-20s %8s B  vr detag=%s  tagA(%s)=%s  tagB(%s)=%s\n" \
         "$t" "$(stat -c%s "$f")" "$detag" "${ha:-?}" "$a_hits" "${hb:-?}" "$b_hits"
  printf "        %-18s 实测真值 A=%s B=%s  (%s)\n" "" "$na_exp" "$nb_exp" "$prov"

  [ "$detag" -ge 1 ] || { echo "        !! 没带上 vr.ko detag" >&2; fail=1; }
  if [ "$a_hits" -ge 0 ]; then
    [ "$b_hits" -eq 3 ] || { echo "        !! tag B 锚点应为 3 处，实测 $b_hits" >&2; fail=1; }
    [ "$a_hits" -eq 3 ] || { echo "        !! tag A 应与之配对 3 处，实测 $a_hits —— 偏移可能填错" >&2; fail=1; }
  fi
done
[ "$fail" -eq 0 ] || { echo "!! 产物校验未通过" >&2; exit 1; }
exit 0
