package io.github.drlacheheb.kufus.disk

internal data class Ext4GroupLayout(
    val groupIndex: Long,
    val firstBlock: Long,
    val blockCount: Long,
    val hasSuperblock: Boolean,
    val blockBitmapBlock: Long,
    val inodeBitmapBlock: Long,
    val inodeTableBlock: Long,
    val freeBlocksCount: Long,
    val allocatedBlockBitmapRanges: List<LongRange>,
)

internal data class Ext4Geometry(
    val blockBytes: Int,
    val blocksPerGroup: Long,
    val inodesPerGroup: Long,
    val inodeTableBlocksPerGroup: Long,
    val totalBlocks: Long,
    val totalInodes: Long,
    val reservedBlocks: Long,
    val freeBlocksTotal: Long,
    val gdtBlocks: Long,
    val rootDirBlock: Long,
    val lostAndFoundStartBlock: Long,
    val groups: List<Ext4GroupLayout>,
)

/**
 * Pure-Kotlin ext4 formatter coordinating geometry layout (`flex_bg`, sparse superblocks)
 * and streaming encoded ext4 metadata structures to a [BlockWriter].
 */
internal object Ext4Formatter : PartitionFormatter {
    override val filesystem: FilesystemType = FilesystemType.EXT4

    override fun format(
        writer: BlockWriter,
        span: PartitionSpan,
        options: PartitionFormatOptions,
        shouldCancel: () -> Boolean,
    ) {
        format(
            target = writer,
            span = span,
            volumeLabel = options.volumeLabel,
            shouldCancel = shouldCancel,
        )
    }

    fun format(
        target: BlockWriter,
        span: PartitionSpan,
        volumeLabel: String,
        shouldCancel: () -> Boolean = { false },
    ) {
        val geom = computeGeometry(span)
        val cleanLabel = sanitizeVolumeLabel(volumeLabel, FilesystemType.EXT4)
        val uuid = Ext4StructureEncoder.deterministicUuidBytes(span.sectorCount xor 0x6B756675_45585434L, 0x40, 0x80)
        val hashSeed = Ext4StructureEncoder.deterministicUuidBytes(span.sectorCount xor 0x34335458_45756675L, 0x40, 0x80)
        val fsSeed = Crc32c.computeRaw(-1, uuid)

        val sbTemplate = Ext4StructureEncoder.buildSuperblock(geom, cleanLabel, uuid, hashSeed)
        val gdtBytes = buildGdtAndBitmaps(target, span, geom, fsSeed, shouldCancel)
        writeSuperblocksAndGdt(target, span, geom, sbTemplate, gdtBytes, shouldCancel)
        writeGroupZeroInodeTableAndDirs(target, span, geom, fsSeed, shouldCancel)
    }

    fun computeGeometry(span: PartitionSpan): Ext4Geometry {
        val totalBlocks = resolveUsableBlockCount((span.sectorCount * span.sectorSizeBytes) / EXT4_BLOCK_BYTES)
        val groupCount = FormatBlockIo.ceilDiv(totalBlocks, EXT4_BLOCKS_PER_GROUP)
        val inodesPerGroup = chooseInodesPerGroup(totalBlocks, groupCount)
        val inodeTableBlocks = FormatBlockIo.ceilDiv(inodesPerGroup * EXT4_INODE_SIZE, EXT4_BLOCK_BYTES.toLong())
        val gdtBlocks = FormatBlockIo.ceilDiv(groupCount * EXT4_DESC_SIZE, EXT4_BLOCK_BYTES.toLong())

        val groups = buildGroupLayouts(totalBlocks, groupCount, inodeTableBlocks, gdtBlocks)
        val rootDirBlock = findGroupZeroFreeStart(groups.first(), gdtBlocks)
        val lostAndFoundStart = rootDirBlock + 1L

        val adjustedGroups =
            groups.mapIndexed { idx, group ->
                if (idx == 0) {
                    val dirRange = rootDirBlock until (lostAndFoundStart + EXT4_LOST_AND_FOUND_BLOCKS)
                    group.copy(
                        freeBlocksCount = group.freeBlocksCount - (1L + EXT4_LOST_AND_FOUND_BLOCKS),
                        allocatedBlockBitmapRanges = group.allocatedBlockBitmapRanges + listOf(dirRange),
                    )
                } else {
                    group
                }
            }

        val freeTotal = adjustedGroups.sumOf { it.freeBlocksCount }
        return Ext4Geometry(
            blockBytes = EXT4_BLOCK_BYTES,
            blocksPerGroup = EXT4_BLOCKS_PER_GROUP,
            inodesPerGroup = inodesPerGroup,
            inodeTableBlocksPerGroup = inodeTableBlocks,
            totalBlocks = totalBlocks,
            totalInodes = groupCount * inodesPerGroup,
            reservedBlocks = (totalBlocks * 5L) / 100L,
            freeBlocksTotal = freeTotal,
            gdtBlocks = gdtBlocks,
            rootDirBlock = rootDirBlock,
            lostAndFoundStartBlock = lostAndFoundStart,
            groups = adjustedGroups,
        )
    }

    /**
     * Drops a tiny trailing partial block group when it cannot fit its own superblock/GDT or flex_bg metadata.
     */
    private fun resolveUsableBlockCount(rawTotalBlocks: Long): Long {
        if (rawTotalBlocks <= EXT4_BLOCKS_PER_GROUP) return rawTotalBlocks
        val rawGroups = FormatBlockIo.ceilDiv(rawTotalBlocks, EXT4_BLOCKS_PER_GROUP)
        val lastGroup = rawGroups - 1L
        val lastGroupBlocks = rawTotalBlocks - lastGroup * EXT4_BLOCKS_PER_GROUP
        val gdtBlocks = FormatBlockIo.ceilDiv(rawGroups * EXT4_DESC_SIZE, EXT4_BLOCK_BYTES.toLong())
        val inodeTableBlocks = FormatBlockIo.ceilDiv(8192L * EXT4_INODE_SIZE, EXT4_BLOCK_BYTES.toLong())
        val superOverhead = if (hasSuperblock(lastGroup)) 1L + gdtBlocks else 0L
        val flexOverhead = if (lastGroup % EXT4_FLEX_GROUP_SIZE == 0L) 2L + inodeTableBlocks else 0L
        return if (lastGroupBlocks < superOverhead + flexOverhead + 16L) {
            lastGroup * EXT4_BLOCKS_PER_GROUP
        } else {
            rawTotalBlocks
        }
    }

    private fun buildGroupLayouts(
        totalBlocks: Long,
        groupCount: Long,
        inodeTableBlocks: Long,
        gdtBlocks: Long,
    ): List<Ext4GroupLayout> {
        val allocsPerGroup = Array(groupCount.toInt()) { mutableListOf<LongRange>() }
        val superOverhead = 1L + gdtBlocks
        for (bg in 0 until groupCount.toInt()) {
            val gFirst = bg.toLong() * EXT4_BLOCKS_PER_GROUP
            if (hasSuperblock(bg.toLong())) {
                allocsPerGroup[bg].add(gFirst until (gFirst + superOverhead))
            }
        }

        val bbmBlocks = LongArray(groupCount.toInt())
        val ibmBlocks = LongArray(groupCount.toInt())
        val itBlocks = LongArray(groupCount.toInt())

        var flexBase = 0L
        while (flexBase < groupCount) {
            val fgEnd = minOf(groupCount, flexBase + EXT4_FLEX_GROUP_SIZE)
            val fgCount = fgEnd - flexBase
            val hostBg = flexBase.toInt()
            var cursor = flexBase * EXT4_BLOCKS_PER_GROUP + (if (hasSuperblock(flexBase)) superOverhead else 0L)

            for (bg in flexBase.toInt() until fgEnd.toInt()) bbmBlocks[bg] = cursor++
            for (bg in flexBase.toInt() until fgEnd.toInt()) ibmBlocks[bg] = cursor++
            for (bg in flexBase.toInt() until fgEnd.toInt()) {
                itBlocks[bg] = cursor
                cursor += inodeTableBlocks
            }
            val metaStart = flexBase * EXT4_BLOCKS_PER_GROUP + (if (hasSuperblock(flexBase)) superOverhead else 0L)
            val metaLen = fgCount * (2L + inodeTableBlocks)
            allocsPerGroup[hostBg].add(metaStart until (metaStart + metaLen))
            flexBase = fgEnd
        }

        return List(groupCount.toInt()) { bg ->
            val gFirst = bg.toLong() * EXT4_BLOCKS_PER_GROUP
            val gCount = minOf(EXT4_BLOCKS_PER_GROUP, totalBlocks - gFirst)
            val used = allocsPerGroup[bg].sumOf { (it.last - it.first + 1L).coerceAtLeast(0L) }
            Ext4GroupLayout(
                groupIndex = bg.toLong(),
                firstBlock = gFirst,
                blockCount = gCount,
                hasSuperblock = hasSuperblock(bg.toLong()),
                blockBitmapBlock = bbmBlocks[bg],
                inodeBitmapBlock = ibmBlocks[bg],
                inodeTableBlock = itBlocks[bg],
                freeBlocksCount = gCount - used,
                allocatedBlockBitmapRanges = allocsPerGroup[bg],
            )
        }
    }

    private fun findGroupZeroFreeStart(
        group0: Ext4GroupLayout,
        gdtBlocks: Long,
    ): Long {
        val afterItable = group0.inodeTableBlock
        val maxUsedInGroup0 = group0.allocatedBlockBitmapRanges.maxOfOrNull { it.last } ?: gdtBlocks
        return maxOf(afterItable, maxUsedInGroup0 + 1L)
    }

    private fun buildGdtAndBitmaps(
        target: BlockWriter,
        span: PartitionSpan,
        geom: Ext4Geometry,
        fsSeed: Int,
        shouldCancel: () -> Boolean,
    ): ByteArray {
        val gdt = ByteArray((geom.gdtBlocks * EXT4_BLOCK_BYTES).toInt())
        val bbmBlock = ByteArray(EXT4_BLOCK_BYTES)
        val ibmBlock = ByteArray(EXT4_BLOCK_BYTES)

        for (group in geom.groups) {
            FormatBlockIo.ensureNotCancelled(shouldCancel)
            Ext4StructureEncoder.populateBlockBitmap(bbmBlock, group, geom.blocksPerGroup)
            val bbmCsum = Crc32c.computeRaw(fsSeed, bbmBlock, 0, (geom.blocksPerGroup / 8L).toInt())
            writeBlock(target, span, group.blockBitmapBlock, bbmBlock)

            ibmBlock.fill(0)
            val ibmCsum: Int
            if (group.groupIndex == 0L) {
                ibmBlock[0] = 0xFF.toByte()
                ibmBlock[1] = 0x07 // inodes 1..11 used
                Ext4StructureEncoder.padInodeBitmap(ibmBlock, geom.inodesPerGroup)
                ibmCsum = Crc32c.computeRaw(fsSeed, ibmBlock, 0, (geom.inodesPerGroup / 8L).toInt())
            } else {
                Ext4StructureEncoder.padInodeBitmap(ibmBlock, geom.inodesPerGroup)
                ibmCsum = 0
            }
            writeBlock(target, span, group.inodeBitmapBlock, ibmBlock)

            Ext4StructureEncoder.writeGroupDescriptor(
                dest = gdt,
                offset = group.groupIndex.toInt() * EXT4_DESC_SIZE,
                group = group,
                geom = geom,
                fsSeed = fsSeed,
                bbmCsum = bbmCsum,
                ibmCsum = ibmCsum,
            )
        }
        return gdt
    }

    private fun writeSuperblocksAndGdt(
        target: BlockWriter,
        span: PartitionSpan,
        geom: Ext4Geometry,
        sbTemplate: ByteArray,
        gdtBytes: ByteArray,
        shouldCancel: () -> Boolean,
    ) {
        val sbCopy = sbTemplate.copyOf()
        for (group in geom.groups) {
            if (!group.hasSuperblock) continue
            FormatBlockIo.ensureNotCancelled(shouldCancel)
            Ext4StructureEncoder.stampSuperblockForGroup(sbCopy, group.groupIndex)

            if (group.groupIndex == 0L) {
                val block0 = ByteArray(EXT4_BLOCK_BYTES)
                sbCopy.copyInto(block0, destinationOffset = 1024)
                writeBlock(target, span, 0L, block0)
                FormatBlockIo.writeSectorAlignedStream(target, span.startByteOffset + EXT4_BLOCK_BYTES.toLong(), gdtBytes, shouldCancel)
            } else {
                val sbBlock = ByteArray(EXT4_BLOCK_BYTES)
                sbCopy.copyInto(sbBlock, destinationOffset = 0)
                writeBlock(target, span, group.firstBlock, sbBlock)
                val gdtOffset = span.startByteOffset + (group.firstBlock + 1L) * EXT4_BLOCK_BYTES
                FormatBlockIo.writeSectorAlignedStream(target, gdtOffset, gdtBytes, shouldCancel)
            }
        }
    }

    private fun writeGroupZeroInodeTableAndDirs(
        target: BlockWriter,
        span: PartitionSpan,
        geom: Ext4Geometry,
        fsSeed: Int,
        shouldCancel: () -> Boolean,
    ) {
        val itableOffset = span.startByteOffset + geom.groups.first().inodeTableBlock * EXT4_BLOCK_BYTES
        val itableTotalBytes = geom.inodeTableBlocksPerGroup * EXT4_BLOCK_BYTES
        FormatBlockIo.writeZeroSectors(target, itableOffset, itableTotalBytes, shouldCancel)

        val firstInodeBlock = ByteArray(EXT4_BLOCK_BYTES)
        Ext4StructureEncoder
            .buildDirectoryInode(
                inodeNumber = EXT4_ROOT_INO,
                mode = 0x41ED, // drwxr-xr-x
                sizeBytes = EXT4_BLOCK_BYTES,
                linksCount = 3,
                extentLengthBlocks = 1,
                extentStartBlock = geom.rootDirBlock,
                fsSeed = fsSeed,
            ).copyInto(firstInodeBlock, (EXT4_ROOT_INO - 1) * EXT4_INODE_SIZE)

        Ext4StructureEncoder
            .buildDirectoryInode(
                inodeNumber = EXT4_LOST_AND_FOUND_INO,
                mode = 0x41C0, // drwx------
                sizeBytes = EXT4_LOST_AND_FOUND_BLOCKS * EXT4_BLOCK_BYTES,
                linksCount = 2,
                extentLengthBlocks = EXT4_LOST_AND_FOUND_BLOCKS,
                extentStartBlock = geom.lostAndFoundStartBlock,
                fsSeed = fsSeed,
            ).copyInto(firstInodeBlock, (EXT4_LOST_AND_FOUND_INO - 1) * EXT4_INODE_SIZE)
        writeBlock(target, span, geom.groups.first().inodeTableBlock, firstInodeBlock)

        val rootBlock = Ext4StructureEncoder.buildDirectoryBlock(EXT4_ROOT_INO, EXT4_ROOT_INO, "lost+found" to EXT4_LOST_AND_FOUND_INO, fsSeed)
        writeBlock(target, span, geom.rootDirBlock, rootBlock)

        val lfFirst = Ext4StructureEncoder.buildDirectoryBlock(EXT4_LOST_AND_FOUND_INO, EXT4_ROOT_INO, null, fsSeed)
        writeBlock(target, span, geom.lostAndFoundStartBlock, lfFirst)
        val lfEmpty = Ext4StructureEncoder.buildEmptyDirectoryTailBlock(EXT4_LOST_AND_FOUND_INO, fsSeed)
        for (i in 1 until EXT4_LOST_AND_FOUND_BLOCKS) {
            writeBlock(target, span, geom.lostAndFoundStartBlock + i.toLong(), lfEmpty)
        }
    }

    private fun writeBlock(
        target: BlockWriter,
        span: PartitionSpan,
        blockIndex: Long,
        blockBytes: ByteArray,
    ) {
        target.writeAt(span.startByteOffset + blockIndex * EXT4_BLOCK_BYTES, blockBytes, EXT4_BLOCK_BYTES)
    }

    private fun hasSuperblock(groupIndex: Long): Boolean =
        groupIndex == 0L || groupIndex == 1L || isPowerOf(groupIndex, 3L) || isPowerOf(groupIndex, 5L) || isPowerOf(groupIndex, 7L)

    private fun isPowerOf(
        value: Long,
        base: Long,
    ): Boolean {
        var cur = value
        while (cur > 1L) {
            if (cur % base != 0L) return false
            cur /= base
        }
        return cur == 1L
    }

    private fun chooseInodesPerGroup(
        totalBlocks: Long,
        groupCount: Long,
    ): Long {
        if (groupCount == 1L) {
            return FormatBlockIo.alignUpBytes(minOf(totalBlocks, EXT4_BLOCKS_PER_GROUP), 16L).coerceAtLeast(64L)
        }
        return 8192L
    }
}
