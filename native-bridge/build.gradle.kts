import java.io.File

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

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

val jdkHomeDir: File =
    providers
        .systemProperty("java.home")
        .map { path ->
            val dir = File(path)
            if (dir.resolve("include").isDirectory) dir else dir.parentFile
        }.get()

kotlin {
    applyDefaultHierarchyTemplate()

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    val (nativeTarget, jniOsSubdir) =
        when (targetOs) {
            "windows" -> mingwX64() to "win32"
            "macos" -> macosArm64() to "darwin"
            else -> linuxX64() to "linux"
        }

    val mainCompilation = nativeTarget.compilations.getByName("main")
    mainCompilation.cinterops.create("jni") {
        definitionFile.set(project.file("src/nativeInterop/cinterop/jni.def"))
        includeDirs(
            jdkHomeDir.resolve("include"),
            jdkHomeDir.resolve("include/$jniOsSubdir"),
        )
    }
    nativeTarget.binaries {
        sharedLib {
            baseName = "kufusnative"
            if (targetOs == "linux") {
                linkerOpts("--as-needed")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":disk-core"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
