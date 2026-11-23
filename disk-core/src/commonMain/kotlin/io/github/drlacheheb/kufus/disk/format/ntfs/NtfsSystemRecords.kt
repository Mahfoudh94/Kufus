package io.github.drlacheheb.kufus.disk.format.ntfs

import io.github.drlacheheb.kufus.disk.io.FormatBlockIo
import io.github.drlacheheb.kufus.disk.io.writeLeInt
import io.github.drlacheheb.kufus.disk.io.writeLeShort

private const val MFT_FIRST_SYSTEM_END = 12
private const val MFT_RESERVED_END = 16
private const val MFT_UNUSED_END = 24

/**
 * Assembles the initial NTFS system MFT table (`$MFT` through `$Reparse` plus cluster-padding
 * unused records) and the root directory `$I30` `INDX` block using [NtfsRecordCodec].
 */
internal object NtfsSystemRecords {
    fun buildMftBytes(
        geom: NtfsGeometry,
        cleanLabel: String,
    ): ByteArray {
        val out = ByteArray(geom.mftRecordCount * NTFS_MFT_RECORD_BYTES)
        val systemRecords =
            listOf(
                buildRecordMft(geom),
                buildRecordMftMirr(geom),
                buildRecordLogFile(geom),
                buildRecordVolume(cleanLabel),
                buildRecordAttrDef(geom),
                buildRecordRoot(geom),
                buildRecordBitmap(geom),
                buildRecordBoot(geom),
                buildRecordBadClus(geom),
                buildRecordSecure(geom),
                buildRecordUpcase(geom),
                buildRecordExtend(),
            )
        val indexBlockClusters = NtfsRecordCodec.indexRootClustersPerBlock(geom.clusterBytes)
        val place = { idx: Int, rec: ByteArray ->
            NtfsRecordCodec.patchIndexRootBlockSize(rec, indexBlockClusters)
            NtfsRecordCodec.applyUsaFixup(rec, usn = 2)
            rec.copyInto(out, idx * NTFS_MFT_RECORD_BYTES)
        }
        systemRecords.forEachIndexed { idx, rec -> place(idx, rec) }
        for (idx in MFT_FIRST_SYSTEM_END until MFT_RESERVED_END) {
            place(idx, buildReservedRecord(idx))
        }
        for (idx in MFT_RESERVED_END until MFT_UNUSED_END) {
            place(idx, buildUnusedRecord(idx))
        }
        place(24, buildRecordQuota())
        place(25, buildRecordSimpleViewIndex(25, "\$ObjId", "\$O", 0))
        place(26, buildRecordSimpleViewIndex(26, "\$Reparse", "\$R", 0))
        for (idx in NTFS_MFT_RECORD_COUNT until geom.mftRecordCount) {
            place(idx, buildUnusedRecord(idx))
        }
        return out
    }

    fun buildRootIndxBlock(geom: NtfsGeometry): ByteArray {
        val indx = ByteArray(NTFS_INDEX_RECORD_BYTES)
        writeLeInt(indx, 0, NTFS_RECORD_MAGIC_INDX)
        writeLeShort(indx, 4, 40) // USA offset
        writeLeShort(indx, 6, NTFS_INDEX_RECORD_BYTES / NTFS_USA_STRIDE + 1) // 9
        // Indx header starts at 24; entries start after USA (40 + 18 = 58 -> aligned 8 = 64 -> relOffset = 40)
        val entriesOffset = 40
        val mftRealBytes = (geom.mftRecordCount * NTFS_MFT_RECORD_BYTES).toLong()
        val mftMirrRealBytes = (geom.mftMirrRecordCount * NTFS_MFT_RECORD_BYTES).toLong()
        val bootAllocBytes = FormatBlockIo.ceilDiv(NTFS_BOOT_BYTES.toLong(), geom.clusterBytes.toLong()) * geom.clusterBytes
        val sortedRefs =
            listOf(
                4L to fnEntry("\$AttrDef", FILE_ATTR_SYSTEM_HIDDEN, geom.attrDefClusters * geom.clusterBytes, NtfsSystemData.ATTRDEF_BYTE_SIZE.toLong()),
                8L to fnEntry("\$BadClus", FILE_ATTR_SYSTEM_HIDDEN, 0L, 0L),
                6L to fnEntry("\$Bitmap", FILE_ATTR_SYSTEM_HIDDEN, geom.volumeBitmapClusters * geom.clusterBytes, geom.volumeBitmapRealBytes),
                7L to fnEntry("\$Boot", FILE_ATTR_SYSTEM_HIDDEN, bootAllocBytes, NTFS_BOOT_BYTES.toLong()),
                11L to fnEntry("\$Extend", FILE_ATTR_DIR_SYSTEM, 0L, 0L),
                2L to fnEntry("\$LogFile", FILE_ATTR_SYSTEM_HIDDEN, geom.logFileClusters * geom.clusterBytes, geom.logFileClusters * geom.clusterBytes),
                0L to fnEntry("\$MFT", FILE_ATTR_SYSTEM_HIDDEN, geom.mftClusters * geom.clusterBytes, mftRealBytes),
                1L to fnEntry("\$MFTMirr", FILE_ATTR_SYSTEM_HIDDEN, geom.mftMirrClusters * geom.clusterBytes, mftMirrRealBytes),
                9L to fnEntry("\$Secure", FILE_ATTR_VIEW_SYSTEM, 0L, 0L),
                10L to fnEntry("\$UpCase", FILE_ATTR_SYSTEM_HIDDEN, 131_072L, 131_072L),
                3L to fnEntry("\$Volume", FILE_ATTR_SYSTEM_HIDDEN, 0L, 0L),
                5L to fnEntry(".", FILE_ATTR_DIR_SYSTEM, 0L, 0L),
            )
        var pos = 24 + entriesOffset
        for ((mftIdx, fnKey) in sortedRefs) {
            pos += NtfsRecordCodec.writeIndexEntry(indx, pos, mftIdx, fnKey)
        }
        // END entry (16 bytes)
        writeLeShort(indx, pos + 8, 16)
        indx[pos + 12] = 2
        pos += 16

        writeLeInt(indx, 24, entriesOffset)
        writeLeInt(indx, 28, pos - 24)
        writeLeInt(indx, 32, NTFS_INDEX_RECORD_BYTES - 24)
        NtfsRecordCodec.applyUsaFixup(indx, usn = 3)
        return indx
    }

    private fun buildRecordMft(geom: NtfsGeometry): ByteArray {
        val realSize = (geom.mftRecordCount * NTFS_MFT_RECORD_BYTES).toLong()
        val allocSize = geom.mftClusters * geom.clusterBytes
        return NtfsRecordCodec.buildMftRecord(0, 1, 1, MFT_FLAG_IN_USE) { b ->
            b.addResident(NTFS_ATTR_STD_INFO, "", NtfsRecordCodec.buildStandardInfo(FILE_ATTR_SYSTEM_HIDDEN, securityId = 0x100))
            b.addResident(NTFS_ATTR_FILE_NAME, "", NtfsRecordCodec.buildFileName(5L, "\$MFT", FILE_ATTR_SYSTEM_HIDDEN, allocSize, realSize))
            b.addNonResident(NTFS_ATTR_DATA, "", geom.mftClusters, realSize, allocSize, NtfsRecordCodec.encodeRun(geom.mftLcn, geom.mftClusters))
            b.addNonResident(NTFS_ATTR_BITMAP, "", 1L, 8L, geom.clusterBytes.toLong(), NtfsRecordCodec.encodeRun(geom.mftBitmapLcn, 1L))
        }
    }

    private fun buildRecordMftMirr(geom: NtfsGeometry): ByteArray {
        val realSize = (geom.mftMirrRecordCount * NTFS_MFT_RECORD_BYTES).toLong()
        val allocSize = geom.mftMirrClusters * geom.clusterBytes
        return NtfsRecordCodec.buildMftRecord(1, 1, 1, MFT_FLAG_IN_USE) { b ->
            b.addResident(NTFS_ATTR_STD_INFO, "", NtfsRecordCodec.buildStandardInfo(FILE_ATTR_SYSTEM_HIDDEN, securityId = 0x100))
            b.addResident(NTFS_ATTR_FILE_NAME, "", NtfsRecordCodec.buildFileName(5L, "\$MFTMirr", FILE_ATTR_SYSTEM_HIDDEN, allocSize, realSize))
            b.addNonResident(NTFS_ATTR_DATA, "", geom.mftMirrClusters, realSize, allocSize, NtfsRecordCodec.encodeRun(geom.mftMirrLcn, geom.mftMirrClusters))
        }
    }

    private fun buildRecordLogFile(geom: NtfsGeometry): ByteArray {
        val size = geom.logFileClusters * geom.clusterBytes
        return NtfsRecordCodec.buildMftRecord(2, 2, 1, MFT_FLAG_IN_USE) { b ->
            b.addResident(NTFS_ATTR_STD_INFO, "", NtfsRecordCodec.buildStandardInfo(FILE_ATTR_SYSTEM_HIDDEN, securityId = 0x100))
            b.addResident(NTFS_ATTR_FILE_NAME, "", NtfsRecordCodec.buildFileName(5L, "\$LogFile", FILE_ATTR_SYSTEM_HIDDEN, size, size))
            b.addNonResident(NTFS_ATTR_DATA, "", geom.logFileClusters, size, size, NtfsRecordCodec.encodeRun(geom.logFileLcn, geom.logFileClusters))
        }
    }

    private fun buildRecordVolume(cleanLabel: String): ByteArray {
        val volInfo =
            ByteArray(12).apply {
                this[8] = 3
                this[9] = 1
            }
        return NtfsRecordCodec.buildMftRecord(3, 3, 1, MFT_FLAG_IN_USE) { b ->
            b.addResident(NTFS_ATTR_STD_INFO, "", NtfsRecordCodec.buildStandardInfo(FILE_ATTR_SYSTEM_HIDDEN, securityId = null))
            b.addResident(NTFS_ATTR_FILE_NAME, "", NtfsRecordCodec.buildFileName(5L, "\$Volume", FILE_ATTR_SYSTEM_HIDDEN, 0L, 0L))
            b.addResident(NTFS_ATTR_SECURITY_DESC, "", NtfsSystemData.buildVolumeSecurityDescriptor())
            b.addResident(NTFS_ATTR_VOLUME_NAME, "", FormatBlockIo.encodeUtf16Le(cleanLabel))
            b.addResident(NTFS_ATTR_VOLUME_INFO, "", volInfo)
            b.addResident(NTFS_ATTR_DATA, "", ByteArray(0))
        }
    }

    private fun buildRecordAttrDef(geom: NtfsGeometry): ByteArray {
        val realSize = NtfsSystemData.ATTRDEF_BYTE_SIZE.toLong()
        val allocSize = geom.attrDefClusters * geom.clusterBytes
        return NtfsRecordCodec.buildMftRecord(4, 4, 1, MFT_FLAG_IN_USE) { b ->
            b.addResident(NTFS_ATTR_STD_INFO, "", NtfsRecordCodec.buildStandardInfo(FILE_ATTR_SYSTEM_HIDDEN, securityId = null))
            b.addResident(NTFS_ATTR_FILE_NAME, "", NtfsRecordCodec.buildFileName(5L, "\$AttrDef", FILE_ATTR_SYSTEM_HIDDEN, allocSize, realSize))
            b.addResident(NTFS_ATTR_SECURITY_DESC, "", NtfsSystemData.buildSystemReadOnlySecurityDescriptor())
            b.addNonResident(NTFS_ATTR_DATA, "", geom.attrDefClusters, realSize, allocSize, NtfsRecordCodec.encodeRun(geom.attrDefLcn, geom.attrDefClusters))
        }
    }

    private fun buildRecordRoot(geom: NtfsGeometry): ByteArray {
        val sdReal = NtfsSystemData.ROOT_SD_REAL_SIZE.toLong()
        val sdAlloc = geom.rootSdClusters * geom.clusterBytes
        val idxRealBytes = NTFS_INDEX_RECORD_BYTES.toLong()
        val idxAllocBytes = geom.rootIndexClusters * geom.clusterBytes
        val indexRoot =
            ByteArray(56).apply {
                writeLeInt(this, 0, NTFS_ATTR_FILE_NAME)
                writeLeInt(this, 4, 1) // COLLATION_FILENAME
                writeLeInt(this, 8, NTFS_INDEX_RECORD_BYTES)
                writeLeInt(this, 16, 16)
                writeLeInt(this, 20, 40)
                writeLeInt(this, 24, 40)
                this[28] = 1 // INDEX_NODE_LARGE
                writeLeShort(this, 40, 24)
                this[44] = 3 // INDEX_ENTRY_NODE | INDEX_ENTRY_END
            }
        val bitmap = ByteArray(8).apply { this[0] = 0x01 }

        return NtfsRecordCodec.buildMftRecord(5, 5, 1, MFT_FLAG_IN_USE or MFT_FLAG_DIRECTORY) { b ->
            b.addResident(NTFS_ATTR_STD_INFO, "", NtfsRecordCodec.buildStandardInfo(FILE_ATTR_HIDDEN_SYS_ARCHIVE, securityId = null))
            b.addResident(NTFS_ATTR_FILE_NAME, "", NtfsRecordCodec.buildFileName(5L, ".", FILE_ATTR_DIR_SYSTEM, 0L, 0L))
            b.addNonResident(
                NTFS_ATTR_SECURITY_DESC,
                "",
                geom.rootSdClusters,
                sdReal,
                sdAlloc,
                NtfsRecordCodec.encodeRun(geom.rootSdLcn, geom.rootSdClusters),
            )
            b.addResident(NTFS_ATTR_INDEX_ROOT, "\$I30", indexRoot)
            b.addNonResident(
                NTFS_ATTR_INDEX_ALLOC,
                "\$I30",
                geom.rootIndexClusters,
                idxRealBytes,
                idxAllocBytes,
                NtfsRecordCodec.encodeRun(geom.rootIndexLcn, geom.rootIndexClusters),
            )
            b.addResident(NTFS_ATTR_BITMAP, "\$I30", bitmap)
        }
    }

    private fun buildRecordBitmap(geom: NtfsGeometry): ByteArray {
        val realSize = geom.volumeBitmapRealBytes
        val allocSize = geom.volumeBitmapClusters * geom.clusterBytes
        return NtfsRecordCodec.buildMftRecord(6, 6, 1, MFT_FLAG_IN_USE) { b ->
            b.addResident(NTFS_ATTR_STD_INFO, "", NtfsRecordCodec.buildStandardInfo(FILE_ATTR_SYSTEM_HIDDEN, securityId = 0x100))
            b.addResident(NTFS_ATTR_FILE_NAME, "", NtfsRecordCodec.buildFileName(5L, "\$Bitmap", FILE_ATTR_SYSTEM_HIDDEN, allocSize, realSize))
            b.addNonResident(
                NTFS_ATTR_DATA,
                "",
                geom.volumeBitmapClusters,
                realSize,
                allocSize,
                NtfsRecordCodec.encodeRun(geom.volumeBitmapLcn, geom.volumeBitmapClusters),
            )
        }
    }

    private fun buildRecordBoot(geom: NtfsGeometry): ByteArray {
        val size = NTFS_BOOT_BYTES.toLong()
        val clusters = FormatBlockIo.ceilDiv(size, geom.clusterBytes.toLong())
        val allocSize = clusters * geom.clusterBytes
        return NtfsRecordCodec.buildMftRecord(7, 7, 1, MFT_FLAG_IN_USE) { b ->
            b.addResident(NTFS_ATTR_STD_INFO, "", NtfsRecordCodec.buildStandardInfo(FILE_ATTR_SYSTEM_HIDDEN, securityId = null))
            b.addResident(NTFS_ATTR_FILE_NAME, "", NtfsRecordCodec.buildFileName(5L, "\$Boot", FILE_ATTR_SYSTEM_HIDDEN, allocSize, size))
            b.addResident(NTFS_ATTR_SECURITY_DESC, "", NtfsSystemData.buildSystemReadOnlySecurityDescriptor())
            b.addNonResident(NTFS_ATTR_DATA, "", clusters, size, allocSize, NtfsRecordCodec.encodeRun(0L, clusters))
        }
    }

    private fun buildRecordBadClus(geom: NtfsGeometry): ByteArray {
        val volSize = geom.totalClusters * geom.clusterBytes
        return NtfsRecordCodec.buildMftRecord(8, 8, 1, MFT_FLAG_IN_USE) { b ->
            b.addResident(NTFS_ATTR_STD_INFO, "", NtfsRecordCodec.buildStandardInfo(FILE_ATTR_SYSTEM_HIDDEN, securityId = 0x100))
            b.addResident(NTFS_ATTR_FILE_NAME, "", NtfsRecordCodec.buildFileName(5L, "\$BadClus", FILE_ATTR_SYSTEM_HIDDEN, 0L, 0L))
            b.addResident(NTFS_ATTR_DATA, "", ByteArray(0))
            b.addNonResident(
                NTFS_ATTR_DATA,
                "\$Bad",
                geom.totalClusters,
                volSize,
                volSize,
                NtfsRecordCodec.encodeSparseRun(geom.totalClusters),
                initializedSize = 0L,
            )
        }
    }

    private fun buildRecordSecure(geom: NtfsGeometry): ByteArray {
        val realSize = NtfsSystemData.SECURE_SDS_REAL_SIZE.toLong()
        val allocSize = geom.secureSdsClusters * geom.clusterBytes
        return NtfsRecordCodec.buildMftRecord(9, 9, 1, MFT_FLAG_IN_USE or MFT_FLAG_VIEW_INDEX) { b ->
            b.addResident(NTFS_ATTR_STD_INFO, "", NtfsRecordCodec.buildStandardInfo(FILE_ATTR_VIEW_SYSTEM, securityId = 0x101))
            b.addResident(NTFS_ATTR_FILE_NAME, "", NtfsRecordCodec.buildFileName(5L, "\$Secure", FILE_ATTR_VIEW_SYSTEM, 0L, 0L))
            b.addNonResident(
                NTFS_ATTR_DATA,
                "\$SDS",
                geom.secureSdsClusters,
                realSize,
                allocSize,
                NtfsRecordCodec.encodeRun(geom.secureSdsLcn, geom.secureSdsClusters),
            )
            b.addResident(NTFS_ATTR_INDEX_ROOT, "\$SDH", NtfsSystemData.buildSecureSdhIndexRoot())
            b.addResident(NTFS_ATTR_INDEX_ROOT, "\$SII", NtfsSystemData.buildSecureSiiIndexRoot())
        }
    }

    private fun buildRecordUpcase(geom: NtfsGeometry): ByteArray {
        val size = 131_072L
        return NtfsRecordCodec.buildMftRecord(10, 10, 1, MFT_FLAG_IN_USE) { b ->
            b.addResident(NTFS_ATTR_STD_INFO, "", NtfsRecordCodec.buildStandardInfo(FILE_ATTR_SYSTEM_HIDDEN, securityId = 0x100))
            b.addResident(NTFS_ATTR_FILE_NAME, "", NtfsRecordCodec.buildFileName(5L, "\$UpCase", FILE_ATTR_SYSTEM_HIDDEN, size, size))
            b.addNonResident(NTFS_ATTR_DATA, "", geom.upcaseClusters, size, size, NtfsRecordCodec.encodeRun(geom.upcaseLcn, geom.upcaseClusters))
            b.addResident(NTFS_ATTR_DATA, "\$Info", NtfsSystemData.buildUpcaseInfoBytes())
        }
    }

    private fun buildRecordExtend(): ByteArray {
        val entries =
            listOf(
                25L to NtfsRecordCodec.buildFileName(11L, "\$ObjId", FILE_ATTR_VIEW_DIR_SYSTEM, 0L, 0L),
                24L to NtfsRecordCodec.buildFileName(11L, "\$Quota", FILE_ATTR_VIEW_DIR_SYSTEM, 0L, 0L),
                26L to NtfsRecordCodec.buildFileName(11L, "\$Reparse", FILE_ATTR_VIEW_DIR_SYSTEM, 0L, 0L),
            )
        val headerAndEntries = NtfsRecordCodec.buildFilenameIndexRoot(entries)
        return NtfsRecordCodec.buildMftRecord(11, 11, 1, MFT_FLAG_IN_USE or MFT_FLAG_DIRECTORY) { b ->
            b.addResident(NTFS_ATTR_STD_INFO, "", NtfsRecordCodec.buildStandardInfo(FILE_ATTR_SYSTEM_HIDDEN, securityId = 0x101))
            b.addResident(NTFS_ATTR_FILE_NAME, "", NtfsRecordCodec.buildFileName(5L, "\$Extend", FILE_ATTR_DIR_SYSTEM, 0L, 0L))
            b.addResident(NTFS_ATTR_INDEX_ROOT, "\$I30", headerAndEntries)
        }
    }

    private fun buildReservedRecord(idx: Int): ByteArray =
        NtfsRecordCodec.buildMftRecord(idx, idx, 1, MFT_FLAG_IN_USE) { b ->
            b.addResident(NTFS_ATTR_STD_INFO, "", NtfsRecordCodec.buildStandardInfo(FILE_ATTR_SYSTEM_HIDDEN, securityId = null))
            b.addResident(NTFS_ATTR_SECURITY_DESC, "", NtfsSystemData.buildVolumeSecurityDescriptor())
            b.addResident(NTFS_ATTR_DATA, "", ByteArray(0))
        }

    private fun buildUnusedRecord(idx: Int): ByteArray =
        NtfsRecordCodec.buildMftRecord(idx, idx, 1, 0) { b ->
            b.addResident(NTFS_ATTR_STD_INFO, "", NtfsRecordCodec.buildStandardInfo(0, securityId = null))
        }

    private fun buildRecordQuota(): ByteArray =
        NtfsRecordCodec.buildMftRecord(24, 1, 1, MFT_FLAG_IN_USE or 0x04 or MFT_FLAG_VIEW_INDEX) { b ->
            b.addResident(NTFS_ATTR_STD_INFO, "", NtfsRecordCodec.buildStandardInfo(FILE_ATTR_SYSTEM_HIDDEN, securityId = 0x101))
            b.addResident(NTFS_ATTR_FILE_NAME, "", NtfsRecordCodec.buildFileName(11L, "\$Quota", FILE_ATTR_VIEW_DIR_SYSTEM, 0L, 0L))
            b.addResident(NTFS_ATTR_INDEX_ROOT, "\$O", NtfsSystemData.buildQuotaOIndexRoot())
            b.addResident(NTFS_ATTR_INDEX_ROOT, "\$Q", NtfsSystemData.buildQuotaQIndexRoot(NTFS_DEFAULT_TIMESTAMP))
        }

    private fun buildRecordSimpleViewIndex(
        recordIdx: Int,
        name: String,
        indexStreamName: String,
        collationRule: Int,
    ): ByteArray =
        NtfsRecordCodec.buildMftRecord(recordIdx, 1, 1, MFT_FLAG_IN_USE or 0x04 or MFT_FLAG_VIEW_INDEX) { b ->
            b.addResident(NTFS_ATTR_STD_INFO, "", NtfsRecordCodec.buildStandardInfo(FILE_ATTR_SYSTEM_HIDDEN, securityId = 0x101))
            b.addResident(NTFS_ATTR_FILE_NAME, "", NtfsRecordCodec.buildFileName(11L, name, FILE_ATTR_VIEW_DIR_SYSTEM, 0L, 0L))
            b.addResident(NTFS_ATTR_INDEX_ROOT, indexStreamName, NtfsSystemData.buildEmptyViewIndexRoot(collationRule))
        }

    private fun fnEntry(
        name: String,
        fileAttrs: Int,
        allocSize: Long,
        realSize: Long,
    ): ByteArray = NtfsRecordCodec.buildFileName(5L, name, fileAttrs, allocSize, realSize)
}
