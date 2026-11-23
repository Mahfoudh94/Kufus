package io.github.drlacheheb.kufus.data.jni

import io.github.drlacheheb.kufus.data.repository.NativeImageBridge
import io.github.drlacheheb.kufus.data.repository.NativeLibraryLocator
import io.github.drlacheheb.kufus.data.repository.decodeWorkerImageLine
import io.github.drlacheheb.kufus.disk.model.ImageInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class JniImageBridge(
    private val libraryLocator: NativeLibraryLocator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NativeImageBridge {
    override suspend fun inspectImage(path: String): Result<ImageInfo> =
        invokeNativeJsonCall(
            libraryLocator = libraryLocator,
            ioDispatcher = ioDispatcher,
            call = { KufusJniLibrary.inspectImageJson(path) },
            decode = ::decodeWorkerImageLine,
        )
}
