package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.ImageInfo

fun interface NativeImageBridge {
    suspend fun inspectImage(path: String): Result<ImageInfo>
}
