package io.github.drlacheheb.kufus.disk

private val macosSystemRoots = setOf("/", "/System", "/System/Volumes/Data", "/System/Volumes/VM", "/System/Volumes/Preboot")

data class DiskutilListEntry(
    val deviceIdentifier: String,
    val sizeBytes: Long,
    val mountPoints: List<String>,
)

data class DiskutilDeviceInfo(
    val deviceIdentifier: String,
    val mediaName: String,
    val busProtocol: String,
    val isInternal: Boolean,
    val isRemovable: Boolean,
    val isVirtual: Boolean,
    val totalSizeBytes: Long,
    val mountPoint: String?,
)

private sealed interface PlistValue {
    data class Str(
        val value: String,
    ) : PlistValue

    data class IntVal(
        val value: Long,
    ) : PlistValue

    data class BoolVal(
        val value: Boolean,
    ) : PlistValue

    data class Arr(
        val items: List<PlistValue>,
    ) : PlistValue

    data class Dict(
        val entries: Map<String, PlistValue>,
    ) : PlistValue
}

fun parseDiskutilListEntries(plistXml: String): List<DiskutilListEntry> {
    val root = parsePlistRootDict(plistXml) ?: return emptyList()
    val disksArray = (root.entries["AllDisksAndPartitions"] as? PlistValue.Arr)?.items.orEmpty()
    return disksArray.mapNotNull { item ->
        val dict = item as? PlistValue.Dict ?: return@mapNotNull null
        val id = dict.string("DeviceIdentifier") ?: return@mapNotNull null
        val size = dict.long("Size") ?: 0L
        DiskutilListEntry(
            deviceIdentifier = id,
            sizeBytes = size,
            mountPoints = collectPlistMountPoints(dict),
        )
    }
}

fun parseDiskutilInfo(plistXml: String): DiskutilDeviceInfo? {
    val root = parsePlistRootDict(plistXml) ?: return null
    val id = root.string("DeviceIdentifier") ?: return null
    val mediaName =
        root.string("MediaName")?.trim()?.takeIf { it.isNotEmpty() }
            ?: root.string("IORegistryEntryName")?.trim()?.takeIf { it.isNotEmpty() }
            ?: id
    val protocol =
        root.string("BusProtocol")?.trim()
            ?: root.string("Protocol")?.trim()
            ?: ""
    val isInternal = root.bool("Internal") ?: false
    val isRemovable =
        (root.bool("RemovableMediaOrExternalDevice") == true) ||
            (root.bool("RemovableMedia") == true) ||
            !isInternal
    val isVirtual = root.string("VirtualOrPhysical").equals("Virtual", ignoreCase = true)
    return DiskutilDeviceInfo(
        deviceIdentifier = id,
        mediaName = mediaName,
        busProtocol = protocol,
        isInternal = isInternal,
        isRemovable = isRemovable,
        isVirtual = isVirtual,
        totalSizeBytes = root.long("TotalSize") ?: root.long("Size") ?: 0L,
        mountPoint = root.string("MountPoint")?.trim()?.takeIf { it.isNotEmpty() },
    )
}

fun buildMacosUsbDrives(
    listPlistXml: String,
    infoPlistByDiskId: Map<String, String>,
): List<UsbDrive> {
    val entries = parseDiskutilListEntries(listPlistXml)
    val drives =
        entries.mapNotNull { entry ->
            val infoXml = infoPlistByDiskId[entry.deviceIdentifier] ?: return@mapNotNull null
            val info = parseDiskutilInfo(infoXml) ?: return@mapNotNull null
            if (info.isInternal || info.isVirtual || !info.busProtocol.equals("USB", ignoreCase = true)) {
                return@mapNotNull null
            }
            val mountPoints = (entry.mountPoints + listOfNotNull(info.mountPoint)).distinct()
            UsbDrive(
                id = "/dev/${info.deviceIdentifier}",
                name = info.deviceIdentifier,
                model = info.mediaName,
                sizeBytes = if (info.totalSizeBytes > 0L) info.totalSizeBytes else entry.sizeBytes,
                mountPoints = mountPoints,
                isRemovable = info.isRemovable,
                isSystemDisk = mountPoints.any(::isMacosSystemMountPoint),
            )
        }
    return filterSafeUsbDrives(drives)
}

fun isValidBsdDiskIdentifier(identifier: String): Boolean =
    identifier.startsWith("disk") &&
        identifier.length > 4 &&
        identifier.substring(4).all { it.isDigit() }

private fun collectPlistMountPoints(dict: PlistValue.Dict): List<String> =
    buildList {
        dict.string("MountPoint")?.takeIf { it.isNotBlank() }?.let(::add)
        val partitions = (dict.entries["Partitions"] as? PlistValue.Arr)?.items.orEmpty()
        partitions.mapNotNull { it as? PlistValue.Dict }.forEach { addAll(collectPlistMountPoints(it)) }
        val apfsVolumes = (dict.entries["APFSVolumes"] as? PlistValue.Arr)?.items.orEmpty()
        apfsVolumes.mapNotNull { it as? PlistValue.Dict }.forEach { addAll(collectPlistMountPoints(it)) }
    }.distinct()

private fun isMacosSystemMountPoint(mountPoint: String): Boolean = mountPoint in macosSystemRoots || mountPoint.startsWith("/System/")

private fun PlistValue.Dict.string(key: String): String? = (entries[key] as? PlistValue.Str)?.value

private fun PlistValue.Dict.long(key: String): Long? = (entries[key] as? PlistValue.IntVal)?.value

private fun PlistValue.Dict.bool(key: String): Boolean? = (entries[key] as? PlistValue.BoolVal)?.value

private fun parsePlistRootDict(xml: String): PlistValue.Dict? {
    val reader = PlistXmlReader(xml)
    var tag = reader.nextTag()
    while (tag != null) {
        if (tag == "dict") return reader.readDict()
        tag = reader.nextTag()
    }
    return null
}

private class PlistXmlReader(
    private val xml: String,
) {
    private var cursor = 0

    fun nextTag(): String? {
        var found: String? = null
        while (found == null && cursor < xml.length) {
            found = readNextRelevantTag()
        }
        return found
    }

    private fun readNextRelevantTag(): String? {
        val open = xml.indexOf('<', cursor)
        if (open < 0) {
            cursor = xml.length
            return null
        }
        if (xml.startsWith("<!--", open)) {
            val commentEnd = xml.indexOf("-->", open + 4)
            cursor = if (commentEnd < 0) xml.length else commentEnd + 3
            return null
        }
        val close = xml.indexOf('>', open + 1)
        if (close < 0) {
            cursor = xml.length
            return null
        }
        val rawTag = xml.substring(open + 1, close).trim()
        cursor = close + 1
        if (rawTag.startsWith("?") || rawTag.startsWith("!")) return null
        val selfClosing = rawTag.endsWith("/")
        val tagName = rawTag.removeSuffix("/").trim().substringBefore(' ')
        return if (selfClosing) "$tagName/" else tagName
    }

    fun readDict(): PlistValue.Dict {
        val entries = linkedMapOf<String, PlistValue>()
        var tag = nextTag()
        while (tag != null && tag != "/dict") {
            if (tag == "key") {
                val key = unescapeXml(readTextUntil("</key>"))
                val valueTag = nextTag()
                if (valueTag != null) {
                    entries[key] = readValue(valueTag)
                }
            }
            tag = nextTag()
        }
        return PlistValue.Dict(entries)
    }

    fun readArray(): PlistValue.Arr {
        val items = mutableListOf<PlistValue>()
        var tag = nextTag()
        while (tag != null && tag != "/array") {
            items += readValue(tag)
            tag = nextTag()
        }
        return PlistValue.Arr(items)
    }

    private fun readValue(tag: String): PlistValue =
        when (tag) {
            "dict" -> readDict()
            "dict/" -> PlistValue.Dict(emptyMap())
            "array" -> readArray()
            "array/" -> PlistValue.Arr(emptyList())
            "string" -> PlistValue.Str(unescapeXml(readTextUntil("</string>")))
            "string/" -> PlistValue.Str("")
            "integer" -> PlistValue.IntVal(readTextUntil("</integer>").trim().toLongOrNull() ?: 0L)
            "true/" -> PlistValue.BoolVal(true)
            "false/" -> PlistValue.BoolVal(false)
            else -> skipUnknownValue(tag)
        }

    private fun skipUnknownValue(tag: String): PlistValue {
        if (!tag.endsWith("/") && !tag.startsWith("/")) {
            readTextUntil("</$tag>")
        }
        return PlistValue.Str("")
    }

    private fun readTextUntil(closingTag: String): String {
        val end = xml.indexOf(closingTag, cursor)
        if (end < 0) {
            val rest = xml.substring(cursor)
            cursor = xml.length
            return rest
        }
        val text = xml.substring(cursor, end)
        cursor = end + closingTag.length
        return text
    }

    private fun unescapeXml(raw: String): String =
        raw
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
}
