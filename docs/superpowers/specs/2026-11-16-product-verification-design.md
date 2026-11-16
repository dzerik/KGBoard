# KGBoard: проверка интерфейса, эксплуатации и совместимости

Статус: письменный дизайн пакета E утверждён пользователем. Сопровождает все пакеты
[product evolution](2026-11-16-product-evolution-design.md).

## Уровни доказательств

1. Pure/unit: device matching, targets, schema migration, profiles, decision reasoning,
   bounded queues и последовательности событий с управляемым временем.
2. SDK fixtures: настоящие subscriptions, SM roots, selection/close, initial dumb state,
   Apply/Cancel и serializers. Helper-тесты не заменяют адаптацию SDK facts.
3. Actual IDE UI: установленный ZIP, нативные темы, Settings, popup/Tool Window, окна.
4. SDK simulator: TCP negotiation, inventory, ошибки, задержки, reconnect и реальные writes.
5. Hardware: конкретное устройство через конкретный OpenRGB server.

Каждый отчёт явно указывает уровень. Offscreen fixture с системным LAF не является
визуальной проверкой Light/Dark IDEA и не доказывает отсутствие внешнего scroll pane.
Успех TCP write означает отправку команды, но не подтверждает видимый физический цвет.

## Автоматические проверки

Сохраняются полный JUnit suite, buildPlugin, verifyPluginConfiguration и PluginVerifier.
До изменений повторяется baseline2c7eec0 с локальным SDK; failures окружения не выдаются
за failing regressions. TDD закрепляет все изменения поведения до реализации.

SDK simulator находится только в test/integration tooling, не пакуется в plugin ZIP.
Он поддерживает controller identity, named LED layouts, несколько устройств, протоколы0–5,
reset, заблокированную запись и перестановку inventory. Каждый запрос имеет ограниченный
timeout; ожидание в тестах синхронизируется latch/event, а не фиксированным sleep.

Проверяется отдельно malformed/обрезанный controller payload. В protocol0 нет vendor:
fixtures должны воспроизводить реальный формат, а не копировать ошибку parser.

Actual UI запускается официальным Starter/Driver через testIdeUi. В Linux разрешён
Xvfb; SDK/IDE distributions кешируются и повторно используются. Тестовые зависимости
не попадают в runtime. Фикстуры проектов локальные, без клонирования произвольных repos.
[JetBrains UI integration testing](https://plugins.jetbrains.com/docs/intellij/integration-tests-ui.html).

Native UI matrix: Light/Dark,100/150/200% scaling, viewport900×700 и1200×900, только
клавиатура, логичные focus traversal и accessible names. Основные формы и error/empty
states сохраняются в screenshots вместе с IDE build/theme/scale. Прежние fixture PNG
остаются историческим аудитом, а не эталоном новой нативной темы.

## Последовательности эксплуатации

- Первый Connect → Device Test → выбор цели → Preview → Apply.
- Test → немедленные Cancel/OK/Apply/Disconnect/Disable; смена устройства/endpoint;
  повторный Test; закрытие диалога во время заблокированного write.
- Два окна проектов, переходы без application deactivation, модальное Settings окно,
  закрытие фонового/активного проекта и последний editor.
- Run+Debug+Tests+Build одновременно; breakpoint/resume; отмена, start failure,
  suite setup failure, ignored-only и discovered-but-never-started tests.
- Изображение, нет редактора, файл удалён, документ изменён, smart/dumb transitions.
- Server reset/restart, тот же LED count с другой раскладкой, переставленные devices,
  missing/duplicate identity, смена USB location и fingerprint raw targets.
- Shutdown с активным Preview на основном и preview-only устройстве; deadline3с.

Тесты причин и фактических frame writes связаны: правильный snapshot без правильного
последнего отправленного кадра не считается достаточным результатом.

## Сон/пробуждение и время

После resume перед новой записью переоцениваются connection session, inventory,
bindings и mappings. Старые session callbacks не применяются. Ручной Disconnect
сохраняется и после пробуждения. OS sleep notifications используются, где они доступны;
разрыв абсолютной разницы wall/monotonic elapsed более10000мс также инициирует сверку
перед следующим выводом. При отсутствии OS notifications и разрыве не более10000мс
остаётся обычный inventory probe с периодом5с; это ограничение проверяется в OS matrix.

Длительности имеют monotonic deadline и wall-clock anchor. Остаток — неотрицательный
минимум monotonic remaining и wall remaining: откат часов не продлевает фазу, сон
учитывается wall-clock интервалом. Перевод часов вперёд может завершить дедлайн;
это явно записывается в diagnostics. Animation position считается монотонным временем.

Истёкшие до сна transient сигналы не проигрываются после него. Pomodoro выполняет
максимум один переход просроченной фазы и начинает следующую от текущего времени;
серия пропущенных перерывов не проигрывается. Stop/Skip меняют generation до публикации.

Tests двигают wall и monotonic clocks независимо: обычный tick,8часов сна, часы назад,
часы вперёд, Stop/Skip перед resume и неизвестная OS sleep capability.

## Cross-IDE проверка

Три Linux targets из integration spec проходят binary verifier и actual launch;
optional dependencies отключаются по одной и вместе. IDE distributions скачиваются
по зафиксированным версиям и кешируются; новая версия не выбирается по слову latest.
Windows/macOS имеют отдельные строки smoke/hardware matrix и не помечаются Passed
по результату Linux. Поддержка конкретной IDE и аппаратная совместимость — разные поля.

Отчёт содержит product/build/JBR/OS, plugin version, feature/capability, command,
status и ссылку на лог/скриншот. Допустимые статусы: NotRun, Passed, Failed, Blocked.
Пустая версия или отсутствующий лог не позволяют автоматически поставить Passed.

## Аппаратный протокол

Перед записью фиксируются модель устройства, OpenRGB version, SDK endpoint, negotiated
protocol, идентификация выбранного устройства и выбранные LED. Не запускается проверка
по произвольному inventory index без выбора устройства.

Последовательно выполняются первый Connect, named target, Preview→Cancel, focus pulse,
два проекта, параллельные операции, серверный restart, перестановка устройств,
сон/пробуждение и shutdown. Для каждого шага записывается наблюдаемый результат.
Оборудование без serial/location проверяется отдельно либо остаётся NotRun.

Текущее состояние: локального openrgb executable нет,127.0.0.1:6742 отказал в соединении;
модель/версия/другой endpoint запрошены у пользователя. До появления этих данных
аппаратные строки остаются Blocked. Это не блокирует программные пакеты и сборку ZIP,
но в документации не появляется заявление о проверенной клавиатуре.

## Условия выпуска программного результата

Полная suite и сборка проходят, ZIP version совпадает с gradle.properties, descriptor
ограничен263.*, verifier проходит на заявленных IDE. Нет тестовых библиотек и simulator
в архиве. Проверки нового UI выполнены в реальной теме; hardware gaps названы явно.
CHANGELOG и инструкции описывают миграцию, однократное подтверждение старого устройства,
новые профили и capabilities. Публикация/tag/push требуют отдельного запроса пользователя.
