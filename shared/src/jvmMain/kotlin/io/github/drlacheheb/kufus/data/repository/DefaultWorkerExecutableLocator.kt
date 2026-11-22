package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.host.WorkerBinarySpec
import java.io.File

class DefaultWorkerExecutableLocator(
    private val workerBinarySpec: WorkerBinarySpec,
    private val configuredWorkerDir: String? = System.getProperty("kufus.worker.dir"),
    private val appResourcesDir: String? = System.getProperty("compose.application.resources.dir"),
) : WorkerExecutableLocator {
    override fun locateWorkerPath(): String? = candidateFiles().firstOrNull { it.isFile && it.canExecute() }?.absolutePath

    private fun candidateFiles(): Sequence<File> =
        candidateDirectories()
            .asSequence()
            .flatMap { dir ->
                workerBinarySpec.executableNames.asSequence().map { name -> File(dir, name) }
            }

    private fun candidateDirectories(): List<String> {
        val devDirs =
            listOf("", "../").flatMap { prefix ->
                listOf("releaseExecutable", "debugExecutable").map { variant ->
                    "${prefix}worker-cli/build/bin/${workerBinarySpec.targetName}/$variant"
                }
            }
        return listOfNotNull(configuredWorkerDir, appResourcesDir).filter { it.isNotBlank() } + devDirs
    }
}
