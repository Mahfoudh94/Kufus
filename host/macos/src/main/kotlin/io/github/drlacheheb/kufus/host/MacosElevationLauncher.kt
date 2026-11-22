package io.github.drlacheheb.kufus.host

class MacosElevationLauncher(
    private val commandExecutor: HostCommandExecutor,
) : ElevationLauncher {
    override suspend fun launchElevated(
        executablePath: String,
        arguments: List<String>,
    ): Result<ElevationResult> {
        require(executablePath.isNotBlank()) { "Executable path must not be blank" }
        val command = listOf(executablePath) + arguments
        val shellCommand = command.joinToString(separator = " ", transform = ::quotePosixShellArgument)
        val appleScriptLiteral = escapeAppleScriptString(shellCommand)
        val wrapper =
            listOf(
                "osascript",
                "-e",
                "do shell script \"$appleScriptLiteral\" with administrator privileges",
            )
        return commandExecutor
            .run(wrapper)
            .map { output ->
                ElevationResult(
                    exitCode = output.exitCode,
                    stdout = output.stdout,
                    stderr = output.stderr,
                )
            }
    }

    private fun quotePosixShellArgument(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun escapeAppleScriptString(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
}
