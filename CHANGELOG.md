# Changelog

All notable changes to this project are documented here. Versions follow semantic versioning.

## [Unreleased]

### Added

- 4K televisions can select `3840×2160 (4K)` explicitly. The choice appears when both the panel mode and the reported AVC decoder ceiling reach it; `Auto / Native` keeps its conservative 1080p behavior, so nothing changes for anyone who does not opt in.
- Television `hello` messages now also carry the `panel` pixels/points/scale facts and `maxEncodeWide`/`maxEncodeHigh` (PROTOCOL.md 6.7 and 6.5). A sender that honors them builds a true HiDPI 4K desktop instead of assuming a 2× backing store and dividing the announced size by two. Handheld receivers stay on the legacy path until their density mapping is measured.
- **Settings & Diagnostics** now report what Android actually told the receiver: the panel mode, the app window, the H.264 decoder's own hardware ceiling, and the highest frame rate it claims at 3840×2160. These separate "the firmware only exposes a 1080p mode" from "this device cannot decode 4K" without needing `adb`.
- An advanced **Force 3840×2160** switch announces 4K even when the reported display mode is 1080p, for televisions whose system renders the interface at 1080p and lets the panel upscale it. It changes only what the receiver announces, never the detected facts shown in Diagnostics, and switching it off returns to what detection reports.

### Fixed

- A Mac that connects while the Activity is still binding the receiver service, or before the `SurfaceView` decoder is attached, no longer leaves a black screen until the sender's next periodic IDR. The receiver now probes with bounded `kf` requests until SPS/PPS have been seen.
- Cold start no longer queues behind the GitHub API. The foreground update check waits until the receiver is up, an *attempt* rather than only a success throttles retries for six hours, and the HTTP timeouts were halved so a filtered or unreachable API host fails fast instead of stalling a television on every launch.
- Fork builds: `.github/workflows/fork-apk.yml` produces an installable debug APK artifact without the project's private signing secrets.

## [0.1.3] - 2026-08-09

### Fixed

- Fixed the in-app installer handoff crash caused by treating Compose's dialog-themed context as an Activity. The actual `MainActivity` is now passed explicitly to the updater, so the **Install unknown apps** and Android package-installer screens open safely on TV devices.

### Upgrade note

- Versions 0.1.1 and 0.1.2 can securely find and download an update but may crash when **Install update** is selected. Install v0.1.3 manually; v0.1.3 and later have the corrected end-to-end updater flow.

## [0.1.2] - 2026-08-09

### Changed

- The Settings button now fades out after five seconds of inactivity while connected. Any remote-key or pointer interaction reveals it and restarts the timer; D-pad/OK focuses it, and Menu/Settings opens the dialog directly.

### Fixed

- Disconnecting now clears the retained hardware-decoder frame and shows an opaque black listener screen instead of leaving the last streamed image visible.

## [0.1.1] - 2026-08-09

### Fixed

- Fixed the production startup crash on Android 11 and newer. Service startup now uses `DisplayManager.DEFAULT_DISPLAY` and conservative 720p/1080p fallbacks; only an Activity may read visual window metrics.
- Contained vendor display-mode and AVC capability failures so the foreground receiver still reaches a visible listening, waiting-for-network, or recoverable error state.
- Updated the accurate display profile after `MainActivity` binds, including an updated protocol hello when a Mac connected before Activity detection completed.
- Removed the receiver-name text field from normal D-pad focus traversal, preventing it from trapping TV remote navigation.

### Added

- Added a secure, TV-friendly updater backed by the repository's latest stable GitHub release API. It uses anonymous public requests and embeds no GitHub token.
- Added exact asset-name, HTTPS redirect-host, streamed SHA-256, GitHub digest, package ID, monotonically increasing version code, tag/version, and Android signing-lineage validation.
- Added app-private streaming downloads, progress, release notes, retry states, dismiss/skip behavior, an opt-in unmetered auto-download setting, and Android's user-confirmed installer flow.
- Added a throttled foreground check, constrained daily WorkManager check, nonintrusive completion notification, and streaming-aware download/install deferral.
- Added API 30 startup regression coverage, Android instrumentation, and focused tests for update parsing, version policy, throttling, assets, checksums, signatures, redirects, cleanup, persistence, and WorkManager constraints.

### Upgrade note

- Version 0.1.0 crashes before it can update itself on Android 11 and newer. Install v0.1.1 manually over v0.1.0; v0.1.1 and later can use the in-app updater.

## [0.1.0] - 2026-08-09

### Added

- Android TV and Google TV launcher support, TV banner, and D-pad focus states.
- Sequential listener fallback from TCP 9010 through 9029 with the full selected address shown.
- Multicast-lock-backed Bonjour registration, independent NSD diagnostics, retry backoff, and network-change re-registration.
- Persistent TV resolution, UI scale, Fit/Fill/Stretch/1:1, keep-awake, and optional performance overlay settings.
- Shared video/cursor/input transform and focused tests for settings, resolution, listener, NSD, transform, and awake policy.
- Production identity, signing workflow, CI checks, release automation, and English/Portuguese resources.

### Changed

- TV Auto output defaults to a low-memory 1080p ceiling instead of advertising 4K.
- Android TV defaults to keeping the screen awake while the receiver Activity is open; handhelds default to connected-only.
- The frame queue is bounded to favor current low-latency video.

### Security

- The receiver binds to the active local IPv4 interface and documents the unauthenticated trusted-LAN boundary.

[0.1.0]: https://github.com/mohith-das/opendisplay-android-tv/releases/tag/v0.1.0
[0.1.1]: https://github.com/mohith-das/opendisplay-android-tv/releases/tag/v0.1.1
[0.1.2]: https://github.com/mohith-das/opendisplay-android-tv/releases/tag/v0.1.2
[0.1.3]: https://github.com/mohith-das/opendisplay-android-tv/releases/tag/v0.1.3
