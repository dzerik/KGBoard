# KGBoard: Git, сборки и optional-интеграции

Статус: письменный дизайн пакета C утверждён пользователем. Общие ограничения:
[product evolution](2026-11-16-product-evolution-design.md).

## Наблюдаемые возможности вместо обязательного Java plugin

Core использует общие platform/lang/xdebugger API. Обязательная Java module dependency
удаляется после проверки фактических ссылок; новый Java adapter не создаётся, если
Java-specific API не нужны. SM Test Runner и Git загружаются только при доступности
соответствующих plugins. Core не импортирует классы этих plugins.

`IntegrationProvider` объявляет capability и создаёт project-owned Disposable adapter.
Provider регистрируется optional descriptor. Core знает capability IDs и domain facts,
а не SDK-классы отдельного engine. Install/unload/dispose снимают subscriptions до
публикации Unavailable; callbacks старого lifetime не восстанавливают capability/facts.

`CapabilitySnapshot` различает Available, Unavailable и Error с причиной. В UI отсутствующий
Git/Test Runner даёт объяснение доступности, а не сломанный checkbox или исключение
загрузки. Наличие plugin не означает, что build конкретного проекта уже проверен.

## Git: фактические изменения и направление расхождения

По каждому Git repository читается локальный статус через Git4Idea command runner:

```text
git status --porcelain=v2 --branch --untracked-files=normal -z
```

NUL-safe parser учитывает обычные/renamed/unmerged/untracked записи и branch headers.
Ahead и behind берутся из branch.ab: нулевые значения отличаются от отсутствия upstream.
Detached HEAD, unborn branch, NoUpstream и Unknown не обозначаются как Clean/Synced.
Conflict определяется unmerged entries; сам процесс merge/rebase без unmerged файлов
показывается отдельно. Эта семантика следует из [git-status](https://git-scm.com/docs/git-status).

Repo facts: repository identity, head OID, branch/upstream availability, dirty/conflicts,
ahead/behind counts, merge/rebase state, sampledAt и Fresh/Unknown. Для нескольких repos
UI показывает каждую строку; индикатор выбирает severity, не складывает несвязанные
counts в одну вымышленную ветку. Conflict > Dirty > Diverged > Ahead/Behind > Synced;
NoUpstream/Detached/Unknown имеют собственные labels и не дают зелёный Clean.

Новые defaults цветов: Behind#00BCD4, Diverged#FF9100; прежние цвета остальных
состояний сохраняются. Project UI показывает эти состояния и даёт Preview.

Запросы идут вне EDT/read lock. По repository не более одного процесса и одного
coalesced обновления. Используется git executable, выбранный IDE; автоматического
fetch/push/доступа к сети нет. Поддерживается porcelain v2; при недоступном executable
или несовместимом ответе публикуется Unknown с причиной, а не ложный статус.

Existing polling interval сохраняется, нижняя граница2с. Repository callbacks coalesce,
закрытие проекта отменяет процесс; таймаут одного status-запроса10с. Ошибка переводит
facts в Unknown до следующего успеха. Completion несёт repository/request revision;
результат старой ветки или удалённого repo не попадает в текущий вывод.

## Единый поток сборок

Выбранный canonical source — публичный BuildProgressObservable у BuildViewManager:
`addListener(BuildProgressListener, Disposable)`. Root StartBuildEvent/FinishBuildEvent
дают identity и результат; дочерние шаги не создают отдельные глобальные build layers.
ID scoped на проект и lifetime observer, никогда не получается из имени или времени.

Это охватывает стандартные JPS, Gradle и Maven pipelines, публикующие Build events;
их реальное покрытие обязательно проверяется на IDE, включая delegated build и
автоматическую JPS-компиляцию. Старую ProjectTaskListener build-подписку заменяют
в том же cutover после этих проверок; одновременно два visual producer не работают.
Если конкретный путь SDK не публикует root lifecycle, это незакрытая проверка,
а не основание выдумать успех или незаметно удалить прежнюю возможность.

Правила результата:

- SuccessResult → SUCCESS; FailureResult → FAILURE с сохранением root/suite ошибок.
- SkippedResult → NOT_EXECUTED; подтверждённая cancellation → CANCELLED.
- Finish с неизвестным result → NOT_EXECUTED с диагностикой, без зелёного сигнала.
- Finish без наблюдавшегося Start не создаёт ложный успех.
- Один root принимает ровно один terminal; поздние child/duplicate события игнорируются.
- Несколько параллельных roots независимы; конец одного не очищает другой.

Build/Test/Run facets связываются только по SDK identity/process handler/root links,
если SDK их предоставляет. Используемый сейчас механизм подавления RUN, принадлежащего
SM Test root, сохраняется. Сопоставление по title, соседству событий или временному
окну запрещено. Root errors и discovered-but-unlaunched tests проверяются настоящими
SDK objects, а не только helper-счётчиками.

Gradle sync/import и Maven import не выдаются за пользовательскую compile/build success.
Разрешённые сигналы этого пакета — lifecycle реального build/task execution. Отдельный
shell-процесс вне IDE наблюдения не становится поддержанной сборкой автоматически.

## Матрица первой cross-IDE поддержки

| IDE | Build263 для проверки | Проверяемые возможности |
|---|---|---|
| IntelliJ IDEA2026.3 |263.7307.200 |Core, UI, analysis, debug, SM tests, Git, JPS/Gradle/Maven |
| PyCharm2026.3 |263.7307.206 |Core, UI, analysis, debug, tests при наличии SM provider, Git |
| WebStorm2026.3 |263.7307.202 |Core, UI, analysis, debug, tests при наличии SM provider, Git |

Бинарники PyCharm/WebStorm ещё не проверены. Build metadata проверены через официальный
[JetBrains endpoint](https://data.services.jetbrains.com/products/releases?code=IIU,WS,PY&latest=true&type=release).
Финальные Gradle product IDs берутся из distribution product-info, а не угадываются
по API alias. Поддержка объявляется только после verifier и запуска actual IDE.

Gradle/Maven не считаются bundled-возможностью каждого продукта. Optional plugin может
добавить capability, но не заставляет пользователя устанавливать Java/Maven для базовой
RGB-подсветки. Другие IDE получают отдельную проверку до добавления в список поддержки.

## Критерии приёмки

- Реальные temporary Git repos: ahead-only, behind-only, diverged, dirty, conflict,
  clean, no-upstream, detached, unborn; имена файлов с пробелами/newline/rename.
- Смена ветки во время задержанного запроса не показывает старый статус; отмена/ошибка
  команды не превращаются в Synced и не запускают процессы после project dispose.
- Actual IDE fixtures/driver: JPS/automatic JPS, delegated Gradle, standalone Gradle
  task и Maven goal дают ровно один наблюдаемый root lifecycle на запуск.
- Success/failure/cancel/skipped/start failure/parallel roots проверяются отправленными
  кадрами и diagnostics; old tests232 сохраняются или заменяются более сильными checks.
- Plugin загружается без Java, Git и SM provider, если они недоступны; отсутствующие
  capabilities объясняются. Dynamic unload не оставляет listeners и effects.
- PluginVerifier не содержит Internal API errors на всех объявляемых IDE263.
