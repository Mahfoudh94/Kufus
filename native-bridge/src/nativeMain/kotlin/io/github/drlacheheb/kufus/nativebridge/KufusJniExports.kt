package io.github.drlacheheb.kufus.nativebridge

import io.github.drlacheheb.kufus.disk.model.DiskJson
import io.github.drlacheheb.kufus.disk.model.WorkerEvent
import io.github.drlacheheb.kufus.disk.model.filterSafeUsbDrives
import io.github.drlacheheb.kufus.disk.platform.inspectPlatformImage
import io.github.drlacheheb.kufus.disk.platform.launchPlatformElevatedProcess
import io.github.drlacheheb.kufus.disk.platform.listPlatformUsbDrives
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
import platform.posix._exit
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

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_io_github_drlacheheb_kufus_data_jni_KufusJniLibrary_inspectImageJson")
@Suppress("UnusedParameter")
fun jniInspectImageJson(
    env: CPointer<JNIEnvVar>,
    clazz: jclass?,
    jImagePath: jstring?,
): jstring? = toJString(env, encodeInspectPayload(fromJString(env, jImagePath)))

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_io_github_drlacheheb_kufus_data_jni_KufusJniLibrary_terminateProcessImmediate")
@Suppress("UnusedParameter")
fun jniTerminateProcessImmediate(
    env: CPointer<JNIEnvVar>,
    clazz: jclass?,
    exitCode: Int,
) {
    _exit(exitCode)
}

private fun encodeInspectPayload(imagePath: String?): String {
    if (imagePath.isNullOrBlank()) {
        return DiskJson.encodeEvent(
            WorkerEvent.Error(
                code = "INVALID_ARGS",
                message = "Image path cannot be blank",
            ),
        )
    }
    return try {
        inspectPlatformImage(imagePath).fold(
            onSuccess = { info -> DiskJson.encodeEvent(WorkerEvent.ImageInspected(info)) },
            onFailure = { error ->
                DiskJson.encodeEvent(
                    WorkerEvent.Error(
                        code = "INSPECT_FAILED",
                        message = error.message ?: "Image inspection failed",
                    ),
                )
            },
        )
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        DiskJson.encodeEvent(
            WorkerEvent.Error(
                code = "NATIVE_INSPECT_FAILED",
                message = e.message ?: "Native image inspection failed",
            ),
        )
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
