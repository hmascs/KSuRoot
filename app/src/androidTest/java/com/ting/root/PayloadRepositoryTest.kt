package com.ting.root

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PayloadRepositoryTest {
    @Test
    fun manifestMatchesDeviceAndArtifactsDownload() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = PayloadRepository(context)
        val snapshot = DeviceSnapshot.current()
        val profile = repository.resolveTarget(snapshot)
        assertTrue(profile.matches(snapshot))

        val payloads = repository.download(profile) { }
        assertEquals(profile.exploit.size, payloads.exploit.length())
        assertTrue(payloads.exploit.canRead())
        // kernelSu 是可空的：在线清单里只有三星那几档才带 KSU 载荷，
        // 本地载荷（内置 / 自定义）本来就没有它 —— 所以这里不能无条件断言。
        val kernelSu = payloads.kernelSu
        if (kernelSu != null) {
            assertEquals(profile.kernelSu?.size, kernelSu.length())
            assertTrue(kernelSu.canRead())
        }
    }
}
