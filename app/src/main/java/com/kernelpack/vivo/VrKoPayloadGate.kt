package com.kernelpack.vivo

import com.kernelpack.profile.BaselineScheme

/**
 * 一次「这份载荷能不能过 vivo 方案闸门」的判定。
 *
 * 三态而不是布尔：**"不适用"和"通过"必须分开**。
 * 通用方案（[BaselineScheme.UNIVERSAL]）本来就不该带 `vr.ko` 绕过，
 * 若把它也判成 [Pass]，界面上就分不清"检查过了、它带"与"根本没检查" ——
 * 那正是本工程一直在防的假象。
 */
sealed interface VrKoGateDecision {

    /** 该方案**不需要**这条检查（通用 / 未知）。**一个字节都不读**。 */
    data object NotApplicable : VrKoGateDecision

    /** 蓝厂方案，且这份载荷确实带 `vr.ko` 抹标记。 */
    data object Pass : VrKoGateDecision

    /** 蓝厂方案，但这份载荷**没带**（或读不出来）—— 必须拦住。 */
    data class Blocked(
        val title: String,
        val detail: List<String>,
        val remedy: String,
    ) : VrKoGateDecision
}

/**
 * ★ 方案闸门：**反 `vr.ko` 只挂蓝厂方案**，通用方案不带、也不检查。
 *
 * ### 为什么必须有这道闸门
 *
 * `vr.ko` 会给每个来自 app 的 task 打两个标记字节，并在 `thread_info.flags`
 * 里置 `VR_SYSCALL_TP_FLAG(0x400)`；该 task 一旦持有 `euid 0`，
 * **`sys_exit` tracepoint 探针就会把它杀掉**。所以蓝厂机型上装一份不带抹标记的载荷，
 * 结果不是"提权失败"，而是**提权成功之后子进程立刻被杀** —— 表现出来极难排查。
 *
 * 闸门放在**构建/选择阶段**，就是为了把这种"装上去才炸"变成"当场说清楚"。
 *
 * ### 能力与闸门是一对
 *
 * 加了这条闸门，就**必须**保证每一份会被路由到蓝厂方案的载荷都过得了它：
 * - 6.6 → `libbs.so`：上游自带，实测带 ✅
 * - 6.1 / 6.12 → 我方自编族基线：**本轮重编后**带上 ✅（之前不带，正是被这条闸门拦的对象）
 * - 那两份无源码的厂商载荷：**过不了**，按用户决定做**降级标注 + 选中时确认**，
 *   而不是伪造判据让它们"看起来能过"（见 [com.ting.root.BundledPayloadCatalog] 的
 *   `vivoVrBypass`）
 */
object VrKoPayloadGate {

    const val TITLE = "这份载荷没有 vivo 反 vr.ko 绕过"

    /**
     * 判定。
     *
     * @param scheme 当前方案。**只有 [BaselineScheme.VIVO] 会触发检查** ——
     *   通用方案直接返回 [VrKoGateDecision.NotApplicable]，连 ELF 都不解析。
     * @param libraryName 载荷文件名，只用于把话说清楚。
     * @param bytes 载荷字节。
     */
    fun decide(
        scheme: BaselineScheme,
        libraryName: String,
        bytes: ByteArray,
    ): VrKoGateDecision {
        if (scheme != BaselineScheme.VIVO) return VrKoGateDecision.NotApplicable

        val result = VrKoPayloadCheck.check(bytes)
        if (result.hasBypass) return VrKoGateDecision.Pass

        val e = result.evidence
        val detail = buildList {
            add(
                when (result.status) {
                    VrKoPayloadCheck.Status.ABSENT ->
                        "判据跑完了：既没有 `vr detag` 日志串，也没有抹标记的机器码特征。"
                    VrKoPayloadCheck.Status.UNPARSEABLE ->
                        "这份文件连 ELF 都解析不了（截断 / 头损坏 / 不是 .so），**无法确认**它带没带。"
                    VrKoPayloadCheck.Status.PRESENT -> "（不可能到达）"
                }
            )
            add("载荷：$libraryName（${bytes.size} B）")
            add(
                "证据：日志串=${if (e.detagLogMarker) "有" else "无"} · " +
                    "add #0x2c=${e.tagBAddSites} 处 · " +
                    "AND ~0x400=${e.syscallTpFlagClearSites} 处 · " +
                    "已扫可执行节=${e.executableBytes} B"
            )
            add("后果：在带 vr.ko 的蓝厂机型上，提权后的子进程会被 sys_exit 探针杀掉。")
        }
        return VrKoGateDecision.Blocked(
            title = TITLE,
            detail = detail,
            remedy = "改选一份带绕过的蓝厂载荷：6.6 用内置 libbs.so，" +
                "6.1 / 6.12 用自编族基线（libbaseline_6_1.so / libbaseline_6_12.so）。",
        )
    }
}
