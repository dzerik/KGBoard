# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.4.0] - 2026-02-13

### Added
- **Git Branch Color Rules**: Regex-based branch name matching with configurable background colors. First matching rule wins. Applied as `AllLeds` background with lowest priority (0). Invalid regex patterns are silently skipped
- **Branch Rules Settings UI**: New "Git Branch Color Rules" section in Project Settings with `ListTableModel` table, add/remove toolbar, editable Pattern and Color columns

## [0.3.0] - 2026-02-13

### Added
- **TODO Indicator Listener**: Per-key RGB indicator for TODO items in the current file. Subscribes to `FileEditorManagerListener` (tab switch) and `DaemonCodeAnalyzer` (content changes). Uses `PsiTodoSearchHelper` with `ReadAction` on pooled thread for safe PSI access. `AtomicBoolean` disposed guard prevents race conditions.

## [0.2.4] - 2026-02-13

### Fixed
- Code analysis indicator now supports three states: errors (red), warnings (yellow), clean (green). Previously `analysisWarningColor` setting was declared but unused
- Warning detection uses `DocumentMarkupModel` of the current editor with `ReadAction` for PSI thread safety

## [0.2.3] - 2026-02-13

### Fixed
- ProgressEffect `var progress` changed to `val` to enforce immutability of sealed class hierarchy
- Pomodoro race condition: `completedSessions` now uses `AtomicInteger` with `synchronized` state transitions to prevent double-increment from concurrent `skip()`/`onPhaseComplete()`
- Pomodoro listener leak: `listeners` now uses `CopyOnWriteArrayList`, added `removeChangeListener()`
- Pomodoro `dispose()` now properly cleans up targeted effects

### Added
- LED index validation in `EffectTarget` — `SingleLed`, `LedSet`, `Zone` now throw `IllegalArgumentException` on negative indices
- 150ms debounce in `ShortcutHighlightService.activateContext()` to prevent flooding OpenRGB
- `LedCompositor` render cycle optimization: double buffering for frame array, cached AllLeds index list, `removeIf` for expired effects

## [0.2.2] - 2026-02-12

### Fixed
- **Critical: Race condition in LedCompositor** — `renderTask` is now guarded by `ReentrantLock`, preventing double render loop start from concurrent threads
- **Critical: Daemon thread leak in EffectManagerService** — replaced `Thread { sleep(N) }` per timed effect with single `ScheduledExecutorService` for cleanup
- **Critical: Race condition in GitStatusListener** — added `AtomicBoolean` disposed guard to prevent `updateGitStatus()` calls during/after dispose
- **Critical: EDT freeze in KgBoardConfigurable** — replaced `Thread.sleep(600)` inside `SwingUtilities.invokeLater` with non-blocking `Alarm`
- Race condition in `OpenRgbClient.getAllDevices()` — count + data reads now in single `synchronized` block
- Empty `dispose()` in CodeAnalysisListener, IdeNotificationListener, ShortcutHighlightService — now properly clean up per-key effects
- Missing error handling in BuildEventListener, ExecutionEventListener, TestEventListener, FocusEventListener — all event callbacks now wrapped in try-catch
- Hardcoded shortcut highlight colors — moved to `KgBoardProjectSettings` (shortcutDebugColor, shortcutSearchColor, shortcutEditingColor, shortcutVcsColor)

### Changed
- `LedCompositor.dispose()` now uses graceful shutdown with 2s timeout before `shutdownNow()`
- `LedCompositor.previousFrame` is now `@Volatile` for cross-thread visibility
- `EffectManagerService.currentGlobalEffectId` and `currentGlobalPriority` are now `@Volatile`

## [0.2.1] - 2026-02-12

### Changed
- Color picker elements are now 2x wider (90px) for easier color selection
- Per-key LED indices configuration for all notification types (indexing, low memory, TODO)
- IdeNotificationListener uses EffectTarget.LedSet when LED indices are specified, falls back to AllLeds when empty

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
