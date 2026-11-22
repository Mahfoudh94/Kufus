package io.github.drlacheheb.kufus.data.jni

import io.github.drlacheheb.kufus.data.repository.NativeDiskBridge
import io.github.drlacheheb.kufus.data.repository.NativeLibraryLocator
import io.github.drlacheheb.kufus.data.repository.decodeWorkerDrivesLine
import io.github.drlacheheb.kufus.disk.UsbDrive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class JniDiskBridge(
    private val libraryLocator: NativeLibraryLocator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NativeDiskBridge {
    override suspend fun listUsbDrives(): Result<List<UsbDrive>> =
        invokeNativeJsonCall(
            libraryLocator = libraryLocator,
            ioDispatcher = ioDispatcher,
            call = KufusJniLibrary::listUsbDrivesJson,
            decode = ::decodeWorkerDrivesLine,
        )
}
