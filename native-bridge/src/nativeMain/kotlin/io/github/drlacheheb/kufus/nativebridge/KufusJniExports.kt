package io.github.drlacheheb.kufus.nativebridge

import io.github.drlacheheb.kufus.disk.DiskJson
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.github.drlacheheb.kufus.disk.filterSafeUsbDrives
import io.github.drlacheheb.kufus.disk.launchPlatformElevatedProcess
import io.github.drlacheheb.kufus.disk.listPlatformUsbDrives
import io.github.drlacheheb.kufus.jni.cinterop.JNIEnvVar
import io.github.drlacheheb.kufus.jni.cinterop.jclass
import io.github.drlacheheb.kufus.jni.cinterop.jstring
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_io_github_drlacheheb_kufus_data_jni_KufusJniLibrary_listUsbDrivesJson")
@Suppress("UnusedParameter")
fun jniListUsbDrivesJson(
    env: CPointer<JNIEnvVar>,
    clazz: jclass?,
): jstring? {
    val payload =
        try {
            val drives = filterSafeUsbDrives(listPlatformUsbDrives())
            DiskJson.encodeEvent(WorkerEvent.DrivesListed(drives))
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            DiskJson.encodeEvent(
                WorkerEvent.Error(
                    code = "NATIVE_LIST_FAILED",
                    message = e.message ?: "Native USB drive query failed",
                ),
            )
        }
    return toJString(env, payload)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_io_github_drlacheheb_kufus_data_jni_KufusJniLibrary_launchElevatedProcess")
@Suppress("UnusedParameter")
fun jniLaunchElevatedProcess(
    env: CPointer<JNIEnvVar>,
    clazz: jclass?,
    jExecutablePath: jstring?,
    jArgumentsJson: jstring?,
): Int {
    val executablePath = fromJString(env, jExecutablePath) ?: return -1
    val argumentsJson = fromJString(env, jArgumentsJson) ?: "[]"
    return try {
        val args = DiskJson.codec.decodeFromString(ListSerializer(String.serializer()), argumentsJson)
        launchPlatformElevatedProcess(executablePath, args)
    } catch (
        @Suppress("TooGenericExceptionCaught") _: Exception,
    ) {
        -1
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun toJString(
    env: CPointer<JNIEnvVar>,
    value: String,
): jstring? {
    val functions = env.pointed.pointed ?: return null
    val newStringUtf = functions.NewStringUTF ?: return null
    return memScoped {
        newStringUtf.invoke(env, value.cstr.ptr)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun fromJString(
    env: CPointer<JNIEnvVar>,
    value: jstring?,
): String? {
    if (value == null) return null
    val functions = env.pointed.pointed ?: return null
    val getChars = functions.GetStringUTFChars ?: return null
    val releaseChars = functions.ReleaseStringUTFChars
    val utfChars = getChars.invoke(env, value, null) ?: return null
    return try {
        utfChars.toKString()
    } finally {
        releaseChars?.invoke(env, value, utfChars)
    }
}
