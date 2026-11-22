package io.github.drlacheheb.kufus.data.jni

import io.github.drlacheheb.kufus.data.repository.NativeDiskBridge
import io.github.drlacheheb.kufus.data.repository.NativeLibraryLocator
import io.github.drlacheheb.kufus.data.repository.decodeWorkerDrivesLine
import io.github.drlacheheb.kufus.disk.UsbDrive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JniDiskBridge(
    private val libraryLocator: NativeLibraryLocator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NativeDiskBridge {
    override suspend fun listUsbDrives(): Result<List<UsbDrive>> =
        withContext(ioDispatcher) {
            try {
                val libraryPath =
                    libraryLocator.locateLibraryPath()
                        ?: return@withContext Result.failure(IllegalStateException("Native shared library not found"))
                KufusJniLibrary.loadIfNeeded(libraryPath)
                val payload =
                    KufusJniLibrary.listUsbDrivesJson()
                        ?: return@withContext Result.failure(IllegalStateException("Native shared library returned null"))
                val firstLine = payload.lineSequence().firstOrNull { it.isNotBlank() }
                if (firstLine == null) {
                    Result.failure(IllegalStateException("Native shared library returned empty output"))
                } else {
                    decodeWorkerDrivesLine(firstLine)
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
