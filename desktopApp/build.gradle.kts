import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
            )
        "windows" ->
            TargetWorkerConfig(
                hostModule = ":host:windows",
                kotlinNativeTarget = "mingwX64",
                linkDebugTask = ":worker-cli:linkDebugExecutableMingwX64",
                linkReleaseTask = ":worker-cli:linkReleaseExecutableMingwX64",
            )
        "macos" ->
            TargetWorkerConfig(
                hostModule = ":host:macos",
                kotlinNativeTarget = "macosArm64",
                linkDebugTask = ":worker-cli:linkDebugExecutableMacosArm64",
                linkReleaseTask = ":worker-cli:linkReleaseExecutableMacosArm64",
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
}

val appVersion =
    libs.versions.app.version
        .get()
val semver = appVersion.substringBefore("-")
val appResourcesDir = layout.buildDirectory.dir("appResources")
val workerBinRoot = rootProject.layout.projectDirectory.dir("worker-cli/build/bin")
val workerDebugDir = workerBinRoot.dir("${targetWorker.kotlinNativeTarget}/debugExecutable")
val workerReleaseDir = workerBinRoot.dir("${targetWorker.kotlinNativeTarget}/releaseExecutable")

val prepareWorkerAppResources by tasks.registering(Sync::class) {
    dependsOn(targetWorker.linkReleaseTask)
    from(workerReleaseDir)
    into(appResourcesDir.map { it.dir(targetOs) })
}

tasks.withType<JavaExec>().configureEach {
    dependsOn(targetWorker.linkDebugTask)
    systemProperty("kufus.worker.dir", workerDebugDir.asFile.absolutePath)
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(prepareWorkerAppResources)
}

compose.desktop {
    application {
        mainClass = "io.github.drlacheheb.kufus.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
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
                shortcut = true
            }
        }
    }
}

tasks.matching { it.name.startsWith("hotRun") }.configureEach {
    dependsOn(tasks.named("classes"))
    notCompatibleWithConfigurationCache("Compose Hot Reload runs an interactive agent with dynamic classpath mutation")
}
