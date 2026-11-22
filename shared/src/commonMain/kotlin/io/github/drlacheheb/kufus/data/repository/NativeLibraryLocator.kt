package io.github.drlacheheb.kufus.data.repository

fun interface NativeLibraryLocator {
    fun locateLibraryPath(): String?
}
