# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - 2026-02-12

### Added
- **LED Compositor**: Buffered rendering engine replacing direct `setAllLeds()` — supports multiple simultaneous effects on different keys with priority-based compositing, dirty-check optimization, and adaptive render cycle (~30 FPS)
- **EffectTarget system**: Sealed interface (`AllLeds`, `SingleLed`, `LedSet`, `Zone`) enabling per-key and per-zone effect targeting — all existing effects remain backward-compatible with `AllLeds` default
- **GradientEffect**: New effect type that transitions between two colors over a configurable duration
- **Git Status Indicator**: Per-key RGB showing repository state — uncommitted (yellow), conflict (red pulse), unpushed (blue), clean (green). Requires Git4Idea plugin (optional dependency). Configurable LED indices, colors, and polling interval per project
- **Code Analysis Indicator**: Per-key RGB reflecting code quality — errors (red), clean (green). Listens to `DaemonCodeAnalyzer` events
- **IDE Notification Listeners**: Indexing mode (orange pulse), low memory warning (red flash)
- **Pomodoro Timer**: Full state machine (IDLE→WORK→BREAK→LONG_BREAK) with gradient effect during work, pulse during breaks, flash on transitions. Status bar widget with countdown. Actions in Tools → KGBoard menu
- **Shortcut Highlight Service**: Context-aware keyboard shortcut highlighting (debug, search, editing, VCS contexts) using KeymapManager and KeyboardLayoutService
- **KeyboardLayoutService**: Auto-maps OpenRGB LED names (e.g., "Key: A") to LED indices and Java KeyEvent key codes
- **Multi-device support**: `setAllLedsMultiDevice()` / `updateLedsMultiDevice()` with 10ms inter-device delay, device roles (primary/ambient/indicator/mirror)
- **Per-project RGB profiles**: `KgBoardProjectSettings` with project-level configurable for git rules, analysis config, shortcut highlight settings
- **Project Settings UI**: New settings page under Settings → Tools → KGBoard RGB → Project Settings with git indicator colors, code analysis colors, shortcut highlight toggle
- **App Settings UI additions**: Multi-device section, Pomodoro timer configuration with color pickers, IDE notifications toggles and colors

### Changed
- `EffectManagerService` refactored into facade over `LedCompositor` — Phase 1 API fully preserved, new `addTargetedEffect`/`removeTargetedEffect` API for per-key effects
- `returnToIdle()` now removes only global (`AllLeds`) effects, preserving per-key indicators (git, analysis)
- `OpenRgbProtocol.parseControllerData()` now captures LED names (previously skipped) and zone matrix data (height, width, map)
- `RgbDeviceInfo` extended with `ledNames: List<String>` field
- `RgbZoneInfo` extended with `matrixHeight`, `matrixWidth`, `matrixMap` fields
- `FocusEventListener` and `AppLifecycleHandler` updated for multi-device support
- `KgBoardStartupActivity` now initializes Phase 2 listeners (CodeAnalysis, IdeNotification, KeyboardLayout)
- `KgBoardSettings.State` extended with multi-device configs, pomodoro settings, notification settings

## [0.1.2] - 2026-02-12

### Changed
- Updated README: added focus/lifecycle documentation, new settings, updated architecture diagram, file tree, code examples (Kotlin UI DSL v2)

## [0.1.1] - 2026-02-12

### Added
- LICENSE (Apache 2.0)
- CONTRIBUTORS.md
- CONTRIBUTING.md with build/dev guide and contribution workflow
- CODE_OF_CONDUCT.md (Contributor Covenant 2.1)
- SECURITY.md with vulnerability reporting policy
- .editorconfig for consistent code formatting

## [0.1.0] - 2026-02-12

### Added
- OpenRGB TCP client with full binary protocol implementation
- Effect engine: static, pulse, flash, progress effects with priority system
- Build event listener: success (green flash), failure (red static), in-progress (yellow pulse)
- Execution listener: run (green static), debug (purple pulse), abnormal exit (orange flash)
- Test listener: running (blue pulse), passed (green flash), failed (red static)
- Focus handling: dim keyboard on IDE focus loss, restore on focus gain
- Lifecycle handling: reset LEDs to black on IDE exit
- Project close handling: clear effects and return to idle
- Settings UI with native color pickers (Kotlin UI DSL v2)
- Status bar widget with connection indicator and toggle
- Auto-connect on startup, auto-reconnect on connection loss
- Persistent settings storage in `kgboard.xml`
- Comprehensive README with architecture docs and setup guide
