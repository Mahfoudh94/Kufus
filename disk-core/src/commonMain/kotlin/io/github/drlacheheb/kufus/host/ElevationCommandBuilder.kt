package io.github.drlacheheb.kufus.host

fun interface ElevationCommandBuilder {
    fun buildElevatedCommand(command: List<String>): List<String>
}
