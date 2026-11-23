package io.github.drlacheheb.kufus.data.jni

import io.github.drlacheheb.kufus.data.repository.NativeLibraryLocator
import io.github.drlacheheb.kufus.disk.model.DiskJson
import io.github.drlacheheb.kufus.host.NativeElevationBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

class JniElevationBridge(
    private val libraryLocator: NativeLibraryLocator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NativeElevationBridge {
    override suspend fun launchElevated(
        executablePath: String,
        arguments: List<String>,
    ): Result<Int> =
        withContext(ioDispatcher) {
            try {
                val libraryPath =
                    libraryLocator.locateLibraryPath()
                        ?: return@withContext Result.failure(IllegalStateException("Native shared library not found"))
                KufusJniLibrary.loadIfNeeded(libraryPath)
                val argumentsJson =
                    DiskJson.codec.encodeToString(ListSerializer(String.serializer()), arguments)
                val exitCode = KufusJniLibrary.launchElevatedProcess(executablePath, argumentsJson)
                if (exitCode < 0) {
                    Result.failure(IllegalStateException("Elevated process launch failed or was cancelled"))
                } else {
                    Result.success(exitCode)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Result.failure(e)
            }
        }
}
