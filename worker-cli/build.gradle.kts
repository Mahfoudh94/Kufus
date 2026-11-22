plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    applyDefaultHierarchyTemplate()

    linuxX64().binaries {
        executable {
            baseName = "kufus-worker"
            entryPoint = "io.github.drlacheheb.kufus.worker.main"
            linkerOpts("--as-needed")
        }
    }

    listOf(
        mingwX64(),
        macosArm64(),
    ).forEach { target ->
        target.binaries {
            executable {
                baseName = "kufus-worker"
                entryPoint = "io.github.drlacheheb.kufus.worker.main"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":disk-core"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
