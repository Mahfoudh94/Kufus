import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

data class TargetWorkerConfig(
    val hostModule: String,
    val kotlinNativeTarget: String,
    val linkDebugTask: String,
    val linkReleaseTask: String,
    val linkDebugSharedTask: String,
    val linkReleaseSharedTask: String,
)

val osName = providers.systemProperty("os.name").get().lowercase()
val hostOs =
    when {
        osName.contains("win") -> "windows"
        osName.contains("mac") -> "macos"
        else -> "linux"
    }
val targetOs =
    providers
        .gradleProperty("kufus.targetOs")
        .orElse(hostOs)
        .get()
        .lowercase()

val targetWorker =
    when (targetOs) {
        "linux" ->
            TargetWorkerConfig(
                hostModule = ":host:linux",
                kotlinNativeTarget = "linuxX64",
                linkDebugTask = ":worker-cli:linkDebugExecutableLinuxX64",
                linkReleaseTask = ":worker-cli:linkReleaseExecutableLinuxX64",
                linkDebugSharedTask = ":native-bridge:linkDebugSharedLinuxX64",
                linkReleaseSharedTask = ":native-bridge:linkReleaseSharedLinuxX64",
            )
        "windows" ->
            TargetWorkerConfig(
                hostModule = ":host:windows",
                kotlinNativeTarget = "mingwX64",
                linkDebugTask = ":worker-cli:linkDebugExecutableMingwX64",
                linkReleaseTask = ":worker-cli:linkReleaseExecutableMingwX64",
                linkDebugSharedTask = ":native-bridge:linkDebugSharedMingwX64",
                linkReleaseSharedTask = ":native-bridge:linkReleaseSharedMingwX64",
            )
        "macos" ->
            TargetWorkerConfig(
                hostModule = ":host:macos",
                kotlinNativeTarget = "macosArm64",
                linkDebugTask = ":worker-cli:linkDebugExecutableMacosArm64",
                linkReleaseTask = ":worker-cli:linkReleaseExecutableMacosArm64",
                linkDebugSharedTask = ":native-bridge:linkDebugSharedMacosArm64",
                linkReleaseSharedTask = ":native-bridge:linkReleaseSharedMacosArm64",
            )
        else -> error("Unsupported kufus.targetOs='$targetOs'. Expected linux, windows, or macos.")
    }

kotlin {
    jvmToolchain(21)

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(targetWorker.hostModule))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.decompose.core)
    implementation(libs.decompose.compose)
    implementation(libs.koin.core)

    implementation(libs.compose.components.resources)
    implementation(libs.compose.uiToolingPreview)

    testImplementation(libs.kotlin.test)
}

val appVersion =
    libs.versions.app.version
        .get()
val semver = appVersion.substringBefore("-")
val appResourcesDir = layout.buildDirectory.dir("appResources")
val workerBinRoot = rootProject.layout.projectDirectory.dir("worker-cli/build/bin")
val nativeBinRoot = rootProject.layout.projectDirectory.dir("native-bridge/build/bin")
val workerDebugDir = workerBinRoot.dir("${targetWorker.kotlinNativeTarget}/debugExecutable")
val workerReleaseDir = workerBinRoot.dir("${targetWorker.kotlinNativeTarget}/releaseExecutable")
val nativeDebugDir = nativeBinRoot.dir("${targetWorker.kotlinNativeTarget}/debugShared")
val nativeReleaseDir = nativeBinRoot.dir("${targetWorker.kotlinNativeTarget}/releaseShared")
val linuxPolkitPolicy = rootProject.layout.projectDirectory.file("packaging/linux/io.github.drlacheheb.kufus.policy")
val uefiNtfsAsset = rootProject.layout.projectDirectory.file("packaging/uefi/uefi-ntfs.img")

val prepareWorkerAppResources by tasks.registering(Sync::class) {
    dependsOn(targetWorker.linkReleaseTask, targetWorker.linkReleaseSharedTask)
    from(workerReleaseDir) {
        if (targetOs != "windows") {
            rename("kufus-worker\\.kexe", "kufus-worker")
        }
    }
    from(nativeReleaseDir) {
        exclude("*.h")
    }
    if (targetOs == "linux") {
        from(linuxPolkitPolicy)
    }
    from(uefiNtfsAsset)
    into(appResourcesDir.map { it.dir(targetOs) })
}

tasks.withType<JavaExec>().configureEach {
    dependsOn(targetWorker.linkDebugTask, targetWorker.linkDebugSharedTask)
    systemProperty("kufus.worker.dir", workerDebugDir.asFile.absolutePath)
    systemProperty("kufus.native.dir", nativeDebugDir.asFile.absolutePath)
    systemProperty("kufus.uefiNtfs.path", uefiNtfsAsset.asFile.absolutePath)
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(prepareWorkerAppResources)
}

compose.desktop {
    application {
        mainClass = "io.github.drlacheheb.kufus.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Kufus"
            packageVersion = semver
            description = "Kufus - Cross-Platform Bootable USB Creator & Formatter"
            copyright = "Copyright (c) 2026 Kufus"
            vendor = "io.github.drlacheheb"
            appResourcesRootDir.set(appResourcesDir)

            modules(
                "java.base",
                "java.desktop",
                "jdk.unsupported",
            )

            windows {
                menuGroup = "Kufus"
                shortcut = true
                perUserInstall = true
                upgradeUuid = "9D3E6B4A-4C1F-4F2B-9A82-3D7E6E51A104"
            }
            macOS {
                bundleID = "io.github.drlacheheb.kufus"
                dockName = "Kufus"
            }
            linux {
                packageName = "kufus"
                menuGroup = "Utility;"
                appCategory = "Utility"
                shortcut = true
                iconFile.set(rootProject.layout.projectDirectory.file("packaging/linux/kufus.png"))
            }
        }
    }
}

tasks.withType<AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.AppImage && targetOs != "windows") {
        val appOutDir = destinationDir
        doLast {
            val executablePermissions = PosixFilePermissions.fromString("rwxr-xr-x")
            appOutDir
                .get()
                .asFile
                .walkTopDown()
                .filter { file ->
                    file.isFile &&
                        (file.name == "kufus-worker" || file.name.endsWith(".so") || file.name.endsWith(".dylib"))
                }.forEach { file ->
                    Files.setPosixFilePermissions(file.toPath(), executablePermissions)
                }
        }
    }
}

afterEvaluate {
    val linuxJpackageResourceDir =
        rootProject.layout.projectDirectory
            .dir("packaging/linux/jpackage")
            .asFile
    val linuxIconFile =
        rootProject.layout.projectDirectory
            .file("packaging/linux/kufus.png")
            .asFile
    val composeTmpDir =
        project.layout.buildDirectory
            .dir("compose/tmp")
            .get()
            .asFile

    tasks.withType<AbstractJPackageTask>().configureEach {
        if (targetFormat == TargetFormat.Deb || targetFormat == TargetFormat.Rpm) {
            val distributableName =
                if (name.contains("Release")) {
                    "createReleaseDistributable"
                } else {
                    "createDistributable"
                }
            val distributableTask = tasks.named<AbstractJPackageTask>(distributableName)
            dependsOn(distributableTask)
            appImage.set(distributableTask.flatMap { task -> task.destinationDir.dir(task.packageName) })

            inputs.dir(linuxJpackageResourceDir)
            inputs.file(linuxIconFile)
            val taskName = name
            val formatExtension =
                if (targetFormat == TargetFormat.Rpm) {
                    "rpm"
                } else {
                    "deb"
                }

            doLast {
                val argsFile = composeTmpDir.resolve("$taskName.args.txt")
                if (!argsFile.isFile) {
                    return@doLast
                }

                val customResourceDir = composeTmpDir.resolve("$taskName-custom-resources")
                customResourceDir.deleteRecursively()
                customResourceDir.mkdirs()
                linuxJpackageResourceDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.copyTo(customResourceDir.resolve(file.name), overwrite = true)
                    }
                }
                if (linuxIconFile.isFile) {
                    linuxIconFile.copyTo(customResourceDir.resolve("Kufus.png"), overwrite = true)
                }

                val originalLines = argsFile.readLines()
                val patchedLines = mutableListOf<String>()
                var idx = 0
                while (idx < originalLines.size) {
                    val line = originalLines[idx]
                    if (line == "--resource-dir" && idx + 1 < originalLines.size) {
                        patchedLines += line
                        patchedLines += "\"${customResourceDir.absolutePath}\""
                        idx += 2
                    } else if (line.startsWith("--resource-dir=")) {
                        patchedLines += "--resource-dir=\"${customResourceDir.absolutePath}\""
                        idx += 1
                    } else {
                        patchedLines += line
                        idx += 1
                    }
                }
                val patchedArgsFile = composeTmpDir.resolve("$taskName.custom.args.txt")
                patchedArgsFile.writeText(patchedLines.joinToString("\n"))

                val destDirFile = destinationDir.get().asFile
                destDirFile.listFiles()?.filter { it.extension == formatExtension }?.forEach { file ->
                    file.delete()
                }

                val jpackageBin = File(javaHome.get(), "bin/jpackage")
                val process =
                    ProcessBuilder(
                        jpackageBin.absolutePath,
                        "@${patchedArgsFile.absolutePath}",
                    ).inheritIO().start()
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    error("Custom jpackage execution failed with exit code $exitCode for $taskName")
                }
            }
        }
    }
}

tasks.matching { it.name.startsWith("hotRun") }.configureEach {
    dependsOn(tasks.named("classes"))
    notCompatibleWithConfigurationCache("Compose Hot Reload runs an interactive agent with dynamic classpath mutation")
}
