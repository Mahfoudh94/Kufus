package io.github.drlacheheb.kufus.host

data class HostCommandOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

fun interface HostCommandExecutor {
    suspend fun run(command: List<String>): Result<HostCommandOutput>
}

fun interface NativeElevationBridge {
    suspend fun launchElevated(
        executablePath: String,
        arguments: List<String>,
    ): Result<Int>
}

data class ElevationResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
)

fun interface ElevationLauncher {
    suspend fun launchElevated(
        executablePath: String,
        arguments: List<String>,
    ): Result<ElevationResult>
}
