package io.github.drlacheheb.kufus.host

data class HostCommandOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

data class ElevatedLineStreamResult(
    val exitCode: Int,
    val stderr: String = "",
)

fun interface HostCommandExecutor {
    suspend fun run(command: List<String>): Result<HostCommandOutput>

    suspend fun streamLines(
        command: List<String>,
        onLine: (String) -> Unit,
    ): Result<ElevatedLineStreamResult> =
        run(command).map { output ->
            output.stdout
                .lineSequence()
                .filter { it.isNotBlank() }
                .forEach(onLine)
            ElevatedLineStreamResult(exitCode = output.exitCode, stderr = output.stderr)
        }
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
    val streamsLiveOutput: Boolean
        get() = false

    suspend fun launchElevated(
        executablePath: String,
        arguments: List<String>,
    ): Result<ElevationResult>

    suspend fun streamElevated(
        executablePath: String,
        arguments: List<String>,
        onLine: (String) -> Unit,
    ): Result<ElevationResult> =
        launchElevated(executablePath, arguments).onSuccess { result ->
            result.stdout
                .lineSequence()
                .filter { it.isNotBlank() }
                .forEach(onLine)
        }
}
