# Проверка KGBoard

Программная контрольная точка **3.9.2**, 17 ноября 2026. Реализован сохранённый
объём A/B/C/E: [надёжная индикация IDE](superpowers/plans/2026-11-17-ide-focus-scope.md).
D1–D3 и ProfileUiTest отложены. Заключительный обзор всей ветки выявил два
Important-дефекта; оба воспроизведены и исправлены за один проход. Critical и Minor
не выявлены. Этот документ различает проверки финального ZIP и историю этапов.
[Журнал принятых решений и цены ошибки](implementation-decisions.md).

## Итоговая программная проверка

| Проверка | Результат | Доказательство |
|---|---|---|
| Повторная native регрессия, 3.9.2 | 4 сценария: Diagnostics Light/Dark и оба Settings workflow | [Результаты](assets/product-verification/review-fixes/native-results.txt) |
| Чистая сборка без build cache, 3.9.2 | Passed; ZIP побайтово совпал с основной сборкой | [Cold build](assets/product-verification/review-fixes/cold-build.txt) |
| Unit/platform и инструменты отчёта, 3.9.2 | 394 JUnit + 6 Python, без failures/errors/skips | [Software](assets/product-verification/review-fixes/software.txt) |
| Plugin Verifier, 3.9.2 | Compatible: IU263.7307.200, WS263.7307.202, PY263.7307.206 | [Software](assets/product-verification/review-fixes/software.txt) |
| Полный native прогон IDEA, 3.9.1 | 20 сценариев: темы/масштабы, Preview/Apply/Cancel, диагностика, JPS/Gradle/Maven, Git, редактор, фокус | [Результаты](assets/product-verification/final/IU/native-results.txt) |
| Два окна и читаемость HEX на 200%, 3.9.1 | Ещё 3 native сценария | [Дополнительные проверки](assets/product-verification/final/IU/supplemental-results.txt) |
| WebStorm без Java, 3.9.1 | 5 native сценариев core и optional providers | [WS](assets/product-verification/final/WS/native-results.txt) |
| PyCharm без Java, 3.9.1 | 5 native сценариев core и optional providers | [PY](assets/product-verification/final/PY/native-results.txt) |
| Чистая сборка без build cache, 3.9.1 | Passed; ZIP побайтово совпал с инкрементальной сборкой | [Cold build](assets/product-verification/final/cold-build.txt) |

Во всех **33 native запусках 3.9.1** устанавливался один main ZIP с SHA256
`601e7609b9d4c8646d78fe25fb8de0c4de8ce2e869ce295e4c684f710629b12a`. Рядом сохранены все 33 launch properties. Test-only observer
развивался между дополнительными проверками и в main ZIP не входит.
Проверки выполнялись на Linux/X11, в изолированных config/system, временных проектах
и с тестовым SDK на случайном loopback-порту. Рабочая IDEA и OpenRGB не менялись.

Нативные снимки контрольной точки 3.9.1: [Dark100%,900×700](assets/product-verification/final/IU/settings-dark-100-en-900x700.png),
[Light200%,1200×900](assets/product-verification/final/IU/settings-light-200-en-1200x900.png).
Оба размера проверены для каждой пары Light/Dark ×100/150/200%. Видимость Preview,
реальный scale и геометрия проверяются assertions. Геометрия HEX дополнительно
проверена по текущему шрифту/insets: доступно63px, требуется46px в обеих темах на200%.

Read-only runtime наблюдения подтверждают text→image→close→delete без старых
текстовых фактов, фактическую деактивацию/возврат фокуса X11, сохранение владельца
в modal Settings и primary→secondary→primary между двумя настоящими окнами.
Закрытие фонового проекта сохраняет активный. Эти тесты не доказывают видимый
аппаратный цвет: SDK writes=0; renderer и доставка отдельно проверены suite.

## Исправления заключительного обзора, 3.9.2

[Регрессии RED→GREEN](assets/product-verification/review-fixes/regressions.txt): обычное событие
IDE на границе Pomodoro больше не теряет Wake; Stop/Skip не продвигают фазу дважды,
следующий отсчёт и опрос устройств продолжаются. Debug ждёт подтверждённый результат
процесса: detach с синтетическим exit0 и неподтверждённый standalone stop не становятся
Success. Выгрузка Debug и запоздалые provider facts не стирают подтверждённый результат.

Четыре повторных native сценария финального ZIP прошли: диагностика Light/Dark,
Identify/Preview/Cancel и Apply/Disconnect/Disable. Все четыре launch properties
содержат SHA256 текущего пакета; полный product/UI прогон 33 сценариев выше относится
к 3.9.1.

SDK-тесты используют настоящий ProcessHandler и изолированный message bus; это
проверка адаптеров, а не native debugger backend. Публичные Experimental предупреждения
BuildProgressObservable остаются: по три использования на IDE; Verifier не обнаружил
использования Internal/Deprecated API в main plugin.

Исторические контрольные точки ниже сохранены со своими версиями и доказательствами.

## История A5: интерфейс3.3.0

Среда: Linux, IntelliJ IDEA `IU263.7307.200`, JBR `25.0.4.1+1-b635.79`.

| Проверка | Результат | Доказательство |
|---|---|---|
| Модульные и платформенные тесты | Passed: 302, без failures/errors/skips | [Сборка и пакет](assets/product-verification/settings-software.txt) |
| Нативная форма настроек | Passed: Light/Dark × 100/150/200% × два размера окна | [Матрица](assets/product-verification/settings-matrix.txt) |
| Identify и Preview одной клавиши | Passed: SDK получил белый 30%, затем выбранный цвет с сохранением фона | [Workflow](assets/product-verification/settings-workflows.txt) |
| Cancel, Apply, Disconnect, Disable | Passed: черновик отменён; назначение применено; Preview отозван; XML сохранён | [Workflow](assets/product-verification/settings-workflows.txt) |
| Plugin Verifier | Passed: Compatible с указанной IDEA | [Сборка и пакет](assets/product-verification/settings-software.txt) |

Размеры окон — **900×700 и 1200×900 логических единиц**. На X11 при масштабе 150%
проверяются реальные AWT-размеры 1350×1050 и 1800×1350, при 200% — 1800×1400
и 2400×1800. Тесты проверяют фактический scale, геометрию окна, выбор вкладки
клавиатурой и видимость Preview. Примеры: [Dark 100%](assets/product-verification/settings-dark-100-en-900x700.png),
[Light 200%](assets/product-verification/settings-light-200-en-1200x900.png).

У каждого запуска сохранены build/JBR/OS и SHA256 установленного ZIP в соседних
`.properties`. Матрица фиксирует более раннюю сборку 3.3.0; workflow также относится к
исторической сборке A5. Повторная матрица после B/C/E записана выше в контрольной точке 3.9.1. Во всех UI-запусках
используются изолированные config/system, временный проект и тестовый SDK на
случайном loopback-порту. Настройки рабочего OpenRGB не менялись.

## Диагностика и корректность runtime, 3.6.0

[Программные проверки](assets/product-verification/diagnostics-software.txt): **352 теста**,
без failures/errors/skips, Plugin Verifier — Compatible. Проверены композиция,
Pending/Sent/Cached, объединение очереди без фиктивной доставки, старые tokens,
отсутствие цикла ack→render и двойные часы. Сон проверен управляемыми часами,
физическое пробуждение ОС остаётся NotRun.

Нативные сценарии Light/Dark проверяют дочернее окно диагностики из Settings,
меню соединения, клавиатурный доступ, Copy/Clear и по 20 открытий/закрытий Tool Window
без дополнительных SDK-записей. [Результат и ограничения снимков](assets/product-verification/diagnostics-native.txt).
Copy сверяет версию установленного ZIP и исключает имя тестового проекта и serial.
Снимки показывают общую панель в нативном дочернем окне:
[Dark](assets/product-verification/diagnostics-child-dark.png),
[Light](assets/product-verification/diagnostics-child-light.png).

## Необязательные интеграции, 3.7.0

[364 теста и Verifier трёх IDE](assets/product-verification/integrations-software.txt)
прошли без failures/errors/skips. Git упакован отдельным optional content module:
загрузчик 263 добавлял legacy optional Git alias в строгие зависимости core.

Нативные проверки core, Settings, диагностики и статусов интеграций:
[IDEA: 4 сценария](assets/product-verification/integrations-IU-native.txt),
[WebStorm: 5](assets/product-verification/integrations-WS-native.txt),
[PyCharm: 5](assets/product-verification/integrations-PY-native.txt).
Во всех сценариях SDK-записей нет; используется тестовый loopback server.
Проверяются реальные загруженные plugins, а не только сохранённые флаги.

Ограничения продуктов: IDEA требует Java; WebStorm и PyCharm требуют SM через
обязательный языковой backend. Эти режимы отсутствия отмечены **Blocked**,
а сохранение обязательного SM проверено отдельно. Настоящее отсутствие SM
проверено в IDEA, отсутствие Java — в WebStorm/PyCharm. Dynamic EP removal
проверен платформенным fixture; нативный unload всего SDK plugin не заявляется.

## Локальный статус Git, 3.8.0

[381 тест и Verifier трёх IDE](assets/product-verification/git-software.txt) прошли.
Проверены реальные локальные repos/clones: ahead, behind, diverged, конфликт,
newline rename, detached, unborn и отсутствие upstream. Последние состояния
не становятся Synced. Счётчики разных репозиториев не складываются.

SDK fixture проверяет выбранный в IDE Git, NUL-байты, отмену процесса и отказ
при ненулевом exit code. На репозиторий допускается один процесс и одно
отложенное обновление, timeout10с. Старый ответ ветки отклоняется. Новые строки
диагностики и Copy проверены на уровне модели; нативная проверка входит в E3.
Native проверки C1 остаются доказательствами версии3.7.0.

## Источники сборки, 3.9.0

[388 тестов и три Verifier](assets/product-verification/builds-software.txt) прошли.

[Нативные наблюдения](assets/product-verification/builds-native.txt) подтверждают
ручную JPS, отдельную и делегированную Gradle, Maven `validate`: по одному
runtime UUID со стартом и завершением. Gradle import не создаёт build root.
Публичные BuildViewManager и Run-view manager используют один tracker;
старый ProjectTask producer удалён после проверки всех явных путей.

Автоматическая JPS проверена отдельно: точный UUID/start/finish из публичного
`intellij.java.compiler.impl`, результат недоступен. Она завершается как
NOT_EXECUTED и не выдаёт выдуманный Success/Failure. Ограничение видно в UI.
Разные тестовые ZIP версии3.9.0 имеют [отдельные SHA запуска JPS](assets/product-verification/builds-jps-launch.properties),
[Gradle](assets/product-verification/builds-gradle-launch.properties) и
[Maven](assets/product-verification/builds-maven-launch.properties).

[Четыре нативных сценария IDEA](assets/product-verification/builds-core-IU-native.txt)
прошли после исправления переноса вкладок диагностики: клавиатурный порядок
сохранён, Build roots Available, SDK writes=0. Новая cross-IDE матрица входит в E3.
Public BuildProgressObservable помечен Experimental; диапазон остаётся263.*,
а обновление SDK требует повторения verifier и native matrix.

## Пакет контрольной точки

`build/distributions/KGBoard-3.9.2.zip`, 1030607 байт.

SHA256: `2de5b2bd652ad44f23bc982a36d1882b93d0a3882d5c1b22e8147b9da635cd45`.

Descriptor и встроенные метаданные содержат версию 3.9.2 и диапазон `263.*`.
Starter, Driver, JUnit, MockK, Kodein и SDK simulator не входят в runtime.
Рекомендация Gradle убрать `until-build` не применена: совместимость намеренно
ограничена проверяемой веткой. Исторический SHA версии 3.3.0 сохранён в доказательствах A5;
одноимённый локальный файл мог быть пересобран следующими этапами и не является
текущим проверенным дистрибутивом. Публикация, установка в рабочую IDE и перезапуск
пользовательских приложений не выполнялись.

## Непроверенные возможности

- Русская native UI локализация — NotRun; проверены только парность ключей и
  наличие переводов. Совместимый языковой пакет в тестовую IDEA не установлен.
- Реальный native overlap Run/Debug/SM процессов — NotRun; независимые операции,
  отмена и SDK producers проверены unit/platform fixtures, а build pipelines — native.
- Windows/macOS — NotRun. Фокус native Wayland также NotRun: автоматизация
  выполнялась в изолированном Xvfb/X11, а не на рабочем Wayland-сеансе пользователя.
- Физический вывод нового плагина на EVision `0C45:5004` — NotRun. Выполнены
  read-only запросы и офлайн-повтор метаданных. SDK-пакеты не доказывают отсутствие
  мерцания; Custom/AutoSave не эквивалентен Direct. [Драйвер EVision](https://github.com/CalcProgrammer1/OpenRGB/blob/0fca93e4544f943d4d7ec8073dba4e47c18ef54b/Controllers/EVisionKeyboardController/RGBController_EVisionKeyboard.cpp).

## Воспроизведение

```bash
./gradlew test buildPlugin verifyPlugin --offline -PlocalPlatformPath=/path/to/pinned/idea
tools/run-ui-tests.sh integrationTest -PlocalPlatformPath=/path/to/pinned/idea
python3 -m unittest discover -s tools/tests
python3 tools/verify-evidence.py docs/product-verification.json
```

Для native UI на Linux нужны Xvfb и openbox либо twm. Машиночитаемый
[отчёт](product-verification.json) допускает только Passed/Failed/NotRun/Blocked;
валидатор проверяет доказательства и не повышает статус автоматически.
