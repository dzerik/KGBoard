# Решения при реализации

Полный журнал решений по этапам: исходные формулировки, основания и цена ошибки. Повторённые дословно решения объёма перечислены один раз. Заключительный обзор и решения по его результатам включены в последний раздел; замечаний Minor не было.

## 2026-11-16-product-evolution

1. Ruling: Continue in existing clean dedicated feature/phase4-settings-presets-keys checkout — user approved Native execution here; no concurrent implementation agents — cost if wrong: changes are visible in current feature checkout rather than separate linked worktree.

2. Ruling: task-start cannot parse alphanumeric Task E1/A1 headings — extract exact approved briefs into each plan workspace directly, use task-done with original IDs — cost if wrong: bookkeeping only; original plan text remains authoritative.

3. Ruling: User explicitly selected «Узкий и надёжный плагин IDE» after comparative research on2026-11-17; current scope retains approved A/B/C/E architecture/interfaces and defers D1-D3 plus ProfileUiTest, universal/hardware engines — scope amendment docs/superpowers/plans/2026-11-17-ide-focus-scope.md records the reduction; existingStaticeffect needs no profile subsystem — cost if wrong: profile/preset/brightness features ship in a later phase; IDE correctness work remains. This supersedes instructions to execute all17; now14 retained tasks, no new architecture approval inferred.


## 2026-11-16-settings-experience

1. Ruling: Correct real v0 controller request payload and vendor fixtures in A1 — official SDK requires zero request data forv0, prior fixture encoded nonexistent vendor — cost if wrong: older nonconforming test server expectations intentionally change.

2. Ruling: Bound packet payload at16MiB and controller count at65535 and isolate malformed-field parsing in a reader — prevents allocation/partial-data failures at socket boundary — cost if wrong: exceptionally large nonstandard controllers need an explicit limit revision.

3. Ruling: Protocol0 fallback only on timeout before any response byte — partial negotiation header remains connection failure — cost if wrong: a silent dead server takes existing5s read timeout before protocol0 probe fails.

4. Ruling: Preserve existing shortcuts.vcs and shortcuts.editing rule IDs in addition to debug/search — existing configurations have these contexts, spec requires preserving functionality — cost if wrong: two extra optional rows in scenario editor.

5. Ruling: Define XML TargetRecord in A2, consumed by A3 schema migration — keeps group target and scenario target codec single — cost if wrong: serialization boundary must be updated in both consumers together.

6. Ruling: Persist immutable original ruleRecords and expose cached executable rules as a computed map — lossless unknown/duplicate records and exact snapshot field coverage cannot be represented by an executable map alone — cost if wrong: one extra record-to-domain codec to maintain.

7. Ruling: Groups publish atomically with SettingsApplied into RuntimeState rather than pure policy calling KeyGroupService — same UI Apply owns app/group snapshots — cost if wrong: group edits require explicit snapshot publication.

8. Ruling: Add runtime epoch to InventoryToken and persist SessionOnly epoch — connection counter1 repeats after IDE/runtime restart, which otherwise falsely reconfirms a session-only device — cost if wrong: one-time device confirmation repeats conservatively across runtime restart.

9. Ruling: Binding records use the same lossless cached-view wrapper as rule records — otherwise opening a draft discarded duplicate/unavailable binding records — cost if wrong: field name bindingRecords is new schema storage, while public bindings remains a computed list. Regression opening-a-draft RED→GREEN.

10. Ruling: Shortcut rule target filters keymap-derived eligible keys; All means all eligible shortcut keys, then each device resolves codes independently — preserves existing hint behavior and mirror geometry — cost if wrong: UI must label this scope clearly rather than imply whole-device painting. Mirror regression RED→GREEN.

11. Ruling: Retain a small component-wise legacy editor bridge until A5 replaces forms — editing one old color must not reset a named target — cost if wrong: temporary adapter must be removed or justified once native forms ship.

12. Ruling: Preserve runtime facts when a visual rule is disabled, while global Disable clears transients — profiles change appearance, not operation facts — cost if wrong: re-enabling a rule can show a still-live fact within its original deadline.

13. Ruling: Register multiple settings owners with one shared connection baseline instead of replacing one scalar owner — App/Project Configurables can coexist, returning pages must retain preview authority — cost if wrong: temporary connection lease adoption/restoration requires explicit owner bookkeeping.

14. Ruling: Only APP SettingsCommitted updates connection baseline; PROJECT Apply clears only its own preview — prevents hidden cross-scope connection commit — cost if wrong: project-only diagnostics remain temporary until explicit manual Connect.

15. Ruling: Legacy UI clears every rendered selection on inventory token change until A5 handles stored-binding status — safest transitional behavior never attaches a fresh token to an old index — cost if wrong: an extra selection after reconnect.

16. Ruling: NativeUI uses platform named colors/fonts/spacing rather than frontend-design custom palette/ASCII wireframes — approvedspec requires IDEA themes and user requiresMermaid — cost if wrong: design distinction comes from scenario structure/status rather than custombranding.

17. Ruling: Starter263 uses its aligned JetBrains coroutinefork1.11.0-intellij-1 from official StarterPOM; exclude vanilla1.10.1 copied by currentintrodocs — observedactualUITestNoSuchMethodErrorBuildersKt.runBlockingKbeforeIDElaunch; forkownsrequiredABI — cost if wrong: UItoolingmusttrackSDK'sforkwhenupgrading. KeepJUnit6BOM. ThisinfraFAILisNOTbehavioralUIRED; actualnativeUIassertionsstillrequired.

18. Ruling: The first explicit app device selection creates a primary binding only in the detached draft — otherwise three-action setup followed byApplysavesnobindingandordinarylightingneverstarts — cost if wrong: selectingafirstdeviceintendspersistenceonApply;CancelstilldiscardsitandDeviceTestcreatesnobinding. ActualfixtureREDselectionexpected1draftbindinggot0; managedbindingsandwritesremainedempty (A5-selection-red.log).

19. Ruling: Inactive duplicate/opaque binding records remain saveable during unrelated edits and get one explicit UI repair/remove row per ID — A3 promises lossless inactive records, rejecting all Apply made recovery impossible — cost if wrong: malformed rows persist until explicit repair; runtime executable view still excludes duplicates. RED actual Configurable expected actionable row1 got0 (A5-inactive-bindings-red.log).

20. Ruling: Russian native UI remains explicitly NotRun until a compatible language pack is available — SDK263 LocalizationUtil ignores JVM user.language and accepts non-English locale only with a language pack; built-in languages are CJK, Russian Marketplace packs are third-party/paid; no fabricated locale screenshot or unrequested purchase — cost if wrong: Russian long-label layout not yet visually verified, despite EN/RU key parity coverage. Official sources https://www.jetbrains.com/help/idea/language-and-region.html and https://plugins.jetbrains.com/docs/intellij/providing-translations.html .

21. Ruling: A5 twelveviewportlayout evidence retains earlier3.3.0WIPZIPhash; final2workflowcases use currentc1be2ZIP — only later mainchange wasprojectisModifiedpendingeditor guard, coveredbyplatformRED/GREEN, not APP-layout code — cost if wrong: layoutregressionmisseduntilmandatoryfinalE3repeatsmatrixonfinalartifact.


## 2026-11-16-diagnostics

1. Ruling: B1 adds defaultFrameWriter.offer(FrameEnvelope) atRuntimePorts seam beforeB2 — actor mustactuallyofferrevision/taggeddecisionenvelopes; existingwritersdelegateoldofferuntilB2receipts — cost if wrong: oneadditiveoverload, nochangedI/Ooroldadaptercontracts.

2. Ruling: AddPARTIAL_TARGET/AMBIGUOUS_TARGETsuppressionenums — spec requiresexplainingpartialtargetsbutplannedreasonenumhasneither; validsubsetmaystillpaint — cost if wrong: twoadditionalreasonlabels inB3, neverfallbacktoAll.

3. Ruling: CompositionLayeraddsdefaultpolicyOverlay=false;Previewsetsittrueforfinalpaint — maxIntpriority/time hacks cannotguaranteePreviewwinsallvalidordinarylayers — cost if wrong: oneadditiveDTOflag, ordinaryorderingunchanged. PreviewtestnowusesordinaryInt.MAX_VALUEpriority.

4. Ruling: Publishone-timecurrent-sessionretirementenvelopesinsideactorlastDecision, and retainownershiponlyfornormalcurrentframes — otherwiseactualcleanupwriteswouldlackexplanation orcleanupwouldrepeatforever — cost if wrong: transientretirementrowvisibleindiagnosticsuntilnextrender;inflightCancel/devicechangescoveredbyexistingsuiteplusnewretirementtest. Distinctold-sessionfinalframesduringdisconnectremaincontrol-releaseoutput,notcurrent-ownedrender.

5. Ruling: FrameWriter.offer returns Boolean acceptance; delivery key combines binding UUID and device index — actor must set Pending only for accepted work, and one binding may retire old index while painting new index in the same revision — cost if wrong: internal port signature/key adjustment; no persisted schema or SDK protocol change.

6. Ruling: DeliveryState adds offered revision separate from pending — after clearing Pending, actor still needs an upper bound to reject invented future acknowledgements while accepting late older actual writes — cost if wrong: one additional in-memory diagnostics field, no persistence.

7. Ruling: Keep plugin minor version for B2 internal port replacement — FrameWriter is plugin implementation infrastructure, not an advertised extension API; stored configuration, user functionality and SDK wire protocol remain compatible — cost if wrong: external unadvertised consumers of implementation classes would need adaptation.

8. Ruling: Control cleanup failure emits Failed receipt but does not prevent close/new connection — old transport is already being retired; recovery must not be blocked by its failure — cost if wrong: shutdown/endpoint cleanup is best effort, never proof restored hardware animation.

9. Ruling: Add detached project displayName to ProjectOpened/ProjectState — opaque runtime UUID alone cannot identify owning project to the user; only String is published, exports omit it — cost if wrong: project name appears locally in UI, no project object/path/export addition.

10. Ruling: Settings Diagnostics uses a native child dialog containing the same DiagnosticsPanel as Tool Window — Settings is modal, activating a window behind it makes the action unusable; shared presenter/actions/data remain single-source — cost if wrong: one additional native container, lifetime owned/disposed by DialogWrapper, no draft commit or Settings close.

11. Ruling: Generate kgboard-build.properties during processResources for runtime version — SDK263 lookup APIs examined are Internal, and diagnostics must report exact artifact version without unstable API — cost if wrong: resource generation must stay in build; native Copy assertion cross-checks ZIP filename version.


## 2026-11-16-integrations

1. Ruling: Add provider generation tombstones and per-operation ownership to ProjectState — removed leases must not be restarted by stale Started, and replacement must clear only its former facts — cost if wrong: extra in-memory maps bounded by project/provider/active-operation lifetime, no persistence.

2. Ruling: SM roots receive weak per-provider alias identities while process/debug handlers retain the shared registry — releasing an unloaded root's old identity must not prevent reattaching the same still-running SDK root, nor leak retired active IDs — cost if wrong: reattached tests get a new operation ID; RUN-parent mapping still uses SDK handler identity, never title/time heuristics.

3. Ruling: Retain compile-time Java/Git/SM SDK dependencies for optional adapter sources, remove mandatory Java/SM runtime dependencies only after core classloading/import gate passes — optional descriptors control actual loading; no empty Java adapter added — cost if wrong: actual core-only boot/verifier must catch any transitive runtime linkage before commit.

4. Ruling: Provider factories run outside Host gate; synchronous startup facts are coalesced per operation/Git until successful creation, failures drop them — prevents a failed integration briefly lighting a test/run signal — cost if wrong: a lifecycle already completed before activation is not replayed as terminal success; existing live facts are reconciled normally.

5. Ruling: Accept a newer ProviderStopped generation before Started and retain its tombstone — initialization can fail or unload before lease activation; actor must publish Error/Unavailable atomically and reject late activation — cost if wrong: trusted lifecycle port can invalidate an older provider with a newer stop; provider emit whitelist forbids forging lifecycle commands.

6. Ruling: Dispose adapters outside Host lock after invalidating lease, and attempt all disposals even if one throws — prevents source/publisher lock inversion and leaves other integrations clean — cost if wrong: disposal may overlap callback return, but both host and actor fences reject its facts.

7. Ruling: Substitute actual WebStorm/PyCharm no-Java boots for impossible essential-Java-disabled IDEA case; retain IDEA no-Git/no-SM/together and record essential restriction Blocked — exact263IDEAmetadatarefusesdisable — cost if wrong: no claim of unsupported IDEA-without-Java product mode; no-Java support depends on actual products that permit/omit it.

8. Ruling: Use a child CoroutineScope for dynamic extension listener registration — Disposable overload isdeprecatedin263; parentprojectscopeprovidedbyservice, standalonehostsownchildjob; cancelbeforeadapterteardown — cost if wrong: one childSupervisorJob perhost, no new runtime dependency, tests/verifier validate lifetime.

9. Ruling: Package Git adapters in a Gradle optional content module with a dependency on the core plugin and Git4Idea, retaining classic core and SM descriptor — pinned263 loader promotes the legacy optional Git alias to strict main dependency; module confines dependency failure to Git — cost if wrong: extra packaged module and experimental modular format support risk, gated by all three actual IDE boots and verifier before completion. Build first succeeded; native no-Git regression running.

10. Ruling: Mark WS/PY no-SM absence scenario platform-Blocked and test mandatory-SM preservation explicitly while disabling all removable integrations — native product language backend requires SM; never override essential guards or label loaded provider Unavailable — cost if wrong: WS/PY do not claim SM-absent product configuration; true absent-SM classloading is proven on IDEA plus denied-loader and EP removal fixtures. Known pinned constraint is asserted, not inferred from a runtime failure/skip.

11. Ruling: Relocate finished task-owned UI caches/output to /var/tmp and keep original paths as symlinks — home volume approached <2GB during multi-product matrix — cost if wrong: local test artifacts depend on task-owned /var/tmp directory, all logs/evidence remain preserved and packaged code is unaffected. No active IDE output was moved.

12. Ruling: Keep one active status process until it returns; exercise delayed old completion delivery after a newer head generation rather than starting two processes concurrently — plan's request2-before-release1 wording conflicts with explicit one-process constraint — cost if wrong: a cancelled/nonresponsive process delays fresh output until cancellation/10s timeout, while Unknown replaces stale green immediately.

13. Ruling: Put pure parser/sampler in core bridge/git and SDK process/provider in the existing optional Gradle Git module — preserves SDK-free core and makes scheduling/parser fixtures independent of IDE classloading — cost if wrong: core contains pure Git domain logic, but no Git4Idea imports/signatures; actual module/three-product verifier remains a gate.

14. Ruling: Retain global Git batch revision plus per-repository request/head revisions — coalesced snapshots need repository removal and actor rejection of already-queued old-head completions — cost if wrong: additional bounded immutable per-repository metadata; no raw SDK objects/filenames or persisted state.

15. Ruling: Use an independent atomic process handoff slot rather than reading GitHandler.myProcess for cancellation — pinned GitBinaryHandler.startProcess returns createProcess before GitHandler assigns myProcess, leaving the plan snippet's final-check/assignment gap — cost if wrong: one AtomicReference per query; cancellation kills immediately before/after handoff, verified by regression and SDK fixture.

16. Ruling: Require captured process exit code zero and recheck cancellation after GitBinaryHandler.run — SDK can return valid-looking stdout after failed or killed command with empty stderr — cost if wrong: nonzero responses become fixed Unknown rather than partial success; no raw stderr/path enters runtime/export.

17. Ruling: Select the same repository for branch preview and lighting — preview previously selected first repository while status selected worst severity — cost if wrong: branch background follows worst-severity repository; ties retain repository order, counts never sum.

18. Ruling: Use public BuildViewManager/BuildProgressObservable hook, not BuildProgressListenerRegistrar — registrar is annotated ApiStatus.Internal in pinned263 SDK — cost if wrong: BuildViewManager may lack a pipeline; actual coverage matrix must block cutover rather than add private hooks or guess correlations.

19. Ruling: Amend C3 to explicit BuildView roots plus a Java-optional automatic-JPS activity provider using public BuildManagerListener UUIDs — pinnedSDK lacks automatic BVM roots and no public identity joins UUID lifecycle to compiler-result callback — cost if wrong: automatic JPS terminal uses NOT_EXECUTED/unknown result, never fabricated Success/Failure; UI labels activity-only and no success promise. No title/time/order matching. Main BVM provider never runs alongside legacy ProjectTask visual producer after cutover; automatic provider filters exact SDK automatic flag and has disjoint observed pipeline. Actual manual/external coverage still gates cutover.

20. Ruling: Observe both public BuildViewManager and ExternalSystemRunConfigurationViewManager through one tracker keyed by exact SDK root ID — Maven run configurations use second manager; registrarInternalunnecessary — cost if wrong: duplicate forwarded SDK IDs must be idempotent, and each actual pipeline remains native-gated; no engine title/path/time matching.

21. Ruling: Accept the public Experimental BuildProgressObservable hook within the pinned263 compatibility range — no stable public alternative covers both managers, Internal registrar is excluded — cost if wrong: a future263 SDK may require adapter changes and repeat verifier/native matrix; no compatibility beyond263.* is declared.

22. Ruling: Depend explicitly on public intellij.java.compiler.impl only inside the optional JPS content module — actual listener class belongs there and native RED→GREEN confirms Java plugin dependency alone cannot deliver the provider — cost if wrong: renamed/unavailable SDK module disables this optional activity capability; core remains available, and supported SDK stays263.*.


## 2026-11-16-product-verification

1. Ruling: Add blocked-write preview-only shutdown regression to existing RuntimeIntegrationTest rather than a second harness — exercises actual actor+writer and preserves3s close barrier — cost if wrong: does not replace native OS/service shutdown evidence in E3.

2. Ruling: Evidence checker has importable tools/verify_evidence.py plus thin requested tools/verify-evidence.py CLI — plan imports underscore but names hyphen file, Python cannot import hyphen — cost if wrong: two tiny entry files instead of one; logic remains single-source.

3. Ruling: Use public JDK25 Desktop SystemSleepListener with actual APP_EVENT_SYSTEM_SLEEP capability, not an IntelliJ internal listener — pinned263 jars expose power-save toggles but no suitable documented cross-platform sleep topic; JDK public API is present and capability-gated — cost if wrong: unsupported toolkit uses explicit gap/probe fallback and cannot promise instant short-sleep detection. Sources https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/Desktop.html and Desktop.Action.html; Context7 SDK listener query provided no sleep topic. Physical sleep still NotRun.

4. Ruling: Retain nullable monotonic timestamp projections and explicit Long→TimeSample(now,now) overloads for pure existing fixtures; all runtime-created duration records additionally capture immutable dual-clock deadline — avoids wide unrelated bridge/API break while runtime uses paired clocks everywhere — cost if wrong: external code constructing records directly receives legacy monotonic fallback until entering actor.

5. Ruling: Capture terminal/save/memory deadline duration at event acceptance — dual-clock expiry must have one fixed start and duration; later visual settings do not reset event lifetime — cost if wrong: changing duration applies to the next event, rather than retroactively extending an accepted one.

6. Ruling: Resume drops transient output without retirement writes to the stale inventory; already in-flight transport I/O cannot be recalled — original-token pending work is cleared by priority reconnect, no new offer occurs until inventory resolves — cost if wrong: an already-started write can finish before reconnect; real hardware acknowledgement is not available.

7. Ruling: Use the documented extension flag to disable all searchable-options tasks instead of creating missing generated directories — Gradle task-only disable left dependent input validation active — cost if wrong: searchable settings index remains intentionally absent, as before; native Settings access is verified independently.

8. Ruling: Run verifyPluginProjectConfiguration in the pinned IntelliJ Platform Gradle Plugin2.20.1 instead of the plan's legacy verifyPluginConfiguration name — current official task and actual Gradle output use the project-qualified name — cost if wrong: command-only adaptation; binary verifier and package checks remain separate required gates.


## Final whole-branch review, 2026-11-17

Critical: 0; Important: 2; Minor: 0. Both Important findings reproduced and retained at that severity. One fix pass; no minor findings deferred.

1. Ruling: D1-D3/ProfileUiTest/extra brightness and animation policies remain deferred — user explicitly narrowed the scope — cost if wrong: those convenience features require a later phase; current Static remains available.

2. Ruling: Hardware compatibility, EVision flicker elimination and firmware/native backend remain unclaimed — no physical new-build test or confirmed Direct/RAM-only command — cost if wrong: the keyboard can still flicker despite software fixes; physical validation remains NotRun.

3. Ruling: Other OS, Wayland, real sleep and Russian native UI remain NotRun — supplied evidence covers Linux/X11 and deterministic clock fixtures only — cost if wrong: environment-specific bugs remain untested.

4. Ruling: Native Run/Debug/SM overlap remains NotRun while actual SDK fixtures and terminal-order regressions cover the code path — reviewer did not execute native multi-process overlap — cost if wrong: native runner interaction beyond fixtures can still differ; no Passed native-overlap claim.

5. Ruling: Keep automatic JPS unknown terminal — public UUID lifecycle cannot be joined to result callback by identity — cost if wrong: automatic compiler success/failure is not displayed; no invented success.

6. Ruling: Preserve essential plugin guards — IDEA Java absence and WS/PY SM absence are impossible supported product modes — cost if wrong: those absence configurations remain platform-Blocked; genuine no-Java WS/PY and no-SM IDEA are separately tested.

7. Ruling: Remote Development and SDK branches beyond263.* remain outside declared support — product targets local pinned263 distributions — cost if wrong: remote host/client focus and future SDK APIs are not guaranteed.

8. Ruling: Keep scheduler repair at the wake-registration boundary, scheduling due work immediately with the current generation — advancing every reducer event could double-advance Stop/Skip — cost if wrong: an extra actor Wake at a due deadline; deterministic ordering and Stop/Skip regressions guard the behavior.

9. Ruling: Core Execution owns process-backed Debug terminal results; a standalone sessionStopped without a confirmed process outcome becomes NOT_EXECUTED — stopped also means detach and the pinned SDK synthesizes exit zero for detach — cost if wrong: standalone debug completion has no green success indication; exact process/environment identity is required, never time/title matching.

10. Ruling: Observe ProcessHandler.processWillTerminate(false) to distinguish detach from natural zero exit, clear optional-provider ownership on an accepted core update, and grant ownership only to an accepted newer provider fact — process result must survive Debug provider unload — cost if wrong: one temporary listener per live handler; disposal and termination remove listeners, weak metadata does not retain retired SDK objects.

11. Ruling: Isolate the public SDK message bus for Debug adapter regression fixtures while retaining a real ProcessHandler — global debugger listeners require backend XDebugSessionImpl and cannot accept a test interface session — cost if wrong: callback ordering/outcome contracts are proven, but real debugger backend and native multi-process overlap remain NotRun.
