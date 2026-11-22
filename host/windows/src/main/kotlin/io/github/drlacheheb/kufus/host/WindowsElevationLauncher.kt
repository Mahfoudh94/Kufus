package io.github.drlacheheb.kufus.host

class WindowsElevationLauncher(
    private val nativeBridge: NativeElevationBridge,
) : ElevationLauncher {
    override suspend fun launchElevated(
        executablePath: String,
        arguments: List<String>,
    ): Result<ElevationResult> {
        require(executablePath.isNotBlank()) { "Executable path must not be blank" }
        return nativeBridge
            .launchElevated(executablePath, arguments)
            .map { exitCode -> ElevationResult(exitCode = exitCode) }
    }
}
