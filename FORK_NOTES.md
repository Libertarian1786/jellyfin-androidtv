# The Real McCoy — fork notes

Personal fork of jellyfin-androidtv (branch `release-custom-home`). This file lists what
this fork changes so upstream merges can be resolved confidently.

## New files (additive — never conflict)

- `app/src/main/java/.../integration/McCoyUpdater.kt` — self-updater (checks this repo's GitHub releases on launch)
- `app/src/main/java/.../data/repository/RecommendationsRepository.kt` — on-device "Suggested for You" recommender
- `app/src/main/java/.../ui/home/HomeFragmentSuggestionsRow.kt` — static-items home row for the recommender
- `app/src/main/java/.../ui/home/HomeHeroCarousel.kt` — full-screen focus-following backdrop ("hero")
- `app/src/main/java/.../ui/startup/IntroActivity.kt` — launcher activity playing `res/raw/intro_splash.mp4`
- `app/src/main/res/raw/intro_splash.mp4`, `res/xml/mccoy_update_paths.xml`, `app/mccoy.keystore`
- `.github/workflows/mccoy-release.yaml` — builds + publishes a GitHub release per push (the updater feeds from it)
- `.github/workflows/upstream-merge-check.yaml` — weekly upstream merge dry-run

## Upstream files intentionally modified (expect conflicts here)

| File | What changed | On conflict, keep |
|---|---|---|
| `ui/home/HomeRowsFragment.kt` | Curated row order, themed-collection rows (cached + daily rotation), suggestions row, transparent grid, top-pinned alignment, `setCurrentItem` focus signal | Ours, re-apply upstream internals carefully |
| `ui/home/HomeFragment.kt` | Box layout: full-screen hero behind transparent rows + floating toolbar | Ours |
| `ui/home/HomeFragmentHelper.kt` | `loadCollectionRow()` added | Merge both |
| `ui/presentation/CardPresenter.java` | `mShowInfo` gate on episode info strip; `Math.min` height cap keyed off `mStaticHeight` (stock behavior for default callers) | Merge both |
| `ui/presentation/CustomListRowPresenter.kt` | `zoomFactor` constructor param (defaults to stock MEDIUM) | Merge both |
| `ui/presentation/PositionableListRowPresenter.kt` | zoom pass-through constructor | Merge both |
| `ui/presentation/UserViewCardPresenter.kt` | `showInfo=false` (no label area under library tiles) | Ours |
| `ui/browsing/MainActivity.kt` | `McCoyUpdater.checkAndUpdate()` call in `onCreate` | Merge both |
| `ui/startup/fragment/SplashFragment.kt` | Blank black (no branding between intro video and app) | Ours |
| `ui/startup/fragment/SelectServerFragment.kt` | Version line rebranded | Ours |
| `ui/preference/category/about.kt` | Version line rebranded | Ours |
| `data/service/BackgroundService.kt` | `currentItem` StateFlow + `setCurrentItem()` for the hero | Merge both |
| `AndroidManifest.xml` | Launcher moved to IntroActivity; `REQUEST_INSTALL_PACKAGES`; update FileProvider | Merge both |
| `app/build.gradle.kts` | `mccoyDebug` pinned signing, `isDebuggable=false` on debug, `-PmccoyBuild` versionCode injection | Merge both |
| `res/values/strings.xml` | App name / welcome / suggested-for-you strings | Ours for branding keys |
| Branding drawables/mipmaps (`app_logo`, `app_icon*`, `app_banner*`) | Red "M" / The Real McCoy | Ours |
| `.github/workflows/app-build.yaml` | `release-*` push trigger removed (mccoy-release builds this branch) | Ours |

## Merge strategy

Merge upstream **release tags one at a time** (not one big jump), with `git rerere` enabled.
The weekly `upstream-merge-check` workflow reports whether upstream currently merges + builds
cleanly. After any merge: build locally, sideload to the Streamer, sanity-check home/playback,
then push — every TV auto-updates via the release pipeline.
