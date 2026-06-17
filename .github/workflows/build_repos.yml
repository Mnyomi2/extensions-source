name: Build Repositories (All-in-One)

on:
  workflow_dispatch:

permissions:
  contents: write   # needed to push branches and commit the signing key

concurrency:
  group: ${{ github.workflow }}
  cancel-in-progress: true

jobs:
  # Job 1: Ensure the signing key is available before building.
  # This avoids write-conflicts when parallel jobs try to generate a key.
  prepare_signing_key:
    name: Prepare Signing Key
    runs-on: ubuntu-latest
    steps:
      - name: Checkout main
        uses: actions/checkout@v4
        with:
          ref: main
          persist-credentials: true

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: temurin

      - name: Generate key if missing
        run: |
          if [ -f "signingkey/signingkey.jks" ]; then
            echo "Signing key already exists."
          else
            echo "Signing key not found. Generating a new one..."
            mkdir -p signingkey
            
            keytool -genkey -v \
              -keystore signingkey/signingkey.jks \
              -alias key0 \
              -keyalg RSA -keysize 2048 -validity 10000 \
              -storepass android -keypass android \
              -dname "CN=Extension Repo, OU=CI, O=CI, L=CI, S=CI, C=US"
              
            # Configure git and save the key to main branch
            git config user.name "github-actions[bot]"
            git config user.email "github-actions[bot]@users.noreply.github.com"
            git add -f signingkey/signingkey.jks
            git commit -m "chore: persist signing key [skip ci]"
            git push origin main
          fi

  # Job 2: Build and publish each language repository in parallel
  build:
    name: Build ${{ matrix.lang }} extensions
    needs: prepare_signing_key
    runs-on: ubuntu-latest
    timeout-minutes: 90
    strategy:
      matrix:
        lang: [ar, all, en]
      fail-fast: false  # if one language fails, others continue building
    steps:
      - name: Checkout main
        uses: actions/checkout@v4
        with:
          ref: main
          persist-credentials: true

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: temurin

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Load signing key
        run: |
          if [ -f "signingkey/signingkey.jks" ]; then
            cp signingkey/signingkey.jks signingkey.jks
          else
            echo "Error: Signing key not found."
            exit 1
          fi

      - name: Discover ${{ matrix.lang }} modules
        id: modules
        run: |
          tasks=""
          for d in src/${{ matrix.lang }}/*/; do
            name="$(basename "$d")"
            if [ -f "${d}build.gradle" ] || [ -f "${d}build.gradle.kts" ]; then
              tasks="$tasks :src:${{ matrix.lang }}:${name}:assembleRelease"
            fi
          done
          echo "Discovered:$tasks"
          echo "tasks=$tasks" >> "$GITHUB_OUTPUT"

      - name: Build ${{ matrix.lang }} extensions (assembleRelease)
        env:
          ALIAS: key0
          KEY_STORE_PASSWORD: android
          KEY_PASSWORD: android
        run: |
          chmod +x gradlew
          ./gradlew ${{ steps.modules.outputs.tasks }} --stacktrace

      - name: Collect APKs
        run: |
          mkdir -p repo/apk
          find src/${{ matrix.lang }} -path '*/build/outputs/apk/*/*.apk' | while read -r apk; do
            base="$(basename "$apk")"
            dest="${base/-release.apk/.apk}"
            cp -v "$apk" "repo/apk/$dest"
          done
          echo "=== APKs ==="; ls -lh repo/apk/

      - name: Run Inspector (extract source metadata)
        env:
          ANDROID_HOME: ${{ env.ANDROID_HOME }}
        run: |
          INSPECTOR_LINK="$(curl -s 'https://api.github.com/repos/keiyoushi/extensions-inspector/releases/latest' | jq -r '.assets[0].browser_download_url')"
          echo "Inspector: $INSPECTOR_LINK"
          curl -L "$INSPECTOR_LINK" -o Inspector.jar
          java -jar Inspector.jar "repo/apk" "output.json" "tmp"
          echo "=== output.json (head) ==="; head -c 600 output.json; echo

      - name: Generate index.min.json + icons
        run: |
          python ./.github/scripts/create-repo.py
          python - <<'PY'
          import json
          data = json.load(open("repo/index.min.json", encoding="utf-8"))
          json.dump(data, open("repo/index.json","w",encoding="utf-8"),
                    ensure_ascii=False, indent=2)
          print(f"Indexed {len(data)} extensions")
          PY
          ls -R repo | head -40

      - name: Publish to repo-${{ matrix.lang }} branch
        run: |
          git config user.name  "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"

          # Work in a clean worktree for the specific language branch
          mkdir -p /tmp/publish
          cp -r repo/* /tmp/publish/

          # Reset local changes and clean untracked files
          git reset --hard
          git clean -fd

          git fetch origin repo-${{ matrix.lang }} || true
          if git show-ref --verify --quiet refs/remotes/origin/repo-${{ matrix.lang }}; then
            git checkout -B repo-${{ matrix.lang }} origin/repo-${{ matrix.lang }}
          else
            git checkout --orphan repo-${{ matrix.lang }}
            git rm -rf . >/dev/null 2>&1 || true
          fi

          # replace contents
          rm -rf apk icon index.json index.min.json
          cp -r /tmp/publish/* .

          git add apk icon index.json index.min.json
          if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
            git commit -m "Update ${{ matrix.lang }} extensions repo"
            git push origin repo-${{ matrix.lang }}
          else
            echo "No changes to publish."
          fi
