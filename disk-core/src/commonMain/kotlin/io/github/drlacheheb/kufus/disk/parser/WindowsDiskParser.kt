package io.github.drlacheheb.kufus.disk.parser

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

fun formatWindowsCommandArguments(arguments: List<String>): String? {
    if (arguments.isEmpty()) return null
    return arguments.joinToString(separator = " ", transform = ::quoteWindowsArgument)
}

private fun quoteWindowsArgument(argument: String): String {
    val needsQuoting = argument.isEmpty() || argument.any { it == ' ' || it == '\t' || it == '"' }
    if (!needsQuoting) return argument
    return buildString {
        append('"')
        var backslashCount = 0
        argument.forEach { ch ->
            when (ch) {
                '\\' -> backslashCount++
                '"' -> {
                    repeat(backslashCount * 2 + 1) { append('\\') }
                    append('"')
                    backslashCount = 0
                }
                else -> {
                    repeat(backslashCount) { append('\\') }
                    append(ch)
                    backslashCount = 0
                }
            }
        }
        repeat(backslashCount * 2) { append('\\') }
        append('"')
    }
}
