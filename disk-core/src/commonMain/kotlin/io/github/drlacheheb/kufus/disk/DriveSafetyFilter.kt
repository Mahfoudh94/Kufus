package io.github.drlacheheb.kufus.disk

/**
 * Filters candidate drives down to non-system, removable drives with a positive byte capacity.
 */
fun filterSafeUsbDrives(drives: List<UsbDrive>): List<UsbDrive> =
    drives.filter { drive ->
        drive.isRemovable && !drive.isSystemDisk && drive.sizeBytes > 0L
    }
