import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":shared"))

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
