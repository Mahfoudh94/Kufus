package io.github.drlacheheb.kufus.disk

fun buildWindowsDriveModel(
    vendorId: String?,
    productId: String?,
    fallbackName: String,
): String =
    listOfNotNull(vendorId?.trim(), productId?.trim())
        .filter { it.isNotEmpty() }
        .joinToString(separator = " ")
        .ifEmpty { fallbackName }

fun normalizeWindowsDriveLetter(pathOrLetter: String): String =
    pathOrLetter
        .trim()
        .trimEnd('\\', '/')
        .uppercase()

fun isWindowsSystemMount(
    mountPoint: String,
    systemDrivePrefix: String,
): Boolean {
    val normalizedMount = normalizeWindowsDriveLetter(mountPoint)
    val normalizedSystem = normalizeWindowsDriveLetter(systemDrivePrefix)
    return normalizedMount.isNotEmpty() && normalizedMount == normalizedSystem
}
