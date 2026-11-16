# Product Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Подтвердить поведение на настоящем TCP/IDE UI и корректно переживать сон, не выдавая непроведённые аппаратные проверки за успех.

**Architecture:** Test-only SDK simulator синхронизирует реальные transport writes. Два источника времени и resume fence защищают runtime. Версионированная evidence matrix связывает каждую заявленную возможность с логом/скриншотом и конкретным IDE/OS.

**Tech Stack:** JUnit6.1.3, ServerSocket/latches, SDK263 fixtures, official Starter/Driver/testIdeUi, Xvfb Linux.

**Spec:** [Product verification design](../specs/2026-11-16-product-verification-design.md), [общий маршрут](2026-11-16-product-evolution.md).

## Global Constraints

- SDK simulator находится только в test/integration tooling, не пакуется в plugin ZIP.
- В protocol0 нет vendor: fixtures должны воспроизводить реальный формат, а не копировать ошибку parser.
- Разрыв абсолютной разницы wall/monotonic elapsed более10000мс инициирует сверку перед следующим выводом.
- При отсутствии OS notifications и разрыве не более10000мс остаётся обычный inventory probe с периодом5с.
- Shutdown с активным Preview на основном и preview-only устройстве; deadline3с.
- Допустимые статусы: NotRun, Passed, Failed, Blocked.
- Публикация/tag/push требуют отдельного запроса пользователя.

## Review Focus

- Protocol0/truncated data и socket reset не дают ложного inventory — E1/A1.
- Закрытие во время блокированной записи укладывается в3с — E1/B2.
-8ч сна или часы назад не продлевают транзиенты/Pomodoro — E2.
- Настоящие темы/scale/keyboard focus отличаются от system-LAF fixture — E3.
- Отсутствие оборудования/OS/optional plugin честно отражено в отчёте — E3.

---

## Карта файлов и порядок

E1 выполняется до A1, не требует новых production DTOs. E2 после B2, до финальной
матрицы. E3 tooling создаётся в A5, проверки повторяются после B/C/D/E2.
`src/test/kotlin/com/kgboard/rgb/testing/` — TCP fixture server; production `runtime/`
— clock/resume policy; `src/integrationTest/` — official actual-IDE suite и локальные
projects; `docs/product-verification.md` — evidence report; `tools/verify-evidence.py`
— проверка структуры отчёта, не генератор фиктивных Passed.

## Task E1: Версионированный SDK simulator и настоящие транспортные гонки

**Files:** Create `src/test/kotlin/com/kgboard/rgb/testing/{ControllerFixture,OpenRgbSdkSimulator}.kt`,
`src/test/kotlin/com/kgboard/rgb/client/OpenRgbSimulatorTest.kt`;
Modify existing protocol/transport tests only to share independently encoded fixture.
No production change in this task; protocol0 client correction belongs to A1.

**Interfaces:** independent test DTO, no dependency on new A1 metadata fields:

```kotlin
data class ControllerFixtureSpec(val name: String = "Board", val vendor: String = "Vendor",
    val serial: String = "serial-1", val location: String = "usb1",
    val ledNames: List<String> = listOf("Esc", "A"),
    val displayNames: List<String> = emptyList())
// ControllerFixture.encode(version: Int, spec: ControllerFixtureSpec): ByteArray
data class RecordedWrite(val connection: Long, val device: Int, val colors: List<Color>)
class OpenRgbSdkSimulator(version: Int, controllers: List<ControllerFixtureSpec>) : AutoCloseable {
    val endpoint: Endpoint
    fun inventory(controllers: List<ControllerFixtureSpec>)
    fun blockWrites(): AutoCloseable
    fun awaitWrite(timeoutMs: Long = 2000): RecordedWrite
    fun resetConnection()
    override fun close()
}
```

Declarations above specify API, not an empty implementation to commit. Simulator
owns loopback ephemeral ServerSocket, accept/read worker, bounded event queue and
cleanup latch. `blockWrites` gates server processing; to force blocking local
transport I/O use existing gated RgbTransport too, since TCP buffering alone cannot
guarantee `write()` blocks. Keep evidence labels explicit.

- [ ] **Step 1: Write failing tests.**

```kotlin
@Test fun `simulator observes actual client frames and survives reset`() {
    OpenRgbSdkSimulator(5, listOf(ControllerFixtureSpec())).use { server ->
        val transport = OpenRgbTransport { false }
        transport.connect(server.endpoint)
        transport.write(0, listOf(Color.RED, Color.BLUE))
        assertEquals(listOf(Color.RED, Color.BLUE), server.awaitWrite().colors)
        server.resetConnection()
        assertThrows<Exception> { transport.probe() }
        transport.close()
    }
}
```

Fixture tests inspect known byte offsets/string lengths from official wire layout;
v0 excludes vendor, v1 includes it, v5 includes display-name suffix. Do not validate
encoder solely by round-tripping through production parser. A1 adds parse tests for
all versions0–5. Add bounded await/no-response, reconnect inventory reorder, same
LED count/different names, preview-only shutdown during gated RgbTransport write.

- [ ] **Step 2: RED.** Run `./gradlew test --tests '*OpenRgbSimulatorTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain`.
- [ ] **Step 3: Implement fixture and packet handling.** Write little-endian packet
header with magic ORGB, device/request/size; implement protocol request/version,
controller count/data, custom mode/update LEDs and inventory probe paths used by client.
Controller encoder follows OpenRGBSDK documented field gates, with zero modes/simple
zone defaults to keep fixture independently auditable. Read exactly declared bytes
with size cap; unknown packet closes client with recorded cause, never endless wait.

```kotlin
private fun utf8String(out: ByteArrayOutputStream, value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
    require(bytes.size <= 65535)
    out.write(bytes.size and 255)
    out.write((bytes.size ushr 8) and 255)
    out.write(bytes)
}
```

Support version0–5 layouts; server6 negotiation responds but encodes requested5.
Use CountDownLatch/BlockingQueue.poll(timeout), socket read timeouts and worker join
on close. No Thread.sleep in synchronization; reset uses SO_LINGER=0 to close active
socket. Close server/socket and release all gates in finally. Record metadata only
in test process, never production diagnostics. Retain successful-send cache regression.

- [ ] **Step 4: GREEN.** Repeat RED/full baseline suite; verify simulator classes
are absent from buildPlugin ZIP. Protocol0 parsing test becomes GREEN only in A1,
not by encoding a nonexistent vendor into v0.
- [ ] **Step 5: Commit.** PATCH bump/CHANGELOG/diff check; commit `test: add bounded OpenRGB SDK simulator and wire fixtures`.

## Task E2: Двойные часы и resume fence

**Files:** Create `runtime/{RuntimeTime,ResumeDetector}.kt`,
`bridge/ResumeSignalSource.kt`; Modify `runtime/{RuntimePorts,RuntimeModel,RuntimeEvent,RuntimeReducer,LightingRuntime,LightingRuntimeService,ConnectionController,PomodoroState,OperationPolicy,IndicatorPolicy,SettingsSessionState}.kt`,
Pomodoro facade/widget; Test `runtime/{RuntimeTimeTest,ResumeRuntimeTest,PomodoroStateTest,RuntimeHarness}.kt`.

**Interfaces:**

```kotlin
data class TimeSample(val monotonicMs: Long, val wallMs: Long)
data class RuntimeDeadline(val monotonicAtMs: Long, val wallAtMs: Long,
    val generation: Long) {
    fun remaining(now: TimeSample): Long = maxOf(0,
        minOf(safeDifference(monotonicAtMs, now.monotonicMs),
            safeDifference(wallAtMs, now.wallMs)))
    companion object {
        fun after(durationMs: Long, now: TimeSample, generation: Long) = RuntimeDeadline(
            Math.addExact(now.monotonicMs, durationMs),
            Math.addExact(now.wallMs, durationMs), generation)
    }
}
internal fun safeDifference(target: Long, now: Long): Long = try {
    Math.subtractExact(target, now)
} catch (_: ArithmeticException) {
    if (target >= now) Long.MAX_VALUE else Long.MIN_VALUE
}
fun interface RuntimeClock {
    fun sample(): TimeSample
    fun nowMs(): Long = sample().monotonicMs
}
// ResumeDetector.observe(previous: TimeSample, current: TimeSample): Boolean
// ResumeSignalSource.subscribe(resumed: () -> Unit): Cancellation
// RuntimeEvent.ResumeDetected; runtime lastTime: TimeSample
```

All duration-bearing records (layer expiry, operation terminal, save/memory/preview,
Pomodoro phase) get RuntimeDeadline; keep animation start monotonic. Retry/probe
schedule uses monotonic time but resume invalidates old scheduled generations.
`RuntimeReducer.reduce(state,event,now: TimeSample)` samples once/event. `LightingPolicy`
receives state's sample and computes wake at `now.monotonicMs + remaining`.

- [ ] **Step 1: Write failing tests.**

```kotlin
@Test fun `clock rollback cannot extend deadline and sleep expires it`() {
    val start = TimeSample(1000, 100_000)
    val deadline = RuntimeDeadline.after(10_000, start, 1)
    assertEquals(5_000L, deadline.remaining(TimeSample(6000, 1)))
    assertEquals(0L, deadline.remaining(TimeSample(1001, 100_000L + 8*60*60*1000L)))
}
@Test fun `resume gap threshold uses absolute wall monotonic divergence`() {
    val start = TimeSample(0,0)
    assertFalse(ResumeDetector.observe(start, TimeSample(1000,11_000)))
    assertTrue(ResumeDetector.observe(start, TimeSample(1000,11_001)))
    assertTrue(ResumeDetector.observe(start, TimeSample(11_001,0)))
}
```

Add harness independent wall/mono controls;8h sleep invalidates connection token and
mapping before any offer, rejects old callbacks, expires transient, advances Pomodoro
at most once from now. Stop/Skip before resume changes generation, no resurrection.
Manual Disconnect stays disconnected. Forward clock jump may expire phase and records
reason; backward jump never extends it. Unknown OS signal capability falls back to
gap detector and5s probe; ≤10000ms gap does not promise instant OS resume detection.

- [ ] **Step 2: RED.** Run `./gradlew test --tests '*RuntimeTimeTest' --tests '*ResumeRuntimeTest' --tests '*PomodoroStateTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain`.
- [ ] **Step 3: Implement clock/deadline cutover.** Update every RuntimeClock lambda
and ManualClock explicitly; no default wall clock in deterministic tests. Actor checks
gap before render-producing event reduction and performs one resume transition first:

```kotlin
fun observe(previous: TimeSample, current: TimeSample): Boolean {
    val monoElapsed = safeDifference(current.monotonicMs, previous.monotonicMs)
    val wallElapsed = safeDifference(current.wallMs, previous.wallMs)
    val gap = safeDifference(wallElapsed, monoElapsed)
    return gap > 10_000 || gap < -10_000
}
```

Resume increments connection/mapping/wake generations, drops stale inventory and
transients, cancels preview, reconnects only if connectionWanted and not manually
disconnected. No offer until new inventory/bindings resolve. Pomodoro expired phase
advances once, sets next deadline from current sample, not old deadline; generation
guards Stop/Skip. Use saturation/checks on time addition/subtraction at accepted
duration bounds; tests include near-Long limits rather than overflow→negative lifetime.
Bridge subscribes to a public OS resume event only where SDK/OS actually exposes one;
absence is an explicit capability with gap/probe fallback. Do not substitute power-save
mode changes for sleep or add unreviewed JNI/runtime dependency. Record platform
limitations in E3. Update diagnostics on clock jump without per-tick history entries.

- [ ] **Step 4: GREEN.** Repeat RED/full suite/simulator reset. Check actual final
frames after resume and no stale write in token fence. Native sleep behavior is
separate OS matrix evidence, not proved by manual clock test.
- [ ] **Step 5: Commit.** PATCH for runtime correctness, version/CHANGELOG/diff check;
commit `fix: reconcile lighting sessions and deadlines after resume`.

## Task E3: Actual UI, IDE matrix и проверяемый программный дистрибутив

**Files:** Modify `build.gradle.kts`, `.github/workflows/ci.yml`;
Create `src/integrationTest/kotlin/com/kgboard/rgb/{SettingsUiTest,DiagnosticsUiTest,ProfileUiTest,BuildPipelinesUiTest,IdeTestContext}.kt`,
local projects under `src/integrationTest/resources/projects/`,
`tools/verify-evidence.py`, `tools/tests/test_verify_evidence.py`,
`docs/product-verification.md`, `docs/assets/product-verification/`.
UI-tooling setup belongs to A5 deliverable, not a separate empty scaffolding commit.

**Interfaces:** `IdeTestContext` creates pinned IDE context from explicit local
distribution + isolated config/system dirs + local project; installs built ZIP;
provides named UI actions by accessible name and condition waits. It exposes no
production test backdoor/hidden RGB write API. Report schema has product/build/JBR/OS,
plugin/feature/command/status/evidence; helper validates rows without promoting status.

- [ ] **Step 1: Write failing tests and matrix rows.** Evidence checker test:

```python
import unittest
from tools.verify_evidence import validate_row

class EvidenceTest(unittest.TestCase):
    def test_passed_requires_versions_and_evidence(self):
        row = dict(product="IDEA", build="", jbr="25", os="Linux", plugin="3.1.0",
                   feature="settings", command="integrationTest", status="Passed", evidence=[])
        self.assertEqual(["Passed requires build and evidence"], validate_row(row))
```

Actual Starter/Driver smoke opens local project, waits for indexing with bounded
condition and opens KGBoard Settings via Find Action/accessible controls. Before A5
the new scenario editor/named target assertions must fail. Add action flows:
Connect→DeviceTest→target→Preview→Apply; Preview→Cancel/Disconnect/Disable;
two project windows/focus/modal Settings/last editor close; image/no editor/deleted
file/smart-dumb/stale analysis; parallel Run/Debug/Tests/Build; B3 popup/Tool Window;
D3 profile diff/Cancel/Apply. Assert visible states and simulator writes together.

- [ ] **Step 2: RED.** For checker run `python3 -m unittest discover -s tools/tests`;
for actual UI run `xvfb-run -a ./gradlew integrationTest --tests '*SettingsUiTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain` after creating registration below. Expect new UI behavior assertion failure, not missing task recorded as behavioral RED.
- [ ] **Step 3: Implement Starter tooling and explicit evidence.** Official Gradle2.x
configuration, retaining project JUnit6.1.3 BOM rather than copying old docs JUnit5:

```kotlin
val integrationTestSourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}
dependencies {
    intellijPlatform {
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Starter,
            configurationName = "integrationTestImplementation")
    }
}
val integrationTest by intellijPlatformTesting.testIdeUi.registering {
    task {
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
    }
}
```

Use official `Starter.newContext`, `TestCase`, `LocalProjectInfo`,
`PluginConfigurator`, `runIdeWithDriver/useDriverAndCloseIde`, xQuery accessible names
and condition waits. The setup uses a concrete local project:

```kotlin
val context = Starter.newContext("kgboard-settings", TestCase(
    IdeInfo.IdeaUltimate,
    LocalProjectInfo(Path.of("src/integrationTest/resources/projects/jps"))))
```

IdeTestContext configures this context to the explicit local distribution and
installs the built plugin before launch. Public action `KGBoard.OpenSettings` opens
the form; subsequent assertions locate the localized accessible scenario-list name.
 Resolve actual overloads from aligned Starter binaries/docs before
compiling; no arbitrary GitHub project clone and no fixed sleeps. Task pins local IDE
and ZIP path; validate product-info build before launch to prevent accidental latest.
UI test parameters are the Cartesian product of theme={Light,Dark},
scale={100,150,200}, viewport={900×700,1200×900}; include separate English/Russian
long-label runs. IdeTestContext reads `kgboard.ui.idePath` and requires it match
expected product-info build; the default is the verified local IU263 path, never latest.
UI cases set real Light/Dark and100/150/200% in separate isolated runs, two viewports
900×700 and1200×900; screenshots metadata includes theme/scale/build/JBR. Verify
keyboard-only focus traversal/accessibility, long labels and empty/error states.

Evidence validation is explicit and deterministic:

```python
def validate_row(row):
    if row["status"] not in {"NotRun", "Passed", "Failed", "Blocked"}:
        return ["Unknown status"]
    if row["status"] == "Passed" and (not row.get("build") or not row.get("evidence")):
        return ["Passed requires build and evidence"]
    return []
```

Use standard-library unittest; no pytest dependency. Extend checker to require all version/command fields, existing artifact
links and hardware model/OpenRGB version for Passed hardware rows. Pure data checker
never infers physical success from socket writes.

Matrix: Linux IDEA263.7307.200, WS263.7307.202, PyCharm263.7307.206. Cache pinned
distributions, read actual product-info codes (do not infer PyCharm product type from
release API alias), pass local IDE paths to verifier and Starter. Test optional Java/
Git/SM disabled singly/together; retain no missing-class/internal-API warnings. Actual
build pipelines required only where engine/provider is present; unavailable is labelled.
Windows/macOS rows remain NotRun unless executed there. Hardware rows remain Blocked
until model/OpenRGB version/selected endpoint/device are supplied. When available,
execute exact spec sequence and record observed physical result per step; no guessed
index selection. These gaps do not stop software fixes/build, but forbid support claims.

- [ ] **Step 4: GREEN and final packaging.** Run checker, full JUnit suite, all actual
UI suites, buildPlugin, verifyPluginConfiguration and per-IDE verifier. ZIP descriptor
version must equal gradle.properties and bounds263.*, no Starter/JUnit/MockK/simulator
classes in runtime. Record SHA256/size/path; preserve full logs and selected screenshots.
If a required software row is Failed/Blocked, fix or report exact blocker; do not mark
package complete just because archive builds. Update README/CHANGELOG with actual
verified capabilities, migration confirmation and named hardware/OS gaps.
- [ ] **Step 5: Commit.** Version/CHANGELOG/diff check; tooling belongs to A5 feature
commit; final evidence-only update uses PATCH and `test: record product verification and distribution evidence`.
Build final ZIP again after version metadata commit so archive version matches HEAD.
No publish/tag/push without explicit request.

## Основные источники

- [OpenRGB SDK wire layout](https://github.com/CalcProgrammer1/OpenRGB/blob/master/Documentation/OpenRGBSDK.md).
- [Official UI integration testing](https://plugins.jetbrains.com/docs/intellij/integration-tests-ui.html).
- [Starter/Gradle configuration](https://plugins.jetbrains.com/docs/intellij/integration-tests-intro.html).
- [Gradle testing tasks](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html#testideui).
