package io.github.drlacheheb.kufus.data.jni

import io.github.drlacheheb.kufus.data.repository.NativeLibraryLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal suspend fun <T> invokeNativeJsonCall(
    libraryLocator: NativeLibraryLocator,
    ioDispatcher: CoroutineDispatcher,
    call: () -> String?,
    decode: (String) -> Result<T>,
): Result<T> =
    withContext(ioDispatcher) {
        try {
            val libraryPath =
                libraryLocator.locateLibraryPath()
                    ?: return@withContext Result.failure(IllegalStateException("Native shared library not found"))
            KufusJniLibrary.loadIfNeeded(libraryPath)
            val payload =
                call()
                    ?: return@withContext Result.failure(IllegalStateException("Native shared library returned null"))
            val firstLine = payload.lineSequence().firstOrNull { it.isNotBlank() }
            if (firstLine == null) {
                Result.failure(IllegalStateException("Native shared library returned empty output"))
            } else {
                decode(firstLine)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Result.failure(e)
        }
    }
