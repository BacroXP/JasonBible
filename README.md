# 📖 BibleApp

A fast, offline **Bible reading & note-taking desktop app** built with Kotlin and
Compose Multiplatform (Desktop). Read the Luther 1912 translation, take richly
formatted notes, and study with confidence — no internet connection required.

---

## ✨ Features

### 📖 Bible reading
- Complete offline **Luther 1912** translation (all 66 books)
- Book / chapter / verse navigation with a quick-reference selection dialog
- Verse highlighting with 5 marker colors, plus **tags** per verse
- Read-tracking: mark chapters as read and see your progress
- Star your favourite books and chapters
- Split view: read and take notes side by side

### 📝 Notes & editor
- Word-style **ribbon toolbar** with collapsible **Home / Insert / Layout** tabs
- Custom icon set for every toolbar action
- **Rich note format**: headings (H1/H2), quotes, coloured highlights, bullet &
  numbered lists, bold / italic / underline, and Bible reference placeholders
- **Styles dropdown** (Normal / H1 / H2 / Quote) applied to the current line
- Live **zoom slider** in the status bar (75 % – 200 %), persisted across runs
- Undo / redo, find & replace, select all, clipboard integration
- Auto-save to disk, plus **Ctrl+S** to save manually
- Notes are plain `.note` files in `~/.bibleapp/notes` — the sidebar watches the
  folder, so adding/removing files shows up instantly
- Create, delete (with confirmation) and export notes as **PDF**

### ⚙️ App
- Dark / light theme, fullscreen & window settings
- Adjustable split ratio and pane widths
- Sound effects (toggleable)
- German / English UI

---

## 🚀 Getting started

### Requirements
- **Java 17+** (JDK)

### Run from source

```bash
git clone https://github.com/BacroXP/JasonBible.git
cd JasonBible
./gradlew run
```

### Download the .deb

A ready-to-install package lives right in this repository:

[⬇️ Download `bibleapp_1.0.0_amd64.deb`](bibleapp_1.0.0_amd64.deb)

Install it with:

```bash
sudo apt install ./bibleapp_1.0.0_amd64.deb
```

### Build a release

```bash
# Linux .deb
./gradlew packageDeb
# macOS .dmg
./gradlew packageDmg
# Windows .msi
./gradlew packageMsi
```

The `.deb` is written to `build/compose/binaries/main/deb/`.

Or grab the latest build from the **Releases** page.

---

## 🗂 Data

- Settings: `~/.bibleapp/private.json`
- Notes: `~/.bibleapp/notes/*.note`

---

## 🛠 Tech

- [Kotlin](https://kotlinlang.org) 2.2
- [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) 1.9 (Material 3)
- kotlinx-serialization, kotlinx-coroutines
- Apache PDFBox (note → PDF export)

## 📄 License

MIT
