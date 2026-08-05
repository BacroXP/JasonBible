import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "2.2.0"
    id("org.jetbrains.compose") version "1.9.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {

    jvm("desktop")

    sourceSets {

        val desktopMain by getting {

            dependencies {

                // Compose
                implementation(compose.desktop.currentOs)

                // Material 3
                implementation("org.jetbrains.compose.material3:material3:1.9.0")

                // JSON
                implementation(
                    "org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0"
                )

                // Coroutines
                implementation(
                    "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"
                )

                // PDF generation (for note export)
                implementation("org.apache.pdfbox:pdfbox:3.0.4")
            }
        }
    }
}


compose.desktop {

    application {

        mainClass = "MainKt"

        nativeDistributions {

            targetFormats(
                TargetFormat.Deb,
                TargetFormat.Msi,
                TargetFormat.Dmg
            )

            packageName = "BibleApp"
            packageVersion = "1.0.0"

            // Application icons, one per platform. Kept in
            // src/desktopMain/resources/icons/ and referenced explicitly
            // so the .deb / .msi / .dmg installers all carry the icon.
            linux.iconFile.set(project.file("src/desktopMain/resources/icons/Icon.png"))
            windows.iconFile.set(project.file("src/desktopMain/resources/icons/icon.ico"))
            macOS.iconFile.set(project.file("src/desktopMain/resources/icons/icon.icns"))
        }
    }
}