<div align="center">

<img src="mobile/icon.png" width="120" alt="JasonBible Phone">

# JasonBible Phone

**A fully offline Bible reader &amp; note-taking app for Android.**

Files · Editor · Bible, one swipe apart · offline KJV &amp; Luther 1912 · notes that save themselves

[![Latest release](https://img.shields.io/github/v/release/BacroXP/JasonBible?label=release&amp;color=informational)](https://github.com/BacroXP/JasonBible/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](https://opensource.org/licenses/MIT)

**[📥 Install](#-install-on-android)** · **[🚀 Build](#-build-the-apk)** · **[✨ What's inside](#-whats-in-the-app)** · **[🖥 Desktop version](https://github.com/BacroXP/JasonBible)**

</div>

---

## 📥 Install on Android

**Android 8.0 (API 26) or newer.** Works offline — no account and no network permission.
Android only; there is no iOS build.

### From a release

1. Open **[Releases](https://github.com/BacroXP/JasonBible/releases)** on your phone.
2. Download **`JasonBible-phone.apk`**.
3. Tap the file, allow installs from that app when Android asks, then tap **Install**.
4. Open **JasonBible** from the app drawer.

The copy in `dist/JasonBible-phone.apk` is a prebuilt debug-signed APK. Release tags
are signed with the project's release key.

### Over USB with `adb`

```bash
adb install -r dist/JasonBible-phone.apk
adb shell am start -n com.jasonbible.phone/.MainActivity
```

## 📱 Phone layout

```
  swipe  ←──────────────  ──────────────→
  ┌──────────┬──────────┬──────────┐
  │  Files   │  Editor  │  Bible   │
  └──────────┴──────────┴──────────┘
```

- **The editor sits between the files and the Bible.** Swiping right on the
  editor opens Files; swiping left opens the Bible. Swiping back is symmetric.
- **Tabs** (`Files` · `Editor` · `Bible`) in the top bar do the same without
  swiping.
- **Notes never lose a draft.** The editor text lives above the pager, so
  switching pages keeps what you typed, and notes auto-save after a pause.
- **Files** lists every note, creates new ones and deletes them.
- **Bible** has a book picker, chapter chips, verse reading, translation
  switching (KJV / Luther 1912) and previous/next chapter buttons.

## ✨ What's in the app

| Included | |
|---|---|
| 📖 Offline Bible | KJV and Luther 1912 bundled in the APK, book/chapter/verse navigation, translation switch |
| 📝 Notes | File list, create/delete, plain-text editor, auto-save into app-private storage |
| 📱 Phone layout | Files ← Editor → Bible swipe pager, status-bar-safe top bar |
| 🎨 Theme | Dark and light |

| Not yet in the phone app | |
|---|---|
| Strong's word study, interlinear view, full-text search | |
| Rich editor ribbon (headings, colours, lists, media chips) | |
| Reading plan, statistics, collections, PDF export | |
| In-app media player (YouTube / Spotify / local files) | |

## 🚀 Build the APK

Requirements: **JDK 21** and the **Android SDK** (platform 35 + build-tools
35.0.0). The Android project is self-contained under `mobile/`.

```bash
cd mobile

# Once, if the SDK is not in the default location:
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew assembleDebug
```

The APK lands at `mobile/app/build/outputs/apk/debug/app-debug.apk`.

Bundled translations live in `mobile/app/src/main/assets/bible/`; add a module
there and register it in `Bible.kt`.

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
debug key — so a plain local build never breaks.

### 🤖 CI

`.github/workflows/release.yml` is phone-only:

- every push builds the **debug APK** and uploads it as the `android-apk`
  artifact;
- every `v*` tag builds the **release-signed APK** (`JasonBible-phone.apk`),
  verifies the certificate with `apksigner` and **fails if it is
  debug-signed**; the release job checks the APK's SHA-256 and attaches the APK,
  its `.sha256` and a `SHA256SUMS.txt` to the GitHub Release.

To sign releases in CI, add these repository secrets (Settings → Secrets and
variables → Actions):

```
ANDROID_KEYSTORE_BASE64     base64 -w0 of the .keystore file
ANDROID_KEYSTORE_PASSWORD   keystore password
ANDROID_KEY_ALIAS           key alias
ANDROID_KEY_PASSWORD        key password
```

Tag builds require all four secrets; branch pushes never do.

## 🗂 Where things live

| What | Where |
|---|---|
| App sources | `mobile/app/src/main/java/com/jasonbible/phone/` |
| Bundled translations | `mobile/app/src/main/assets/bible/` |
| Prebuilt APK | `dist/JasonBible-phone.apk` |
| Phone notes | app-private `filesDir/notes/*.note` |
| CI | `.github/workflows/release.yml` |

## 🔗 Links

- **⬇️ Releases** — https://github.com/BacroXP/JasonBible/releases
- **🖥 Desktop editions** — https://github.com/BacroXP/JasonBible
- **🌐 Website** — https://bacroxp.github.io/JasonBible/
- **🛠 CI pipeline** — https://github.com/BacroXP/JasonBible/actions
- **🧱 Tech stack** — Kotlin 2.2 · Jetpack Compose (Material 3) · kotlinx-serialization
