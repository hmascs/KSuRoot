package com.ting.root

import android.content.Context

import android.os.Build
import android.system.Os
import android.system.OsConstants

data class DeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val device: String,
    /**
     * 市场名（人能读懂的名字，如 `iQOO 13`）。厂商没提供时为 null。
     *
     * **不回落**到型号代码 —— 由界面决定怎么显示，避免把 `V2463A` 当名字用。
     */
    val marketName: String? = null,
    /**
     * 全部身份串拼成的匹配 haystack（小写）。
     *
     * 匹配器用它而不是只用 [model]：厂商把名字放在 MODEL / DEVICE / PRODUCT /
     * 市场名属性的哪一个里完全不统一，只赌 MODEL 会在 vivo 这类机型上必然失败。
     */
    val identityText: String = "",
    val kernelRelease: String,
    val buildId: String,
    val fingerprint: String,
    val androidRelease: String,
    val sdk: Int,
    val abi: String,
    /** `Build.VERSION.SECURITY_PATCH`，如 `2026-06-01`；取不到时为空串。 */
    /**
     * 安全补丁月份串。
     *
     * 给默认值是刻意的：漏传时得到空串 → `PatchLevel` 判 `NONE` → **不出警告**。
     * 反过来（默认某个月份）会凭空造出假警报，而"假警报比不报更糟"是本工程既有原则。
     */
    val securityPatch: String = "",
    val pageSize: Long,
) {
    val targetLabel: String
        get() = "$kernelRelease / $buildId"

    val kernelVersion: String
        get() = kernelRelease.takeWhile { it.isDigit() || it == '.' }

    /**
     * 内核 release 里的 **localversion 段**，也就是 `-` 之后的构建串。
     *
     * `6.6.89-android15-8-g1f71897ac249-abogki467805059-4k` → `android15-8-g1f71897ac249-abogki467805059-4k`
     *
     * 厂商载荷是按**具体构建**编译的（同一台机型不同 OTA 的 6.6.89 都要各来一份），
     * 光靠 `kernelVersion` 区分不开，必须带上这一段才能命中正确的那份库。
     */
    val kernelBuildTag: String
        get() = kernelRelease.substringAfter('-', "")

    /** 内核构建的短标识：`g` 后面那截 commit（`g1f71897ac249` → `1f71897ac249`）。 */
    val kernelCommit: String
        get() = KERNEL_COMMIT_PATTERN.find(kernelRelease)?.groupValues?.get(1).orEmpty()

    /** 内核代号主版本，例如 `6.6`、`6.12` —— 相似机型回落时用它比较。 */
    val kernelMajorMinor: String
        get() = kernelVersion.split('.').take(2).joinToString(".")

    /**
     * 内核是否达到「免 ADB 提权」的下限 —— **6.6 及以上**。
     *
     * GhostLock（CVE-2026-43499）打的是 `rt_mutex` + `pipe_buffer` 这条链，
     * 6.6 及以后的内核都有（6.6 与 6.12 两系都验证过），所以 6.7/6.8…6.12 乃至更高
     * 一样能免 ADB。**只有低于 6.6** 的内核没有这条链，必须借 ADB / Shizuku 的权限去装。
     */
    val supportsGhostLockWithoutAdb: Boolean
        get() {
            val parts = kernelVersion.split('.').mapNotNull { it.toIntOrNull() }
            val major = parts.getOrNull(0) ?: return false
            val minor = parts.getOrNull(1) ?: return false
            return major > 6 || (major == 6 && minor >= 6)
        }

    companion object {
        private val KERNEL_COMMIT_PATTERN = Regex("-g([0-9a-f]{10,})")

        /**
         * @param context 用于读 `Settings.Global.device_name`（市场名的一种来源）。
         *        传 null 时只查系统属性 —— 单测与无 Context 场景仍可用。
         */
        fun current(context: Context? = null): DeviceSnapshot {
            val uname = Os.uname()
            return DeviceSnapshot(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                device = Build.DEVICE,
                marketName = DeviceIdentity.marketName(context),
                identityText = DeviceIdentity.identityText(context),
                kernelRelease = uname.release,
                buildId = Build.DISPLAY,
                fingerprint = Build.FINGERPRINT,
                androidRelease = Build.VERSION.RELEASE,
                sdk = Build.VERSION.SDK_INT,
                abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                securityPatch = Build.VERSION.SECURITY_PATCH.orEmpty(),
                pageSize = Os.sysconf(OsConstants._SC_PAGESIZE),
            )
        }
    }
}
