plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":disk-core"))
    implementation(libs.koin.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotest.assertions)
}
