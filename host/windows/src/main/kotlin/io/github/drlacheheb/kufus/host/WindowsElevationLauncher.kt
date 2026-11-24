package io.github.drlacheheb.kufus.host

class WindowsElevationLauncher(
    private val commandExecutor: HostCommandExecutor,
) : ElevationLauncher {
    override suspend fun launchElevated(
        executablePath: String,
        arguments: List<String>,
    ): Result<ElevationResult> {
        require(executablePath.isNotBlank()) { "Executable path must not be blank" }
        return commandExecutor
            .run(listOf(executablePath, "--elevate", executablePath) + arguments)
            .map { output ->
                ElevationResult(
                    exitCode = output.exitCode,
                    stdout = output.stdout,
                    stderr = output.stderr,
                )
            }
    }
}
