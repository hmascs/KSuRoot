package com.ting.root.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 移交功能的测试。
 *
 * 最重要的一条是 [KernelSU 的包名在查找与 package-name 两处必须一致] ——
 * 用户给的原命令在 `--package-name` 处漏了 `su`（写成 `me.weishu.kernel`），
 * 那是错的；这里把它钉死，避免以后有人"照着原文抄回去"。
 */
class RootHandoffTest {

    // ---------------------------------------------------------------- 管理器定义

    @Test
    fun `两个管理器的包名正确`() {
        assertEquals("me.weishu.kernelsu", RootManager.KERNELSU.packageName)
        assertEquals("com.sukisu.ultra", RootManager.SUKISU_ULTRA.packageName)
    }

    @Test
    fun `KernelSU 的包名在查找与 package-name 两处必须一致`() {
        // 回归用例：原命令写的是 --package-name me.weishu.kernel（少 su）
        val cmd = HandoffCommand.of(RootManager.KERNELSU)
        assertTrue("定位命令里要按包名过滤", cmd.locate.contains("me.weishu.kernelsu"))
        assertTrue("late-load 也要用同一个包名", cmd.lateLoad.contains("--package-name me.weishu.kernelsu"))
        assertFalse(
            "绝不能出现漏掉 su 的包名",
            cmd.shell.contains("me.weishu.kernel "),
        )
    }

    @Test
    fun `late-load 命令形态与用户给的一致`() {
        val cmd = HandoffCommand.of(RootManager.SUKISU_ULTRA)
        assertTrue(cmd.lateLoad.contains("late-load"))
        assertTrue(cmd.lateLoad.contains("--allow-shell"))
        assertTrue(cmd.lateLoad.contains("--package-name com.sukisu.ultra"))
        // 用 $KSUD 变量把"找到的路径"和"执行它"串起来
        assertTrue(cmd.shell.contains("KSUD="))
        assertTrue(cmd.shell.contains("\"\$KSUD\" late-load"))
    }

    @Test
    fun `找不到 ksud 时命令要以 3 退出并打印哨兵`() {
        val cmd = HandoffCommand.of(RootManager.KERNELSU)
        assertTrue("必须显式判空", cmd.shell.contains("NO_KSUD"))
        assertTrue("哨兵要有约定的退出码", cmd.shell.contains("exit 3"))
    }

    // ---------------------------------------------------------------- 输出解析

    @Test
    fun `能从输出里取出 ksud 路径`() {
        val out = "KSUD=/data/app/~~abc==/me.weishu.kernelsu-1/base.apk/lib/arm64/libksud.so\nok\n"
        assertEquals(
            "/data/app/~~abc==/me.weishu.kernelsu-1/base.apk/lib/arm64/libksud.so",
            HandoffOutput.ksudPath(out),
        )
    }

    @Test
    fun `没有路径行时返回 null 而不是乱猜`() {
        assertNull(HandoffOutput.ksudPath("late-load: done"))
        assertNull(HandoffOutput.ksudPath("KSUD=\n"))
    }

    @Test
    fun `NO_KSUD 或退出码 3 判为管理器没装`() {
        assertTrue(HandoffOutput.managerMissing("NO_KSUD\n", 3))
        assertTrue("只看输出也要能判出来", HandoffOutput.managerMissing("NO_KSUD", 0))
        assertFalse(HandoffOutput.managerMissing("KSUD=/x\nok", 0))
    }

    @Test
    fun `成功判定既看退出码也看有没有明显错误串`() {
        assertTrue(HandoffOutput.looksSuccessful("KSUD=/x\nlate-load ok", 0))
        assertFalse("非 0 退出码不算成功", HandoffOutput.looksSuccessful("late-load ok", 1))
        assertFalse("有 error 字样不算成功", HandoffOutput.looksSuccessful("error: module mismatch", 0))
        assertFalse("failed 也不算", HandoffOutput.looksSuccessful("late-load failed", 0))
        assertFalse("NO_KSUD 更不算", HandoffOutput.looksSuccessful("NO_KSUD", 0))
    }

    @Test
    fun `大小写不敏感地识别错误`() {
        assertFalse(HandoffOutput.looksSuccessful("DENIED", 0))
        assertFalse(HandoffOutput.looksSuccessful("operation not permitted", 0))
    }

    @Test
    fun `EPERM 与 EACCES 的标准说法都要判失败`() {
        // 回归用例：第一版的关键字表漏了 "not permitted"，
        // 于是 `Operation not permitted` 被当成成功 —— 权限失败报成成功是最坏的一种错。
        assertFalse(HandoffOutput.looksSuccessful("late-load: Operation not permitted", 0))
        assertFalse(HandoffOutput.looksSuccessful("Permission denied", 0))
        assertFalse(HandoffOutput.looksSuccessful("unable to open /dev/ksu", 0))
        assertFalse(HandoffOutput.looksSuccessful("connection refused", 0))
        assertFalse(HandoffOutput.looksSuccessful("version mismatch", 0))
    }

    // ---------------------------------------------------------------- 结果描述

    @Test
    fun `管理器没装时要引导去官网安装`() {
        val lines = HandoffOutput.describe(HandoffResult.ManagerMissing(RootManager.SUKISU_ULTRA))
        assertTrue("要点名缺失", lines.any { it.contains("没找到") })
        assertTrue("要给安装地址", lines.any { it.contains(RootManager.SUKISU_ULTRA.homeUrl) })
        assertTrue("要给出包名便于核对", lines.any { it.contains("com.sukisu.ultra") })
    }

    @Test
    fun `成功时要提示去管理器里确认`() {
        val lines = HandoffOutput.describe(
            HandoffResult.Success(RootManager.KERNELSU, "/data/app/x/libksud.so", "ok"),
        )
        assertTrue(lines.any { it.contains("KernelSU") })
        assertTrue(lines.any { it.contains("确认") })
        assertTrue(lines.any { it.contains("/data/app/x/libksud.so") })
    }

    @Test
    fun `失败时要把原始输出带给用户`() {
        val lines = HandoffOutput.describe(HandoffResult.Failed(RootManager.KERNELSU, 2, "boom: mismatch"))
        assertTrue("原始输出不能丢", lines.any { it.contains("boom: mismatch") })
        assertTrue("要带上退出码", lines.any { it.contains("2") })
    }

    // ---------------------------------------------------------------- root 检测

    @Test
    fun `id 输出里有 uid=0 才算有 root`() {
        assertTrue(RootShell.outputIndicatesRoot("uid=0(root) gid=0(root) groups=0(root)"))
        assertTrue(RootShell.outputIndicatesRoot("uid=0"))
        assertFalse(RootShell.outputIndicatesRoot("uid=2000(shell) gid=2000(shell)"))
        assertFalse(RootShell.outputIndicatesRoot(""))
    }

    @Test
    fun `没有 root 时不执行移交`() {
        var executed = false
        val runner = RootHandoffRunner(
            RootShell(exec = { _, _ -> executed = true; RootShell.ShellOutcome(0, "uid=0(root)") }),
        )
        val r = runner.handoff(RootManager.KERNELSU, hasRoot = false)
        assertEquals(HandoffResult.NoRoot, r)
        assertFalse("没 root 就不该去跑命令（否则会白弹一次授权框）", executed)
    }

    @Test
    fun `有 root 且找到 ksud 时判成功`() {
        val runner = RootHandoffRunner(
            RootShell(
                exec = { _, _ ->
                    RootShell.ShellOutcome(0, "KSUD=/data/app/me.weishu.kernelsu-1/lib/arm64/libksud.so\nlate-load ok")
                },
            ),
        )
        val r = runner.handoff(RootManager.KERNELSU, hasRoot = true)
        assertTrue("实际 $r", r is HandoffResult.Success)
        r as HandoffResult.Success
        assertEquals(RootManager.KERNELSU, r.manager)
        assertTrue(r.ksudPath.contains("libksud.so"))
    }

    @Test
    fun `发射 NO_KSUD 时判管理器没装而不是失败`() {
        val runner = RootHandoffRunner(
            RootShell(exec = { _, _ -> RootShell.ShellOutcome(3, "NO_KSUD") }),
        )
        val r = runner.handoff(RootManager.SUKISU_ULTRA, hasRoot = true)
        assertTrue("实际 $r", r is HandoffResult.ManagerMissing)
    }

    @Test
    fun `late-load 报错时判失败并保留输出`() {
        val runner = RootHandoffRunner(
            RootShell(
                exec = { _, _ ->
                    RootShell.ShellOutcome(0, "KSUD=/x/libksud.so\nlate-load failed: version mismatch")
                },
            ),
        )
        val r = runner.handoff(RootManager.KERNELSU, hasRoot = true)
        assertTrue("实际 $r", r is HandoffResult.Failed)
        assertTrue((r as HandoffResult.Failed).output.contains("version mismatch"))
    }

    @Test
    fun `su 超时时保守判失败而不是假设成功`() {
        val runner = RootHandoffRunner(
            RootShell(exec = { _, _ -> RootShell.ShellOutcome(-1, "", timedOut = true) }),
        )
        val r = runner.handoff(RootManager.KERNELSU, hasRoot = true)
        assertTrue("超时必须算失败", r is HandoffResult.Failed)
    }

    @Test
    fun `检测 root 时异常不往外抛`() {
        val runner = RootHandoffRunner(RootShell(exec = { _, _ -> throw IllegalStateException("boom") }))
        assertFalse(runner.detectRoot { throw IllegalStateException("boom") })
    }

    @Test
    fun `找不到 su 时给出 127 与可读说明`() {
        val shell = RootShell(
            exec = { _, _ -> RootShell.ShellOutcome(0, "uid=0") },
            suCandidates = emptyList(),
        )
        val r = shell.runAsRoot("id")
        assertEquals(127, r.exitCode)
        assertTrue(r.output.contains("找不到可用的 su"))
    }

    @Test
    fun `su 候选路径覆盖常见位置`() {
        val c = RootShell.DEFAULT_SU_CANDIDATES
        assertTrue(c.contains("su"))
        assertTrue(c.contains("/system/bin/su"))
    }
    // ================= 真机实测（2026-09-12，SukiSU Ultra 设备）=================

    @Test
    fun `late-load 成功时是静默的 —— 空输出加退出码 0 必须判成功`() {
        // 真机实测：`"$KSUD" late-load --allow-shell --package-name com.sukisu.ultra`
        //   → stdout 空、stderr 空、退出码 0
        // 这一条很关键：如果把"没输出"当成"没做事"，就会把**成功**报成失败。
        assertTrue("空输出 + rc=0 必须算成功", HandoffOutput.looksSuccessful("", 0))
        assertTrue(HandoffOutput.looksSuccessful("\n", 0))
    }

    @Test
    fun `真机实测的失败输出必须判失败`() {
        // 真机实测：不存在的包名 → 退出码 1，stderr 打出
        //   kernelnosu: exec /data/adb/ksud failed
        // App 侧用 redirectErrorStream(true) 把它并进输出，所以这里能看见。
        val real = "kernelnosu: exec /data/adb/ksud failed"
        assertFalse("真机失败输出必须判失败", HandoffOutput.looksSuccessful(real, 1))
        assertFalse("就算退出码被骗成 0 也还有关键字兜底", HandoffOutput.looksSuccessful(real, 0))
    }

    @Test
    fun `真机实测的成功与失败在 Runner 上分流正确`() {
        // 用真机量到的两组 (输出, 退出码) 直接喂给 Runner
        val okRunner = RootHandoffRunner(
            RootShell(exec = { _, _ -> RootShell.ShellOutcome(0, "KSUD=/data/app/x/libksud.so\n") }),
        )
        assertTrue(
            "正常包名应判成功，实际 ${okRunner.handoff(RootManager.SUKISU_ULTRA, hasRoot = true)}",
            okRunner.handoff(RootManager.SUKISU_ULTRA, hasRoot = true) is HandoffResult.Success,
        )

        val badRunner = RootHandoffRunner(
            RootShell(
                exec = { _, _ ->
                    RootShell.ShellOutcome(1, "KSUD=/data/app/x/libksud.so\nkernelnosu: exec /data/adb/ksud failed")
                },
            ),
        )
        val r = badRunner.handoff(RootManager.SUKISU_ULTRA, hasRoot = true)
        assertTrue("不存在的包名必须判失败，实际 $r", r is HandoffResult.Failed)
        assertTrue(
            "要把 kernelnosu 原文带给用户",
            (r as HandoffResult.Failed).output.contains("kernelnosu"),
        )
    }

    @Test
    fun `本机只装 SukiSU 时 KernelSU 分支应判为管理器没装`() {
        // 真机实测：find /data/app -name libksud.so | grep me.weishu.kernelsu → 空
        val runner = RootHandoffRunner(
            RootShell(exec = { _, _ -> RootShell.ShellOutcome(3, "NO_KSUD") }),
        )
        val r = runner.handoff(RootManager.KERNELSU, hasRoot = true)
        assertTrue("应判 ManagerMissing，实际 $r", r is HandoffResult.ManagerMissing)
        assertEquals(RootManager.KERNELSU, (r as HandoffResult.ManagerMissing).manager)
    }
    // ================= su 候选路径（真机踩坑后的回归）=================

    @Test
    fun `su 候选表包含 product bin —— 本机实测的可用路径`() {
        // 真机实测：本机（vivo/iQOO + SukiSU Ultra）可用的 su 在 /product/bin/su，
        // /system/bin/su、/sbin/su 都**不存在**。第一版候选表没有它，
        // 导致界面显示「未检测到 root」而设备明明有 root。
        val c = RootShell.DEFAULT_SU_CANDIDATES
        assertTrue("必须包含载荷提权后的落点 /apex/com.android.virt/bin/su：$c",
            c.contains("/apex/com.android.virt/bin/su"))
        assertTrue("载荷落点要排在最前（跑完载荷就靠它命中）：$c",
            c.indexOf("/apex/com.android.virt/bin/su") == 0)
        assertTrue("必须包含 /product/bin/su：$c", c.contains("/product/bin/su"))
        assertTrue("保留 /debug_ramdisk/su（Magisk 落点）", c.contains("/debug_ramdisk/su"))
        assertTrue("保留传统路径", c.contains("/system/bin/su"))
        assertTrue("绝对路径要排在裸名之前（裸名依赖 PATH，最不可靠）",
            c.indexOf("/product/bin/su") < c.indexOf("su"))
    }

    @Test
    fun `绝对路径的 su 用文件可执行位判断是否存在`() {
        assertTrue(PathLookup.exists("/system/bin/sh"))
        assertFalse(PathLookup.exists("/definitely/not/here/su"))
    }

    @Test
    fun `裸名 su 要真的去 PATH 里找而不是假装存在`() {
        // 回归：第一版对裸名无条件 return true，等于假装它在 —— App 会去 exec
        // 一个不存在的路径，报出来的理由却是"找不到可用的 su"，掩盖真因。
        PathLookup.searchPath = "/nonexistent-a:/nonexistent-b"
        assertFalse("PATH 里没有就不该说存在", PathLookup.exists("su"))

        PathLookup.searchPath = "/nonexistent-a:/system/bin"
        assertTrue("PATH 里有就该说存在", PathLookup.exists("sh"))
        PathLookup.searchPath = null
    }

    @Test
    fun `逐条探测会把每个候选的结果记下来`() {
        var n = 0
        val probe = RootShell.probe(
            exec = { _, _ ->
                n++
                if (n == 1) RootShell.ShellOutcome(1, "kernelnosu: exec /data/adb/ksud failed")
                else RootShell.ShellOutcome(0, "uid=0(root) gid=0(root)")
            },
            suCandidates = listOf("/product/bin/su", "/system/bin/su"),
            suExists = { true },
        )
        assertTrue("第二个候选成功，整体应为 true", probe.ok)
        assertEquals("应记录两次尝试", 2, probe.tried.size)
        assertEquals("/product/bin/su", probe.tried[0].path)
        assertTrue("第一条要留下失败原因", probe.tried[0].detail.contains("kernelnosu"))
        assertTrue("第二条是成功", probe.tried[1].detail.contains("uid=0"))
    }

    @Test
    fun `全部候选失败时诊断要说清每条为什么不行`() {
        val probe = RootShell.probe(
            exec = { _, _ -> RootShell.ShellOutcome(126, "Denied") },
            suCandidates = listOf("/product/bin/su", "/system/bin/su"),
            suExists = { it != "/system/bin/su" },
        )
        assertFalse(probe.ok)
        val text = probe.describe()
        assertTrue("要提到已试的路径：$text", text.contains("/product/bin/su"))
        assertTrue("不存在的候选要标出来：$text", text.contains("不存在"))
        assertTrue("执行失败的候选要带输出：$text", text.contains("Denied"))
    }

    @Test
    fun `超时会被单独说明而不是笼统失败`() {
        val probe = RootShell.probe(
            exec = { _, _ -> RootShell.ShellOutcome(-1, "", timedOut = true) },
            suCandidates = listOf("/product/bin/su"),
            suExists = { true },
        )
        assertFalse(probe.ok)
        assertTrue(
            "超时要提示可能是管理器在等授权：${probe.describe()}",
            probe.describe().contains("授权"),
        )
    }

    @Test
    fun `找不到 su 时的说明要列出试过哪些路径`() {
        val shell = RootShell(exec = { _, _ -> RootShell.ShellOutcome(0, "uid=0") }, suCandidates = emptyList())
        val r = shell.runAsRoot("id")
        assertEquals(127, r.exitCode)
        assertTrue("要列出试过的路径：${r.output}", r.output.contains("逐个试过"))
    }
}
