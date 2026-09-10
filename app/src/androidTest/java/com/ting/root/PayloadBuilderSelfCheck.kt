package com.ting.root

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kernelpack.KernelPack
import com.kernelpack.PackRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真机自检：**不走界面**，直接把「载荷构建」那条链路在本机跑一遍。
 *
 * 为什么要有它：这条链路的输入是一份 96 MB 的 boot.img 与十万个符号，
 * 真正的风险不在逻辑（`com.kernelpack` 是纯函数、已在容器里对过结果），
 * 而在**设备侧**：ART 能不能吃下这个规模的解析、峰值内存够不够、
 * 内置库能不能从 nativeLibraryDir 读出来。这些只有真机跑一次才知道。
 *
 * 用法（boot.img 需要先推到应用私有目录，仪器测试读不到 /storage）：
 * ```
 * adb push boot.img /data/local/tmp/            # 或用 root 直接 cp 进 filesDir
 * adb shell run-as com.ting.root cp ...          # 见 HANDOFF 的实测命令
 * adb shell am instrument -w -e class com.ting.root.PayloadBuilderSelfCheck \
 *     com.ting.root.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * 没有 boot-img 文件时**自动跳过**（assumeTrue），因此在任何设备上跑整套仪器测试都不会因为缺文件而红。
 */
@RunWith(AndroidJUnit4::class)
class PayloadBuilderSelfCheck {

    private val tag = "KSU-BUILD"

    @Test
    fun packsBundledLibraryAgainstLocalBootImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // 两个候选位置：应用私有目录、以及应用自己的外部目录
        // （后者用 root push 进去最省事，且不需要任何存储权限）。
        val boot = listOfNotNull(
            File(context.filesDir, BOOT_FILE),
            context.getExternalFilesDir(null)?.let { File(it, BOOT_FILE) },
        ).firstOrNull { it.exists() }
        assumeTrue("未放置 $BOOT_FILE，跳过", boot != null)

        val base = File(context.applicationInfo.nativeLibraryDir, "libbs.so")
        assertTrue("内置动态库不存在: ${base.absolutePath}", base.exists())
        Log.i(tag, "内置库 ${base.length()} 字节 sha256=${PayloadBuilderViewModel.sha256Hex(base.readBytes())}")

        val startedAt = System.currentTimeMillis()
        val result = KernelPack.pack(
            PackRequest(
                bootImage = boot!!.readBytes(),
                baseLibrary = base.readBytes(),
                log = { Log.i(tag, it) },
            ),
        )
        val elapsed = System.currentTimeMillis() - startedAt

        Log.i(tag, "耗时 ${elapsed}ms")
        Log.i(tag, result.summary())
        Log.i(tag, "目标画像 ${result.profile.variantLabel}")

        // ① 符号表解析出来了（真实内核是 10 万量级）
        assertTrue("符号数异常: ${result.analysis.symbols.size}", result.analysis.symbols.size > 10_000)
        // ② 偏移基本全部解析出来（链路缺一不可）
        val resolved = result.profile.offsets.count { it.value.resolved }
        assertTrue("偏移解析过少: $resolved/${result.profile.offsets.size}", resolved >= result.profile.offsets.size - 1)
        // ③ 打包产物存在、与原库等长（原地改写，不增删字节）
        val packed = result.packedLibrary
        assertTrue("未产出打包结果（基线没识别出来？）", packed != null)
        assertEquals("产物长度必须与输入一致", base.length(), packed!!.size.toLong())
        // ④ 每个被改写过的键都不能有旧值残留
        result.patchReport?.outcomes?.filter { it.changed }?.forEach { outcome ->
            assertEquals("${outcome.key} 旧值残留", 0, outcome.residualOld)
            assertTrue("${outcome.key} 有未能替换的站点", outcome.sitesFailed == 0)
        }
        Log.i(
            tag,
            "OK 符号=${result.analysis.symbols.size} 偏移=$resolved/${result.profile.offsets.size} " +
                "产物=${packed.size}B 需要改写=${result.patchReport?.outcomes?.count { it.changed } ?: 0} 项 " +
                "警告=${result.warnings.size} 条",
        )
    }

    private companion object {
        const val BOOT_FILE = "boot-selfcheck.img"
    }
}
