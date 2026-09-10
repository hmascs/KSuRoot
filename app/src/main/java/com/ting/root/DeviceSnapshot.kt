package com.ting.root

import android.os.Build
import android.system.Os
import android.system.OsConstants

data class DeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val device: String,
    val kernelRelease: String,
    val buildId: String,
    val fingerprint: String,
    val androidRelease: String,
    val sdk: Int,
    val abi: String,
    val pageSize: Long,
) {
    val targetLabel: String
        get() = "$kernelRelease / $buildId"

    val kernelVersion: String
        get() = kernelRelease.takeWhile { it.isDigit() || it == '.' }

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
        fun current(): DeviceSnapshot {
            val uname = Os.uname()
            return DeviceSnapshot(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                device = Build.DEVICE,
                kernelRelease = uname.release,
                buildId = Build.DISPLAY,
                fingerprint = Build.FINGERPRINT,
                androidRelease = Build.VERSION.RELEASE,
                sdk = Build.VERSION.SDK_INT,
                abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                pageSize = Os.sysconf(OsConstants._SC_PAGESIZE),
            )
        }
    }
}
