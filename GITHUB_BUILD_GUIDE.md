# Dot Recorder (audio-only) — GitHub build guide

Same zero-install approach as before, with one fix: last time the
`.github/workflows/build.yml` file got silently skipped because file
managers hide dot-folders when you drag things. This time, create that
one file directly on GitHub's website instead of dragging it — it takes
30 seconds and can't be skipped.

## 1. New repo
- github.com → **+** → **New repository**
- Name: `dot-recorder-audio`, set **Public**, don't add a README
- **Create repository**

## 2. Add the workflow file FIRST (this is the part that failed last time)
- On the empty repo page, click **Add file → Create new file**
- Filename box: type exactly `.github/workflows/build.yml`
  (GitHub auto-creates the folders as you type the slashes)
- Paste the contents of `.github/workflows/build.yml` from this zip
- Click **Commit changes**

## 3. Now drag in everything else
- Unzip `DotRecorderAudio_project.zip` on your computer
- Go to your repo → **Add file → Upload files**
- Drag in the **contents** of the unzipped folder — `app`,
  `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
  (skip the `.github` folder, you already added that file directly)
- **Commit changes**

## 4. Check the Actions tab
- Click **Actions** — you should see "Build APK" running (not the
  "Simple workflow / Configure" prompt from before — that only shows up
  when GitHub can't find a workflow file, which is exactly what we just fixed)
- Takes ~3-5 min. Green check = success.
- Click the run → **Artifacts** at the bottom → download
  **DotRecorderAudio-debug-apk**

## 5. Install
- Do this from your phone's browser if possible, so the file lands
  straight in your Downloads
- Open the downloaded `.apk`, allow "install from this source" when
  prompted, tap Install

## What's different from the camera version
- No camera, no preview screen — just a big timer and one REC button
- Same Bluetooth mic routing (HFP/SCO), same quality ceiling
  (phone-call-grade audio, not studio-grade)
- Saves `.m4a` files to **Music/DotRecorder** in your phone's storage,
  visible in any file manager or the Files app

## If Actions shows a red X
Click into the failed run, open the failing step, and paste me the
error text — usually it's one dependency version needing a bump.
