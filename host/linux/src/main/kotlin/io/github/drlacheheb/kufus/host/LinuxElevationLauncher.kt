package io.github.drlacheheb.kufus.host

class LinuxElevationLauncher(
    private val commandExecutor: HostCommandExecutor,
) : ElevationLauncher {
    override val streamsLiveOutput: Boolean = true

    override suspend fun launchElevated(
        executablePath: String,
        arguments: List<String>,
    ): Result<ElevationResult> {
        require(executablePath.isNotBlank()) { "Executable path must not be blank" }
        return commandExecutor
            .run(listOf("pkexec", executablePath) + arguments)
            .map { output ->
                ElevationResult(
                    exitCode = output.exitCode,
                    stdout = output.stdout,
                    stderr = output.stderr,
                )
            }
    }

    override suspend fun streamElevated(
        executablePath: String,
        arguments: List<String>,
        onLine: (String) -> Unit,
    ): Result<ElevationResult> {
        require(executablePath.isNotBlank()) { "Executable path must not be blank" }
        return commandExecutor
            .streamLines(listOf("pkexec", executablePath) + arguments, onLine)
            .map { result ->
                ElevationResult(
                    exitCode = result.exitCode,
                    stderr = result.stderr,
                )
            }
    }
}
