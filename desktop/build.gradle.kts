import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

// Pure-JVM core shared with the Android app (no Android imports). Tested by the app's unit tests.
val sharedCore = listOf(
    "com/lyvt/files/core/common/Formatters.kt",
    "com/lyvt/files/core/network/Nearby.kt",
    "com/lyvt/files/core/common/NaturalOrder.kt",
    "com/lyvt/files/core/common/Parallel.kt",
    "com/lyvt/files/core/storage/FileModels.kt",
    "com/lyvt/files/core/storage/FastFs.kt",
    "com/lyvt/files/core/storage/FileOperations.kt",
    "com/lyvt/files/core/storage/BatchRename.kt",
    "com/lyvt/files/core/storage/FileSearch.kt",
    "com/lyvt/files/core/storage/Hashing.kt",
    "com/lyvt/files/core/scan/DuplicateFinder.kt",
    "com/lyvt/files/core/scan/StorageScanner.kt",
    "com/lyvt/files/core/organizer/OrganizerEngine.kt",
    "com/lyvt/files/core/archive/ArchiveManager.kt",
    "com/lyvt/files/core/security/VaultCipher.kt",
)
val syncShared by tasks.registering(Sync::class) {
    from("../app/src/main/java") { include(sharedCore) }
    into(layout.buildDirectory.dir("shared-src"))
}
sourceSets["main"].kotlin.srcDir(syncShared)

dependencies {
    implementation(compose.desktop.currentOs)
    // Windows natives too, so the uber JAR also runs on Windows when built elsewhere.
    implementation(compose.desktop.windows_x64)
    implementation(compose.materialIconsExtended)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.json:json:20240303")
    implementation(libs.zip4j)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.zxing)
    implementation("org.apache.pdfbox:pdfbox:3.0.5")
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.astrum.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "Astrum Files"
            packageVersion = "1.2.0"
            description = "Astrum Files for Windows"
            vendor = "Astrum"
            includeAllModules = true // bundled runtime: no separate Java install needed
            windows {
                iconFile.set(project.file("src/main/resources/astrum.ico"))
                menu = true; shortcut = true; dirChooser = true; perUserInstall = true
                upgradeUuid = "6d3c0d0e-3b2a-4c7e-9a5f-8a4b6f1e2c11"
            }
            linux { iconFile.set(project.file("src/main/resources/astrum.png")) }
        }
    }
}
