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
        /**
         * 被**豁免**的可选键（[SymbolCatalog] 里标了 `required = false` 的那些）。
         *
         * 这些键不参与阻断，但**必须单独列出来**：豁免不等于无事发生 ——
         * 它们对应的功能在本次产物里就是不可用的。悄悄放过它们，
         * 就从"硬拦一切"变成了"悄悄少改几个"，那是本工程最不想要的两种极端之一。
         */
        val skippedOptional: List<String> = emptyList(),
    ) {
        /** 两边完全对齐（可选键除外），可以安全打补丁。 */
        val aligned: Boolean get() = unpatchable.isEmpty() && unresolved.isEmpty()

        /** 豁免说明。没有豁免就返回空 —— 调用方直接 `forEach { log(it) }` 即可。 */
        fun optionalNotice(): List<String> = if (skippedOptional.isEmpty()) {
            emptyList()
        } else {
            listOf(
                "[!] 有 ${skippedOptional.size} 个**可选**键没有参与改写（不阻断打包）：" +
                    skippedOptional.joinToString(", "),
                "    它们对应的功能在本次产物里**不可用**；必需键已全部对齐。",
            )
        }

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
     * @param optionalKeys **可选键**（[SymbolCatalog] 里 `required = false` 的那些）。
     *
     * ### 为什么可选键必须豁免
     *
     * 这些键缺席是**设计如此**，不是缺陷：
     * - `SYS_EXIT_TP` / `RVH_COMMIT_CREDS_TP` 是 `neutralize_vr()`（全局关掉 vr.ko 探针，
     *   即 Option B）用的。6.1 / 6.12 两族的自编基线**故意没有编进去** ——
     *   那两个 tracepoint 偏移在两族上**没有可核实的来源**，按"不许猜"的规矩留空
     *   （见 `载荷构建/README.md` §7）。它们只带 per-task 抹标记（Option A）。
     * - `SLIDE_NFULNL_LOG_PACKET` 上游标注就是"需按机型核对"。
     *
     * 原来的实现只做纯集合差，把这三个键当成硬要求 —— 结果这两份基线
     * **永远过不了闸门**，界面上表现为"6.1 / 6.12 没有可用的基线"。
     * 关键是 `SymbolSpec.required` 这个字段**声明了却全仓库无人读取**，
     * 于是"可选"只停留在注释里。
     *
     * ⚠️ 豁免**只针对被显式标成可选**的键。必需键一个都不能少 ——
     * 那才是这道闸门存在的理由。
     */
    fun check(
        baseKeys: Set<String>,
        resolvedKeys: Set<String>,
        optionalKeys: Set<String> = emptySet(),
    ): Report {
        val rawUnpatchable = (resolvedKeys - baseKeys).sorted()
        val rawUnresolved = (baseKeys - resolvedKeys).sorted()
        return Report(
            unpatchable = rawUnpatchable.filterNot { it in optionalKeys },
            unresolved = rawUnresolved.filterNot { it in optionalKeys },
            baseKeyCount = baseKeys.size,
            resolvedKeyCount = resolvedKeys.size,
            skippedOptional = (rawUnpatchable + rawUnresolved)
                .filter { it in optionalKeys }
                .distinct()
                .sorted(),
        )
    }
}
