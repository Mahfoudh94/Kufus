package io.github.drlacheheb.kufus.disk

internal const val EXT4_BLOCK_BYTES = 4096
internal const val EXT4_BLOCKS_PER_GROUP = 32_768L
internal const val EXT4_INODE_SIZE = 256
internal const val EXT4_DESC_SIZE = 64
internal const val EXT4_SUPER_MAGIC = 0xEF53
internal const val EXT4_LOG_FLEX_GROUPS = 4
internal const val EXT4_FLEX_GROUP_SIZE = 16L
internal const val EXT4_LOST_AND_FOUND_BLOCKS = 4
internal const val EXT4_ROOT_INO = 2
internal const val EXT4_LOST_AND_FOUND_INO = 11
internal const val EXT4_USED_INODES_GROUP0 = 11

private const val EXT4_DEFAULT_TIME_SEC = 1_795_459_200L
private const val EXT4_FEAT_COMPAT = 0x0008 // EXT_ATTR
private const val EXT4_FEAT_INCOMPAT = 0x02C2 // FILETYPE | EXTENTS | 64BIT | FLEX_BG
private const val EXT4_FEAT_RO_COMPAT = 0x046B // SPARSE_SUPER | LARGE_FILE | HUGE_FILE | DIR_NLINK | EXTRA_ISIZE | METADATA_CSUM
private const val EXT4_BG_INODE_UNINIT = 0x0001
private const val EXT4_BG_ITABLE_ZEROED = 0x0004
private const val EXT4_EXTENTS_FL = 0x0008_0000
private const val EXT4_EXTENT_MAGIC = 0xF30A

/**
 * Pure in-memory ext4 binary encoder responsible for superblocks, 64-byte group descriptors,
 * block/inode bitmaps, extent-based directory inodes, directory blocks, and CRC32C `metadata_csum` tails.
 */
internal object Ext4StructureEncoder {
    fun buildSuperblock(
        geom: Ext4Geometry,
        cleanLabel: String,
        uuid: ByteArray,
        hashSeed: ByteArray,
    ): ByteArray {
        val sb = ByteArray(1024)
        writeLeInt(sb, 0x00, geom.totalInodes.toInt())
        writeLeInt(sb, 0x04, geom.totalBlocks.toInt())
        writeLeInt(sb, 0x08, geom.reservedBlocks.toInt())
        writeLeInt(sb, 0x0C, geom.freeBlocksTotal.toInt())
        writeLeInt(sb, 0x10, (geom.totalInodes - EXT4_USED_INODES_GROUP0).toInt())
        writeLeInt(sb, 0x14, 0) // first_data_block
        writeLeInt(sb, 0x18, 2) // log_block_size -> 4096
        writeLeInt(sb, 0x1C, 2) // log_cluster_size -> 4096
        writeLeInt(sb, 0x20, geom.blocksPerGroup.toInt())
        writeLeInt(sb, 0x24, geom.blocksPerGroup.toInt())
        writeLeInt(sb, 0x28, geom.inodesPerGroup.toInt())
        writeLeInt(sb, 0x30, EXT4_DEFAULT_TIME_SEC.toInt())
        writeLeShort(sb, 0x36, -1) // s_max_mnt_count = 0xFFFF
        writeLeShort(sb, 0x38, EXT4_SUPER_MAGIC)
        writeLeShort(sb, 0x3A, 1) // EXT4_VALID_FS
        writeLeShort(sb, 0x3C, 1) // EXT4_ERRORS_CONTINUE
        writeLeInt(sb, 0x40, EXT4_DEFAULT_TIME_SEC.toInt())
        writeLeInt(sb, 0x4C, 1) // DYNAMIC_REV
        writeLeInt(sb, 0x54, EXT4_LOST_AND_FOUND_INO)
        writeLeShort(sb, 0x58, EXT4_INODE_SIZE)
        writeLeInt(sb, 0x5C, EXT4_FEAT_COMPAT)
        writeLeInt(sb, 0x60, EXT4_FEAT_INCOMPAT)
        writeLeInt(sb, 0x64, EXT4_FEAT_RO_COMPAT)
        uuid.copyInto(sb, 0x68)
        cleanLabel.encodeToByteArray().let { lbl ->
            lbl.copyInto(sb, 0x78, endIndex = minOf(lbl.size, 16))
        }
        hashSeed.copyInto(sb, 0xEC)
        sb[0xFC] = 1 // EXT2_HASH_HALF_MD4
        writeLeShort(sb, 0xFE, EXT4_DESC_SIZE)
        writeLeInt(sb, 0x100, 0x0C) // user_xattr + acl
        writeLeInt(sb, 0x108, EXT4_DEFAULT_TIME_SEC.toInt())
        writeLeInt(sb, 0x150, (geom.totalBlocks ushr 32).toInt())
        writeLeInt(sb, 0x154, (geom.reservedBlocks ushr 32).toInt())
        writeLeInt(sb, 0x158, (geom.freeBlocksTotal ushr 32).toInt())
        writeLeShort(sb, 0x15C, 32)
        writeLeShort(sb, 0x15E, 32)
        writeLeInt(sb, 0x160, 0x01) // SIGNED_HASH
        sb[0x174] = EXT4_LOG_FLEX_GROUPS.toByte()
        sb[0x175] = 1 // CRC32C
        return sb
    }

    fun stampSuperblockForGroup(
        sbCopy: ByteArray,
        groupIndex: Long,
    ) {
        writeLeShort(sbCopy, 0x5A, groupIndex.toInt())
        val sbCsum = Crc32c.computeRaw(-1, sbCopy, 0, 0x3FC)
        writeLeInt(sbCopy, 0x3FC, sbCsum)
    }

    fun populateBlockBitmap(
        blockBuf: ByteArray,
        group: Ext4GroupLayout,
        blocksPerGroup: Long,
    ) {
        blockBuf.fill(0)
        for (range in group.allocatedBlockBitmapRanges) {
            for (absBlk in range) {
                val rel = (absBlk - group.firstBlock).toInt()
                blockBuf[rel ushr 3] = (blockBuf[rel ushr 3].toInt() or (1 shl (rel and 7))).toByte()
            }
        }
        // Mark trailing nonexistent blocks beyond group.blockCount up to blocksPerGroup as 1
        for (rel in group.blockCount.toInt() until blocksPerGroup.toInt()) {
            blockBuf[rel ushr 3] = (blockBuf[rel ushr 3].toInt() or (1 shl (rel and 7))).toByte()
        }
    }

    /** Bits past the last inode of a group must be set to 1 (e2fsck "padding at end of inode bitmap"). */
    fun padInodeBitmap(
        bitmap: ByteArray,
        inodesPerGroup: Long,
    ) {
        for (bit in inodesPerGroup until bitmap.size * 8L) {
            val idx = (bit ushr 3).toInt()
            bitmap[idx] = (bitmap[idx].toInt() or (1 shl (bit and 7L).toInt())).toByte()
        }
    }

    fun writeGroupDescriptor(
        dest: ByteArray,
        offset: Int,
        group: Ext4GroupLayout,
        geom: Ext4Geometry,
        fsSeed: Int,
        bbmCsum: Int,
        ibmCsum: Int,
    ) {
        val isGroup0 = group.groupIndex == 0L
        val freeInodes = if (isGroup0) geom.inodesPerGroup - EXT4_USED_INODES_GROUP0 else geom.inodesPerGroup
        val usedDirs = if (isGroup0) 2 else 0
        val flags = if (isGroup0) EXT4_BG_ITABLE_ZEROED else EXT4_BG_INODE_UNINIT

        writeLeInt(dest, offset + 0x00, group.blockBitmapBlock.toInt())
        writeLeInt(dest, offset + 0x04, group.inodeBitmapBlock.toInt())
        writeLeInt(dest, offset + 0x08, group.inodeTableBlock.toInt())
        writeLeShort(dest, offset + 0x0C, low16(group.freeBlocksCount))
        writeLeShort(dest, offset + 0x0E, low16(freeInodes))
        writeLeShort(dest, offset + 0x10, usedDirs)
        writeLeShort(dest, offset + 0x12, flags)
        writeLeShort(dest, offset + 0x18, bbmCsum and 0xFFFF)
        writeLeShort(dest, offset + 0x1A, ibmCsum and 0xFFFF)
        writeLeShort(dest, offset + 0x1C, low16(freeInodes))

        writeLeInt(dest, offset + 0x20, (group.blockBitmapBlock ushr 32).toInt())
        writeLeInt(dest, offset + 0x24, (group.inodeBitmapBlock ushr 32).toInt())
        writeLeInt(dest, offset + 0x28, (group.inodeTableBlock ushr 32).toInt())
        writeLeShort(dest, offset + 0x2C, (group.freeBlocksCount ushr 16).toInt())
        writeLeShort(dest, offset + 0x2E, (freeInodes ushr 16).toInt())
        writeLeShort(dest, offset + 0x38, (bbmCsum ushr 16) and 0xFFFF)
        writeLeShort(dest, offset + 0x3A, (ibmCsum ushr 16) and 0xFFFF)
        writeLeShort(dest, offset + 0x3C, (freeInodes ushr 16).toInt())

        val csum =
            Crc32c(fsSeed)
                .apply {
                    updateIntLe(group.groupIndex.toInt())
                    update(dest, offset, EXT4_DESC_SIZE)
                }.rawState()
        writeLeShort(dest, offset + 0x1E, csum and 0xFFFF)
    }

    fun buildDirectoryInode(
        inodeNumber: Int,
        mode: Int,
        sizeBytes: Int,
        linksCount: Int,
        extentLengthBlocks: Int,
        extentStartBlock: Long,
        fsSeed: Int,
    ): ByteArray {
        val inode = ByteArray(EXT4_INODE_SIZE)
        val t = EXT4_DEFAULT_TIME_SEC.toInt()
        writeLeShort(inode, 0x00, mode)
        writeLeInt(inode, 0x04, sizeBytes)
        writeLeInt(inode, 0x08, t)
        writeLeInt(inode, 0x0C, t)
        writeLeInt(inode, 0x10, t)
        writeLeShort(inode, 0x1A, linksCount)
        writeLeInt(inode, 0x1C, sizeBytes / 512) // 512B blocks count
        writeLeInt(inode, 0x20, EXT4_EXTENTS_FL)

        // Inline extent header at 0x28 + single leaf extent at 0x34
        writeLeShort(inode, 0x28, EXT4_EXTENT_MAGIC)
        writeLeShort(inode, 0x2A, 1) // entries
        writeLeShort(inode, 0x2C, 4) // max
        writeLeShort(inode, 0x2E, 0) // depth
        writeLeInt(inode, 0x34, 0) // logical block 0
        writeLeShort(inode, 0x38, extentLengthBlocks)
        writeLeShort(inode, 0x3A, (extentStartBlock ushr 32).toInt())
        writeLeInt(inode, 0x3C, extentStartBlock.toInt())

        writeLeShort(inode, 0x80, 32) // i_extra_isize
        writeLeInt(inode, 0x90, t) // i_crtime

        val csum =
            Crc32c(fsSeed)
                .apply {
                    updateIntLe(inodeNumber)
                    updateIntLe(0) // generation = 0
                    update(inode, 0, EXT4_INODE_SIZE)
                }.rawState()
        writeLeShort(inode, 0x7C, csum and 0xFFFF)
        writeLeShort(inode, 0x82, (csum ushr 16) and 0xFFFF)
        return inode
    }

    fun buildDirectoryBlock(
        selfInode: Int,
        parentInode: Int,
        extraEntry: Pair<String, Int>?,
        fsSeed: Int,
    ): ByteArray {
        val block = ByteArray(EXT4_BLOCK_BYTES)
        val usableEnd = EXT4_BLOCK_BYTES - 12
        writeDirEntry(block, offset = 0, inode = selfInode, recLen = 12, fileType = 2, name = ".")
        if (extraEntry != null) {
            writeDirEntry(block, offset = 12, inode = parentInode, recLen = 12, fileType = 2, name = "..")
            writeDirEntry(
                block = block,
                offset = 24,
                inode = extraEntry.second,
                recLen = usableEnd - 24,
                fileType = 2,
                name = extraEntry.first,
            )
        } else {
            writeDirEntry(block, offset = 12, inode = parentInode, recLen = usableEnd - 12, fileType = 2, name = "..")
        }
        writeDirChecksumTail(block, selfInode, fsSeed)
        return block
    }

    fun buildEmptyDirectoryTailBlock(
        dirInode: Int,
        fsSeed: Int,
    ): ByteArray {
        val block = ByteArray(EXT4_BLOCK_BYTES)
        val usableEnd = EXT4_BLOCK_BYTES - 12
        writeLeShort(block, 4, usableEnd)
        writeDirChecksumTail(block, dirInode, fsSeed)
        return block
    }

    fun deterministicUuidBytes(
        seed: Long,
        versionByte: Int,
        variantByte: Int,
    ): ByteArray {
        val out = ByteArray(16)
        var state = seed xor 0x9E3779B97F4A7C15uL.toLong()
        for (i in 0 until 16) {
            state = state * 6364136223846793005L + 1442695040888963407L
            out[i] = ((state ushr 24) and 0xFFL).toByte()
        }
        out[6] = ((out[6].toInt() and 0x0F) or versionByte).toByte()
        out[8] = ((out[8].toInt() and 0x3F) or variantByte).toByte()
        return out
    }

    private fun writeDirEntry(
        block: ByteArray,
        offset: Int,
        inode: Int,
        recLen: Int,
        fileType: Int,
        name: String,
    ) {
        val nameBytes = name.encodeToByteArray()
        writeLeInt(block, offset, inode)
        writeLeShort(block, offset + 4, recLen)
        block[offset + 6] = nameBytes.size.toByte()
        block[offset + 7] = fileType.toByte()
        nameBytes.copyInto(block, offset + 8)
    }

    private fun writeDirChecksumTail(
        block: ByteArray,
        dirInode: Int,
        fsSeed: Int,
    ) {
        val tail = EXT4_BLOCK_BYTES - 12
        writeLeInt(block, tail, 0)
        writeLeShort(block, tail + 4, 12)
        block[tail + 6] = 0
        block[tail + 7] = 0xDE.toByte()
        val csum =
            Crc32c(fsSeed)
                .apply {
                    updateIntLe(dirInode)
                    updateIntLe(0)
                    update(block, 0, tail)
                }.rawState()
        writeLeInt(block, tail + 8, csum)
    }

    private fun low16(value: Long): Int = (value and 0xFFFFL).toInt()
}
