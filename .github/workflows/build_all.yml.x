name: Build ALL extensions

# Builds every extension under src/all as debug APKs and uploads them
# as a downloadable workflow artifact. No signing secrets required.
#
# How to use:
#   1. Push this branch to your GitHub fork.
#   2. Go to the repo's "Actions" tab -> "Build ALL extensions" -> "Run workflow".
#      (It also runs automatically on pushes that touch src/all.)
#   3. When the run finishes, open the run and download the "all-apks" artifact (a zip of all APKs).

on:
  workflow_dispatch:
  push:
    branches:
      - main
    paths:
      - 'src/all/**'
      - '.github/workflows/build_all.yml'

permissions:
  contents: read

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build-all:
    name: Build src/all extensions
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

      - name: Discover ALL modules
        id: modules
        run: |
          tasks=""
          for d in src/all/*/; do
            name="$(basename "$d")"
            if [ -f "${d}build.gradle" ] || [ -f "${d}build.gradle.kts" ]; then
              tasks="$tasks :src:all:${name}:assembleDebug"
            fi
          done
          echo "Discovered tasks:$tasks"
          echo "tasks=$tasks" >> "$GITHUB_OUTPUT"

      - name: Build ALL extensions (assembleDebug)
        run: |
          chmod +x gradlew
          ./gradlew ${{ steps.modules.outputs.tasks }} --stacktrace

      - name: Collect APKs
        run: |
          mkdir -p apks
          find src/all -path '*/build/outputs/apk/*/*.apk' -exec cp -v {} apks/ \;
          echo "=== Collected APKs ==="
          ls -lh apks/

      - name: Upload APKs
        uses: actions/upload-artifact@v4
        with:
          name: all-apks
          path: apks/*.apk
          if-no-files-found: error
          retention-days: 7
