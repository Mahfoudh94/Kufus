package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.ImageInfo
import io.github.drlacheheb.kufus.domain.repository.ImageRepository

class NativeImageRepository(
    private val nativeImageBridge: NativeImageBridge,
) : ImageRepository {
    override suspend fun inspectImage(path: String): Result<ImageInfo> = nativeImageBridge.inspectImage(path)
}
