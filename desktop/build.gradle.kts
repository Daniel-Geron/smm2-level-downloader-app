import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
}

val appVersion = "1.0.0"

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The level archive's file readers and the course crypto are plain Kotlin, so they're shared
// with the Android app instead of copied.
val sharedArchive = "../android/app/src/main/java/io/github/daniel_geron/smm2injector/archive"
sourceSets.main {
    kotlin.srcDir(sharedArchive)
    kotlin.exclude("LevelArchive.kt", "ArchiveService.kt")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.json:json:20240303")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
}

// Version info for the About text and the user agent.
val generateVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    inputs.property("version", appVersion)
    outputs.dir(out)
    doLast {
        val f = out.get().file("io/github/daniel_geron/smm2injector/AppVersion.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package io.github.daniel_geron.smm2injector\n\nconst val APP_VERSION = \"$appVersion\"\n")
    }
}
sourceSets.main { kotlin.srcDir(generateVersion) }

compose.desktop {
    application {
        mainClass = "io.github.daniel_geron.smm2injector.MainKt"
        // The bundled Java runtime comes from this JDK. Releases use an OpenJDK build (Eclipse Temurin 21):
        // ./gradlew packageReleaseMsi -PpackagingJdk=<path to the JDK>
        (findProperty("packagingJdk") as String?)?.let { javaHome = it }
        // sqlite-jdbc loads its native library.
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "SMM2 Level Downloader"
            packageVersion = appVersion
            description = "Download Super Mario Maker 2 levels and inject them into Eden or Ryujinx"
            vendor = "Daniel Geron"
            modules("java.instrument", "java.sql", "jdk.unsupported", "jdk.crypto.ec", "jdk.localedata")
            windows {
                iconFile.set(project.file("icon/icon.ico"))
                menuGroup = "SMM2 Level Downloader"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "6a0f8b0e-6a51-4d7b-9d36-2a3e8f0c2b17"
            }
            linux { iconFile.set(project.file("icon/icon.png")) }
        }
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

