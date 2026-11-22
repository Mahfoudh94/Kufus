package io.github.drlacheheb.kufus.domain.repository

import io.github.drlacheheb.kufus.disk.ImageInfo

fun interface ImageRepository {
    suspend fun inspectImage(path: String): Result<ImageInfo>
}
