package com.kernelpack

/**
 * 打补丁前的**符号对齐检查**。
 *
 * ### 为什么这是一道硬闸门
 *
 * 打补丁只能改写**基线 `.so` 里本来就有的字面量**。所以「这次从内核提解析出的符号」
 * 与「基线 `.so` 里烤着的符号」必须**双向对齐**：
 *
 * - 解析到、但基线里没有 → **改不了**，`.so` 会保留旧值；
 * - 基线里有、但这次没解析出 → **同样保留旧值**。
 *
 * 两种情况下产出的 `.so` 都是一个**新旧混血**：一部分常量对着目标内核，
 * 另一部分对着基线编译时的那个内核。而它是**静默**的 ——
 * 既不报错也不 manifest 成失败，装机后才炸。
 *
 * ### 由来
 *
 * 上游那 50 档每档只带 9 个符号，而我方必需键有 25 个。
 * 原先的写法是 `baseline.symbolOffsets[key] ?: continue` —— 缺了就跳过，
 * 等于"看起来支持、实际写错内存"。本工程一直在防的正是这种假象。
 *
 * 做成纯函数是为了能脱离 Android 运行时单测 —— 这道闸门本身**必须有测试**，
 * 否则它自己就成了下一个"口头承诺"。
 */
object SymbolAlignment {

    /** 一次对齐检查的结果。 */
    data class Report(
        /** 解析到了、但基线 .so 里没有对应字面量 —— 改不了。 */
        val unpatchable: List<String>,
        /** 基线里有、但这次没解析出来 —— 也会留旧值。 */
        val unresolved: List<String>,
        val baseKeyCount: Int,
        val resolvedKeyCount: Int,
    ) {
        /** 两边完全对齐，可以安全打补丁。 */
        val aligned: Boolean get() = unpatchable.isEmpty() && unresolved.isEmpty()

        /** 给用户看的阻断说明（逐行）。 */
        fun blockMessage(baselineId: String, baselineKernel: String): List<String> = buildList {
            add("[X] 基线档位与本次内核的符号**对不齐**，已停止打包（不会产出可能写坏内存的 .so）")
            add("    基线：$baselineId（编译时对着 $baselineKernel）")
            add("    基线里有 $baseKeyCount 个符号，本次解析出 $resolvedKeyCount 个")
            if (unpatchable.isNotEmpty()) {
                add("    提取到但**基线里没有**（改不了，会保留旧值）：${unpatchable.size} 个")
                add("      " + unpatchable.take(8).joinToString(", ") + if (unpatchable.size > 8) " …" else "")
            }
            if (unresolved.isNotEmpty()) {
                add("    基线里有但**本次没解析出**（同样会保留旧值）：${unresolved.size} 个")
                add("      " + unresolved.take(8).joinToString(", ") + if (unresolved.size > 8) " …" else "")
            }
            add("")
            add("    为什么必须拦住：载荷靠**编译期常量**寻址内核符号，")
            add("    只改写一部分、剩下的留旧值，等于拿旧内核的常量去改新内核的内存。")
            add("    补救：换一份**为该内核编译的基线 .so**，或补全该内核的符号来源。")
        }
    }

    /**
     * @param baseKeys 基线 `.so` 里烤着的符号键。
     * @param resolvedKeys 本次从内核镜像解析出的符号键。
     */
    fun check(baseKeys: Set<String>, resolvedKeys: Set<String>): Report = Report(
        unpatchable = (resolvedKeys - baseKeys).sorted(),
        unresolved = (baseKeys - resolvedKeys).sorted(),
        baseKeyCount = baseKeys.size,
        resolvedKeyCount = resolvedKeys.size,
    )
}
