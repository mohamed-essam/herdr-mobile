# herdr-mobile App Icon (Design)

**Date:** 2026-07-08
**Status:** Approved (brainstorming)

## Problem

The app ships with no launcher icon — `AndroidManifest.xml`'s `<application>` has
no `android:icon`, so Android falls back to the generic default. The app should
carry the official **herdr** brand mark.

## The mark

herdr's logo (`../herdr/assets/logo.svg`) is a ram's head in profile — charcoal
`#303438` — whose eye/muzzle is formed as a terminal prompt `>_`, with a curled
horn, on a light grey `#d9dad8` field. It is a single-path SVG, so it converts
cleanly to an Android VectorDrawable.

## Decisions (locked)

- **Motif:** the official herdr ram mark, reproduced faithfully.
- **Background:** authentic `#d9dad8` (the herdr.dev field), fixed — not the app's
  dark Catppuccin base.
- **Composition:** **1:1** with the web logo — the ram fills the icon canvas and
  its body bleeds off the edges exactly as on herdr.dev. (The horn sits close to
  the round-mask crop line; accepted.)
- **Format:** a **vector adaptive icon** — `minSdk 26` guarantees adaptive-icon
  support, so no raster PNG densities are needed.

## Components

Adaptive icon = three layers:

- **Foreground** (`res/drawable/ic_launcher_foreground.xml`): the ram silhouette
  only (fill `#303438`), transparent elsewhere. The path is copied verbatim from
  `logo.svg` and wrapped in a VectorDrawable `<group>` reproducing the SVG's
  transform `translate(0,512) scale(0.1,-0.1)`; viewport `512x512` mapped to the
  full `108dp` canvas (the 1:1 composition). The horn spiral and `>_` prompt are
  carved by the path's existing winding — default `nonZero` fillType matches the
  source SVG.
- **Background** (`@color/ic_launcher_background` = `#d9dad8`): a solid color
  resource in `res/values/colors.xml`.
- **Monochrome** (Android 13+ themed icons): reuses the same foreground drawable,
  so the launcher can tint the mark to the user's theme; the carved `>_`/horn
  details remain as holes and stay legible.

## Files

- Create `app/src/main/res/drawable/ic_launcher_foreground.xml` — VectorDrawable
  (ram path).
- Create `app/src/main/res/values/colors.xml` — `ic_launcher_background` color.
- Create `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` — `<adaptive-icon>`
  referencing background + foreground + monochrome.
- Create `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` — identical
  `<adaptive-icon>` (round launchers).
- Modify `app/src/main/AndroidManifest.xml` — add
  `android:icon="@mipmap/ic_launcher"` and
  `android:roundIcon="@mipmap/ic_launcher_round"` to `<application>`.

No raster mipmaps and no legacy (`mipmap-*dpi`) fallbacks are required, because
`minSdk 26` means every target device renders the `anydpi-v26` adaptive icon.

## Licensing

The mark is herdr's own logo, vendored from the herdr repo (the app already
vendors herdr's GPLv3 Termux/terminal code). Reproducing herdr's brand for a
herdr companion app is in keeping with that; the icon asset is derived from
`herdr/assets/logo.svg`.

## Testing

No unit test — this is a resource/asset change with no logic. Verification:

1. `:app:assembleDebug` builds with the new resources (a malformed VectorDrawable
   fails the build, so a green build proves the path parses).
2. Install on device; confirm the launcher shows the ram mark (not the default
   Android icon), and that it renders correctly under the device's mask
   (squircle/round) with the `>_` prompt legible.

## Out of scope

- Notification icon (a separate small monochrome asset) — the app's notifications
  currently use the system default; changing that is a follow-up if desired.
- Play Store listing raster (512px) — not needed for install/run.
- Any change to the app's in-UI theme or the `herdr` label.
