# Integrations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Получать достоверные Git/build facts и загружать core без обязательных Java/Git/SM plugins.

**Architecture:** Project-owned optional providers публикуют только domain facts с lifetime token. Git adapter выполняет локальный NUL-safe status; BuildView root observer становится единственным producer после доказанной сохранности существующего покрытия.

**Tech Stack:** SDK263 public BuildView API, Git4Idea binary command handler, Kotlin/JUnit6.1.3, Starter/Driver.

**Spec:** [Integrations design](../specs/2026-11-16-integrations-design.md), [общий план](2026-11-16-product-evolution.md). Requires A/B; profiles require C2.

## Global Constraints

- Core не импортирует классы этих plugins.
- По repository не более одного процесса и одного coalesced обновления.
- Existing polling interval сохраняется, нижняя граница2с.
- Таймаут одного status-запроса10с.
- Один visual producer; сопоставление по title, соседству событий или временному окну запрещено.
- IDE targets: IDEA263.7307.200, PyCharm263.7307.206, WebStorm263.7307.202; поддержка объявляется только после verifier и запуска actual IDE.

## Review Focus

- Unload provider при незавершённом callback не воскрешает facts/effects — C1.
- Newline/rename/NUL path не повреждает porcelain parser — C2.
- NoUpstream/Detached/Unborn/error не становятся зелёным Synced — C2.
- Завершение старого branch request не заменяет новый facts snapshot — C2.
- Дубликаты root/child, skipped и неизвестный finish не дают ложный success — C3.

---

## Карта файлов

`bridge/integration/` — core host/lease/interfaces. `bridge/git/` — optional Git parser,
command runner, provider; `bridge/tests/` — optional SM runner; `bridge/build/` —
canonical BuildView adapter и root tracker. Existing IdeOperationAdapter разделяется
на Execution/Debug core и SM provider, ProjectRuntimeService владеет host.
`runtime/EditorState.kt` теряет упрощённый GitFacts; facts находятся в `bridge/git/`
только если pure DTO вынесен в `runtime/GitState.kt` (никакого SDK-import).
Изменения plugin.xml/optional descriptors определяют реальные границы classloading.

## Task C1: Optional integration providers и lifecycle capabilities

**Files:** Create `bridge/integration/{IntegrationProvider,IntegrationHost}.kt`,
`runtime/CapabilityState.kt`, `bridge/tests/{SmTestIntegrationProvider,SmTestOutcomes}.kt`,
`bridge/{ExecutionAdapter,DebugAdapter}.kt`;
Modify `bridge/{IdeOperationAdapter,ProjectRuntimeService,GitStatusStartupActivity}.kt`,
`runtime/{RuntimeEvent,RuntimeReducer,RuntimeModel}.kt`,
`bridge/OperationIdentityRegistry.kt`,
`src/main/resources/META-INF/{plugin,kgboard-git}.xml`;
Create `src/main/resources/META-INF/kgboard-tests.xml`;
Modify `build.gradle.kts`, B3 capability UI/message bundles;
Test `bridge/integration/IntegrationHostTest.kt`, `bridge/OperationAdaptersTest.kt`.

**Interfaces:**

```kotlin
enum class CapabilityStatus { AVAILABLE, UNAVAILABLE, ERROR }
data class ProviderLease(val project: ProjectId, val capability: String, val generation: Long)
data class CapabilitySnapshot(val id: String, val status: CapabilityStatus, val reasonKey: String?)
data class IntegrationContext(val project: Project, val lease: ProviderLease,
    val binding: ProjectRuntimeBinding, val emit: (RuntimeEvent) -> Unit)
interface IntegrationProvider {
    val capabilityId: String
    fun create(context: IntegrationContext): Disposable
}
// RuntimeEvent.ProviderStarted(lease: ProviderLease)
// RuntimeEvent.ProviderStopped(lease: ProviderLease, reasonKey: String)
// RuntimeEvent.ProviderFact(lease: ProviderLease, fact: RuntimeEvent)
```

IntegrationContext may hold SDK objects but never enters runtime snapshots.
ProviderFact accepts only facts (Git/OperationChanged/availability), not commands
such as SettingsApplied/Connect/Shutdown. Host checks lease before emit; actor
checks lease again because callback may already be queued when provider is disposed.
Stable IDs: `git`, `tests.sm`, `build.roots`, `debug`; capability absence clears only
facts/operations owned by that lease. Execution/core subscriptions remain project-owned.

- [ ] **Step 1: Write failing tests.** Pure lease gate test, plus actual extension
lifecycle fixture using project Disposable:

```kotlin
@Test fun `late provider fact cannot restore stopped capability`() {
    RuntimeHarness().use { h ->
        h.project()
        val lease = ProviderLease(ProjectId("p"), "tests.sm", 1)
        h.send(RuntimeEvent.ProviderStarted(lease))
        h.send(RuntimeEvent.ProviderStopped(lease, "capability.unloaded"))
        val op = OperationState(OperationId("late"), OperationKind.TEST, OperationPhase.RUNNING)
        h.send(RuntimeEvent.ProviderFact(lease, RuntimeEvent.OperationChanged(ProjectId("p"), op)))
        assertTrue(h.snapshotForTest().projects.getValue(ProjectId("p")).operations.isEmpty())
    }
}
```

Register/remove fake providers through real extension area; assert dispose before
Unavailable notification, one subscription per project, no Project retained after
close. Move existing actual SM root/suite/unlaunched tests to optional fixture class
without weakening assertions. E3 boots core with Java/Git/SM disabled singly/together.

- [ ] **Step 2: RED.** Run `./gradlew test --tests '*IntegrationHostTest' --tests '*OperationAdaptersTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain`.
- [ ] **Step 3: Implement host and classloader separation.** Dynamic extension point:

```xml
<extensionPoints>
  <extensionPoint name="integrationProvider"
      interface="com.kgboard.rgb.bridge.integration.IntegrationProvider" dynamic="true"/>
</extensionPoints>
<depends optional="true" config-file="kgboard-tests.xml">com.intellij.platform.testRunner</depends>
<depends optional="true" config-file="kgboard-git.xml">Git4Idea</depends>
```

Optional descriptors register providers under `com.kgboard.rgb` namespace. Core
host uses ExtensionPointListener with project Disposable, creates fresh lease on
each add, invalidates lease, disposes adapter, then publishes Unavailable on remove.
Do not store removed extension instances. Split actual SM imports out of
IdeOperationAdapter; preserve SDK process/root correlation via OperationIdentityRegistry
and RUN-parent suppression. Move `outcome(SMRootTestProxy)` out of OperationIdentityRegistry into optional
SmTestOutcomes; retain pure testOutcome helper in core. Core Execution/Debug adapters use public platform APIs.
ProjectState stores capabilities by ID and current leases; track operation lease
ownership so unload clears its effects without touching another producer.
Audit all imports and binary verifier results before removing mandatory Java module;
retain only required platform/lang/xdebugger modules. Compile-only SDK plugin classpath
may remain for optional source compilation; packaging/descriptor must not require them.
No empty Java-specific adapter. Add meaningful Available/Unavailable/Error rows to B3.

- [ ] **Step 4: GREEN.** Repeat RED/full suite. Run E3 core-only launch and verifier;
inspect packaged constant pools: core package references no `git4idea`/SM classes.
Verify dynamic remove with queued callback and stale facts after project reopen.
- [ ] **Step 5: Commit.** Version/CHANGELOG/diff check; commit `feat: isolate optional IDE integrations behind project leases`.

## Task C2: Локальный Git status с точными ahead/behind/conflict

**Files:** Create `runtime/GitState.kt`,
`bridge/git/{PorcelainV2Parser,GitStatusRunner,GitRepositorySampler,GitIntegrationProvider}.kt`,
`runtime/GitIndicatorPolicy.kt`;
Replace internals of `bridge/{GitFactsAdapter,GitStatusListener}.kt` and existing
optional startup registration; Modify runtime Git events/policy and project rule UI;
Test `bridge/git/{PorcelainV2ParserTest,GitRepositorySamplerTest,GitRepositoryIntegrationTest}.kt`.

**Interfaces:** consumes C1 lease; produces pure types in runtime/GitState:

```kotlin
enum class HeadState { BRANCH, DETACHED, UNBORN, UNKNOWN }
enum class UpstreamState { PRESENT, NONE, UNKNOWN }
enum class RepositoryOperation { NONE, MERGE, REBASE, OTHER }
enum class Freshness { FRESH, UNKNOWN }
enum class GitIndicator { CONFLICT, DIRTY, DIVERGED, AHEAD, BEHIND, SYNCED,
    NO_UPSTREAM, DETACHED, UNBORN, UNKNOWN }
data class RepositoryFacts(val id: String, val headOid: String?, val branch: String?,
    val head: HeadState, val upstream: UpstreamState, val dirty: Boolean,
    val conflicts: Int, val ahead: Int?, val behind: Int?,
    val operation: RepositoryOperation, val freshness: Freshness, val sampledAtMs: Long)
data class RepoRevision(val repositoryId: String, val request: Long, val headRevision: Long)
data class GitSample(val revision: RepoRevision, val facts: RepositoryFacts, val errorKey: String?)
// PorcelainV2Parser.parse(bytes: ByteArray, repositoryId: String, nowMs: Long): RepositoryFacts
// GitStatusRunner.status(root: VirtualFile, cancelled: () -> Boolean): ByteArray
// GitRepositorySampler.invalidate(repositoryId: String): Unit; close(): Unit
// GitIndicatorPolicy.classify(facts: RepositoryFacts): GitIndicator
```

Parser never stores filenames in published DTO. GitChanged now carries list of
GitSample/RepositoryFacts with request revision; maintain one row per repository.
Branch/head invalidation increments `headRevision` before scheduling next request.

- [ ] **Step 1: Write failing tests.**

```kotlin
@Test fun `branch ab is directional and renamed newline path remains dirty`() {
    val oid = "a".repeat(40)
    val old = "b".repeat(40)
    val output = "# branch.oid $oid\u0000# branch.head main\u0000" +
        "# branch.upstream origin/main\u0000# branch.ab +2 -3\u0000" +
        "2 R. N... 100644 100644 100644 $oid $old R100 new\nname\u0000old name\u0000"
    val facts = PorcelainV2Parser.parse(output.toByteArray(), "repo", 0)
    assertEquals(2, facts.ahead)
    assertEquals(3, facts.behind)
    assertTrue(facts.dirty)
    assertEquals(GitIndicator.DIRTY, GitIndicatorPolicy.classify(facts))
    assertEquals(GitIndicator.DIVERGED, GitIndicatorPolicy.classify(facts.copy(dirty = false)))
}
```

Parameterize no upstream/detached/unborn/truncated/invalid status/error→never Synced.
Real temp repos use `git init --initial-branch=main`, local bare origin and two local
clones; fixture helper uses ProcessBuilder args, configures test identity per repo,
commits fixtures and closes every process. Generate ahead/behind/diverged/conflict,
untracked/rename/newline path without network. Sampler gated runner delays request1,
invalidate head, complete request2 then release1; assert only2 visible and max one
active process/one coalesced rerun. Dispose/timeout kills current process, no rerun.

- [ ] **Step 2: RED.** Run `./gradlew test --tests '*PorcelainV2ParserTest' --tests '*GitRepositorySamplerTest' --tests '*GitRepositoryIntegrationTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain`.
- [ ] **Step 3: Implement NUL-safe runner/parser.** Use SDK263 GitBinaryHandler,
not line-joined `GitCommandResult` output. Its public constructors/run and protected
`myProcess/startProcess` were checked with javap on installed SDK. Cancellation-aware
subclass closes startup race by checking cancellation before and after process creation:

```kotlin
class CancellableStatusHandler(project: Project, root: VirtualFile) :
    GitBinaryHandler(project, root, GitCommand.STATUS) {
    private val cancelled = AtomicBoolean()
    fun cancel() { cancelled.set(true); myProcess?.destroyForcibly() }
    override fun startProcess(): Process {
        check(!cancelled.get())
        val process = super.startProcess()
        if (cancelled.get()) process.destroyForcibly()
        return process
    }
}
val handler = CancellableStatusHandler(project, root)
handler.addParameters("--porcelain=v2", "--branch", "--untracked-files=normal", "-z")
```

Run in background with10s cancellation and project-owned scope, selected IDE Git
executable, no read lock/EDT. Preserve polling interval≥2s, one active/one pending per
repo. Parse NUL-delimited headers/records; record2 consumes extra original-name token;
ordinary/unmerged fixed prefix field counts use bounded splits preserving path bytes.
Reject malformed/truncated records to Unknown. Use GitRepository state for merge/rebase
separately from actual unmerged conflict count. Request completion checks lease,
repository existence, request/head revisions before dispatch. Errors replace stale
green with Unknown. Classification: conflict>dirty>diverged>ahead/behind>synced;
require branch+upstream+fresh+known zero counts for Synced. No counts aggregated across repos.
Add behind cyan#00BCD4/diverged orange#FF9100 rules and previews; preserve old colors.

- [ ] **Step 4: GREEN.** Repeat RED/full suite. Inspect actual status fixtures from
installed Git2.53.0. Assert no fetch/push invocation, output paths absent from diagnostics
export, and branch-change completion does not recolor old facts.
- [ ] **Step 5: Commit.** Version/CHANGELOG/diff check; commit `feat: report directional Git status from local porcelain snapshots`.

## Task C3: Canonical BuildView root lifecycle без потери покрытия

**Files:** Create `bridge/build/{BuildObservation,BuildRootTracker,BuildViewAdapter}.kt`;
Modify `bridge/{IdeOperationAdapter,ProjectRuntimeService}.kt`, integration host,
runtime diagnostics reason mapping; Test `bridge/build/BuildRootTrackerTest.kt`,
`bridge/OperationAdaptersTest.kt`, `src/integrationTest/kotlin/com/kgboard/rgb/BuildPipelinesUiTest.kt`;
Create local projects `src/integrationTest/resources/projects/{jps,gradle,maven}/`.

**Interfaces:** consumes OperationIdentityRegistry, C1 ProviderLease.

```kotlin
sealed interface BuildObservation {
    data class Started(val id: Any) : BuildObservation
    data class Finished(val id: Any, val outcome: OperationOutcome) : BuildObservation
}
// BuildRootTracker(emit: (OperationState) -> Unit)
// BuildRootTracker.accept(event: BuildObservation): Unit
// BuildViewAdapter(project: Project, context: IntegrationContext): Disposable
```

`Any` IDs remain adapter-local; domain operation IDs are scoped UUIDs, no SDK objects
in state. Tracker has per-id running/retired records and emits monotonic revisions.
Do not infer Build/Test/Run parent links without SDK identity/process links.

- [ ] **Step 1: Write failing tests and coverage observations.**

```kotlin
@Test fun `finish without start and duplicate terminal never invent success`() {
    val emitted = mutableListOf<OperationState>()
    val tracker = BuildRootTracker(emitted::add)
    val id = Any()
    tracker.accept(BuildObservation.Finished(id, OperationOutcome.SUCCESS))
    assertTrue(emitted.isEmpty())
    tracker.accept(BuildObservation.Started(id))
    tracker.accept(BuildObservation.Finished(id, OperationOutcome.NOT_EXECUTED))
    tracker.accept(BuildObservation.Finished(id, OperationOutcome.SUCCESS))
    assertEquals(2, emitted.size)
    assertEquals(OperationOutcome.NOT_EXECUTED, emitted.last().outcome)
}
```

Add parallel IDs, child events, FailureResult, cancellation, unknown result, start
failure, skipped; use actual SDK root/suite objects for SM results/unlaunched tests.
Before removing old ProjectTaskListener, instrument BuildView observer in tests only
and run local JPS/manual+automatic JPS, delegated Gradle, standalone Gradle task and
Maven goal via actual IDE. Record source root identity/start/finish/result for each;
assert one lifecycle/run and no compile-success signal for sync/import. Failing
coverage observation blocks replacement; it does not justify silently removing path.

- [ ] **Step 2: RED.** Run `./gradlew test --tests '*BuildRootTrackerTest' --tests '*OperationAdaptersTest' -PlocalPlatformPath=/home/dzerik/.cache/kgboard-sdk/2026.3/installation/idea-IU-263.7307.200 --console=plain`, then E3 integrationTest filtered to BuildPipelinesUiTest. New canonical pipeline assertions fail before implementation.
- [ ] **Step 3: Implement adapter and atomic cutover after observations.** Subscribe
through public `BuildViewManager.addListener(BuildProgressListener, Disposable)`.
Accept root StartBuildEvent/FinishBuildEvent only; ignore child IDs/parents.
Use SDK root ID equality within project/lifetime, retired IDs prevent replay.

```kotlin
when (event) {
    is StartBuildEvent -> if (event.parentId == null)
        tracker.accept(BuildObservation.Started(buildId))
    is FinishBuildEvent -> if (event.parentId == null) {
        val result = when (event.result) {
            is FailureResult -> OperationOutcome.FAILURE
            is SuccessResult -> OperationOutcome.SUCCESS
            is SkippedResult -> OperationOutcome.NOT_EXECUTED
            else -> OperationOutcome.NOT_EXECUTED
        }
        tracker.accept(BuildObservation.Finished(buildId, result))
    }
}
```

Confirmed SDK cancellation maps CANCELLED before generic FailureResult using actual
cancellation Throwable/result metadata; unknown does not map success. Tracker uses existing weak retired-key registry with a strong map only for live roots:

```kotlin
private val identities = OperationIdentityRegistry()
private val active = mutableMapOf<Any, OperationId>()
fun accept(event: BuildObservation) {
    when (event) {
        is BuildObservation.Started -> {
            if (event.id in active) return
            val id = identities.id(event.id) ?: return
            active[event.id] = id
            val revision = identities.nextRevision(id) ?: return
            emit(OperationState(id, OperationKind.BUILD, OperationPhase.RUNNING, revision = revision))
        }
        is BuildObservation.Finished -> {
            val id = active.remove(event.id) ?: return
            val revision = identities.nextRevision(id) ?: return
            emit(OperationState(id, OperationKind.BUILD, OperationPhase.TERMINATED,
                outcome = event.outcome, revision = revision))
            identities.release(id)
        }
    }
}
```

The adapter stamps changedAtMs from the runtime clock before emitting. Tracker accepts
one running and one terminal revision; provider disposal retires
live roots with NOT_EXECUTED and clears only its lease. Build capability is based
on provider availability, not a claim every external engine is tested. Remove old
ProjectTaskListener producer in the same change only after all required paths pass.
If BuildView lacks a required path, stop this cutover and record exact missing SDK
source for a design amendment; never run two uncoupled visual producers.

- [ ] **Step 4: GREEN.** Repeat RED, actual pipeline matrix, full suite and verifier.
Assert actual envelopes/decisions for success/failure/cancel/parallel roots; no green
for imports or discovered-but-not-launched tests. Preserve RUN-parent suppression.
- [ ] **Step 5: Commit.** Version/CHANGELOG/diff check; commit `feat: unify observed build roots across IDE build pipelines`.
