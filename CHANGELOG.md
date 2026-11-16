# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.2.0] - 2026-11-17

### Added
- Draft scenario previews use the shared effect factory, captured device choices and layout-validated targets; device identification shows white at 30% for two seconds.
- Per-page preview availability reasons and independent application/project settings owners.

### Fixed
- Restore temporary diagnostic connections when settings pages overlap; project Apply cannot commit application connection changes.
- Reject stale rendered selections after reconnect and preserve another page's preview when an old page closes or submits an invalid request.
- Retire preview-only output after in-flight writes on Cancel, Apply, Disable, device/server changes and shutdown.

## [3.1.0] - 2026-11-17

### Added
- Versioned, detached XML migration and settings drafts with independent scenario-state rules and lossless preservation of unsupported records.
- Identity-based runtime device assignments, layout-confirmed raw targets and per-device shortcut key resolution, including mirror devices.

### Fixed
- Block every ordinary, idle, disabled and shutdown output without a confirmed device binding; retire outputs through their original identity rather than a reused index.
- Fence session-only bindings across runtime restarts and preview output across connection changes.
- Merge observed duplicate-serial metadata safely through concurrent Settings Apply; retain unavailable duplicate bindings in drafts.
- Preserve typed targets while legacy settings forms edit colors or flags.

## [3.0.5] - 2026-11-16

### Added
- Typed LED targets with named-key, stable-group and confirmed-layout resolution; unknown targets stay preserved and unavailable.
- A shared scenario catalogue and effect factory with independent per-state rules and fixed priorities.

### Fixed
- Reject ambiguous key aliases and shortcut key-code mappings instead of overwriting earlier LED matches.
- Preserve user-group UUIDs across renames and XML round trips; resolve long/cyclic group references without recursion.

## [3.0.4] - 2026-11-16

### Fixed
- Parse controller identity, display names and geometry using protocol0–5 field gates with bounded payload/string/count validation.
- Send protocol0 controller requests without a version payload and support unversioned servers that ignore protocol negotiation.

### Added
- Pure endpoint/session-aware device binding resolution with serial trust checks, ambiguous-device rejection and ordered layout fingerprints.

## [3.0.3] - 2026-11-16

### Added
- A bounded loopback OpenRGB SDK simulator with independently encoded protocol0–5 controller fixtures, inventory changes, write gates and socket resets.
- Real transport regressions for frame delivery/reconnect and preview-only device retirement while a write is blocked.

## [3.0.2] - 2026-11-16

### Added
- Five implementation plans and a shared dependency/contract index for the complete approved product evolution scope, with migration, race-condition and actual IDE verification tasks.

### Changed
- Mark the written product specifications as approved. Product code is unchanged; implementation plans are ready for review before execution.

## [3.0.1] - 2026-11-16

### Added
- A researched product evolution design and five package specifications covering native settings, typed LED targets, stable device bindings, output diagnostics, lighting profiles, Git/build integrations and cross-IDE/hardware verification.
- Explicit legacy XML migration rules, first-upgrade device confirmation, optional integration boundaries and pinned IDEA/PyCharm/WebStorm263 verification targets.

### Changed
- Document the full user-approved roadmap and its acceptance criteria. Product runtime behavior is unchanged; implementation awaits review of the new written specifications.

## [3.0.0] - 2026-11-16

### Changed
- Replace project compositors and effect managers with one application runtime, immutable state snapshots, a serial event queue and a single bounded network writer.
- Give active project windows exclusive lighting ownership; compose focus dimming and per-device roles through one policy.
- Scope code analysis and TODO results to the selected document revision, with explicit unknown/indexing/not-applicable states.
- Apply settings atomically from validated drafts; diagnostic Connect and temporary Test previews are scoped to the settings dialog.
- Move Pomodoro phases and deadlines into the runtime and derive remaining time without independent ticking threads.
- Use ProjectTaskListener for build lifecycle, retain independent operation identities and restore lower-priority effects on completion.
- Bound terminal effects consistently by effectDurationMs for global and per-key targets. Cancelled, empty and not-started runs do not signal success.

### Fixed
- Fence old connection results, retry with bounded backoff, close failed sockets and retain per-device successful-send caches.
- Coalesce pending frames, preserve return-to-previous-color updates during in-flight writes and flush preview retirement frames before disconnect.
- Expire static effects without a second IDE event; prevent background projects, disabled features and stale editor results from overwriting current output.
- Cancel preview on close, device changes, Disable and Disconnect; preserve manual disconnect through Settings Cancel.
- Refresh device geometry and keyboard mappings after reconnect or inventory changes; respect skipCustomMode and negotiated protocol versions.

### Added
- Deterministic runtime, transport concurrency, TCP reset, settings validation and real IDEA263 lifecycle/XML/UI regression tests.
- CI plugin compatibility verification, documented runtime extension contracts and a separate UX/UI audit with fixture screen captures and a proposed settings redesign. Physical OpenRGB hardware verification remains pending.

## [2.0.0] - 2026-11-16

### Changed
- Build against IntelliJ IDEA 2026.3 (build 263.7307.200); require IDEA 2026.3 (263.*) and JDK 25.
- Upgrade Kotlin to 2.4.21, IntelliJ Platform Gradle Plugin to 2.20.1, and Gradle Wrapper to 9.7.0 with distribution checksum verification.
- Use the Kotlin standard library bundled with the IDE and explicitly provide the JUnit Platform launcher for Gradle 9 test execution.
- Align JUnit Jupiter, Platform, and Vintage with the SDK test framework through the JUnit 6.1.3 BOM.
- Allow reusing a local IDEA SDK installation via `-PlocalPlatformPath` when building and testing.
- Declare the extracted bundled Test Runner plugin as a compile-time and runtime dependency for IDE test events.
- Align CI, contributor documentation, and the runtime redesign documents with the new SDK baseline. The runtime rewrite is planned separately as 3.0.0.

## [1.4.2] - 2026-11-16

### Added
- Detailed runtime state implementation plan with regression tests, SDK integration contracts, and atomic migration checks.

### Changed
- Runtime redesign specification marked as approved. Runtime implementation is unchanged.

## [1.4.1] - 2026-11-16

### Added
- Contributor guide in `AGENTS.md` and proposed runtime state redesign specification, including lifecycle invariants and regression scenarios. Runtime implementation is unchanged.

## [1.4.0] - 2026-06-22

### Added
- **Условное включение полей настроек (`enabledIf`)**: зависимые поля (Pomodoro, File Save Flash, Debugger Breakpoint, Git-индикатор, Code Analysis, Multi-Device) теперь гаснут, когда соответствующая функция выключена — настройки сразу показывают, что активно.
- **Валидация ввода**: поля LED-индексов и Host проверяются на лету (`validationOnInput`) с подсказкой об ошибке.
- **Статус подключения по событию**: метка статуса в настройках обновляется по событию подключения (`addConnectionListener`) с состоянием «Connecting…», вместо угадывания фиксированной задержкой.

### Fixed
- **Фриз IDE при недоступном OpenRGB**: `OpenRgbClient.connect()` использовал `Socket(host, port)` без таймаута соединения (`soTimeout` влияет только на чтение) и выполнялся под глобальным локом. Недоступный/фильтруемый хост блокировал лок на десятки секунд, а EDT-вызовы (`isConnected` в status-bar, кнопки Connect/Disconnect) зависали. Теперь: connect-таймаут 3с, а блокирующий сетевой I/O вынесен за пределы лока (публикация результата защищена epoch-счётчиком от гонки с `disconnect()`).
- **Гонка в `EffectManagerService.applyEffect`**: неатомарный check-then-act на `currentGlobalEffectId`/`currentGlobalPriority` при одновременных событиях build/test/run/debug мог «залипить» глобальный эффект. Операции обёрнуты в общий лок.
- **Утечка connection-listener**: `KgBoardStartupActivity` регистрировал listener в application-level сервисе и не снимал его — listener'ы накапливались при каждом open/close проекта. Теперь снимается через `Disposer.register(project)`.
- **Утечка listener в Pomodoro-виджете**: `install()` добавлял change-listener, а `dispose()` его не снимал. Теперь listener хранится и снимается.
- **Гонка двойного перехода фазы Pomodoro**: `cancel(false)` не прерывает уже выполняющийся tick — при `skip()`/`stop()` мог произойти двойной переход. Добавлен generation-счётчик тиков.
- **Render-цикл крутился вечно для статических эффектов**: persistent `StaticEffect` (git/analysis) держал 30 FPS цикл «вхолостую». Теперь loop стартует только при анимированных эффектах, статика рисуется через `renderOnce()`.
- **`untilBuild` не применялся**: `build.gradle.kts` не пробрасывал `pluginUntilBuild`, из-за чего эффективный верхний предел был `243.*` (плагин не ставился на 2025.1+). Теперь `pluginUntilBuild=253.*` действительно применяется.
- **Zone-эффекты не отрисовывались** в single-device пути: `composeFrame` получил device-aware zone-resolver.

### Changed
- **Потокобезопасность состояния**: `@Volatile` для `state` в `KgBoardSettings`/`KgBoardProjectSettings`, `cachedDevices`/`cachedDeviceCount` в `OpenRgbConnectionService`; синхронизация перестроения маппингов в `KeyboardLayoutService`; `@Volatile` в `KeyGroupService`; потокобезопасный `activeContexts` (`ConcurrentHashMap.newKeySet`) в `ShortcutHighlightService`.
- **Кэш regex для git branch-rules**: паттерны компилируются один раз вместо рекомпиляции на каждый poll.
- **Снижен уровень логирования парсинга протокола** (`info` → `debug`) — убран спам в `idea.log`.
- **Дедупликация UI настроек**: общий `SettingsUiUtil` (color panel, hex, key-group popup, валидация LED-индексов) вместо копипасты в двух Configurable; `Alarm`'ы привязаны к UI-Disposable и корректно освобождаются в `disposeUIResources`.
- **Кнопка Test без активного подключения** показывает уведомление вместо тихой записи в лог.

### Removed
- Мёртвое поле `frameBuffer` в `LedCompositor`; артефакты компиляции (`com/`, `git4idea/`, `*.class`, `img.png`) добавлены в `.gitignore`.

## [1.3.0] - 2026-02-14

### Fixed
- **OpenRGB protocol v4/v5 parsing**: Root cause of all LED control failures — `parseZone()` didn't read v4 zone segments, causing buffer position shift and `numLeds` to parse as 0. Now correctly handles:
  - v3+: brightness fields in modes (`brightness_min`, `brightness_max`, `brightness`)
  - v4+: zone segments (`name`, `type`, `start_idx`, `leds_count`)
  - v5+: zone flags, LED alternate names, controller flags
- **Test buttons flash black instead of configured color**: Compositor pause during test preview, restoring idle state after 2 seconds
- **"Test LEDs" button on Connection tab kills EVision keyboard**: Was calling `setCustomMode` → now uses safe `testLeds()` path

### Changed
- **Protocol version upgraded to v5**: `CLIENT_PROTOCOL_VERSION` now 5 (was 4), matching OpenRGB 1.0+
- **Frame composition extracted to `EffectColorComputer.composeFrame()`**: Pure testable function for LED frame composition, used by LedCompositor.doRender()

### Added
- **4 new protocol parsing tests**: v0, v3 (brightness), v4 (segments), v5 (full) device payload parsing (total 158 tests)

## [1.2.0] - 2026-02-13

### Added
- **Test buttons for all color settings**: "Test" button next to each of 18 color pickers in app settings — sends a 2-second flash preview to the keyboard, then returns to idle color
- **Connection listeners in OpenRgbConnectionService**: `addConnectionListener()` / `removeConnectionListener()` API — listeners are notified after every successful connection

### Fixed
- **Key Groups not available after manual connect**: `refreshFromDevice()` was only called during auto-connect on startup. Now connection listeners automatically refresh keyboard layout and key groups on any successful connection
- **Lazy key group refresh in settings**: "Key Groups..." popup now attempts a refresh if groups are empty but connection is active, fixing the case when user connects manually before opening settings

## [1.1.0] - 2026-02-13

### Added
- **`skipCustomMode` setting**: New checkbox in Connection tab — skips `setCustomMode` (1100) before `updateLeds`. Fixes EVision Keyboard and similar devices that break when custom mode is activated via SDK

### Fixed
- **EVision Keyboard compatibility**: `setCustomMode` command causes EVision keyboards to go black and ignore subsequent LED updates. With `skipCustomMode` enabled, `updateLeds` is sent directly without mode switch, which works correctly

## [1.0.0] - 2026-02-13

### Changed
- **Settings UI redesigned to tabbed layout**: `JBTabbedPane` with 6 tabs replacing vertical scroll
  - **Connection**: Enable toggle, OpenRGB connection (status, host, port, device, auto-*)
  - **Effects**: Idle color, effect timing (duration, pulse speed), focus & shutdown settings
  - **Events**: Build (3 colors + LEDs), Execution (3 colors + LEDs), Test (3 colors + LEDs)
  - **Features**: Pomodoro timer, File Save Flash, Debugger Breakpoint — each with enable/colors/LEDs
  - **Notifications**: Indexing, Low Memory, TODO — each with enable/color/LEDs
  - **Devices**: Multi-device enable + device role table
- Tab panels delegate `apply()`/`reset()`/`isModified()` for correct DSL binding lifecycle

## [0.9.1] - 2026-02-13

### Added
- **Key Group Picker**: "Key Groups..." button next to all 9 LED indices fields in app settings and 2 fields in project settings
- **`showKeyGroupPopup()`**: JBPopupFactory popup listing available key groups with LED count, appends selected indices to text field
- **`ledIndicesRow()` helper**: Reusable DSL helper combining text field + key group picker button

### Changed
- All LED indices rows in `KgBoardConfigurable` now use `ledIndicesRow()` with key group picker
- Git and Analysis LED indices in `KgBoardProjectConfigurable` now have key group picker buttons

## [0.9.0] - 2026-02-13

### Added
- **Key Group System**: `KeyGroupDefinitions` with 11 built-in groups (f-row, number-row, arrows, nav-cluster, wasd, hjkl, modifiers-left/right, top-row, home-row, numpad)
- **KeyGroupService**: `PersistentStateComponent` resolving key names to LED indices via `KeyboardLayoutService`, supports user-defined custom groups
- **Key group refresh on startup**: `refreshFromDevice()` called in `KgBoardStartupActivity` after device connection
- **Unit tests** for `KeyGroupDefinitions` (8 tests, total 132)

## [0.8.2] - 2026-02-13

### Added
- **Unit tests for per-key settings**: 18 new tests — `parseLedIndices` (7 cases), `resolveTarget` (3 cases), default values (1), property accessors (5), integration (2). Total ~124 tests

## [0.8.1] - 2026-02-13

### Added
- **Per-key LED selection for FileSave**: Dual-path dispatch with `EFFECT_ID = "file-save"`
- **Per-key LED selection for DebuggerBreakpoint**: Target resolution via `resolveTarget(debugBreakpointLedIndices)`
- **Per-key LED selection for Pomodoro**: Target resolution in `startWorkPhase()`, `startBreakPhase()`, `flashTransition()`
- **LED indices UI fields** for File Save, Debugger Breakpoint, Pomodoro Timer settings sections

## [0.8.0] - 2026-02-13

### Added
- **Per-key LED selection for Build/Execution/Test**: Dual-path dispatch — empty LED indices use Phase 1 API (backward-compatible), configured indices use Phase 2 targeted API
- **Settings fields**: `buildLedIndices`, `executionLedIndices`, `testLedIndices`, `fileSaveLedIndices`, `debugBreakpointLedIndices`, `pomodoroLedIndices` in `KgBoardSettings.State`
- **`resolveTarget()` helper**: Converts LED indices list to `EffectTarget.AllLeds` or `EffectTarget.LedSet`
- **LED indices UI fields** in Build Events, Execution Events, Test Events settings sections
- **Stable EFFECT_ID** constants in `BuildEventListener`, `ExecutionEventListener`, `TestEventListener`

### Changed
- `parseLedIndices()` visibility changed to `internal` for testability

## [0.7.0] - 2026-02-13

### Added
- **Debugger Breakpoint Listener**: Flash on breakpoint hit (`sessionPaused`), cleanup on resume/stop. Uses `XDebuggerManagerListener` + `XDebugSessionListener`. `AtomicBoolean` disposed guard
- **Debugger Settings UI**: Enable checkbox and color picker in app settings
- **Plugin Marketplace Icon**: `pluginIcon.svg` (40x40) with keyboard + RGB glow design

### Changed
- Plugin description enhanced with full feature list, formatted HTML for JetBrains Marketplace

## [0.6.0] - 2026-02-13

### Added
- **RainbowEffect**: HSV hue rotation across LED indices with configurable speed. Creates a moving rainbow pattern across the keyboard
- **WaveEffect**: Sinusoidal brightness wave traveling across the keyboard with configurable speed and minimum brightness
- **FileSaveListener**: Brief white flash (300ms) on Ctrl+S / Cmd+S. Configurable via `fileSaveFlashEnabled` and `fileSaveFlashColor` in app settings
- **File Save Flash UI**: New settings section with enable checkbox and color picker
- Unit tests for Rainbow and Wave effects (8 new tests, total 106)

## [0.5.1] - 2026-02-13

### Added
- **Unit test infrastructure**: JUnit 5 + MockK dependencies, `useJUnitPlatform()` in Gradle
- **98 unit tests**: OpenRgbProtocol (23), EffectColorComputer (23), KeyboardLayoutService (19), KgBoardSettings (14), RgbEffect (10), EffectTarget (9)
- **EffectColorComputer**: Extracted `computeColors()`, `scaleBrightness()`, `interpolateColor()` from LedCompositor into standalone testable `object`

### Changed
- LedCompositor now delegates color computation to `EffectColorComputer`

## [0.5.0] - 2026-02-13

### Added
- **Multi-device rendering in LedCompositor**: When `multiDeviceEnabled`, the compositor sends role-appropriate frames to each device — `primary/mirror` get the full frame (adjusted for LED count), `ambient` receives idle color only, `indicator` receives idle + per-key effects only. 10ms inter-device delay prevents overwhelming OpenRGB
- **Device Config UI**: New table in "Multi-Device" settings section with columns: Enabled (checkbox), Name, Device Index, Role (dropdown: primary/mirror/ambient/indicator). Add/remove toolbar via `ToolbarDecorator`

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
