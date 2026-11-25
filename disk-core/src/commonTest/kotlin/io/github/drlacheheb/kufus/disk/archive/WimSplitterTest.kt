package io.github.drlacheheb.kufus.disk.archive

import io.github.drlacheheb.kufus.disk.io.FlashStepException
import io.github.drlacheheb.kufus.disk.io.readUInt16LeInt
import io.github.drlacheheb.kufus.disk.io.readUInt32LeLong
import io.github.drlacheheb.kufus.disk.io.readUInt64LeLong
import io.github.drlacheheb.kufus.disk.io.writeLeInt
import io.github.drlacheheb.kufus.disk.io.writeLeLong
import io.github.drlacheheb.kufus.disk.io.writeLeShort
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WimSplitterTest {
    @Test
    fun splitsSyntheticWimAcrossMultipleSwmVolumesAndPreservesHeadersAndPayloads() {
        val metaChunk = ByteArray(100_000) { idx -> (idx % 251).toByte() }
        val data1 = ByteArray(600_000) { idx -> ((idx * 3) % 251).toByte() }
        val data2 = ByteArray(700_000) { idx -> ((idx * 7) % 251).toByte() }
        val data3 = ByteArray(600_000) { idx -> ((idx * 11) % 251).toByte() }
        val xmlBytes = "<WIM><TOTALBYTES>2000000</TOTALBYTES></WIM>".encodeToByteArray()

        val sourceWim = buildSyntheticWim(metaChunk, listOf(data1, data2, data3), xmlBytes)
        val partBuffers = mutableMapOf<String, ByteArray>()

        val partsWritten =
            WimSplitter.split(
                reader = TestByteReader(sourceWim),
                maxPartBytes = 1_100_000L,
            ) { _, name ->
                val acc = mutableListOf<Byte>()
                WimPartSinkImpl(name, acc) { fileName, data -> partBuffers[fileName] = data }
            }

        partsWritten shouldBe 3
        partBuffers.keys shouldBe setOf("install.swm", "install2.swm", "install3.swm")

        val p1 = partBuffers.getValue("install.swm")
        val p2 = partBuffers.getValue("install2.swm")
        val p3 = partBuffers.getValue("install3.swm")

        readUInt16LeInt(p1, 0x28) shouldBe 1
        readUInt16LeInt(p1, 0x2A) shouldBe 3
        readUInt16LeInt(p2, 0x28) shouldBe 2
        readUInt16LeInt(p2, 0x2A) shouldBe 3
        readUInt16LeInt(p3, 0x28) shouldBe 3
        readUInt16LeInt(p3, 0x2A) shouldBe 3
        (readUInt32LeLong(p1, 0x10) and 0x08L) shouldBe 0x08L
        (readUInt32LeLong(p2, 0x10) and 0x08L) shouldBe 0x08L
        (readUInt32LeLong(p3, 0x10) and 0x08L) shouldBe 0x08L

        // Each part's lookup table and XML resource headers carry RESHDR_FLAG_METADATA (0x02)
        ((readUInt64LeLong(p1, 0x30) ushr 56) and 0xFFL) shouldBe 0x02L
        ((readUInt64LeLong(p1, 0x48) ushr 56) and 0xFFL) shouldBe 0x02L
        ((readUInt64LeLong(p2, 0x30) ushr 56) and 0xFFL) shouldBe 0x02L
        ((readUInt64LeLong(p2, 0x48) ushr 56) and 0xFFL) shouldBe 0x02L

        // Part 1 lookup table contains ONLY part 1 resources (meta + data1) with original flags preserved
        val lookup1Offset = readUInt64LeLong(p1, 0x30 + 8).toInt()
        val lookup1Size = (readUInt64LeLong(p1, 0x30) and 0x00FF_FFFF_FFFF_FFFFL).toInt()
        lookup1Size shouldBe 2 * 50
        readUInt16LeInt(p1, lookup1Offset + 24) shouldBe 1 // meta
        ((readUInt64LeLong(p1, lookup1Offset) ushr 56) and 0xFFL) shouldBe 0x02L
        readUInt16LeInt(p1, lookup1Offset + 50 + 24) shouldBe 1 // data1
        ((readUInt64LeLong(p1, lookup1Offset + 50) ushr 56) and 0xFFL) shouldBe 0x04L
        val reconstructedMetaOff = readUInt64LeLong(p1, lookup1Offset + 8).toInt()
        val reconstructedData1Off = readUInt64LeLong(p1, lookup1Offset + 50 + 8).toInt()
        p1.copyOfRange(reconstructedMetaOff, reconstructedMetaOff + metaChunk.size) shouldBe metaChunk
        p1.copyOfRange(reconstructedData1Off, reconstructedData1Off + data1.size) shouldBe data1

        // Part 2 lookup table contains ONLY part 2 resources (data2)
        val lookup2Offset = readUInt64LeLong(p2, 0x30 + 8).toInt()
        val lookup2Size = (readUInt64LeLong(p2, 0x30) and 0x00FF_FFFF_FFFF_FFFFL).toInt()
        lookup2Size shouldBe 50
        readUInt16LeInt(p2, lookup2Offset + 24) shouldBe 2
        ((readUInt64LeLong(p2, lookup2Offset) ushr 56) and 0xFFL) shouldBe 0x04L
        val reconstructedData2Off = readUInt64LeLong(p2, lookup2Offset + 8).toInt()
        p2.copyOfRange(reconstructedData2Off, reconstructedData2Off + data2.size) shouldBe data2

        // Part 3 lookup table contains ONLY part 3 resources (data3)
        val lookup3Offset = readUInt64LeLong(p3, 0x30 + 8).toInt()
        val lookup3Size = (readUInt64LeLong(p3, 0x30) and 0x00FF_FFFF_FFFF_FFFFL).toInt()
        lookup3Size shouldBe 50
        readUInt16LeInt(p3, lookup3Offset + 24) shouldBe 3
        ((readUInt64LeLong(p3, lookup3Offset) ushr 56) and 0xFFL) shouldBe 0x04L
        val reconstructedData3Off = readUInt64LeLong(p3, lookup3Offset + 8).toInt()
        p3.copyOfRange(reconstructedData3Off, reconstructedData3Off + data3.size) shouldBe data3

        // Boot metadata & boot index are remapped on part 1 and cleared on parts 2 & 3
        readUInt32LeLong(p1, 0x78) shouldBe 1L
        readUInt64LeLong(p1, 0x60 + 8) shouldBe reconstructedMetaOff.toLong()
        readUInt32LeLong(p2, 0x78) shouldBe 0L
        readUInt64LeLong(p2, 0x60) shouldBe 0L
        readUInt32LeLong(p3, 0x78) shouldBe 0L
        readUInt64LeLong(p3, 0x60) shouldBe 0L
    }

    @Test
    fun allowsCompressedNonSolidWimWithStandardCompressionHeaderFlag() {
        val metaChunk = ByteArray(1024) { 0x11 }
        val data1 = ByteArray(4096) { 0x22 }
        val data2 = ByteArray(4096) { 0x33 }
        val xmlBytes = "<WIM><IMAGE INDEX=\"1\"/></WIM>".encodeToByteArray()
        val wimBytes = buildSyntheticWim(metaChunk, listOf(data1, data2), xmlBytes)
        // Standard Microsoft XPRESS/RP_FIX compressed WIM flags (0x40082 includes FLAG_HEADER_COMPRESSION = 0x2)
        writeLeInt(wimBytes, 0x10, 0x00040082)

        val summary = WimSplitter.inspectSplitCapability(TestByteReader(wimBytes), maxPartBytes = 20_000L)
        summary.isSolidOrUnsupported shouldBe false
    }

    @Test
    fun rejectsSolidOrOversizedSingleResourceWimGracefully() {
        val solidWim = ByteArray(256)
        "MSWIM\u0000\u0000\u0000".encodeToByteArray().copyInto(solidWim, destinationOffset = 0)
        writeLeInt(solidWim, 8, 208)
        writeLeInt(solidWim, 0x10, 0x20000000)

        WimSplitter.inspectSplitCapability(TestByteReader(solidWim)).isSolidOrUnsupported shouldBe true
        assertFailsWith<FlashStepException> {
            WimSplitter.split(TestByteReader(solidWim)) { _, name -> WimPartSinkImpl(name, mutableListOf()) { _, _ -> } }
        }.code shouldBe "UNSUPPORTED_SOLID_WIM"

        val normalWim =
            buildSyntheticWim(
                meta = ByteArray(2048) { 0x11 },
                dataBlobs = listOf(ByteArray(10_000) { 0x22 }),
                xmlBytes = "<WIM/>".encodeToByteArray(),
            )
        // Single blob (10_000) plus header/lookup/xml overhead exceeds 10_100 limit
        WimSplitter.inspectSplitCapability(TestByteReader(normalWim), maxPartBytes = 10_100L).isSolidOrUnsupported shouldBe true
        assertFailsWith<FlashStepException> {
            WimSplitter.split(TestByteReader(normalWim), maxPartBytes = 10_100L) { _, name ->
                WimPartSinkImpl(name, mutableListOf()) { _, _ -> }
            }
        }.code shouldBe "WIM_ENTRY_TOO_LARGE"

        // Already-spanned .swm part cannot be re-split in isolation
        val alreadySpanned = normalWim.copyOf()
        writeLeShort(alreadySpanned, 0x2A, 2)
        WimSplitter.inspectSplitCapability(TestByteReader(alreadySpanned)).isSolidOrUnsupported shouldBe true
    }

    private fun buildSyntheticWim(
        meta: ByteArray,
        dataBlobs: List<ByteArray>,
        xmlBytes: ByteArray,
    ): ByteArray {
        val headerSize = 208
        val allBlobs = listOf(meta to 0x02) + dataBlobs.map { it to 0x04 }
        val payloadTotal = allBlobs.sumOf { it.first.size }
        val lookupSize = allBlobs.size * 50
        val totalSize = headerSize + payloadTotal + lookupSize + xmlBytes.size
        val out = ByteArray(totalSize)

        "MSWIM\u0000\u0000\u0000".encodeToByteArray().copyInto(out, destinationOffset = 0)
        writeLeInt(out, 8, headerSize)
        writeLeInt(out, 12, 0x00010D00)
        writeLeInt(out, 0x10, 0x00000000)
        for (i in 0 until 16) out[0x18 + i] = (i + 1).toByte()
        writeLeShort(out, 0x28, 1)
        writeLeShort(out, 0x2A, 1)
        writeLeInt(out, 0x2C, 1)

        var cursor = headerSize
        val offsets = mutableListOf<Int>()
        for ((blob, _) in allBlobs) {
            offsets.add(cursor)
            blob.copyInto(out, destinationOffset = cursor)
            cursor += blob.size
        }

        val lookupOffset = cursor
        for (index in allBlobs.indices) {
            val (blob, flags) = allBlobs[index]
            val entryOff = lookupOffset + index * 50
            val packedSizeFlags = blob.size.toLong() or ((flags.toLong() and 0xFFL) shl 56)
            writeLeLong(out, entryOff, packedSizeFlags)
            writeLeLong(out, entryOff + 8, offsets[index].toLong())
            writeLeLong(out, entryOff + 16, blob.size.toLong())
            writeLeShort(out, entryOff + 24, 1)
            writeLeInt(out, entryOff + 26, 1)
            out[entryOff + 30] = (index + 1).toByte()
        }
        cursor += lookupSize
        xmlBytes.copyInto(out, destinationOffset = cursor)

        writeLeLong(out, 0x30, lookupSize.toLong() or (0x02L shl 56))
        writeLeLong(out, 0x38, lookupOffset.toLong())
        writeLeLong(out, 0x40, lookupSize.toLong())
        writeLeLong(out, 0x48, xmlBytes.size.toLong() or (0x02L shl 56))
        writeLeLong(out, 0x50, cursor.toLong())
        writeLeLong(out, 0x58, xmlBytes.size.toLong())
        writeLeLong(out, 0x60, meta.size.toLong() or (0x02L shl 56))
        writeLeLong(out, 0x68, offsets[0].toLong())
        writeLeLong(out, 0x70, meta.size.toLong())
        writeLeInt(out, 0x78, 1)
        return out
    }

    private class WimPartSinkImpl(
        private val name: String,
        private val acc: MutableList<Byte>,
        private val onClose: (String, ByteArray) -> Unit,
    ) : WimPartSink {
        override fun write(
            bytes: ByteArray,
            length: Int,
        ) {
            for (i in 0 until length) acc.add(bytes[i])
        }

        override fun close() {
            onClose(name, acc.toByteArray())
        }
    }
}
