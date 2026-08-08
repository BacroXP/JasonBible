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

        // Desktop unit tests — pure logic only (search matching, reading
        // plan, reference-prefix scan, copy formatting). `kotlin("test")`
        // brings the JUnit-backed test runner. `kotlinx-coroutines-test`
        // adds virtual-time `runTest` for the search debounce and other
        // suspend logic. Run with `./gradlew desktopTest`.
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
    }
}


compose.desktop {

    application {

        mainClass = "MainKt"

        // Skiko (Compose's rendering layer) loads its native libraries via
        // java.lang.System::load. On JDK 24+ that is a restricted native
        // access call, so without this flag the JVM prints a warning (and
        // will block it in a future release). Harmless on older JDKs.
        // Applied to both the `run` task and the packaged launchers.
        jvmArgs("--enable-native-access=ALL-UNNAMED")

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