package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.host.WorkerBinarySpec

class DefaultWorkerExecutableLocator(
    private val workerBinarySpec: WorkerBinarySpec,
    private val configuredWorkerDir: String? = System.getProperty("kufus.worker.dir"),
    private val appResourcesDir: String? = System.getProperty("compose.application.resources.dir"),
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
}
