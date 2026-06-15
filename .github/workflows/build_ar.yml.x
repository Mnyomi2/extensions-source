name: Build AR extensions

# Builds every extension under src/ar as debug APKs and uploads them
# as a downloadable workflow artifact. No signing secrets required.
#
# How to use:
#   1. Push this branch to your GitHub fork.
#   2. Go to the repo's "Actions" tab -> "Build AR extensions" -> "Run workflow".
#      (It also runs automatically on pushes that touch src/ar.)
#   3. When the run finishes, open the run and download the "ar-apks" artifact (a zip of all APKs).

on:
  workflow_dispatch:
  push:
    branches:
      - main
    paths:
      - 'src/ar/**'
      - '.github/workflows/build_ar.yml'

permissions:
  contents: read

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build-ar:
    name: Build src/ar extensions
    runs-on: ubuntu-latest
    timeout-minutes: 60
    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          persist-credentials: false

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: temurin

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Discover AR modules
        id: modules
        run: |
          tasks=""
          for d in src/ar/*/; do
            name="$(basename "$d")"
            if [ -f "${d}build.gradle" ] || [ -f "${d}build.gradle.kts" ]; then
              tasks="$tasks :src:ar:${name}:assembleDebug"
            fi
          done
          echo "Discovered tasks:$tasks"
          echo "tasks=$tasks" >> "$GITHUB_OUTPUT"

      - name: Build AR extensions (assembleDebug)
        run: |
          chmod +x gradlew
          ./gradlew ${{ steps.modules.outputs.tasks }} --stacktrace

      - name: Collect APKs
        run: |
          mkdir -p apks
          find src/ar -path '*/build/outputs/apk/*/*.apk' -exec cp -v {} apks/ \;
          echo "=== Collected APKs ==="
          ls -lh apks/

      - name: Upload APKs
        uses: actions/upload-artifact@v4
        with:
          name: ar-apks
          path: apks/*.apk
          if-no-files-found: error
          retention-days: 7
