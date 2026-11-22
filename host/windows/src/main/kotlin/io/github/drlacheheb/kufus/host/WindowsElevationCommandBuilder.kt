package io.github.drlacheheb.kufus.host

class WindowsElevationCommandBuilder : ElevationCommandBuilder {
    override fun buildElevatedCommand(command: List<String>): List<String> {
        require(command.isNotEmpty()) { "Command must not be empty" }
        val executable = quotePowerShellLiteral(command.first())
        val arguments = command.drop(1)
        val argumentClause =
            if (arguments.isEmpty()) {
                ""
            } else {
                val escapedArgs = arguments.joinToString(separator = ", ", transform = ::quotePowerShellLiteral)
                " -ArgumentList @($escapedArgs)"
            }
        val script =
            "\$process = Start-Process -FilePath $executable$argumentClause -Verb RunAs -Wait -PassThru; " +
                "exit \$process.ExitCode"
        return listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
    }

    private fun quotePowerShellLiteral(value: String): String = "'${value.replace("'", "''")}'"
}
