# Parlons Node — посібник оператора

Для `parlons-node.jar` **0.2.138** (тег випуску `node-v0.2.138`), що містить DEX-ретранслятор
**0.1.5**. Кожен прапорець, порт, типове значення й кінцева точка в цьому посібнику взяті з вихідного коду
репозиторію `eurobuddha/maxima` (`node/`, `server/`, `core/`, `account/`, `dexrelay/`) і
скрипту розгортання `ops/deploy-parlons-node.sh`. Якщо значення немає в коді, посібник прямо про це каже
і не вгадує.

Імена хостів у прикладах (`node.example.com`, `peer1.example.net` тощо) — це
заповнювачі. Замініть їх своїми.

Для кожного завдання є пронумерована процедура **Як …** з точними командами. Команди розраховані на
сервер із Debian або Ubuntu, шляхи скрипту розгортання (`/opt/maxima`, `/var/lib/parlons-node`),
службового користувача `maxima` і типові порти. Якщо у вас інакше, змініть їх.

Пов'язані посібники:

- [Посібник користувача Parlons!](parlons.md): застосунки й ретранслятори, якими вони користуються
  ([вкладка «Мережа»](parlons.md#19-the-network-tab-relays-and-helping-the-network)).
- [Посібник порталу Parlons Cloud](parlons-cloud-portal.md): під'єднання телефона до облікового запису на
  цьому вузлі та служба `parlons-tenants`.

---

## Зміст

1. [Що таке Parlons Node](#1-what-a-parlons-node-is)
2. [Архітектура](#2-architecture)
3. [Вимоги](#3-requirements)
4. [Завантаження й перевірка jar-файлу](#4-download-and-verify-the-jar)
5. [Три способи встановлення](#5-three-ways-to-install)
6. [Перший запуск](#6-first-start)
7. [Юніт systemd (з коментарями)](#7-the-systemd-unit-annotated)
8. [Довідник налаштувань: усі властивості](#8-configuration-reference-every-property)
9. [Порти](#9-ports)
10. [Структура каталогу даних](#10-data-directory-layout)
11. [TLS-фронт: Caddy, nginx, Apache](#11-tls-fronting-caddy-nginx-apache)
12. [Кейп ретранслятора і меш-мережа](#12-the-relay-cape-and-the-mesh)
13. [Режим MegaMMR і кореневий вузол (rootnode)](#13-megammr-mode-and-the-rootnode)
14. [Шлюз гаманця та його токен](#14-the-wallet-gateway-and-its-token)
15. [Розміщення NFT і `parlons.node.public`](#15-nft-hosting-and-parlonsnodepublic)
16. [Кейп DEX-ретранслятора (Dexicon)](#16-the-dex-relay-cape-dexicon)
17. [Обліковий запис, під'єднання й локальна панель](#17-the-account-pairing-and-the-local-panel)
18. [Допоміжний процес keets (міст KeetChat)](#18-the-keets-sidecar-keetchat-bridge)
19. [MDS (MiniHUB) на вузлі](#19-mds-minihub-on-the-node)
20. [Сід-фрази, ідентичність і резервні копії](#20-seeds-identity-and-backups)
21. [Адміністративний RPC](#21-the-admin-rpc)
22. [Оновлення й відкат](#22-upgrading-and-rolling-back)
23. [Перевірка ззовні](#23-verifying-from-outside)
24. [Моніторинг і журнали](#24-monitoring-and-logs)
25. [Нотатки щодо брандмауера](#25-firewall-notes)
26. [Розв'язання проблем](#26-troubleshooting)
27. [Контрольний список безпеки](#27-security-checklist)
28. [Настільні застосунки, що вбудовують цей jar](#28-desktop-apps-that-embed-this-jar)
29. [Глосарій](#29-glossary)

---

## 1. Що таке Parlons Node

Parlons Node — це один Java-процес, який містить:

- **Повний вузол Minima.** Це форк `minima-core` (`org.minima`), вбудований у jar як
  `node/libs/minima-node.jar`. Він перевіряє ланцюг, спілкується з іншими вузлами через P2P і має гаманець.
- **Ретранслятор Maxima, який називають «кейпом» (cape).** Це той самий незалежно написаний код ретранслятора, що й
  `maxima-server.jar` (`:core` + `:server`). Телефони й інші клієнти самі підключаються до нього. Він також бере
  участь у меш-мережі ретрансляторів.
- **Шлюз гаманця.** Це захищений проксі `POST /cmd` зі списком дозволених команд, через який телефони можуть
  користуватися MegaMMR цього вузла, щоб читати баланси й транслювати в мережу транзакції, які вони підписали самі.
- **Розміщення NFT.** Вузол віддає файли зображень, на які вказують токени, за адресою `/nft/...` на порту шлюзу.
- **Обліковий запис Parlons.** Це той самий `ParlonsCore`, що працює в `parlons-cloud.jar`: під'єднання,
  чат, контакти, push-сповіщення й локальна вебпанель. Його гаманець — це власний гаманець вузла.
- **Кейп DEX-ретранслятора Dexicon** (необов'язково). Це невелика HTTP-служба, яка розсилає підписані
  конверти книги заявок. Вона працює в тому самому процесі.
- **Міст чату keets** (необов'язково). Він з'єднує чат облікового запису з KeetChat, коли вбудований
  вузол запускає допоміжний процес keets.

**Однією сід-фразою керується весь вузол.** Під час першого запуску ідентичність ретранслятора Maxima й облікового запису
виводиться із сід-фрази гаманця вузла, а потім закріплюється в `identity.txt`
(див. [§20](#20-seeds-identity-and-backups)).

Кому це потрібно? Більшості людей — ні. Parlons Cloud (`parlons-cloud.jar`) тримає постійно ввімкнений обліковий запис
приблизно в 1 GB оперативної пам'яті. Запускайте Parlons Node, коли хочете мати повну копію ланцюга, обслуговувати
мережу ретрансляторів і надавати телефонам шлюз гаманця.

---

## 2. Архітектура

```
                        Internet
   ┌───────────────┬──────────────┬─────────────────────┐
   │ Minima peers  │ phones /     │ browsers, phones,    │
   │ (TCP p2p)     │ Maxima       │ Dexicon clients      │
   │               │ clients      │ (HTTPS 443)          │
   ▼               ▼ (TCP 9501)   ▼                      │
┌──────────────────────────────────────────┐   ┌─────────┴──────────────┐
│           parlons-node.jar (one JVM)     │   │  TLS front (Caddy /    │
│                                          │   │  nginx / Apache)       │
│  Minima node (org.minima)   :9001 public │   │                        │
│    ├─ admin RPC  127.0.0.1:p2p+4 (opt-in)│◄──┤ /parlons-node/ ──► 9585│
│    └─ MDS        127.0.0.1:p2p+2 (opt-in)│   │ /dex-relay/    ──► 9602│
│                                          │   └────────────────────────┘
│  Maxima relay cape           :9501 public│
│    (TCP, plus UDP STUN on the same port) │
│                                          │
│  Wallet gateway + NFT  127.0.0.1:9585    │
│  Account panel + API   127.0.0.1:9587    │
│  DEX relay cape        127.0.0.1:9602    │  (opt-in; loopback by default when
│                                          │   parlons.dex.public is set)
│  Parlons account (pairing, chat, push)   │
│  keets chat bridge (when -keets is on)   │──► keets sidecar (Node.js child)
└──────────────────────────────────────────┘
```

| Компонент | Код | Слухає на (типово) | Типовий стан |
|---|---|---|---|
| Вузол Minima | вбудований `node/libs/minima-node.jar` | TCP `9001` (`parlons.node.port`) | увімкнено |
| Адміністративний RPC | `node/.../AdminRpc.java` | `127.0.0.1:<p2p+4>`, завжди loopback | вимкнено (`parlons.node.rpc=false`) |
| MDS / MiniHUB | MDS форку Minima | `127.0.0.1:<p2p+2>` (файли), `<p2p+3>` (команди) | вимкнено |
| Кейп ретранслятора | `server/.../RelayRuntime`, `RelayServer` | TCP `9501`, а також UDP STUN на тому самому порту | увімкнено |
| Шлюз гаманця і `/nft` | `node/.../NodeGateway.java`, `NftStore.java` | `127.0.0.1:9585` | увімкнено (запускається, щойно гаманець готовий) |
| Панель облікового запису | `account/.../ParlonsLocal.java` | `127.0.0.1:9587` | увімкнено |
| Кейп DEX-ретранслятора | `dexrelay/.../DexRelay.java` | `127.0.0.1:<port>`, якщо задано `parlons.dex.public`, інакше `0.0.0.0:<port>`; явно задане `parlons.dex.bind` має пріоритет | вимкнено (`parlons.dex.port=0`) |
| Міст keets | `node/.../KeetsChatBridge.java` | жодного (опитує вузол) | увімкнено лише тоді, коли вузол працює з `-keets` |

**Порядок запуску** (з `ParlonsNodeMain.main`):

1. Застосовуються власні прапорці Minima (`parlons.node.args` і `parlons.node.conf`), потім
   властивості `parlons.node.*`.
2. Запускається вбудований вузол Minima.
3. Якщо ввімкнено, запускається адміністративний RPC. Якщо він не може зайняти порт, процес зупиняється з кодом виходу 2.
4. Якщо ввімкнено, запускається кейп DEX. Він не залежить від ланцюга, тому працює ще до синхронізації вузла.
5. Фоновий потік чекає, поки сховище (vault) вузла стане доступним для читання (до 120 с). Потім він виводить
   ідентичність і запускає кейп ретранслятора, шлюз і сховище NFT, а також обліковий запис — саме в такому порядку.
6. Кожні 30 с у журнал записується рядок пульсу (heartbeat).

---

## 3. Вимоги

### Java

- **Java 11 або новіша.** Jar скомпільовано в байт-код Java 11 (`node/build.gradle`).
- **Рекомендовано Java 21 або новішу.** На JDK 21+ кейп ретранслятора використовує віртуальні потоки, а його
  типовий ліміт з'єднань зростає з 512 до 4096 (`RelayServer.DEFAULT_MAX_CONNECTIONS_VIRTUAL`).
- **Модуль `jdk.httpserver` обов'язковий.** Шлюз, адміністративний RPC, панель і
  кейп DEX використовують `com.sun.net.httpserver`. Повний JRE має цей модуль. Якщо ви збираєте урізане
  середовище виконання за допомогою jlink, додайте його:
  `--add-modules java.se,jdk.unsupported,jdk.httpserver`. Без нього адміністративний RPC завершується помилкою
  `NoClassDefFoundError`, і вузол відмовляється працювати.
- Скрипт розгортання встановлює `default-jre-headless` на Debian/Ubuntu, якщо `java` не знайдено.

### Оперативна пам'ять і купа (heap)

| Роль | Оперативна пам'ять сервера | Купа Java (`-Xmx`) |
|---|---|---|
| Вузол із MegaMMR (типово; потрібен, щоб бути шлюзом гаманця) | 8 GB | щонайменше 3 GB |
| Вузол без MegaMMR (`-Dparlons.node.megammr=false`) | 2 GB | 768 MB |

Ці цифри взято з нотаток про флот вузлів у `cloud/NODE-SETUP.md`. Їх здобуто важким досвідом: вузли MegaMMR із купою 2 GB і 2,5 GB зациклювалися на помилках нестачі пам'яті,
а один із купою 3 GB аварійно завершився.

- **Завжди задавайте `-Xmx`.** Необмежена типова купа — це вірний спосіб втратити невеликий VPS.
- **Задавайте `MemoryMax` у systemd вище за купу.** Скрипт розгортання використовує купу + 512 MB. Якщо
  `MemoryMax` дорівнює `-Xmx`, cgroup убиває JVM, щойно зростає її пам'ять поза купою.
- Вузол, що аварійно перезапускається по колу, спрацьовує на `StartLimitBurst` і залишається зупиненим. Після будь-якої зміни купи перевірте
  `systemctl is-active parlons-node`.

### Диск

Використання диска зростає разом із ланцюгом. Код не задає фіксованої цифри. Також заплануйте місце для:

- завантаження знімка MegaMMR (приблизно 460 MB згідно з `cloud/NODE-SETUP.md`), поки ви його наповнюєте;
- полиці блобів ретранслятора, якщо ви її ввімкнете (`parlons.relay.blob`, у MB);
- розміщених файлів NFT (не більше 32 MB кожен).

### Мережа

- Публічна IPv4-адреса або переадресація порту на роутері для P2P-порту Minima і порту ретранслятора.
- Вихідний доступ до інтернету, щоб зв'язуватися з вузлами Minima, ретрансляторами меш-мережі й архівним вузлом.
- Доменне ім'я з TLS, якщо ви хочете публічно надавати шлюз, файли NFT або кейп DEX.

### Як перевірити сервер перед встановленням

1. Перевірте версію Java. Вона має бути 11 або новішою; 21 або новіша — краще:

   ```bash
   java -version
   ```

2. Перевірте, що модуль `jdk.httpserver` присутній. Команда має вивести один рядок:

   ```bash
   java --list-modules | grep jdk.httpserver
   ```

3. Перевірте оперативну пам'ять. Потрібно 8 GB для вузла MegaMMR або 2 GB без MegaMMR:

   ```bash
   free -h
   ```

4. Перевірте вільне місце на диску там, де буде каталог даних:

   ```bash
   df -h /var/lib
   ```

5. Перевірте, що порти, потрібні вузлу, ніхто не займає:

   ```bash
   ss -ltnp | grep -E ':(9001|9005|9501|9585|9587|9602) '
   ```

   Команда не має нічого вивести. Якщо порт 9001 займає звичайний вузол Minima, заплануйте перенесення P2P-порту вузла
   ([§9](#9-ports)). Якщо порт 9501 займає `maxima-relay.service`, заплануйте його заміну ([§5](#5-three-ways-to-install)).

---

## 4. Завантаження й перевірка jar-файлу

Випуски публікуються на GitHub у `eurobuddha/maxima` під тегами `node-v<version>`. Вони публікуються
з `--latest=false`, тому позначка «Latest» репозиторію **не** вказує на вузол. Завжди
вибирайте випуск вузла за його тегом.

1. Завантажте jar і файл контрольних сум:

   ```bash
   VER=0.2.138
   curl -fLO https://github.com/eurobuddha/maxima/releases/download/node-v$VER/parlons-node-$VER.jar
   curl -fLO https://github.com/eurobuddha/maxima/releases/download/node-v$VER/SHA256SUMS
   ```

2. Перевірте хеш SHA-256. Хеш `parlons-node-0.2.138.jar`:

   ```
   597a77225514b8ceddf1036a6016abc6d4b826e7711210c7f55de01857c9b4af
   ```

   ```bash
   echo "597a77225514b8ceddf1036a6016abc6d4b826e7711210c7f55de01857c9b4af  parlons-node-0.2.138.jar" | sha256sum -c -
   # macOS: shasum -a 256 -c  (same input)
   ```

   Має вивестися `parlons-node-0.2.138.jar: OK`. Якщо ні, видаліть файл і не запускайте його.

3. Перевірте версію. Це не запускає вузол:

   ```bash
   java -jar parlons-node-0.2.138.jar --version
   # parlons-node 0.2.138
   ```

4. Виведіть вбудовану довідку, у якій перелічено основні властивості. Це теж не запускає вузол:

   ```bash
   java -jar parlons-node-0.2.138.jar --help
   ```

### Як натомість зібрати jar із вихідного коду

1. Клонуйте репозиторій і зберіть fat jar:

   ```bash
   git clone https://github.com/eurobuddha/maxima.git
   cd maxima
   ./gradlew :node:fatJar
   ```

2. Знайдіть jar. Це `node/build/libs/parlons-node-<version>.jar`:

   ```bash
   ls node/build/libs/
   ```

3. Перш ніж розповсюджувати jar, перевірте, що він містить вашу зміну. Застарілий `core.jar` уже якось потрапляв у випуск
   під новим номером версії. Порахуйте входження символу, який ви додали в змінений клас; результат
   має бути 1 або більше:

   ```bash
   unzip -p node/build/libs/parlons-node-<version>.jar <class path> | grep -ac <symbol>
   ```

4. Запишіть його контрольну суму, щоб перевірити її ще раз на сервері перед запуском:

   ```bash
   sha256sum node/build/libs/parlons-node-<version>.jar
   ```

> Jar **не має параметрів командного рядка**. Він приймає лише аргументи `--version`/`-v`
> і `--help`/`-h`/`help`. Будь-який інший аргумент виводить довідку в stderr і завершує роботу з кодом 2.
> Кожне налаштування — це системна властивість `-D`, розміщена **перед** `-jar`.

---

## 5. Три способи встановлення

### A. Інсталятор одним рядком (почніть звідси)

На самій Linux-машині (VPS, Raspberry Pi з 64-бітною ОС, домашній сервер), від імені root або користувача з `sudo`:

```bash
curl -fsSL https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-node.sh | bash
```

Що він робить, по черзі:

1. Встановлює Java 11+ і `qrencode` (apt, dnf, apk або pacman), якщо їх немає.
2. Знаходить найновіший випуск `node-v…` на GitHub, завантажує `parlons-node-<ver>.jar` і відкидає його, якщо його sha256 не збігається з `SHA256SUMS` випуску.
3. Створює користувача `maxima` і `/var/lib/parlons-node` (режим 700), встановлює `/usr/local/bin/parlons-pair` і записує той самий захищений `parlons-node.service`, що й скрипт розгортання ([§7](#7-the-systemd-unit-annotated)).
4. Підбирає розмір вузла під машину: за 3,5 GB оперативної пам'яті або більше він запускає MegaMMR із купою 2 GB; за меншого обсягу — звичайний повний вузол із купою 768 MB. Синхронізація відбувається з `65.109.31.226:9001`.
5. Відкриває TCP 9001 (Minima) і 9501 (ретранслятор) в ufw або firewalld, якщо один із них активний.
6. Запускає вузол, чекає на адресу вашого облікового запису й виводить її повністю.
7. Просить задати адміністративний пароль для під'єднання (лише під час першого встановлення), потім просить ввести його ще раз і малює QR-код, який ваш телефон сканує в Parlons Cloud.

Параметри (вказуйте їх після `bash -s --`):

| Параметр | Дія |
|---|---|
| `--update` | Завантажити найновіший випуск і перезапустити. Повторний запуск команди встановлення робить те саме. |
| `--uninstall` | Зупинити й видалити службу та `parlons-pair`. `/var/lib/parlons-node` (ідентичність, гаманець, чати) зберігається. |
| `--no-megammr` | Звичайний повний вузол (досить 1 GB оперативної пам'яті). Вибирається автоматично на машинах із менш ніж 3,5 GB. |
| `--heap <size>` | Інший розмір купи Java, наприклад `--heap 3g`. |

Наприклад: `curl -fsSL https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-node.sh | bash -s -- --no-megammr`

Інсталятор залишає адміністративний RPC вимкненим і не задає вузлів меш-мережі ретрансляторів, DEX-ретранслятора, TLS-фронту чи допоміжного процесу keets. Для будь-чого з цього скористайтеся скриптом розгортання (B) або відредагуйте юніт ([§7](#7-the-systemd-unit-annotated)). На Mac або ПК з Windows натомість встановіть Parlons Desktop. Якщо машина взагалі не може запустити вузол, `get-parlons-cloud.sh` лише з обліковим записом працює в 1 GB ([посібник порталу Parlons Cloud](parlons-cloud-portal.md)).

#### Як встановити інсталятором одним рядком

1. Відкрийте TCP 9001 і 9501 у брандмауері вашого хмарного провайдера або переадресуйте їх на домашньому роутері. Обліковий запис працює і без них; але вони потрібні, щоб ретранслювати для інших і віддавати блоки.
2. Виконайте наведену вище команду в SSH-сеансі або в терміналі на машині. Не запускайте її зі скрипту без термінала: тоді вона пропускає кроки з паролем і QR-кодом.
3. Коли попросять, двічі введіть адміністративний пароль для під'єднання. Збережіть його; він потрібен для кожного нового запрошення для ваших власних пристроїв.
4. Коли попросять знову, введіть пароль один раз. З'являться QR-код і повне запрошення.
5. У Parlons Cloud на телефоні торкніться **Сканувати QR-код під'єднання** і відскануйте його.
6. Стежте за синхронізацією ланцюга: `journalctl -u parlons-node -f | grep heartbeat`.
7. Створіть резервну копію сід-фрази ([§20](#20-seeds-identity-and-backups)).

Якщо крок 3 або 4 було пропущено, виконайте один раз `sudo parlons-pair --set-admin-password`, а потім `sudo parlons-pair --admin`.

### B. Скрипт розгортання (з вашого власного комп'ютера)

Запускайте його з клону репозиторію на власному комп'ютері, націливши на свіжий Linux-сервер,
до якого ви маєте доступ через SSH як root. Повторний запуск оновлює вузол; встановлення й оновлення — це
та сама операція.

#### Як встановити скриптом розгортання

1. На своєму комп'ютері клонуйте репозиторій. У ньому є `ops/deploy-parlons-node.sh` і
   `ops/parlons-pair`:

   ```bash
   git clone https://github.com/eurobuddha/maxima.git
   cd maxima
   ```

2. Покладіть перевірений jar у `dist/` ([§4](#4-download-and-verify-the-jar)). Без `--jar`
   скрипт бере найновіший `dist/parlons-node-*.jar`:

   ```bash
   mkdir -p dist
   mv ~/Downloads/parlons-node-0.2.138.jar dist/
   ```

3. Перевірте, що SSH-вхід як root за ключем працює. Скрипт використовує `BatchMode=yes`, тому не може відповісти на
   запит пароля:

   ```bash
   ssh -o BatchMode=yes root@node.example.com true && echo ssh-ok
   ```

4. Якщо на сервері вже працює `maxima-relay.service` на порту 9501, додайте `--replace-relay` до наступної
   команди.
5. Запустіть скрипт. Ось типовий вузол MegaMMR із TLS-фронтом; у таблиці нижче перелічено всі прапорці:

   ```bash
   ops/deploy-parlons-node.sh root@node.example.com \
       --jar dist/parlons-node-0.2.138.jar \
       --heap 3g \
       --rootnode seed-peer.example.net:9001 \
       --rpc \
       --megammr-seed https://eurobuddha.com/mega.mmr \
       --public https://node.example.com/parlons-node \
       --dex 9602 --dex-public https://node.example.com/dex-relay \
       --peers "peer1.example.net:9501,peer2.example.net:9501"
   ```

6. Прочитайте підсумок у кінці. Він виводить адресу гаманця облікового запису, ідентичність Maxima
   (`Mx…`) і bearer-токен шлюзу. Тримайте токен у таємниці
   ([§14](#14-the-wallet-gateway-and-its-token)).
7. Налаштуйте TLS-фронт ([§11](#11-tls-fronting-caddy-nginx-apache)).
8. Під'єднайте телефон ([§17](#17-the-account-pairing-and-the-local-panel)) і створіть резервну копію сід-фрази
   ([§20](#20-seeds-identity-and-backups)).
9. Перевірте вузол з іншої машини ([§23](#23-verifying-from-outside)).

#### Що робить скрипт

Скрипт виконує такі кроки по черзі:

1. Встановлює headless JRE, якщо `java` немає, і намагається встановити `qrencode` (для QR-кодів під'єднання).
2. Створює непривілейованого системного користувача `maxima` і створює `/var/lib/parlons-node` з режимом 700.
3. Вивантажує jar у `/opt/maxima/parlons-node-<ver>.jar` і перевіряє його SHA-256. Лише після цього він
   спрямовує символьне посилання `/opt/maxima/parlons-node.jar` на новий jar.
4. Встановлює `/usr/local/bin/parlons-pair`.
5. Записує захищений юніт `/etc/systemd/system/parlons-node.service`.
6. Відкриває P2P-порт і порт ретранслятора в ufw або firewalld. Порт DEX він відкриває лише тоді, коли `--dex`
   задано без `--dex-public`, а порт шлюзу — лише з `--gateway-public`.
7. Запускає вузол і перевіряє, що порт ретранслятора слухає.
8. Необов'язково: `--seed-from` переносить наявну фразу з 24 слів ([§20](#20-seeds-identity-and-backups)).
9. Необов'язково: `--megammr-seed` імпортує знімок MegaMMR ([§13](#13-megammr-mode-and-the-rootnode)).
10. Виводить адресу гаманця облікового запису, ідентичність Maxima, токен шлюзу й інструкції з під'єднання.

| Прапорець скрипту | Перетворюється на | Типово |
|---|---|---|
| `--jar FILE` | jar для розгортання | найновіший `dist/parlons-node-*.jar` |
| `--heap 3g` | `-Xmx` | `2g` (замало для MegaMMR; вкажіть `3g` або більше) |
| `--memmax 3584M` | `MemoryMax` юніта | купа + 512M |
| `--p2p-port N` | `-Dparlons.node.port` | `9001` |
| `--relay-port N` | `-Dparlons.relay.port` | `9501` |
| `--gateway-port N` | `-Dparlons.gateway.port` | `9585` |
| `--gateway-public` | `-Dparlons.gateway.bind=0.0.0.0` і відкриває порт | loopback |
| `--public URL` | `-Dparlons.node.public` | не задано |
| `--rootnode h:p` | `-Dparlons.node.rootnode` | не задано (вузол не синхронізуватиметься) |
| `--no-megammr` | `-Dparlons.node.megammr=false` | MegaMMR увімкнено |
| `--rpc` | `-Dparlons.node.rpc=true` | вимкнено |
| `--peers h:p,…` | `-Dparlons.relay.peers` | не задано (використовується вкомпільований початковий список) |
| `--blobstore MB` | `-Dparlons.relay.blob` | `0` |
| `--dex PORT` | `-Dparlons.dex.port` | `0` (вимкнено) |
| `--dex-public URL` | `-Dparlons.dex.bind=127.0.0.1 -Dparlons.dex.public=URL` | не задано |
| `--passphrase-file F` | `-Dparlons.node.passphrase.file` | не задано |
| `--node-args "…"` | `-Dparlons.node.args` | порожньо |
| `--keets` | додає `-keets -keetssidecar /opt/maxima/keets-sidecar/index.js` до аргументів вузла й вивантажує допоміжний процес | вимкнено |
| `--seed-from F --archive h:p` | одноразова повторна синхронізація гаманця на фразу з F (потрібен `--rpc`), потім закріплення її в `identity.txt` | — (`--archive` типово вказує на архівний вузол, прописаний у скрипті) |
| `--megammr-seed URL` | одноразовий імпорт MegaMMR (потрібен `--rpc`) | — |
| `--replace-relay` | зупиняє й вимикає `maxima-relay.service`, щоб вузол міг зайняти його порт | — |

> **Завжди вказуйте `--dex-public` разом із `--dex`.** З ним скрипт задає
> `parlons.dex.bind=127.0.0.1` і тримає порт закритим. Без нього кейп DEX слухає на `0.0.0.0`,
> і скрипт відкриває порт в інтернет як звичайний HTTP.

Скрипт записує в юніт `Restart=always`, тому вузол повертається й після штатного завершення
(`quit`, імпорт MegaMMR, повторна синхронізація).

### C. Вручну

Мінімальна команда:

```bash
java -Xmx3g -Dparlons.node.data=/var/lib/parlons-node -jar parlons-node.jar
```

Вузол, запущений так, не має кореневого вузла (rootnode), тому не синхронізуватиметься.

#### Як встановити вручну

Ці кроки роблять на сервері те саме, що й скрипт розгортання. Виконуйте їх від імені root.

1. Встановіть Java, якщо її немає, і `qrencode`, якщо вам потрібні QR-коди під'єднання:

   ```bash
   apt-get update
   apt-get install -y default-jre-headless qrencode
   ```

2. Створіть службового користувача й каталоги:

   ```bash
   id maxima >/dev/null 2>&1 || useradd --system --home /var/lib/maxima --shell /usr/sbin/nologin maxima
   mkdir -p /opt/maxima /var/lib/parlons-node
   chown -R maxima:maxima /var/lib/parlons-node
   chmod 700 /var/lib/parlons-node
   ```

3. Скопіюйте перевірений jar в `/opt/maxima` і спрямуйте на нього символьне посилання:

   ```bash
   cp parlons-node-0.2.138.jar /opt/maxima/
   cd /opt/maxima
   echo "597a77225514b8ceddf1036a6016abc6d4b826e7711210c7f55de01857c9b4af  parlons-node-0.2.138.jar" | sha256sum -c -
   chmod 644 parlons-node-0.2.138.jar
   ln -sfn parlons-node-0.2.138.jar parlons-node.jar
   ```

4. Необов'язково: встановіть помічник під'єднання з клону репозиторію:

   ```bash
   install -m 755 ops/parlons-pair /usr/local/bin/parlons-pair
   ```

5. Запишіть файл юніта `/etc/systemd/system/parlons-node.service` за прикладом із
   [§7](#7-the-systemd-unit-annotated). Змініть імена хостів, купу й `MemoryMax` відповідно до
   сервера.
6. Відкрийте P2P-порт і порт ретранслятора ([§25](#25-firewall-notes)):

   ```bash
   ufw allow 9001/tcp
   ufw allow 9501/tcp
   ```

7. Запустіть вузол, потім виконайте кроки з [§6](#6-first-start):

   ```bash
   systemctl daemon-reload
   systemctl enable --now parlons-node
   ```

---

## 6. Перший запуск

1. Запустіть службу (інсталятор одним рядком і скрипт розгортання вже це зробили):

   ```bash
   systemctl enable --now parlons-node
   systemctl is-active parlons-node
   ```

2. Стежте за журналом:

   ```bash
   journalctl -u parlons-node -f
   ```

3. Знайдіть рядок запуску. У ньому вказано версію, теку даних і порти:

   ```
   [parlons-node] Parlons Node 0.2.138 — booting embedded Minima node <ver> at /var/lib/parlons-node/<base> (p2p 9001, megammr true, admin rpc 127.0.0.1:9005 (loopback-bound))
   ```

4. Стежте за синхронізацією ланцюга. Кожні 30 с рядок пульсу виводить висоту блока:

   ```
   [parlons-node] heartbeat: node block=<n> chainlen=<n> | cape=up:9501 | gateway=up:9585 | account=up:<n> hosts, <n> devices
   ```

   Перша синхронізація завершена, коли `block=` досягає поточної вершини ланцюга. Фіксованого часу
   для цього немає; він залежить від сервера й з'єднання.
5. Щойно гаманець готовий, з'являються такі рядки:
   - `identity: pinned from the vault into identity.txt` (лише під час першого запуску);
   - `Maxima cape up on port 9501 — identity Mx…`;
   - `account wallet = node wallet: 0x… (Mx…)`;
   - `wallet gateway up on 127.0.0.1:9585/cmd (… bearer token in /var/lib/parlons-node/gateway-token.txt)`;
   - `account up: attached to N relay(s)…`.
6. На вузлі MegaMMR один раз імпортуйте MegaMMR ([§13](#13-megammr-mode-and-the-rootnode)). Доки
   ви цього не зробите, баланси наявних адрес повертаються порожніми.
7. Під'єднайте телефон ([§17](#17-the-account-pairing-and-the-local-panel)):

   ```bash
   sudo parlons-pair --set-admin-password
   sudo parlons-pair --admin
   ```

8. Створіть резервну копію сід-фрази ([§20](#20-seeds-identity-and-backups)).

---

## 7. Юніт systemd (з коментарями)

Цей юніт повторює той, який записує `ops/deploy-parlons-node.sh`, із прапорцями, які флот використовує
в жовтні 2026 року: вузол MegaMMR, ретранслятор на 9501, шлюз і кейп DEX на loopback за TLS, а також
явний початковий список меш-мережі.

systemd не дозволяє коментарів у кінці рядка чи всередині продовженого `ExecStart`, тому
пояснення наведено в блоці коментарів над `ExecStart`.

```ini
# /etc/systemd/system/parlons-node.service
[Unit]
Description=Parlons Node - full Minima node + Maxima cape + wallet gateway (0.2.138)
Documentation=https://github.com/eurobuddha/maxima
After=network-online.target
Wants=network-online.target
# If the node crashes 5 times within 300 s, systemd stops restarting it (check after heap changes).
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
Type=simple
User=maxima
Group=maxima

# What each property does (full reference: section 8):
#   -Xmx3g                          hard heap ceiling (MegaMMR needs at least 3g on an 8 GB box)
#   parlons.node.data               data directory (seed, identity, tokens, chain)
#   parlons.node.port=9001          Minima P2P port, public. The admin RPC is p2p+4 = 9005.
#   parlons.node.rpc=true           loopback admin RPC (needed for vault, megammr import)
#   parlons.node.megammr=true       keep the MegaMMR (required for the wallet gateway)
#   parlons.node.rootnode           a Minima peer to discover the chain from
#   parlons.relay.port=9501         Maxima relay cape, public TCP (and UDP STUN on the same port)
#   parlons.relay.peers             mesh bootstrap list: other relays, host:port, comma-separated
#   parlons.relay.blob=1024         media blob shelf in MB (0 = off)
#   parlons.gateway.port/bind       wallet gateway + /nft on loopback, fronted by TLS
#   parlons.node.public             public base URL of the gateway front (NFT URLs, gateway advert)
#   parlons.dex.port/bind/public    DEX relay cape on loopback, announced as its TLS URL
ExecStart=/usr/bin/java -Xmx3g \
    -Dparlons.node.data=/var/lib/parlons-node \
    -Dparlons.node.port=9001 \
    -Dparlons.node.rpc=true \
    -Dparlons.node.megammr=true \
    -Dparlons.node.rootnode=seed-peer.example.net:9001 \
    -Dparlons.relay.port=9501 \
    -Dparlons.relay.peers=peer1.example.net:9501,peer2.example.net:9501,peer3.example.net:9501 \
    -Dparlons.relay.blob=1024 \
    -Dparlons.gateway.port=9585 \
    -Dparlons.gateway.bind=127.0.0.1 \
    -Dparlons.node.public=https://node.example.com/parlons-node \
    -Dparlons.dex.port=9602 \
    -Dparlons.dex.bind=127.0.0.1 \
    -Dparlons.dex.public=https://node.example.com/dex-relay \
    "-Dparlons.node.args=" \
    -jar /opt/maxima/parlons-node.jar

# Restart on a crash. Exit code 3 means "restart me": a wallet resync or an identity import
# requested from a paired device ends with exit 3, and the node comes back on the same identity.
# always, not on-failure: `quit` and a megammr import/resync end with exit 0, and the node must come back.
# The deploy script writes Restart=always. Older units may say on-failure: change them.
Restart=always
RestartSec=10
RestartForceExitStatus=3
SuccessExitStatus=3

# Filesystem: the node gets write access to its data directory and nothing else.
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
# ProtectHome makes /home and /root unreadable: keep passphrase files under /var/lib/parlons-node or /etc.
ProtectHome=true
ReadWritePaths=/var/lib/parlons-node
UMask=0077
PrivateDevices=true
ProtectProc=invisible
ProcSubset=pid
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectKernelLogs=true
ProtectControlGroups=true
ProtectClock=true
ProtectHostname=true
RemoveIPC=true

# Privileges: no capabilities at all.
CapabilityBoundingSet=
AmbientCapabilities=
RestrictSUIDSGID=true
RestrictNamespaces=true
RestrictRealtime=true
LockPersonality=true

# Syscalls: seccomp allow-list for a network service.
SystemCallArchitectures=native
SystemCallFilter=@system-service
SystemCallFilter=~@privileged ~@resources ~@obsolete
SystemCallErrorNumber=EPERM

# Network: block cloud metadata and link-local addresses.
# With --keets, add AF_NETLINK here (the Node.js sidecar needs it for interface discovery).
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
IPAddressDeny=169.254.0.0/16 fe80::/10

# Resource ceilings: MemoryMax sits ABOVE -Xmx (heap + 512M).
MemoryMax=3584M
TasksMax=1024
LimitNOFILE=65536

# Logs go to the journal. The node never prints the seed to stdout.
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

Примітки:

- Уся властивість `-Dparlons.node.args=…` взята в лапки, бо це один аргумент для JVM, і
  вона може містити пробіли. systemd враховує подвійні лапки. Не ставте подвійних лапок усередині значення.
- Якщо ви передаєте `-data <dir>` для Minima в `parlons.node.args`, додайте цей каталог до `ReadWritePaths`.
- Для вузла, заблокованого паролем, додайте `-Dparlons.node.passphrase.file=/var/lib/parlons-node/passphrase.txt`
  (власник `maxima`, режим 600) або задайте `PARLONS_NODE_PASSPHRASE` в `EnvironmentFile=` з режимом 600.
- Перед кожною зміною зберігайте резервну копію юніта. Прийнята назва —
  `parlons-node.service.before-<what>`.

### Як змінити юніт

1. Створіть резервну копію поточного юніта:

   ```bash
   cp /etc/systemd/system/parlons-node.service /etc/systemd/system/parlons-node.service.before-<what>
   ```

2. Відредагуйте його:

   ```bash
   nano /etc/systemd/system/parlons-node.service
   ```

3. Перевірте юніт на помилки:

   ```bash
   systemd-analyze verify /etc/systemd/system/parlons-node.service
   ```

4. Завантажте його й перезапустіть вузол:

   ```bash
   systemctl daemon-reload
   systemctl restart parlons-node
   ```

5. Зачекайте близько 25 с, потім перевірте, що він працює, і прочитайте рядок запуску:

   ```bash
   systemctl is-active parlons-node
   journalctl -u parlons-node --since -40s --no-pager | grep -E 'Parlons Node|minima flags|REFUSING' | grep -v seed
   ```

6. Якщо він не запускається, поверніть резервну копію й повторіть крок 4:

   ```bash
   cp /etc/systemd/system/parlons-node.service.before-<what> /etc/systemd/system/parlons-node.service
   ```

### Як перевести старий юніт на `Restart=always`

1. Перевірте, що вказано в юніті:

   ```bash
   grep -n '^Restart=' /etc/systemd/system/parlons-node.service
   ```

2. Якщо виводиться `Restart=on-failure`, змініть це:

   ```bash
   cp /etc/systemd/system/parlons-node.service /etc/systemd/system/parlons-node.service.before-restart-always
   sed -i 's/^Restart=on-failure$/Restart=always/' /etc/systemd/system/parlons-node.service
   systemctl daemon-reload
   ```

   Нове налаштування діє одразу; перезапуск не потрібен.

---

## 8. Довідник налаштувань: усі властивості

Усі властивості — це системні властивості JVM (`-Dname=value`), розміщені перед `-jar`. Цей список отримано
пошуком (grep) усіх викликів `System.getProperty`, `Integer.getInteger`, `Long.getLong` і `getenv`
у модулях, вбудованих у jar.

### Як змінити налаштування

1. Знайдіть властивість у таблицях нижче.
2. Додайте або змініть її рядок `-D…` в `ExecStart` юніта, перед `-jar`, і перезапустіть
   вузол ([§7, Як змінити юніт](#how-to-change-the-unit)). Якщо ви розгортали скриптом,
   можна натомість повторно запустити скрипт із відповідним прапорцем ([§5](#5-three-ways-to-install)); він
   переписує юніт на основі своїх прапорців.
3. Перевірте, що вузол його застосував. Рядок запуску показує порти, а рядок `minima flags:` показує
   власні прапорці Minima:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'Parlons Node|minima flags' | grep -v seed
   ```

### Як передати власні прапорці Minima у conf-файлі

Використовуйте це для секретів, таких як `mdspassword`, щоб вони ніколи не з'являлися в командному рядку.

1. Запишіть файл, по одному `key=value` на рядок. Просто `key` — це логічний прапорець. Не пишіть
   початковий `-`:

   ```bash
   cat > /var/lib/parlons-node/minima.conf <<'EOF'
   mdspassword=<your MDS password>
   EOF
   chown maxima:maxima /var/lib/parlons-node/minima.conf
   chmod 600 /var/lib/parlons-node/minima.conf
   ```

2. Додайте `-Dparlons.node.conf=/var/lib/parlons-node/minima.conf` до юніта й перезапустіть
   ([§7](#how-to-change-the-unit)).
3. Перевірте рядок `minima flags:` у журналі. Значення `mdspassword` там замасковане.

### 8.1 Вузол і ланцюг

| Властивість | Значення | Типово | Приклад |
|---|---|---|---|
| `parlons.node.data` | Каталог даних. Пріоритет: ця властивість, потім `-data` для Minima (його батьківська тека), потім `~/.parlons-node`. | `~/.parlons-node` | `/var/lib/parlons-node` |
| `parlons.node.port` | P2P-порт Minima (публічний). Похідні порти йдуть за ним: файли MDS `+2`, команди MDS `+3`, адміністративний RPC `+4`. | `9001` (типовий для Minima, якщо в аргументах немає `-port`) | `9101` |
| `parlons.node.rootnode` | Вузол Minima (`host:port`), через який відкривається ланцюг. Цей форк постачається з **порожнім** типовим списком вузлів, тому без цього параметра (або `connect`) вузол запускається, але ніколи не синхронізується. | порожньо | `seed-peer.example.net:9001` |
| `parlons.node.connect` | Фіксований список вузлів. Якщо його задати, **виявлення P2P вимикається**: лише статичні вузли. | порожньо | `a.example.net:9001,b.example.net:9001` |
| `parlons.node.megammr` | Зберігати повний MegaMMR, щоб `coins`/`balance megammr:true address:<any>` працювали для будь-якої адреси. Він потрібен шлюзу гаманця. Пріоритет: ця властивість, потім `-megammr` для Minima, потім `true`. | `true` | `false` |
| `parlons.node.rpc` | Запустити адміністративний RPC на loopback `127.0.0.1:<p2p+4>`. Якщо він не може зайняти порт, вузол завершує роботу з кодом 2. | `false` | `true` |
| `parlons.node.args` | Власні прапорці запуску Minima, які обробляє власний парсер Minima. Деякі прапорці відхиляються (див. 8.8). Резервна змінна середовища: `PARLONS_NODE_ARGS`. | порожньо | `"-host 203.0.113.10 -archive"` |
| `parlons.node.conf` | Файл `key=value` із прапорцями Minima, який читається **перед** `parlons.node.args`. Саме тут місце секретам, таким як `mdspassword`, у файлі з режимом 600. Рядок із самим `key` — це логічний прапорець. | порожньо | `/var/lib/parlons-node/minima.conf` |
| `parlons.node.passphrase.file` | Файл із парольною фразою вузла, заблокованого паролем. Вузол один раз пробує розблокуватися під час запуску. Альтернатива через змінну середовища: `PARLONS_NODE_PASSPHRASE`. Парольна фраза не може містити пробільних символів, `"` або `;`. | порожньо | `/var/lib/parlons-node/passphrase.txt` |
| `parlons.node.archive` | Архівний хост/хост MegaMMR, який використовується для **повторної синхронізації гаманця**, запитаної з під'єднаного пристрою (`megammrsync action:resync host:<this>`). | вкомпільований архівний вузол проєкту (див. `NodeAccountWallet.java`) | `archive.example.net:9001` |
| `parlons.node.selftest.send` | **Лише для тестів розробника.** Під час запуску надсилає `<address>[,<amount>]` з гаманця вузла (типова сума `0.001`). На вузлі з коштами **це надсилає справжні кошти**. Ніколи не задавайте його в робочому середовищі. | порожньо | — |

### 8.2 Кейп ретранслятора (Maxima)

| Властивість | Значення | Типово | Приклад |
|---|---|---|---|
| `parlons.relay.port` | Порт ретранслятора. Число дає ретранслятору власний слухач. `0` вимикає ретранслятор (тоді обліковий запис використовує ретранслятори флоту). `shared` змушує ретранслятор ділити P2P-порт Minima: вузол передає ретранслятору кожне з'єднання, яке вітається як клієнт Parlons, тож публічний порт лише один. | `9501` | `8001`, `0`, `shared` |
| `parlons.relay.host` | Публічне ім'я хоста або IP ретранслятора. Якщо не задано, вузол використовує публічну адресу, яку дізнався від інших вузлів, і продовжує перевіряти її (кожні 30 с протягом перших 30 хвилин, доки не отримає адресу, потім кожні 10 хвилин, тож змінена динамічна IP-адреса відстежується). Приватні, loopback- і CGNAT-адреси ніколи не використовуються. | визначається автоматично | `node.example.com` |
| `parlons.relay.peers` | Початковий список меш-мережі (`host:port`, через кому). Якщо не задано, використовується вкомпільований список `Bootstrap.RELAYS`. Обліковий запис також використовує їх як вузли меш-мережі. | вкомпільований список | `peer1.example.net:9501,peer2.example.net:8001` |
| `parlons.relay.blob` | Розмір полиці медіаблобів у MB (де зберігаються фотографії користувачів). `0` = вимкнено. | `0` | `1024` |
| `parlons.relay.maxconn` | Максимальна кількість з'єднань, які тримає ретранслятор. `0` залишає вбудоване типове значення: 512 на платформних потоках, 4096 на віртуальних потоках (JDK 21+). | `0` | `8192` |

Властивості рівня ретранслятора з `RelayServer` (вони діють і всередині вузла):

| Властивість | Значення | Типово |
|---|---|---|
| `maxima.relay.maxpersource` | Максимальна кількість одночасних з'єднань з однієї IP-адреси джерела (домогосподарство за NAT вважається одним джерелом). | `32` |
| `maxima.relay.shed` | М'яка цільова кількість клієнтів. Понад неї ретранслятор просить до 4 клієнтів за такт перейти деінде. `0` = ніколи не просити. | `384` |
| `maxima.relay.replicate` | На скільки випадкових перевірених вузлів пулу копіюється кожен підписаний запис каталогу. `0` = вимкнено. | `3` |
| `maxima.relay.vthreads` | Використовувати віртуальні потоки, якщо JDK їх має. `false` примусово вмикає платформні потоки. | `true` |
| `maxima.pow.enforce` | Відхиляти повідомлення без мінімального доказу виконаної роботи (proof-of-work), передбаченого протоколом. Якщо false, їх лише підраховують. Змінна середовища: `MAXIMA_POW_ENFORCE=true`. | `false` |
| `maxima.mls.open` | Типове значення відкритого визначення пулу. **На вузол не впливає:** вузол завжди вмикає режим пулу. | — |

Зафіксовано в коді (не налаштовується): протокол `1.0.48`; обмеження швидкості ретранслятора — 600 повідомлень на хвилину на одного адресата.

Змінна середовища: `MAXIMA_STUN=false` вимикає UDP STUN-відповідач на порту ретранслятора.

### 8.3 Шлюз гаманця і NFT

| Властивість | Значення | Типово | Приклад |
|---|---|---|---|
| `parlons.gateway.port` | Порт шлюзу гаманця (`/cmd`) і сервера NFT (`/nft`). | `9585` | `9585` |
| `parlons.gateway.bind` | Інтерфейс, на якому слухає шлюз. Тримайте його на loopback за TLS. | `127.0.0.1` | `127.0.0.1` |
| `parlons.gateway.token` | Bearer-токен. Якщо не задано, вузол читає `<data>/gateway-token.txt` або генерує там 256-бітний токен (режим 600). | з файлу | — |
| `parlons.gateway.threads` | Потоки-обробники. Кожен блокується на одну команду вузла. | `8` | `16` |
| `parlons.gateway.rate.global` | Загальна частота запитів `/cmd` (запитів/с; сплеск = обсяг за 2 с). `0` = вимкнено. | `50` | `100` |
| `parlons.gateway.rate.perip` | Частота `/cmd` на одну IP-адресу. Має сенс лише тоді, коли шлюз відкрито напряму, бо за проксі кожен запит надходить із 127.0.0.1. | `10` | `0` |
| `parlons.gateway.rate.nft.global` | Загальна частота `/nft`. | `400` | — |
| `parlons.gateway.rate.nft.perip` | Частота `/nft` на одну IP-адресу. | `40` | — |
| `parlons.node.public` | Публічна базова URL-адреса TLS-фронту шлюзу, **без** `/cmd`. Вона визначає URL-адреси NFT. На вузлі MegaMMR з увімкненим ретранслятором вона також повідомляє телефонам про шлюз у привітанні ретранслятора. | порожньо | `https://node.example.com/parlons-node` |

### 8.4 Кейп DEX-ретранслятора

| Властивість | Значення | Типово | Приклад |
|---|---|---|---|
| `parlons.dex.port` | Порт кейпа DEX-ретранслятора. `0` = вимкнено. | `0` | `9602` |
| `parlons.dex.bind` | Інтерфейс, на якому він слухає. Явно задане значення має пріоритет. Якщо не задано, кейп слухає на `127.0.0.1`, коли задано `parlons.dex.public`, інакше на `0.0.0.0`. Тримайте його на loopback і ставте перед ним TLS-фронт. | `127.0.0.1` з `parlons.dex.public`, інакше `0.0.0.0` | `127.0.0.1` |
| `parlons.dex.public` | Публічна базова URL-адреса, яка оголошується в меш-мережі. Якщо не задано, вузол оголошує `http://<public host>:<port>`, щойно дізнається свій публічний хост. | порожньо | `https://node.example.com/dex-relay` |

Зафіксовано в коді: версія DEX-ретранслятора `0.1.5`; загальна частота 200 запитів/с і частота на одну IP-адресу 20 запитів/с
(що це означає за проксі, див. у [§16](#16-the-dex-relay-cape-dexicon)).

### 8.5 Обліковий запис і панель

| Властивість | Значення | Типово | Приклад |
|---|---|---|---|
| `parlons.account` | Запускати обліковий запис Parlons. `false` = вузол лише з ретранслятором і шлюзом. | `true` | `false` |
| `parlons.account.name` | Ім'я для показу (використовується лише під час першого запуску). | порожньо | `Example Node` |
| `parlons.account.relays` | Додаткові ретранслятори, до яких під'єднується обліковий запис (csv). | порожньо | `peer1.example.net:9501` |
| `parlons.account.builtin` | `false` = не брати початкові ретранслятори з вкомпільованого списку. Тоді обліковий запис починає з `account.relays` і ретрансляторів, які пам'ятає. | `true` | `false` |
| `parlons.account.direct` | Порт прямого слухача облікового запису (Tier-2). `0` = вимкнено. | `0` | — |
| `parlons.panel.port` | Локальна вебпанель і JSON API, лише на `127.0.0.1`. `0` = вимкнено. | `9587` | `0` |
| `parlons.hosted.dir` | Каталог розміщених (гостьових) облікових записів. Змінна середовища: `PARLONS_HOSTED_DIR`. Якщо не задано, вузол вмикає кероване розміщення для роботи з окремою службою орендарів (tenants). | не задано | `/var/lib/parlons-tenants` |
| `parlons.hosted.limit` | Максимальна кількість розміщених облікових записів. | `10` | — |
| `parlons.restore` | Відновити переносний пакет облікового запису (`.pbk`) у **свіжий** каталог даних, потім завершити роботу. Для парольної фрази потрібна інтерактивна консоль. | порожньо | `/root/backup.pbk` |

### 8.6 MDS і keets

| Властивість | Значення | Типово | Приклад |
|---|---|---|---|
| `parlons.mds` | Надавати MDS (MiniHUB і мінідапи) на `https://<bind>:<p2p+2>/`. Те саме, що `-mdsenable` для Minima. | вимкнено | `true` |
| `parlons.mds.bind` | Інтерфейс, на якому слухає MDS. Пріоритет: ця властивість, потім `-mdsbind` для Minima, потім `127.0.0.1`. `0.0.0.0` або `*` означає всі інтерфейси. | `127.0.0.1` | `127.0.0.1` |
| `parlons.node.keetschat` | `off`/`false`/`0`/`no` передає вхідні keets застосунку-хосту, і міст не запускається. | `on` | `off` |

### 8.7 Змінні середовища

| Змінна | Значення |
|---|---|
| `PARLONS_NODE_ARGS` | Резервний варіант для `parlons.node.args`. |
| `PARLONS_NODE_PASSPHRASE` | Парольна фраза для заблокованого вузла (використовуйте `EnvironmentFile` з режимом 600). |
| `PARLONS_HOSTED_DIR` | Резервний варіант для `parlons.hosted.dir`. |
| `MAXIMA_STUN` | `false` вимикає UDP STUN-відповідач. |
| `MAXIMA_POW_ENFORCE` | `true` вимагає мінімального доказу виконаної роботи. |

### 8.8 Прапорці Minima, які вузол відхиляє

Прапорці в `parlons.node.args` або `parlons.node.conf` передаються власному парсеру Minima, **крім**
наведених нижче. Вузол виводить причину й завершує роботу з кодом 2, перш ніж торкнутися каталогу даних.
Невідомий прапорець так само призводить до виходу з кодом 2.

| Прапорці | Чому | Що використовувати натомість |
|---|---|---|
| `-rpc -rpcenable -rpcpassword -rpccrlf` | Стандартний RPC слухає на всіх інтерфейсах із повними правами адміністратора. | `-Dparlons.node.rpc=true` (лише loopback) |
| `-seed -anyseed -dbpassword` | Секрет в argv може прочитати будь-який процес (`ps`). | `--seed-from` / `--passphrase-file` |
| `-clean -genesis -test -solo -testchainlength` | Вони стирають каталог даних або виводять вузол з основної мережі (mainnet). | — |
| `-daemon -noshutdownhook -jnlp -help` | JVM вузла сама керує stdin, завершенням і хуком завершення роботи. | — |

Явно задані властивості `-Dparlons.node.*` застосовуються після прапорців Minima і мають пріоритет у разі конфлікту.
Журнал запуску показує застосовані прапорці як `minima flags: …`, із замаскованим значенням `mdspassword`.

### 8.9 Коди виходу

| Код | Значення |
|---|---|
| `0` | Штатна зупинка: `quit` через адміністративний RPC, або вбудований вузол сам завершив роботу після повторної синхронізації, відновлення чи скидання. |
| `2` | Відмова запуститися: невідомий аргумент командного рядка, відхилений або невідомий прапорець Minima, нечитабельний файл `-conf` або адміністративний RPC не зміг зайняти порт. |
| `3` | Запитано перезапуск: повторна синхронізація гаманця або імпорт ідентичності з під'єднаного пристрою. systemd має його перезапустити (`RestartForceExitStatus=3`). |

---

## 9. Порти

| Порт (типово) | Протокол | Що | Прив'язка | Доступність |
|---|---|---|---|---|
| `9001` (`parlons.node.port`) | TCP | P2P Minima | усі | **Публічний.** Відкрийте його в брандмауері. |
| `p2p+2` (`9003`) | TCP/HTTPS | Файловий сервер MDS / MiniHUB (лише з `parlons.mds=true`) | `127.0.0.1` | Loopback. Ніколи не робіть його публічним без власного TLS і брандмауера. |
| `p2p+3` (`9004`) | TCP | Командний порт MDS (лише з увімкненим MDS) | як у MDS | Loopback. |
| `p2p+4` (`9005`) | TCP/HTTP | Адміністративний RPC (лише з `parlons.node.rpc=true`) | `127.0.0.1`, жорстко задано в коді | **Ніколи не відкривайте. Ніколи не проксіюйте.** |
| `9501` (`parlons.relay.port`) | TCP | Кейп ретранслятора Maxima | усі | **Публічний.** Сюди підключаються телефони й вузли меш-мережі. |
| `9501` | UDP | STUN-відповідач прив'язки на порту ретранслятора | усі | Необов'язково. Скрипт розгортання не відкриває UDP. |
| `9585` (`parlons.gateway.port`) | TCP/HTTP | Шлюз гаманця `/cmd` і `/nft` | `127.0.0.1` | Loopback, за TLS-фронтом на `/parlons-node/`. |
| `9587` (`parlons.panel.port`) | TCP/HTTP | Панель облікового запису та API | `127.0.0.1`, жорстко задано в коді | Loopback. Під'єднуйтеся через SSH-тунель. |
| `9602` (`parlons.dex.port`, типово вимкнено) | TCP/HTTP | Кейп DEX-ретранслятора | `127.0.0.1`, якщо задано `parlons.dex.public`, **інакше `0.0.0.0`**; `parlons.dex.bind` перевизначає | Loopback, за TLS-фронтом на `/dex-relay/`. |

Конфлікти портів, про які варто знати:

- **Звичайний вузол Minima на тому самому сервері** вже займає 9001 (і 9005). Перенесіть Parlons Node за допомогою
  `-Dparlons.node.port=9101`. Його похідні порти теж переносяться (MDS 9103/9104, RPC 9105).
- **`maxima-relay.service`** (старий окремий ретранслятор) займає 9501. Спочатку зупиніть і вимкніть його
  (`--replace-relay` у скрипті розгортання).
- **Служба `parlons-tenants`** поруч із вузлом не повинна використовувати 9587. Задайте їй `--panel-port 9597`;
  тоді орендарі займають 9597, 9598 і так далі, на loopback.
- На сервері, де переадресовано лише один порт (домашній роутер), використовуйте `-Dparlons.relay.port=shared` або
  виберіть порт ретранслятора, який можна переадресувати.

### Як перенести вузол із порту 9001

Робіть це, коли на сервері вже працює звичайний вузол Minima.

1. Виберіть вільний базовий порт, наприклад 9101. Перевірте, що він і наступні чотири вільні:

   ```bash
   ss -ltnp | grep -E ':(9101|9103|9104|9105) '
   ```

2. Задайте `-Dparlons.node.port=9101` у юніті й перезапустіть ([§7](#how-to-change-the-unit)). Зі
   скриптом розгортання натомість передайте `--p2p-port 9101`.
3. Відкрийте новий P2P-порт:

   ```bash
   ufw allow 9101/tcp
   ```

4. Використовуйте новий порт адміністративного RPC (`9105`) у кожній команді `curl` у цьому посібнику.

### Як побачити, які порти займає вузол

```bash
ss -ltnp | grep java
ss -lunp | grep java
```

Перша команда показує TCP-слухачі та їхні адреси прив'язки; друга показує
UDP STUN-відповідач.

---

## 10. Структура каталогу даних

Усі шляхи вказано відносно `parlons.node.data` (типово `/var/lib/parlons-node` у скрипті
розгортання). Каталог і кожен секретний файл мають належати службовому користувачу й бути доступними лише йому.

| Шлях | Що | Секрет? |
|---|---|---|
| `<base version>/` | Власні дані вузла Minima: бази даних, гаманець, ланцюг. Файл MegaMMR — `<base version>/databases/megammr.mmr` (скрипт розгортання використовує шлях `1.1/databases/megammr.mmr`). | **Так.** Тут зберігається гаманець. |
| `identity.txt` | Закріплена фраза ідентичності (або сід у hex), з якої виводиться ідентичність Maxima. Режим 600. | **Так. Це сід.** |
| `identity-anyphrase.txt` | Власна (не BIP39) фраза ідентичності, якщо таку було імпортовано. | **Так.** |
| `gateway-token.txt` | Bearer-токен шлюзу гаманця. Режим 600. | Низька чутливість (лише читання й трансляція, і його повідомляють телефонам; див. [§14](#14-the-wallet-gateway-and-its-token)). |
| `pair-code.txt` | Поточний невикористаний одноразовий код під'єднання. | **Так**, доки його не використано. |
| `account.txt` | Постійна адреса облікового запису (`MAX#…`). | Ні. |
| `invite.txt` | Адреса разом з одноразовим кодом. | **Так**, доки код не використано. |
| `panel-ticket.txt` | Одноразове посилання для входу в локальну панель. | **Так.** |
| `local-device.key` | Ключ локального пристрою панелі. Видаліть його й перезапустіть вузол, щоб знову ввімкнути відкликану панель. | **Так.** |
| `devices.json` | Під'єднані пристрої. | Приватно. |
| `cloud-settings.properties` | Налаштування облікового запису. | Приватно. |
| `node/`, `chat/`, `media/`, `private-files/` | Сховище облікового запису, історія чатів, медіа. | Приватно. |
| `relay/` | Стан ретранслятора: каталог, елементи поштових скриньок, полиця блобів. | Приватно. |
| `nft/` | Розміщені файли NFT (`<sha256>.<ext>`, `c/<16-hex>/…`), а також `tmp/` для вивантажень, що тривають. | Публічний вміст. |
| `account-history/<import-id>/previous/` | Попередня ідентичність після імпорту ідентичності. | **Так.** |
| `.seed-adopted` | Позначка, яку записує `--seed-from` скрипту розгортання. | Ні. |

### Як перевірити дозволи каталогу даних

1. Виведіть власника й режим каталогу та секретних файлів:

   ```bash
   ls -ld /var/lib/parlons-node
   ls -l /var/lib/parlons-node/*.txt
   ```

2. Каталог має бути `drwx------ maxima maxima`, а кожен секретний `.txt` — `-rw------- maxima maxima`.
   Якщо ні, виправте це:

   ```bash
   chown -R maxima:maxima /var/lib/parlons-node
   chmod 700 /var/lib/parlons-node
   chmod 600 /var/lib/parlons-node/*.txt
   ```

---

## 11. TLS-фронт: Caddy, nginx, Apache

Шлюз (9585) і кейп DEX (9602) працюють через звичайний HTTP на loopback. Вебсервер на
тому самому сервері завершує TLS і переадресовує ці два шляхи:

| Публічний шлях | Куди переадресувати | Префікс |
|---|---|---|
| `https://node.example.com/parlons-node/…` | `http://127.0.0.1:9585/…` | прибрати `/parlons-node` (щоб `/parlons-node/cmd` потрапляв на `/cmd`, а `/parlons-node/nft/…` — на `/nft/…`) |
| `https://node.example.com/dex-relay/…` | `http://127.0.0.1:9602/…` | прибрати `/dex-relay` (щоб `/dex-relay/dex/health` потрапляв на `/dex/health`) |

Потім задайте в `parlons.node.public` і `parlons.dex.public` публічні URL-адреси (крок 4 нижче).

Ніколи не переадресовуйте адміністративний RPC (`p2p+4`), панель (9587) чи MDS.

Розміри й тайм-аути, які мають значення:

- Шлюз приймає тіла запитів до 1 MiB (найбільший — підписаний `txnimport`) і надсилає
  відповіді до 12 MiB.
- Довгі опитування (long-poll) DEX тримаються відкритими до 25 с. Конверти заявок — щонайбільше 16 KiB; конверти
  повідомлень — щонайбільше 256 KiB.
- Типового тайм-ауту читання більшості проксі (60 с) достатньо. Якщо ви його знизили, тримайте його вище 30 с.

### Як налаштувати будь-який TLS-фронт

1. Спрямуйте DNS-ім'я (тут `node.example.com`) на сервер і відкрийте TCP 443 (і 80 для
   перевірки сертифіката):

   ```bash
   ufw allow 80/tcp
   ufw allow 443/tcp
   ```

2. Перевірте, що дві HTTP-служби вузла відповідають на loopback (DEX — лише якщо ви його ввімкнули):

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:9585/cmd   # 401 = gateway up
   curl -s http://127.0.0.1:9602/dex/health
   ```

3. Налаштуйте Caddy, nginx або Apache за кроками нижче.
4. Задайте публічні URL-адреси в юніті й перезапустіть вузол ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.node.public=https://node.example.com/parlons-node
   -Dparlons.dex.public=https://node.example.com/dex-relay
   ```

5. Перевірте з іншої машини:

   ```bash
   curl -s https://node.example.com/dex-relay/dex/health
   curl -s -o /dev/null -w '%{http_code}\n' -X POST https://node.example.com/parlons-node/cmd   # 401
   ```

### Caddy

Caddy сам отримує й поновлює сертифікат.

#### Як поставити Caddy перед вузлом

1. Встановіть Caddy з його офіційних пакетів (<https://caddyserver.com/docs/install>).
2. Створіть резервну копію Caddyfile:

   ```bash
   cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.before-parlons-node
   ```

3. Додайте ці блоки до блоку сайту для вашого імені хоста в `/etc/caddy/Caddyfile`:

   ```caddy
   node.example.com {
       # DEX relay cape: use `handle` + `uri strip_prefix`, NOT `handle_path` (see the trap below).
       handle /dex-relay/* {
           uri strip_prefix /dex-relay
           reverse_proxy 127.0.0.1:9602
       }

       # Wallet gateway + NFT files.
       handle /parlons-node/* {
           uri strip_prefix /parlons-node
           reverse_proxy 127.0.0.1:9585
       }

       # Your site's catch-all (if any) goes LAST.
       handle {
           root * /var/www/html
           file_server
       }
   }
   ```

4. Перевірте файл, потім перезавантажте Caddy:

   ```bash
   caddy validate --config /etc/caddy/Caddyfile
   systemctl reload caddy
   ```

5. Перевірте з іншої машини (крок 5 розділу [Як налаштувати будь-який TLS-фронт](#how-to-set-up-any-tls-front)).
   Якщо `/dex-relay/dex/health` повертає вашу вебсторінку замість JSON, ви потрапили в пастку, описану нижче.

`reverse_proxy` у Caddy типово надсилає `X-Forwarded-For`, тому кейп DEX обмежує частоту запитів для кожного
клієнта окремо.

> **Пастка порядку в Caddy: `handle_path` проти `handle`.** Перед застосуванням Caddy сортує директиви за фіксованим
> порядком, і `handle` йде **перед** `handle_path`. Серед блоків `handle`
> першими йдуть точніші зіставлення шляхів, а `handle` без зіставлення — останнім. Блоки `handle`
> взаємовиключні: виграє перший, що збігся. Тож якщо в блоці сайту є
> загальний `handle { … }`, то `handle_path /dex-relay/*` опиняється **після** нього й ніколи не виконується:
> на кожен запит DEX відповідає загальний блок. Виправлення: напишіть `handle /dex-relay/* { uri strip_prefix
> /dex-relay; reverse_proxy … }`, який сортується разом з іншими блоками `handle` за шляхом. У
> блоці сайту без загального `handle` варіант `handle_path /dex-relay/*` працює.

#### Як додати обмеження частоти для кожного клієнта в Caddy (необов'язково)

Власний лічильник шлюзу вузла для кожної IP-адреси за проксі бачить лише `127.0.0.1`, тому обмежуйте кожного клієнта
натомість у Caddy. Для цього потрібен сторонній модуль `rate_limit`, якого немає в стандартному Caddy.

1. Зберіть або завантажте бінарний файл Caddy з модулем `rate_limit`.
2. Замініть блок шлюзу на:

   ```caddy
       handle /parlons-node/* {
           rate_limit {
               zone phones {
                   key    {remote_host}
                   events 60
                   window 1m
               }
           }
           uri strip_prefix /parlons-node
           reverse_proxy 127.0.0.1:9585
       }
   ```

3. Перевірте й перезавантажте, як у кроках 4 і 5 вище.

### nginx

#### Як поставити nginx перед вузлом

1. Отримайте сертифікат для імені хоста (наприклад, за допомогою certbot) і створіть для нього блок
   `server { listen 443 ssl; server_name node.example.com; … }`.
2. Створіть резервну копію файлу сайту, який збираєтеся змінити:

   ```bash
   cp /etc/nginx/sites-available/<your site> /etc/nginx/sites-available/<your site>.before-parlons-node
   ```

   Якщо на сервері працює монітор цілісності файлів, що стежить за конфігурацією nginx, редагування його спрацює. Повідомте
   власника цього монітора, перш ніж змінювати файл.
3. Додайте ці блоки всередину блоку `server { … }`:

   ```nginx
   # DEX relay cape. The trailing slashes strip the prefix.
   location /dex-relay/ {
       proxy_pass http://127.0.0.1:9602/;
       proxy_http_version 1.1;
       proxy_set_header Host $host;
       proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
       proxy_read_timeout 60s;
   }

   # Wallet gateway + NFT files.
   location /parlons-node/ {
       proxy_pass http://127.0.0.1:9585/;
       proxy_http_version 1.1;
       proxy_set_header Host $host;
       client_max_body_size 2m;
   }
   ```

4. Перевірте конфігурацію, потім перезавантажте nginx:

   ```bash
   nginx -t
   systemctl reload nginx
   ```

5. Перевірте з іншої машини (крок 5 розділу [Як налаштувати будь-який TLS-фронт](#how-to-set-up-any-tls-front)).

Примітки:

- `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` потрібен у блоці `/dex-relay/`.
  nginx типово не надсилає цей заголовок, і без нього всі клієнти DEX ділять один
  лічильник обмеження частоти ([§16](#16-the-dex-relay-cape-dexicon)).
- `client_max_body_size 2m` дає запас понад ліміт тіла шлюзу в 1 MiB (типове значення nginx —
  `1m`).

### Apache

#### Як поставити Apache перед вузлом

1. Майте віртуальний хост із TLS (`<VirtualHost *:443>`) для імені хоста, наприклад від certbot.
2. Увімкніть модулі проксі:

   ```bash
   a2enmod proxy proxy_http
   ```

3. Розмістіть це у фрагменті, який підключається з віртуального хоста з TLS:

   ```apache
   # DEX relay cape
   ProxyPass        /dex-relay/ http://127.0.0.1:9602/
   ProxyPassReverse /dex-relay/ http://127.0.0.1:9602/

   # Wallet gateway (POST /cmd) and NFT files (GET/HEAD /nft/...)
   <Location "/parlons-node/cmd">
       ProxyPass        http://127.0.0.1:9585/cmd
       ProxyPassReverse http://127.0.0.1:9585/cmd
   </Location>
   <Location "/parlons-node/nft">
       ProxyPass        http://127.0.0.1:9585/nft
       ProxyPassReverse http://127.0.0.1:9585/nft
   </Location>
   ```

4. Перевірте конфігурацію, потім перезавантажте Apache:

   ```bash
   apachectl configtest
   systemctl reload apache2
   ```

5. Перевірте з іншої машини (крок 5 розділу [Як налаштувати будь-який TLS-фронт](#how-to-set-up-any-tls-front)).

`ProxyPass` в Apache типово надсилає `X-Forwarded-For`. Для шлюзу за Apache обмеженням частоти слугують власні загальні
лічильники токенів вузла.

---

## 12. Кейп ретранслятора і меш-мережа

### Що робить кейп

Кейп — це повноцінний ретранслятор Maxima. На рівні протоколу він говорить класичним Maxima, байт у байт, а також підтримує
розширення Parlons: поштову скриньку з принципом «зберегти й переслати», каталог, підтвердження доставки, gossip і меш-мережу. У
телефона немає публічної адреси, тому він сам підключається до кейпа й тримає з'єднання відкритим; повідомлення
для нього повертаються цим самим з'єднанням. Звичайний вузол Minima теж може використовувати кейп як свій хост Maxima.

Кейп завжди працює як **хост пулу**: хост каталогу з відкритим визначенням адрес і пересилач меш-мережі.

### Ідентичність

Ідентичність кейпа (`Mx…`) виводиться з `identity.txt`, який закріплюється із сід-фрази вузла під час
першого запуску. Сервер, на якому раніше працював `maxima-server.jar`, може зберегти свою ідентичність ретранслятора:
`--seed-from /var/lib/maxima/seed.txt` у скрипті розгортання повторно синхронізує гаманець вузла на цю фразу й закріплює її.

### Публічний хост

Кейп має знати свою публічну адресу. Він використовує її, щоб називати себе в списках вузлів, не
пересилати самому собі, прив'язувати постійну адресу облікового запису й будувати URL-адресу DEX, яку
оголошує. Адреса визначається в такому порядку:

1. `-Dparlons.relay.host`, якщо ви його задали.
2. Інакше — публічна адреса, яку вузол Minima дізнався від інших вузлів
   (`network` → `details.p2p.address`), потім `status` → `network.host`.
3. Якщо жодна з них ще не публічна, фоновий потік перевіряє знову кожні 30 с протягом перших 30 хвилин,
   потім кожні 10 хвилин. Коли він знаходить адресу, у журнал записується
   `public address learned from peers: <ip> - the cape now names itself <ip>:<port>`.

Ніколи не задавайте як хост ретранслятора адресу прив'язки, наприклад `0.0.0.0`. Класичний Minima вважає адреси `0.x`
внутрішніми й відкидає весь ретранслятор.

### Gossip і перевірені вузли

З `core/.../session/RelayPeers.java`:

- Вузол може запропонувати лише **себе**. Заява в привітанні приймається, тільки якщо заявлений хост
  збігається з IP-адресою джерела з'єднання.
- Кожна заява **перевіряється зворотним підключенням** (4 с на з'єднання, 2 с на читання). Вузол додається, лише якщо
  у відповідь приходить справжнє привітання Maxima.
- Вузли, про які стало відомо з привітань інших ретрансляторів, теж перевіряються зворотним підключенням перед використанням.
  Непублічні адреси пропускаються.
- Зберігається щонайбільше **250** перевірених вузлів (`MAX_PEERS`). Кожне привітання передає перемішаний список
  до **50** (`SHARE_LIMIT`).
- **TTL 2 год** (`TTL_MS`). Перевірений вузол, який не заявив про себе знову або до якого не вдалося повторно підключитися протягом
  2 годин, вилучається.
- **Повторне підключення кожні 10 хв** (`REDIAL_MS`). Коли черга вільна, потік перевірки
  повторно підключається до кожного перевіреного вузла, до якого не підключався 10 хвилин. Так оголошені дані кожного вузла
  (біт пулу, URL DEX, шлюз) лишаються актуальними, а успішне повторне підключення оновлює
  TTL. Недоступний вузол не проходить підключення й вилучається.

### Меш-мережа і `parlons.relay.peers`

- `parlons.relay.peers` — це **початковий** список, а не список довіри й не єдина точка відмови.
  Довіра ґрунтується на підписі видавця, тому недоступний чи ворожий вузол просто пропускається.
- Якщо в каталозі запису немає, ретранслятор пересилає запит своїм вузлам пулу. Пересилання суворо на один
  крок, із розгалуженням не більше ніж на 8. Ретранслятор перевіряє підписану відповідь, ненадовго кешує її й відповідає.
- Кожен прийнятий підписаний запис каталогу копіюється на `maxima.relay.replicate` (типово 3) випадкових вузлів
  пулу, тож адреса продовжує визначатися, навіть коли її домашній ретранслятор недоступний.
- Якщо `parlons.relay.peers` не задано, використовується вкомпільований початковий список. У журналі буде
  `mesh: N bootstrap peer(s) (default fleet list)`.
- Перелічіть кожен інший ретранслятор, з яким ви хочете гарантовано зв'язуватися, у вигляді `host:port`. Використовуйте порт, на якому
  справді слухає ретранслятор кожного вузла; домашній сервер може використовувати 8001 замість 9501.

### Пропускна здатність

- Типовий ліміт з'єднань: 512 на платформних потоках, 4096 на віртуальних потоках (JDK 21+). Перевизначається через
  `parlons.relay.maxconn`. Під час запуску ретранслятор записує в журнал, який режим і ліміт він використовує.
- Ліміт на одну IP-адресу джерела: 32 (`maxima.relay.maxpersource`).
- Незареєстроване з'єднання (яке так і не стає клієнтом) розривається через 10 хвилин. Неактивні
  незареєстровані з'єднання розриваються через 120 с.
- Понад `maxima.relay.shed` (384) зареєстрованих клієнтів ретранслятор просить до 4 клієнтів за такт
  перейти на інший ретранслятор. Куди саме — вирішує клієнт.

### Режим спільного порту

З `-Dparlons.relay.port=shared` ретранслятор не має власного слухача. P2P-слухач Minima
передає йому кожне з'єднання, яке вітається як клієнт Parlons. Тоді один публічний порт обслуговує і
ланцюг, і ретранслятор. Настільні застосунки використовують це, коли ввімкнено «внесок» (contribute). Сервери флоту зберігають
явно заданий 9501.

### Як перевірити, що кейп працює

1. Знайдіть рядки кейпа й меш-мережі в журналі цього запуску:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'Maxima cape|mesh:|public address learned|own relay' | grep -v seed
   ```

   Ви маєте побачити `Maxima cape up on port 9501 — identity Mx…` і `mesh: N bootstrap peer(s)`.
2. Перевірте рядок пульсу. Він має показувати `cape=up:9501`:

   ```bash
   journalctl -u parlons-node --since -2min --no-pager | grep heartbeat | tail -1
   ```

3. Перевірте наскрізно з іншої машини ([§23](#23-verifying-from-outside), крок 2).

### Як задати початкові вузли меш-мережі

1. Зберіть `host:port` кожного ретранслятора, з яким хочете зв'язуватися. Використовуйте порт, на якому кожен ретранслятор справді
   слухає.
2. Задайте їх одним значенням через кому в юніті, потім перезапустіть ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.relay.peers=peer1.example.net:9501,peer2.example.net:9501,peer3.example.net:8001
   ```

   Зі скриптом розгортання передайте `--peers "peer1.example.net:9501,peer2.example.net:9501"`.
3. Якщо сервер типово блокує вихідний трафік, дозвольте порт ретранслятора кожного вузла
   ([§25](#25-firewall-notes)).
4. Перевірте журнал запуску. Там має бути `mesh: N bootstrap peer(s)` з вашою кількістю:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'mesh:'
   ```

### Як задати публічний хост

Робіть це, коли журнал показує `own relay NOT advertised`, або коли сервер має фіксоване ім'я, яке ви хочете,
щоб використовував кейп.

1. Додайте публічне ім'я хоста або IP до юніта, потім перезапустіть ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.relay.host=node.example.com
   ```

   Ніколи не використовуйте адресу прив'язки, наприклад `0.0.0.0`, а також приватну, loopback- чи CGNAT-адресу.
2. Перевірте, що попередження зникло:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'own relay NOT advertised' || echo advertised-ok
   ```

### Як запустити ретранслятор на P2P-порту Minima (режим спільного порту)

1. Задайте порт ретранслятора `shared` у юніті, потім перезапустіть ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.relay.port=shared
   ```

2. Відкрийте (або переадресуйте) лише P2P-порт, наприклад 9001. Видаліть правило для 9501, якщо воно вам більше не потрібне:

   ```bash
   ufw delete allow 9501/tcp
   ```

3. Повідомте своїм користувачам і вузлам меш-мережі, що адреса цього ретранслятора — `node.example.com:9001`.

### Як увімкнути полицю медіаблобів

1. Задайте розмір полиці в MB у юніті, потім перезапустіть ([§7](#how-to-change-the-unit)). Зі
   скриптом розгортання передайте `--blobstore 1024`:

   ```
   -Dparlons.relay.blob=1024
   ```

2. Переконайтеся, що на диску є стільки вільного місця (`df -h /var/lib`).
3. Перевірте журнал запуску. Там має бути `blob shelf: 1024 MB`:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'blob shelf'
   ```

### Як підняти ліміт з'єднань

1. Запускайте вузол на JDK 21 або новішому. Тоді ліміт сам зростає до 4096.
2. Щоб задати власний ліміт, додайте його до юніта й перезапустіть ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.relay.maxconn=8192
   ```

3. Також підніміть `LimitNOFILE` у юніті, якщо ліміт до нього наближається (юніт задає 65536).

### Як вимкнути ретранслятор

Задайте `-Dparlons.relay.port=0` у юніті й перезапустіть. Тоді обліковий запис використовує інші ретранслятори. Закрийте
порт ретранслятора в брандмауері.

---

## 13. Режим MegaMMR і кореневий вузол (rootnode)

### Кореневий вузол (rootnode)

Цей форк вузла постачається з **порожнім типовим списком вузлів**. Задайте йому одне з такого:

- `-Dparlons.node.rootnode=host:port`: вузол Minima, через який відбувається виявлення. Далі вузол сам знаходить інші
  вузли. Використовуйте будь-який доступний вузол Minima, якому довіряєте. `--help` виводить кореневий вузол,
  рекомендований проєктом.
- `-Dparlons.node.connect=host:port,…`: фіксований список із вимкненим виявленням.
- Власні прапорці Minima `-connect` або `-nop2p` усередині `parlons.node.args`.

Без жодного з них вузол запускається й надає свої локальні служби, але `block=` ніколи не змінюється.

#### Як задати кореневий вузол

1. Виведіть кореневий вузол, рекомендований проєктом, або виберіть вузол Minima, якому довіряєте:

   ```bash
   java -jar /opt/maxima/parlons-node.jar --help | grep -i rootnode
   ```

2. Перевірте, що сервер може до нього дістатися (замініть хост і порт):

   ```bash
   nc -z -w 8 seed-peer.example.net 9001 && echo reachable
   ```

3. Додайте його до юніта, потім перезапустіть ([§7](#how-to-change-the-unit)). Зі скриптом розгортання передайте
   `--rootnode seed-peer.example.net:9001`:

   ```
   -Dparlons.node.rootnode=seed-peer.example.net:9001
   ```

4. Стежте, як зростає `block=` у рядку пульсу:

   ```bash
   journalctl -u parlons-node -f | grep heartbeat
   ```

### MegaMMR

На вузлі MegaMMR **типово ввімкнено**; у самому Minima він типово вимкнений. З MegaMMR вузол
може доводити наявність монет для **будь-якої** адреси (`coins`/`balance megammr:true address:<any>`). Саме це
робить його шлюзом гаманця для телефонів.

**Початкова синхронізація не заповнює MegaMMR.** MegaMMR щойно синхронізованого вузла порожній (приблизно 20
байтів) і відстежує лише монети, створені відтоді. Доки ви не імпортуєте знімок, баланси
наявних адрес повертаються **порожніми**.

#### Як імпортувати знімок MegaMMR

Зробіть це один раз, після першої синхронізації. Виконуйте команди на сервері від імені root.

1. Переконайтеся, що адміністративний RPC увімкнено (`-Dparlons.node.rpc=true`, [§21](#21-the-admin-rpc)). Ця команда
   має вивести статус у JSON:

   ```bash
   curl -s http://127.0.0.1:9005/status | head -c 200; echo
   ```

2. Перевірте поточний розмір MegaMMR. Менше 1 MB означає, що він порожній:

   ```bash
   stat -c %s /var/lib/parlons-node/1.1/databases/megammr.mmr
   ```

3. Завантажте знімок у каталог даних і передайте його службовому користувачу (приблизно 460 MB;
   `-c` продовжує перерване завантаження):

   ```bash
   cd /var/lib/parlons-node
   wget -c -O mega.mmr https://eurobuddha.com/mega.mmr
   chown maxima:maxima mega.mmr
   ```

   Щоб натомість використати власний знімок, див. наступну процедуру.
4. Імпортуйте його через loopback RPC (P2P-порт 9001 означає RPC 9005):

   ```bash
   curl -s -m 3600 'http://127.0.0.1:9005/megammr%20action:import%20file:/var/lib/parlons-node/mega.mmr'
   ```

   Відповідь має містити `"status":true`. Імпорт завершується зупинкою вузла.
5. Поверніть вузол до роботи. `Restart=always` перезапускає його через 10 с; для певності виконайте:

   ```bash
   systemctl restart parlons-node
   ```

6. Зачекайте близько 20 с, потім перевірте, що `megammr.mmr` тепер більший за 1 MB:

   ```bash
   stat -c %s /var/lib/parlons-node/1.1/databases/megammr.mmr
   ```

7. Запитайте адресу, про яку ви знаєте, що на ній є кошти. Її баланс не має бути порожнім:

   ```bash
   curl -s 'http://127.0.0.1:9005/balance%20megammr:true%20address:<a funded Mx or 0x address>'
   ```

8. Видаліть завантажений файл:

   ```bash
   rm -f /var/lib/parlons-node/mega.mmr
   ```

`--rpc --megammr-seed <URL>` у скрипті розгортання виконує всі ці кроки й пропускає їх, якщо
MegaMMR уже заповнено.

#### Як створити власний знімок

1. На будь-якому вузлі MegaMMR, яким ви керуєте, з увімкненим адміністративним RPC експортуйте MegaMMR. Це бере блокування
   на читання й не потребує простою:

   ```bash
   curl -s -m 3600 'http://127.0.0.1:9005/megammr%20action:export%20file:/var/lib/parlons-node/donor.megammr'
   ```

2. Скопіюйте файл у каталог даних нового вузла й передайте його службовому користувачу:

   ```bash
   scp /var/lib/parlons-node/donor.megammr root@node.example.com:/var/lib/parlons-node/mega.mmr
   ssh root@node.example.com chown maxima:maxima /var/lib/parlons-node/mega.mmr
   ```

3. Видаліть експорт на вузлі-донорі, потім виконайте імпорт на новому вузлі, починаючи з кроку 4 попередньої процедури.

#### Як запустити вузол без MegaMMR

1. Додайте `-Dparlons.node.megammr=false` до юніта (або передайте `--no-megammr` скрипту розгортання).
2. Зменште купу відповідно (768 MB на сервері з 2 GB, [§3](#3-requirements)) і задайте `MemoryMax`
   на 512 MB більше.
3. Перезапустіть ([§7](#how-to-change-the-unit)). Вузол більше не може бути шлюзом гаманця для інших
   адрес.

**Порядок важливий, якщо ви також переносите сід-фразу:** виконуйте `--seed-from` **перед** імпортом MegaMMR.
`megammrsync` скидає бази даних ланцюга й викидає імпортований MegaMMR.

Вузол із `megammr=false` не може обслуговувати читання `megammr:true`. Його шлюз відповідає на такі запити
кодом HTTP 503 (`this node has no MegaMMR - use a MegaMMR gateway node`), тож телефони перемикаються на
інший шлюз.

---

## 14. Шлюз гаманця та його токен

### Контракт

```
POST /cmd
Authorization: Bearer <token>
Content-Type: application/json

{"command":"balance megammr:true address:Mx..."}
```

Відповідь — це власний JSON вузла. Помилки повертаються як `{"status":false,"error":"…"}` з такими HTTP-кодами:

| Код | Значення |
|---|---|
| 400 | неправильне тіло JSON або порожня команда |
| 401 | bearer-токен відсутній або неправильний |
| 403 | команди немає в списку дозволених, або `coins`/`balance` без `address:` |
| 405 | це не POST |
| 429 | перевищено обмеження частоти (перевіряється **до** автентифікації) |
| 500 | внутрішня помилка шлюзу (подробиці лише в журналі вузла) |
| 502 | відповідь вузла понад 12 MiB |
| 503 | `megammr:true` на вузлі без MegaMMR |

### Список дозволених команд

Проходять лише ці команди. Перевіряється кожен сегмент, відокремлений `;`, бо вузол виконує кожен
сегмент як окрему команду:

- **Читання:** `status`, `tokens`, `balance`, `coins`, `checkaddress`, `tokenvalidate`, `block`
- **Лише відстеження** (не можуть переміщувати кошти): `newscript`, `coinexport`, `coinimport`
- **Трансляція транзакції, яку телефон підписав сам:** `txnimport`, `txnbasics`, `txnpost`, `txndelete`, `txncheck`

Усе інше відхиляється: `send`, `vault`, `keys`, `sign`, `quit` тощо. `coins` і
`balance` мусять містити `address:`, тож ніхто не може переглянути власні монети оператора через шлюз.

### Токен

- Пріоритет: `-Dparlons.gateway.token`, потім `<data>/gateway-token.txt`, потім новий випадковий
  256-бітний токен, записаний у цей файл із режимом 600.
- Токен дає **лише читання й трансляцію**. Він не може переміщувати кошти вузла.
- **Від телефонів він не секретний.** На вузлі MegaMMR із заданим `parlons.node.public` привітання
  ретранслятора оголошує URL-адресу шлюзу (`<public>/cmd`) і його ключ, щоб телефони могли знаходити шлюзи
  разом із ретрансляторами. У журналі буде `wallet gateway advertised to phones: <url>`.
- Флот шлюзів може використовувати один спільний токен: скопіюйте той самий `gateway-token.txt` на кожен сервер.

### Обмеження частоти

- Загальний лічильник: 50 запитів/с, сплеск 100 (`parlons.gateway.rate.global`). Це справжній
  запобіжник за TLS-фронтом.
- Лічильник на одну IP-адресу: 10 запитів/с (`parlons.gateway.rate.perip`). За loopback-проксі кожен
  запит надходить із `127.0.0.1`, тому обмежуйте кожного клієнта в проксі.
- `/nft` має власні окремі лічильники (400 загальний, 40 на одну IP-адресу).

### Як опублікувати шлюз для телефонів

1. Запускайте вузол з увімкненим MegaMMR та імпортуйте MegaMMR ([§13](#13-megammr-mode-and-the-rootnode)).
2. Тримайте шлюз на loopback (`-Dparlons.gateway.bind=127.0.0.1`, типово) і поставте TLS-фронт перед
   `/parlons-node/` ([§11](#11-tls-fronting-caddy-nginx-apache)).
3. Задайте публічну базову URL-адресу, без `/cmd`, у юніті й перезапустіть ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.node.public=https://node.example.com/parlons-node
   ```

4. Перевірте, що вузол її оголошує:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'wallet gateway (up|advertised|FAILED)'
   ```

   Ви маєте побачити `wallet gateway advertised to phones: https://node.example.com/parlons-node/cmd`.
5. Перевірте його з іншої машини (наступна процедура).

### Як перевірити шлюз

1. На сервері прочитайте токен у змінну оболонки. Не вставляйте його в спільні чати чи заявки:

   ```bash
   TOKEN=$(sudo cat /var/lib/parlons-node/gateway-token.txt)
   ```

2. Надішліть команду `block`. Вона має повернути JSON вузла з `"status":true`:

   ```bash
   curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        -d '{"command":"block"}' https://node.example.com/parlons-node/cmd
   ```

3. Надішліть її без токена. Має повернутися HTTP 401:

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' \
        -d '{"command":"block"}' https://node.example.com/parlons-node/cmd
   ```

4. Надішліть заборонену команду. Має повернутися HTTP 403:

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' -d '{"command":"vault"}' \
        https://node.example.com/parlons-node/cmd
   ```

### Як використовувати один токен на кількох шлюзах

1. Скопіюйте `gateway-token.txt` з першого сервера на кожен інший:

   ```bash
   scp root@node.example.com:/var/lib/parlons-node/gateway-token.txt ./gateway-token.txt
   scp ./gateway-token.txt root@node2.example.com:/var/lib/parlons-node/gateway-token.txt
   rm ./gateway-token.txt
   ```

2. На кожному сервері, який його отримав, виправте власника й режим, потім перезапустіть:

   ```bash
   chown maxima:maxima /var/lib/parlons-node/gateway-token.txt
   chmod 600 /var/lib/parlons-node/gateway-token.txt
   systemctl restart parlons-node
   ```

### Як замінити токен

1. Видаліть файл токена й перезапустіть. Під час запуску вузол запише новий 256-бітний токен:

   ```bash
   rm /var/lib/parlons-node/gateway-token.txt
   systemctl restart parlons-node
   ```

2. Зачекайте, поки гаманець запрацює (`wallet gateway up` у журналі), потім прочитайте новий токен:

   ```bash
   sudo cat /var/lib/parlons-node/gateway-token.txt
   ```

3. Передайте новий токен усьому, що налаштовували зі старим вручну. Телефони, які знайшли
   шлюз через привітання ретранслятора, отримають новий ключ там само.

Якщо в юніті задано `-Dparlons.gateway.token`, він має пріоритет над файлом: змінюйте його там.

### Як змінити обмеження частоти шлюзу

1. Додайте до юніта властивості, які хочете змінити (значення — запити на секунду; `0`
   вимикає лічильник `/cmd`):

   ```
   -Dparlons.gateway.rate.global=100
   -Dparlons.gateway.rate.perip=0
   ```

2. Перезапустіть ([§7](#how-to-change-the-unit)).
3. За TLS-фронтом натомість обмежуйте кожного клієнта в проксі
   ([§11, Caddy](#how-to-add-per-client-rate-limiting-in-caddy-optional)).

---

## 15. Розміщення NFT і `parlons.node.public`

Вузол віддає файли, на які посилаються метадані токенів, тож маркетплейс чи оглядач блоків може
посилатися прямо на ваш сервер.

- **Зберігання:** `<data>/nft/`.
  - Окремі файли **адресуються за вмістом**: `<sha256>.<ext>`. URL-адреса ніколи не змінюється, і будь-хто
    може звірити байти з хешем.
  - Колекції: `c/<16-hex id>/<index>.<ext>` (індекс від 0 до 9999), з `manifest.json`, у якому
    перелічено sha256 кожного елемента.
  - Не більше **32 MB** на файл. Розширення — від 2 до 5 малих літер або цифр.
  - Покинуті вивантаження в `tmp/` видаляються через 24 год.
- **Вивантаження:** лише з під'єднаних пристроїв, через зашифрований канал керування (`parlons.nft.put`,
  `parlons.nft.newcollection`, `parlons.nft.list`, `parlons.nft.delete`). Кожне вивантаження надсилається
  частинами й звіряється зі своїм sha256, перш ніж його буде розміщено. Публічної кінцевої точки для вивантаження немає.
- **Віддача:** `GET`/`HEAD /nft/<path>` на порту шлюзу. Токен не потрібен. Доступні лише шляхи двох
  наведених вище форм. Відповіді містять:
  - `Cache-Control: public, max-age=31536000, immutable`
  - `X-Content-Type-Options: nosniff`
  - `Access-Control-Allow-Origin: *`
  - `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox`, тож SVG,
    відкритий напряму, не може виконати скрипт на вашому домені
  - `Content-Disposition: inline`
- **Публічна база:** `-Dparlons.node.public=https://node.example.com/parlons-node`. Публічна URL-адреса
  файлу — `<public>/nft/<path>`.
  - Без неї вивантаження все одно вдаються, але повернений `url` порожній. У журналі буде
    `nft hosting: … (no public base - set -Dparlons.node.public=https://host/parlons-node)`.
  - **Задавайте лише ту URL-адресу, яка справді веде на порт 9585 цього сервера.** URL-адреса, скопійована з іншого сервера,
    змусить гаманці публікувати посилання на сервер, на якому немає цих файлів.
  - На вузлі MegaMMR та сама властивість також оголошує шлюз телефонам ([§14](#14-the-wallet-gateway-and-its-token)).
- Типи вмісту визначаються за розширенням: png, jpg/jpeg, gif, webp, svg, avif, mp4, webm, mp3,
  json, txt, glb. Усе інше — `application/octet-stream`.

### Як увімкнути розміщення NFT

Розміщення NFT завжди ввімкнене; йому потрібна лише публічна URL-адреса.

1. Поставте TLS-фронт, що переадресовує `/parlons-node/` на `http://127.0.0.1:9585/`
   ([§11](#11-tls-fronting-caddy-nginx-apache)).
2. Задайте публічну базу в юніті й перезапустіть ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.node.public=https://node.example.com/parlons-node
   ```

3. Перевірте рядок журналу:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'nft hosting'
   ```

   Він має закінчуватися на `served at https://node.example.com/parlons-node/nft/<file>`, а не на
   `(no public base …)`.
4. Вивантажте файл із під'єднаного пристрою. Вивантаження повертає публічний `url` файлу.
5. Перевірте файл з іншої машини. Має повернутися `200`:

   ```bash
   curl -sI https://node.example.com/parlons-node/nft/<sha256>.<ext> | head -1
   ```

### Як переглянути й видалити розміщені файли

1. Виведіть, що зберігає вузол:

   ```bash
   ls -l /var/lib/parlons-node/nft/ /var/lib/parlons-node/nft/c/
   ```

2. Видаліть файл із під'єднаного пристрою (`parlons.nft.delete`). `manifest.json` колекції перелічує
   кожен елемент, тому не видаляйте файли колекцій вручну.
3. Перевірте використання диска:

   ```bash
   du -sh /var/lib/parlons-node/nft
   ```

---

## 16. Кейп DEX-ретранслятора (Dexicon)

Кейп DEX-ретранслятора (`dexrelay/`, версія `0.1.5`, без залежностей) — це ретранслятор книги заявок для
Dexicon. Він працює **всередині** вузла; це не окрема служба. Він за задумом не потребує довіри:

- Кожна заявка й повідомлення — це **підписаний конверт** (Ed25519), і ретранслятор перевіряє підпис,
  перш ніж прийняти його.
- Прийняті конверти передаються далі байт у байт, тож кожен клієнт перевіряє ті самі байти, що й ретранслятор.
- **На диск нічого не зберігається.** Перезапущений ретранслятор починає з порожнього стану, а мейкери повторно публікують заявки на наступному такті.
- Автентифікації немає, а CORS відкритий, бо кожне повідомлення автентифікує себе саме.

### Як увімкнути кейп DEX-ретранслятора

1. Додайте три властивості до юніта, потім перезапустіть ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.dex.port=9602
   -Dparlons.dex.bind=127.0.0.1
   -Dparlons.dex.public=https://node.example.com/dex-relay
   ```

   Зі скриптом розгортання передайте `--dex 9602 --dex-public https://node.example.com/dex-relay`. Тоді
   скрипт сам задасть прив'язку до loopback.
2. Поставте фронт, що переадресовує `/dex-relay/` на `http://127.0.0.1:9602/` ([§11](#11-tls-fronting-caddy-nginx-apache)).
   У nginx додайте рядок `X-Forwarded-For`.
3. Перевірте журнал запуску:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'dex relay'
   ```

   Ви маєте побачити ці два рядки:

   ```
   [parlons-node] dex relay 0.1.5 up on 127.0.0.1:9602 (GET /dex/health)
   [parlons-node] dex relay announced in the mesh as https://node.example.com/dex-relay (GET /dex/relays)
   ```

   Якщо публічна URL-адреса чи хост ще невідомі, другий рядок натомість виглядає так:
   `dex relay: no public host known yet - NOT announced in the mesh until one is learned …`.
4. Перевірте, що кейп слухає лише на loopback. Адреса має бути `127.0.0.1:9602`:

   ```bash
   ss -ltnp | grep ':9602 '
   ```

5. Перевірте його з іншої машини (див. [Перевірка](#check-it) нижче).

**Прив'язка.** Явно задане `parlons.dex.bind` завжди має пріоритет. Якщо його не задано, кейп слухає на
`127.0.0.1`, коли задано `parlons.dex.public`, і на `0.0.0.0`, коли ні. Тож якщо задати лише
`parlons.dex.port`, кейп відповідає звичайним HTTP усьому інтернету. Задайте `parlons.dex.public`
(і, для ясності, `parlons.dex.bind=127.0.0.1`) і поставте перед ним TLS.

### Як вимкнути кейп DEX-ретранслятора

1. Видаліть три властивості `parlons.dex.*` з юніта (або задайте `-Dparlons.dex.port=0`) і
   перезапустіть ([§7](#how-to-change-the-unit)).
2. Видаліть блок `/dex-relay/` з TLS-фронту й перезавантажте його.

### API

| Метод і шлях | Призначення | Відповіді |
|---|---|---|
| `POST /dex/publish` | Опублікувати один конверт заявки (щонайбільше 16 KiB). | `200 {"ok":true,"seq":…,"cursor":…}` · `400` недійсний · `409` застарілий seq · `413` завеликий · `503` ретранслятор заповнений |
| `GET /dex/book?market=&since=&hold=` | Зміни книги заявок від курсора. Довге опитування до `hold` секунд (щонайбільше 25). `since=0` повертає повний знімок (`reset:true`). | `200 {"cursor":…,"reset":…,"orders":[…]}` · `503` зайнято |
| `POST /dex/msg` | Доставити одне запечатане торгове повідомлення (щонайбільше 256 KiB). | `200 {"ok":true}` · `400` · `413` · `503` вхідні заповнені |
| `GET /dex/inbox?pk=&since=&hold=` | Повідомлення для публічного ідентифікатора (`pk` має бути `0x` плюс 64 байти в hex). Довге опитування до 25 с. | `200 {"cursor":…,"messages":[…]}` · `400` · `503` зайнято |
| `GET /dex/health` | Перевірка працездатності. | `{"ok":true,"version":"0.1.5","orders":<n>,"cursor":<n>,"uptime":<s>}` |
| `GET /dex/relays` | Усі URL-адреси DEX-ретрансляторів, відомі цьому хосту, включно з ним самим, отримані через меш-мережу ретрансляторів. | `{"ok":true,"relays":[{"url":"…","seen":<epoch s>}]}` |

Будь-який інший шлях повертає 404. Обмеження частоти повертає 429 з `{"ok":false,"error":"rate limited"}`.

### Ліміти (зафіксовано в коді)

| Ліміт | Значення |
|---|---|
| Активні заявки | 20 000 (після цього `503 relay full`) |
| Усього збережених повідомлень | 20 000 |
| Повідомлень на одного отримувача | 200 |
| Одночасних довгих опитувань | 512 |
| Найдовше довге опитування | 25 с |
| Термін дії заявки | щонайбільше на 24 год наперед |
| Термін дії повідомлення | щонайбільше на 600 с наперед |
| Монет на заявку | 32 |
| Очищення прострочених записів | кожні 30 с |
| Частота | 200 запитів/с загалом, 20 запитів/с на одну IP-адресу (сплеск = обсяг за 2 с) |

**Обмеження частоти за проксі.** За loopback TLS-фронтом кожен запит надходить із `127.0.0.1`.
Коли запит надходить із loopback, кейп обмежує частоту за **крайнім правим записом `X-Forwarded-For`**
(тим, який додав ваш проксі), а не за адресою з'єднання. Заголовок прямого клієнта не з loopback
ігнорується, тож ніхто не може вибрати собі лічильник. Ваш фронт мусить надсилати цей заголовок:
`reverse_proxy` у Caddy і `ProxyPass` в Apache роблять це типово; nginx потребує
`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` у блоці `/dex-relay/`. Без
заголовка лічильник на одну IP-адресу в 20 запитів/с (сплеск 40) діє на **весь публічний трафік разом**.

### Виявлення

- Кожен ретранслятор з URL-адресою DEX додає її у своє привітання меш-мережі (`dex`). Інші вузли записують її під час
  зворотного підключення й оновлюють під час повторного підключення кожні 10 хвилин.
- `GET /dex/relays` перелічує власну URL-адресу цього ретранслятора й усі URL-адреси DEX, оголошені його перевіреними
  вузлами. Адреси-шаблони (`0.0.0.0`, `::`) ніколи не потрапляють у список.
- Новий ретранслятор з'являється в списках інших ретрансляторів приблизно за один цикл повторного підключення (10 хвилин) після
  перевірки.
- Клієнти Dexicon починають із вбудованого списку ретрансляторів і об'єднують `/dex/relays` з кожного з них, тож
  повільна меш-мережа ніколи не блокує торгівлю.

### Перевірка

1. З іншої машини запитайте запис про працездатність. Має повернутися
   `{"ok":true,"version":"0.1.5",…}`:

   ```bash
   curl -s https://node.example.com/dex-relay/dex/health
   ```

2. Виведіть DEX-ретранслятори, відомі цьому. Його власна URL-адреса має бути в списку:

   ```bash
   curl -s https://node.example.com/dex-relay/dex/relays
   ```

3. Перевірте, що звичайний порт закритий ззовні. Команда не має нічого вивести:

   ```bash
   nc -z -w 5 node.example.com 9602 && echo "EXPOSED 9602"
   ```

4. Приблизно через 10 хвилин запитайте список в іншого DEX-ретранслятора. URL-адреса цього вузла теж має там з'явитися.

---

## 17. Обліковий запис, під'єднання й локальна панель

Кожен Parlons Node також запускає **обліковий запис** Parlons: той самий код, що й `parlons-cloud.jar`, з
ідентичністю вузла, власним гаманцем вузла й кейпом як ретранслятором. Телефони під'єднуються до нього із
застосунку Parlons Cloud. Вимкнути його можна через `-Dparlons.account=false`.

### Як під'єднати телефон

Виконуйте це на сервері, в інтерактивному SSH-терміналі. Що робити в застосунку на кожному кроці, описано в
[посібнику порталу Parlons Cloud](parlons-cloud-portal.md#4-pair-the-app-to-your-account).

1. Один раз задайте адміністративний пароль для під'єднання:

   ```bash
   sudo parlons-pair --set-admin-password
   ```

2. Покажіть адресу облікового запису й одноразове запрошення власника. Якщо встановлено `qrencode`,
   буде намальовано QR-код:

   ```bash
   sudo parlons-pair --admin
   ```

3. Відскануйте або вставте запрошення в застосунку Parlons Cloud. Дійсний код під'єднує одразу.
4. Перевірте рядок пульсу. Кількість пристроїв в `account=up:… devices` зросте на один:

   ```bash
   journalctl -u parlons-node --since -2min --no-pager | grep heartbeat | tail -1
   ```

`parlons-pair` типово читає `/var/lib/parlons-node`. Передайте `--data DIR`, якщо ваш каталог даних
в іншому місці.

Невикористаний код зберігається в `pair-code.txt`, доки його не використають. Використаний код більше ніколи не дійсний.
Не беріть коди зі старих журналів чи старих копій `invite.txt`.

### Як розмістити гостьовий обліковий запис

Для гостьових облікових записів потрібна окрема служба `parlons-tenants` поруч із вузлом. Спочатку налаштуйте її
([посібник порталу Parlons Cloud, §21](parlons-cloud-portal.md#21-many-accounts-the-parlons-tenants-service)).
Задайте їй `--panel-port 9597`, щоб вона не конфліктувала з панеллю вузла на 9587.

1. Створіть гостьове запрошення для нового імені облікового запису:

   ```bash
   sudo parlons-pair --guest <name>
   ```

2. Надішліть запрошення гостеві приватним каналом. Він під'єднується зі свого застосунку Parlons
   Cloud.

Докладніше про запрошення: [посібник порталу Parlons Cloud, §23](parlons-cloud-portal.md#23-owner-and-guest-invitations-from-the-server).

### Як запустити вузол без облікового запису

Задайте `-Dparlons.account=false` у юніті й перезапустіть ([§7](#how-to-change-the-unit)). Тоді вузол
працює лише як ретранслятор і шлюз. Рядок пульсу показує `account=off`.

### Власний ретранслятор

Коли кейп увімкнено і його публічний хост відомий, обліковий запис спершу під'єднується до власного кейпа,
оголошує його першим і прив'язує до нього свою постійну адресу. Якщо хост невідомий або приватний,
у журналі буде `own relay NOT advertised: … set -Dparlons.relay.host=<public ip>`.

### Як відкрити локальну панель

1. Зі свого комп'ютера відкрийте SSH-тунель до порту панелі й не закривайте його:

   ```bash
   ssh -L 9587:127.0.0.1:9587 root@node.example.com
   ```

2. У цьому SSH-сеансі прочитайте одноразове посилання:

   ```bash
   sudo cat /var/lib/parlons-node/panel-ticket.txt
   ```

   Воно має вигляд `http://127.0.0.1:9587/open?ticket=…`.
3. Відкрийте посилання в браузері на своєму комп'ютері. Обліковий запис створює нове посилання, щойно використано попереднє.
4. Якщо ви раніше відкликали панель і хочете її повернути, видаліть ключ локального пристрою й перезапустіть:

   ```bash
   rm /var/lib/parlons-node/local-device.key
   systemctl restart parlons-node
   ```

Панель слухає лише на `127.0.0.1:9587` (`parlons.panel.port`; `0` її вимикає). Вона не має доступу
до сід-фрази, резервних копій, надсилань із гаманця, платежів, консолі вузла чи розміщення NFT. Усе це лишається на
під'єднаних телефонах і в командному рядку. Що вона показує: [посібник порталу Parlons Cloud, §24](parlons-cloud-portal.md#24-the-local-web-panel).

### Як відновити переносний обліковий запис на новому вузлі

Пакет `.pbk` створюється на під'єднаному пристрої (Налаштування → **Створити резервну копію облікового запису…**, див.
[посібник порталу Parlons Cloud, §16](parlons-cloud-portal.md#16-backups-recovery-and-moving)). Він
містить ідентичність, під'єднані пристрої, налаштування, контакти й чат.

1. Зупиніть хост, з якого взято резервну копію, **назавжди**: одна ідентичність — один активний обліковий запис.
2. Встановіть вузол ([§5](#5-three-ways-to-install)), але не запускайте його, або зупиніть його й перемістіть його
   каталог даних. Для відновлення потрібен **свіжий** каталог даних:

   ```bash
   systemctl stop parlons-node
   mv /var/lib/parlons-node /var/lib/parlons-node.before-restore
   mkdir -p /var/lib/parlons-node
   chown maxima:maxima /var/lib/parlons-node
   chmod 700 /var/lib/parlons-node
   ```

3. Скопіюйте пакет і передайте його службовому користувачу:

   ```bash
   cp backup.pbk /var/lib/parlons-node/backup.pbk
   chown maxima:maxima /var/lib/parlons-node/backup.pbk
   ```

4. Запустіть відновлення від імені службового користувача в інтерактивному терміналі. Воно запитує
   парольну фразу пакета, записує обліковий запис і завершує роботу:

   ```bash
   sudo -u maxima java -Dparlons.node.data=/var/lib/parlons-node \
       -Dparlons.restore=/var/lib/parlons-node/backup.pbk -jar /opt/maxima/parlons-node.jar
   ```

5. Видаліть пакет і запустіть вузол:

   ```bash
   rm /var/lib/parlons-node/backup.pbk
   systemctl start parlons-node
   ```

6. Під'єднані пристрої підключаються знову самі, бо `MAX#` той самий.
7. Гаманець вузла лишається його власним сховищем (vault). Щоб кошти перейшли разом з обліковим записом, повторно синхронізуйте гаманець на стару
   фразу з під'єднаного пристрою ([§20](#how-to-resync-the-wallet-to-another-phrase)).

Застосунок також може імпортувати ідентичність або `.pbk` прямо на працюючий вузол (Налаштування →
**Імпортувати ідентичність або резервну копію…**). Тоді вузол завершує роботу з кодом 3 і повертається з імпортованою
ідентичністю. Попередня ідентичність зберігається в `account-history/<import-id>/previous/`.

---

## 18. Допоміжний процес keets (міст KeetChat)

Коли вбудований вузол Minima запускає keets, чат-рушій облікового запису отримує транспорт keets,
а вузол стає **єдиним читачем** своїх вхідних keets.

- Увімкніть його прапорцями Minima в `parlons.node.args`:
  `-keets -keetssidecar /opt/maxima/keets-sidecar/index.js`. Настільні застосунки також передають
  `-keetsnode <path to a node binary>`. `--keets` у скрипті розгортання вивантажує допоміжний процес (з
  `../keets/sidecar`, якому потрібні `node_modules`) і задає прапорці.
- На сервері потрібне середовище виконання **Node.js**. Допоміжний процес працює як дочірній процес у пісочниці служби.
- **Додайте `AF_NETLINK` до `RestrictAddressFamilies`** у юніті. Без цього кожен сокет, який відкриває допоміжний процес,
  завершується помилкою `EPERM`.
- **Вхідний порт у брандмауері не потрібен.** Допоміжний процес використовує пробивання NAT (hole punching) hyperswarm.
- Під час запуску міст записує в журнал `keets chat bridge up (KeetChat interop)`, потім
  `keets identity: 0x<64 hex> (a KeetChat user adds this as a contact)`.
- Міст перевіряє вузол кожні 5 с. На запити платіжної адреси він відповідає лише **контактам**,
  не частіше ніж раз на 10 хвилин для кожного співрозмовника.
- `-Dparlons.node.keetschat=off` дає змогу застосунку-хосту самому читати вхідні keets (minimaCore Desktop
  так і робить). На кожен вузол завжди має бути лише один читач.

### Як увімкнути keets скриптом розгортання

1. Встановіть Node.js на сервері. Без середовища `node` скрипт відмовляється продовжувати:

   ```bash
   apt-get install -y nodejs
   node --version
   ```

2. На своєму комп'ютері клонуйте репозиторій keets поруч із клоном maxima, щоб допоміжний процес
   був у `../keets/sidecar`, і встановіть його пакети:

   ```bash
   (cd ../keets/sidecar && npm ci --omit=dev)
   ```

   Щоб використати допоміжний процес з іншого місця, задайте `SIDECAR=/path/to/sidecar` перед скриптом.
3. Повторно запустіть скрипт розгортання з усіма звичними прапорцями плюс `--keets`. Він вивантажує допоміжний процес у
   `/opt/maxima/keets-sidecar`, додає прапорці й додає `AF_NETLINK` до юніта.
4. Перевірте журнал:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'keets'
   ```

   Ви маєте побачити `keets chat bridge up (KeetChat interop)` і `keets identity: 0x…`.

### Як увімкнути keets вручну

1. Встановіть Node.js на сервері й скопіюйте допоміжний процес разом із його `node_modules` у
   `/opt/maxima/keets-sidecar` (там має бути `index.js`).
2. Додайте прапорці keets до `parlons.node.args` у юніті:

   ```
   "-Dparlons.node.args=-keets -keetssidecar /opt/maxima/keets-sidecar/index.js"
   ```

3. Додайте `AF_NETLINK` до сімейств адрес у юніті:

   ```ini
   RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
   ```

4. Перезавантажте конфігурацію й перезапустіть ([§7](#how-to-change-the-unit)), потім перевірте журнал, як у кроці 4 вище.
5. Передайте значення `keets identity: 0x…` користувачам KeetChat, які хочуть додати вузол як контакт.

---

## 19. MDS (MiniHUB) на вузлі

- `-Dparlons.mds=true` надає MiniHUB і мінідапи на `https://127.0.0.1:<p2p+2>/`. Використовується
  самопідписаний сертифікат.
- **Він слухає на loopback, якщо ви не вкажете інакше.** MiniHUB — це повний інтерфейс гаманця за одним паролем.
  Ніколи не робіть його публічним без власного TLS і брандмауера.
- Пароль береться з conf-файлу з режимом 600 (`-Dparlons.node.conf=/path/minima.conf`, що містить
  `mdspassword=…`). Інакше пароль генерується: прочитайте його командою `mds` через адміністративний RPC.
  Журнал запуску його маскує.
- У комплект входять лише хаб, сторінка входу, `mds.js`, publicmds і мінідап Pending. Інші
  мінідапи встановлюйте самі.

### Як увімкнути MDS і відкрити MiniHUB

1. Запишіть пароль MDS у conf-файл ([§8, conf-файл](#how-to-pass-minimas-own-flags-in-a-conf-file))
   або пропустіть цей крок, і вузол згенерує пароль сам.
2. Додайте ці властивості до юніта й перезапустіть ([§7](#how-to-change-the-unit)). Не додавайте
   рядок `parlons.node.conf`, якщо пропустили крок 1: conf-файл, який неможливо прочитати, зупиняє вузол
   з кодом виходу 2.

   ```
   -Dparlons.mds=true
   -Dparlons.mds.bind=127.0.0.1
   -Dparlons.node.conf=/var/lib/parlons-node/minima.conf
   ```

3. Якщо ви не задали пароль, прочитайте згенерований через адміністративний RPC на сервері:

   ```bash
   curl -s http://127.0.0.1:9005/mds
   ```

4. Зі свого комп'ютера відкрийте SSH-тунель до файлового порту MDS (P2P-порт 9001 означає 9003):

   ```bash
   ssh -L 9003:127.0.0.1:9003 root@node.example.com
   ```

5. Відкрийте `https://127.0.0.1:9003/` у браузері, прийміть самопідписаний сертифікат і увійдіть
   із паролем.

---

## 20. Сід-фрази, ідентичність і резервні копії

> **Сід-фраза — це гаманець.** Фраза вузла з 24 слів, `identity.txt` і будь-який перенесений вами `seed.txt`
> дають змогу витрачати кошти. Ніколи не виводьте їх у журнал, заявку, чат чи термінал, який
> записується. Коли шукаєте щось через grep у файлах юнітів або журналах, додавайте `| grep -v seed`.

### Два секрети, одне джерело

| Що | Де | Для чого |
|---|---|---|
| **Сід-фраза гаманця** | сховище (vault) вузла, у `<data>/<base version>/` | гаманець вузла (гаманець облікового запису) |
| **Фраза ідентичності** | `<data>/identity.txt` (режим 600) | ідентичність Maxima `Mx…`, `MAX#` облікового запису, під'єднання, контакти |

Під час першого запуску вузол створює власну сід-фразу гаманця й копіює фразу в `identity.txt`. Якщо
фраза вузла власна і правило фраз не може її відтворити, натомість зберігається сід у hex.
Відтоді ідентичність береться з `identity.txt`, а не зі сховища. Тому:

- Гаманець можна **повторно синхронізувати** на нову фразу (з під'єднаного пристрою: Гаманець → «Пересинхронізувати гаманець на
  нову фразу…»), не змінюючи `MAX#`, під'єднаних пристроїв чи контактів. Вузол виконує
  `megammrsync action:resync host:<parlons.node.archive> …` і завершує роботу з кодом 3. Якщо повторна синхронізація
  не вдається, вузол продовжує працювати, гаманець не змінюється, а причину показано на пристрої.
- Щоб знову закріпити ідентичність за тим, що зараз є у сховищі, видаліть `identity.txt` і перезапустіть.

### Як створити резервну копію сід-фрази гаманця

1. Переконайтеся, що адміністративний RPC увімкнено ([§21](#21-the-admin-rpc)).
2. На сервері, у сеансі, який не записується, виведіть сховище:

   ```bash
   curl -s http://127.0.0.1:9005/vault
   ```

3. Запишіть фразу `phrase` з 24 слів на папері або в офлайн-сховищі. Не перенаправляйте її у файл у
   спільному місці.
4. Очистіть прокрутку термінала.
5. Якщо фраза ідентичності відрізняється від фрази гаманця (після повторної синхронізації або імпорту ідентичності),
   створіть резервну копію й `identity.txt` (наступна процедура).

### Як створити резервну копію всього вузла

Сам ланцюг резервної копії не потребує; новий вузол синхронізує його знову. Зберігайте копії секретів і своїх
налаштувань.

1. Створіть резервну копію сід-фрази гаманця (попередня процедура).
2. Скопіюйте файли ідентичності й облікового запису в приватне зашифроване місце, яке ви контролюєте. Спочатку зупиніть вузол,
   щоб файли були узгодженими:

   ```bash
   systemctl stop parlons-node
   tar -C /var/lib/parlons-node -czf /root/parlons-node-backup.tgz \
       --exclude='./1.1' --exclude='./nft/tmp' .
   systemctl start parlons-node
   chmod 600 /root/parlons-node-backup.tgz
   ```

   Це містить `identity.txt`, обліковий запис (пристрої, контакти, чат), стан ретранслятора, токен
   шлюзу й розміщені файли NFT. Це секрет рівня гаманця: поводьтеся з ним як із сід-фразою.
3. Скопіюйте юніт і конфігурацію свого TLS-фронту:

   ```bash
   cp /etc/systemd/system/parlons-node.service /root/
   ```

4. Перенесіть архів із сервера, потім видаліть його там:

   ```bash
   scp root@node.example.com:/root/parlons-node-backup.tgz .
   ssh root@node.example.com rm /root/parlons-node-backup.tgz
   ```

5. Для переносної копії лише облікового запису створіть пакет `.pbk` на під'єднаному пристрої
   ([посібник порталу Parlons Cloud, §16](parlons-cloud-portal.md#16-backups-recovery-and-moving)).

Ніколи не відновлюйте цей архів на другому сервері, поки працює перший. Одна ідентичність працює в
одному місці.

### Як перенести наявну фразу скриптом розгортання

1. Покладіть фразу з 24 слів у файл на сервері, що належить root, з режимом 600. На сервері, де працював
   `maxima-server.jar`, вона вже є в `/var/lib/maxima/seed.txt`.
2. Повторно запустіть скрипт розгортання з `--rpc --seed-from <file on the box>` і звичними прапорцями.
   Передайте `--archive <host:port>`, щоб вибрати архівний вузол. Якщо ви також наповнюєте MegaMMR,
   скрипт спершу переносить фразу, як і має бути.
3. Прочитайте результат. `vault MATCHES <file> - identity preserved (and pinned in identity.txt)` означає,
   що все вдалося. Будь-який інший результат означає: не довіряйте вузлу, доки не з'ясуєте причину.
4. Видаліть файл із фразою із сервера, щойно матимете офлайн-копію.

### Що робить `--seed-from`

Він зберігає ідентичність наявного ретранслятора чи хмарного облікового запису. Скрипт розгортання:

1. Перевіряє, що файл на сервері містить рівно 24 слова.
2. Надсилає `megammrsync action:resync host:<--archive> phrase:"…"` як **тіло POST-запиту з тимчасового
   файлу з режимом 600**. Фраза ніколи не потрапляє в argv і в журнал.
3. Перезапускає вузол і порівнює фразу сховища з файлом, не виводячи жодну з них.
4. Якщо вони збігаються, записує `identity.txt` з файлу й записує позначку `.seed-adopted`, щоб
   наступні запуски пропускали цей крок.

Фраза ніколи не залишає сервер.

### Як повторно синхронізувати гаманець на іншу фразу

З під'єднаного пристрою: Гаманець → **Пересинхронізувати гаманець на нову фразу…**. Вузол використовує архівний хост
з `parlons.node.archive`, завершує роботу з кодом 3 і повертається з тією самою ідентичністю.

Вручну на сервері (саме це робить `--seed-from` у скрипті розгортання):

1. Запишіть команду в приватний тимчасовий файл, щоб фраза ніколи не потрапила в argv чи журнал:

   ```bash
   umask 077
   tmp=$(mktemp /var/lib/parlons-node/.resync.XXXXXX)
   printf 'megammrsync action:resync host:%s phrase:"%s"' 'archive.example.net:9001' '<24 words>' > "$tmp"
   ```

   Вводьте фразу в сеансі, який не записується, і потім очистіть історію оболонки.
2. Надішліть її адміністративному RPC як тіло POST-запиту. Це може тривати кілька хвилин:

   ```bash
   curl -s -m 3600 --data-binary @"$tmp" http://127.0.0.1:9005/
   shred -u "$tmp"
   ```

   Відповідь має містити `"status":true`. Повторна синхронізація завершується зупинкою вузла.
3. Перезапустіть і зачекайте, поки RPC знову відповідатиме:

   ```bash
   systemctl restart parlons-node
   until curl -s -m 5 http://127.0.0.1:9005/status >/dev/null; do sleep 2; done
   ```

4. Перевірте, що сховище тепер містить нову фразу (`curl -s http://127.0.0.1:9005/vault`), у сеансі,
   який не записується.
5. Якщо ви наповнюєте MegaMMR, імпортуйте його знову зараз ([§13](#13-megammr-mode-and-the-rootnode)):
   повторна синхронізація скинула бази даних ланцюга.

Ідентичність лишається закріпленою в `identity.txt`, тож `MAX#`, під'єднані пристрої й контакти не
змінюються.

### Як знову закріпити ідентичність за сховищем

Це змінює ідентичність Maxima й `MAX#`, якщо фраза сховища відрізняється від
`identity.txt`. Тоді під'єднані пристрої й контакти вказують на стару ідентичність.

1. Збережіть копію старого файлу ідентичності, потім видаліть його:

   ```bash
   cp /var/lib/parlons-node/identity.txt /var/lib/parlons-node/identity.txt.before-repin
   rm /var/lib/parlons-node/identity.txt
   ```

2. Перезапустіть. Журнал запуску покаже `identity: pinned from the vault into identity.txt`:

   ```bash
   systemctl restart parlons-node
   journalctl -u parlons-node -b --no-pager | grep 'identity:' | grep -v seed
   ```

3. Видаліть копію `.before-repin`, коли переконаєтеся, що стара ідентичність вам не потрібна.

### Як запустити вузол, заблокований паролем

Якщо сховище заблоковане, кейп, шлюз і обліковий запис не можуть запуститися, доки його не розблоковано. Сам
вузол Minima продовжує працювати. Вузол пробує розблокуватися рівно один раз за запуск, з файлу або
зі змінної середовища `PARLONS_NODE_PASSPHRASE`.

1. Запишіть парольну фразу у файл у `/var/lib/parlons-node` або `/etc` (не в `/root` чи
   `/home`: `ProtectHome=true` їх приховує). Парольна фраза не може містити пробільних символів, `"` або `;`:

   ```bash
   umask 077
   printf '%s\n' '<passphrase>' > /var/lib/parlons-node/passphrase.txt
   chown maxima:maxima /var/lib/parlons-node/passphrase.txt
   chmod 600 /var/lib/parlons-node/passphrase.txt
   ```

2. Додайте його до юніта й перезапустіть ([§7](#how-to-change-the-unit)). Зі скриптом розгортання передайте
   `--passphrase-file /var/lib/parlons-node/passphrase.txt`:

   ```
   -Dparlons.node.passphrase.file=/var/lib/parlons-node/passphrase.txt
   ```

3. Перевірте, що кейп, шлюз і обліковий запис запустилися (`cape=up`, `gateway=up` у рядку пульсу).
   `node is password-locked but no passphrase provided` у журналі означає, що файл не було прочитано.

### Ніколи

- Ніколи не передавайте `-seed`, `-anyseed` чи `-dbpassword` у командному рядку. Вузол однаково їх відхиляє.
- Ніколи не запускайте два процеси на одному каталозі даних або одну ідентичність на двох хостах. Стан поштових скриньок і
  каталогу розділяється між ними, і повідомлення губляться.
- Ніколи не копіюйте `identity.txt` на інший працюючий сервер.

---

## 21. Адміністративний RPC

- Увімкніть його через `-Dparlons.node.rpc=true`. Він слухає на `127.0.0.1:<p2p+4>` (9005, коли P2P —
  9001). Прив'язка до loopback жорстко задана в коді; жодне налаштування не може відкрити його назовні.
- Він приймає **будь-яку** команду вузла **без автентифікації**. Він довіряє локальним процесам. Він
  відхиляє міжсайтові (cross-origin) запити з браузера (перевіряє заголовок Host, Origin і Sec-Fetch-Site).
- Дві форми запиту: `GET /<url-encoded command>` або `POST /` з командою в тілі (щонайбільше
  64 KiB).

  ```bash
  curl -s http://127.0.0.1:9005/status
  curl -s 'http://127.0.0.1:9005/balance'
  curl -s --data-binary 'network' http://127.0.0.1:9005/
  ```

- `quit` зупиняє вузол, і JVM завершує роботу приблизно через 0,8 с з кодом 0. З `Restart=always`
  (саме це записує скрипт розгортання) systemd запускає його знову через 10 с. Щоб зупинити вузол остаточно,
  натомість використовуйте `systemctl stop parlons-node`.
- **Ніколи не ставте його за проксі й ніколи не відкривайте його порт.** Захищена публічна точка доступу — це шлюз.
- Якщо порт уже зайнятий, вузол записує в журнал `REFUSING to run: admin rpc on 127.0.0.1:N cannot
  start …` і завершує роботу з кодом 2.

### Як увімкнути адміністративний RPC

1. Додайте `-Dparlons.node.rpc=true` до юніта й перезапустіть ([§7](#how-to-change-the-unit)). Зі
   скриптом розгортання передайте `--rpc`.
2. Перевірте рядок запуску. Там має бути `admin rpc 127.0.0.1:9005 (loopback-bound)`:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'admin rpc' | grep -v seed
   ```

3. На сервері виконайте команду:

   ```bash
   curl -s http://127.0.0.1:9005/status
   ```

4. З іншої машини перевірте, що порт не відповідає. Команда не має нічого вивести:

   ```bash
   nc -z -w 5 node.example.com 9005 && echo "EXPOSED 9005"
   ```

### Як виконати команду з пробілами або лапками

1. Для короткої команди закодуйте пробіли в URL як `%20`:

   ```bash
   curl -s 'http://127.0.0.1:9005/balance%20megammr:true%20address:<address>'
   ```

2. Для довших команд або команд із лапками надсилайте їх як тіло POST-запиту (до 64 KiB):

   ```bash
   curl -s --data-binary 'coins relevant:true' http://127.0.0.1:9005/
   ```

### Як зупинити або перезапустити вузол

Використовуйте systemd, а не `quit`:

```bash
systemctl stop parlons-node
systemctl start parlons-node
systemctl restart parlons-node
```

---

## 22. Оновлення й відкат

Прийнятий підхід — **jar-файли з версіями плюс символьне посилання**. Кожен jar зберігається в `/opt/maxima`, а
юніт завжди запускає `/opt/maxima/parlons-node.jar`, який є символьним посиланням.

### Як оновити один сервер

1. На своєму комп'ютері завантажте новий jar і перевірте його SHA-256 ([§4](#4-download-and-verify-the-jar)).
2. Скопіюйте його на сервер під тимчасовою назвою:

   ```bash
   B=parlons-node-0.2.138.jar
   scp $B root@node.example.com:/opt/maxima/$B.part
   ```

3. На сервері ще раз перевірте хеш, потім поставте jar на місце:

   ```bash
   cd /opt/maxima
   echo '597a77225514b8ceddf1036a6016abc6d4b826e7711210c7f55de01857c9b4af  parlons-node-0.2.138.jar.part' | sha256sum -c --quiet
   mv parlons-node-0.2.138.jar.part parlons-node-0.2.138.jar
   chmod 644 parlons-node-0.2.138.jar
   ```

4. Запишіть, на який jar зараз вказує символьне посилання. Це знадобиться для відкату:

   ```bash
   readlink /opt/maxima/parlons-node.jar
   ```

5. Створіть резервну копію юніта:

   ```bash
   cp /etc/systemd/system/parlons-node.service /etc/systemd/system/parlons-node.service.before-parlons-node-0.2.138.jar
   ```

6. Оновіть версію в рядку `Description` юніта (необов'язково; це лише підпис):

   ```bash
   sed -i 's/wallet gateway ([0-9.]*)/wallet gateway (0.2.138)/' /etc/systemd/system/parlons-node.service
   ```

7. Спрямуйте символьне посилання на новий jar:

   ```bash
   ln -sfn /opt/maxima/parlons-node-0.2.138.jar /opt/maxima/parlons-node.jar
   ```

8. Перезапустіть:

   ```bash
   systemctl daemon-reload
   systemctl restart parlons-node
   ```

9. Зачекайте близько 25 с, потім перевірте:

   ```bash
   systemctl is-active parlons-node
   journalctl -u parlons-node --since -40s --no-pager | grep -E 'Parlons Node|dex relay|Exception' | grep -v seed
   ```

   Рядок запуску має показувати `Parlons Node 0.2.138`, а кейп DEX має показувати `dex relay 0.1.5 up`.
10. Перевірте ззовні ([§23](#23-verifying-from-outside)).

Якщо у вас кілька серверів, оновлюйте **по одному серверу**, і перевіряйте кожен, перш ніж братися за наступний.

### Як оновити скриптом розгортання

1. Покладіть новий перевірений jar у `dist/` на своєму комп'ютері.
2. Повторно запустіть скрипт із **тими самими прапорцями**, що й раніше, і новим jar:

   ```bash
   ops/deploy-parlons-node.sh root@node.example.com --jar dist/parlons-node-0.2.138.jar <your usual flags>
   ```

   Скрипт вивантажує й перевіряє jar, перемикає символьне посилання й переписує юніт на основі своїх прапорців.
   Прапорець, який ви пропустите, повертається до типового значення.
3. Перевірте ззовні ([§23](#23-verifying-from-outside)).

### Як виконати відкат

1. Спрямуйте символьне посилання на попередній jar (той, який показав `readlink` у кроці 4 оновлення):

   ```bash
   cd /opt/maxima && ln -sfn parlons-node-<old version>.jar parlons-node.jar
   ```

2. Якщо ви змінювали юніт, відновіть його з резервної копії `.before-…`:

   ```bash
   cp /etc/systemd/system/parlons-node.service.before-<what> /etc/systemd/system/parlons-node.service
   ```

3. Перезапустіть:

   ```bash
   systemctl daemon-reload
   systemctl restart parlons-node
   ```

4. Перевірте, що рядок запуску показує стару версію, потім перевірте ззовні ([§23](#23-verifying-from-outside)).

### Як повернутися до `maxima-relay.service`

Якщо на сервері раніше працював `maxima-relay.service` і вузол його замінив, можна повернутися й до
нього. Скрипт розгортання залишив його юніт на диску:

```bash
systemctl disable --now parlons-node
systemctl enable --now maxima-relay
```

---

## 23. Перевірка ззовні

`systemctl is-active` показує лише те, що процес працює. Завжди перевіряйте **з іншої машини
в публічному інтернеті**. Не перевіряйте із самого сервера чи з його локальної мережі: hairpin NAT дає
хибні результати в обидва боки.

1. **Доступність TCP** для P2P-порту й порту ретранслятора:

   ```bash
   nc -z -w 8 node.example.com 9001 && echo p2p-open
   nc -z -w 8 node.example.com 9501 && echo relay-open
   ```

2. **Наскрізна перевірка ретранслятора.** З клону цього репозиторію, де `:core` і його тести
   скомпільовано в `build/classes`:

   ```bash
   ops/verify-relay.sh node.example.com:9501
   ```

   Це підключає двох справжніх клієнтів через ретранслятор і змушує їх поспілкуватися: привітання, під'єднання, призначення
   адреси, обмін рукостисканням контактів, ретрансльована доставка в обидва боки, повернення підтвердження доставки й
   групова розсилка. Скрипт завершується з кодом 0, лише якщо пройдено всі перевірки.
3. **Кейп DEX:**

   ```bash
   curl -s https://node.example.com/dex-relay/dex/health    # {"ok":true,"version":"0.1.5",...}
   curl -s https://node.example.com/dex-relay/dex/relays
   ```

4. **Шлюз:** POST-запит `block` із токеном ([§14](#14-the-wallet-gateway-and-its-token)).
   Запит без токена має повернути 401.
5. **NFT:** `curl -sI https://node.example.com/parlons-node/nft/<sha256>.<ext>` повертає 200 для
   розміщеного файлу й 404 для всього іншого.
6. **Порти лише для loopback НЕ повинні відповідати ззовні:**

   ```bash
   for p in 9005 9585 9587 9602; do nc -z -w 5 node.example.com $p && echo "EXPOSED $p"; done
   ```

   Команда не має нічого вивести.

---

## 24. Моніторинг і журнали

Увесь вивід іде в журнал systemd (`StandardOutput=journal`). Корисні команди:

```bash
journalctl -u parlons-node -f                                   # follow
journalctl -u parlons-node -f | grep heartbeat                  # sync progress and subsystem health
journalctl -u parlons-node --since -1h | grep -E 'Exception|FAILED|REFUSING' | grep -v seed
journalctl -u parlons-node -b | grep -E 'Parlons Node|cape up|gateway up|dex relay|account up'
systemctl status parlons-node
```

Рядок пульсу, кожні 30 с:

```
[parlons-node] heartbeat: node block=<n> chainlen=<n> | cape=up:<port> | gateway=up:<port> | account=up:<n> hosts, <n> devices
```

| Поле | Норма |
|---|---|
| `block=` | зростає, потім тримається на вершині ланцюга |
| `cape=` | `up:<relay port>` (`down` означає, що кейп не запустився або вимкнений) |
| `gateway=` | `up:<gateway port>` |
| `account=` | `up:` з 1 або більше хостами (`off`, коли `parlons.account=false`) |

Якщо ви бачите `heartbeat: node not ready: …`, вбудований вузол ще не відповідає на `status`. Це
нормально протягом короткого часу після запуску.

### Як перевірити стан вузла за одну хвилину

1. Чи працює служба? `-w` зіставляє ціле слово, тож `inactive` не рахується:

   ```bash
   systemctl is-active parlons-node | grep -qw active && echo running
   ```

2. Прочитайте останній рядок пульсу:

   ```bash
   journalctl -u parlons-node --since -2min --no-pager | grep heartbeat | tail -1
   ```

3. Звірте кожне поле з таблицею вище.
4. Пошукайте помилки за останню годину:

   ```bash
   journalctl -u parlons-node --since -1h --no-pager | grep -E 'Exception|FAILED|REFUSING|OutOfMemoryError' | grep -v seed
   ```

5. Перевірте диск і пам'ять:

   ```bash
   df -h /var/lib
   systemctl status parlons-node --no-pager | grep -E 'Memory|Tasks'
   ```

6. Виконайте перевірки ззовні ([§23](#23-verifying-from-outside)).

### Як отримувати сповіщення про проблеми

Вузол не має кінцевої точки для метрик; інтерфейсом слугують рядок пульсу й наведені нижче рядки журналу.
Проста перевірка — завдання cron, яке надсилає сповіщення, коли рядка пульсу немає або з'являється тривожний рядок:

1. Запишіть скрипт перевірки `/usr/local/bin/parlons-node-check`:

   ```bash
   #!/bin/sh
   # Exit 1 (and print why) when the node needs attention.
   systemctl is-active parlons-node | grep -qw active || { echo "parlons-node not active"; exit 1; }
   journalctl -u parlons-node --since -2min --no-pager | grep -q heartbeat || { echo "no heartbeat for 2 min"; exit 1; }
   bad=$(journalctl -u parlons-node --since -5min --no-pager | grep -E 'REFUSING|FAILED|OutOfMemoryError|password-locked' | grep -v seed)
   [ -z "$bad" ] || { echo "$bad"; exit 1; }
   ```

2. Зробіть його виконуваним і запускайте кожні 5 хвилин із crontab користувача root разом із власною командою сповіщення:

   ```bash
   chmod 755 /usr/local/bin/parlons-node-check
   crontab -e
   # */5 * * * * /usr/local/bin/parlons-node-check || <your alert command>
   ```

Рядки журналу, про які варто отримувати сповіщення:

| Рядок містить | Значення |
|---|---|
| `REFUSING to start` / `REFUSING to run` | неправильні прапорці або конфлікт портів (вихід 2) |
| `Maxima cape FAILED to start` | ретранслятор не запустився |
| `wallet gateway FAILED` | шлюз не запустився |
| `account layer FAILED to start` | обліковий запис не запустився |
| `node is password-locked but no passphrase provided` | кейп, шлюз і обліковий запис чекають на розблокування |
| `node seed (vault) not available after 120s` | гаманець не готовий, тому кейп не може вивести свою ідентичність |
| `the embedded node shut itself down` | після повторної синхронізації, відновлення чи скидання JVM завершує роботу; перезапустіть її |
| `wallet resync FAILED` | повторна синхронізація з пристрою не вдалася; гаманець не змінено |
| `OutOfMemoryError` | купа замала (див. [§3](#3-requirements)) |

---

## 25. Нотатки щодо брандмауера

**Вхідні, відкрити:**

- TCP `<p2p port>` (9001 або те, що ви задали). Сюди підключаються вузли Minima.
- TCP `<relay port>` (9501). Сюди підключаються телефони й вузли меш-мережі.
- TCP 443 (і 80 для ACME), якщо сервер обслуговує TLS-фронт.
- Необов'язково: UDP `<relay port>` для STUN. Скрипт розгортання його не відкриває.

**Вхідні, ніколи не відкривати:** `p2p+2`, `p2p+3`, `p2p+4`, 9585, 9587, 9602 і будь-яку панель орендаря 959x.

**Хмарні брандмауери.** Брандмауер провайдера (у консолі Vultr, Hetzner чи AWS) може відкидати пакети
ще до того, як вони дійдуть до сервера. ufw цього не виправить. Якщо порт ззовні не відповідає (тайм-аут), хоча він відкритий
в ufw і відповідає на localhost, відкрийте його в консолі провайдера. Застосування змін правил може тривати хвилину чи
дві, тож перевірте ще раз, перш ніж робити висновки.

**Вихідні з типовою забороною.** На сервері з політикою типової заборони вихідного трафіку (`ufw default deny
outgoing`) вузол не може дістатися вузлів, яких ви не дозволили. Дозвольте щонайменше:

- вихідний TCP до вашого кореневого вузла й вузлів Minima;
- вихідний TCP до порту ретранслятора **кожного** вузла меш-мережі, використовуючи порт, на якому ретранслятор цього вузла **справді**
  слухає (деякі вузли використовують 8001, а не 9501): `ufw allow out to <peer-ip> port <port> proto tcp`;
- вихідний TCP до архівного вузла, який використовується для повторної синхронізації;
- вихідний 443 для завантаження знімків.

Відсутнє правило для вихідного трафіку проявляється як вузол, якого немає в `/dex/relays`, або як пошуки в меш-мережі,
які ніколи не доходять до одного з ретрансляторів.

**Домашні роутери.** Переадресуйте P2P-порт і порт ретранслятора або використовуйте `-Dparlons.relay.port=shared`,
щоб вистачило одного переадресованого порту.

### Як відкрити порти за допомогою ufw

1. Спочатку дозвольте SSH, щоб не заблокувати самого себе, потім публічні порти вузла:

   ```bash
   ufw allow OpenSSH
   ufw allow 9001/tcp
   ufw allow 9501/tcp
   ufw allow 80/tcp
   ufw allow 443/tcp
   ```

2. Необов'язково: дозвольте STUN на порту ретранслятора:

   ```bash
   ufw allow 9501/udp
   ```

3. Увімкніть ufw, якщо його вимкнено, і перевірте правила:

   ```bash
   ufw enable
   ufw status numbered
   ```

4. Відкрийте ті самі порти в брандмауері хмарного провайдера, якщо він є.
5. Перевірте з іншої машини ([§23](#23-verifying-from-outside)).

### Як відкрити порти за допомогою firewalld

1. Додайте порти назавжди, потім перезавантажте конфігурацію, щоб вони набули чинності:

   ```bash
   firewall-cmd --permanent --add-port=9001/tcp
   firewall-cmd --permanent --add-port=9501/tcp
   firewall-cmd --permanent --add-service=http
   firewall-cmd --permanent --add-service=https
   firewall-cmd --reload
   ```

2. Перевірте результат:

   ```bash
   firewall-cmd --list-all
   ```

Скрипт розгортання додає порти з `--permanent`, але не перезавантажує firewalld. На сервері з firewalld
після скрипту виконайте `firewall-cmd --reload`.

### Як дозволити вихідний трафік на сервері з типовою забороною

1. Дозвольте порт ретранслятора кожного вузла меш-мережі (повторіть для кожного вузла, з його справжнім портом):

   ```bash
   ufw allow out to <peer ip> port 9501 proto tcp
   ```

2. Дозвольте кореневий вузол, ваші вузли Minima й архівний вузол (повторіть для кожного):

   ```bash
   ufw allow out to <peer ip> port 9001 proto tcp
   ```

3. Дозвольте вихідний HTTPS для завантаження знімків, а також DNS:

   ```bash
   ufw allow out 443/tcp
   ufw allow out 53
   ```

4. Перевірте, що вузол, якого бракувало в `/dex/relays`, з'являється приблизно за 10 хвилин.

### Як переадресувати порти на домашньому роутері

1. Задайте серверу фіксовану адресу в локальній мережі (резервування DHCP у роутері).
2. У роутері переадресуйте TCP 9001 і TCP 9501 на цю адресу. З
   `-Dparlons.relay.port=shared` переадресуйте лише 9001.
3. Перевіряйте ззовні домашньої мережі, наприклад із телефона через мобільні дані або з VPS. Перевірка
   зсередини локальної мережі не є дійсною (hairpin NAT).

---

## 26. Розв'язання проблем

### Спершу розберіться з «не вдається підключитися»

Ззовні `nc -z -w 8 <host> <port>` має три можливі результати:

| Результат | Означає | Що перевірити |
|---|---|---|
| **Відмова** (refused), одразу | Хост відповів, але нічого не слухає. | `systemctl status parlons-node`; `ss -ltnp \| grep <port>`; журнал запуску |
| **Тайм-аут** (~8 с) | Фільтр пакетів мовчки відкидає пакети. | Хмарний брандмауер або переадресацію порту на роутері, потім ufw |
| **Відкрито** | Мережевий шлях у порядку; проблема в застосунку. | Журнал; `ops/verify-relay.sh` |

Перевірки на сервері:

```bash
systemctl is-active parlons-node
ss -ltnp | grep -E ':(9001|9501|9585|9587|9602) '
ufw status numbered | head -20
bash -c '</dev/tcp/127.0.0.1/9501' && echo localhost-ok
```

Вирішальна перевірка — перехопити пакети на сервері, поки ви підключаєтеся ззовні:

```bash
# on the box
timeout 25 tcpdump -ni any 'tcp port 9501' -c 5
# meanwhile, from outside
nc -z -w 5 node.example.com 9501
```

- Пакетів немає взагалі: їх відкидають десь раніше, і жодні дії на сервері цього не виправлять.
- SYN надходить, але SYN-ACK у відповідь немає: їх відкидає сам сервер.

Остерігайтеся `systemctl is-active x | grep -q active`: це також збігається з `inactive`. Використовуйте `grep -qw active`.

### «Invalid SyncBlock as NO PARENT» після перезапуску

Таке траплялося в журналі одразу після перезапуску, поки вузол наздоганяв вершину ланцюга.
Глибше це не досліджували. Що робити:

1. Стежте за рядком пульсу. Якщо `block=` продовжує зростати й досягає вершини, вузол відновився сам.
2. Якщо `block=` довго не змінюється, перевірте, що кореневий вузол та інші вузли доступні
   (вихідний брандмауер, [§25](#25-firewall-notes)) і що годинник правильний.

### Порт уже використовується

| Симптом | Причина | Виправлення |
|---|---|---|
| `REFUSING to run: admin rpc on 127.0.0.1:9005 cannot start` | Інший вузол Minima займає `p2p+4`, або в JRE немає `jdk.httpserver`. | Перенесіть P2P-порт (`parlons.node.port=9101` переносить RPC на 9105) або використовуйте повний JRE. |
| Конфлікт порту ретранслятора; `cape=down` | `maxima-relay.service` або інший ретранслятор займає 9501. | `systemctl disable --now maxima-relay` або виберіть інший порт ретранслятора. |
| Панель не запускається на 9587 | Його займає служба `parlons-tenants` (або другий вузол). | Задайте орендарям `--panel-port 9597` або задайте `parlons.panel.port`. |
| `wallet gateway FAILED: …BindException` | Щось займає 9585. | Задайте `parlons.gateway.port` і відповідно оновіть TLS-фронт. |
| Вузол не запускається на 9001 | На 9001 працює звичайний вузол Minima. | `parlons.node.port=9101` |

### Інші симптоми

| Симптом | Виправлення |
|---|---|
| Процес одразу завершується й виводить довідку | Ви передали аргумент командного рядка. Усі налаштування — це властивості `-D` перед `-jar`. |
| `REFUSING to start: these Minima flags are not allowed` | Видаліть перелічені прапорці з `parlons.node.args` або conf-файлу (див. [§8.8](#88-minima-flags-the-node-refuses)). |
| `block=` лишається на 0 | Немає кореневого вузла чи списку connect, або вихідний доступ до нього заблоковано. |
| Цикл нестачі пам'яті, або служба опиняється у стані «failed» | Збільште купу (щонайменше 3 GB з MegaMMR) і тримайте `MemoryMax` вище неї. Скиньте ліміт запусків командою `systemctl reset-failed parlons-node`. |
| Телефони бачать порожні баланси | MegaMMR ще не імпортовано ([§13](#13-megammr-mode-and-the-rootnode)). |
| Шлюз повертає 503 `no MegaMMR` | Вузол працює з `megammr=false`. Телефони перемикаються на інший шлюз. |
| Шлюз повертає 403 `requires an address:` | Додайте `address:` до `coins`/`balance`. |
| Шлюз повертає 429 | Загальний лічильник вичерпано. Збільште `parlons.gateway.rate.global` або обмежуйте частоту в проксі. |
| Шлюз ніколи не оголошується телефонам | Потрібні увімкнений MegaMMR, задане `parlons.node.public` і ввімкнений ретранслятор. |
| `url` NFT порожній | Задайте в `parlons.node.public` URL-адресу, яка справді веде на порт 9585 цього сервера. |
| `own relay NOT advertised: public host unknown or private` | Задайте `-Dparlons.relay.host=<public host>`. |
| `dex relay: no public host known yet - NOT announced` | Задайте `-Dparlons.dex.public=<https url>` (або `parlons.relay.host`). |
| Вузла немає в `/dex/relays` | Зачекайте один 10-хвилинний цикл повторного підключення. Потім перевірте, що цей сервер може підключитися **назовні** до порту ретранслятора того вузла. |
| Кейп DEX відповідає на публічній IP-адресі | `parlons.dex.bind` досі `0.0.0.0`. Задайте `127.0.0.1` і перезапустіть. |
| Caddy віддає ваш вебсайт на `/dex-relay/…` | Пастка порядку `handle_path` проти `handle` ([§11](#11-tls-fronting-caddy-nginx-apache)). |
| Обліковий запис ніколи не повідомляє «attached» | Немає вихідного доступу до інтернету, або годинник сильно відстає чи поспішає. |
| Кейп, шлюз і обліковий запис ніколи не запускаються; журнал згадує блокування паролем | Надайте парольну фразу ([§20](#how-to-run-a-password-locked-node)). |
| Служба зупинилася після `quit`, ручного імпорту megammr або повторної синхронізації | У юніті досі вказано `Restart=on-failure`, а це не перезапускає після штатного завершення. Переведіть його на `Restart=always` (див. виправлення нижче). |
| Допоміжний процес keets завершується з `EPERM` на сокетах | Додайте `AF_NETLINK` до `RestrictAddressFamilies`. |
| Завершення завислого процесу | Завершуйте його за PID. Ніколи не виконуйте `pkill -f parlons-node.jar`: шаблон може збігтися з вашим власним SSH-сеансом. |

### Покрокові виправлення

#### Виправлення: порт адміністративного RPC зайнятий

1. Подивіться, хто його займає:

   ```bash
   ss -ltnp | grep ':9005 '
   ```

2. Якщо це звичайний вузол Minima, перенесіть P2P-порт Parlons Node ([§9](#how-to-move-the-node-off-port-9001)).
3. Якщо його ніхто не займає, у JRE немає `jdk.httpserver`. Перевірте й установіть повний JRE, якщо рядка немає:

   ```bash
   java --list-modules | grep jdk.httpserver || apt-get install -y default-jre-headless
   ```

#### Виправлення: порт ретранслятора зайнятий

1. Подивіться, хто займає 9501:

   ```bash
   ss -ltnp | grep ':9501 '
   ```

2. Якщо це старий окремий ретранслятор, зупиніть його й перезапустіть вузол:

   ```bash
   systemctl disable --now maxima-relay
   systemctl restart parlons-node
   ```

3. Перевірте, що рядок пульсу показує `cape=up:9501`.

#### Виправлення: порт панелі або шлюзу зайнятий

1. Подивіться, хто його займає:

   ```bash
   ss -ltnp | grep -E ':(9585|9587) '
   ```

2. Для служби `parlons-tenants` на 9587 задайте їй `--panel-port 9597`
   ([посібник порталу Parlons Cloud, §21](parlons-cloud-portal.md#21-many-accounts-the-parlons-tenants-service)).
   Інакше перенесіть вузол: задайте `-Dparlons.panel.port=<free port>` або `-Dparlons.gateway.port=<free port>`.
3. Якщо ви перенесли шлюз, змініть порт і в TLS-фронті, потім перезавантажте його.
4. Перезапустіть вузол ([§7](#how-to-change-the-unit)).

#### Виправлення: вузол одразу завершується або відмовляється запускатися

1. З'ясуйте причину:

   ```bash
   journalctl -u parlons-node -n 30 --no-pager | grep -E 'REFUSING|usage|Usage' | grep -v seed
   ```

2. Якщо було виведено довідку, видаліть із юніта всі аргументи після `-jar …parlons-node.jar`.
   Усі налаштування — це властивості `-D` перед `-jar`.
3. Якщо названо відхилені прапорці Minima, видаліть їх із `parlons.node.args` або conf-файлу
   ([§8.8](#88-minima-flags-the-node-refuses)).
4. Якщо сказано, що не вдається прочитати `-conf`, перевірте шлях до conf-файлу, власника (`maxima`) і режим (600).
5. Перезапустіть ([§7](#how-to-change-the-unit)).

#### Виправлення: `block=` лишається на 0

1. Перевірте, що в юніті є кореневий вузол або список connect:

   ```bash
   grep -E 'parlons.node.(rootnode|connect)' /etc/systemd/system/parlons-node.service
   ```

2. Якщо немає жодного, задайте його ([§13](#how-to-set-the-rootnode)).
3. Перевірте, що сервер може до нього дістатися:

   ```bash
   nc -z -w 8 seed-peer.example.net 9001 && echo reachable
   ```

4. Якщо не може, дозвольте вихідний трафік до нього ([§25](#how-to-allow-outbound-traffic-on-a-default-deny-box)).
5. Перевірте годинник:

   ```bash
   timedatectl | grep -E 'synchronized|NTP'
   ```

#### Виправлення: цикл нестачі пам'яті, або служба в стані «failed»

1. Підтвердьте причину:

   ```bash
   journalctl -u parlons-node --since -1h --no-pager | grep -E 'OutOfMemoryError|oom' | tail -5
   ```

2. Збільште `-Xmx` у юніті (3g або більше з MegaMMR) і задайте `MemoryMax` щонайменше на 512 MB більше,
   наприклад `-Xmx3g` з `MemoryMax=3584M`.
3. Перезавантажте конфігурацію, скиньте ліміт запусків і запустіть знову:

   ```bash
   systemctl daemon-reload
   systemctl reset-failed parlons-node
   systemctl restart parlons-node
   ```

4. Через хвилину перевірте, що служба досі `active`.

#### Виправлення: телефони бачать порожні баланси

1. Перевірте розмір MegaMMR. Менше 1 MB означає, що його так і не імпортували:

   ```bash
   stat -c %s /var/lib/parlons-node/1.1/databases/megammr.mmr
   ```

2. Імпортуйте його ([§13](#how-to-import-a-megammr-snapshot)).

#### Виправлення: шлюз ніколи не оголошується телефонам

1. Перевірте три умови: MegaMMR увімкнено, `parlons.node.public` задано, ретранслятор увімкнено:

   ```bash
   grep -E 'megammr|node.public|relay.port' /etc/systemd/system/parlons-node.service
   ```

2. Виправте те, чого бракує, і перезапустіть ([§14](#how-to-publish-the-gateway-for-phones)).
3. Пошукайте в журналі `wallet gateway advertised to phones:`.

#### Виправлення: кейп не оголошує себе, або кейп DEX не оголошено

1. Прочитайте попередження:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'own relay NOT advertised|dex relay: no public host'
   ```

2. Задайте публічний хост ([§12](#how-to-set-the-public-host)), а для кейпа DEX також
   `-Dparlons.dex.public=https://node.example.com/dex-relay`.
3. Перезапустіть і знову перевірте журнал.

#### Виправлення: кейп DEX відповідає на публічній IP-адресі

1. Перевірте прив'язку:

   ```bash
   ss -ltnp | grep ':9602 '
   ```

2. Якщо показано `0.0.0.0:9602` або `*:9602`, додайте `-Dparlons.dex.bind=127.0.0.1` (і
   `-Dparlons.dex.public=…`) до юніта й перезапустіть.
3. Закрийте порт у брандмауері, якщо його відкрив скрипт розгортання:

   ```bash
   ufw delete allow 9602/tcp
   ```

4. Перевірте ззовні, що 9602 більше не відповідає ([§23](#23-verifying-from-outside), крок 6).

#### Виправлення: Caddy віддає ваш вебсайт на `/dex-relay/…`

1. Знайдіть блок DEX у Caddyfile:

   ```bash
   grep -n 'dex-relay' /etc/caddy/Caddyfile
   ```

2. Якщо там `handle_path /dex-relay/*`, змініть це на `handle /dex-relay/*` з
   `uri strip_prefix /dex-relay` усередині ([§11](#how-to-front-the-node-with-caddy)).
3. Перевірте конфігурацію й перезавантажте Caddy, потім перевірте `/dex-relay/dex/health` ззовні.

#### Виправлення: за nginx усі клієнти DEX обмежуються разом

1. Перевірте, що блок `/dex-relay/` надсилає заголовок:

   ```bash
   grep -n 'X-Forwarded-For' /etc/nginx/sites-enabled/*
   ```

2. Якщо його немає, додайте `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` до
   блоку ([§11](#how-to-front-the-node-with-nginx)), потім виконайте `nginx -t` і `systemctl reload nginx`.

#### Виправлення: служба лишається зупиненою після штатного завершення

1. Переведіть юніт на `Restart=always` ([§7](#how-to-switch-an-old-unit-to-restartalways)).
2. Запустіть вузол:

   ```bash
   systemctl start parlons-node
   ```

#### Виправлення: допоміжний процес keets завершується з `EPERM`

1. Додайте `AF_NETLINK` до юніта:

   ```bash
   sed -i 's/^RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX$/RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK/' /etc/systemd/system/parlons-node.service
   ```

2. Перезавантажте конфігурацію й перезапустіть:

   ```bash
   systemctl daemon-reload
   systemctl restart parlons-node
   ```

#### Виправлення: обліковий запис ніколи не повідомляє «attached»

1. Перевірте вихідний доступ до інтернету й годинник:

   ```bash
   curl -sI https://github.com | head -1
   timedatectl | grep synchronized
   ```

2. Якщо сервер блокує вихідний трафік, дозвольте порти ретрансляторів ([§25](#how-to-allow-outbound-traffic-on-a-default-deny-box)).
3. Після перезапуску пошукайте в журналі `account up: attached to N relay(s)`.

#### Виправлення: завислий процес

1. Знайдіть PID. Ніколи не використовуйте `pkill -f parlons-node.jar`: шаблон може збігтися з вашим власним SSH-сеансом:

   ```bash
   systemctl show -p MainPID parlons-node
   ```

2. Попросіть його зупинитися, і лише якщо він не зупиниться, завершіть примусово:

   ```bash
   kill <pid>
   sleep 15
   kill -0 <pid> 2>/dev/null && kill -9 <pid>
   ```

3. Запустіть його знову командою `systemctl start parlons-node`.

---

## 27. Контрольний список безпеки

- [ ] SHA-256 jar-файлу перевірено перед активацією.
- [ ] Служба працює від імені непривілейованого користувача `maxima`, із захищеним юнітом ([§7](#7-the-systemd-unit-annotated)).
- [ ] `/var/lib/parlons-node` має режим 700. `identity.txt`, `gateway-token.txt`, `pair-code.txt`
      і `panel-ticket.txt` мають режим 600.
- [ ] Сід-фразу й фразу гаманця збережено офлайн, і вони **не** з'являються в жодному журналі, заявці чи чаті.
- [ ] Для вхідного трафіку відкрито лише P2P-порт, порт ретранслятора й 443.
- [ ] Ззовні 9005 (адміністративний RPC), 9585, 9587 і 9602 **не** відповідають ([§23](#23-verifying-from-outside), крок 6).
- [ ] Щоразу, коли задано `parlons.dex.port`, задано й `parlons.dex.public` (тоді кейп слухає на `127.0.0.1`), або задано `parlons.dex.bind=127.0.0.1`.
- [ ] `parlons.gateway.bind` дорівнює `127.0.0.1` (без `--gateway-public` у робочому середовищі).
- [ ] Адміністративний RPC ніколи не проксіюється, а `parlons.mds.bind` ніколи не публічний.
- [ ] `-Xmx` задано, а `MemoryMax` вище за нього.
- [ ] `parlons.node.public` вказує на URL-адресу, яка справді веде на цей сервер.
- [ ] У командному рядку немає секретів. Парольні фрази й `mdspassword` зберігаються у файлах із режимом 600.
- [ ] `parlons.node.selftest.send` **не** задано.
- [ ] Кожну ідентичність і кожен каталог даних використовує лише один хост.
- [ ] Брандмауер хмарного провайдера узгоджений з ufw.
- [ ] Адміністративний пароль для під'єднання задано, а старі запрошення використано або відкинуто.

---

## 28. Настільні застосунки, що вбудовують цей jar

`parlons-node.jar` також є рушієм вузла в настільних застосунках. Вони завантажують випуск `node-v*`
у свої ресурси (`scripts/fetch-parlons-node-jar.sh`) і запускають його з власними прапорцями.

- **minimaDesk (MinimaClassic Desktop)** і **minimaCore Desktop** запускають його з
  `-Dparlons.node.rpc=true`, MegaMMR відповідно до вибору користувача, `-Dparlons.relay.port=shared`,
  коли користувач робить внесок у мережу (інакше `0`), а також із портами панелі й шлюзу, похідними від власних налаштувань портів застосунку.
- minimaDesk також передає `-Dparlons.mds=true -Dparlons.mds.bind=127.0.0.1` і
  `-Dparlons.node.conf=<file>`.
- minimaCore Desktop передає `-Dparlons.node.keetschat=off`, бо сам читає вхідні keets.
- Вбудований JRE має містити `jdk.httpserver`.

Операторам серверних вузлів нічого не потрібно робити для цих застосунків. Ніколи не запускайте настільний застосунок,
що зберігає справжні кошти, лише для того, щоб щось перевірити.

---

## 29. Глосарій

| Термін | Значення |
|---|---|
| **Адміністративний RPC** (admin RPC) | HTTP-кінцева точка вузла лише на loopback, без автентифікації, з усіма командами, на `p2p+4`. |
| **Архівний вузол** (archive node) | Вузол Minima, який зберігає достатньо історії (і MegaMMR) для повторної синхронізації через `megammrsync`. |
| **Bearer-токен** | Облікові дані шлюзу в `gateway-token.txt`. Дозволяє лише читання й трансляцію. |
| **Початкові вузли** (bootstrap peers) | Перші ретранслятори, до яких звертається кейп (`parlons.relay.peers` або вкомпільований список). |
| **Кейп** (cape) | Ретранслятор Maxima, що працює всередині вузла. Так само називають кейп DEX-ретранслятора. |
| **Курсор** (cursor) | Число в DEX-ретрансляторі, яке тільки зростає. Клієнти запитують зміни «від» курсора. |
| **Dexicon** | DEX-застосунок, книгу заявок якого переносить кейп DEX-ретранслятора. |
| **Конверт** (envelope) | Підписана JSON-заявка або повідомлення, прийняті DEX-ретранслятором. |
| **Шлюз** (gateway) | Проксі `POST /cmd` зі списком дозволених команд, яким телефони користуються для читання даних гаманця й трансляції транзакцій. |
| **Пульс** (heartbeat) | Рядок стану, який вузол записує в журнал кожні 30 с. |
| **IBD** | Initial blockchain download — початкове завантаження блокчейну, тобто перша синхронізація. Воно не заповнює MegaMMR. |
| **identity.txt** | Закріплена фраза, з якої виводяться ідентичність Maxima вузла й `MAX#` облікового запису. Секрет рівня гаманця. |
| **keets / KeetChat** | Однорангова (peer-to-peer) система чату. Вузол з'єднується з нею через допоміжний процес Node.js. |
| **Довге опитування** (long-poll) | HTTP-запит, який сервер тримає відкритим (тут до 25 с), доки не з'являться новини. |
| **MAX#** | Постійна адреса облікового запису, якою можна ділитися. |
| **MegaMMR** | Режим Minima, у якому зберігаються докази для кожної монети, тож вузол може відповідати щодо будь-якої адреси. |
| **Меш-мережа** (mesh) | Мережа ретрансляторів для пересилання пошуків у каталозі й копіювання записів каталогу. |
| **MDS / MiniHUB** | Система мінідапів Minima та її інтерфейс-хаб. |
| **MxUSD** | Прив'язаний до долара токен Minima, ідентифікатор токена `0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90`. Платіжні функції облікового запису розпізнають його за цим ідентифікатором. |
| **Ідентичність Mx…** | Публічна ідентичність Maxima ретранслятора чи облікового запису. |
| **Панель** (panel) | Локальний вебінтерфейс і API облікового запису на `127.0.0.1:9587`. |
| **Хост пулу** (pool host) | Ретранслятор, який відповідає на відкриті запити до каталогу й бере участь у меш-мережі. |
| **REDIAL_MS / TTL_MS** | 10-хвилинний інтервал повторного підключення ретранслятора і 2-годинний термін дії перевірених вузлів. |
| **Кореневий вузол** (rootnode) | Вузол Minima, через який вузол уперше знаходить мережу. |
| **Спільний порт** (shared port) | Ретранслятор, що працює на P2P-порту Minima (`parlons.relay.port=shared`). |
| **STUN** | Невелика UDP-служба на порту ретранслятора, яка повідомляє клієнтам їхню публічну адресу. |
| **Хост орендарів** (tenants host) | Окрема служба `parlons-cloud`, яка запускає гостьові облікові записи поруч із вузлом. |
| **TLS-фронт** (TLS front) | Вебсервер (Caddy, nginx або Apache), який завершує HTTPS і переадресовує запити на loopback-порти. |
| **Сховище** (vault) | Команда й сховище Minima, де зберігається сід-фраза гаманця вузла. |
| **Перевірений вузол** (verified peer) | Ретранслятор, заявлену адресу якого підтверджено зворотним підключенням. |
