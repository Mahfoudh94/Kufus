package io.github.drlacheheb.kufus.disk

internal const val NTFS_RECORD_MAGIC_FILE = 0x454C4946 // "FILE"
internal const val NTFS_RECORD_MAGIC_INDX = 0x58444E49 // "INDX"
internal const val NTFS_MFT_RECORD_BYTES = 1024
internal const val NTFS_INDEX_RECORD_BYTES = 4096
internal const val NTFS_MFT_RECORD_COUNT = 27
internal const val NTFS_BOOT_BYTES = 8192
internal const val NTFS_USA_STRIDE = 512
internal const val NTFS_DEFAULT_TIMESTAMP = 0x01DB_3E1C_4D00_0000L

internal const val MFT_FLAG_IN_USE = 0x01
internal const val MFT_FLAG_DIRECTORY = 0x02
internal const val MFT_FLAG_VIEW_INDEX = 0x08

internal const val NTFS_ATTR_STD_INFO = 0x10
internal const val NTFS_ATTR_FILE_NAME = 0x30
internal const val NTFS_ATTR_SECURITY_DESC = 0x50
internal const val NTFS_ATTR_VOLUME_NAME = 0x60
internal const val NTFS_ATTR_VOLUME_INFO = 0x70
internal const val NTFS_ATTR_DATA = 0x80
internal const val NTFS_ATTR_INDEX_ROOT = 0x90
internal const val NTFS_ATTR_INDEX_ALLOC = 0xA0
internal const val NTFS_ATTR_BITMAP = 0xB0

internal const val FILE_ATTR_SYSTEM_HIDDEN = 0x0000_0006
internal const val FILE_ATTR_HIDDEN_SYS_ARCHIVE = 0x0000_0026
internal const val FILE_ATTR_DIR_SYSTEM = 0x1000_0006
internal const val FILE_ATTR_VIEW_SYSTEM = 0x2000_0006
internal const val FILE_ATTR_VIEW_DIR_SYSTEM = 0x2000_0026

/**
 * Low-level binary encoding primitives for NTFS MFT `FILE` records, resident/non-resident attributes,
 * data run-lists, filename `INDEX_ROOT`/`INDX` entries, and USA sector fixups.
 */
internal object NtfsRecordCodec {
    class MftAttrBuilder(
        private val record: ByteArray,
        initialOffset: Int,
    ) {
        var cursor: Int = initialOffset
        var nextAttrId: Int = 0

        fun addResident(
            type: Int,
            name: String,
            value: ByteArray,
        ) {
            val nameLen = name.length
            val attrId = nextAttrId++
            val nameOffset = 24
            val valOffset = ((nameOffset + nameLen * 2 + 7) / 8) * 8
            val attrLen = ((valOffset + value.size + 7) / 8) * 8
            writeLeInt(record, cursor, type)
            writeLeInt(record, cursor + 4, attrLen)
            record[cursor + 8] = 0
            record[cursor + 9] = nameLen.toByte()
            writeLeShort(record, cursor + 10, nameOffset)
            writeLeShort(record, cursor + 14, attrId)
            writeLeInt(record, cursor + 16, value.size)
            writeLeShort(record, cursor + 20, valOffset)
            if (type == NTFS_ATTR_FILE_NAME) {
                record[cursor + 22] = 1 // RESIDENT_ATTR_IS_INDEXED
            }
            if (nameLen > 0) {
                FormatBlockIo.writeUtf16Le(record, cursor + nameOffset, name)
            }
            value.copyInto(record, cursor + valOffset)
            cursor += attrLen
        }

        fun addNonResident(
            type: Int,
            name: String,
            clusterCount: Long,
            realSize: Long,
            allocSize: Long,
            runList: ByteArray,
            initializedSize: Long = realSize,
        ) {
            val nameLen = name.length
            val attrId = nextAttrId++
            val nameOffset = 64
            val runOffset = nameOffset + nameLen * 2
            val attrLen = ((runOffset + runList.size + 7) / 8) * 8
            writeLeInt(record, cursor, type)
            writeLeInt(record, cursor + 4, attrLen)
            record[cursor + 8] = 1
            record[cursor + 9] = nameLen.toByte()
            writeLeShort(record, cursor + 10, nameOffset)
            writeLeShort(record, cursor + 14, attrId)
            writeLeLong(record, cursor + 16, 0L)
            writeLeLong(record, cursor + 24, (clusterCount - 1L).coerceAtLeast(0L))
            writeLeShort(record, cursor + 32, runOffset)
            writeLeLong(record, cursor + 40, allocSize)
            writeLeLong(record, cursor + 48, realSize)
            writeLeLong(record, cursor + 56, initializedSize)
            if (nameLen > 0) {
                FormatBlockIo.writeUtf16Le(record, cursor + nameOffset, name)
            }
            runList.copyInto(record, cursor + runOffset)
            cursor += attrLen
        }
    }

    fun buildMftRecord(
        recordNumber: Int,
        seqNumber: Int,
        linkCount: Int,
        flags: Int,
        populate: (MftAttrBuilder) -> Unit,
    ): ByteArray {
        val rec = ByteArray(NTFS_MFT_RECORD_BYTES)
        writeLeInt(rec, 0, NTFS_RECORD_MAGIC_FILE)
        writeLeShort(rec, 4, 48) // usaOffset
        writeLeShort(rec, 6, NTFS_MFT_RECORD_BYTES / NTFS_USA_STRIDE + 1) // 3
        writeLeShort(rec, 16, seqNumber)
        writeLeShort(rec, 18, linkCount)
        writeLeShort(rec, 20, 56) // attrsOffset
        writeLeShort(rec, 22, flags)
        writeLeInt(rec, 28, NTFS_MFT_RECORD_BYTES)
        writeLeInt(rec, 44, recordNumber)

        val builder = MftAttrBuilder(rec, initialOffset = 56)
        populate(builder)
        writeLeInt(rec, builder.cursor, -1) // 0xFFFF_FFFF
        val bytesUsed = builder.cursor + 8
        writeLeInt(rec, 24, bytesUsed)
        writeLeShort(rec, 40, builder.nextAttrId)
        return rec
    }

    fun buildStandardInfo(
        fileAttrs: Int,
        securityId: Int?,
    ): ByteArray {
        val size = if (securityId != null) 72 else 48
        return ByteArray(size).apply {
            writeLeLong(this, 0, NTFS_DEFAULT_TIMESTAMP)
            writeLeLong(this, 8, NTFS_DEFAULT_TIMESTAMP)
            writeLeLong(this, 16, NTFS_DEFAULT_TIMESTAMP)
            writeLeLong(this, 24, NTFS_DEFAULT_TIMESTAMP)
            writeLeInt(this, 32, fileAttrs)
            if (securityId != null) {
                writeLeInt(this, 52, securityId)
            }
        }
    }

    fun buildFileName(
        parentMftIdx: Long,
        name: String,
        fileAttrs: Int,
        allocSize: Long,
        realSize: Long,
    ): ByteArray {
        val seq = if (parentMftIdx == 0L) 1L else parentMftIdx
        return ByteArray(66 + name.length * 2).apply {
            writeLeLong(this, 0, parentMftIdx or (seq shl 48))
            writeLeLong(this, 8, NTFS_DEFAULT_TIMESTAMP)
            writeLeLong(this, 16, NTFS_DEFAULT_TIMESTAMP)
            writeLeLong(this, 24, NTFS_DEFAULT_TIMESTAMP)
            writeLeLong(this, 32, NTFS_DEFAULT_TIMESTAMP)
            writeLeLong(this, 40, allocSize)
            writeLeLong(this, 48, realSize)
            writeLeInt(this, 56, fileAttrs)
            this[64] = name.length.toByte()
            this[65] = 3 // FILE_NAME_WIN32_AND_DOS
            FormatBlockIo.writeUtf16Le(this, 66, name)
        }
    }

    fun buildFilenameIndexRoot(entries: List<Pair<Long, ByteArray>>): ByteArray {
        val entriesLen = entries.sumOf { (_, key) -> ((16 + key.size + 7) / 8) * 8 } + 16
        val total = 32 + entriesLen
        val out = ByteArray(total)
        writeLeInt(out, 0, NTFS_ATTR_FILE_NAME)
        writeLeInt(out, 4, 1)
        writeLeInt(out, 8, NTFS_INDEX_RECORD_BYTES)
        out[12] = 1
        writeLeInt(out, 16, 16)
        writeLeInt(out, 20, 16 + entriesLen)
        writeLeInt(out, 24, 16 + entriesLen)
        var pos = 32
        for ((mftIdx, key) in entries) {
            pos += writeIndexEntry(out, pos, mftIdx, key)
        }
        writeLeShort(out, pos + 8, 16)
        out[pos + 12] = 2
        return out
    }

    fun writeIndexEntry(
        dest: ByteArray,
        offset: Int,
        mftIdx: Long,
        fileNameKey: ByteArray,
    ): Int {
        val entryLen = ((16 + fileNameKey.size + 7) / 8) * 8
        val seq =
            when {
                mftIdx == 0L -> 1L
                mftIdx >= 24L -> 1L
                else -> mftIdx
            }
        writeLeLong(dest, offset, mftIdx or (seq shl 48))
        writeLeShort(dest, offset + 8, entryLen)
        writeLeShort(dest, offset + 10, fileNameKey.size)
        fileNameKey.copyInto(dest, offset + 16)
        return entryLen
    }

    fun encodeRun(
        startLcn: Long,
        clusterCount: Long,
    ): ByteArray {
        val lenBytes = minSignedOrUnsignedByteCount(clusterCount, signed = false)
        val offBytes = minSignedOrUnsignedByteCount(startLcn, signed = true)
        val out = ByteArray(1 + lenBytes + offBytes + 1)
        out[0] = ((offBytes shl 4) or lenBytes).toByte()
        writeMinimalLe(out, 1, clusterCount, lenBytes)
        writeMinimalLe(out, 1 + lenBytes, startLcn, offBytes)
        return out
    }

    fun encodeSparseRun(clusterCount: Long): ByteArray {
        val lenBytes = minSignedOrUnsignedByteCount(clusterCount, signed = false)
        val out = ByteArray(1 + lenBytes + 1)
        out[0] = lenBytes.toByte()
        writeMinimalLe(out, 1, clusterCount, lenBytes)
        return out
    }

    fun applyUsaFixup(
        record: ByteArray,
        usn: Int,
    ) {
        val usaOffset = readUInt16LeInt(record, 4)
        val usaCount = readUInt16LeInt(record, 6)
        writeLeShort(record, usaOffset, usn)
        for (secIdx in 1 until usaCount) {
            val tailPos = secIdx * NTFS_USA_STRIDE - 2
            record[usaOffset + secIdx * 2] = record[tailPos]
            record[usaOffset + secIdx * 2 + 1] = record[tailPos + 1]
            writeLeShort(record, tailPos, usn)
        }
    }

    /** INDEX_ROOT "clusters per index block": cluster count, or 512-byte block count when clusters exceed the index block. */
    fun indexRootClustersPerBlock(clusterBytes: Int): Byte =
        if (clusterBytes <= NTFS_INDEX_RECORD_BYTES) {
            (NTFS_INDEX_RECORD_BYTES / clusterBytes).toByte()
        } else {
            (NTFS_INDEX_RECORD_BYTES / NTFS_USA_STRIDE).toByte()
        }

    fun patchIndexRootBlockSize(
        record: ByteArray,
        indexBlockClusters: Byte,
    ) {
        var attr = readUInt16LeInt(record, 20)
        while (attr + 8 <= record.size) {
            val type = readUInt32LeLong(record, attr).toInt()
            if (type == -1) return
            if (type == NTFS_ATTR_INDEX_ROOT && record[attr + 8].toInt() == 0) {
                record[attr + readUInt16LeInt(record, attr + 20) + 12] = indexBlockClusters
            }
            attr += readUInt32LeLong(record, attr + 4).toInt()
        }
    }

    fun encodeRecordSizeField(
        clusterBytes: Int,
        recordBytes: Int,
    ): Int {
        if (clusterBytes <= recordBytes) {
            return recordBytes / clusterBytes
        }
        var shift = 0
        var v = recordBytes
        while (v > 1) {
            v = v ushr 1
            shift++
        }
        return -shift
    }

    private fun minSignedOrUnsignedByteCount(
        value: Long,
        signed: Boolean,
    ): Int {
        var count = 1
        while (count < 8) {
            val bits = count * 8
            val maxVal = if (signed) (1L shl (bits - 1)) - 1L else (1L shl bits) - 1L
            if (value in 0L..maxVal) return count
            count++
        }
        return 8
    }

    private fun writeMinimalLe(
        dest: ByteArray,
        offset: Int,
        value: Long,
        byteCount: Int,
    ) {
        for (i in 0 until byteCount) {
            dest[offset + i] = ((value ushr (i * 8)) and 0xFFL).toByte()
        }
    }
}
