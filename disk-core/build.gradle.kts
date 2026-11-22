plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    applyDefaultHierarchyTemplate()

    jvm()
    linuxX64()
    mingwX64()
    macosArm64()

    sourceSets {
        val nativeMain by getting
        val unixMain by creating {
            dependsOn(nativeMain)
        }
        val linuxMain by getting {
            dependsOn(unixMain)
        }
        val macosMain by getting {
            dependsOn(unixMain)
        }

        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotest.assertions)
        }
    }
}
