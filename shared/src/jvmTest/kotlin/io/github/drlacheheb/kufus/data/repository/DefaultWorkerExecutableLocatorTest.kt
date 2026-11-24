package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.host.WorkerBinarySpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

class DefaultWorkerExecutableLocatorTest {
    @Test
    fun prefersConfiguredWorkerDirectoryOverAppResourcesDirectory() {
        val tempRoot = createTempDirectory("kufus-locator-test").toFile()
        try {
            val configuredDir = File(tempRoot, "configured").apply { mkdirs() }
            val resourcesDir = File(tempRoot, "resources").apply { mkdirs() }
            val configuredBinary =
                File(configuredDir, "kufus-worker").apply {
                    writeText("binary")
                    setExecutable(true)
                }
            File(resourcesDir, "kufus-worker").apply {
                writeText("fallback")
                setExecutable(true)
            }

            val locator =
                DefaultWorkerExecutableLocator(
                    workerBinarySpec =
                        WorkerBinarySpec(
                            targetName = "missingTarget",
                            executableNames = listOf("kufus-worker", "kufus-worker.kexe"),
                        ),
                    configuredWorkerDir = configuredDir.absolutePath,
                    appResourcesDir = resourcesDir.absolutePath,
                )

            locator.locateWorkerPath() shouldBe configuredBinary.absolutePath
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun fallsBackToAppResourcesDirectoryWhenConfiguredDirectoryIsEmpty() {
        val tempRoot = createTempDirectory("kufus-locator-fallback").toFile()
        try {
            val emptyDir = File(tempRoot, "empty").apply { mkdirs() }
            val resourcesDir = File(tempRoot, "resources").apply { mkdirs() }
            val resourceBinary =
                File(resourcesDir, "kufus-worker.kexe").apply {
                    writeText("binary")
                    setExecutable(true)
                }

            val locator =
                DefaultWorkerExecutableLocator(
                    workerBinarySpec =
                        WorkerBinarySpec(
                            targetName = "missingTarget",
                            executableNames = listOf("kufus-worker", "kufus-worker.kexe"),
                        ),
                    configuredWorkerDir = emptyDir.absolutePath,
                    appResourcesDir = resourcesDir.absolutePath,
                )

            locator.locateWorkerPath() shouldBe resourceBinary.absolutePath
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun ignoresDirectoriesAndMismatchedBinaryNames() {
        val tempRoot = createTempDirectory("kufus-locator-ignore").toFile()
        try {
            val workerDir = File(tempRoot, "worker").apply { mkdirs() }
            File(workerDir, "kufus-worker").mkdirs()
            File(workerDir, "kufus-worker.exe").apply {
                writeText("windows-binary")
                setExecutable(true)
            }

            val locator =
                DefaultWorkerExecutableLocator(
                    workerBinarySpec =
                        WorkerBinarySpec(
                            targetName = "missingTarget",
                            executableNames = listOf("kufus-worker", "kufus-worker.kexe"),
                        ),
                    configuredWorkerDir = workerDir.absolutePath,
                    appResourcesDir = null,
                )

            locator.locateWorkerPath() shouldBe null
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun resolvesUefiNtfsCompanionAssetFromExplicitPropertyAppResourcesOrWorkerSibling() {
        val tempRoot = createTempDirectory("kufus-uefi-locator").toFile()
        try {
            val explicitFile = File(tempRoot, "explicit/uefi-ntfs.img")
            explicitFile.parentFile.mkdirs()
            explicitFile.writeText("explicit")
            val resourcesDir = File(tempRoot, "resources").apply { mkdirs() }
            val resourceFile = File(resourcesDir, "uefi-ntfs.img").apply { writeText("resource") }
            val workerDir = File(tempRoot, "worker").apply { mkdirs() }
            File(workerDir, "kufus-worker").apply {
                writeText("bin")
                setExecutable(true)
            }
            val siblingFile = File(workerDir, "uefi-ntfs.img").apply { writeText("sibling") }

            val spec = WorkerBinarySpec("missing", listOf("kufus-worker"))
            DefaultWorkerExecutableLocator(spec, workerDir.absolutePath, resourcesDir.absolutePath, explicitFile.absolutePath)
                .locateCompanionFile("uefi-ntfs.img") shouldBe explicitFile.absolutePath
            DefaultWorkerExecutableLocator(spec, workerDir.absolutePath, resourcesDir.absolutePath, null)
                .locateCompanionFile("uefi-ntfs.img") shouldBe resourceFile.absolutePath
            DefaultWorkerExecutableLocator(spec, workerDir.absolutePath, null, null)
                .locateCompanionFile("uefi-ntfs.img") shouldBe siblingFile.absolutePath
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
