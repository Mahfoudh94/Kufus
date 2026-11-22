package io.github.drlacheheb.kufus.host

class MacosElevationCommandBuilder : ElevationCommandBuilder {
    override fun buildElevatedCommand(command: List<String>): List<String> {
        require(command.isNotEmpty()) { "Command must not be empty" }
        val shellCommand = command.joinToString(separator = " ", transform = ::quotePosixShellArgument)
        val appleScriptLiteral = escapeAppleScriptString(shellCommand)
        return listOf(
            "osascript",
            "-e",
            "do shell script \"$appleScriptLiteral\" with administrator privileges",
        )
    }

    private fun quotePosixShellArgument(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun escapeAppleScriptString(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
}
