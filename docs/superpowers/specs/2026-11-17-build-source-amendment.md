# Уточнение источников сборки для SDK263

Этот документ уточняет [C3](2026-11-16-integrations-design.md) по результатам
реального запуска IU263.7307.200. Цель остаётся прежней: точные события IDE,
без ложного успеха и без потери существующего покрытия.

## Подтверждённые ограничения

Ручная JPS-сборка публикует BuildView Start/Finish; автоматическая — нет.
Нативная компиляция автоматически завершилась с errors=0, но не дала ни
BuildView, ни ProjectTask lifecycle. Старый обработчик её также не наблюдал.

Публичный BuildManagerListener даёт UUID/start/finish/automatic. Публичный
CompilationStatusListener передаёт результат без UUID, через CompileContext,
который не содержит доступной связи с сессией. В проверке этот callback пришёл
после finish сессии. Объединение по порядку, времени или «текущей сборке» запрещено.
BuildProgressListenerRegistrar помечен Internal и не используется продуктом.

## Решение

| Путь | Источник | Терминальный результат |
|---|---|---|
| Явные JPS/Gradle/Maven | Публичные BuildViewManager / ExternalSystemRunConfigurationViewManager | Только фактический EventResult |
| Автоматическая JPS | Java-optional BuildManagerListener, automatic=true | NOT_EXECUTED как неизвестный результат; без Success/Failure |

Автоматический провайдер отдельно обозначается в диагностике как наблюдение
процесса без результата. Он не связывает сообщения компилятора с UUID и не
предлагает недоказанный зелёный или красный итог. Java отсутствует — провайдер
недоступен; core продолжает работать.

Старый ProjectTask visual producer удаляется только после наблюдения всех
явных путей. Каждый SDK путь имеет один producer; оба используют общий reducer
и writer, project leases и один проверенный BuildRootTracker. Временные SDK IDs
не попадают в runtime snapshots. Будущая поддержка результата автокомпиляции
потребует публичной точной связи SDK, отдельного теста и новой проверки.

## Явные сборки в Run tool window

MavenShCommandLineState в SDK263 пересылает события собственного BuildView
в публичный ExternalSystemRunConfigurationViewManager. Поэтому один адаптер
наблюдает обе публичные BuildProgressObservable; один tracker удаляет дубликаты
по точному SDK root ID. Internal registrar, заголовки и временные окна не нужны.

## Доказательство перед заменой

В изолированной IU263.7307.200 выполнены ручная JPS, автоматическая JPS,
Gradle import, отдельная Gradle `classes`, делегированная `CompileDirty` и
Maven `validate` без сети. Явные сборки дали парные root start/finish с одним
SDK ID. Импорт Gradle дал только RESOLVE_PROJECT, без BuildView roots.
Отдельная задача Gradle и Maven использовали Run-view manager, делегированная
Gradle и ручная JPS — основной BuildViewManager. Старый ProjectTask ID отличался
от BuildView ID; сопоставление между ними не добавлялось.
