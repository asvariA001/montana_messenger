# Сборка Montana для Android

Одна дорога на каждом компьютере: закреплённые средства (`scripts/toolchain.json`), их установка (`scripts/setup.py`)
и сборка (`scripts/build.py`). Общее место сборки — Лотербург: оттуда сборка уходит на сайт и на телефоны, и писать код
можно с любого компьютера, который пушит в общее хранилище `/home/anna/repos/montana-android.git`.

## Средства

`scripts/toolchain.json` держит версии и контрольные суммы: JDK Temurin 17.0.20.1+1, Android cmdline-tools 22.0,
build-tools 35.0.0, платформа android-35, NDK 27.2.12479018, Kotlin 2.1.21, cargo-ndk 4.1.2. Компилятор Rust — тот,
что закрепило само ядро (`Montana Protocol/Code/rust-toolchain.toml`), здесь он не повторяется.

cmdline-tools — 22.0, а не 23.0: в 23.0 sdkmanager передаёт каждый вызов «Android CLI», который скачивается при запуске
без закреплённой версии и по умолчанию отправляет Google сведения о командах. Kotlin — 2.1.21, а не новее: d8 из
build-tools 35.0.0 читает метаданные Kotlin только до 2.1.

- `python3 scripts/setup.py` — ставит всё; уже стоящее не трогает, другую версию называет и останавливается.
- `python3 scripts/setup.py --apk-only` — всё, кроме дороги ядра (NDK, Rust, cargo-ndk): для компьютера, который пишет
  и проверяет Kotlin и ставит на телефон, а ядро собирает Лотербург.

Где лежит: на Маке — `~/Library/Montana-tools/jdk-17`, `~/Library/Montana-tools/kotlinc`, SDK в `~/Library/Android/sdk`;
на Лотербурге — всё в `/opt/montana-tools`, группа `mtdev`.

## Ядро рядом

Библиотека `native/montana-jni` собирает крейты ядра по пути `../../../Montana Protocol/Code/crates`, поэтому рядом
с этим хранилищем лежит папка `Montana Protocol` — дерево ядра ровно на коммите `protocol.commit` из `toolchain.json`,
без правок. Иначе сборка ядра откажет и назовёт нужный коммит. На Маке это рабочее дерево ядра:
`git -C ДЕРЕВО-ПРОТОКОЛА worktree add --detach "ПАПКА/Montana Protocol" КОММИТ`.

## Команды

- `python3 scripts/build.py --check` — ресурсы и Kotlin: код компилируется (без ядра, около минуты).
- `python3 scripts/build.py` — ядро и APK в `build/Montana.apk`; `--install` — поставить на телефон по adb.
- `python3 scripts/lauterbourg.py [ВЕТКА или КОММИТ]` — общая сборка на Лотербурге: сборка, перенос APK со сверкой хеша,
  выкладка на сайт и сверка того, что сайт отдаёт. `--install` — ещё и на телефон, `--no-site` — без сайта.
  Перед этим — `git push`.
- `scripts/build.sh` и `scripts/build-core.sh` остались переходниками на `build.py`.

## Лотербург — общее место сборки

- `/srv/android/montana-android` — рабочая копия общего хранилища; `/srv/android/Montana Protocol` — ядро
  на закреплённом коммите (из `/var/backups/montana-protocol.git`).
- `python3 /srv/android/build [REF]` — одна сборка за раз (замок): берёт REF из общего хранилища (по умолчанию main),
  подводит ядро к закреплённому коммиту, собирает с пониженным приоритетом (на машине живёт личный узел), подписывает
  общим ключом и кладёт в `/srv/android/out/НОМЕР-КОММИТ/` и `/srv/android/out/latest.json`.
- Анна: `ssh anna@89.116.29.95 python3 /srv/android/build` — та же сборка; APK лежит в `/srv/android/out`.
- Ключ подписи — `/srv/android/keys/montana.keystore`, пароль рядом в `montana.keystore.pass`; папка открыта только
  группе `mtdev`. Ключ — паспорт приложения: обновление встаёт поверх только с той же подписью. Сборку с прежним
  отладочным ключом нужно один раз удалить с телефона, дальше всё ставится поверх.

## Сайт

Каждая общая сборка сразу ложится на сайт: `https://montana.quest/download/android/Montana-НОМЕР-КОММИТ.apk`,
новейшая — ещё и `Montana.apk`, её паспорт — `latest.json`; дверь Android на главной странице читает `latest.json`.
Выкладывает `lauterbourg.py` с компьютера, у которого есть ключ к Москве и Амстердаму (агенты автора). Сайт считается
получившим сборку, только когда отдаёт её байты с тем же хешем.

## Номер версии

Правило — в `scripts/version`: пока Android догоняет iOS, он показывает `v1.0 (N → 1277)`, где N — число коммитов
на main; когда паритет закрыт (`docs/parity-1277.md`), номер становится 1277.
