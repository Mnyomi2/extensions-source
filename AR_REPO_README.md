# AR extensions — self-hosted Mihon/Tachiyomi repo

This fork is trimmed to the **`ar`**, **`en`**, and **`all`** source directories and ships a
self-contained GitHub Actions pipeline that builds the Arabic (`src/ar`) extensions and
publishes them as an installable extension repository.

## What gets built

- **`.github/workflows/build_ar_repo.yml`** — builds every `src/ar/*` extension as a signed
  release APK, runs the keiyoushi Inspector, generates `index.min.json` + `index.json` + icons,
  and pushes them to the **`repo`** branch of this repository.
  - No Personal Access Token and no repository secrets are required: it generates a signing key
    on the fly and pushes with the built-in `GITHUB_TOKEN`.
- **`.github/workflows/build_ar.yml`** — simpler variant: builds all `src/ar` APKs and uploads
  them as a downloadable workflow artifact (`ar-apks`). Useful if you just want the APK files.

## How to run

1. Push this repository to your GitHub account (see below).
2. Open the **Actions** tab. If prompted, enable workflows for the repo.
3. Run **"Build AR repo"** → **Run workflow** (or just push to `main`).
4. When it finishes, the APKs + index live on the **`repo`** branch.

## Add it to your app

In Mihon/Tachiyomi (or a fork that supports custom repos), add this URL as an extension repo:

```
https://raw.githubusercontent.com/<OWNER>/<REPO>/repo/index.min.json
```

Replace `<OWNER>/<REPO>` with your account/repository, e.g. `Mnyomi2/extensions-source`.

## Notes

- These APKs are **self-signed** by the CI-generated key. They are independent from the official
  keiyoushi signature, so do not mix-and-match updates from both sources for the same extension.
- The actual extension code requires `com.github.keiyoushi:extensions-lib`, which is only on
  jitpack.io. GitHub's runners can reach it, so the build works in CI.
