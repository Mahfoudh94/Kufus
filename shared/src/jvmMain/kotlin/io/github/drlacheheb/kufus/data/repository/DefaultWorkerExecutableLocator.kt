package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.host.WorkerBinarySpec
import java.io.File

class DefaultWorkerExecutableLocator(
    private val workerBinarySpec: WorkerBinarySpec,
    private val configuredWorkerDir: String? =
        System.getProperty("kufus.worker.dir") ?: System.getenv("KUFUS_WORKER_DIR"),
    private val appResourcesDir: String? = System.getProperty("compose.application.resources.dir"),
    private val explicitUefiNtfsPath: String? =
        System.getProperty("kufus.uefiNtfs.path") ?: System.getenv("KUFUS_UEFI_NTFS_PATH"),
    private val workingDir: File = File("."),
) : WorkerExecutableLocator {
    override fun locateWorkerPath(): String? {
        val devDirs =
            listOf("", "../").flatMap { prefix ->
                listOf("releaseExecutable", "debugExecutable").map { variant ->
                    "${prefix}worker-cli/build/bin/${workerBinarySpec.targetName}/$variant"
                }
            }
        val searchDirs = listOfNotNull(configuredWorkerDir, appResourcesDir).filter { it.isNotBlank() } + devDirs
        return searchDirs
            .asSequence()
            .flatMap { dir ->
                workerBinarySpec.executableNames.asSequence().map { name -> File(dir, name) }
            }.firstOrNull { file -> file.isFile && file.canExecute() }
            ?.absolutePath
    }

    override fun locateCompanionFile(fileName: String): String? {
        if (fileName.isBlank()) return null
        val candidates =
            buildList {
                if (fileName == "uefi-ntfs.img" && !explicitUefiNtfsPath.isNullOrBlank()) {
                    add(File(explicitUefiNtfsPath))
                }
                if (!appResourcesDir.isNullOrBlank()) {
                    add(File(appResourcesDir, fileName))
                }
                locateWorkerPath()
                    ?.let(::File)
                    ?.parentFile
                    ?.let { workerDir -> add(File(workerDir, fileName)) }
                if (!configuredWorkerDir.isNullOrBlank()) {
                    add(File(configuredWorkerDir, fileName))
                }
                add(File(workingDir, "packaging/uefi/$fileName"))
                add(File(workingDir, "../packaging/uefi/$fileName"))
            }
        return candidates.firstOrNull { it.isFile }?.absolutePath
    }
}
