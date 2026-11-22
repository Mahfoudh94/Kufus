package io.github.drlacheheb.kufus.data.process

data class CommandOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

interface CommandRunner {
    suspend fun run(command: List<String>): Result<CommandOutput>
}
