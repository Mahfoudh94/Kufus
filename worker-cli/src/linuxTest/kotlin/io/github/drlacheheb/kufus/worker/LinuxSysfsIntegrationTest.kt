package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.LinuxSysfsPaths
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.listLinuxSysfsUsbDrives
import io.github.drlacheheb.kufus.disk.watchPlatformUsbDrives
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.mkdir
import platform.posix.mkdtemp
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.realpath
import platform.posix.rmdir
import platform.posix.symlink
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals

private const val DIR_MODE = 0x1EDu
private const val PATH_BUF_SIZE = 4096

class LinuxSysfsIntegrationTest {
    @Test
    fun readsVirtualUsbSysfsTreeResolvesUsbSerialAndExcludesLoopNvmeAndEncryptedRootUsb() {
        val root = createTempRoot()
        try {
            val paths = buildVirtualSysfsTree(root)

            val drives = listLinuxSysfsUsbDrives(paths)

            assertEquals(
                listOf(
                    UsbDrive(
                        id = "/dev/sdb",
                        name = "sdb",
                        model = "Kufus Virtual Flash",
                        sizeBytes = 128_000_000L,
                        serial = "KF-VUSB-001",
                        mountPoints = listOf("/run/media/test/Kufus USB"),
                        isRemovable = true,
                        isSystemDisk = false,
                    ),
                ),
                drives,
            )
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun watchPlatformUsbDrivesEmitsInitialSnapshotAndStopsWhenCancelled() {
        val emissions = mutableListOf<List<UsbDrive>>()
        var checks = 0

        watchPlatformUsbDrives(
            onDrivesUpdated = { drives -> emissions += drives },
            shouldContinue = {
                checks++
                false
            },
        )

        assertEquals(1, emissions.size)
        assertEquals(1, checks)
    }

    private fun buildVirtualSysfsTree(root: String): LinuxSysfsPaths {
        val usbBus = "$root/sys/bus/usb".also(::ensureDirTree)
        val nvmeBus = "$root/sys/bus/nvme".also(::ensureDirTree)
        val sysBlock = "$root/sys/block".also(::ensureDirTree)
        val sysDevices = "$root/sys/devices".also(::ensureDirTree)

        addUsbBlockDevice(
            root = root,
            portPath = "pci0000:00/usb1/1-2",
            usbBus = usbBus,
            blockName = "sdb",
            sizeSectors = 250_000L,
            removable = "1",
            vendor = "Kufus   ",
            model = "Virtual Flash   ",
            usbSerial = "KF-VUSB-001",
            partitions = listOf("sdb1"),
        )
        addUsbBlockDevice(
            root = root,
            portPath = "pci0000:00/usb2/2-1",
            usbBus = usbBus,
            blockName = "sdc",
            sizeSectors = 500_000L,
            removable = "0",
            vendor = "Kufus   ",
            model = "Encrypted Root SSD",
            usbSerial = "KF-SYS-002",
            partitions = listOf("sdc1"),
        )
        addInternalNvmeDevice(nvmeBus = nvmeBus, sysBlock = sysBlock, sysDevices = sysDevices)
        addVirtualBlockDevice(root = root, blockName = "loop0", sizeSectors = 250_000L, slaves = emptyList())
        addVirtualBlockDevice(root = root, blockName = "dm-0", sizeSectors = 500_000L, slaves = listOf("sdc1"))

        val procDir = "$root/proc".also(::ensureDirTree)
        val mountsPath = "$procDir/mounts"
        val swapsPath = "$procDir/swaps"
        writeText(
            mountsPath,
            """
            /dev/sdb1 /run/media/test/Kufus\040USB vfat rw,nosuid 0 0
            /dev/dm-0 / ext4 rw,relatime 0 0
            """.trimIndent(),
        )
        writeText(
            swapsPath,
            """
            Filename Type Size Used Priority
            /dev/zram0 partition 1024 0 100
            """.trimIndent(),
        )
        return LinuxSysfsPaths(
            sysBlockRoot = sysBlock,
            sysDevicesPrefix = "$sysDevices/",
            procMountsPath = mountsPath,
            procSwapsPath = swapsPath,
        )
    }

    private fun addUsbBlockDevice(
        root: String,
        portPath: String,
        usbBus: String,
        blockName: String,
        sizeSectors: Long,
        removable: String,
        vendor: String,
        model: String,
        usbSerial: String,
        partitions: List<String>,
    ) {
        val usbDeviceDir = "$root/sys/devices/$portPath".also(::ensureDirTree)
        createSymlink(usbBus, "$usbDeviceDir/subsystem")
        writeText("$usbDeviceDir/serial", "$usbSerial\n")

        val usbInterfaceDir = "$usbDeviceDir/1-0".also(::ensureDirTree)
        createSymlink(usbBus, "$usbInterfaceDir/subsystem")

        val scsiDeviceDir = "$usbInterfaceDir/host0/target0:0:0/0:0:0:0".also(::ensureDirTree)
        writeText("$scsiDeviceDir/vendor", "$vendor\n")
        writeText("$scsiDeviceDir/model", "$model\n")
        writeText("$scsiDeviceDir/type", "0\n")

        val blockDir = "$scsiDeviceDir/block/$blockName".also(::ensureDirTree)
        createSymlink("../..", "$blockDir/device")
        writeText("$blockDir/size", "$sizeSectors\n")
        writeText("$blockDir/removable", "$removable\n")
        ensureDirTree("$blockDir/slaves")
        partitions.forEach { part ->
            val partDir = "$blockDir/$part".also(::ensureDirTree)
            writeText("$partDir/partition", "1\n")
        }
        createSymlink(blockDir, "$root/sys/block/$blockName")
    }

    private fun addInternalNvmeDevice(
        nvmeBus: String,
        sysBlock: String,
        sysDevices: String,
    ) {
        val nvmeController = "$sysDevices/pci0000:00/nvme/nvme0".also(::ensureDirTree)
        createSymlink(nvmeBus, "$nvmeController/subsystem")
        val blockDir = "$nvmeController/nvme0n1".also(::ensureDirTree)
        createSymlink("..", "$blockDir/device")
        writeText("$blockDir/size", "1000000\n")
        writeText("$blockDir/removable", "0\n")
        ensureDirTree("$blockDir/slaves")
        createSymlink(blockDir, "$sysBlock/nvme0n1")
    }

    private fun addVirtualBlockDevice(
        root: String,
        blockName: String,
        sizeSectors: Long,
        slaves: List<String>,
    ) {
        val blockDir = "$root/sys/devices/virtual/block/$blockName".also(::ensureDirTree)
        writeText("$blockDir/size", "$sizeSectors\n")
        val slavesDir = "$blockDir/slaves".also(::ensureDirTree)
        slaves.forEach { slave ->
            createSymlink(blockDir, "$slavesDir/$slave")
        }
        createSymlink(blockDir, "$root/sys/block/$blockName")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createTempRoot(): String =
    memScoped {
        val template = "/tmp/kufus-sysfs-XXXXXX".cstr.ptr
        val created = mkdtemp(template)?.toKString() ?: error("mkdtemp failed")
        val buf = allocArray<ByteVar>(PATH_BUF_SIZE)
        realpath(created, buf)?.toKString() ?: created
    }

@OptIn(ExperimentalForeignApi::class)
private fun ensureDirTree(path: String) {
    var current = if (path.startsWith('/')) "/" else ""
    path
        .split('/')
        .filter { it.isNotEmpty() }
        .forEach { part ->
            current = if (current == "/") "/$part" else "$current/$part"
            mkdir(current, DIR_MODE)
        }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeText(
    path: String,
    content: String,
) {
    val file = fopen(path, "w") ?: error("Failed to open $path")
    try {
        fputs(content, file)
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createSymlink(
    target: String,
    linkPath: String,
) {
    unlink(linkPath)
    if (symlink(target, linkPath) != 0) {
        error("Failed to create symlink $linkPath -> $target")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun deleteRecursively(path: String) {
    if (unlink(path) == 0) return
    val dir = opendir(path) ?: return
    val children =
        try {
            buildList {
                while (true) {
                    val entry = readdir(dir) ?: break
                    val name = entry.pointed.d_name.toKString()
                    if (name != "." && name != "..") {
                        add(name)
                    }
                }
            }
        } finally {
            closedir(dir)
        }
    children.forEach { child -> deleteRecursively("$path/$child") }
    rmdir(path)
}
