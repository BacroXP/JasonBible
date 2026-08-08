# 📖 BibleApp

A fast, offline **Bible reading & note-taking desktop app** built with Kotlin and
Compose Multiplatform (Desktop). Read the Luther 1912 translation, take richly
formatted notes, and study with confidence — no internet connection required.

---

## ✨ Features

### 📖 Bible reading
- Complete offline **Luther 1912** translation (all 66 books)
- Book / chapter / verse navigation with a quick-reference selection dialog
- Full-text **search across the whole Bible** (Ctrl+F): results grouped by book, step through matches with the prev/next buttons (or Enter / Shift+Enter), click a match to jump straight to the verse. **Aa** (match case) and **abc** (whole word) toggles, a scope filter (**All / This book / This chapter**), a **recent-queries** dropdown on the search icon (click to re-run), and the last query + toggles are remembered across restarts. Queries shaped like a Strong's number (`G25`, `H1`) run a **reverse concordance** lookup listing every verse containing that word-study token
- **Word study** with Strong's numbers: in the bundled *KJV with Strongs* and *TR Parsed* (parsed Greek NT) translations every word is clickable — tap a `G`/`H` number to see its definition, root, transliteration and pronunciation (offline Strong's concordance). In the Greek module the **TVM codes** are clickable too, revealing each verb's tense / voice / mood breakdown
- **Copy a verse, chapter or verse range** (hover a verse → 📋 Copy; chapter header → 📋 Copy chapter + a More ▾ menu with **Copy verse range…** and **Export chapter as PDF…**): e.g. `John 3:16 — For God so loved…` or a whole chapter as `John 3` with numbered verses. An optional setting appends the translation name (e.g. `… (Luther Bible 1912)`)
- **Interlinear view**: the **ΑΩ** header toggle cycles three modes — off, the matching Greek TR verse (bundled `trparsed` module) beneath each New-Testament verse, and **word-aligned** (ΑΩ≡): Greek tokens paired column-by-column with the English word sharing their Strong's number (needs a Strong's-tagged translation like *KJV with Strongs*). Clickable numbers feed the word-study panel in every mode
- **Ctrl+G jump-to-verse** dialog (book autocomplete + chapter / optional verse), and **← / → back-forward history** in the Bible pane header
- **Whole-book continuous reading**: open a book's chapter list → “Read whole book →” to scroll the entire book as one passage (the interlinear mode carries over, so Greek lines / word-aligned columns and word study stay consistent with the chapter view)
- **365-day reading plan** on Home with today's deterministic chapter assignments, overall progress bar and one-tap “mark today as read”
- **Daily verse** on Home: a date-seeded verse from the active translation (changes each day), with a one-click jump to read it in the Bible
- Verse highlighting with 5 marker colors, plus **tags** per verse
- Read-tracking: mark chapters as read and see your progress
- Star your favourite books and chapters
- Split view: read and take notes side by side

### 📝 Notes & editor
- Word-style **ribbon toolbar** with collapsible **Home / Insert / Layout** tabs
- Custom icon set for every toolbar action
- **Rich note format**: headings (H1/H2), quotes, coloured highlights, bullet &
  numbered lists, bold / italic / underline, and Bible reference placeholders
- **Media references**: `@youtube:dQw4w9WgXcQ`, `@spotify:track:4cOdon…`,
  `@vimeo:123456789`, `@soundcloud:https://…` or `@url:https://…` render as
  clickable chips — tapping one opens an **in-app preview panel** (title +
  thumbnail fetched from the service's public oEmbed endpoint, no API keys)
  with **Open in browser** / **Copy link**, and right-click offers the same
  actions. The Insert ribbon's ▶️ button opens a picker that validates the
  ID / URL before inserting. YouTube ids may carry a `?t=` timestamp
- **Styles dropdown** (Normal / H1 / H2 / Quote) applied to the current line
- Live **zoom slider** in the status bar (75 % – 200 %), persisted across runs
- Undo / redo, find & replace, select all, clipboard integration
- **Global notes search** (Ctrl+Shift+F): full-text scan across every note with previews and click-to-open at the matching line
- **Inline reference autocomplete**: type `$Joh` and matching book names appear as chips — Enter, Tab or a click completes the reference
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

### Download installers

Ready-to-install packages are attached to every
[**Release**](https://github.com/BacroXP/JasonBible/releases) — grab the
`.deb` (Linux), `.msi` (Windows) or `.dmg` (macOS) for your platform there.

The Linux package installs with:

```bash
sudo apt install ./bibleapp_1.0.0_amd64.deb
```

(Pre-built installers are intentionally not committed to the repository —
they are too large for GitHub's 100 MB per-file limit and would bloat every
clone. Build your own with `./gradlew packageDeb` instead, see below.)

### Installers for all platforms

Every **release** on the [Releases](https://github.com/BacroXP/JasonBible/releases) page ships an installer
for each platform, built automatically by GitHub Actions on its native OS:

| Platform | File | Built on |
|----------|------|----------|
| Linux | `.deb` | Ubuntu runner |
| Windows | `.msi` | Windows runner (WiX) |
| macOS | `.dmg` | macOS runner |

Each format is only buildable on its own OS (the Compose Gradle plugin can't
cross-compile MSI / DMG), so the CI workflow builds all three in parallel and
attaches them to the release.

Shipping a new version? Follow the step-by-step tutorial:
**[RELEASING.md](RELEASING.md)** — commit, tag `vX.Y.Z`, and the pipeline
builds & attaches the installers automatically.

### Build a release locally

```bash
# Linux .deb
./gradlew packageDeb
# macOS .dmg (requires macOS)
./gradlew packageDmg
# Windows .msi (requires Windows)
./gradlew packageMsi
```

The `.deb` is written to `build/compose/binaries/main/deb/`.

### Run the tests

```bash
./gradlew desktopTest
```

Unit tests cover the pure logic: Bible search matching (whole-word, case,
Strong's reverse concordance, the book/chapter scope slices), the 365-day
reading plan (determinism, full canon coverage, date→day mapping), the
editor's `$Book` reference-prefix scan, and the copy-range formatter.
Tests redirect `user.home` to a throwaway directory, so your real
`~/.bibleapp` settings are never read or written.

---

## 🗂 Data

- Settings: `~/.bibleapp/private.json`
- Notes: `~/.bibleapp/notes/*.note`
- Bibles: `src/desktopMain/resources/bible/<Sprache>-<Name>/*.json` im SWORD-Format
  (`{"metadata":…, "verses":[…]}`). Alle vorhandenen Module werden beim Start
  automatisch erkannt und lassen sich in den **Einstellungen → Bible preferences**
  (Sprache + Übersetzung) umschalten. Neue Module einfach in den Ordner legen.
  Referenzen in Notizen (`$Lukas$3$16`) bleiben dank der sprachübergreifenden
  Buchnamen-Zuordnung (`Extras/books_*.json`) auch nach einem Sprachwechsel gültig.

---

## 🛠 Tech

- [Kotlin](https://kotlinlang.org) 2.2
- [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) 1.9 (Material 3)
- kotlinx-serialization, kotlinx-coroutines
- Apache PDFBox (note → PDF export)

## 📄 License

MIT
