package io.github.drlacheheb.kufus.data.repository

import java.io.File

internal fun locateNativeArtifact(
    moduleDir: String,
    targetName: String,
    variants: List<String>,
    artifactNames: List<String>,
    configuredDir: String?,
    appResourcesDir: String?,
    requireExecutable: Boolean,
): String? =
    candidateArtifactFiles(
        moduleDir = moduleDir,
        targetName = targetName,
        variants = variants,
        artifactNames = artifactNames,
        configuredDir = configuredDir,
        appResourcesDir = appResourcesDir,
    ).firstOrNull { file ->
        file.isFile && (!requireExecutable || file.canExecute())
    }?.absolutePath

private fun candidateArtifactFiles(
    moduleDir: String,
    targetName: String,
    variants: List<String>,
    artifactNames: List<String>,
    configuredDir: String?,
    appResourcesDir: String?,
): Sequence<File> {
    val devDirs =
        listOf("", "../").flatMap { prefix ->
            variants.map { variant -> "$prefix$moduleDir/build/bin/$targetName/$variant" }
        }
    val dirs = listOfNotNull(configuredDir, appResourcesDir).filter { it.isNotBlank() } + devDirs
    return dirs
        .asSequence()
        .flatMap { dir ->
            artifactNames.asSequence().map { name -> File(dir, name) }
        }
}
