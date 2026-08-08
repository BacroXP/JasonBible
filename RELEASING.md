# 🚀 Shipping a Release — step-by-step

This is the manual for the person who ships a BibleApp release. It takes
roughly 15–25 minutes of wall-clock time, almost all of it waiting on
GitHub Actions.

## How the pipeline works

The workflow [`.github/workflows/release.yml`](.github/workflows/release.yml)
is fully automated:

| Event | What runs |
|---|---|
| Any push to **any branch** | `test` job (desktop unit tests) |
| Push of a tag `v*` (e.g. `v1.0.0`) | `test` → `build-deb` (Ubuntu) + `build-msi` (Windows) + `build-dmg` (macOS) in parallel → `release` job attaches the installers to the GitHub Release |

The three installers are built on their **native OS** (the Compose plugin
cannot cross-compile MSI/DMG), then one final job uploads them all to the
release created for the tag. Release notes are generated automatically
from the commits since the previous tag.

> Installers are **not committed to the repository** — they only exist as
> release assets (and as local build output under `build/`). The README's
> download links point at the Releases page, not at the repo.

---

## Step 0 — Pre-flight checks (recommended)

Run these locally before tagging anything:

```bash
# 1. All unit tests green (CI runs exactly this)
./gradlew desktopTest

# 2. The Linux package builds
./gradlew packageDeb

# 3. Optional smoke test of the freshly built package
sudo apt install ./build/compose/binaries/main/deb/bibleapp_1.0.0_amd64.deb
```

## Step 1 — Set the version (only when versioning)

The version lives in [`build.gradle.kts`](build.gradle.kts):

```kotlin
packageVersion = "1.0.0"
```

The **git tag must match** (`v` + packageVersion): `1.0.0` → tag `v1.0.0`.

## Step 2 — Commit everything and push

```bash
git add -A
git commit -m "v1.0.0: <short summary of what's in this release>"
git push
```

## Step 3 — Tag the release

```bash
# Tag the exact commit you just pushed, then push the tag:
git tag v1.0.0
git push origin v1.0.0
```

> Tag names are checked with `startsWith('refs/tags/v')`, so any `vX.Y.Z`
> name works. Don't reuse a tag — delete it first (`git tag -d v1.0.0`,
> `git push origin :refs/tags/v1.0.0`) if you need to re-tag.

## Step 4 — Watch the pipeline

1. Open **GitHub → Actions** in the repo and select the **Build & Release**
   workflow run for your tag.
2. Order of jobs: `test` → `build-deb`, `build-msi`, `build-dmg`
   (parallel) → `release`.
3. The first tag run is the slowest (each runner downloads its
   dependencies); expect roughly **10–20 minutes** in total.
4. No manual action is needed — when the last job finishes, the installers
   are already attached to the release.

If a job fails, click it to see the logs. The `test` job gates everything:
a failing test blocks the installer builds by design.

## Step 5 — Publish the release

1. When the run completes, GitHub creates a **draft** release named after
   the tag. Open **GitHub → Releases**.
2. Click **Edit** on the draft:
   - **Title** — e.g. `BibleApp v1.0.0`.
   - **Release notes** — the auto-generated commit list is a good start;
     tidy it into user-facing bullets if you like (see below for a
     template).
   - Tick **Set as the latest release**.
3. Confirm the three assets are listed: `*.deb`, `*.msi`, `*.dmg`.
4. Click **Publish release**.

### Release-notes template

```markdown
## What's new
- <!-- one bullet per user-visible change, e.g. -->
- Global search (Ctrl+F) across notes, the Bible and book names
- Word study with Strong's numbers + interlinear view
- ...

## Installers
- **Linux:** `bibleapp_<version>_amd64.deb` — `sudo apt install ./bibleapp_<version>_amd64.deb`
- **Windows:** `BibleApp-<version>.msi`
- **macOS:** `BibleApp-<version>.dmg`
```

## Step 6 — Verify the shipped installers

- Download the `.deb` from the release and install it on a clean machine:
  `sudo apt install ./BibleApp-<version>.deb`.
- Confirm the app launches and the bundled translations are listed in
  **Settings → Bible preferences**.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Workflow ran, but no installers on the release | The tag wasn't pushed to the *remote*, or the push didn't carry the tag — re-check `git ls-remote --tags origin`. |
| Only the `test` job ran | Branch pushes never build installers — only `v*` **tags** do. |
| `release` job failed with permission error | The job needs `contents: write` on the repo (already configured in the workflow; verify repo settings don't override it). |
| MSI/DMG missing | They can only be built on Windows/macOS runners — check the respective job logs for toolchain issues (WiX download, `hdiutil`). |
| `test` job failed | A unit test is broken — fix and push again before tagging. |
