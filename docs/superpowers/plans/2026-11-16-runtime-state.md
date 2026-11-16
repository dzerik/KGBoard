# KGBoard Runtime State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Подсветка достоверно отражает актуальное состояние IDEA при конкурирующих событиях, переключении файлов/проектов, потере фокуса и разрывах OpenRGB.

**Architecture:** Чистые переходы состояния и политика композиции исполняются одним application-level runtime. Адаптеры передают факты с идентичностью и ревизией, единственный writer управляет подключением и ограниченной очередью кадров. Новое ядро сначала проверяется без регистрации в IDE; переключение всех адаптеров и удаление старых путей вывода выполняются вместе.

**Tech Stack:** Kotlin 1.9.25, JDK 21, IntelliJ Platform Gradle Plugin 2.3.0, JUnit Jupiter 5.10.2, MockK 1.13.13, Gradle Wrapper 8.12.

**Spec:** `docs/superpowers/specs/2026-11-16-runtime-state-design.md` — утверждена пользователем 2026-11-16.

## Global Constraints

- Сохранить Kotlin 1.9.25, JDK 21, IntelliJ Platform 2024.3.6, диапазон 243–253.*.
- Сохранить формат существующих XML-настроек, цвета, группы клавиш, роли устройств и математические функции эффектов. Новые библиотеки не требуются.
- Бинарный протокол OpenRGB сохраняется. Визуальный редизайн настроек и новые эффекты выходят за границы задачи.
- Текущий документационный checkpoint — `89f3939`; baseline полной сборки ещё не подтверждён. Изолированные воспроизведения аудита не заменяют полный baseline.
- До Task 10 новый runtime не регистрируется и не отправляет данные из работающего плагина. Все промежуточные проверки используют его через явный конструктор и fake ports.
- До атомарного переключения хранить изменения в рабочей ветке без промежуточных product-коммитов: итоговая версия реализации должна быть ровно 2.0.0. Исходный checkpoint перед рефакторингом уже существует. Если пользователь захочет промежуточные коммиты, сначала согласовать серии 2.0.x: каждый коммит по правилам проекта требует повышения версии и changelog.
- Методы runtime не блокируют EDT и не выполняют сетевой I/O. Очередь событий не ожидает PSI или транспорта.
- Документировать ограничения SDK-источников; не придумывать ID операций из имён конфигураций и не подменять неизвестное состояние успехом.

## Перед Task 1: workspace и baseline

- [ ] При начале исполнения применить using-git-worktrees: проверить существующую isolation.
  Если пользователь выбрал работу в текущей ветке, сохранить этот выбор и не создавать worktree.
- [ ] До изменения продуктового кода выполнить
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test buildPlugin --console=plain`.
  Записать точные результаты тестов и сборки. Сетевая загрузка SDK не считается ни
  failing regression, ни успешным baseline. Устранить проблемы окружения до TDD-цикла.

## Review Focus

1. Смена фокуса между двумя окнами IDEA без деактивации приложения: владелец вывода меняется, таймеры фонового проекта сохраняются. Тест Task 5.
2. Устройство исчезло или поменяло количество LED после reconnect: старый кадр и mapping не используются; недоступный индекс объясняется пользователю. Тесты Tasks 4, 9.
3. Анализ файла A завершился после выбора B, изменения B или dispose проекта: результат A не меняет вывод. Тест Task 7.
4. Истечение слоя, закрытие preview и завершение записи приходят после Disconnect/Disable: не запускаются новая сессия или устаревший цвет. Тесты Tasks 3, 4, 9.
5. Пустая/отменённая тестовая сессия и ошибка старта процесса: нет зелёного успеха и зависшего running. Тест Task 6.

## Распределение файлов и общий контракт

Пути ниже относительно `src/main/kotlin/com/kgboard/rgb/`; тесты повторяют пакет в `src/test/kotlin/com/kgboard/rgb/`.

| Файл | Ответственность |
|---|---|
| `runtime/RuntimeModel.kt`, `RuntimeEvent.kt` | Неизменяемые факты, ID, ревизии и события; без IntelliJ imports. |
| `runtime/RuntimeReducer.kt` | Переходы, проверка владельцев и ревизий, истечение сроков. |
| `runtime/LightingPolicy.kt` | Выбор проекта, слоёв, ролей устройств и итоговых кадров. |
| `runtime/RuntimePorts.kt`, `LightingRuntime.kt` | Порты времени/планирования/транспорта и последовательное исполнение. |
| `runtime/ConnectionController.kt`, `FrameWriter.kt` | Сессии соединения, backoff, ограниченные pending-кадры. |
| `runtime/RuntimeSettings.kt` | Глубокие снимки существующих настроек и валидация черновика. |
| `runtime/LightingRuntimeService.kt` | Единственная точка сборки runtime в приложении и подписка UI. |
| `bridge/ProjectRuntimeBinding.kt`, `FrameFocusTracker.kt` | Lifetime проекта, initial reconciliation, владелец окна. |
| `bridge/OperationIdentityRegistry.kt`, `EditorStateAdapter.kt` | Идентичность процессов/тестов и ревизии выбранного документа. |
| `client/OpenRgbTransport.kt` | Блокирующий OpenRgbClient за transport-портом, без самовольных retries. |
| `settings/SettingsPreviewSession.kt` | UI lifetime диагностического подключения и preview. |

Task 1 определяет эти типы; позднейшие задачи расширяют их только указанными полями и событиями:

```kotlin
@JvmInline value class ProjectId(val value: String)
@JvmInline value class OperationId(val value: String)
@JvmInline value class SessionId(val value: Long)
data class Endpoint(val host: String, val port: Int)
data class EditorRevision(val fileUrl: String, val documentStamp: Long, val request: Long)
enum class OperationKind { RUN, DEBUG, BUILD, TEST }
enum class OperationPhase { STARTING, RUNNING, PAUSED, TERMINATED }
enum class OperationOutcome { SUCCESS, FAILURE, CANCELLED, NOT_EXECUTED }
data class OperationState(
    val id: OperationId,
    val kind: OperationKind,
    val phase: OperationPhase,
    val outcome: OperationOutcome? = null,
    val parent: OperationId? = null,
    val revision: Long = 1
)
data class EffectKey(val project: ProjectId?, val source: String)
data class EffectLayer(
    val key: EffectKey,
    val effect: RgbEffect,
    val createdAtMs: Long,
    val expiresAtMs: Long? = null
)
```

`RuntimeSettings.capture(app: KgBoardSettings.State): RuntimeSettings` и
`ProjectSettingsSnapshot.capture(project: KgBoardProjectSettings.State): ProjectSettingsSnapshot`
копируют все имеющиеся поля в неизменяемые DTO. MutableList, rules, device configs и
`EffectTarget.LedSet` копируются глубоко; сырой `State` в runtime не хранится.
Имена scalar-полей сохраняются для проверки полноты снимка.

`RuntimeState` содержит `settings: RuntimeSettings`, `projects: Map<ProjectId, ProjectState>`,
`activeProject: ProjectId?`, `focused: Boolean`, `shuttingDown: Boolean`,
`connection: ConnectionState`, `layers: Map<EffectKey, EffectLayer>`,
`preview: PreviewState?`. Начальное значение — enabled из снимка, без проектов,
без готового соединения и без предположения о фокусе.

`ProjectState` содержит project settings, `operations: Map<OperationId, OperationState>`,
editor state, indexing flag, текущую analysis revision/result, Git facts,
shortcut contexts и Pomodoro state. В initial snapshot операции могут быть неизвестны;
unknown не создаёт готовый положительный сигнал.

Публичные подписи ядра:

```kotlin
data class Transition(val state: RuntimeState, val commands: List<RuntimeCommand>)
class RuntimeReducer {
    fun reduce(state: RuntimeState, event: RuntimeEvent, nowMs: Long): Transition
}
class LightingPolicy {
    fun frames(state: RuntimeState, nowMs: Long): Map<Int, List<Color>>
    fun nextWakeAt(state: RuntimeState, nowMs: Long): Long?
}
interface RuntimeClock { fun nowMs(): Long }
fun interface Cancellation { fun cancel() }
interface RuntimeScheduler {
    fun scheduleAt(timeMs: Long, action: () -> Unit): Cancellation
}
interface RuntimeEvents { fun dispatch(event: RuntimeEvent) }
```

В декларациях методов классов выше показаны сигнатуры, не готовые тела исходников.
Ни один DTO ядра не содержит Project, Editor, VirtualFile, ProcessHandler или PSI.

## Task 1: чистая модель, переходы и сохранность настроек

**Files:** Create `runtime/RuntimeModel.kt`, `RuntimeEvent.kt`, `RuntimeReducer.kt`, `RuntimeSettings.kt`; test `runtime/RuntimeReducerTest.kt`, `RuntimeSettingsTest.kt`.

**Interfaces:** Consumes существующие `RgbEffect`, `EffectTarget`, settings State. Produces общий контракт выше и события:
`ProjectOpened(id, settings)`, `ProjectClosed(id)`, `ProjectActivated(id?)`,
`FocusChanged(focused)`, `SettingsApplied(settings)`, `ProjectSettingsApplied(id, settings)`,
`OperationChanged(project, operation)`, `LayerChanged(layer)`, `LayerRemoved(key)`,
`Wake(generation)`, `Shutdown`. В `RuntimeCommand` определить `ScheduleWake(atMs, generation)`,
`CancelWake`, `Connect(endpoint, session)`, `Disconnect(session)`, `OfferFrame(session, device, colors)`.
Конструкторы событий — data class внутри sealed interface `RuntimeEvent`.

- [ ] **Step 1:** Добавить тест перехода через публичный reducer и фикстуру настроек.

```kotlin
@Test fun `terminating A preserves running B`() {
    val reducer = RuntimeReducer()
    var state = RuntimeState(RuntimeSettings.capture(KgBoardSettings.State()))
    val project = ProjectId("p")
    state = reducer.reduce(state, RuntimeEvent.ProjectOpened(project,
        ProjectSettingsSnapshot.capture(KgBoardProjectSettings.State())), 0).state
    for (name in listOf("A", "B")) {
        state = reducer.reduce(state, RuntimeEvent.OperationChanged(project,
            OperationState(OperationId(name), OperationKind.RUN, OperationPhase.RUNNING)), 0).state
    }
    state = reducer.reduce(state, RuntimeEvent.OperationChanged(project,
        OperationState(OperationId("A"), OperationKind.RUN,
            OperationPhase.TERMINATED, OperationOutcome.SUCCESS, revision = 2)), 1).state
    assertEquals(OperationPhase.RUNNING,
        state.projects.getValue(project).operations.getValue(OperationId("B")).phase)
}
```

Добавить отдельные тесты duplicate finish, finish неизвестного ID, событие после ProjectClosed,
закрытие фонового проекта и глубокое копирование deviceConfigs/branch rules/LED-списков.
Закрытый ProjectId не принимается до нового ProjectOpened; новый binding получает новый ID.
Тест полноты снимка сопоставляет имена полей State с DTO, исключая только явно вычисляемые поля.

- [ ] **Step 2:** Запустить `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test --tests '*RuntimeReducerTest' --tests '*RuntimeSettingsTest'`. Ожидать RED из-за отсутствующего нового контракта; ошибки загрузки SDK фиксировать отдельно.
- [ ] **Step 3:** Реализовать DTO и переходы копированием map; terminal outcome хранить как факт, активность вычислять по phase. Удаление project чистит только его layers/operations/preview. Capture создаёт собственные immutable collections; отражение используется только тестом полноты.

```kotlin
val project = state.projects[event.project] ?: return Transition(state, emptyList())
val previous = project.operations[event.operation.id]
if (previous != null && event.operation.revision <= previous.revision) {
    return Transition(state, emptyList())
}
if (previous?.phase == OperationPhase.TERMINATED &&
    event.operation.phase != OperationPhase.TERMINATED) {
    return Transition(state, emptyList())
}
val updated = project.copy(operations = project.operations + (event.operation.id to event.operation))
return Transition(state.copy(projects = state.projects + (event.project to updated)), emptyList())
```

Это тело ветки `OperationChanged`. У каждого адаптера монотонная revision события для
operation; она хранится в OperationState и сравнивается до обновления. Произвольный порядок сообщений не исправляется
сравнением времени из разных потоков.

- [ ] **Step 4:** Добиться GREEN и выполнить существующие `*KgBoardSettings*Test`, `*RgbEffectTest`, `*EffectTargetTest`.
- [ ] **Step 5:** Проверить diff; отметить Task 1 выполненным. Изменения остаются в рабочей ветке до атомарного коммита Task 10.

## Task 2: политика композиции и deadline статичных слоёв

**Files:** Create `runtime/LightingPolicy.kt`; modify `effect/EffectColorComputer.kt` только для общего device-aware resolver; test `runtime/LightingPolicyTest.kt`.

**Interfaces:** Consumes RuntimeState, EffectLayer, RgbDeviceInfo, текущий `EffectColorComputer.composeFrame`. Produces `frames` и `nextWakeAt`; добавить
`ConnectionState.Ready(session: SessionId, devices: List<RgbDeviceInfo>)` и остальные состояния
`Disconnected`, `Connecting(session, endpoint)`, `Backoff(session, retryAtMs, error)`.
В RuntimeState отдельно хранить connect intent и next session number.

- [ ] **Step 1:** Написать тесты фактических кадров: focus dim/restoration для static и pulse;
подавленный Debug восстанавливается после Test; одинаковый приоритет стабильно разрешается
`createdAtMs`, затем `EffectKey`; у indicator нет глобального слоя, но сохранена сортировка.

```kotlin
@Test fun `static expiry schedules a wake without animation`() {
    val policy = LightingPolicy()
    val settings = RuntimeSettings.capture(KgBoardSettings.State())
    val key = EffectKey(ProjectId("p"), "failure")
    val state = RuntimeState(settings).copy(layers = mapOf(key to
        EffectLayer(key, StaticEffect(Color.RED), 10, expiresAtMs = 3010)))
    assertEquals(3010L, policy.nextWakeAt(state, 10))
    assertNull(policy.nextWakeAt(state.copy(layers = emptyMap()), 10))
}
```

Фикстура для frame-тестов: два RgbDeviceInfo с тремя LED, готовое соединение и
активный открытый проект. Использовать реальный composeFrame и реальные эффекты.

- [ ] **Step 2:** Запустить `./gradlew test --tests '*LightingPolicyTest'` под JDK 21; получить RED на отсутствии политики.
- [ ] **Step 3:** Перед composeFrame отфильтровать владельца/feature flags/deadlines, отсортировать
слои детерминированно и сформировать кадр каждого устройства независимо. Для indicator убрать
AllLeds; ambient — idle; mirror/primary используют собственный LED count и zone resolver.
При Unfocused+dim сформировать dim-idle для всех enabled устройств после выбора обычных слоёв;
при Disabled — idle; при отсутствии Ready не выдавать кадров.

```kotlin
val ordered = layers.sortedWith(compareBy<EffectLayer>(
    { it.effect.priority }, { it.createdAtMs },
    { it.key.project?.value.orEmpty() }, { it.key.source }
))
val deadline = layers.mapNotNull { it.expiresAtMs }.filter { it > nowMs }.minOrNull()
```

`nextWakeAt` возвращает минимум deadline и `nowMs + 33` для видимой анимации.
Unfocused+dim/Disabled не держит анимационные кадры, но сохраняет необходимые deadlines
процессов и Pomodoro. Просроченные слои снимаются reducer при Wake, даже когда вывода нет.

- [ ] **Step 4:** GREEN новых тестов и существующих `*EffectColorComputerTest`, `*ComposeFrameTest`, `*ResolveIndicesTest`.
- [ ] **Step 5:** Проверить, что нет копии отдельного compositor-алгоритма для indicator. Отметить Task 2.

## Task 3: последовательный runtime и детерминированный стенд

**Files:** Create `runtime/RuntimePorts.kt`, `LightingRuntime.kt`; test `runtime/RuntimeHarness.kt`, `LightingRuntimeTest.kt`.

**Interfaces:** `LightingRuntime(eventsExecutor: Executor, clock: RuntimeClock, scheduler: RuntimeScheduler,
reducer: RuntimeReducer, policy: LightingPolicy, writer: FrameWriter, initial: RuntimeState)`
реализует RuntimeEvents и AutoCloseable. `snapshot(): RuntimeState` возвращает опубликованный
immutable snapshot; `subscribe(listener: (RuntimeState) -> Unit): Cancellation`.
Task 3 определяет интерфейс `FrameWriter` в RuntimePorts:
`offer(session: SessionId, device: Int, colors: List<Color>)`, `connect(endpoint: Endpoint, session: SessionId)`,
`disconnect(session: SessionId)`, `close()`; Task 4 реализует его как `CoalescingFrameWriter`.

`RuntimeHarness` — только тестовый класс. Конструктор `RuntimeHarness(settings: RuntimeSettings)`;
методы `send(event)`, `advanceBy(ms: Long)`, `drain()`, `lastFrame(device: Int): List<Color>`,
`completeConnect(session: SessionId, devices: List<RgbDeviceInfo>)`, `close()`.
`send` выполняет очередь до покоя, `advanceBy` исполняет все due actions в порядке deadline/sequence,
`drain` не двигает время. Fake writer хранит копии предложенных кадров; позднее тесты
Task 4 отдельно проверяют реальные отправки CoalescingFrameWriter.

- [ ] **Step 1:** Написать тест expired static → idle без вторичного события;
старый Wake после Disable/Shutdown; разные перестановки событий двух независимых процессов.
Первый кадр фокуса проверить через writer, а не поля state.

```kotlin
@Test fun `stale wake after shutdown produces no frame`() {
    val harness = RuntimeHarness(RuntimeSettings.capture(KgBoardSettings.State()))
    harness.send(RuntimeEvent.Shutdown)
    val before = harness.snapshotForTest()
    harness.send(RuntimeEvent.Wake(Long.MIN_VALUE))
    assertEquals(before, harness.snapshotForTest())
    harness.close()
}
```

Добавить в RuntimeHarness `snapshotForTest(): RuntimeState`, возвращающий публичный snapshot runtime.
Проверять окончание shutdown отдельным fake-writer списком команд: финальная запись допустима
только одна, после неё новых offer/connect нет.

- [ ] **Step 2:** RED: `./gradlew test --tests '*LightingRuntimeTest'`.
- [ ] **Step 3:** Реализовать одну очередь dispatch, цикл reduce→publish→commands→policy,
монотонный clock через `System.nanoTime()` и один отменяемый wake handle с generation.
Колбэки источников только ставят события в очередь. Подписчики UI не вызываются под lock;
публикация snapshot — атомарная ссылка. Runtime close idempotent; поздние события не принимаются.

```kotlin
override fun dispatch(event: RuntimeEvent) {
    eventsExecutor.execute { process(event) }
}
```

`process` сначала проверяет closed/shutdown и generation Wake; затем применяет reducer,
публикует snapshot и выдаёт команды. Исключения transport/computation преобразуются в события
ошибки; `ProcessCanceledException` в IDE-адаптерах не превращается в ошибку анализа.

- [ ] **Step 4:** GREEN; новые тесты используют управляемое время, без Thread.sleep. Повторить
их со 100 фиксированными seed перестановок независимых событий, сравнивая итоговые кадры.
- [ ] **Step 5:** Проверить тестами отсутствие сетевых вызовов на runtime thread и сохранение
блокирующего транспорта вне EDT. Отметить Task 3.

## Task 4: соединение, reconnect, bounded writer и кеш каждого устройства

**Files:** Create `runtime/ConnectionController.kt`, `FrameWriter.kt`, `client/OpenRgbTransport.kt`;
modify `client/OpenRgbClient.kt`; test `runtime/ConnectionControllerTest.kt`, `FrameWriterTest.kt`,
`client/OpenRgbReconnectTest.kt`.

**Interfaces:**
`OpenRgbTransport.connect(endpoint: Endpoint): List<RgbDeviceInfo>`,
`write(device: Int, colors: List<Color>)`, `close()` — блокирующие методы только worker thread.
`CoalescingFrameWriter(transport: OpenRgbTransport, executor: Executor, events: RuntimeEvents)`
реализует FrameWriter. Для подмены использовать `interface RgbTransport` с этими тремя методами;
OpenRgbTransport реализует его, конструктор writer принимает RgbTransport.
События: `Connected(session, devices)`, `ConnectFailed(session, message)`,
`FrameSent(session, device, sequence, colors)`, `TransportFailed(session, message)`.
`ConnectionController.reduce(state: RuntimeState, event: RuntimeEvent, nowMs: Long): Transition`
вызывается RuntimeReducer только для connection-related событий. Он чистый и не владеет
executor/transport; изменения состояния публикует исключительно LightingRuntime.

- [ ] **Step 1:** Перенести TCP reset repro в OpenRgbReconnectTest: локальный ServerSocket
отвечает на negotiation/name, затем закрывает peer с SO_LINGER=0; запрос клиента должен
закрыть сессию после SocketException. Повторное подключение должно реально принять новый socket.
Использовать latch/future с ограниченным timeout; timeout служит аварийным пределом, не синхронизацией.
- [ ] **Step 2:** RED; написать writer-тест с блокируемым transport и 1000 кадрами на устройство.

```kotlin
@Test fun `pending frames are replaced while transport is blocked`() {
    val transport = GatedTransport()
    val events = RecordingRuntimeEvents()
    val worker = Executors.newSingleThreadExecutor()
    val writer = CoalescingFrameWriter(transport, worker, events)
    writer.connect(Endpoint("localhost", 6742), SessionId(1))
    transport.awaitConnected()
    writer.offer(SessionId(1), 0, listOf(Color.RED))
    transport.awaitWriteStarted()
    repeat(1000) { writer.offer(SessionId(1), 0, listOf(Color.BLUE)) }
    transport.releaseWrite()
    transport.awaitWrites(2)
    assertEquals(listOf(Color.RED, Color.BLUE), transport.colorsWritten())
    writer.close()
    worker.shutdownNow()
}
```

В тестовом файле определить `GatedTransport : RgbTransport` с CountDownLatch для
connect/first write/release/second write и списком успешных write; методы above получают
таймаут 5 секунд и падают при превышении. `RecordingRuntimeEvents : RuntimeEvents`
сохраняет события в ConcurrentLinkedQueue. Закрытие обязательно в finally в итоговом тесте.

- [ ] **Step 3:** В OpenRgbClient каждое исключение I/O закрывает socket и переводит его в null
под существующим lock. Никакого сетевого запроса в isConnected. Controller выдаёт новую SessionId
при смене endpoint/намерения; результат старой сессии игнорирует. Backoff starts 1s, doubles
до 30s, сбрасывается после Ready; ручной Disconnect отменяет retry и инвалидирует сессию.
- [ ] **Step 4:** Writer хранит не более одного выполняющегося write и одного pending кадра
на доступное устройство. Новые кадры заменяют pending; disconnect/connect имеют управляющий
приоритет. Последний успешно записанный кадр кешируется по session+device. После ошибки
и нового Ready кеш очищается. Помещённый в очередь кадр не считается успешно записанным.
`skipCustomMode` сохраняется как настройка transport connect/config, проверяется regression test.
- [ ] **Step 5:** GREEN: reset→retry→ready; Disconnect во время Connect; старый callback после
новой сессии; два устройства с неизменным primary; reconnect с новым LED count/без устройств;
равные кадры после новой сессии всё равно отправляются; close прекращает retries. Проверить
`./gradlew test --tests '*OpenRgb*Test' --tests '*FrameWriterTest' --tests '*ConnectionControllerTest'`.
- [ ] **Step 6:** Отметить Task 4; существующий OpenRgbConnectionService пока не заменять в
живых регистрациях. Исправление socket допустимо, второй runtime writer остаётся неактивным.

## Task 5: lifetime проектов и корректный владелец фокуса

**Files:** Create `bridge/ProjectRuntimeBinding.kt`, `FrameFocusTracker.kt`; prepare changes
`bridge/KgBoardStartupActivity.kt`, `FocusEventListener.kt`, `ProjectFocusListener.kt`,
`AppLifecycleHandler.kt`; test `bridge/ProjectRuntimeBindingTest.kt`, `FrameFocusTrackerTest.kt`.

**Interfaces:** `ProjectRuntimeBinding(project: Project, events: RuntimeEvents)` с `start()`/`dispose()`;
`ProjectId` назначается одному lifetime binding, не одному пути на диске.
`FrameFocusTracker` принимает порт `FocusedProjectSource.current(): ProjectId?` и
`subscribe(listener: () -> Unit): Cancellation`; после каждого изменения считывает текущего
владельца, не доверяет запоздалой паре window-lost/window-gained.
Binding всегда подписывается, затем выполняет reconciliation с ревизией.

- [ ] **Step 1:** RED тест с fake source: A→B без application-deactivated. В выводе только B;
закрытие A ничего не меняет; деактивация приложения применяет dim к B; возврат выбирает
актуальное окно. Initial callback, пришедший до завершения snapshot, не затирается старым снимком.
- [ ] **Step 2:** Проверить API на SDK 243: native Window focus через AWT WindowFocusListener
для IDE frames и WindowManager; слушать открытие/закрытие рамок, а не только ApplicationActivationListener.
Существующие application focus listeners передают события runtime; прямые LED-записи удалить при cutover.
- [ ] **Step 3:** Реализовать binding с собственным Disposable и снимком только валидного проекта.
Reconciliation охватывает DumbService.isDumb, выбранный editor, живые RunContentDescriptors
и XDebuggerManager.debugSessions; неизвестные terminal results не восстанавливать как success.
При закрытии generation binding инвалидируется до отмены вычислений.
- [ ] **Step 4:** GREEN новых тестов и интеграционная проверка закрытия проекта при queued callback.
Listener count после 20 open/close циклов возвращается к исходному. Shutdown отправляет только
один финальный кадр по resetOnExit с транспортным пределом не более 3 секунд.
- [ ] **Step 5:** До cutover готовые классы без service/extension registration. Отметить Task 5.

## Task 6: операции Build/Run/Debug/Test с идентичностью

**Files:** Create `bridge/OperationIdentityRegistry.kt`; prepare replacements
`ExecutionEventListener.kt`, `BuildEventListener.kt`, `TestEventListener.kt`,
`DebuggerBreakpointListener.kt`; test `bridge/OperationIdentityRegistryTest.kt`,
`OperationAdaptersTest.kt`, `runtime/OperationPolicyTest.kt`.

**Interfaces:**
`OperationIdentityRegistry.processId(handler: ProcessHandler, executionId: Long): OperationId`,
`testId(root: SMTestProxy.SMRootTestProxy): OperationId`, `release(id: OperationId)`.
Registry хранит ссылки только в пределах project Disposable, снимает их на terminal/dispose.
Для build identity использовать ProjectTaskContext.sessionId, при null — identity самого context
и назначенный счётчик; результат ProjectTaskManager.Result.context возвращает тот же ключ.

- [ ] **Step 1:** RED тесты всех перестановок завершения A/B; debug pause→resume→stop;
startScheduled→notStarted; cancelled build/test; ignored-only и zero tests; поздний duplicate finish.
OperationOutcome.NOT_EXECUTED не создаёт зелёную вспышку. Считать leaf tests, исключая suites/root.
- [ ] **Step 2:** Использовать подтверждённый в SDK 2024.3.6 ProjectTaskListener.TOPIC:
`started(context)` и `finished(result)`, `result.context`, `isAborted`, `hasErrors`.
Это даёт пары начала/конца; не выдумывать started из fileGenerated. Удалить дублирующую
регистрацию CompilationStatusListener при cutover. Источники сборки вне ProjectTaskManager
не выдаются за наблюдаемые; поддержка самостоятельных external-system задач в эту миграцию не добавляется.
- [ ] **Step 3:** Сопоставить SM test root с process descriptor:

```kotlin
val descriptor = ExecutionManager.getInstance(project).getRunningDescriptors { true }
    .firstOrNull { descriptor ->
        val console = descriptor.executionConsole as? SMTRunnerConsoleView
        console?.resultsViewer?.testsRootNode === testsRoot
    }
val handler = descriptor?.processHandler
```

Эти getter-подписи проверены javap на локальном SDK 243.26574.91. Если descriptor ещё не
опубликован, повторить reconciliation на run-content lifecycle; сохранить test root как
самостоятельный ID до появления корреляции, не связывать по времени/имени. Для descriptor
с SMTRunnerConsoleView обычный RUN не создаёт отдельный визуальный слой. Корреляция не должна
убирать независимо работающий Run другого handler; закрепить это тестом.
- [ ] **Step 4:** Все listeners передают facts с revision. Reducer сохраняет нижние слои;
policy использует имеющиеся приоритеты цветов. При terminal действие касается только ID
операции, а не `returnToIdle()` всей подсветки. Breakpoint связан с конкретной debug session.
- [ ] **Step 5:** GREEN трёх новых наборов; проверить начальную сверку уже работающих процессов,
отсутствие опоры на mutable runProfile.name и очистку registry. После завершения снять callbacks
источника; после окончания terminal-вспышки удалить его state, сохраняя запрет адаптера на
повторное использование OperationId. Registry выдаёт уникальные ID и отбрасывает callbacks
закрытого binding, не накапливает сильные ссылки на завершённые handlers/roots. Проверить
1000 start/finish циклов: число живых bindings/layers возвращается к нулю. Отметить Task 6.

## Task 7: editor revisions, анализ, TODO, Git и уведомления

**Files:** Create `bridge/EditorStateAdapter.kt`; prepare replacements
`CodeAnalysisListener.kt`, `TodoIndicatorListener.kt`, `GitStatusListener.kt`,
`GitStatusStartupActivity.kt`, `IdeNotificationListener.kt`, `ShortcutHighlightService.kt`,
`FileSaveListener.kt`; test `bridge/EditorStateAdapterTest.kt`, `IndicatorAdaptersTest.kt`,
`runtime/EditorPolicyTest.kt`.

**Interfaces:** События `EditorSelected(project, revision: EditorRevision?, applicable: Boolean)`,
`AnalysisRequested(project, revision)`, `AnalysisCompleted(project, revision, result: AnalysisResult)`,
`TodosCompleted(project, revision, hasTodos: Boolean)`, `IndexingChanged(project, indexing: Boolean)`,
`GitChanged(project, revision: Long, facts: GitFacts)`, `ShortcutsChanged(project, contexts: Set<String>)`,
`DocumentsSaved(project, revision: Long)`, `LowMemory`.
`AnalysisResult` = `Unknown`, `NotApplicable`, `Ready(hasErrors: Boolean, hasWarnings: Boolean)`;
editor и indexing остаются независимыми полями ProjectState.

- [ ] **Step 1:** RED тесты выбора A→B→image→none, последнего закрытого редактора, удаления файла,
отмены read action и старого результата после изменения stamp. Показать чистый B только
после достоверного Ready(B), не после отсутствия markup или инспекций.
- [ ] **Step 2:** Подписать FILE_EDITOR_MANAGER.selectionChanged/fileClosed/fileOpened,
DocumentListener для активного документа, VFS deletion и daemon lifecycle. Событие всегда
сверяется с текущей selection; asynchronous result несёт file URL, stamp и request sequence.
Проверять validity и dispose до/после read action; не удерживать PSI в runtime.

```kotlin
if (project.editorRevision != event.revision || project.indexing) {
    return Transition(state, emptyList())
}
```

Это первая проверка в reducer для результатов анализа; disabled feature не уничтожает
подписку. При применении настроек policy сразу снимает выключенный слой.
- [ ] **Step 3:** Errors/warnings получать для выбранного editor/document, не Wolf whole-project
вместе с selected-file warnings. Отсутствие данных, dumb mode и отмена дают Unknown.
TODO применим отдельно от инспекций; результаты PSI считаются только для валидной ревизии.
При смене selection/stamp предыдущие analysis/TODO layers инвалидировать немедленно.
- [ ] **Step 4:** Git callback/poll возвращает facts и revision; regex rules компилируются при
SettingsApplied. Сохранить existing indicator meaning, не вводить новую трактовку Git-состояний.
Indexing подписывается всегда с initial isDumb. LowMemory тоже подписывается независимо от toggle.
Shortcut service формирует facts contexts по existing настройкам; debug context привязать к
наличию debug session, search — к IDE SearchEverywhere/Find UI lifetime, без прямых LED calls.
Не обещать editing/vcs automatic contexts, пока нет SDK-события: сохранить существующие настройки
и возможность явной активации через `ShortcutsChanged`, отметить ограничение в документации.
- [ ] **Step 5:** Save listener проверяет modified documents до save, определяет owning projects,
один DocumentsSaved на batch, ноль на пустой save-all. При закрытии binding отменяет read
tasks и poll; запоздалый callback игнорируется. GREEN новых трёх наборов и toggle off/on без reopen.
- [ ] **Step 6:** Отметить Task 7; не оставлять старый Git poll и новый poll одновременно при cutover.

## Task 8: Pomodoro как часть той же очереди

**Files:** Create `runtime/PomodoroState.kt`; prepare changes
`pomodoro/PomodoroTimerService.kt`, `PomodoroAction.kt`, `PomodoroWidget.kt`;
test `runtime/PomodoroStateTest.kt`, `pomodoro/PomodoroFacadeTest.kt`.

**Interfaces:** `PomodoroState(phase: PomodoroPhase, completedSessions: Int, deadlineMs: Long?, generation: Long)`;
enum `PomodoroPhase { IDLE, WORK, BREAK, LONG_BREAK }`; события
`PomodoroStart(project)`, `PomodoroStop(project)`, `PomodoroSkip(project)`.
Существующий PomodoroTimerService становится facade RuntimeEvents/snapshot,
сохраняет public start/stop/skip, isRunning/currentPhase/remainingSeconds/completedSessions,
formatTimeRemaining/phaseDisplayName и listener API для widget/actions.

- [ ] **Step 1:** RED sequence-тест skip перед старым tick, stop перед due tick, длительная задержка
scheduler, длинный перерыв после N циклов, смена проекта во время work, выключение функции.
remainingSeconds >= 0 и вывод соответствует новой фазе после любого старого wake.
- [ ] **Step 2:** В reducer каждая команда меняет phase/deadline/generation атомарно;
remainingSeconds вычисляется `max(0, (deadlineMs - nowMs + 999) / 1000)`.
На запоздалом wake выполнить максимум один переход фазы и начать следующую от nowMs,
без проигрывания серии пропущенных перерывов и вспышек. Зафиксировать это тестом.
- [ ] **Step 3:** Policy создаёт Gradient/Pulse/transition layers из текущего PomodoroState.
Удалить собственный ScheduledExecutorService таймера; facade публикует изменения UI через EDT,
widget снимает подписку без получения уже disposed project service.
- [ ] **Step 4:** GREEN runtime и facade тестов. Сверить дефолты 25/5/15/4 и настройки target/color
с прежними XML; ноль unmanaged ticking threads после закрытия проекта. Отметить Task 8.

## Task 9: атомарный Apply, диагностический Connect и scoped preview

**Files:** Create `settings/SettingsPreviewSession.kt`; prepare changes
`settings/KgBoardConfigurable.kt`, `KgBoardProjectConfigurable.kt`, `SettingsUiUtil.kt`,
`KgBoardSettings.kt`, `KgBoardProjectSettings.kt`, `layout/KeyboardLayoutService.kt`,
`preset/KeyGroupService.kt`, `bridge/KgBoardStatusWidgetFactory.kt`;
test `settings/SettingsPreviewSessionTest.kt`, `SettingsApplyTest.kt`, `SettingsValidationTest.kt`.

**Interfaces:** События `BeginSettings(id: Long)`, `DiagnosticConnect(id, endpoint)`,
`PreviewStarted(id, device: Int, color: Color, indices: List<Int>, durationMs: Long)`,
`EndSettings(id, applied: Boolean)`. PreviewState хранит session id UI, исходное устройство,
цвет/цель/deadline; BeginSettings сохраняет исходные endpoint и connect intent.
`SettingsPreviewSession(events: RuntimeEvents, id: Long)` реализует AutoCloseable;
`connect(endpoint)`, `preview(device, color, indices)`, `applied()`, `close()`.
`close()` после applied всё равно удаляет preview, но не откатывает новый SettingsApplied.

- [ ] **Step 1:** RED тесты Test→OK/Cancel сразу, repeated Test, Apply device0→1 во время preview,
Disconnect/Disable, отсутствующее устройство. Отдельно Connect с изменёнными host/port до Apply:
он использует черновик, Cancel возвращает исходный endpoint и connect intent, изначально
disconnected остаётся disconnected. Старый UI id не снимает preview нового диалога.
- [ ] **Step 2:** UI сначала собирает и валидирует весь draft, затем одним SettingsApplied
публикует глубокий снимок и заменяет persisted State. Новые данные не попадают в runtime
через частичные setters. Project Apply работает аналогично. reset только меняет draft.
Использовать отдельную модель draft, чтобы super.apply/tabPanels.apply не меняли singleton
по одному полю до полной валидации. Тест: невалидный LED CSV не меняет даже host/color.
- [ ] **Step 3:** Preview — слой с высоким приоритетом и заданным устройством; после его удаления
пересчитать обычные кадры, не записывать idle напрямую и не ставить paused всем проектам.
UI disposable безусловно вызывает session.close. При dim/Disabled preview подчиняется
верхней политике приложения. Удалить testAlarm и direct testLeds calls после cutover.
- [ ] **Step 4:** Validation различает пустое поле и malformed: `",,"`, отрицательные, overflow,
нечисловые и >=numLeds — ошибка. Если устройство неизвестно, разрешить сохранение чисел,
но запретить Test и явно сообщить, что диапазон ещё не проверен. Не превращать malformed в AllLeds.
UI combo показывает имена/индексы; отсутствие сохранённого индекса видно как недоступный выбор.
- [ ] **Step 5:** После нового устройства/Ready обновить layout/groups одним immutable mapping
snapshot, очистить mapping при отсутствии устройства. Settings и status widget подписаны на
один runtime snapshot: disconnected/connecting/ready/backoff/no-device/error. Analysis label
показывает unknown/indexing/not-applicable/result. Listener UI работает только на EDT и снимается
в dispose. GREEN всех новых тестов и существующих settings/layout/key-group tests.
- [ ] **Step 6:** Отметить Task 9. Проверить, что открытый Settings не заставляет runtime создавать
facades/композиторы всех проектов и не изменяет настройки по Cancel.

## Task 10: сборка сервисов, атомарное переключение и итоговая проверка

**Files:** Create `runtime/LightingRuntimeService.kt`; activate подготовленные изменения Tasks 5–9;
modify `src/main/resources/META-INF/plugin.xml`, `META-INF/kgboard-git.xml`,
`build.gradle.kts`, `.github/workflows/ci.yml`, `README.md`, `CONTRIBUTING.md`,
`CHANGELOG.md`, `gradle.properties`; remove `effect/LedCompositor.kt`,
`EffectManagerService.kt` и старый `client/OpenRgbConnectionService.kt` после переноса всех consumers.
Test `runtime/RuntimeIntegrationTest.kt`, `bridge/RuntimeLifecyclePlatformTest.kt`,
`settings/SettingsPersistencePlatformTest.kt`.

**Interfaces:** `LightingRuntimeService.getInstance(): LightingRuntimeService`,
`events: RuntimeEvents`, `snapshot(): RuntimeState`,
`subscribe(listener: (RuntimeState) -> Unit): Cancellation`.
Только этот сервис создаёт event executor, scheduler, transport worker и runtime.
ProjectRuntimeBinding принадлежит проекту и не владеет транспортом.

- [ ] **Step 1:** Проверить записанные результаты workspace/baseline prerequisite перед Task 1;
сопоставить новые failures с baseline, не скрывая их выключением тестов или проверок.
- [ ] **Step 2:** RED интеграционных тестов: приложение с двумя project bindings и одной fake-сетью;
все 10 групп последовательностей спецификации проверяют отправленные кадры. В platform-тестах
проверить реальную подписку selectionChanged, initial dumb state, XML round-trip старых fixtures,
Apply/Cancel и dispose. Подготовить старые XML fixtures в `src/test/resources/settings/` для
app/project/keygroups, используя нынешние serializer аннотации и имена storage.
- [ ] **Step 3:** В одном working-tree переключении зарегистрировать единственный app runtime,
подключить все адаптеры, удалить старые service registrations/direct paths. Git adapter остаётся
в optional Git4Idea config; runtime не импортирует git4idea и загружается без Git-плагина.
На старте подписать наблюдение до initial reconciliation независимо от enabled.
- [ ] **Step 4:** Заменить все consumers OpenRgbConnectionService/EffectManagerService/LedCompositor;
удалить эти реализации и paused-флаг. RgbEffect, EffectColorComputer, протокол и настройки XML
сохранить. Проверить отсутствие второй точки создания OpenRgbClient и старых render loops.

```bash
rg -n 'LedCompositor|EffectManagerService|OpenRgbConnectionService|\.paused\s*=' src/main
rg -n 'OpenRgbClient\(' src/main/kotlin
rg -n 'setAllLeds|testLeds|updateLeds' src/main/kotlin/com/kgboard/rgb/bridge src/main/kotlin/com/kgboard/rgb/settings
```

Первый и третий поиски должны быть пустыми; конструктор OpenRgbClient остаётся только
в transport, низкоуровневые update методы — только в client. Проверки поведения остаются
главным критерием, поиск служит контролем полноты миграции.
- [ ] **Step 5:** Добавить plugin verification targets в build.gradle.kts для IC 2024.3.6,
IC 2025.1, IC 2025.2 и IU 2025.3. Начиная с 2025.3 отдельная IC не публикуется;
для ветки 253 использовать распространяемую IntelliJ IDEA с Java-модулем, сохраняя SDK 243
для компиляции. Не сужать untilBuild ради прохождения проверки. В CI выполнять unit/build
и verifyPlugin. Отсутствие бинарника платформы — unresolved verification.

```kotlin
intellijPlatform {
    pluginVerification {
        ides {
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.6")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.2")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaUltimate, "2025.3")
        }
    }
}
```
- [ ] **Step 6:** Выполнить под JDK 21:

```bash
./gradlew test buildPlugin --console=plain
./gradlew verifyPlugin --console=plain
git diff --check
```

Новые platform-тесты остаются JUnit Jupiter и явно управляют lifecycle SDK fixture,
без наследования JUnit3 TestCase и без добавления engine. Проверить присутствие классов
в XML test results, а не только успешность задачи. SDK 243 поддерживает следующие вызовы:

```kotlin
private lateinit var fixture: IdeaProjectTestFixture

@BeforeEach fun setUpFixture() {
    fixture = IdeaTestFixtureFactory.getFixtureFactory()
        .createFixtureBuilder(javaClass.simpleName).fixture
    EdtTestUtil.runInEdtAndWait { fixture.setUp() }
}

@AfterEach fun tearDownFixture() {
    EdtTestUtil.runInEdtAndWait { fixture.tearDown() }
}
```

Операции с editor/PSI и проверки selection выполнять в EDT/read action по контракту SDK;
сеть остаётся fake. XML round-trip проверяет существующие storage names и все значения,
включая custom groups и branch rules.
- [ ] **Step 7:** Ручной sandbox OpenRGB: первый Connect, выбор устройства, Test→Cancel, два
окна проектов, Run+Debug+Tests, focus во время pulse, image/no tabs, reconnect, смена устройств,
close background project и shutdown. Записать IDE/OpenRGB/device versions и каждый результат.
Без оборудования явно оставить hardware verification неподтверждённой, не изображать зелёный
статус; unit/integration фейки не доказывают совместимость конкретной клавиатуры.
- [ ] **Step 8:** Обновить README/CONTRIBUTING с семантикой active project, анализа выбранного
файла, Cancel/preview и ограничений источников сборки/shortcut contexts. Привести Mermaid
архитектуру в соответствие. AGENTS.md не переписывать: пользователь запретил изменять
существующий AGENTS.md при первоначальной задаче; упоминание удалённых модулей в нём отсутствует.
- [ ] **Step 9:** Повысить pluginVersion до 2.0.0, добавить секцию CHANGELOG с изменённой
архитектурой и пользовательским поведением, повторить buildPlugin для ZIP с финальной версией.
Зафиксировать только проверенную миграцию, план и необходимую документацию:

```bash
git add src build.gradle.kts .github/workflows/ci.yml README.md CONTRIBUTING.md CHANGELOG.md gradle.properties docs/superpowers
git commit -m "refactor: centralize lighting state and output (2.0.0)"
git status --short
```

Если проверка нашла проблему, исправить её и повторить затронутую проверку до коммита.
После успешной проверки обновить Serena memory с фактическими интерфейсами, ограничениями
и результатами, отличая аппаратную проверку от тестового транспорта. Не создавать release,
tag или публикацию без отдельного запроса пользователя.

## Проверка полноты плана

| Требование спецификации | Задания |
|---|---|
| Единый владелец, immutable facts, stale-result guards | 1, 3, 5, 10 |
| Focus/active project/disabled/shutdown | 2, 3, 5, 10 |
| Несколько процессов, priority restoration, cancel/not-started/empty tests | 1, 2, 6 |
| Static deadlines и Pomodoro stop/skip | 2, 3, 8 |
| Ready/backoff/manual disconnect/session fencing/queue bounds | 3, 4 |
| Per-device dirty cache, roles, mapping after reconnect | 2, 4, 9 |
| Editor types, unknown, indexing, revisions, initial reconciliation | 5, 7 |
| Toggle Apply, deep snapshots, draft Connect/Test и Cancel | 1, 7, 9 |
| Один writer при миграции, совместимость XML и диапазона IDE | 10 |
| Unit, deterministic sequences, real platform subscriptions, manual device QA | 1–10 |

## Передача на исполнение

План подготовлен для проверки пользователем; продуктовые изменения ещё не начаты.
Рекомендуется последовательное исполнение основным агентом (Native), потому что задачи
используют общие DTO, один reducer и связанный cutover. После завершения — независимый
review всей ветки. Альтернатива — отдельный implementer/reviewer на каждое задание
(Subagent-driven): больше независимых проверок и затрат на передачу контекста.
