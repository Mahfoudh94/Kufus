package io.github.drlacheheb.kufus.host

data class WorkerBinarySpec(
    val targetName: String,
    val executableNames: List<String>,
)

data class NativeLibrarySpec(
    val targetName: String,
    val libraryNames: List<String>,
)
