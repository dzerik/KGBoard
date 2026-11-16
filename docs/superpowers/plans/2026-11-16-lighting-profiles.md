# Lighting Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Предоставить три предсказуемых профиля и пользовательские варианты, меняющие только внешний вид сигналов.

**Architecture:** Whitelist appearance templates накладываются на draft текущей области. Каталог задаёт канал правила; renderer масштабирует цвет один раз и управляет анимацией независимо от фактов/дедлайнов.

**Tech Stack:** Kotlin/JDK25, SDK263 XML serializer/UI DSL, JUnit6.1.3.

**Spec:** [Lighting profiles design](../specs/2026-11-16-lighting-profiles-design.md), [общий план](2026-11-16-product-evolution.md). Requires A/B/C2 including new Git states.

## Global Constraints

- Встроенные IDs: minimal, debug, quiet.
- Два значения имеют диапазон0–100 и legacy-default100.
- Профиль не содержит host/port, auto-connect/reconnect, skipCustomMode, device bindings, multi-device topology, версии схемы, capabilities и историю diagnostics.
- Не меняет длительности/число циклов/включение Pomodoro, текущую фазу и таймер.
- Импорт/экспорт внешних файлов и обмен профилями через сеть в этот пакет не входят.
- Диапазон IDE остаётся `263.*`; новые runtime-библиотеки не нужны.

## Review Focus

- Apply project-профиля не пишет app config или Pomodoro state — D1/D3.
- Legacy100% Full сохраняет прежние actual frames — D2.
- Static убирает33ms loop, сохраняя deadlines и progress updates — D2.
- Esc отсутствует: проектный conflict/error target не расширяется на All — D1.
- Case-insensitive duplicate name/UUID persistence/Cancel не портят каталог — D3.

---

## Карта файлов

`profile/AppearanceProfile.kt` — whitelist DTOs; `BuiltinProfiles.kt` — immutable
versioned templates; `AppearanceProfileService.kt` — local XML persistence;
`ProfileApplication.kt` — pure draft diff/merge. `runtime/AppearancePolicy.kt` —
яркость/анимация; existing policy/effect math сохраняют единственный проход.
`settings/ui/ProfilePicker.kt` — draft-only actions и preview diff.
Изменения snapshots/schema добавляются в reflection/round-trip tests.

## Task D1: Appearance whitelist и точные встроенные шаблоны

**Files:** Create `profile/{AppearanceProfile,BuiltinProfiles,ProfileApplication}.kt`;
Modify `runtime/RuntimeSettings.kt`, `settings/SettingsDraft.kt`, serializer DTOs;
Test `profile/{BuiltinProfilesTest,ProfileApplicationTest}.kt`, `runtime/RuntimeSettingsTest.kt`.

**Interfaces:** consumes A2 rule map/catalogue, C2 Git rule IDs.

```kotlin
enum class AnimationPolicy { FULL, STATIC }
data class BrightnessSettings(val persistent: Int = 100, val notification: Int = 100)
data class AppearanceTemplate(val rules: Map<String, RuleAppearance>,
    val brightness: BrightnessSettings, val animation: AnimationPolicy)
data class AppearanceProfile(val id: String, val name: String, val schemaVersion: Int,
    val templates: Map<SettingsScope, AppearanceTemplate>)
data class AppearanceChange(val field: String, val before: String, val after: String)
// BuiltinProfiles.catalogVersion: Int = 1
// BuiltinProfiles.all: List<AppearanceProfile>
// ProfileApplication.app(draft: AppDraft, template: AppearanceTemplate): List<AppearanceChange>
// ProfileApplication.project(draft: ProjectDraft, template: AppearanceTemplate): List<AppearanceChange>
```

RuntimeSettings adds `brightness: BrightnessSettings = BrightnessSettings()` and
`animation: AnimationPolicy = FULL`; project snapshot has nullable overrides with
legacy-default null, so existing installs inherit application appearance. Applying a
project template sets project-only overrides; idle uses app values, each project-owned
layer uses its owner's overrides or app fallback. These settings never change active
project/facts. App/project templates are separate map entries; no cross-scope Apply.

- [ ] **Step 1: Write failing tests.** Assert every table cell from spec with explicit
expected values, not a snapshot produced by the implementation:

```kotlin
@Test fun `quiet is static failure-only and cannot widen missing escape`() {
    val profile = BuiltinProfiles.all.single { it.id == "quiet" }
    val app = profile.templates.getValue(SettingsScope.APP)
    assertEquals(BrightnessSettings(15,35), app.brightness)
    assertEquals(AnimationPolicy.STATIC, app.animation)
    assertFalse(app.rules.getValue("build.success").enabled)
    assertTrue(app.rules.getValue("build.failure").enabled)
    assertFalse(app.rules.getValue("test.progress").enabled)
    assertEquals(1200L, app.rules.getValue("build.failure").style.durationMs)
    val project = profile.templates.getValue(SettingsScope.PROJECT)
    val target = project.rules.getValue("git.conflict").target
    assertEquals(TargetSpec.NamedKeys(listOf("Esc")), target)
    val device = RgbDeviceInfo(1, "LED strip", "V", "", 1, listOf("LED 1"),
        zones = emptyList(), colors = emptyList())
    assertTrue(TargetResolver.resolve(target, device, emptyMap()).indices.isEmpty())
}
```

Add minimal25/60 Static1500ms; debug40/70 Full2000ms. Explicitly enumerate enabled
sets per scope from spec, assert all other known rule flags disabled except Pomodoro
visual rules, which retain existing enable behavior. Clone an app draft with custom
endpoint/bindings/focus/Pomodoro settings, apply template, assert excluded fields
identical. Project Apply asserts branch rules retained but visual flag disabled.

- [ ] **Step 2: RED.** Run `./gradlew test --tests '*BuiltinProfilesTest' --tests '*ProfileApplicationTest' --tests '*RuntimeSettingsTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain`.
- [ ] **Step 3: Implement immutable templates and whitelist application.** Define
all builtin profile values explicitly from spec table, using existing palette.
App signals targetAll; project analysis/conflict uses NamedKeys(Esc); other disabled
rules retain safe target. Do not replace entire RuntimeSettings via copyBean.

```kotlin
fun applyAppearance(target: MutableMap<String, RuleAppearance>, template: AppearanceTemplate) {
    template.rules.forEach { (id, appearance) ->
        if (ScenarioCatalog.byId(id) != null) target[id] = appearance.copy(
            target = appearance.target)
    }
}
```

Produce diff before draft mutation, restricted to rules/brightness/animation.
Profiles change per-state flags, not operation enable/fact collection; branch regex
data remains. Preserve Pomodoro phase, timing and enable fields. No automatic builtin
selection on upgrade. Unknown IDs remain persisted in profile records, absent from
executable template application. Add new fields to XML/runtime deep snapshots.

- [ ] **Step 4: GREEN.** Repeat RED and XML/reflection tests. Assert immutable builtin
collections cannot be modified through returned user copies; invalid brightness
rejects full Apply without partial changes.
- [ ] **Step 5: Commit.** Version/CHANGELOG/diff check; commit `feat: define scoped appearance profiles with safe field boundaries`.

## Task D2: Два канала яркости и Static animation policy

**Files:** Create `runtime/AppearancePolicy.kt`; Modify
`runtime/{LightingPolicy,OperationPolicy,IndicatorPolicy,PomodoroState}.kt`,
`diagnostics/DecisionComposer.kt`, effect color factory;
Test `runtime/AppearancePolicyTest.kt`, `runtime/{LightingPolicyTest,PomodoroStateTest}.kt`.

**Interfaces:** consumes D1 settings and A2 BrightnessChannel;
`AppearancePolicy.scale(color: Color, channel: BrightnessChannel,
brightness: BrightnessSettings): Color`;
`AppearancePolicy.effect(effect: RgbEffect, policy: AnimationPolicy): RgbEffect`;
`AppearancePolicy.animated(effect: RgbEffect, policy: AnimationPolicy): Boolean`.
B1 `LightingDecision` remains sole source for frame/reason. Preview scenario shares
the same layer treatment; DeviceTest retains fixed white30% independently.

- [ ] **Step 1: Write failing tests.**

```kotlin
@Test fun `channels scale once and full brightness preserves legacy color`() {
    val color = Color(200,100,40)
    assertEquals(color, AppearancePolicy.scale(color, BrightnessChannel.NOTIFICATION,
        BrightnessSettings()))
    assertEquals(Color(50,25,10), AppearancePolicy.scale(color,
        BrightnessChannel.PERSISTENT, BrightnessSettings(25,100)))
    assertEquals(Color.BLACK, AppearancePolicy.scale(color,
        BrightnessChannel.NOTIFICATION, BrightnessSettings(100,0)))
}
@Test fun `static pulse has no periodic wake`() {
    val effect = PulseEffect(Color.RED)
    assertFalse(AppearancePolicy.animated(effect, AnimationPolicy.STATIC))
    val reduced = AppearancePolicy.effect(effect, AnimationPolicy.STATIC)
    assertTrue(reduced is StaticEffect)
    assertEquals(Color.RED, (reduced as StaticEffect).color)
}
```

Runtime tests: Static flash lasts until original deadline without33ms wake; gradient
uses start color; progress update changes frame but no autonomous tick. Pomodoro
remaining UI/deadline still advances.100% Full matches previous frames for all effect
classes;0/25/100 both channels; focus dim multiplies already-scaled idle once; draft
scenario Preview equals normal rule intensity, DeviceTest stays77/77/77.

- [ ] **Step 2: RED.** Run `./gradlew test --tests '*AppearancePolicyTest' --tests '*LightingPolicyTest' --tests '*PomodoroStateTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain`.
- [ ] **Step 3: Implement transformation before composition.** Convert Pulse/Wave/
Flash to static base color preserving priority/target; Gradient to static start color;
Progress remains data-driven; Full returns original effect. Static Rainbow uses a
deterministic frozen t=0 palette through color computation, never an autonomous tick.

```kotlin
fun scale(color: Color, channel: BrightnessChannel, brightness: BrightnessSettings): Color {
    val percent = when (channel) {
        BrightnessChannel.PERSISTENT -> brightness.persistent
        BrightnessChannel.NOTIFICATION -> brightness.notification
    }
    require(percent in 0..100)
    return Color((color.red * percent / 100f).roundToInt(),
        (color.green * percent / 100f).roundToInt(),
        (color.blue * percent / 100f).roundToInt())
}
```

Scale computed colors once per layer before writing compositor pixels; do not scale
again in writer. Channel comes from ScenarioCatalog, not animated/static test.
Persistent covers idle/branch/long indicators, Notification terminal/save/memory/
transitions. Focus dim is extra factor on idle only as baseline policy requires.
Keep original terminal/Pomodoro deadlines even after effect becomes static; decide
next wake from visible Full animation plus meaningful deadlines/UI countdown.

- [ ] **Step 4: GREEN.** Repeat RED/full suite; use actual envelope assertions to
prove no double scaling and no continuous offers while only Static effects remain.
- [ ] **Step 5: Commit.** Version/CHANGELOG/diff check; commit `feat: apply brightness channels and static lighting mode`.

## Task D3: User profiles, diff picker и scope-safe Apply/Cancel

**Files:** Create `profile/AppearanceProfileService.kt`, `settings/ui/ProfilePicker.kt`;
Modify app/project Configurable editors/message bundles;
Test `profile/AppearanceProfilePersistenceTest.kt`, `settings/SettingsPersistencePlatformTest.kt`,
E3 actual UI profile scenarios; Modify README/CHANGELOG in same feature commit.

**Interfaces:** consumes D1 templates/D2 renderer.
`AppearanceProfileService.profiles(scope: SettingsScope): List<AppearanceProfile>`;
`save(profile: AppearanceProfile): Unit`, `delete(id: String): Unit`;
`ProfilePicker(scope: SettingsScope,
applyTemplate: (AppearanceTemplate) -> List<AppearanceChange>).createPanel(): JComponent`.
New user ID=`user:<UUID>`, schemaVersion1; builtin IDs immutable. User save operates
only current scope entry, no implicit overwrite of counterpart scope. Name uniqueness
is Locale.ROOT case-insensitive among profiles offering a template in that scope.

- [ ] **Step 1: Write failing tests.** XML serializer round-trip exact fields/IDs:

```kotlin
@Test fun `case insensitive duplicate names are rejected within scope`() {
    val service = AppearanceProfileService()
    val template = BuiltinProfiles.all.first().templates.getValue(SettingsScope.APP)
    service.save(AppearanceProfile("user:00000000-0000-4000-8000-000000000001", "My profile", 1,
        mapOf(SettingsScope.APP to template)))
    assertThrows<IllegalArgumentException> {
        service.save(AppearanceProfile("user:00000000-0000-4000-8000-000000000002", "MY PROFILE", 1,
            mapOf(SettingsScope.APP to template)))
    }
    assertEquals(1, service.profiles(SettingsScope.APP).count { it.id.startsWith("user:") })
}
```

Actual Configurable fixture: choose quiet→edit→Cancel leaves managed/runtime and
stored profile unchanged; choose→diff→Apply changes only current scope. Save as
copy, restart service through XML, rename, delete; builtin delete/overwrite rejects.
Manual appearance edit shows Custom; reconnect does not. Unknown stored IDs never
execute or widen target. Validate UUID format in production and keep the valid deterministic UUIDs above.

- [ ] **Step 2: RED.** Run `./gradlew test --tests '*AppearanceProfilePersistenceTest' --tests '*SettingsPersistencePlatformTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain`.
- [ ] **Step 3: Implement local store/picker.** Register `@Service(Service.Level.APP)` with
`@State(name = "KgBoardAppearanceProfiles", storages = [Storage("kgboard-profiles.xml")])`.
Mutable serializer DTO converts to immutable templates; do not serialize complete application config. Picker selection
changes only draft, shows exact field diff and scope. Save/rename/delete are staged
in Settings draft and commit with successful Apply; Cancel abandons them. Persistence
service provides explicit validated replace transaction so settings/profile changes
do not partially persist on validation error.

```kotlin
val template = selected.templates[scope] ?: return
val changes = applyTemplate(template)
val rows = changes.map { "${it.field}: ${it.before} → ${it.after}" }
changeList.setListData(rows.toTypedArray())
```

Application Configurable supplies `ProfileApplication.app` bound to its AppDraft;
Project Configurable supplies `ProfileApplication.project` bound to its ProjectDraft.
`changeList` is a JBList<String> created on EDT with the other picker components. Compare whitelist
appearance values for Custom marker, not network state. Builtins are read-only and
edit action creates UUID copy. No import/export or background network sync.

- [ ] **Step 4: GREEN.** Repeat RED/full suite/build/verifier and E3 profile UI flow
in actual themes; inspect frames/diffs and no writes to excluded fields.
- [ ] **Step 5: Commit.** Version/CHANGELOG/diff check; commit `feat: manage local lighting profiles through settings drafts`.
