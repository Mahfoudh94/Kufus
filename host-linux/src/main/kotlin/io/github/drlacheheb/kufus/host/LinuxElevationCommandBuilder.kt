package io.github.drlacheheb.kufus.host

class LinuxElevationCommandBuilder : ElevationCommandBuilder {
    override fun buildElevatedCommand(command: List<String>): List<String> {
        require(command.isNotEmpty()) { "Command must not be empty" }
        return listOf("pkexec") + command
    }
}
