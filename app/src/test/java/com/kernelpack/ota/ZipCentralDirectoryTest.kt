package com.kernelpack.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ZIP 结构解析的单元测试。
 *
 * 两条路线各测一遍：
 *  ① **真实 ZIP**（`java.util.zip.ZipOutputStream` 现场生成）—— 覆盖常规路径；
 *  ② **手工拼的 ZIP64 记录** —— 完整包几乎一定 > 4 GiB，ZIP64 是**常态**，
 *     而这条路径用真实文件测不了（造不出 4 GiB 的测试夹具）。
 */
class ZipCentralDirectoryTest {

    private fun realZip(): Triple<ByteArray, ByteArray, ByteArray> {
        val payload = ByteArray(4096) { (it * 7 and 0xFF).toByte() }
        val boot = ByteArray(8192) { (it % 251).toByte() }
        val other = "hello".toByteArray()
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val e1 = ZipEntry("payload.bin").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = payload.size.toLong()
                crc = CRC32().apply { update(payload) }.value
            }
            zos.putNextEntry(e1); zos.write(payload); zos.closeEntry()

            val e2 = ZipEntry("images/boot.img")
            zos.putNextEntry(e2); zos.write(boot); zos.closeEntry()

            val e3 = ZipEntry("notes.txt")
            zos.putNextEntry(e3); zos.write(other); zos.closeEntry()
        }
        return Triple(baos.toByteArray(), payload, boot)
    }

    @Test
    fun `真实 ZIP 能定位到中央目录`() {
        val (zip, _, _) = realZip()
        val cd = ZipCentralDirectory.locateCentralDirectory(zip, zip.size.toLong())
        assertTrue("没找到中央目录", cd.offset > 0)
        assertTrue("中央目录长度非法：${cd.size}", cd.size > 0)
        assertTrue(cd.offset + cd.size <= zip.size)
        // 中央目录第一条必须是 CENSIG
        val sig = (zip[cd.offset.toInt()].toInt() and 0xFF) or
            ((zip[cd.offset.toInt() + 1].toInt() and 0xFF) shl 8) or
            ((zip[cd.offset.toInt() + 2].toInt() and 0xFF) shl 16) or
            ((zip[cd.offset.toInt() + 3].toInt() and 0xFF) shl 24)
        assertEquals(0x02014b50, sig)
    }

    @Test
    fun `真实 ZIP 能挑出指定条目并读出元信息`() {
        val (zip, payload, boot) = realZip()
        val cd = ZipCentralDirectory.locateCentralDirectory(zip, zip.size.toLong())
        val cdBytes = zip.copyOfRange(cd.offset.toInt(), (cd.offset + cd.size).toInt())

        val entries = ZipCentralDirectory.locateEntries(
            cdBytes, setOf("payload.bin", "images/boot.img", "does-not-exist")
        )
        assertEquals(2, entries.size)
        assertFalse("没请求的条目不该出现", entries.containsKey("does-not-exist"))

        val p = entries.getValue("payload.bin")
        assertEquals("payload.bin 必须是 Stored", 0, p.method)
        assertEquals(payload.size.toLong(), p.uncompressedSize)
        assertEquals(payload.size.toLong(), p.compressedSize)
        assertTrue(ZipCentralDirectory.isStored(p))

        val b = entries.getValue("images/boot.img")
        assertEquals("boot.img 用 Deflate", 8, b.method)
        assertEquals(boot.size.toLong(), b.uncompressedSize)
        assertFalse(ZipCentralDirectory.isStored(b))
    }

    @Test
    fun `本地头偏移能换算成数据起点并读回原文`() {
        val (zip, payload, _) = realZip()
        val cd = ZipCentralDirectory.locateCentralDirectory(zip, zip.size.toLong())
        val cdBytes = zip.copyOfRange(cd.offset.toInt(), (cd.offset + cd.size).toInt())
        val entry = ZipCentralDirectory.locateEntries(cdBytes, setOf("payload.bin"))
            .getValue("payload.bin")

        val off = entry.localHeaderOffset.toInt()
        val probe = zip.copyOfRange(off, minOf(zip.size, off + 256))
        val internal = ZipCentralDirectory.locateLocalFileOffset(probe)
        assertTrue("解析不出本地头长度：$internal", internal in 1..256L)

        val dataStart = off + internal.toInt()
        val got = zip.copyOfRange(dataStart, dataStart + payload.size)
        assertTrue("读回的数据与写入的不一致", payload.contentEquals(got))
    }

    @Test
    fun `ZIP64 结束记录能正确定位中央目录`() {
        // 布局：[0, X) 填充 · Zip64 EOCD(56) · Zip64 定位器(20) · EOCD(22)
        val x = 4096
        val cdOffset = 1000L
        val cdSize = 500L
        val buf = ByteArray(x + 56 + 20 + 22)

        fun putInt(pos: Int, v: Int) {
            buf[pos] = (v and 0xFF).toByte()
            buf[pos + 1] = ((v ushr 8) and 0xFF).toByte()
            buf[pos + 2] = ((v ushr 16) and 0xFF).toByte()
            buf[pos + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        fun putLong(pos: Int, v: Long) {
            putInt(pos, (v and 0xFFFFFFFFL).toInt())
            putInt(pos + 4, (v ushr 32).toInt())
        }

        putInt(x, 0x06064b50)            // Zip64 EOCD 签名
        putLong(x + 4, 44L)              // 本记录剩余长度
        putLong(x + 40, cdSize)          // 中央目录长度
        putLong(x + 48, cdOffset)        // 中央目录偏移

        putInt(x + 56, 0x07064b50)       // Zip64 定位器签名
        putLong(x + 56 + 8, x.toLong())  // Zip64 EOCD 记录的文件偏移

        val eocd = x + 76
        putInt(eocd, 0x06054b50)         // EOCD 签名
        putInt(eocd + 12, -1)            // 中央目录长度 = 0xFFFFFFFF（去看 ZIP64）
        putInt(eocd + 16, -1)            // 中央目录偏移 = 0xFFFFFFFF

        val cd = ZipCentralDirectory.locateCentralDirectory(buf, buf.size.toLong())
        assertEquals("中央目录偏移", cdOffset, cd.offset)
        assertEquals("中央目录长度", cdSize, cd.size)
    }

    @Test
    fun `ZIP64 扩展区能补出被置为 magic 的字段`() {
        val name = "payload.bin"
        val nameBytes = name.toByteArray()
        val extra = ByteArray(4 + 24)
        fun putInt(a: ByteArray, pos: Int, v: Int) {
            a[pos] = (v and 0xFF).toByte()
            a[pos + 1] = ((v ushr 8) and 0xFF).toByte()
            a[pos + 2] = ((v ushr 16) and 0xFF).toByte()
            a[pos + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        fun putLong(a: ByteArray, pos: Int, v: Long) {
            putInt(a, pos, (v and 0xFFFFFFFFL).toInt())
            putInt(a, pos + 4, (v ushr 32).toInt())
        }
        putInt(extra, 0, 0x0001)          // 扩展区 id
        putInt(extra, 2, 24)              // 扩展区长度
        putLong(extra, 4, 12345L)         // 原始长度
        putLong(extra, 12, 6789L)         // 压缩长度
        putLong(extra, 20, 999L)          // 本地头偏移

        val buf = ByteArray(46 + nameBytes.size + extra.size)
        putInt(buf, 0, 0x02014b50)        // CENSIG
        putInt(buf, 10, 0)                // method = Stored（putInt 写的是低 2 字节）
        putInt(buf, 20, -1)               // 压缩长度 = magic
        putInt(buf, 24, -1)               // 原始长度 = magic
        putInt(buf, 28, nameBytes.size)   // 文件名长度
        putInt(buf, 30, extra.size)       // 扩展区长度
        putInt(buf, 42, -1)               // 本地头偏移 = magic
        nameBytes.copyInto(buf, 46)
        extra.copyInto(buf, 46 + nameBytes.size)

        val e = ZipCentralDirectory.locateEntries(buf, setOf(name)).getValue(name)
        assertEquals("原始长度", 12345L, e.uncompressedSize)
        assertEquals("压缩长度", 6789L, e.compressedSize)
        assertEquals("本地头偏移", 999L, e.localHeaderOffset)
        assertEquals("压缩方式", 0, e.method)
    }

    @Test
    fun `垃圾字节不会假装成功`() {
        val junk = ByteArray(1024) { 0x5A }
        val cd = ZipCentralDirectory.locateCentralDirectory(junk, junk.size.toLong())
        assertTrue("垃圾输入不该给出偏移，实际 ${cd.offset}", cd.offset < 0)
        assertTrue(ZipCentralDirectory.locateEntries(junk, setOf("payload.bin")).isEmpty())
        assertEquals(-1L, ZipCentralDirectory.locateLocalFileOffset(junk))
    }

    @Test
    fun `boot 候选条目表以 payload 优先且无重复`() {
        val c = ZipCentralDirectory.BOOT_ENTRY_CANDIDATES
        assertEquals("payload.bin 必须优先（只有它带分区清单）", "payload.bin", c.first())
        assertEquals("不应有重复项", c.size, c.toSet().size)
        assertTrue(c.contains("boot.img"))
        assertTrue(c.contains("init_boot.img"))
    }
}
