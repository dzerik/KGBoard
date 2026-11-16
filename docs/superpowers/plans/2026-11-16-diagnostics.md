# Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Объяснять текущую композицию по каждому устройству и отличать намерение отправки от завершённой записи.

**Architecture:** Один проход renderer возвращает кадры и provenance LED. Writer возвращает revision-tagged receipts; delivery-only события обновляют наблюдаемое состояние без render. Нативный UI подписывается через coalesced lifetime-bound presenter.

**Tech Stack:** Kotlin/JDK25, SDK263 Swing/Tool Window/status widget, JUnit6.1.3.

**Spec:** [Diagnostics design](../specs/2026-11-16-diagnostics-design.md), [общие контракты](2026-11-16-product-evolution.md). Требует завершённый A.

## Global Constraints

- Delivery-only событие обновляет diagnostics snapshot без нового render, animation reschedule и повторной отправки.
- История хранит последние100 значимых переходов в памяти приложения.
- UI updates coalesce и происходят не чаще4раз/с.
- Никакие Project/PSI/Swing объекты не попадают в опубликованный snapshot.
- Диапазон IDE остаётся `263.*`; новые runtime-библиотеки не нужны.

## Review Focus

- Частичное перекрытие и одинаковый priority имеют тот же победитель, что actual pixels — B1.
- Coalesced кадр не становится Sent, старый session ack не меняет новую доставку — B2.
-1000 receipts не образуют frame→ack→frame loop и не меняют animation wake — B2.
- Закрытая панель не обрабатывает queued EDT update и не удерживает listener — B3.
- Diagnostics copy не содержит path/branch/file contents/serial/location — B3.

---

## Карта файлов

Новые `diagnostics/LightingDecision.kt`, `DecisionComposer.kt` — pure domain;
`DeliveryState.kt`, `DiagnosticsHistory.kt` — bounded state; `DiagnosticsPresenter.kt`
— частота/подписка, `DiagnosticsToolWindowFactory.kt` — Swing presentation.
`runtime/LightingPolicy.kt` остаётся владельцем правил/overlays, а не вторым renderer.
`FrameWriter.kt`, `RuntimePorts.kt`, actor/reducer/model получают envelope/receipt.
UI widget и plugin.xml регистрируют явные действия и Tool Window.
Все пути — по source roots в общем плане.

## Task B1: Единственное решение о кадре и видимости

**Files:** Create `diagnostics/{LightingDecision,DecisionComposer}.kt`;
Modify `runtime/{LightingPolicy,LightingRuntime,RuntimeModel}.kt`,
`effect/EffectColorComputer.kt`; Test `diagnostics/LightingDecisionTest.kt`,
`runtime/LightingPolicyTest.kt`, `effect/ComposeFrameTest.kt`.

**Interfaces:** consumes A1 binding/token and A2 catalogue/resolver.

```kotlin
data class RenderRevision(val value: Long)
data class FrameEnvelope(val token: InventoryToken, val bindingId: String,
    val deviceIndex: Int, val revision: RenderRevision, val colors: List<Color>)
enum class SuppressionReason { COVERED, BACKGROUND_PROJECT, DISABLED_RULE,
    UNASSIGNED, MISSING_DEVICE, AMBIGUOUS_DEVICE, STALE_LAYOUT, ROLE,
    PREVIEW, FOCUS_DIM, DISABLED, SHUTDOWN, UNKNOWN_FACTS }
data class RuleVisibility(val ruleId: String, val owner: ProjectId?, val source: String,
    val requested: List<Int>, val visible: List<Int>,
    val reasons: Set<SuppressionReason>)
data class DeviceDecision(val bindingId: String, val deviceIndex: Int?,
    val frame: FrameEnvelope?, val rules: List<RuleVisibility>, val idleReasonKey: String?)
data class LightingDecision(val revision: RenderRevision, val token: InventoryToken?,
    val devices: List<DeviceDecision>, val nextWakeAtMs: Long?)
data class CompositionLayer(val ruleId: String, val owner: ProjectId?, val priority: Int,
    val createdAtMs: Long, val source: String, val indices: List<Int>, val colors: List<Color>)
data class ComposedPixels(val colors: List<Color>, val visibility: List<RuleVisibility>)
```

`DecisionComposer.compose(idle: Color, count: Int, layers: List<CompositionLayer>): ComposedPixels`;
`LightingPolicy.decide(state: RuntimeState, nowMs: Long,
revision: RenderRevision): LightingDecision`. Remove standalone frame calculation:
legacy `frames` may temporarily delegate to decide for old tests, never recompute
separate explanation. Actor stores `lastDecision` and offers envelopes from it.

- [ ] **Step 1: Write failing tests.**

```kotlin
@Test fun `partial coverage agrees with actual pixels and stable tie break`() {
    val low = CompositionLayer("build.progress", ProjectId("p"), 1, 0, "a",
        listOf(0,1), listOf(Color.BLUE, Color.BLUE))
    val high = CompositionLayer("debug.breakpoint", ProjectId("p"), 2, 0, "b",
        listOf(1), listOf(Color.RED))
    val result = DecisionComposer.compose(Color.BLACK, 3, listOf(high, low))
    assertEquals(listOf(Color.BLUE, Color.RED, Color.BLACK), result.colors)
    assertEquals(listOf(0), result.visibility.single { it.ruleId == "build.progress" }.visible)
    assertEquals(setOf(SuppressionReason.COVERED),
        result.visibility.single { it.ruleId == "build.progress" }.reasons)
    val tie = high.copy(priority = 1, indices = listOf(0), createdAtMs = 0, source = "z")
    assertEquals(Color.RED, DecisionComposer.compose(Color.BLACK, 3, listOf(low, tie)).colors[0])
}
```

Parameterize normal/indicator/ambient/mirror roles; active/background project;
missing/ambiguous binding; Unassigned/Partial; Preview/dim/Disable/shutdown. Assert
both decision pixels and visibility, including an idle reason with no active rules.
Add unknown-analysis/indexing/notApplicable checks without fake clean label.

- [ ] **Step 2: RED.** Run `./gradlew test --tests '*LightingDecisionTest' --tests '*LightingPolicyTest' --tests '*ComposeFrameTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain`.
- [ ] **Step 3: Implement one composition pass.** Sort with existing ascending
priority/createdAt/project/source order; later paint wins. Track last painter per LED
during the same writes to color array, derive visibility from that owner array.
Keep duplicates keyed by source/owner internally, not rule ID alone (parallel builds
may share rule). Add suppression metadata for excluded layers before composition.

```kotlin
val ordered = layers.sortedWith(compareBy({ it.priority }, { it.createdAtMs },
    { it.owner?.value.orEmpty() }, { it.source }))
val pixels = MutableList(count) { idle }
val owners = IntArray(count) { -1 }
ordered.forEachIndexed { layerIndex, layer ->
    layer.indices.zip(layer.colors).forEach { (led, color) ->
        if (led in pixels.indices) { pixels[led] = color; owners[led] = layerIndex }
    }
}
```

Compute target/effect colors once. Represent preview/focus/disabled/shutdown as final
policy stage with explicit reasons. Device without resolved binding has explanation
but no frame. `nextWakeAtMs` derives from actually visible animated sources/deadlines,
so diagnostics cannot introduce animation ticks. Existing color math remains.

- [ ] **Step 4: GREEN.** Repeat RED and full suite. Compare baseline100% Full frames
from existing tests, then frame envelopes offered by RuntimeIntegrationTest for
parallel operations/two devices. Snapshot consistency alone is insufficient.
- [ ] **Step 5: Commit.** Version/CHANGELOG/diff check; stage B1 files and commit `feat: explain lighting through the compositor decision`.

## Task B2: Реальная доставка и ограниченная история без feedback loop

**Files:** Create `diagnostics/{DeliveryState,DiagnosticsHistory}.kt`;
Modify `runtime/{RuntimePorts,FrameWriter,LightingRuntime,RuntimeReducer,RuntimeEvent,RuntimeModel}.kt`;
Test `diagnostics/DeliveryStateTest.kt`, `runtime/{FrameWriterTest,RuntimeIntegrationTest}.kt`.

**Interfaces:** consumes B1 FrameEnvelope. Replace `FrameWriter.offer(session,
device,colors)` with `offer(frame: FrameEnvelope)`; control retirement frames also
carry envelopes and retain their original session/token until write finishes.

```kotlin
sealed interface DeliveryReceipt {
    val frame: FrameEnvelope
    data class Sent(override val frame: FrameEnvelope, val sentAtMs: Long) : DeliveryReceipt
    data class Failed(override val frame: FrameEnvelope, val reason: String) : DeliveryReceipt
    data class Cached(override val frame: FrameEnvelope,
        val sentRevision: RenderRevision, val sentAtMs: Long) : DeliveryReceipt
}
data class DeliveryState(val pending: RenderRevision? = null,
    val lastSent: RenderRevision? = null, val sentAtMs: Long? = null, val error: String? = null)
data class HistoryEntry(val key: String, val reasonKey: String, val firstSeenMs: Long,
    val lastSeenMs: Long, val count: Int)
data class DiagnosticsSnapshot(val decision: LightingDecision?,
    val delivery: Map<String, DeliveryState>, val history: List<HistoryEntry>)
// RuntimeEvent.DeliveryReported(receipt: DeliveryReceipt)
// DiagnosticsHistory.record(entry: HistoryEntry): Unit
// DiagnosticsHistory.entries(): List<HistoryEntry>; clear(): Unit
```

Actor increments render revision only on render-triggering events. Last successful
revision is monotonic within token/device; acknowledgement for an older revision
cannot clear a newer pending frame. Preview-only envelopes use binding ID
`preview:<owner>` and the captured inventory token, never a persistent assignment. Pending is set
on accepted offer, Sent only after successful `transport.write`. Cached references
last successful write in same token/device; a displaced pending envelope has no Sent.

- [ ] **Step 1: Write failing tests.** Extend RecordingFrameWriter to record envelopes
and expose `offers: Int`; preserve history helpers for existing tests. Add:

```kotlin
@Test fun `delivery reports never render or schedule another wake`() {
    RuntimeHarness().use { h ->
        h.project()
        val before = h.writer.offers
        val decision = requireNotNull(h.snapshotForTest().lastDecision)
        val frame = requireNotNull(decision.devices.first().frame)
        repeat(1000) { h.send(RuntimeEvent.DeliveryReported(DeliveryReceipt.Sent(frame, 10))) }
        assertEquals(before, h.writer.offers)
        assertEquals(decision.revision, h.snapshotForTest().lastDecision?.revision)
    }
}
```

Use existing gated transport to hold a write: assert Pending until latch release;
offer BLUE→RED(in-flight)→BLUE and inspect actual last write. Coalesce pending
versions and assert no invented receipt for dropped version. Report old token after
reconnect and assert current delivery unchanged; compare scheduler pending deadline
before/after receipts. Record1000 distinct significant events and assert100 retained;
repeat same error and assert count/firstSeen/lastSeen. Animation-only revisions add no entries.

- [ ] **Step 2: RED.** Run `./gradlew test --tests '*DeliveryStateTest' --tests '*FrameWriterTest' --tests '*RuntimeIntegrationTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain`.
- [ ] **Step 3: Implement receipt flow.** Writer cache stores successful envelope,
not only colors; dispatch receipt after network result. Cached success requires
same token/index and last successful content; in-flight color is not successful cache.
Use envelope token to reject inventory changes even within same connection session.
History records significant reason/owner/state transitions, not frame time/colors.

```kotlin
private fun process(event: RuntimeEvent) {
    if (event is RuntimeEvent.DeliveryReported) {
        state = reducer.reduceDelivery(state, event.receipt)
        subscriptions.forEach { notify(it) }
        return
    }
    processAndRender(event)
}
```

Extract existing normal path as `processAndRender(event)` without changing ordering.
Delivery branch neither cancels scheduled wake nor increments generation. Failed
write still emits TransportFailed through existing lifecycle path; receipt only
records failure. Bound last100 transitions, coalesce adjacent identical errors,
no disk sink. Clear History is a diagnostics-only event with same no-render rule.
Keep shutdown control retirement/queued-connect suppression/bounded close intact.

- [ ] **Step 4: GREEN.** Repeat RED, simulator blocked/reset tests, full suite. Assert
no receipt after actor shutdown resurrects subscriptions/session; final retirement
write completes or hits documented3s bound.
- [ ] **Step 5: Commit.** Version/CHANGELOG/diff check; commit `feat: track frame delivery without rerendering acknowledgements`.

## Task B3: Tool Window, компактный статус и безопасный Copy Diagnostics

**Files:** Create `diagnostics/{DiagnosticsPresenter,DiagnosticsExport,DiagnosticsToolWindowFactory}.kt`;
Modify `bridge/KgBoardStatusWidgetFactory.kt`, `src/main/resources/META-INF/plugin.xml`,
`settings/ui/ConnectionHeader.kt`, message bundles;
Test `diagnostics/{DiagnosticsPresenterTest,DiagnosticsExportTest}.kt` and E3 actual UI suite.

**Interfaces:** consumes runtime `subscribe`, `DiagnosticsSnapshot`; C1 later adds
capability rows using its CapabilitySnapshot without optional SDK imports.
`DiagnosticsPresenter(subscribe: ((RuntimeState) -> Unit) -> Cancellation,
schedule: (Long, () -> Unit) -> Cancellation, publish: (DiagnosticsView) -> Unit)`;
`start(): Unit`, `close(): Unit`. `DiagnosticsView(connectionLabel: String, deviceRows: List<String>,
ruleRows: List<String>, analysisRows: List<String>, errorRows: List<String>,
capabilityRows: List<String>)` is a data class with equality by contents.
`DiagnosticsExport.copyText(snapshot: DiagnosticsSnapshot,
versions: Map<String,String>, capabilityLabels: List<String>): String` is a whitelist.

- [ ] **Step 1: Write failing tests.** Define ManualUiQueue in test with scheduled
actions, `advanceBy` and cancellation; no real sleep.

```kotlin
@Test fun `export never copies private identifiers`() {
    val decision = LightingDecision(RenderRevision(1), null, emptyList(), null)
    val snapshot = DiagnosticsSnapshot(decision, emptyMap(), listOf(
        HistoryEntry("network", "transport.failed", 1, 2, 3)))
    val text = DiagnosticsExport.copyText(snapshot, mapOf("plugin" to "3.1.0"), emptyList())
    assertTrue(text.contains("transport.failed"))
    assertFalse(text.contains("serial"))
    assertFalse(text.contains("location"))
}
```

Also inject path/branch/serial-like strings into error reason input and assert only
safe reason code is exported; arbitrary Throwable.message is never copied by default.
Presenter tests open/close20times, count active subscriptions=0 after each close;
queue update then close and assert no publish. Burst1000 updates/second yields≤4
publishes/second and identical view yields zero extra. Dispose UI must leave runtime active.

- [ ] **Step 2: RED.** Run `./gradlew test --tests '*DiagnosticsPresenterTest' --tests '*DiagnosticsExportTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain`.
- [ ] **Step 3: Implement UI/lifetime.** Presenter keeps latest view and one scheduled
publish at≥250ms spacing, checks active generation again on EDT; close cancels both
subscription and pending callback. Tool Window uses JB panels/lists and explicit
Connect/Disconnect/Open Settings/Copy/Clear actions; rows cover current device/owner,
visible/overridden rules, last send, Unknown/Indexing/NotApplicable/Errors/Warnings/Clean.
Status bar stays `RGB <short state>`; click opens popup, not hidden toggle.

```kotlin
override fun close() {
    if (!active.compareAndSet(true, false)) return
    generation.incrementAndGet()
    subscription?.cancel()
    pending?.cancel()
}
```

Register ToolWindowFactory with platform parent Disposable; assign accessible names
and message bundle text. Copy exports versions/counters/revisions/reason keys only. Export reason codes must
belong to the fixed message-bundle reason-key set; any other string becomes
`diagnostics.unknown`, never an arbitrary Throwable.message. Metadata values and
raw error text are excluded from the export. Serial is collapsed in UI details.
Use one action implementation shared by widget, header and Tool Window.

- [ ] **Step 4: GREEN.** Repeat RED/full suite and E3 actual popup/Tool Window tests
including Light/Dark, keyboard focus and20lifecyclecycles. Inspect no extra frame
writes while Tool Window opens/closes or Copy/Clear runs.
- [ ] **Step 5: Commit.** Version/CHANGELOG/diff check; stage B3 files and UI tests;
commit `feat: add lighting diagnostics and explicit connection actions`.
