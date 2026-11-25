package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.archive.IsoUdfReader
import io.github.drlacheheb.kufus.disk.archive.WimPartSink
import io.github.drlacheheb.kufus.disk.archive.WimSplitter
import io.github.drlacheheb.kufus.disk.io.BlockFlashTarget
import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.disk.model.ImageFormat
import io.github.drlacheheb.kufus.disk.model.PartitionScheme
import io.github.drlacheheb.kufus.disk.model.WindowsFlashRequest
import io.github.drlacheheb.kufus.disk.model.WindowsUserExperienceOptions
import io.github.drlacheheb.kufus.disk.partition.PartitionTableBuilder
import io.github.drlacheheb.kufus.disk.platform.inspectPlatformImage
import io.github.drlacheheb.kufus.disk.platform.openPlatformSeekableReader
import io.github.drlacheheb.kufus.disk.windows.MountedPartitionHandle
import io.github.drlacheheb.kufus.disk.windows.WindowsInstallerEngine
import io.github.drlacheheb.kufus.disk.windows.WritableMountedFile
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EEXIST
import platform.posix.FILE
import platform.posix.F_OK
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_TRUNC
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.access
import platform.posix.close
import platform.posix.closedir
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fsync
import platform.posix.ftruncate
import platform.posix.fwrite
import platform.posix.mkdir
import platform.posix.mkdtemp
import platform.posix.open
import platform.posix.opendir
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.readdir
import platform.posix.realpath
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.system
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WIN_TEST_DISK_BYTES = 48L * 1024L * 1024L
private const val SECTOR_SIZE_512 = 512
private const val UDF_SECTOR_SIZE = 2048
private const val UDF_PART_START_LBA = 280L
private const val COPY_SLICE_BUF_BYTES = 262_144
private const val DIR_MODE_0755 = 0x1EDu
private const val MAX_PATH_BYTES = 4096

class LinuxWindowsInstallIntegrationTest {
    @Test
    fun executesFat32UdfWindowsInstallAndSplitsWimToPosixFilesAndValidatesFsck() {
        val root = createWinTempDir()
        try {
            val efiPayload = byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00, 0x12, 0x34)
            val wimPayload = buildMultiResourceSyntheticWim()
            val isoBytes = buildSyntheticUdfIso("WIN11_X64", efiPayload, wimPayload)
            val isoPath = "$root/win11.iso"
            val diskPath = "$root/virtual-usb.img"
            val mountDir = "$root/mounted".also { ensurePosixDir(it) }
            writeAllPosixBytes(isoPath, isoBytes)
            createSparseDiskFile(diskPath, WIN_TEST_DISK_BYTES)

            val inspected = inspectPlatformImage(isoPath).getOrThrow()
            assertEquals(ImageFormat.WINDOWS_ISO, inspected.format)
            assertEquals("WIN11_X64", inspected.windowsMetadata?.volumeLabel)

            executeInstallAgainstPosixFiles(isoPath, diskPath, mountDir)
            verifyMountedOutputTree(mountDir, efiPayload, wimPayload)
            verifyPartitionSlicePassesFsckVfat(root, diskPath)
            verifyWimSplitThroughPosixReader(isoPath, "$root/split-wim".also { ensurePosixDir(it) })
        } finally {
            deleteTreeRecursively(root)
        }
    }

    private fun executeInstallAgainstPosixFiles(
        isoPath: String,
        diskPath: String,
        mountDir: String,
    ) {
        val isoReader = openPlatformSeekableReader(isoPath).getOrThrow()
        try {
            val completed =
                WindowsInstallerEngine.execute(
                    request =
                        WindowsFlashRequest(
                            imagePath = isoPath,
                            deviceId = diskPath,
                            partitionScheme = PartitionScheme.GPT,
                            filesystem = FilesystemType.FAT32,
                            volumeLabel = "WIN11_X64",
                            userExperience =
                                WindowsUserExperienceOptions(
                                    bypassSystemRequirements = true,
                                    bypassMicrosoftAccount = true,
                                    disableDataCollection = true,
                                    localAccountUsername = "KufusUser",
                                    regionalLocaleTag = "fr-FR",
                                ),
                        ),
                    isoReader = isoReader,
                    target = PosixFileBlockTarget(diskPath, SECTOR_SIZE_512, WIN_TEST_DISK_BYTES),
                    uefiNtfsBytes = null,
                    clockMs = { 50L },
                    onEvent = {},
                    mountPartition = { _, _ -> PosixDirectoryMountHandle(mountDir) },
                )
            assertEquals(false, completed.verified)
        } finally {
            isoReader.close()
        }
    }

    private fun verifyMountedOutputTree(
        mountDir: String,
        efiPayload: ByteArray,
        wimPayload: ByteArray,
    ) {
        assertTrue(readAllPosixBytes("$mountDir/efi/boot/bootx64.efi").contentEquals(efiPayload))
        assertTrue(readAllPosixBytes("$mountDir/sources/install.wim").contentEquals(wimPayload))
        val xmlText = readAllPosixBytes("$mountDir/autounattend.xml").decodeToString()
        assertTrue(xmlText.contains("BypassTPMCheck"))
        assertTrue(xmlText.contains("BypassNRO"))
        assertTrue(xmlText.contains("<Name>KufusUser</Name>"))
        assertTrue(xmlText.contains("fr-FR"))
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun verifyPartitionSlicePassesFsckVfat(
        root: String,
        diskPath: String,
    ) {
        val slicePath = "$root/part1-fat32.vfat"
        val span = PartitionTableBuilder.computeSinglePartitionSpan(WIN_TEST_DISK_BYTES, SECTOR_SIZE_512, PartitionScheme.GPT)
        extractDiskSlice(diskPath, slicePath, span.startByteOffset, span.sizeBytes)
        if (access("/usr/bin/fsck.vfat", F_OK) == 0) {
            assertEquals(0, system("/usr/bin/fsck.vfat -n '$slicePath' >/dev/null 2>&1"))
        }
    }

    private fun verifyWimSplitThroughPosixReader(
        isoPath: String,
        splitOutDir: String,
    ) {
        val reader = openPlatformSeekableReader(isoPath).getOrThrow()
        try {
            val archive = requireNotNull(IsoUdfReader.open(reader))
            val wimEntry = archive.entries.first { it.relativePath == "sources/install.wim" }
            val partCount =
                WimSplitter.split(
                    reader = IsoUdfReader.openEntryReader(reader, wimEntry),
                    maxPartBytes = 1_100_000L,
                ) { _, fileName -> PosixWimPartSink("$splitOutDir/$fileName") }
            assertEquals(2, partCount)
            val part1 = readAllPosixBytes("$splitOutDir/install.swm")
            val part2 = readAllPosixBytes("$splitOutDir/install2.swm")
            assertEquals(1, readLe16(part1, 0x28))
            assertEquals(2, readLe16(part1, 0x2A))
            assertEquals(2, readLe16(part2, 0x28))
            assertEquals(2, readLe16(part2, 0x2A))
        } finally {
            reader.close()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun splitsRealMultiImageLzxWimAndVerifiesRoundTripWithWimlib() {
        if (!hasLocalWimlibTools()) return

        val root = createWinTempDir()
        try {
            val img1Dir = "$root/img1".also { ensurePosixDir(it) }
            val img2Dir = "$root/img2".also { ensurePosixDir(it) }
            val swmDir = "$root/swm".also { ensurePosixDir(it) }
            val fixtures = populateMultiImageWimFixtures(img1Dir, img2Dir)

            val wimPath = "$root/install.wim"
            assertEquals(0, system("/usr/bin/wimlib-imagex capture '$img1Dir' '$wimPath' 'WinHome' --compress=LZX --boot >/dev/null 2>&1"))
            assertEquals(0, system("/usr/bin/wimlib-imagex append '$img2Dir' '$wimPath' 'WinPro' >/dev/null 2>&1"))

            val maxPartBytes = 700_000L
            val partCount = splitWimToPosixDir(wimPath, swmDir, maxPartBytes)
            assertTrue(partCount >= 3, "Expected >= 3 split parts, got $partCount")
            for (partIdx in 1..partCount) {
                val partPath = "$swmDir/${WimSplitter.formatSplitFileName(partIdx)}"
                val partSize = readAllPosixBytes(partPath).size.toLong()
                assertTrue(partSize <= maxPartBytes, "Part $partIdx size $partSize exceeded limit $maxPartBytes")
            }

            verifySwmSetWithWimlib(root, swmDir, fixtures.first, fixtures.second)
        } finally {
            deleteTreeRecursively(root)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun hasLocalWimlibTools(): Boolean =
        access("/usr/bin/wimlib-imagex", F_OK) == 0 &&
            access("/usr/bin/wimverify", F_OK) == 0 &&
            access("/usr/bin/wiminfo", F_OK) == 0 &&
            access("/usr/bin/wimapply", F_OK) == 0

    private fun populateMultiImageWimFixtures(
        img1Dir: String,
        img2Dir: String,
    ): Pair<Map<String, ByteArray>, Map<String, ByteArray>> {
        val compressibleText = "Kufus WIM split round-trip verification payload line.\n".repeat(8_000).encodeToByteArray()
        val img1Files =
            mapOf(
                "a0.bin" to deterministicNoiseBytes(300_000, 0x1111_2222u),
                "a1.bin" to deterministicNoiseBytes(300_000, 0x3333_4444u),
                "a2.bin" to deterministicNoiseBytes(300_000, 0x5555_6666u),
                "a3.bin" to deterministicNoiseBytes(300_000, 0x7777_8888u),
                "compressible.txt" to compressibleText,
            )
        val img2Files =
            mapOf(
                "shared.bin" to img1Files.getValue("a0.bin"),
                "b0.bin" to deterministicNoiseBytes(300_000, 0x9999_AAAAu),
                "b1.bin" to deterministicNoiseBytes(300_000, 0xBBBB_CCCCu),
                "compressible_copy.txt" to compressibleText,
            )
        img1Files.forEach { (name, bytes) -> writeAllPosixBytes("$img1Dir/$name", bytes) }
        img2Files.forEach { (name, bytes) -> writeAllPosixBytes("$img2Dir/$name", bytes) }
        return img1Files to img2Files
    }

    private fun splitWimToPosixDir(
        wimPath: String,
        swmDir: String,
        maxPartBytes: Long,
    ): Int {
        val reader = openPlatformSeekableReader(wimPath).getOrThrow()
        return try {
            val summary = WimSplitter.inspectSplitCapability(reader, maxPartBytes = maxPartBytes)
            assertEquals(false, summary.isSolidOrUnsupported)
            val written =
                WimSplitter.split(reader = reader, maxPartBytes = maxPartBytes) { _, fileName ->
                    PosixWimPartSink("$swmDir/$fileName")
                }
            assertEquals(summary.totalParts, written)
            written
        } finally {
            reader.close()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun verifySwmSetWithWimlib(
        root: String,
        swmDir: String,
        img1Files: Map<String, ByteArray>,
        img2Files: Map<String, ByteArray>,
    ) {
        val verifyLogPath = "$root/wimverify.log"
        assertEquals(0, system("/usr/bin/wimverify '$swmDir/install.swm' --ref='$swmDir/install*.swm' >'$verifyLogPath' 2>&1"))
        val verifyLog = readAllPosixBytes(verifyLogPath).decodeToString()
        assertTrue(!verifyLog.contains("wrong part number"), "Unexpected wimverify warning:\n$verifyLog")

        val infoLogPath = "$root/wiminfo.log"
        assertEquals(0, system("/usr/bin/wiminfo '$swmDir/install2.swm' --header >'$infoLogPath' 2>&1"))
        val infoLog = readAllPosixBytes(infoLogPath).decodeToString()
        assertTrue(!infoLog.contains("wrong part number"), "Unexpected wiminfo warning:\n$infoLog")

        val extract1Dir = "$root/extract1".also { ensurePosixDir(it) }
        val extract2Dir = "$root/extract2".also { ensurePosixDir(it) }
        assertEquals(0, system("/usr/bin/wimapply '$swmDir/install.swm' 1 '$extract1Dir' --ref='$swmDir/install*.swm' >/dev/null 2>&1"))
        assertEquals(0, system("/usr/bin/wimapply '$swmDir/install.swm' 2 '$extract2Dir' --ref='$swmDir/install*.swm' >/dev/null 2>&1"))

        img1Files.forEach { (name, expected) ->
            assertTrue(readAllPosixBytes("$extract1Dir/$name").contentEquals(expected), "Image 1 mismatch: $name")
        }
        img2Files.forEach { (name, expected) ->
            assertTrue(readAllPosixBytes("$extract2Dir/$name").contentEquals(expected), "Image 2 mismatch: $name")
        }
    }
}

private fun deterministicNoiseBytes(
    size: Int,
    seed: UInt,
): ByteArray {
    val out = ByteArray(size)
    var state = seed
    for (i in 0 until size) {
        state = state xor (state shl 13)
        state = state xor (state shr 17)
        state = state xor (state shl 5)
        out[i] = (state and 0xFFu).toByte()
    }
    return out
}

private fun buildMultiResourceSyntheticWim(): ByteArray {
    val meta = ByteArray(50_000) { idx -> (idx % 251).toByte() }
    val blob1 = ByteArray(650_000) { idx -> ((idx * 3) % 251).toByte() }
    val blob2 = ByteArray(750_000) { idx -> ((idx * 7) % 251).toByte() }
    val xml = "<WIM><TOTALBYTES>1450000</TOTALBYTES></WIM>".encodeToByteArray()
    val headerSize = 208
    val allBlobs = listOf(meta to 0x02, blob1 to 0x04, blob2 to 0x04)
    val lookupSize = allBlobs.size * 50
    val payloadTotal = allBlobs.sumOf { it.first.size }
    val out = ByteArray(headerSize + payloadTotal + lookupSize + xml.size)

    "MSWIM\u0000\u0000\u0000".encodeToByteArray().copyInto(out)
    writeLe32(out, 8, headerSize.toLong())
    writeLe32(out, 12, 0x00010D00L)
    for (i in 0 until 16) out[0x18 + i] = (i + 1).toByte()
    writeLe16(out, 0x28, 1)
    writeLe16(out, 0x2A, 1)
    writeLe32(out, 0x2C, 1L)

    var cursor = headerSize
    val offsets = mutableListOf<Int>()
    for ((blob, _) in allBlobs) {
        offsets += cursor
        blob.copyInto(out, destinationOffset = cursor)
        cursor += blob.size
    }
    val lookupOff = cursor
    for (i in allBlobs.indices) {
        val (blob, flags) = allBlobs[i]
        val off = lookupOff + i * 50
        writeLe64(out, off, blob.size.toLong() or ((flags.toLong() and 0xFFL) shl 56))
        writeLe64(out, off + 8, offsets[i].toLong())
        writeLe64(out, off + 16, blob.size.toLong())
        writeLe16(out, off + 24, 1)
        writeLe32(out, off + 26, 1L)
        out[off + 30] = (i + 1).toByte()
    }
    cursor += lookupSize
    xml.copyInto(out, destinationOffset = cursor)
    writeLe64(out, 0x30, lookupSize.toLong())
    writeLe64(out, 0x38, lookupOff.toLong())
    writeLe64(out, 0x40, lookupSize.toLong())
    writeLe64(out, 0x48, xml.size.toLong())
    writeLe64(out, 0x50, cursor.toLong())
    writeLe64(out, 0x58, xml.size.toLong())
    return out
}

private fun buildSyntheticUdfIso(
    volumeLabel: String,
    efiPayload: ByteArray,
    wimPayload: ByteArray,
): ByteArray {
    val totalSectors = 1200
    val img = ByteArray(totalSectors * UDF_SECTOR_SIZE)
    writeLe16(img, 256 * UDF_SECTOR_SIZE, 2)
    writeLe32(img, 256 * UDF_SECTOR_SIZE + 16, (4 * UDF_SECTOR_SIZE).toLong())
    writeLe32(img, 256 * UDF_SECTOR_SIZE + 20, 32L)

    writeLe16(img, 32 * UDF_SECTOR_SIZE, 5)
    writeLe32(img, 32 * UDF_SECTOR_SIZE + 188, UDF_PART_START_LBA)
    writeLe32(img, 32 * UDF_SECTOR_SIZE + 192, 900L)

    writeLe16(img, 33 * UDF_SECTOR_SIZE, 6)
    img[33 * UDF_SECTOR_SIZE + 84] = 8
    volumeLabel.encodeToByteArray().copyInto(img, destinationOffset = 33 * UDF_SECTOR_SIZE + 85)
    writeLe32(img, 33 * UDF_SECTOR_SIZE + 212, UDF_SECTOR_SIZE.toLong())
    writeUdfLongAd(img, 33 * UDF_SECTOR_SIZE + 248, UDF_SECTOR_SIZE.toLong(), 0L)
    writeLe16(img, 34 * UDF_SECTOR_SIZE, 8)

    writeLe16(img, UDF_PART_START_LBA.toInt() * UDF_SECTOR_SIZE, 256)
    writeUdfLongAd(img, UDF_PART_START_LBA.toInt() * UDF_SECTOR_SIZE + 400, UDF_SECTOR_SIZE.toLong(), 1L)

    writeUdfDirectory(img, 1L, 2L, listOf("efi" to (3L to true), "sources" to (5L to true)))
    writeUdfDirectory(img, 3L, 4L, listOf("boot" to (7L to true)))
    writeUdfDirectory(img, 7L, 8L, listOf("bootx64.efi" to (9L to false)))
    writeUdfDirectory(img, 5L, 6L, listOf("install.wim" to (11L to false)))

    writeUdfFile(img, 9L, 10L, efiPayload)
    writeUdfFile(img, 11L, 12L, wimPayload)
    return img
}

private fun writeUdfDirectory(
    img: ByteArray,
    feBlock: Long,
    dataBlock: Long,
    children: List<Pair<String, Pair<Long, Boolean>>>,
) {
    val dataOff = (UDF_PART_START_LBA + dataBlock).toInt() * UDF_SECTOR_SIZE
    var pos = 0
    for ((name, target) in children) {
        val (childIcb, isDir) = target
        val nameBytes = name.encodeToByteArray()
        val fiLen = 1 + nameBytes.size
        val recLen = ((38 + fiLen + 3) / 4) * 4
        writeLe16(img, dataOff + pos, 257)
        img[dataOff + pos + 18] = if (isDir) 0x02 else 0x00
        img[dataOff + pos + 19] = fiLen.toByte()
        writeUdfLongAd(img, dataOff + pos + 20, UDF_SECTOR_SIZE.toLong(), childIcb)
        img[dataOff + pos + 38] = 8
        nameBytes.copyInto(img, destinationOffset = dataOff + pos + 39)
        pos += recLen
    }
    writeUdfFileEntry(img, feBlock, dataBlock, pos.toLong())
}

private fun writeUdfFile(
    img: ByteArray,
    feBlock: Long,
    dataBlock: Long,
    payload: ByteArray,
) {
    payload.copyInto(img, destinationOffset = (UDF_PART_START_LBA + dataBlock).toInt() * UDF_SECTOR_SIZE)
    writeUdfFileEntry(img, feBlock, dataBlock, payload.size.toLong())
}

private fun writeUdfFileEntry(
    img: ByteArray,
    feBlock: Long,
    dataBlock: Long,
    lengthBytes: Long,
) {
    val feOff = (UDF_PART_START_LBA + feBlock).toInt() * UDF_SECTOR_SIZE
    writeLe16(img, feOff, 261)
    writeLe64(img, feOff + 56, lengthBytes)
    writeLe32(img, feOff + 172, 8L)
    writeLe32(img, feOff + 176, lengthBytes)
    writeLe32(img, feOff + 180, dataBlock)
}

private fun writeUdfLongAd(
    img: ByteArray,
    offset: Int,
    lenBytes: Long,
    block: Long,
) {
    writeLe32(img, offset, lenBytes)
    writeLe32(img, offset + 4, block)
    writeLe16(img, offset + 8, 0)
}

private fun readLe16(
    src: ByteArray,
    offset: Int,
): Int = (src[offset].toInt() and 0xFF) or ((src[offset + 1].toInt() and 0xFF) shl 8)

private fun writeLe16(
    dst: ByteArray,
    offset: Int,
    value: Int,
) {
    dst[offset] = (value and 0xFF).toByte()
    dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

private fun writeLe32(
    dst: ByteArray,
    offset: Int,
    value: Long,
) {
    for (i in 0 until 4) dst[offset + i] = ((value ushr (8 * i)) and 0xFFL).toByte()
}

private fun writeLe64(
    dst: ByteArray,
    offset: Int,
    value: Long,
) {
    for (i in 0 until 8) dst[offset + i] = ((value ushr (8 * i)) and 0xFFL).toByte()
}

@OptIn(ExperimentalForeignApi::class)
private class PosixFileBlockTarget(
    private val path: String,
    override val sectorSizeBytes: Int,
    override val capacityBytes: Long,
) : BlockFlashTarget {
    private var fd = -1

    override fun unmountAndLock() {
        fd = open(path, O_RDWR)
        if (fd < 0) error("Unable to open $path")
    }

    override fun writeAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ) {
        var written = 0
        buffer.usePinned { pinned ->
            while (written < length) {
                val n = pwrite(fd, pinned.addressOf(written), (length - written).toULong(), offset + written)
                if (n <= 0L) error("pwrite failed")
                written += n.toInt()
            }
        }
    }

    override fun syncAndFlush() {
        if (fd >= 0) fsync(fd)
    }

    override fun readAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ): Int = 0

    override fun close() {
        if (fd >= 0) {
            close(fd)
            fd = -1
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PosixDirectoryMountHandle(
    private val mountDir: String,
) : MountedPartitionHandle {
    override fun ensureDirectory(relativeDirPath: String) {
        var cur = mountDir
        relativeDirPath.split('/').filter { it.isNotEmpty() }.forEach { seg ->
            cur = "$cur/$seg"
            ensurePosixDir(cur)
        }
    }

    override fun openFileForWrite(relativeFilePath: String): WritableMountedFile {
        val file = fopen("$mountDir/${relativeFilePath.trimStart('/')}", "wb") ?: error("fopen write failed for $relativeFilePath")
        return PosixWritableFileAdapter(file)
    }

    override fun sync() = Unit

    override fun unmountAndClose() = Unit
}

@OptIn(ExperimentalForeignApi::class)
private class PosixWritableFileAdapter(
    private val file: kotlinx.cinterop.CPointer<FILE>,
) : WritableMountedFile {
    override fun write(
        bytes: ByteArray,
        length: Int,
    ) {
        writePosixFileBytes(file, bytes, length)
    }

    override fun close() {
        fflush(file)
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PosixWimPartSink(
    path: String,
) : WimPartSink {
    private val file = fopen(path, "wb") ?: error("fopen failed for $path")

    override fun write(
        bytes: ByteArray,
        length: Int,
    ) {
        writePosixFileBytes(file, bytes, length)
    }

    override fun close() {
        fflush(file)
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writePosixFileBytes(
    file: kotlinx.cinterop.CPointer<FILE>,
    bytes: ByteArray,
    length: Int,
) {
    if (length <= 0) return
    bytes.usePinned { pinned ->
        var written = 0
        while (written < length) {
            val n = fwrite(pinned.addressOf(written), 1u, (length - written).toULong(), file).toInt()
            if (n <= 0) error("fwrite failed")
            written += n
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeAllPosixBytes(
    path: String,
    bytes: ByteArray,
) {
    val file = fopen(path, "wb") ?: error("Unable to create $path")
    try {
        writePosixFileBytes(file, bytes, bytes.size)
        fflush(file)
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readAllPosixBytes(path: String): ByteArray {
    val st = memScoped { alloc<stat>().also { if (stat(path, it.ptr) != 0) error("stat failed: $path") }.st_size.toInt() }
    val out = ByteArray(st)
    if (st == 0) return out
    val file = fopen(path, "rb") ?: error("fopen failed: $path")
    try {
        out.usePinned { pinned ->
            var offset = 0
            while (offset < st) {
                val n = fread(pinned.addressOf(offset), 1u, (st - offset).toULong(), file).toInt()
                if (n <= 0) break
                offset += n
            }
        }
    } finally {
        fclose(file)
    }
    return out
}

@OptIn(ExperimentalForeignApi::class)
private fun ensurePosixDir(path: String) {
    if (mkdir(path, DIR_MODE_0755) != 0 && errno != EEXIST) {
        error("mkdir failed for $path")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createWinTempDir(): String =
    memScoped {
        val created = mkdtemp("/tmp/kufus-win-XXXXXX".cstr.ptr)?.toKString() ?: error("mkdtemp failed")
        val buf = allocArray<ByteVar>(MAX_PATH_BYTES)
        realpath(created, buf)?.toKString() ?: created
    }

@OptIn(ExperimentalForeignApi::class)
private fun createSparseDiskFile(
    path: String,
    sizeBytes: Long,
) {
    val fd = open(path, O_RDWR or O_CREAT or O_TRUNC, S_IRUSR or S_IWUSR)
    if (fd < 0) error("open failed")
    try {
        if (ftruncate(fd, sizeBytes) != 0) error("ftruncate failed")
    } finally {
        close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun extractDiskSlice(
    srcPath: String,
    dstPath: String,
    startOffset: Long,
    lengthBytes: Long,
) {
    val inFd = open(srcPath, O_RDONLY)
    val outFd = open(dstPath, O_RDWR or O_CREAT or O_TRUNC, S_IRUSR or S_IWUSR)
    if (inFd < 0 || outFd < 0) error("slice open failed")
    try {
        val buf = ByteArray(COPY_SLICE_BUF_BYTES)
        var rem = lengthBytes
        var off = startOffset
        var dstOff = 0L
        buf.usePinned { pinned ->
            while (rem > 0L) {
                val n = pread(inFd, pinned.addressOf(0), minOf(buf.size.toLong(), rem).toULong(), off)
                if (n <= 0L) break
                if (pwrite(outFd, pinned.addressOf(0), n.toULong(), dstOff) != n) error("slice write failed")
                off += n
                dstOff += n
                rem -= n
            }
        }
        fsync(outFd)
    } finally {
        close(outFd)
        close(inFd)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun deleteTreeRecursively(path: String) {
    if (unlink(path) == 0) return
    val dir = opendir(path) ?: return
    val names =
        try {
            buildList {
                while (true) {
                    val entry = readdir(dir) ?: break
                    val name = entry.pointed.d_name.toKString()
                    if (name != "." && name != "..") add(name)
                }
            }
        } finally {
            closedir(dir)
        }
    names.forEach { deleteTreeRecursively("$path/$it") }
    rmdir(path)
}
