package com.ting.root

/**
 * Android 安全补丁等级判定 —— 用于提示「幽灵锁漏洞是否已被厂商修掉」。
 *
 * 背景：CVE-2026-43499（GhostLock）走的是 `rt_mutex` PI 链。厂商一旦把修复合进
 * 安全补丁，提权就会失败。按已知信息：
 * ```
 *   2026-06 及更早   可能已合并修复（不确定）→ 黄色感叹号，**只提示、不限制**
 *   2026-07 及以上   已合并修复               → 红色感叹号，提示大概率不可用
 * ```
 *
 * [为什么"只是提示"而不是拦截] 我们**没有**官方补丁映射表，判据只是月份。
 * 拿一个推断去阻止用户操作，属于"假警报比不报更糟"；而且用户可能在已打补丁的
 * 设备上验证别的东西。所以两级都只弹窗，确认键加 10 秒冷却 —— 冷却的作用是
 * **让人把话读完**，不是阻止继续。
 *
 * [诚实声明] 这条阈值来自本项目已知的时间线，**不是**从厂商公告核实的。
 */
enum class PatchRisk(val level: Int) {
    /** 补丁早于 2026-06，或解析不出来 —— 不做任何提示。 */
    NONE(0),

    /** 2026-06：可能已合并修复。黄色感叹号，无限制。 */
    MAYBE_FIXED(1),

    /** 2026-07 及以后：已合并修复。红色感叹号，提示大概率不可用。 */
    LIKELY_FIXED(2),
    ;

    val hasWarning: Boolean get() = this != NONE
}

object PatchLevel {

    /** 已知开始合并修复的月份：2026-07。 */
    const val FIRST_FIXED_YEAR = 2026
    const val FIRST_FIXED_MONTH = 7

    /** 开始"可能已合并"的月份：2026-06。 */
    const val MAYBE_FIXED_YEAR = 2026
    const val MAYBE_FIXED_MONTH = 6

    /**
     * 解析 `Build.VERSION.SECURITY_PATCH`（形如 `2026-06-01` / `2026-06-05`）。
     *
     * 解析不出来一律返回 `null` —— **不猜**。厂商改格式的设备确实存在，
     * 猜错会给出与事实相反的警告。
     */
    fun parse(patch: String?): Pair<Int, Int>? {
        val p = patch?.trim().orEmpty()
        // 允许 `2026-06-01`\ `2026-06`\ `2026/06/01` 三种常见写法
        val m = Regex("^(\\d{4})[-/](\\d{1,2})").find(p) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues[2].toIntOrNull() ?: return null
        if (mo !in 1..12) return null
        return y to mo
    }

    /** 判定等级。解析不出来 → [PatchRisk.NONE]（不提示）。 */
    fun evaluate(patch: String?): PatchRisk {
        val (y, m) = parse(patch) ?: return PatchRisk.NONE
        val ym = y * 12 + (m - 1)
        val fixed = FIRST_FIXED_YEAR * 12 + (FIRST_FIXED_MONTH - 1)
        val maybe = MAYBE_FIXED_YEAR * 12 + (MAYBE_FIXED_MONTH - 1)
        return when {
            ym >= fixed -> PatchRisk.LIKELY_FIXED
            ym >= maybe -> PatchRisk.MAYBE_FIXED
            else -> PatchRisk.NONE
        }
    }

    /**
     * 界面上那一行显示的文字。
     *
     * [2026-09-24 修正] 原来在这里拼 `⚠` / `❗` 字符 —— 实测在设备上渲染成**白色**，
     * 看不出黄/红之分（那两个码位是 emoji 变体，字形与颜色由系统字体决定，我们控制不了）。
     * 现在这里只出纯文本，**颜色一律交给带 tint 的图标**去表达。
     */
    fun display(patch: String?): String =
        patch?.trim().orEmpty().ifBlank { "未知" }
}
