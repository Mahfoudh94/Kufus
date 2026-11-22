package io.github.drlacheheb.kufus.data.jni

object KufusJniLibrary {
    private var loadedPath: String? = null

    @Synchronized
    fun loadIfNeeded(libraryPath: String) {
        if (loadedPath == libraryPath) return
        System.load(libraryPath)
        loadedPath = libraryPath
    }

    external fun listUsbDrivesJson(): String?

    external fun launchElevatedProcess(
        executablePath: String,
        argumentsJson: String,
    ): Int
}
