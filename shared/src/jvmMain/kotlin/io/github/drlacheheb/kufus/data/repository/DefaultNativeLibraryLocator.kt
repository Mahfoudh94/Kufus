package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.host.NativeLibrarySpec

class DefaultNativeLibraryLocator(
    private val nativeLibrarySpec: NativeLibrarySpec,
    private val configuredNativeDir: String? = System.getProperty("kufus.native.dir"),
    private val appResourcesDir: String? = System.getProperty("compose.application.resources.dir"),
) : NativeLibraryLocator {
    override fun locateLibraryPath(): String? =
        locateNativeArtifact(
            moduleDir = "native-bridge",
            targetName = nativeLibrarySpec.targetName,
            variants = listOf("releaseShared", "debugShared"),
            artifactNames = nativeLibrarySpec.libraryNames,
            configuredDir = configuredNativeDir,
            appResourcesDir = appResourcesDir,
            requireExecutable = false,
        )
}
