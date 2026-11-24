package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.model.ImageFormat
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

class JvmImageRepositoryTest {
    @Test
    fun inspectsRawBootableImageDirectlyOnJvm() =
        runTest {
            val tempDir = createTempDirectory("kufus-jvm-image-test").toFile()
            try {
                val imageFile = File(tempDir, "boot.img")
                val bytes = ByteArray(1024)
                bytes[510] = 0x55
                bytes[511] = 0xAA.toByte()
                imageFile.writeBytes(bytes)

                val info = JvmImageRepository().inspectImage(imageFile.absolutePath).getOrThrow()

                info.path shouldBe imageFile.absolutePath
                info.sizeBytes shouldBe 1024L
                info.format shouldBe ImageFormat.RAW_IMAGE
            } finally {
                tempDir.deleteRecursively()
            }
        }

    @Test
    fun rejectsBlankMissingAndDirectoryPaths() =
        runTest {
            val tempDir = createTempDirectory("kufus-jvm-image-invalid").toFile()
            try {
                val repository = JvmImageRepository()
                repository.inspectImage("  ").isFailure shouldBe true
                repository.inspectImage(File(tempDir, "missing.iso").absolutePath).isFailure shouldBe true
                repository.inspectImage(tempDir.absolutePath).isFailure shouldBe true
            } finally {
                tempDir.deleteRecursively()
            }
        }
}
