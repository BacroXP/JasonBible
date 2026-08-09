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

        sourceSets.getByName("desktopMain") {

            // In-app media player: an embedded JavaFX WebView hosts each
            // service's official embed (YouTube iframe / Vimeo player /
            // SoundCloud widget / Spotify embed) in a small always-on-top
            // window, with play-progress reported back to the media cards.
            // The per-OS classifier jars carry the native libraries, so
            // every installer bundles exactly its own platform's binaries;
            // the classifier is derived from the BUILD host's OS. Version
            // 26 is required on modern Linux distros (ffmpeg 8.0 ships
            // libavcodec 62): JavaFX 25.x only bundles codec plugins up to
            // libavcodec 61, so on such systems native playback fails with
            // "ERROR_MEDIA_AUDIO_FORMAT_UNSUPPORTED" and only WAV plays.
            // JavaFX 26.0.2 adds the libavcodec-62 plugin and runs on the
            // JDK 25 runtime this project targets.
            val javafxVersion = "26.0.2"
            val fxClassifier = when {
                System.getProperty("os.name").lowercase().contains("mac") -> "mac"
                System.getProperty("os.name").lowercase().contains("win") -> "win"
                else -> "linux"
            }

            dependencies {

                // Compose
                implementation(compose.desktop.currentOs)

                // Material 3
                implementation("org.jetbrains.compose.material3:material3:1.9.0")

                // JavaFX (embedded media player) — plain jars for the
                // classes, classifier jars for the native libs.
                implementation("org.openjfx:javafx-base:$javafxVersion")
                implementation("org.openjfx:javafx-base:$javafxVersion:$fxClassifier")
                implementation("org.openjfx:javafx-graphics:$javafxVersion")
                implementation("org.openjfx:javafx-graphics:$javafxVersion:$fxClassifier")
                implementation("org.openjfx:javafx-controls:$javafxVersion")
                implementation("org.openjfx:javafx-controls:$javafxVersion:$fxClassifier")
                implementation("org.openjfx:javafx-media:$javafxVersion")
                implementation("org.openjfx:javafx-media:$javafxVersion:$fxClassifier")
                implementation("org.openjfx:javafx-web:$javafxVersion")
                implementation("org.openjfx:javafx-web:$javafxVersion:$fxClassifier")
                // JavaFX↔Swing bridge: hosts the in-app media player (the
                // native MediaView for video services and the WebView for
                // Spotify's embed) inside the main Compose window via a
                // JFXPanel wrapped in Compose's SwingPanel — so playback
                // happens IN the app instead of a separate OS window.
                implementation("org.openjfx:javafx-swing:$javafxVersion")
                implementation("org.openjfx:javafx-swing:$javafxVersion:$fxClassifier")

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
        sourceSets.getByName("desktopTest") {
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

            // JavaFX WebView (the in-app media player) is NOT fully
            // detected by the plugin's automatic jlink module analysis:
            // WebKit pulls in java.net.http and other JDK modules
            // dynamically at runtime. Without them the packaged app fails
            // with a NoClassDefFoundError (network stack) or a native
            // SIGSEGV inside libjfxwebkit's twkExecuteScript (missing
            // class during JNI GetMethodID) — media then never plays and
            // the JavaFX Application Thread dies. `modules` ADDS to the
            // auto-computed jlink list (see Compose docs); this is the
            // module set JavaFX WebView needs on top of what the plugin
            // already detects (java.desktop, java.logging, java.xml, …).
            modules(
                "java.net.http",
                "java.management",
                "java.naming",
                "java.scripting",
                "java.security.jgss",
                "java.sql",
                "jdk.jsobject",
                "jdk.unsupported",
                // JFXPanel (javafx.swing — the SwingPanel bridge that hosts
                // the in-app media player) needs the Swing-interop classes;
                // since JDK 22 these ship in jdk.unsupported.desktop (the
                // old jdk.swing.interop module no longer exists). Without it
                // the packaged app spams NoClassDefFoundError:
                // jdk.swing.interop.SwingInterOpUtils on the FX thread.
                "jdk.unsupported.desktop"
            )

            targetFormats(
                TargetFormat.Deb,
                TargetFormat.Msi,
                TargetFormat.Dmg
            )

            packageName = "BibleApp"
            packageVersion = "2.0.0"

            // Application icons, one per platform. Kept in
            // src/desktopMain/resources/icons/ and referenced explicitly
            // so the .deb / .msi / .dmg installers all carry the icon.
            linux.iconFile.set(project.file("src/desktopMain/resources/icons/Icon.png"))
            windows.iconFile.set(project.file("src/desktopMain/resources/icons/icon.ico"))
            macOS.iconFile.set(project.file("src/desktopMain/resources/icons/icon.icns"))
        }
    }
}

// ---------------------------------------------------------------------------
// File-size guard: blocks local builds when any project file exceeds the
// configured hard limit, mirroring the CI `check-file-sizes` job in
// .github/workflows/release.yml. This keeps a local build from ever
// succeeding on a tree that GitHub would reject (or that would bloat the
// installers).
//
// Note: the local limit (50 MB) is deliberately stricter than the CI hard
// limit (100 MB) — see the CI job for its own 100/50 MB thresholds. Both
// are overridable, e.g.:
//   ./gradlew build -PfileSizeHardLimitMb=100
// ---------------------------------------------------------------------------
val fileSizeHardLimitMb: Int =
    (findProperty("fileSizeHardLimitMb") as String?)?.toIntOrNull() ?: 50
// Warn at 80% of the hard limit (40 MB by default) — high enough that the
// existing ~30 MB translation files don't warn on every build.
val fileSizeWarnLimitMb: Int =
    (findProperty("fileSizeWarnLimitMb") as String?)?.toIntOrNull()
        ?: ((fileSizeHardLimitMb * 4) / 5)

fun formatFileSize(bytes: Long): String =
    "%.1f MB".format(bytes / (1024.0 * 1024.0))

val checkFileSizes = tasks.register("checkFileSizes") {
    group = "verification"
    description =
        "Fails the build if any project file exceeds the $fileSizeHardLimitMb MB size limit."

    doLast {
        val hardLimit = fileSizeHardLimitMb * 1024L * 1024L
        val warnLimit = fileSizeWarnLimitMb * 1024L * 1024L
        val offenders = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        project.fileTree(projectDir) {
            exclude(
                "**/.git/**",
                "**/.gradle/**",
                "**/.idea/**",
                "**/.kotlin/**",
                "**/build/**"
            )
        }.files.sortedByDescending { it.length() }.forEach { file ->
            val size = file.length()
            if (size > hardLimit) {
                offenders += "${file.relativeTo(projectDir)} (${formatFileSize(size)})"
            } else if (size > warnLimit) {
                warnings += "${file.relativeTo(projectDir)} (${formatFileSize(size)})"
            }
        }

        warnings.forEach { file ->
            logger.warn("File-size guard: $file is approaching the $fileSizeWarnLimitMb MB warning level.")
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Build blocked: file(s) exceed the $fileSizeHardLimitMb MB limit:\n" +
                    offenders.joinToString("\n") +
                    "\nMove them out of the repo (e.g. attach them to a GitHub Release instead) and rebuild."
            )
        }
    }
}

// The guard runs before anything that produces a distributable artifact
// (and before `build`), so an oversized file stops the build early. The
// matching is tolerant of tasks that don't exist on this host OS.
tasks.matching {
    it.name in setOf(
        "build",
        "createDistributable",
        "createReleaseDistributable",
        "packageDeb",
        "packageMsi",
        "packageDmg"
    )
}.configureEach { dependsOn(checkFileSizes) }