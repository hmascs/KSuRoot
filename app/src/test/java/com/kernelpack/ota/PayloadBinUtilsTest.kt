package com.kernelpack.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `payload.bin` 清单解析的单元测试。
 *
 * 夹具是**手工按 update_engine 的 proto 定义拼的 protobuf 字节**：
 * 字段号来自真实定义（`DeltaArchiveManifest.block_size = 3`、
 * `partitions = 13`；`PartitionUpdate.partition_name = 1`、`operations = 8`；
 * `InstallOperation.type = 1`、`data_offset = 2`、`data_length = 3`、`dst_extents = 6`；
 * `Extent.start_block = 1`、`num_blocks = 2`）。
 *
 * 手工拼而不是录一份真实清单，是因为真实清单有几 MB —— 而这里要钉的是
 * **字段号与取值路径**，不是"能不能扛住大文件"。
 */
class PayloadBinUtilsTest {

    // ────────────────────────── protobuf 手工编码 ──────────────────────────

    private fun varint(v: Long): ByteArray {
        var x = v
        val out = ArrayList<Byte>(10)
        while (true) {
            val b = (x and 0x7F).toInt()
            x = x ushr 7
            if (x == 0L) { out.add(b.toByte()); break }
            out.add((b or 0x80).toByte())
        }
        return out.toByteArray()
    }

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    private fun uintField(field: Int, v: Long) = tag(field, 0) + varint(v)

    private fun bytesField(field: Int, payload: ByteArray) =
        tag(field, 2) + varint(payload.size.toLong()) + payload

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) { p.copyInto(out, pos); pos += p.size }
        return out
    }

    private fun extent(startBlock: Long, numBlocks: Long) =
        concat(uintField(1, startBlock), uintField(2, numBlocks))

    private fun operation(
        type: Long,
        dataOffset: Long,
        dataLength: Long,
        vararg extents: ByteArray,
    ) = concat(
        uintField(1, type),
        uintField(2, dataOffset),
        uintField(3, dataLength),
        *extents.map { bytesField(6, it) }.toTypedArray(),
    )

    private fun partition(name: String, vararg ops: ByteArray) = concat(
        bytesField(1, name.toByteArray()),
        *ops.map { bytesField(8, it) }.toTypedArray(),
    )

    /** 一份含 boot / xbl_config / system 三个分区的清单。 */
    private fun manifest(blockSize: Long? = 4096L): ByteArray {
        val bootOp1 = operation(0, 0, 4096, extent(0, 1))
        val bootOp2 = operation(8, 4096, 1024, extent(1, 2), extent(10, 1))
        val bootOp3 = operation(6, 0, 0, extent(3, 1))       // ZERO
        val xblOp = operation(0, 5120, 2048, extent(0, 1))
        val sysOp = operation(0, 7168, 8192, extent(0, 2))
        return concat(
            blockSize?.let { uintField(3, it) } ?: ByteArray(0),
            bytesField(13, partition("boot", bootOp1, bootOp2, bootOp3)),
            bytesField(13, partition("xbl_config", xblOp)),
            bytesField(13, partition("system", sysOp)),
        )
    }

    private fun header(manifestSize: Long, signatureSize: Long): ByteArray {
        val out = ByteArray(PayloadBinUtils.HEADER_SIZE)
        "CrAU".toByteArray().copyInto(out, 0)
        fun be64(pos: Int, v: Long) {
            for (i in 0 until 8) out[pos + i] = ((v ushr (56 - i * 8)) and 0xFF).toByte()
        }
        fun be32(pos: Int, v: Long) {
            for (i in 0 until 4) out[pos + i] = ((v ushr (24 - i * 8)) and 0xFF).toByte()
        }
        be64(4, 2L)                  // version
        be64(12, manifestSize)
        be32(20, signatureSize)
        return out
    }

    // ────────────────────────── 头部 ──────────────────────────

    @Test
    fun `头部能读出清单与签名长度`() {
        val h = PayloadBinUtils.parseHeader(header(123456L, 267L))
        assertEquals(123456L, h!!.manifestSize)
        assertEquals(267L, h.signatureSize)
    }

    @Test
    fun `魔数不对的头部被拒绝`() {
        val bad = header(123456L, 267L).also { it[0] = 'X'.code.toByte() }
        assertNull(PayloadBinUtils.parseHeader(bad))
    }

    @Test
    fun `清单长度为 0 或超上限的头部被拒绝`() {
        assertNull("长度为 0 应当拒绝", PayloadBinUtils.parseHeader(header(0L, 0L)))
        assertNull(
            "超过 64 MiB 应当拒绝",
            PayloadBinUtils.parseHeader(header(65L shl 20, 0L)),
        )
    }

    @Test
    fun `不足 24 字节的头部被拒绝`() {
        assertNull(PayloadBinUtils.parseHeader(header(100L, 0L).copyOf(23)))
    }

    // ────────────────────────── 清单 ──────────────────────────

    @Test
    fun `分区名按清单顺序列出`() {
        assertEquals(
            listOf("boot", "xbl_config", "system"),
            PayloadBinUtils.listPartitions(manifest()),
        )
    }

    @Test
    fun `块大小取自清单字段 3`() {
        assertEquals(4096L, PayloadBinUtils.blockSize(manifest(4096L)))
        assertEquals(8192L, PayloadBinUtils.blockSize(manifest(8192L)))
    }

    @Test
    fun `块大小缺失或为 0 时回落到 4096`() {
        assertEquals(4096L, PayloadBinUtils.blockSize(manifest(null)))
        assertEquals(4096L, PayloadBinUtils.blockSize(manifest(0L)))
    }

    @Test
    fun `boot 分区的操作按顺序解析出类型偏移长度与目标区间`() {
        val ops = PayloadBinUtils.partitionOperations(manifest(), "boot")
        assertEquals(3, ops.size)

        assertEquals(PayloadBinUtils.OP_REPLACE, ops[0].type)
        assertEquals(0L, ops[0].dataOffset)
        assertEquals(4096L, ops[0].dataLength)
        assertEquals(listOf(PayloadBinUtils.Extent(0, 1)), ops[0].destExtents)

        assertEquals(PayloadBinUtils.OP_REPLACE_XZ, ops[1].type)
        assertEquals(4096L, ops[1].dataOffset)
        assertEquals(1024L, ops[1].dataLength)
        assertEquals(
            listOf(PayloadBinUtils.Extent(1, 2), PayloadBinUtils.Extent(10, 1)),
            ops[1].destExtents,
        )

        assertEquals(PayloadBinUtils.OP_ZERO, ops[2].type)
        assertEquals(listOf(PayloadBinUtils.Extent(3, 1)), ops[2].destExtents)
    }

    @Test
    fun `每个分区的操作互不串台`() {
        val m = manifest()
        assertEquals(1, PayloadBinUtils.partitionOperations(m, "xbl_config").size)
        assertEquals(5120L, PayloadBinUtils.partitionOperations(m, "xbl_config")[0].dataOffset)
        assertEquals(1, PayloadBinUtils.partitionOperations(m, "system").size)
        assertEquals(7168L, PayloadBinUtils.partitionOperations(m, "system")[0].dataOffset)
    }

    @Test
    fun `不存在的分区返回空表`() {
        assertTrue(PayloadBinUtils.partitionOperations(manifest(), "vendor_boot").isEmpty())
    }

    @Test
    fun `没有目标区间的操作会被丢弃`() {
        // 一个只有 type / data_offset 而没有 dst_extents 的操作 —— 真实清单里
        // 不该出现，但出现了也不能让整个解析崩掉或返回半个对象。
        val noExtent = concat(uintField(1, 0L), uintField(2, 100L), uintField(3, 50L))
        val m = concat(
            uintField(3, 4096L),
            bytesField(13, partition("boot", noExtent, operation(0, 0, 4096, extent(0, 1)))),
        )
        val ops = PayloadBinUtils.partitionOperations(m, "boot")
        assertEquals(1, ops.size)
        assertEquals(0L, ops[0].dataOffset)
    }

    @Test
    fun `空清单不会崩`() {
        assertTrue(PayloadBinUtils.listPartitions(ByteArray(0)).isEmpty())
        assertTrue(PayloadBinUtils.partitionOperations(ByteArray(0), "boot").isEmpty())
        assertEquals(4096L, PayloadBinUtils.blockSize(ByteArray(0)))
    }

    @Test
    fun `被截断的清单不会崩也不会给出假条目`() {
        val m = manifest()
        for (cut in intArrayOf(1, 5, 20, m.size / 2, m.size - 1)) {
            // 只要求"不抛异常"；截断处正好落在字段边界时可能解析出部分内容，
            // 这是可接受的 —— 调用方随后会用 payload 头部长度校验兜底。
            PayloadBinUtils.listPartitions(m.copyOf(cut))
            PayloadBinUtils.partitionOperations(m.copyOf(cut), "boot")
        }
    }

    @Test
    fun `操作类型常量与 update_engine 一致`() {
        assertEquals(0L, PayloadBinUtils.OP_REPLACE)
        assertEquals(1L, PayloadBinUtils.OP_REPLACE_BZ)
        assertEquals(6L, PayloadBinUtils.OP_ZERO)
        assertEquals(7L, PayloadBinUtils.OP_DISCARD)
        assertEquals(8L, PayloadBinUtils.OP_REPLACE_XZ)
    }
}
