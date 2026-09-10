package com.ting.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「免 ADB 提权」的内核下限判定。
 *
 * 规则（用户口径）：GhostLock / CVE-2026-43499 打的是 `rt_mutex` + `pipe_buffer`
 * 这条链，**6.6 及以上**内核都有 —— 6.6 与 6.12 两系都验证过，6.7~6.11 同理；
 * **只有低于 6.6** 才必须借 ADB / Shizuku。
 */
class GhostLockKernelTest {

    private fun device(release: String) = DeviceSnapshot(
        manufacturer = "test",
        model = "test",
        device = "test",
        kernelRelease = release,
        buildId = "test",
        fingerprint = "test",
        androidRelease = "16",
        sdk = 36,
        abi = "arm64-v8a",
        pageSize = 4096,
    )

    @Test
    fun kernelAtLeastSixSixSupportsNoAdb() {
        // 本机就是这一档
        assertTrue(device("6.6.89-android15-8-g1f71897ac249-abogki467805059-4k").supportsGhostLockWithoutAdb)
        assertTrue(device("6.12.30-android16-5").supportsGhostLockWithoutAdb)
        // 6.6 与 6.12 之间的版本同样有这条链，不该被误判
        assertTrue(device("6.7.0").supportsGhostLockWithoutAdb)
        assertTrue(device("6.11.99-rc1").supportsGhostLockWithoutAdb)
        // 更高大版本
        assertTrue(device("7.0.1").supportsGhostLockWithoutAdb)
    }

    @Test
    fun kernelBelowSixSixRequiresAdb() {
        assertFalse(device("6.5.9").supportsGhostLockWithoutAdb)
        assertFalse(device("5.15.74-android13-8").supportsGhostLockWithoutAdb)
        assertFalse(device("5.10.209-android12-9").supportsGhostLockWithoutAdb)
        assertFalse(device("4.19.191").supportsGhostLockWithoutAdb)
    }

    @Test
    fun kernelVersionIsParsedFromReleaseString() {
        assertTrue(device("6.6.89-android15-8").kernelVersion == "6.6.89")
        assertTrue(device("5.15.74-android13-8").kernelVersion == "5.15.74")
        // 解析不出数字时按「不支持免 ADB」保守处理
        assertFalse(device("unknown").supportsGhostLockWithoutAdb)
    }
}
