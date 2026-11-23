package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.host.WorkerBinarySpec
import java.io.File

class DefaultWorkerExecutableLocator(
    private val workerBinarySpec: WorkerBinarySpec,
    private val configuredWorkerDir: String? = System.getProperty("kufus.worker.dir"),
    private val appResourcesDir: String? = System.getProperty("compose.application.resources.dir"),
    private val explicitUefiNtfsPath: String? = System.getProperty("kufus.uefiNtfs.path"),
    private val workingDir: File = File("."),
) : WorkerExecutableLocator {
    override fun locateWorkerPath(): String? =
        locateNativeArtifact(
            moduleDir = "worker-cli",
            targetName = workerBinarySpec.targetName,
            variants = listOf("releaseExecutable", "debugExecutable"),
            artifactNames = workerBinarySpec.executableNames,
            configuredDir = configuredWorkerDir,
            appResourcesDir = appResourcesDir,
            requireExecutable = true,
        )

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
