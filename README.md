<div align="center">

<img src="src/desktopMain/resources/icons/Icon-512.png" width="110" alt="BibleApp icon"/>

# JasonBible — Phone

**A fully offline Bible reader & note-taking app for Android.**

Files · Editor · Bible, one swipe apart · offline translations · notes that save themselves

[![Latest release](https://img.shields.io/github/v/release/BacroXP/JasonBible?label=release&color=informational)](https://github.com/BacroXP/JasonBible/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](https://opensource.org/licenses/MIT)

**[📱 Phone layout](#-phone-layout)** · **[⬇️ Install on Android](#-install-on-android)** · **[🍎 iPhone](#-iphone)** · **[🚀 Build](#-build-the-apk)** · **[✨ What's in the app](#-whats-in-the-phone-app)**

</div>

---

## 📱 Phone layout

The phone layout is a horizontal strip of three pages:

```
  swipe  ←──────────────  ──────────────→
  ┌──────────┬──────────┬──────────┐
  │  Files   │  Editor  │  Bible   │
  └──────────┴──────────┴──────────┘
```

- **The editor sits between the files and the Bible.** Swiping **right on the
  editor** opens the files (there is no page further left), swiping **left**
  moves to the Bible. Swiping back is symmetric.
- **Tabs** (`Files` · `Editor` · `Bible`) in the top bar do the same without
  swiping.
- **Notes never lose a draft.** The editor text lives above the pager, so
  switching to the Bible and back keeps what you typed; notes auto-save after
  a short pause.
- **Files page** lists every note, creates new ones, and deletes them.
- **Bible page** has a book picker, chapter chips, verse reading, translation
  switching (KJV / Luther 1912) and prev/next chapter buttons.

The same layout exists in the desktop build of this branch (`navigation/PhoneShell.kt`)
for previewing on a computer.

## ⬇️ Install on Android

Minimum: **Android 8.0** (API 26). Works offline; no account and no network
permission.

### Option 1 — copy the APK to your phone

1. Download **`dist/JasonBible-phone.apk`** from this repository (or from the
   release page) onto your phone — USB, cloud drive or email.
2. Tap the file in your file manager / Downloads.
3. Android asks to allow installing unknown apps for that app (Files, Chrome,
   …): allow it, then tap **Install**.
4. Open **JasonBible** from the app drawer.

### Option 2 — install over USB with `adb`

```bash
adb install -r dist/JasonBible-phone.apk
adb shell am start -n com.jasonbible.phone/.MainActivity
```

The APK is a **debug-signed** build, so Android may show the usual
"unknown developer" prompt; that is expected for a self-built app.

## 🍎 iPhone

There is **no iPhone build**. An iOS app needs a Kotlin/Native + Compose
Multiplatform iOS target compiled with Xcode on macOS, plus an Apple
developer account for signing — none of which exists on this branch or can
be produced from Linux. Android is the supported phone platform here.

## 🚀 Build the APK

Requirements: **JDK 21** and the **Android SDK** (platform 35 + build-tools
35.0.0). The mobile build is a standalone Gradle project under `mobile/`.

```bash
cd mobile

# once, if the SDK is not in the default location:
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew assembleDebug
```

The APK lands at `mobile/app/build/outputs/apk/debug/app-debug.apk`.
The build from this branch is included at `dist/JasonBible-phone.apk` for
download.

Bundled translations live in `mobile/app/src/main/assets/bible/` (copy any
SWORD-format module from `src/desktopMain/resources/bible/` there and add it
to `TRANSLATIONS` in `Bible.kt`).

### 🔐 Release signing

Release builds read the keystore from **`mobile/keystore.properties`** (never
committed — it is in `.gitignore`) or from env vars, which CI uses:

| Property / env var | Meaning |
|---|---|
| `storeFile` / `ANDROID_KEYSTORE_FILE` | path to the `.keystore` / `.jks` |
| `storePassword` / `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `keyAlias` / `ANDROID_KEY_ALIAS` | signing key alias |
| `keyPassword` / `ANDROID_KEY_PASSWORD` | key password |

Create a keystore and build a signed APK locally:

```bash
keytool -genkeypair -v -keystore mobile/release.keystore -alias jasonbible \
  -keyalg RSA -keysize 2048 -validity 10000

cat > mobile/keystore.properties <<'EOF'
storeFile=release.keystore
storePassword=YOUR_STORE_PASSWORD
keyAlias=jasonbible
keyPassword=YOUR_KEY_PASSWORD
EOF

cd mobile && ./gradlew assembleRelease
```

Without a keystore, `assembleRelease` still builds — signed with the Android
debug key — so a plain local build never breaks. CI prints a warning in that
case.

### 🤖 CI

`.github/workflows/release.yml` has a **build-android** job:

- every push to any branch builds the **debug APK** and uploads it as the
  `android-apk` artifact (so the mobile project can't rot);
- every `v*` tag builds the **release-signed APK** (`JasonBible-phone.apk`),
  verifies the signing certificate with `apksigner` and **fails the job if it
  is debug-signed**; the existing `release` job verifies the APK's SHA-256
  and attaches the APK plus its `.sha256` (and a `SHA256SUMS.txt` covering
  every release asset) to the GitHub Release next to the desktop installers.

To sign releases in CI, add these repository secrets (Settings → Secrets and
variables → Actions):

```
ANDROID_KEYSTORE_BASE64     base64 -w0 of the .keystore file
ANDROID_KEYSTORE_PASSWORD   keystore password
ANDROID_KEY_ALIAS           key alias
ANDROID_KEY_PASSWORD        key password
```

Tag builds **require** all four secrets; without them `build-android` fails
with a clear `::error::` rather than publishing a debug-signed APK. Branch
pushes never need secrets — they build the debug APK artifact.

Download the APK from the release and check it:

```bash
sha256sum -c JasonBible-phone.apk.sha256      # or: grep JasonBible-phone.apk SHA256SUMS.txt
```

### Run the desktop phone layout

Requires **Java 17+**:

```bash
./gradlew run
```

The window opens phone-sized with the editor | Bible swipe layout.

## ✨ What's in the phone app

| Included | |
|---|---|
| 📖 Offline Bible | KJV and Luther 1912 bundled in the APK, book/chapter/verse navigation, translation switch |
| 📝 Notes | File list, create/delete, plain-text editor, auto-save into app-private storage |
| 📱 Phone layout | Files ← Editor → Bible swipe pager, status-bar-safe top bar, dark/light theme |

| Not yet ported from the desktop app | |
|---|---|
| Strong's word study, interlinear view, full-text search | |
| Rich editor ribbon (headings, colours, lists, media chips) | |
| Reading plan, statistics, collections, PDF export | |
| In-app media player (YouTube / Spotify / local files) | |

The desktop app in `src/desktopMain` is unchanged and still builds/tests
with `./gradlew desktopTest`.

## 🗂 Where things live

| What | Where |
|------|-------|
| Phone app sources | `mobile/app/src/main/java/com/jasonbible/phone/` |
| Phone APK | `dist/JasonBible-phone.apk` |
| Phone notes | app-private `filesDir/notes/*.note` |
| Desktop phone shell | `src/desktopMain/kotlin/navigation/PhoneShell.kt` |
| Bundled translations | `mobile/app/src/main/assets/bible/` |

## 🔗 Links

- **⬇️ Releases** — https://github.com/BacroXP/JasonBible/releases
- **🛠 CI pipeline** — https://github.com/BacroXP/JasonBible/actions
- **🧱 Tech stack** — Kotlin 2.2 · Jetpack Compose (Material 3) · kotlinx-serialization
