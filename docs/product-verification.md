# Проверка KGBoard

Контрольная точка C1, версия **3.7.0**, 17 ноября 2026. Текущий этап —
[надёжная индикация IDE](superpowers/plans/2026-11-17-ide-focus-scope.md).
Это промежуточная проверка; A/B/C1/E2 выполнены, C2–C3 и финальная E3 продолжаются.
D1–D3 отложены. Таблица A5 ниже сохранена как история проверки версии 3.3.0.

## Выполненные проверки

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
исторической сборке A5. После B/C/E финальная матрица будет повторена. Во всех UI-запусках
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

## Пакет контрольной точки

`build/distributions/KGBoard-3.7.0.zip`, 965478 байт.

SHA256: `3c98571eb9a1e9e54f59e1170b871a4643fb2a1ca8347b9cef1d30aa6172484d`.

Descriptor и встроенные метаданные содержат версию 3.7.0 и диапазон `263.*`.
Starter, Driver, JUnit, MockK, Kodein и SDK simulator не входят в runtime.
Рекомендация Gradle убрать `until-build` не применена: совместимость намеренно
ограничена проверяемой веткой. Исторический SHA версии 3.3.0 сохранён в доказательствах A5;
одноимённый локальный файл мог быть пересобран следующими этапами и не является
текущим проверенным дистрибутивом. Публикация, установка в рабочую IDE и перезапуск
пользовательских приложений не выполнялись.

## Непроверенные возможности

- Русская native UI локализация — NotRun; проверены только парность ключей и
  наличие переводов. Совместимый языковой пакет в тестовую IDEA не установлен.
- Полная финальная UI/workflow матрица после C2/C3 — E3; текущая cross-IDE проверка
  покрывает core, загрузку интеграций, Settings и диагностику, без build pipeline claims.
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
