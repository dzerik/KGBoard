# Settings Experience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Настраивать устройства и сценарии без LED-индексов и без записи в случайное устройство после reconnect.

**Architecture:** Pure device/target resolvers отделяют сохранённое намерение от текущего inventory. Версионированная миграция даёт immutable rules; один редактор и preview используют эти правила в app/project scope.

**Tech Stack:** Kotlin2.4.21, JDK25, SDK263, JUnit6.1.3, UI DSL v2 и platform Swing.

**Spec:** [Settings experience design](../specs/2026-11-16-settings-experience-design.md), [общий маршрут и команды](2026-11-16-product-evolution.md).

## Global Constraints

- Root AGENTS.md остаётся без изменений по исходному указанию пользователя.
- Swing-компоненты создаются только в createPanel на EDT.
- Нет подстановки All при ошибке.
- Старые State fields и storage names сохраняются.
- Диапазон IDE остаётся `263.*`; Новые runtime-библиотеки не нужны; UI использует компоненты и темы IDEA.

## Review Focus

- Дублирующийся/fake serial и одинаковые metadata не выбирают первый индекс — A1.
- Одинаковый numLeds при перестановке имён инвалидирует raw target — A2/A3.
- Malformed legacy CSV сохраняется для исправления, не исполняется — A3.
- Click старого списка после reconnect не получает свежую revision — A4.
- Cancel/apply одной области не меняет другую; незавершённая ячейка не теряется — A5.

---

## Карта файлов

Пути раскрываются по [правилу source roots](2026-11-16-product-evolution.md#где-определены-общие-контракты).
`client/OpenRgbProtocol.kt` хранит controller metadata; новые `device/` и `target/`
содержат pure matching. `scenario/` — каталог и appearance DTO, без Swing.
`settings/SettingsMigration.kt` — единственная миграция, `settings/SettingsDraft.kt`
— изолированные черновики. `settings/ui/` — header, device/target/scenario editors.
Существующие Configurable становятся тонкими владельцами draft/lifetime.
Runtime model/reducer/policies/preview/keyboard mapping меняются в A3/A4 одним cutover.
Нельзя оставить idle/shutdown на legacy index после включения binding resolver.

## Task A1: Controller identity и разрешение устройств

**Files:** Modify `client/{OpenRgbProtocol,OpenRgbClient,OpenRgbTransport}.kt`; Create
`client/ControllerDataReader.kt`,
`device/{DeviceBinding,DeviceResolver,LayoutFingerprint}.kt`;
Test `client/{OpenRgbProtocolTest,ControllerMetadataTest,OpenRgbTransportTest}.kt`,
`device/DeviceResolverTest.kt`.
Зависимость: E1 даёт реальные versioned binary fixtures.

**Interfaces:** consumes `Endpoint`, `SessionId`, `RgbDeviceInfo`.
Produces следующие типы, добавляя metadata в конец `RgbDeviceInfo` с defaults:

```kotlin
data class InventoryToken(val session: SessionId, val revision: Long)
data class InventorySnapshot(val token: InventoryToken, val endpoint: Endpoint,
    val devices: List<RgbDeviceInfo>)
sealed interface DeviceSelector {
    data class Serial(val type: Int, val vendor: String, val serial: String) : DeviceSelector
    data class Port(val type: Int, val vendor: String, val name: String,
        val location: String) : DeviceSelector
    data class SessionOnly(val token: InventoryToken, val index: Int) : DeviceSelector
}
data class DeviceBinding(val id: String, val endpoint: Endpoint,
    val selector: DeviceSelector)
sealed interface DeviceResolution {
    data class Resolved(val index: Int, val fingerprint: String) : DeviceResolution
    data object Missing : DeviceResolution
    data object Ambiguous : DeviceResolution
    data object ConfirmationRequired : DeviceResolution
}
data class DeviceChoice(val token: InventoryToken, val index: Int,
    val fingerprint: String)
data class SerialKey(val type: Int, val vendor: String, val serial: String)
// RgbDeviceInfo: serial: String = "", location: String = "",
// displayNames: List<String> = emptyList(), version: String = ""
```

`DeviceResolver.resolve(binding, inventory, untrusted: Set<SerialKey>): DeviceResolution`;
`DeviceResolver.bind(id: String, choice: DeviceChoice, inventory: InventorySnapshot,
untrusted: Set<SerialKey>): DeviceBinding` rejects stale choice;
`LayoutFingerprint.of(device: RgbDeviceInfo): String` hashes ordered names/zones/matrix,
not colors. `DeviceResolver.duplicates(devices): Set<SerialKey>` persists observed
duplicates via A3, so a later partial inventory cannot silently promote an unsafe ID.

- [x] **Step 1: Write failing tests.** Add a test device factory inside `DeviceResolverTest`:

```kotlin
private fun device(serial: String, name: String = "Board", location: String = "usb1") =
    RgbDeviceInfo(type = 1, name = name, vendor = "Vendor", description = "",
        numLeds = 1, ledNames = listOf("Esc"), zones = emptyList(), colors = emptyList(),
        serial = serial, location = location)
@Test fun `duplicate serial blocks existing strong binding`() {
    val endpoint = Endpoint("localhost", 6742)
    val inventory = InventorySnapshot(InventoryToken(SessionId(1), 1), endpoint,
        listOf(device("same"), device("same", location = "usb2")))
    val binding = DeviceBinding("b", endpoint, DeviceSelector.Serial(1, "Vendor", "same"))
    assertEquals(DeviceResolution.Ambiguous,
        DeviceResolver.resolve(binding, inventory, emptySet()))
}
@Test fun `serial binding follows reorder and rename`() {
    val endpoint = Endpoint("localhost", 6742)
    val inventory = InventorySnapshot(InventoryToken(SessionId(2), 1), endpoint,
        listOf(device("other"), device("stable", name = "Renamed")))
    val binding = DeviceBinding("b", endpoint, DeviceSelector.Serial(1, "Vendor", "stable"))
    assertEquals(1, (DeviceResolver.resolve(binding, inventory, emptySet())
        as DeviceResolution.Resolved).index)
    assertEquals(DeviceResolution.ConfirmationRequired,
        DeviceResolver.resolve(binding.copy(endpoint = Endpoint("other", 6742)), inventory, emptySet()))
}
```

Add parameterized cases for blank/fake serial (`unknown`, `none`, `n/a`, all-zero),
two indistinguishable devices, location change and SessionOnly across token change.
For protocol0–5 use E1 `ControllerFixture.encode(version, ControllerFixtureSpec(serial = "serial-1", location = "usb1"))`; assert all
metadata after parse; truncate each fixture at every byte and expect bounded
`OpenRgbException`, never partial device acceptance.

- [x] **Step 2: RED.** Run `./gradlew test --tests '*DeviceResolverTest' --tests '*OpenRgbProtocolTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain`. New types/missing metadata must fail.
- [x] **Step 3: Implement parsing and matching.** Preserve serial/location/version;
vendor is read only for version≥1, display names only version≥5. Keep negotiation≤5.
Check lengths/counts against payload before allocating. Replace underflow/index
exceptions with protocol errors carrying field name, not arbitrary payload contents.

```kotlin
val vendor = if (protocolVersion >= 1) readString(payload) else ""
// At their existing positions in the controller layout:
val version = readString(payload)
val serial = readString(payload)
val location = readString(payload)
```

Matching first checks endpoint, then selector. Serial requires exactly one candidate
and a trusted serial. Port requires exactly one exact location/type/vendor/name.
SessionOnly requires identical token. Zero matches→Missing, multiple→Ambiguous.
`bind` checks token, bounds and fingerprint against the supplied inventory before
creating selector. Invalid serial uses Port, otherwise SessionOnly; never ordinal IDs.
Fingerprint uses SHA-256 with length-prefixed UTF-8 names and fixed-width integers,
including zone boundaries/matrix map. Transport inventory comparison includes these
fields but excludes colors. Keep payload-v0 tests independent of production encoder.

- [x] **Step 4: GREEN.** Repeat RED command; run E1 simulator version matrix and reset test. Assert equal fingerprint with changed colors and different fingerprint with swapped names at same LED count.
- [x] **Step 5: Commit.** Update version/CHANGELOG and `git diff --check` per common rules; stage only A1 files and version files. Commit `fix: preserve controller identity and reject ambiguous device matches`.

## Task A2: Typed targets, groups и единый каталог сценариев

**Files:** Create `target/{TargetSpec,TargetResolver,TargetRecord}.kt`, `layout/KeyNames.kt`,
`scenario/{ScenarioCatalog,ScenarioEffects,RuleAppearance}.kt`; Modify
`preset/{KeyGroupDefinitions,KeyGroupService}.kt`, `layout/KeyboardLayoutService.kt`;
Test `target/TargetResolverTest.kt`, `scenario/ScenarioCatalogTest.kt`,
`preset/KeyGroupServiceTest.kt`, `settings/SettingsPersistencePlatformTest.kt`.

**Interfaces:** consumes A1 fingerprint/device. Produces:

```kotlin
sealed interface TargetSpec {
    data object All : TargetSpec
    data object Unassigned : TargetSpec
    data class NamedKeys(val names: List<String>) : TargetSpec
    data class Group(val id: String) : TargetSpec
    data class RawIndices(val text: String, val fingerprint: String?) : TargetSpec
}
enum class ResolutionStatus { RESOLVED, PARTIAL, UNAVAILABLE, AMBIGUOUS }
data class TargetResolution(val indices: List<Int>, val missing: List<String>,
    val status: ResolutionStatus, val reason: String?)
data class GroupDefinition(val id: String, val name: String, val target: TargetSpec)
enum class SettingsScope { APP, PROJECT }
enum class BrightnessChannel { PERSISTENT, NOTIFICATION }
enum class EffectStyle { STATIC, PULSE, FLASH, PROGRESS, GRADIENT, RAINBOW, WAVE }
data class RuleStyle(val color: Color, val effect: EffectStyle,
    val durationMs: Long, val periodMs: Long)
data class RuleAppearance(val enabled: Boolean, val target: TargetSpec, val style: RuleStyle)
data class ScenarioDescriptor(val id: String, val group: String, val scope: SettingsScope,
    val labelKey: String, val channel: BrightnessChannel, val priority: Int)
```

`TargetResolver.resolve(target: TargetSpec, device: RgbDeviceInfo,
groups: Map<String, GroupDefinition>): TargetResolution`;
`ScenarioCatalog.rules: List<ScenarioDescriptor>` and `byId(id: String): ScenarioDescriptor?`.
`ScenarioEffects.create(ruleId: String, style: RuleStyle, target: EffectTarget,
progress: Float = 0f): RgbEffect` maps catalogue style/priority to existing effect classes.
Group IDs are `builtin:<existing-id>` or `user:<UUID>`; names are labels.
Rule IDs are explicit constants in catalogue, including build/test
progress/success/failure/cancelled/notExecuted; run/debug active/success/failure/
cancelled/notExecuted; debug.breakpoint; fileSave; indexing; lowMemory; todo;
pomodoro.work/break/longBreak/transition; git.conflict/dirty/ahead/behind/diverged/
synced/branch; analysis.error/warning/clean; shortcuts.debug/search.
Unavailable Git states get no fake color. Unknown future IDs persist but `byId` returns null.

- [x] **Step 1: Write failing tests.**

```kotlin
@Test fun `raw target is invalid after reorder with unchanged LED count`() {
    val device = RgbDeviceInfo(1, "Board", "V", "", 2, listOf("Esc", "A"),
        zones = emptyList(), colors = emptyList())
    val raw = TargetSpec.RawIndices("0", LayoutFingerprint.of(device))
    val swapped = device.copy(ledNames = listOf("A", "Esc"))
    assertEquals(emptyList<Int>(), TargetResolver.resolve(raw, swapped, emptyMap()).indices)
    assertEquals(listOf(1), TargetResolver.resolve(TargetSpec.NamedKeys(listOf("Esc")),
        swapped, emptyMap()).indices)
}
@Test fun `missing and ambiguous keys never become all`() {
    val device = RgbDeviceInfo(1, "Board", "V", "", 2, listOf("LEFT SHIFT", "LSHIFT"),
        zones = emptyList(), colors = emptyList())
    val absent = TargetResolver.resolve(TargetSpec.NamedKeys(listOf("Missing")), device, emptyMap())
    assertTrue(absent.indices.isEmpty())
    val ambiguous = TargetResolver.resolve(TargetSpec.NamedKeys(listOf("lshift")), device, emptyMap())
    assertTrue(ambiguous.indices.isEmpty())
}
@Test fun `catalog IDs are unique and progress flags independent of results`() {
    val ids = ScenarioCatalog.rules.map { it.id }
    assertEquals(ids.size, ids.toSet().size)
    assertNotNull(ScenarioCatalog.byId("build.progress"))
    assertNotNull(ScenarioCatalog.byId("build.failure"))
    assertNull(ScenarioCatalog.byId("future.rule"))
}
```

The alias test uses `lshift`: both LEFT SHIFT and LSHIFT normalize to LSHIFT.
Exact raw name wins; alias resolves only when unique. Parameterize
raw values `""`, `"-1"`, `"2147483648"`, `"0,,1"`, `"2"` for two LED device;
all produce empty indices and reason. Assert partial `Esc,Missing` returns Esc only,
deleted/cyclic groups are unavailable, rename preserves group ID and target.

- [x] **Step 2: RED.** Run `./gradlew test --tests '*TargetResolverTest' --tests '*ScenarioCatalogTest' --tests '*KeyGroupServiceTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain`.
- [x] **Step 3: Implement resolver and catalogue.** Use existing key-name aliases,
but index alias→list rather than overwriting collisions. Raw parsing delegates strict
`SettingsValidation.indices`, rejects empty, requires matching fingerprint. Named
keys collect only unique matches; missing yields Partial if any resolved, otherwise
Unavailable; collisions yield Ambiguous and list affected names, retaining unrelated
unique matches. Group recursion tracks visited IDs and caps traversal to group count.

```kotlin
val indices = requestedNames.flatMap { name ->
    val exact = device.ledNames.withIndex().filter { it.value == name }.map { it.index }
    if (exact.isNotEmpty()) exact.singleOrNull()?.let(::listOf).orEmpty()
    else aliases[normalize(name)]?.singleOrNull()?.let(::listOf).orEmpty()
}.distinct().sorted()
```

Persist group UUIDs and raw text without resolving at save time. Existing built-ins
retain IDs with namespace prefix. Catalogue assigns existing fixed priorities and
effect defaults; every production policy lookup and UI row will use it in A3/A5.
Implement ScenarioEffects with an exhaustive EffectStyle when, passing catalogue
priority and supplied EffectTarget to each existing effect constructor. Pulse uses
periodMs, Flash/Gradient durationMs, Progress supplied progress, Rainbow/Wave periodMs;
Gradient end color keeps the existing #FFD600 phase default.
Keep branch background separate from git status target. Add coverage assertion that
all known legacy feature/state combinations have exactly one catalogue entry.

- [x] **Step 4: GREEN.** Repeat RED command; run existing layout/preset/effect tests. Confirm no fake QWERTY map was introduced.
- [x] **Step 5: Commit.** Version/CHANGELOG + diff check; stage A2 files and commit `refactor: define scenario rules and typed LED targets`.

## Task A3: XML migration и атомарный runtime cutover

**Files:** Create `settings/{SettingsSchema,SettingsMigration,SettingsDraft}.kt`;
Modify `settings/{KgBoardSettings,KgBoardProjectSettings,SettingsValidation}.kt`,
`runtime/{RuntimeSettings,RuntimeModel,RuntimeEvent,RuntimeReducer,LightingRuntime,LightingPolicy,OperationPolicy,IndicatorPolicy,PomodoroState,LightingRuntimeService}.kt`,
`layout/KeyboardLayoutService.kt`, `bridge/ShortcutHighlightService.kt`;
Test `settings/SettingsMigrationTest.kt`, `runtime/{RuntimeSettingsTest,BindingPolicyTest}.kt`,
`settings/SettingsPersistencePlatformTest.kt`; preserve fixtures under `src/test/resources/settings/`.

**Interfaces:** consumes A1/A2. `RuntimeSettings` adds `schemaVersion: Int`,
`bindings: List<ConfiguredBinding>`, `rules: Map<String, RuleAppearance>`,
`untrustedSerials: Set<SerialKey>`; project snapshot adds `rules`/schemaVersion.
`ConfiguredBinding(binding: DeviceBinding, enabled: Boolean, role: String)` retains
legacy role meaning. `ConnectionState.Ready` adds `inventoryRevision: Long`.
`DeviceMappingRevision` adds inventory revision, binding ID, fingerprint (update all
constructors together; no default fresh revision attached to old indices).
`AppDraft.capture(state: KgBoardSettings.State): AppDraft`,
`ProjectDraft.capture(state: KgBoardProjectSettings.State): ProjectDraft`;
`toState()` returns detached state; `reset(state)` replaces draft contents, not managed state.
Capture/reset run migration on a detached copy before exposing rules to editors.
`SettingsMigration.app/project/groups` return detached migrated DTOs, never persist.

- [ ] **Step 1: Write failing tests.** Extend existing XML/round-trip reflection tests
to include every new field; use actual legacy resources, not newly synthesized state only.

```kotlin
@Test fun `blank migration preserves actual legacy policy`() {
    val source = KgBoardSettings.State()
    val migrated = SettingsMigration.app(source)
    val rules = RuntimeSettings.capture(migrated).rules
    assertEquals(TargetSpec.All, rules.getValue("build.progress").target)
    assertEquals(TargetSpec.All, rules.getValue("lowMemory").target)
    assertEquals(TargetSpec.Unassigned, rules.getValue("indexing").target)
    assertEquals(TargetSpec.Unassigned, rules.getValue("todo").target)
    assertEquals(migrated, SettingsMigration.app(migrated))
    assertEquals(0, source.schemaVersion)
}
@Test fun `legacy raw text is retained but cannot render without confirmation`() {
    val source = KgBoardSettings.State().apply { buildLedIndices = "0,,1" }
    val settings = RuntimeSettings.capture(SettingsMigration.app(source))
    assertEquals("0,,1", (settings.rules.getValue("build.progress").target
        as TargetSpec.RawIndices).text)
    assertTrue(settings.bindings.isEmpty())
}
```

The legacy field is `buildLedIndices` in current KgBoardSettings.State. Add project
blank cases GitStatus/analysis→Unassigned and branch→All. In RuntimeHarness bind
test devices explicitly; add no-bindings tests asserting zero offers for active,
idle, Disabled and shutdown. Reordered devices must send only to resolved serial;
two enabled roles resolving to the same device fail Apply with a field error.
Add stale shortcut mapping test with same LED count/new fingerprint.

- [ ] **Step 2: RED.** Run `./gradlew test --tests '*SettingsMigrationTest' --tests '*BindingPolicyTest' --tests '*RuntimeSettingsTest' --tests '*SettingsPersistencePlatformTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain`.
- [ ] **Step 3: Implement migration, snapshots and policy together.** New XML DTOs
are serializer-friendly mutable beans; convert to sealed domain targets at capture.
Retain all 51 app/19 project fields and numeric group fields as legacy projection;
add optional new lists/fields. Unknown rule/target IDs round-trip as opaque persisted
records, are absent from executable rules, and receive an unavailable label in UI.
Use schema1 for this extension, advance only in fully validated detached result
that is persisted by Apply; a load migration failure keeps original state untouched.

```kotlin
val executable = snapshot.rules.filter { (id, _) -> ScenarioCatalog.byId(id) != null }
val resolutions = snapshot.bindings.filter { it.enabled }.map { configured ->
    configured to DeviceResolver.resolve(configured.binding, inventory, snapshot.untrustedSerials)
}
val resolved = resolutions.mapNotNull { (configured, result) ->
    (result as? DeviceResolution.Resolved)?.let { configured to it }
}
```

EffectLayer gains optional `ruleId: String?`; built-in producers set stable catalogue
ID, preserving source key for parallel operations. Per-device layer preparation resolves
that rule target once and creates effect through ScenarioEffects. Generic internal
LayerChanged effects retain existing EffectTarget math but still require bound devices.
All render paths, including retirement frames in LightingRuntime, use `resolved`; never synthesize a
binding from saved index. Legacy index-only state displays confirmation-needed.
Inventory changes retire the connection session before any new offer; ready snapshots
receive a fresh token. Old ownedDevices cleanup must not address a now-different device
at the same index. Named targets rerun resolver per device; raw targets require explicit layout
confirmation. Rules gate individual states before making effects; no separate
UI-only flag. Mapping changes clear stale shortcuts before publishing new geometry.
Full validation precedes managed `loadState` and one SettingsApplied event; failure
persists nothing. Keep existing focus/ownership/terminal-deadline behavior.

- [ ] **Step 4: GREEN.** Repeat RED then full test suite. Assert old fixtures retain
colors/durations/auto-connect/skipCustomMode/branches/groups; repeated migration is
stable and failed persist does not expose upgraded schema. Set RuntimeHarness default to `RuntimeSettings(bindings = testBindings())`, with
`testBindings(): List<ConfiguredBinding>` selecting its two explicit test devices;
an explicitly supplied empty settings object must keep no bindings. Update RuntimeHarness
explicit bindings so baseline tests still exercise actual writes, not vacuous empty frames.
- [ ] **Step 5: Commit.** Bump MINOR for enabled binding/target migration, update
CHANGELOG with one-time device confirmation. Stage A3 files; commit `feat: apply versioned device bindings and scenario targets atomically`.

## Task A4: Предпросмотр draft-сценария с fencing и cleanup

**Files:** Modify `settings/SettingsPreviewSession.kt`,
`runtime/{SettingsSessionState,RuntimeEvent,RuntimeReducer,LightingPolicy}.kt`;
Create `settings/PreviewAvailability.kt`; Test
`settings/SettingsPreviewSessionTest.kt`, `runtime/RuntimeIntegrationTest.kt`.

**Interfaces:** consumes A1 choice/token and A2 appearance/catalog.

```kotlin
sealed interface PreviewContent {
    data object DeviceTest : PreviewContent
    data class Scenario(val ruleId: String, val appearance: RuleAppearance) : PreviewContent
}
data class PreviewRequest(val owner: Long, val choice: DeviceChoice,
    val content: PreviewContent)
data class PreviewAvailability(val allowed: Boolean, val reasonKey: String?)
// SettingsPreviewSession.preview(choice: DeviceChoice, content: PreviewContent): Unit
// PreviewAvailability.evaluate(state: RuntimeState, request: PreviewRequest): PreviewAvailability
```

Runtime accepts choice only from same inventory token/fingerprint and owned Settings
session. DeviceTest may use explicitly selected unpersisted device, only in that
scoped session; it cannot enable ordinary writes or create a persistent binding.

- [ ] **Step 1: Write failing tests.** Extend integration harness with captured choice
and live CoalescingFrameWriter, retaining existing blocking-write latch.

```kotlin
@Test fun `old rendered choice cannot preview after inventory revision changes`() {
    RuntimeHarness().use { h ->
        val events = SettingsPreviewSession(h.runtime)
        h.drain()
        val choice = DeviceChoice(InventoryToken(SessionId(1), 1), 0, "old-layout")
        events.preview(choice, PreviewContent.DeviceTest)
        h.drain()
        assertNull(h.snapshotForTest().preview)
        events.close()
    }
}
```

Pin successful DeviceTest frame to `Color(77,77,77)` for exactly2s, distinguish idle.
Pin scenario preview Pulse/Flash/targets to draft values, not managed snapshot.
Parameterize runtime disabled/disconnected/missing target/zero LED/stale token reasons.
Reuse existing in-flight preview retirement tests for Cancel/Apply/Disconnect/Disable,
device/endpoint switch and shutdown≤3s, including device absent from normal bindings.

- [ ] **Step 2: RED.** Run `./gradlew test --tests '*SettingsPreviewSessionTest' --tests '*RuntimeIntegrationTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain`.
- [ ] **Step 3: Implement scoped request.** Evaluate before accepting; never repair a
stale choice using current session/index. Scenario preview creates effect from draft
RuleStyle via same catalogue factory as ordinary render, lifetime capped by Settings
preview deadline. DeviceTest uses static white30%, duration2000ms. Publish rejection
reason for UI. Keep Begin/EndSettings and diagnostic Connect rollback semantics.

```kotlin
val ready = state.connection as? ConnectionState.Ready
val token = ready?.let { InventoryToken(it.session, it.inventoryRevision) }
if (token != request.choice.token) return PreviewAvailability(false, "preview.staleDevice")
if (!state.settings.enabled) return PreviewAvailability(false, "preview.applyEnableFirst")
```

Retire preview-only devices through writer control batches before release; preserve
return-to-base frames when one write is in flight. EndSettings is idempotent.

- [ ] **Step 4: GREEN.** Repeat RED and FrameWriterTest. Assert cleanup's last actual
transport frame is base composition, not only `preview == null`. No external OpenRGB
animation restoration claim.
- [ ] **Step 5: Commit.** Version/CHANGELOG + diff check; stage A4 files and commit `feat: preview draft scenarios with inventory revision guards`.

## Task A5: Нативные настройки с двумя scope и общим редактором

**Files:** Rewrite `settings/{KgBoardConfigurable,KgBoardProjectConfigurable}.kt`;
Create `settings/ui/{ConnectionHeader,DeviceSettingsPanel,ScenarioEditor,LedTargetEditor,PomodoroSettingsPanel,AdvancedSettingsPanel,BranchRulesEditor}.kt`,
`KgBoardBundle.kt`, `settings/OpenKgBoardSettingsAction.kt`,
`src/main/resources/META-INF/plugin.xml`, `src/main/resources/messages/{KgBoardBundle,KgBoardBundle_ru}.properties`;
Modify `preset/KeyGroupService.kt`; Test `settings/SettingsPersistencePlatformTest.kt`,
`settings/SettingsDraftTest.kt`; E3 creates actual UI tests and Gradle test tooling.

**Interfaces:** consumes AppDraft/ProjectDraft, catalogue/targets, A4 preview;
`ScenarioEditor(scope: SettingsScope, rules: MutableMap<String, RuleAppearance>,
onPreview: (String, RuleAppearance) -> Unit).createPanel(): JComponent`;
`LedTargetEditor.createPanel()/value(): TargetSpec`;
`ConnectionHeader.update(state: RuntimeState): Unit`;
Configurable lifetime captures fixed ProjectId and owns subscription + SettingsPreviewSession.

- [ ] **Step 1: Write failing tests.** Add detached draft/Cancel check:

```kotlin
@Test fun `editing application draft does not mutate managed state`() {
    val managed = KgBoardSettings.State()
    val original = RuntimeSettings.capture(managed)
    val draft = AppDraft.capture(managed)
    draft.rules["build.failure"] = draft.rules.getValue("build.failure")
        .copy(target = TargetSpec.NamedKeys(listOf("Esc")))
    assertEquals(original, RuntimeSettings.capture(managed))
    draft.reset(managed)
    assertEquals(original, RuntimeSettings.capture(draft.toState()))
}
```

Extend real Configurable fixture: createPanel on EDT, type malformed pending raw
cell, click Apply, expect ConfigurationException and unchanged managed+runtime;
correct it, Apply once, capture one snapshot; Cancel profile/target edits; switch
active project while form is open and assert original scope remains. Assert app
Apply leaves project XML unchanged and project Apply leaves app XML unchanged.
Group rename/delete/recreate must preserve UUID semantics; branch reorder affects
match precedence, invalid regex is shown before persist. Add resource-key parity
test for English/Russian and accessible names on editable controls.

- [ ] **Step 2: RED.** Run `./gradlew test --tests '*SettingsDraftTest' --tests '*SettingsPersistencePlatformTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain`.
- [ ] **Step 3: Implement UI over draft.** Constructors hold data only. Create all
components on EDT, using platform spacing/fonts/themes. Application navigation has
Connection/devices, Scenarios, Pomodoro, Advanced. Project navigation has Git/Analysis/
Shortcuts and explicit project title. Both scenario forms iterate same catalogue.

```kotlin
fun createPanel(): JComponent {
    ApplicationManager.getApplication().assertIsDispatchThread()
    return panel {
        row { label(KgBoardBundle.message("scope.application")) }
        row { cell(connectionHeader.createPanel()).align(AlignX.FILL) }
        row { cell(scenarioEditor.createPanel()).align(Align.FILL) }.resizableRow()
    }
}
```

Device roles choose actual inventory entries with captured tokens, use readable role
descriptions, show weak/session-only/missing/ambiguous status and rebind action.
Host/port/skipCustomMode live in expandable server/compatibility areas.
Scenario rows show independent state enable/color/target/seconds and Preview reason.
For an unresolved RawIndices target show explicit Confirm layout action; it captures
the displayed choice token/fingerprint only after strict range validation. Deleting
a user group through UI changes referencing draft targets to Unassigned with visible
reason; unknown externally stored group IDs remain preserved but unresolved.
Named LED list with search is always complete; optional matrix visualizes only
real device geometry. Group CRUD edits draft; built-ins immutable. Raw input stays
advanced. Branch editor supports up/down and regex test against entered/current branch.
Apply first stops pending table editors, validates full own-scope draft, persists once,
dispatches once, then marks baseline; reset preserves already-bound draft object.
dispose clears preview and cancels subscription before queued EDT callbacks can run.
Register a normal Tools/Find Action command `KGBoard.OpenSettings` implemented by
OpenKgBoardSettingsAction using ShowSettingsUtil and the existing Configurable ID.
It is reused by diagnostics; UI tests invoke this public user action.
Header diagnostics action is disabled with explanatory text until B3 is installed,
then opens Tool Window; no stub success action.

- [ ] **Step 4: GREEN.** Repeat RED, full test/buildPlugin, and E3 real Light/Dark
UI matrix (900×700/1200×900,100/150/200%, keyboard-only). Check empty/error/reconnect/
unassigned states, translation and live device list. Attach actual screenshots; fixture
system-LAF renders cannot satisfy this step. Verify plugin before calling A complete.
- [ ] **Step 5: Commit.** Version/CHANGELOG + diff check; stage A5 and E3 UI-tooling
files in the same deliverable commit, document migration/three-action setup in README.
Commit `feat: replace settings tabs with native scoped scenario editors`.
