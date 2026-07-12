# herdr-mobile App Icon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the app the official herdr ram mark as a vector adaptive launcher icon.

**Architecture:** An Android adaptive icon (`minSdk 26`, so no raster fallback): a VectorDrawable foreground carrying the ram silhouette converted from `herdr/assets/logo.svg`, a solid `#d9dad8` background color, and a monochrome layer reusing the foreground for Android 13+ themed icons. The manifest `<application>` is wired to it.

**Tech Stack:** Android resources (VectorDrawable, `<adaptive-icon>` XML), Gradle. Build: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`.

## Global Constraints

- **Motif:** the official herdr ram mark, reproduced faithfully (charcoal `#303438` ram + `>_` prompt).
- **Background:** authentic `#d9dad8`, fixed (not the app's dark theme).
- **Composition:** 1:1 with the web logo — viewport `512` mapped to the full `108dp` canvas; body bleeds off edges.
- **Format:** vector adaptive icon only; no raster PNG densities, no legacy `mipmap-*dpi` fallbacks (`minSdk 26`).
- **Source of truth for the path:** the `d` attribute of the single `<path>` in `the herdr repo's assets/logo.svg` (line 4), copied verbatim.
- **SVG→VectorDrawable transform:** SVG `transform="translate(0 512) scale(.1 -.1)"` becomes a VectorDrawable `<group android:translateY="512" android:scaleX="0.1" android:scaleY="-0.1">`.
- No unit test (resource-only change); a green `assembleDebug` proves the VectorDrawable parses, and on-device install confirms the visual.

---

### Task 1: herdr ram adaptive launcher icon

**Files:**
- Create: `app/app/src/main/res/drawable/ic_launcher_foreground.xml`
- Modify: `app/app/src/main/res/values/colors.xml` (add one color)
- Create: `app/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Modify: `app/app/src/main/AndroidManifest.xml:5-10` (`<application>` tag)

**Interfaces:** none (self-contained resource change).

- [ ] **Step 1: Create the foreground VectorDrawable**

Create `app/app/src/main/res/drawable/ic_launcher_foreground.xml` with the structure below. For `android:pathData`, copy the **entire** value of the `d` attribute from the single `<path>` in `the herdr repo's assets/logo.svg` (line 4) **verbatim** — do not re-type or truncate it. (Do NOT copy the SVG's background `<rect>`; the background is a separate layer.)

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="512"
    android:viewportHeight="512">
    <group
        android:translateY="512"
        android:scaleX="0.1"
        android:scaleY="-0.1">
        <path
            android:fillColor="#303438"
            android:pathData="PASTE_THE_d_ATTRIBUTE_FROM_logo.svg_HERE" />
    </group>
</vector>
```

- [ ] **Step 2: Add the background color**

Edit `app/app/src/main/res/values/colors.xml` to add the launcher background color inside `<resources>` (keep the existing `herdr_window` color):

```xml
    <!-- herdr.dev logo field (authentic light grey) -->
    <color name="ic_launcher_background">#FFD9DAD8</color>
```

- [ ] **Step 3: Create the adaptive icon (square/default)**

Create `app/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

- [ ] **Step 4: Create the round adaptive icon**

Create `app/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` with identical content:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

- [ ] **Step 5: Wire the manifest**

In `app/app/src/main/AndroidManifest.xml`, add `android:icon` and `android:roundIcon` to the `<application>` tag. The result must read:

```xml
    <application
        android:label="herdr"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:theme="@style/Theme.HerdrMobile"
        android:usesCleartextTraffic="true"
        android:allowBackup="true">
```

- [ ] **Step 6: Build**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (A malformed `pathData` or bad resource reference fails resource linking/parsing, so success proves the vector and adaptive XML are valid.)

- [ ] **Step 7: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/res/drawable/ic_launcher_foreground.xml \
        app/app/src/main/res/values/colors.xml \
        app/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml \
        app/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml \
        app/app/src/main/AndroidManifest.xml
git commit -m "feat(app): herdr ram adaptive launcher icon"
```

- [ ] **Step 8: Install and verify on device**

Run: `"$HOME/Android/Sdk/platform-tools/adb" -s adb-R5CY32261JN-KB349E._adb-tls-connect._tcp install -r app/app/build/outputs/apk/debug/app-debug.apk`
Expected: `Success`. Then confirm on the phone's launcher/app drawer that the icon is the charcoal herdr ram on a light-grey field (not the default Android icon), with the `>_` prompt legible under the device's icon mask.

## Self-Review

**Spec coverage:** Foreground vector (Step 1), `#d9dad8` background color (Step 2), square + round adaptive icons with monochrome layer (Steps 3-4), manifest `android:icon`/`android:roundIcon` wiring (Step 5), build + on-device verification (Steps 6, 8) — every spec component and file is covered. No notification icon / Play raster (spec: out of scope).

**Placeholder scan:** The one intentional fill-in (`PASTE_THE_d_ATTRIBUTE_FROM_logo.svg_HERE`) is a precise instruction pointing at an exact, stable source location (logo.svg line 4 `d` attribute) — chosen over pasting a ~2KB path string to avoid transcription corruption, not a vague TODO. All other content is complete.

**Type consistency:** Resource names are consistent across files — `@drawable/ic_launcher_foreground` (Step 1) referenced in Steps 3-4; `@color/ic_launcher_background` (Step 2) referenced in Steps 3-4; `@mipmap/ic_launcher` / `@mipmap/ic_launcher_round` (Steps 3-4 filenames) referenced in the manifest (Step 5).
