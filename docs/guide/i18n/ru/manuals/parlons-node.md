# Parlons Node — руководство оператора

Для Parlons Node **0.2.144** (тег выпуска `node-v0.2.144`), который несёт DEX-ретранслятор
**0.1.5**. В выпуске два jar: `parlons-node-0.2.144.jar` — обычный узел, и `parlons-node-fleet-0.2.144.jar` — тот же узел со встроенными средой Node.js и вспомогательным процессом keets, так что один jar ещё и запускает транспорт keets, публичный узел начальной загрузки HyperDHT и почтовый ящик keets ([§18](#18-the-keets-sidecar-keetchat-bridge)). Установщик одной командой выбирает fleet-jar всегда, когда машина его тянет.

Имена хостов в примерах (`node.example.com`, `peer1.example.net` и т. д.) —
условные. Замените их своими.

Для каждой задачи есть пронумерованная процедура **«Как …»** с точными командами. Команды рассчитаны на
сервер с Debian или Ubuntu, пути скрипта развёртывания (`/opt/maxima`, `/var/lib/parlons-node`),
служебного пользователя `maxima` и порты по умолчанию. Если у вас они другие, измените их.

Связанные руководства:

- [Руководство пользователя Parlons!](parlons.md): приложения и ретрансляторы, которыми они пользуются
  ([вкладка «Сеть»](parlons.md#19-the-network-tab-relays-and-helping-the-network)).
- [Руководство по порталу Parlons Cloud](parlons-cloud-portal.md): подключение телефона к аккаунту на
  этом узле и служба `parlons-tenants`.

---

## Содержание

1. [Что такое Parlons Node](#1-what-a-parlons-node-is)
2. [Архитектура](#2-architecture)
3. [Требования](#3-requirements)
4. [Загрузка и проверка jar-файла](#4-download-and-verify-the-jar)
5. [Три способа установки](#5-three-ways-to-install)
6. [Первый запуск](#6-first-start)
7. [Юнит systemd (с комментариями)](#7-the-systemd-unit-annotated)
8. [Справочник по настройкам: все свойства](#8-configuration-reference-every-property)
9. [Порты](#9-ports)
10. [Структура каталога данных](#10-data-directory-layout)
11. [TLS-прокси: Caddy, nginx, Apache](#11-tls-fronting-caddy-nginx-apache)
12. [Кейп ретранслятора и mesh-сеть](#12-the-relay-cape-and-the-mesh)
13. [Режим MegaMMR и корневой узел (rootnode)](#13-megammr-mode-and-the-rootnode)
14. [Шлюз кошелька и его токен](#14-the-wallet-gateway-and-its-token)
15. [Хостинг NFT и `parlons.node.public`](#15-nft-hosting-and-parlonsnodepublic)
16. [DEX-кейп ретранслятора (Dexicon)](#16-the-dex-relay-cape-dexicon)
17. [Аккаунт, подключение устройств и локальная панель](#17-the-account-pairing-and-the-local-panel)
18. [Вспомогательный процесс keets (мост KeetChat)](#18-the-keets-sidecar-keetchat-bridge)
19. [MDS (MiniHUB) на узле](#19-mds-minihub-on-the-node)
20. [Сид-фразы, личность и резервные копии](#20-seeds-identity-and-backups)
21. [Административный RPC](#21-the-admin-rpc)
22. [Обновление и откат](#22-upgrading-and-rolling-back)
23. [Проверка извне](#23-verifying-from-outside)
24. [Мониторинг и журналы](#24-monitoring-and-logs)
25. [Заметки о файрволе](#25-firewall-notes)
26. [Устранение неполадок](#26-troubleshooting)
27. [Чек-лист безопасности](#27-security-checklist)
28. [Настольные приложения, в которые встроен этот jar](#28-desktop-apps-that-embed-this-jar)
29. [Глоссарий](#29-glossary)

---

## 1. Что такое Parlons Node

Parlons Node — это один Java-процесс, который содержит:

- **Полный узел Minima.** Это форк `minima-core` (`org.minima`), упакованный внутрь jar-файла как
  `node/libs/minima-node.jar`. Он проверяет цепочку, общается с пирами по P2P и хранит кошелёк.
- **Ретранслятор Maxima, который называется «кейп» (cape).** Это тот же самостоятельно написанный код ретранслятора, что и в
  `maxima-server.jar` (`:core` + `:server`). Телефоны и другие клиенты сами подключаются к нему. Он также участвует
  в mesh-сети ретрансляторов.
- **Шлюз кошелька.** Это защищённый прокси `POST /cmd` со списком разрешённых команд, через который телефоны могут использовать
  MegaMMR этого узла, чтобы читать балансы и рассылать в сеть транзакции, которые они подписали сами.
- **Хостинг NFT.** Узел раздаёт файлы изображений, на которые ссылаются токены, по адресу `/nft/...` на порту шлюза.
- **Аккаунт Parlons.** Это тот же `ParlonsCore`, который работает в `parlons-cloud.jar`: подключение устройств,
  чат, контакты, push-уведомления и локальная веб-панель. Его кошелёк — это собственный кошелёк узла.
- **DEX-кейп ретранслятора Dexicon** (необязательно). Это небольшая HTTP-служба, которая рассылает подписанные
  конверты книги заявок. Она работает внутри того же процесса.
- **Чат-мост keets** (необязательно). Он соединяет чат аккаунта с KeetChat, когда встроенный
  узел запускает вспомогательный процесс keets.

**Весь узел управляется одной сид-фразой.** При первом запуске личность ретранслятора Maxima и аккаунта
выводится из сид-фразы кошелька узла, а затем закрепляется в `identity.txt`
(см. [§20](#20-seeds-identity-and-backups)).

Кому это нужно? Большинству людей — нет. Parlons Cloud (`parlons-cloud.jar`) держит постоянно работающий аккаунт
примерно на 1 ГБ оперативной памяти. Запускайте Parlons Node, если хотите хранить полную копию цепочки, обслуживать
сеть ретрансляторов и предоставить телефонам шлюз кошелька.

---

## 2. Архитектура

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

| Компонент | Код | Слушает (по умолчанию) | Состояние по умолчанию |
|---|---|---|---|
| Узел Minima | встроенный `node/libs/minima-node.jar` | TCP `9001` (`parlons.node.port`) | включён |
| Административный RPC | `node/.../AdminRpc.java` | `127.0.0.1:<p2p+4>`, всегда loopback | выключен (`parlons.node.rpc=false`) |
| MDS / MiniHUB | MDS из форка Minima | `127.0.0.1:<p2p+2>` (файлы), `<p2p+3>` (команды) | выключен |
| Кейп ретранслятора | `server/.../RelayRuntime`, `RelayServer` | TCP `9501`, плюс UDP STUN на том же порту | включён |
| Шлюз кошелька и `/nft` | `node/.../NodeGateway.java`, `NftStore.java` | `127.0.0.1:9585` | включён (запускается, как только готов кошелёк) |
| Панель аккаунта | `account/.../ParlonsLocal.java` | `127.0.0.1:9587` | включена |
| DEX-кейп ретранслятора | `dexrelay/.../DexRelay.java` | `127.0.0.1:<port>`, если задан `parlons.dex.public`, иначе `0.0.0.0:<port>`; явно заданный `parlons.dex.bind` имеет приоритет | выключен (`parlons.dex.port=0`) |
| Мост keets | `node/.../KeetsChatBridge.java` | нет (опрашивает узел) | включён, только когда узел запущен с `-keets` |

**Порядок запуска** (из `ParlonsNodeMain.main`):

1. Применяются собственные флаги Minima (`parlons.node.args` и `parlons.node.conf`), затем
   свойства `parlons.node.*`.
2. Запускается встроенный узел Minima.
3. Если включён, запускается административный RPC. Если он не может занять порт, процесс завершается с кодом 2.
4. Если включён, запускается DEX-кейп. Он не зависит от цепочки, поэтому работает ещё до синхронизации узла.
5. Фоновый поток ждёт, пока хранилище (vault) узла станет доступно для чтения (до 120 с). Затем он выводит
   личность и запускает кейп ретранслятора, шлюз и хранилище NFT, а затем аккаунт — именно в таком порядке.
6. Каждые 30 с в журнал пишется строка heartbeat.

---

## 3. Требования

### Java

- **Java 11 или новее.** Jar-файл скомпилирован в байт-код Java 11 (`node/build.gradle`).
- **Рекомендуется Java 21 или новее.** На JDK 21+ кейп ретранслятора использует виртуальные потоки, а его
  лимит подключений по умолчанию вырастает с 512 до 4096 (`RelayServer.DEFAULT_MAX_CONNECTIONS_VIRTUAL`).
- **Должен присутствовать модуль `jdk.httpserver`.** Шлюз, административный RPC, панель и
  DEX-кейп используют `com.sun.net.httpserver`. В полной JRE этот модуль есть. Если вы собираете урезанную
  среду выполнения с помощью jlink, добавьте его:
  `--add-modules java.se,jdk.unsupported,jdk.httpserver`. Без него административный RPC падает с
  `NoClassDefFoundError`, и узел отказывается работать.
- Если `java` не найдена, скрипт развёртывания устанавливает `default-jre-headless` в Debian/Ubuntu.

### Оперативная память и куча

| Роль | ОЗУ на сервере | Куча Java (`-Xmx`) |
|---|---|---|
| Узел с MegaMMR (по умолчанию; нужен, чтобы быть шлюзом кошелька) | 8 ГБ | минимум 3 ГБ |
| Узел без MegaMMR (`-Dparlons.node.megammr=false`) | 2 ГБ | 768 МБ |
| Fleet-jar (любой из вариантов выше + вспомогательный процесс keets: Node.js, DHT, почтовый ящик) | + 1 ГБ, минимум 2 ГБ; только x86_64 или aarch64 | как выше (`MemoryMax` + 1 ГБ) |

Эти цифры взяты из заметок по парку серверов в `cloud/NODE-SETUP.md`. Они получены на горьком опыте: узлы MegaMMR с кучей 2 ГБ и 2,5 ГБ зацикливались на ошибках нехватки памяти,
а один с 3 ГБ упал.

- **Всегда задавайте `-Xmx`.** Неограниченная куча по умолчанию — верный способ потерять небольшой VPS.
- **Задайте в systemd `MemoryMax` больше размера кучи.** Скрипт развёртывания использует размер кучи + 512 МБ. Если
  `MemoryMax` равен `-Xmx`, cgroup убивает JVM, как только растёт её память вне кучи.
- Узел, который падает и перезапускается по кругу, срабатывает на `StartLimitBurst` и остаётся выключенным. После любого изменения кучи проверяйте
  `systemctl is-active parlons-node`.

### Диск

Занятое место на диске растёт вместе с цепочкой. Фиксированной цифры в коде нет. Заложите также место под:

- загрузку снимка MegaMMR (около 460 МБ по данным `cloud/NODE-SETUP.md`), пока вы его импортируете;
- хранилище медиаблобов ретранслятора, если вы его включите (`parlons.relay.blob`, в МБ);
- размещённые файлы NFT (не более 32 МБ каждый).

### Сеть

- Публичный IPv4-адрес или проброс порта на роутере для P2P-порта Minima и порта ретранслятора.
- Исходящий доступ в интернет, чтобы связываться с пирами Minima, ретрансляторами mesh-сети и архивным узлом.
- Доменное имя с TLS, если вы хотите публично раздавать шлюз, файлы NFT или DEX-кейп.

### Как проверить сервер перед установкой

1. Проверьте версию Java. Она должна быть 11 или новее; лучше 21 или новее:

   ```bash
   java -version
   ```

2. Проверьте, что модуль `jdk.httpserver` присутствует. Команда должна вывести одну строку:

   ```bash
   java --list-modules | grep jdk.httpserver
   ```

3. Проверьте объём ОЗУ. Для узла MegaMMR нужно 8 ГБ, без MegaMMR — 2 ГБ:

   ```bash
   free -h
   ```

4. Проверьте свободное место на диске там, где будет каталог данных:

   ```bash
   df -h /var/lib
   ```

5. Проверьте, что нужные узлу порты ничем не заняты:

   ```bash
   ss -ltnp | grep -E ':(9001|9005|9501|9585|9587|9602) '
   ```

   Команда не должна ничего вывести. Если порт 9001 занят обычным узлом Minima, запланируйте перенос P2P-порта узла
   ([§9](#9-ports)). Если порт 9501 занят `maxima-relay.service`, запланируйте его замену ([§5](#5-three-ways-to-install)).

---

## 4. Загрузка и проверка jar-файла

Релизы публикуются на GitHub в `eurobuddha/maxima` под тегами `node-v<version>`. Они публикуются
с `--latest=false`, поэтому значок «Latest» репозитория **не** указывает на узел. Всегда
выбирайте релиз узла по его тегу.

1. Скачайте jar-файл и файл контрольных сумм:

   ```bash
   VER=0.2.144
   curl -fLO https://github.com/eurobuddha/maxima/releases/download/node-v$VER/parlons-node-$VER.jar
   curl -fLO https://github.com/eurobuddha/maxima/releases/download/node-v$VER/SHA256SUMS
   ```

   Для fleet-jar (с транспортом keets, узлом начальной загрузки DHT и почтовым ящиком) скачайте из
   того же выпуска `parlons-node-fleet-$VER.jar`. Его SHA-256:
   `3dfd655e2c2e3541f471252b3ddbd058c9040ba5814b2bbbbba40674771bf166`.

2. Проверьте хеш SHA-256. Хеш `parlons-node-0.2.144.jar`:

   ```
   c5b114cc13294bdeee95d9872fc6710cd99c92472ffb63c1581081343663a6f5
   ```

   ```bash
   echo "c5b114cc13294bdeee95d9872fc6710cd99c92472ffb63c1581081343663a6f5  parlons-node-0.2.144.jar" | sha256sum -c -
   # macOS: shasum -a 256 -c  (same input)
   ```

   Команда должна вывести `parlons-node-0.2.144.jar: OK`. Если это не так, удалите файл и не запускайте его.

3. Проверьте версию. Это не запускает узел:

   ```bash
   java -jar parlons-node-0.2.144.jar --version
   # parlons-node 0.2.144
   ```

4. Выведите встроенную справку со списком основных свойств. Это тоже не запускает узел:

   ```bash
   java -jar parlons-node-0.2.144.jar --help
   ```

### Как вместо этого собрать jar-файл из исходного кода

1. Клонируйте репозиторий и соберите fat jar:

   ```bash
   git clone https://github.com/eurobuddha/maxima.git
   cd maxima
   ./gradlew :node:fatJar
   ```

2. Найдите jar-файл. Это `node/build/libs/parlons-node-<version>.jar`:

   ```bash
   ls node/build/libs/
   ```

3. Перед отправкой убедитесь, что jar-файл содержит ваше изменение. Уже случалось, что устаревший `core.jar` выходил
   под новым номером версии. Посчитайте вхождения символа, который вы добавили в изменённый класс; результат
   должен быть 1 или больше:

   ```bash
   unzip -p node/build/libs/parlons-node-<version>.jar <class path> | grep -ac <symbol>
   ```

4. Запишите его контрольную сумму, чтобы ещё раз проверить её на сервере перед запуском:

   ```bash
   sha256sum node/build/libs/parlons-node-<version>.jar
   ```

> Jar-файл **не принимает параметров командной строки**. Он понимает только аргументы `--version`/`-v`
> и `--help`/`-h`/`help`. Любой другой аргумент выводит справку в stderr и завершает работу с кодом 2.
> Каждая настройка — это системное свойство `-D`, указанное **перед** `-jar`.

---

## 5. Три способа установки

### A. Установщик одной командой (начните с него)

На самой Linux-машине (VPS, Raspberry Pi 4/5 с 64-битной ОС, домашний сервер), от root или пользователя с `sudo`:

```bash
curl -fsSL {{NODE_INSTALL}} | bash
```

Одна команда даёт сети полноценную точку присутствия. По умолчанию машина запускает в одном процессе:

- **полный узел Minima** в роли сервера (P2P 9001/tcp), с MegaMMR при 3,5 ГБ ОЗУ и больше;
- **ретранслятор Parlons** (кейп Maxima, 9501/tcp+udp) и ваш постоянно доступный **аккаунт Parlons**;
- **транспорт keets**, публичный **узел начальной загрузки HyperDHT** (49737/udp) и **почтовый ящик keets** — из среды Node.js и вспомогательного процесса, встроенных в fleet-jar;
- **DEX-кейп ретранслятора** (книга ордеров Dexicon) и **шлюз кошелька**, которые Caddy отдаёт по HTTPS на `https://<имя>/dex-relay` и `https://<имя>/parlons-node`. Имя — то, что вы передали через `--host`; без него машина использует `<a-b-c-d>.sslip.io`, бесплатное имя, которое разрешается в её собственный публичный адрес, так что регистрировать ничего не нужно.

Что он делает, по порядку:

1. Ставит Java 11+ и `qrencode` (apt, dnf, apk или pacman), если их нет, и Caddy для HTTPS-фронта (если порты 80/443 уже занимает другой веб-сервер, он сообщает, какие два пути проксировать самостоятельно, [§11](#11-tls-fronting-caddy-nginx-apache)).
2. Находит на GitHub новейший выпуск `node-v…`, скачивает `parlons-node-fleet-<ver>.jar` и отвергает его, если sha256 не совпадает с `SHA256SUMS` выпуска.
3. Создаёт пользователя `maxima` и `/var/lib/parlons-node` (права 700), ставит `/usr/local/bin/parlons-pair` и записывает защищённый `parlons-node.service` ([§7](#7-the-systemd-unit-annotated)) с `-Dparlons.fleet=true`, `-Dparlons.public.host=<ваш публичный IPv4>`, `-Dparlons.dex.public=https://<имя>/dex-relay` и `-Dparlons.node.public=https://<имя>/parlons-node`.
4. Подбирает узел под машину: при 3,5 ГБ ОЗУ и больше — MegaMMR с кучей 2 ГБ; меньше — обычный полный узел с кучей 768 МБ. `MemoryMax` — куча плюс 1,5 ГБ на вспомогательный процесс. Синхронизация с `65.109.31.226:9001`.
5. Открывает 9001/tcp, 9501/tcp+udp, 49737/udp и 80 + 443 в ufw или firewalld, если один из них активен.
6. Запускает узел, ждёт адрес аккаунта и печатает его полностью.
7. Просит задать пароль администратора для сопряжения (только при первой установке), затем ввести его ещё раз и рисует QR-код, который телефон сканирует в Parlons Cloud.
8. Печатает публичный ключ keets машины, адрес её узла начальной загрузки DHT и ключ почтового ящика вместе с командой, которой сопровождающий списка подписывает ящик в опубликованный список.

Полному набору нужны процессор x86_64 или aarch64, 2 ГБ ОЗУ и публичный IPv4-адрес. Если чего-то нет, установщик объясняет почему и откатывается к обычному jar (`parlons-node-<ver>.jar`: узел Minima, ретранслятор Parlons и аккаунт, без keets и без DEX), которому хватает 1 ГБ на любом процессоре.

Параметры (ставьте их после `bash -s --`):

| Параметр | Действие |
|---|---|
| `--host node.example.com` | Отдавать DEX-ретранслятор и шлюз кошелька под вашим именем вместо sslip.io. Сначала направьте A-запись имени на машину; Caddy сам получит сертификат. |
| `--public-host 1.2.3.4` | Публичный IPv4, который объявляют DHT и ретранслятор. По умолчанию — глобальный адрес машины. За домашним роутером укажите свой публичный адрес и пробросьте порты. |
| `--basic` | Намеренно обычный jar: без транспорта keets, DHT, почтового ящика и DEX. |
| `--fleet` | Настаивать на полном jar; завершиться ошибкой вместо отката. |
| `--update` | Скачать новейший выпуск и перезапустить. Повторный запуск команды установки делает то же самое. |
| `--uninstall` | Остановить и удалить службу и `parlons-pair`. `/var/lib/parlons-node` (личность, кошелёк, чаты) сохраняется. |
| `--no-megammr` | Обычный полный узел без MegaMMR (хватит 1 ГБ ОЗУ). Выбирается автоматически на машинах с менее чем 3,5 ГБ. |
| `--heap <размер>` | Другая куча Java, например `--heap 3g`. |

Например, со своим доменом: `curl -fsSL {{NODE_INSTALL}} | bash -s -- --host node.example.com`

Установщик не задаёт пиров сетки ретрансляторов (используется встроенный список начальной загрузки) и полку blob. Для этого возьмите скрипт развёртывания (B) или отредактируйте юнит ([§7](#7-the-systemd-unit-annotated)). На Mac или Windows установите Parlons Desktop. Если машина вовсе не тянет узел, аккаунт без блокчейна — `get-parlons-cloud.sh` — работает в 1 ГБ ([руководство Parlons Cloud Portal](parlons-cloud-portal.md)).

#### Как установить с помощью установщика одной командой

1. Откройте 9001/tcp, 9501/tcp и 9501/udp, 49737/udp, а также 80 + 443/tcp в файрволе облачного провайдера или пробросьте их на домашнем роутере. Аккаунт работает и без них; ретрансляция для других, раздача блоков, DHT и DEX-ретранслятор требуют их.
2. Если у вас есть домен, направьте A-запись на машину сейчас, чтобы Caddy получил сертификат в конце установки. Иначе пропустите: имени sslip.io ничего не нужно.
3. Выполните команду выше в SSH-сессии или терминале машины. Не запускайте её из скрипта без терминала: тогда шаги с паролем и QR будут пропущены.
4. По запросу дважды введите пароль администратора для сопряжения. Сохраните его: он нужен для каждого нового приглашения ваших устройств.
5. По повторному запросу введите пароль один раз. Появятся QR-код и полное приглашение.
6. В Parlons Cloud на телефоне нажмите **Scan pairing QR** и отсканируйте его.
7. Прочтите последние строки. Они называют URL DEX-ретранслятора и шлюза кошелька и печатают публичный ключ keets машины, адрес начальной загрузки DHT и ключ почтового ящика. Отправьте строку с ящиком сопровождающему списка; пока ящик не подписан в опубликованный список, машина ретранслирует и выполняет начальную загрузку, но не хранит почту для телефонов офлайн.
8. Следите за синхронизацией цепочки: `journalctl -u parlons-node -f | grep heartbeat`.
9. Проверьте DEX-ретранслятор с другой машины: `curl -s https://<имя>/dex-relay/dex/health` должен ответить. После первого запуска сертификат может появиться через минуту.
10. Сделайте резервную копию сид-фразы ([§20](#20-seeds-identity-and-backups)).

Если шаг 4 или 5 был пропущен, выполните один раз `sudo parlons-pair --set-admin-password`, затем `sudo parlons-pair --admin`.

### B. Скрипт развёртывания (со своего компьютера)

Запускайте его из клона репозитория на своём компьютере, указав чистый Linux-сервер,
к которому вы можете подключиться по SSH как root. Повторный запуск обновляет узел: установка и обновление —
одна и та же операция.

#### Как установить с помощью скрипта развёртывания

1. На своём компьютере клонируйте репозиторий. В нём находятся `ops/deploy-parlons-node.sh` и
   `ops/parlons-pair`:

   ```bash
   git clone https://github.com/eurobuddha/maxima.git
   cd maxima
   ```

2. Положите проверенный jar-файл в `dist/` ([§4](#4-download-and-verify-the-jar)). Без `--jar`
   скрипт берёт самый новый `dist/parlons-node-*.jar`:

   ```bash
   mkdir -p dist
   mv ~/Downloads/parlons-node-0.2.144.jar dist/
   ```

3. Убедитесь, что вход по SSH-ключу от имени root работает. Скрипт использует `BatchMode=yes`, поэтому не может ответить
   на запрос пароля:

   ```bash
   ssh -o BatchMode=yes root@node.example.com true && echo ssh-ok
   ```

4. Если на сервере уже работает `maxima-relay.service` на порту 9501, добавьте `--replace-relay` к следующей
   команде.
5. Запустите скрипт. Ниже — типичный узел MegaMMR с TLS-прокси; все флаги перечислены в таблице ниже:

   ```bash
   ops/deploy-parlons-node.sh root@node.example.com \
       --jar dist/parlons-node-0.2.144.jar \
       --heap 3g \
       --rootnode seed-peer.example.net:9001 \
       --rpc \
       --megammr-seed https://eurobuddha.com/mega.mmr \
       --public https://node.example.com/parlons-node \
       --dex 9602 --dex-public https://node.example.com/dex-relay \
       --peers "peer1.example.net:9501,peer2.example.net:9501"
   ```

6. Прочитайте итоговую сводку в конце. В ней выводятся адрес кошелька аккаунта, личность Maxima
   (`Mx…`) и bearer-токен шлюза. Храните токен в секрете
   ([§14](#14-the-wallet-gateway-and-its-token)).
7. Настройте TLS-прокси ([§11](#11-tls-fronting-caddy-nginx-apache)).
8. Подключите телефон ([§17](#17-the-account-pairing-and-the-local-panel)) и сделайте резервную копию сид-фразы
   ([§20](#20-seeds-identity-and-backups)).
9. Проверьте узел с другой машины ([§23](#23-verifying-from-outside)).

#### Что делает скрипт

Скрипт выполняет эти шаги по порядку:

1. Устанавливает JRE без графики, если `java` нет, и пытается установить `qrencode` (для QR-кодов подключения).
2. Создаёт непривилегированного системного пользователя `maxima` и каталог `/var/lib/parlons-node` с правами 700.
3. Загружает jar-файл в `/opt/maxima/parlons-node-<ver>.jar` и проверяет его SHA-256. Только после этого он
   направляет символическую ссылку `/opt/maxima/parlons-node.jar` на новый jar-файл.
4. Устанавливает `/usr/local/bin/parlons-pair`.
5. Записывает защищённый юнит `/etc/systemd/system/parlons-node.service`.
6. Открывает порты P2P и ретранслятора в ufw или firewalld. Порт DEX он открывает, только если `--dex`
   указан без `--dex-public`, а порт шлюза — только с `--gateway-public`.
7. Запускает узел и проверяет, что порт ретранслятора слушает.
8. Необязательно: `--seed-from` принимает существующую фразу из 24 слов ([§20](#20-seeds-identity-and-backups)).
9. Необязательно: `--megammr-seed` импортирует снимок MegaMMR ([§13](#13-megammr-mode-and-the-rootnode)).
10. Выводит адрес кошелька аккаунта, личность Maxima, токен шлюза и инструкции по подключению устройств.

| Флаг скрипта | Превращается в | По умолчанию |
|---|---|---|
| `--jar FILE` | jar-файл для развёртывания | самый новый `dist/parlons-node-*.jar` |
| `--heap 3g` | `-Xmx` | `2g` (мало для MegaMMR; укажите `3g` или больше) |
| `--memmax 3584M` | `MemoryMax` юнита | куча + 512M |
| `--p2p-port N` | `-Dparlons.node.port` | `9001` |
| `--relay-port N` | `-Dparlons.relay.port` | `9501` |
| `--gateway-port N` | `-Dparlons.gateway.port` | `9585` |
| `--gateway-public` | `-Dparlons.gateway.bind=0.0.0.0` и открывает порт | loopback |
| `--public URL` | `-Dparlons.node.public` | не задано |
| `--rootnode h:p` | `-Dparlons.node.rootnode` | не задано (узел не будет синхронизироваться) |
| `--no-megammr` | `-Dparlons.node.megammr=false` | MegaMMR включён |
| `--rpc` | `-Dparlons.node.rpc=true` | выключен |
| `--peers h:p,…` | `-Dparlons.relay.peers` | не задано (используется встроенный в код начальный список) |
| `--blobstore MB` | `-Dparlons.relay.blob` | `0` |
| `--dex PORT` | `-Dparlons.dex.port` | `0` (выключен) |
| `--dex-public URL` | `-Dparlons.dex.bind=127.0.0.1 -Dparlons.dex.public=URL` | не задано |
| `--passphrase-file F` | `-Dparlons.node.passphrase.file` | не задано |
| `--node-args "…"` | `-Dparlons.node.args` | пусто |
| `--keets` | добавляет `-keets -keetssidecar /opt/maxima/keets-sidecar/index.js` к аргументам узла и загружает вспомогательный процесс | выключен |
| `--seed-from F --archive h:p` | однократная пересинхронизация кошелька на фразу из F (нужен `--rpc`), затем закрепляет её в `identity.txt` | — (`--archive` по умолчанию указывает на архивный узел, прописанный в скрипте) |
| `--megammr-seed URL` | однократный импорт MegaMMR (нужен `--rpc`) | — |
| `--replace-relay` | останавливает и отключает `maxima-relay.service`, чтобы узел мог занять его порт | — |

> **Всегда указывайте `--dex-public` вместе с `--dex`.** С ним скрипт задаёт
> `parlons.dex.bind=127.0.0.1` и держит порт закрытым. Без него DEX-кейп слушает `0.0.0.0`,
> и скрипт открывает порт в интернет как обычный HTTP.

Скрипт записывает в юнит `Restart=always`, поэтому узел возвращается и после штатного завершения
(`quit`, импорт MegaMMR, пересинхронизация).

### C. Вручную

Минимальная команда:

```bash
java -Xmx3g -Dparlons.node.data=/var/lib/parlons-node -jar parlons-node.jar
```

У узла, запущенного так, нет корневого узла (rootnode), поэтому он не будет синхронизироваться.

#### Как установить вручную

Эти шаги делают на сервере то же, что и скрипт развёртывания. Выполняйте их от имени root.

1. Установите Java, если её нет, и `qrencode`, если вам нужны QR-коды подключения:

   ```bash
   apt-get update
   apt-get install -y default-jre-headless qrencode
   ```

2. Создайте служебного пользователя и каталоги:

   ```bash
   id maxima >/dev/null 2>&1 || useradd --system --home /var/lib/maxima --shell /usr/sbin/nologin maxima
   mkdir -p /opt/maxima /var/lib/parlons-node
   chown -R maxima:maxima /var/lib/parlons-node
   chmod 700 /var/lib/parlons-node
   ```

3. Скопируйте проверенный jar-файл в `/opt/maxima` и направьте на него символическую ссылку:

   ```bash
   cp parlons-node-0.2.144.jar /opt/maxima/
   cd /opt/maxima
   echo "c5b114cc13294bdeee95d9872fc6710cd99c92472ffb63c1581081343663a6f5  parlons-node-0.2.144.jar" | sha256sum -c -
   chmod 644 parlons-node-0.2.144.jar
   ln -sfn parlons-node-0.2.144.jar parlons-node.jar
   ```

4. Необязательно: установите помощник подключения из клона репозитория:

   ```bash
   install -m 755 ops/parlons-pair /usr/local/bin/parlons-pair
   ```

5. Создайте файл юнита `/etc/systemd/system/parlons-node.service` по примеру из
   [§7](#7-the-systemd-unit-annotated). Измените имена хостов, размер кучи и `MemoryMax` под
   свой сервер.
6. Откройте порты P2P и ретранслятора ([§25](#25-firewall-notes)):

   ```bash
   ufw allow 9001/tcp
   ufw allow 9501/tcp
   ```

7. Запустите узел, затем следуйте [§6](#6-first-start):

   ```bash
   systemctl daemon-reload
   systemctl enable --now parlons-node
   ```

---

## 6. Первый запуск

1. Запустите службу (установщик одной командой и скрипт развёртывания уже сделали это):

   ```bash
   systemctl enable --now parlons-node
   systemctl is-active parlons-node
   ```

2. Следите за журналом:

   ```bash
   journalctl -u parlons-node -f
   ```

3. Найдите строку запуска. В ней указаны версия, каталог данных и порты:

   ```
   [parlons-node] Parlons Node 0.2.144 — booting embedded Minima node <ver> at /var/lib/parlons-node/<base> (p2p 9001, megammr true, admin rpc 127.0.0.1:9005 (loopback-bound))
   ```

4. Следите за синхронизацией цепочки. Каждые 30 с строка heartbeat выводит высоту блока:

   ```
   [parlons-node] heartbeat: node block=<n> chainlen=<n> | cape=up:9501 | gateway=up:9585 | account=up:<n> hosts, <n> devices
   ```

   Первая синхронизация закончена, когда `block=` достигает текущей вершины цепочки. Фиксированного времени
   для этого нет: оно зависит от сервера и соединения.
5. Когда кошелёк готов, появляются такие строки:
   - `identity: pinned from the vault into identity.txt` (только при первом запуске);
   - `Maxima cape up on port 9501 — identity Mx…`;
   - `account wallet = node wallet: 0x… (Mx…)`;
   - `wallet gateway up on 127.0.0.1:9585/cmd (… bearer token in /var/lib/parlons-node/gateway-token.txt)`;
   - `account up: attached to N relay(s)…`.
6. На узле MegaMMR один раз импортируйте MegaMMR ([§13](#13-megammr-mode-and-the-rootnode)). Пока
   вы этого не сделаете, балансы существующих адресов будут возвращаться пустыми.
7. Подключите телефон ([§17](#17-the-account-pairing-and-the-local-panel)):

   ```bash
   sudo parlons-pair --set-admin-password
   sudo parlons-pair --admin
   ```

8. Сделайте резервную копию сид-фразы ([§20](#20-seeds-identity-and-backups)).

---

## 7. Юнит systemd (с комментариями)

Этот юнит повторяет тот, который записывает `ops/deploy-parlons-node.sh`, с флагами, которые парк серверов использует
в октябре 2026 года: узел MegaMMR, ретранслятор на 9501, шлюз и DEX-кейп на loopback за TLS и
явно заданный начальный список mesh-сети.

systemd не допускает комментариев в конце строки или внутри продолженной строки `ExecStart`, поэтому
пояснения находятся в блоке комментариев над `ExecStart`.

```ini
# /etc/systemd/system/parlons-node.service
[Unit]
Description=Parlons Node - full Minima node + Maxima cape + wallet gateway (0.2.144)
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

Примечания:

- Всё свойство `-Dparlons.node.args=…` взято в кавычки, потому что это один аргумент JVM и
  оно может содержать пробелы. systemd учитывает двойные кавычки. Не ставьте двойные кавычки внутри значения.
- Если вы передаёте Minima `-data <dir>` в `parlons.node.args`, добавьте этот каталог в `ReadWritePaths`.
- Для узла, защищённого паролем, добавьте `-Dparlons.node.passphrase.file=/var/lib/parlons-node/passphrase.txt`
  (владелец `maxima`, права 600) или задайте `PARLONS_NODE_PASSPHRASE` в `EnvironmentFile=` с правами 600.
- Перед каждым изменением сохраняйте резервную копию юнита. По соглашению она называется
  `parlons-node.service.before-<what>`.

### Как изменить юнит

1. Сохраните резервную копию текущего юнита:

   ```bash
   cp /etc/systemd/system/parlons-node.service /etc/systemd/system/parlons-node.service.before-<what>
   ```

2. Отредактируйте его:

   ```bash
   nano /etc/systemd/system/parlons-node.service
   ```

3. Проверьте юнит на ошибки:

   ```bash
   systemd-analyze verify /etc/systemd/system/parlons-node.service
   ```

4. Загрузите его и перезапустите узел:

   ```bash
   systemctl daemon-reload
   systemctl restart parlons-node
   ```

5. Подождите около 25 с, затем убедитесь, что узел работает, и прочитайте строку запуска:

   ```bash
   systemctl is-active parlons-node
   journalctl -u parlons-node --since -40s --no-pager | grep -E 'Parlons Node|minima flags|REFUSING' | grep -v seed
   ```

6. Если узел не поднимается, верните резервную копию и повторите шаг 4:

   ```bash
   cp /etc/systemd/system/parlons-node.service.before-<what> /etc/systemd/system/parlons-node.service
   ```

### Как перевести старый юнит на `Restart=always`

1. Посмотрите, что указано в юните:

   ```bash
   grep -n '^Restart=' /etc/systemd/system/parlons-node.service
   ```

2. Если выводится `Restart=on-failure`, измените это:

   ```bash
   cp /etc/systemd/system/parlons-node.service /etc/systemd/system/parlons-node.service.before-restart-always
   sed -i 's/^Restart=on-failure$/Restart=always/' /etc/systemd/system/parlons-node.service
   systemctl daemon-reload
   ```

   Новая настройка вступает в силу сразу, перезапуск не нужен.

---

## 8. Справочник по настройкам: все свойства

Все свойства — это системные свойства JVM (`-Dname=value`), указываемые перед `-jar`. Этот список составлен
поиском (grep) всех вызовов `System.getProperty`, `Integer.getInteger`, `Long.getLong` и `getenv`
в модулях, упакованных в jar-файл.

### Как изменить настройку

1. Найдите свойство в таблицах ниже.
2. Добавьте или измените его строку `-D…` в `ExecStart` юнита перед `-jar` и перезапустите
   узел ([§7, «Как изменить юнит»](#how-to-change-the-unit)). Если вы развёртывали узел скриптом,
   можно вместо этого запустить скрипт повторно с соответствующим флагом ([§5](#5-three-ways-to-install)); он
   перезаписывает юнит по своим флагам.
3. Убедитесь, что узел применил настройку. Строка запуска показывает порты, а строка `minima flags:` —
   собственные флаги Minima:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'Parlons Node|minima flags' | grep -v seed
   ```

### Как передать собственные флаги Minima в conf-файле

Используйте этот способ для секретов, например `mdspassword`, чтобы они никогда не появлялись в командной строке.

1. Запишите файл, по одной паре `key=value` в строке. Строка из одного `key` — логический флаг. Ведущий `-`
   не пишите:

   ```bash
   cat > /var/lib/parlons-node/minima.conf <<'EOF'
   mdspassword=<your MDS password>
   EOF
   chown maxima:maxima /var/lib/parlons-node/minima.conf
   chmod 600 /var/lib/parlons-node/minima.conf
   ```

2. Добавьте `-Dparlons.node.conf=/var/lib/parlons-node/minima.conf` в юнит и перезапустите узел
   ([§7](#how-to-change-the-unit)).
3. Проверьте строку `minima flags:` в журнале. Значение `mdspassword` там скрыто.

### 8.1 Узел и цепочка

| Свойство | Значение | По умолчанию | Пример |
|---|---|---|---|
| `parlons.node.data` | Каталог данных. Приоритет: это свойство, затем `-data` Minima (его родительский каталог), затем `~/.parlons-node`. | `~/.parlons-node` | `/var/lib/parlons-node` |
| `parlons.node.port` | P2P-порт Minima (публичный). Производные порты идут от него: файлы MDS `+2`, команды MDS `+3`, административный RPC `+4`. | `9001` (значение Minima по умолчанию, если в аргументах нет `-port`) | `9101` |
| `parlons.node.rootnode` | Пир Minima (`host:port`), через который узел находит цепочку. В этом форке список пиров по умолчанию **пустой**, поэтому без этого свойства (или `connect`) узел запускается, но никогда не синхронизируется. | пусто | `seed-peer.example.net:9001` |
| `parlons.node.connect` | Фиксированный список пиров. Если его задать, **поиск пиров по P2P отключается**: только статические пиры. | пусто | `a.example.net:9001,b.example.net:9001` |
| `parlons.node.megammr` | Хранить полный MegaMMR, чтобы `coins`/`balance megammr:true address:<any>` работали для любого адреса. Он нужен шлюзу кошелька. Приоритет: это свойство, затем `-megammr` Minima, затем `true`. | `true` | `false` |
| `parlons.node.rpc` | Запустить административный RPC на loopback `127.0.0.1:<p2p+4>`. Если он не может занять порт, узел завершается с кодом 2. | `false` | `true` |
| `parlons.node.args` | Собственные флаги запуска Minima, обрабатываемые собственным парсером Minima. Некоторые флаги отвергаются (см. 8.8). Резервная переменная окружения: `PARLONS_NODE_ARGS`. | пусто | `"-host 203.0.113.10 -archive"` |
| `parlons.node.conf` | Файл `key=value` с флагами Minima, который читается **до** `parlons.node.args`. Именно здесь место секретам вроде `mdspassword` — в файле с правами 600. Строка из одного `key` — логический флаг. | пусто | `/var/lib/parlons-node/minima.conf` |
| `parlons.node.passphrase.file` | Файл с парольной фразой узла, защищённого паролем. Узел пытается разблокироваться один раз при запуске. Альтернатива через окружение: `PARLONS_NODE_PASSPHRASE`. Парольная фраза не должна содержать пробельных символов, `"` или `;`. | пусто | `/var/lib/parlons-node/passphrase.txt` |
| `parlons.node.archive` | Архивный хост / хост MegaMMR, используемый при **пересинхронизации кошелька**, запрошенной с подключённого устройства (`megammrsync action:resync host:<this>`). | встроенный в код архивный узел проекта (см. `NodeAccountWallet.java`) | `archive.example.net:9001` |
| `parlons.node.selftest.send` | **Только для тестов разработчика.** При запуске отправляет `<address>[,<amount>]` с кошелька узла (сумма по умолчанию `0.001`). На узле со средствами **это отправляет реальные средства**. Никогда не задавайте его в рабочей среде. | пусто | — |

### 8.2 Кейп ретранслятора (Maxima)

| Свойство | Значение | По умолчанию | Пример |
|---|---|---|---|
| `parlons.relay.port` | Порт ретранслятора. Число даёт ретранслятору собственный слушающий порт. `0` выключает ретранслятор (тогда аккаунт использует ретрансляторы парка). `shared` заставляет ретранслятор делить P2P-порт Minima: узел передаёт ретранслятору каждое подключение, которое представляется как клиент Parlons, так что публичный порт один. | `9501` | `8001`, `0`, `shared` |
| `parlons.relay.host` | Публичное имя хоста или IP ретранслятора. Если не задано, узел использует публичный адрес, который он узнал от своих пиров, и продолжает его перепроверять (каждые 30 с в первые 30 минут, пока адрес не найден, затем каждые 10 минут, так что изменившийся динамический IP отслеживается). Частные, loopback- и CGNAT-адреса никогда не используются. | определяется автоматически | `node.example.com` |
| `parlons.relay.peers` | Начальный список mesh-сети (`host:port` через запятую). Если не задан, используется встроенный в код список `Bootstrap.RELAYS`. Аккаунт также использует их как пиров mesh-сети. | встроенный список | `peer1.example.net:9501,peer2.example.net:8001` |
| `parlons.relay.blob` | Размер хранилища медиаблобов в МБ (там хранятся фотографии пользователей). `0` = выключено. | `0` | `1024` |
| `parlons.relay.maxconn` | Максимальное число подключений, которые держит ретранслятор. `0` оставляет встроенное значение по умолчанию: 512 на платформенных потоках, 4096 на виртуальных потоках (JDK 21+). | `0` | `8192` |

Свойства уровня ретранслятора из `RelayServer` (они действуют и внутри узла):

| Свойство | Значение | По умолчанию |
|---|---|---|
| `maxima.relay.maxpersource` | Максимум одновременных подключений с одного IP-адреса источника (домохозяйство за NAT считается одним источником). | `32` |
| `maxima.relay.shed` | Мягкий целевой предел числа клиентов. Выше него ретранслятор за каждый такт просит до 4 клиентов перейти в другое место. `0` = никогда не просить. | `384` |
| `maxima.relay.replicate` | Сколько случайных проверенных пиров пула получают копию каждой подписанной записи каталога. `0` = выключено. | `3` |
| `maxima.relay.vthreads` | Использовать виртуальные потоки, если они есть в JDK. `false` принудительно включает платформенные потоки. | `true` |
| `maxima.pow.enforce` | Отклонять сообщения без минимального доказательства работы (proof-of-work), требуемого протоколом. При false они только подсчитываются. Окружение: `MAXIMA_POW_ENFORCE=true`. | `false` |
| `maxima.mls.open` | Значение по умолчанию для открытого разрешения в пуле. **На узел не влияет:** узел всегда включает режим пула. | — |

Зафиксировано в коде (не настраивается): протокол `1.0.48`; лимит скорости ретранслятора 600 сообщений в минуту на одного получателя.

Окружение: `MAXIMA_STUN=false` отключает UDP STUN-ответчик на порту ретранслятора.

### 8.3 Шлюз кошелька и NFT

| Свойство | Значение | По умолчанию | Пример |
|---|---|---|---|
| `parlons.gateway.port` | Порт шлюза кошелька (`/cmd`) и NFT-сервера (`/nft`). | `9585` | `9585` |
| `parlons.gateway.bind` | Интерфейс, на котором слушает шлюз. Держите его на loopback за TLS. | `127.0.0.1` | `127.0.0.1` |
| `parlons.gateway.token` | Bearer-токен. Если не задан, узел читает `<data>/gateway-token.txt` или создаёт там 256-битный токен (права 600). | из файла | — |
| `parlons.gateway.threads` | Потоки-обработчики. Каждый блокируется на одну команду узла. | `8` | `16` |
| `parlons.gateway.rate.global` | Общая частота запросов `/cmd` (запросов/с; всплеск = запас на 2 с). `0` = выключено. | `50` | `100` |
| `parlons.gateway.rate.perip` | Частота `/cmd` на один IP. Имеет смысл, только если шлюз открыт напрямую: за прокси каждый запрос приходит с 127.0.0.1. | `10` | `0` |
| `parlons.gateway.rate.nft.global` | Общая частота `/nft`. | `400` | — |
| `parlons.gateway.rate.nft.perip` | Частота `/nft` на один IP. | `40` | — |
| `parlons.node.public` | Публичный базовый URL TLS-прокси шлюза, **без** `/cmd`. Он задаёт URL файлов NFT. На узле MegaMMR с включённым ретранслятором он также объявляет шлюз телефонам в приветствии ретранслятора. | пусто | `https://node.example.com/parlons-node` |

### 8.4 DEX-кейп ретранслятора

| Свойство | Значение | По умолчанию | Пример |
|---|---|---|---|
| `parlons.dex.port` | Порт DEX-кейпа ретранслятора. `0` = выключено. | `0` | `9602` |
| `49737` (`parlons.keets.dhtport`, только fleet-jar) | UDP | узел начальной загрузки keets HyperDHT | все | **Публичный.** Откройте его в файрволе. |
| `p2p+5` (`9006`, с `-keets`) | TCP/HTTP | loopback-API keets (токен в `<data>/keets/apitoken`) | `127.0.0.1`, жёстко задано | Никогда не открывать наружу. |
| `parlons.dex.bind` | Интерфейс, на котором он слушает. Явно заданное значение имеет приоритет. Если не задано, кейп слушает `127.0.0.1`, когда задан `parlons.dex.public`, иначе `0.0.0.0`. Держите его на loopback и ставьте перед ним TLS-прокси. | `127.0.0.1` с `parlons.dex.public`, иначе `0.0.0.0` | `127.0.0.1` |
| `parlons.dex.public` | Публичный базовый URL, объявляемый в mesh-сети. Если не задан, узел объявляет `http://<public host>:<port>`, как только узнаёт свой публичный хост. | пусто | `https://node.example.com/dex-relay` |

Зафиксировано в коде: версия DEX-ретранслятора `0.1.5`; общая частота 200 запросов/с и частота на один IP 20 запросов/с
(что это означает за прокси, см. в [§16](#16-the-dex-relay-cape-dexicon)).

### 8.5 Аккаунт и панель

| Свойство | Значение | По умолчанию | Пример |
|---|---|---|---|
| `parlons.account` | Запускать аккаунт Parlons. `false` = узел только с ретранслятором и шлюзом. | `true` | `false` |
| `parlons.account.name` | Отображаемое имя (используется только при первом запуске). | пусто | `Example Node` |
| `parlons.account.relays` | Дополнительные ретрансляторы, к которым подключается аккаунт (csv). | пусто | `peer1.example.net:9501` |
| `parlons.account.builtin` | `false` = не брать начальные ретрансляторы из встроенного в код списка. Тогда аккаунт начинает с `account.relays` и запомненных им ретрансляторов. | `true` | `false` |
| `parlons.account.direct` | Порт прямого (Tier-2) слушателя аккаунта. `0` = выключено. | `0` | — |
| `parlons.panel.port` | Локальная веб-панель и JSON API, только на `127.0.0.1`. `0` = выключено. | `9587` | `0` |
| `parlons.hosted.dir` | Каталог размещённых (гостевых) аккаунтов. Окружение: `PARLONS_HOSTED_DIR`. Если не задан, узел включает управляемое размещение для работы с отдельной службой tenants. | не задано | `/var/lib/parlons-tenants` |
| `parlons.hosted.limit` | Максимальное число размещённых аккаунтов. | `10` | — |
| `parlons.restore` | Восстановить переносимый пакет аккаунта (`.pbk`) в **чистый** каталог данных и завершить работу. Для ввода парольной фразы нужна интерактивная консоль. | пусто | `/root/backup.pbk` |

### 8.6 MDS и keets

| Свойство | Значение | По умолчанию | Пример |
|---|---|---|---|
| `parlons.mds` | Раздавать MDS (MiniHUB и MiniDapps) по адресу `https://<bind>:<p2p+2>/`. То же, что `-mdsenable` в Minima. | выключено | `true` |
| `parlons.mds.bind` | Интерфейс, на котором слушает MDS. Приоритет: это свойство, затем `-mdsbind` Minima, затем `127.0.0.1`. `0.0.0.0` или `*` означает все интерфейсы. | `127.0.0.1` | `127.0.0.1` |
| `parlons.node.keetschat` | `off`/`false`/`0`/`no` передаёт входящие keets приложению-хосту, и мост не запускается. | `on` | `off` |

### 8.7 Переменные окружения

| Переменная | Значение |
|---|---|
| `PARLONS_NODE_ARGS` | Резервный вариант для `parlons.node.args`. |
| `PARLONS_NODE_PASSPHRASE` | Парольная фраза для заблокированного узла (используйте `EnvironmentFile` с правами 600). |
| `PARLONS_HOSTED_DIR` | Резервный вариант для `parlons.hosted.dir`. |
| `MAXIMA_STUN` | `false` отключает UDP STUN-ответчик. |
| `MAXIMA_POW_ENFORCE` | `true` включает обязательный минимум доказательства работы. |

### 8.8 Флаги Minima, которые узел отвергает

Флаги из `parlons.node.args` или `parlons.node.conf` передаются собственному парсеру Minima, **кроме**
перечисленных ниже. Узел выводит причину и завершается с кодом 2, прежде чем коснуться каталога данных.
Неизвестный флаг тоже приводит к завершению с кодом 2.

| Флаги | Почему | Что использовать вместо них |
|---|---|---|
| `-rpc -rpcenable -rpcpassword -rpccrlf` | Стандартный RPC слушает все интерфейсы с полными правами администратора. | `-Dparlons.node.rpc=true` (только loopback) |
| `-seed -anyseed -dbpassword` | Секрет в argv может прочитать любой процесс (`ps`). | `--seed-from` / `--passphrase-file` |
| `-clean -genesis -test -solo -testchainlength` | Они стирают каталог данных или уводят узел из основной сети. | — |
| `-daemon -noshutdownhook -jnlp -help` | JVM узла сама управляет stdin, завершением и обработчиком выключения. | — |

Явно заданные свойства `-Dparlons.node.*` применяются после флагов Minima и имеют приоритет при конфликте.
Журнал запуска показывает применённые флаги как `minima flags: …`, значение `mdspassword` при этом скрыто.

### 8.9 Коды завершения

| Код | Значение |
|---|---|
| `0` | Штатная остановка: `quit` через административный RPC или встроенный узел сам завершился после пересинхронизации, восстановления или сброса. |
| `2` | Отказ запуска: неизвестный аргумент командной строки, отвергнутый или неизвестный флаг Minima, нечитаемый файл `-conf` или административный RPC не смог занять порт. |
| `3` | Запрошен перезапуск: пересинхронизация кошелька или импорт личности с подключённого устройства. systemd должен перезапустить узел (`RestartForceExitStatus=3`). |

---

## 9. Порты

| Порт (по умолчанию) | Протокол | Что | Адрес привязки | Доступность |
|---|---|---|---|---|
| `9001` (`parlons.node.port`) | TCP | P2P Minima | все | **Публичный.** Откройте его в файрволе. |
| `p2p+2` (`9003`) | TCP/HTTPS | Файловый сервер MDS / MiniHUB (только при `parlons.mds=true`) | `127.0.0.1` | Loopback. Никогда не делайте публичным без собственного TLS и файрвола. |
| `p2p+3` (`9004`) | TCP | Порт команд MDS (только при включённом MDS) | как у MDS | Loopback. |
| `p2p+4` (`9005`) | TCP/HTTP | Административный RPC (только при `parlons.node.rpc=true`) | `127.0.0.1`, жёстко задано в коде | **Никогда не открывайте наружу. Никогда не проксируйте.** |
| `9501` (`parlons.relay.port`) | TCP | Кейп ретранслятора Maxima | все | **Публичный.** Сюда подключаются телефоны и пиры mesh-сети. |
| `9501` | UDP | STUN-ответчик на порту ретранслятора | все | Необязательно. Скрипт развёртывания UDP не открывает. |
| `9585` (`parlons.gateway.port`) | TCP/HTTP | Шлюз кошелька `/cmd` и `/nft` | `127.0.0.1` | Loopback, за TLS-прокси по пути `/parlons-node/`. |
| `9587` (`parlons.panel.port`) | TCP/HTTP | Панель и API аккаунта | `127.0.0.1`, жёстко задано в коде | Loopback. Подключайтесь через SSH-туннель. |
| `9602` (`parlons.dex.port`, по умолчанию выключен) | TCP/HTTP | DEX-кейп ретранслятора | `127.0.0.1`, если задан `parlons.dex.public`, **иначе `0.0.0.0`**; `parlons.dex.bind` переопределяет | Loopback, за TLS-прокси по пути `/dex-relay/`. |

Конфликты портов, о которых нужно знать:

- **Обычный узел Minima на том же сервере** уже занимает 9001 (и 9005). Перенесите Parlons Node с помощью
  `-Dparlons.node.port=9101`. Его производные порты тоже переедут (MDS 9103/9104, RPC 9105).
- **`maxima-relay.service`** (старый отдельный ретранслятор) занимает 9501. Сначала остановите и отключите его
  (флаг `--replace-relay` скрипта развёртывания).
- **Служба `parlons-tenants`** рядом с узлом не должна использовать 9587. Укажите ей `--panel-port 9597`;
  тогда гостевые аккаунты займут 9597, 9598 и так далее на loopback.
- На сервере, где проброшен только один порт (домашний роутер), используйте `-Dparlons.relay.port=shared` или
  выберите порт ретранслятора, который вы можете пробросить.

### Как перенести узел с порта 9001

Используйте это, когда на сервере уже работает обычный узел Minima.

1. Выберите свободный базовый порт, например 9101. Убедитесь, что он и следующие четыре свободны:

   ```bash
   ss -ltnp | grep -E ':(9101|9103|9104|9105) '
   ```

2. Задайте `-Dparlons.node.port=9101` в юните и перезапустите узел ([§7](#how-to-change-the-unit)). Со
   скриптом развёртывания вместо этого укажите `--p2p-port 9101`.
3. Откройте новый P2P-порт:

   ```bash
   ufw allow 9101/tcp
   ```

4. Используйте новый порт административного RPC (`9105`) во всех командах `curl` в этом руководстве.

### Как посмотреть, какие порты занимает узел

```bash
ss -ltnp | grep java
ss -lunp | grep java
```

Первая команда выводит слушающие TCP-сокеты и их адреса привязки; вторая —
UDP STUN-ответчик.

---

## 10. Структура каталога данных

Все пути указаны относительно `parlons.node.data` (по умолчанию в скрипте развёртывания — `/var/lib/parlons-node`).
Каталог и каждый секретный файл должны принадлежать служебному пользователю и быть доступны только ему.

| Путь | Что | Секрет? |
|---|---|---|
| `<base version>/` | Собственные данные узла Minima: базы данных, кошелёк, цепочка. Файл MegaMMR — `<base version>/databases/megammr.mmr` (скрипт развёртывания использует путь `1.1/databases/megammr.mmr`). | **Да.** Здесь хранится кошелёк. |
| `identity.txt` | Закреплённая фраза личности (или seed в hex), из которой выводится личность Maxima. Права 600. | **Да. Это сид.** |
| `identity-anyphrase.txt` | Произвольная (не BIP39) фраза личности, если она была импортирована. | **Да.** |
| `gateway-token.txt` | Bearer-токен шлюза кошелька. Права 600. | Низкая важность (только чтение и рассылка, к тому же объявляется телефонам; см. [§14](#14-the-wallet-gateway-and-its-token)). |
| `pair-code.txt` | Текущий неиспользованный одноразовый код подключения. | **Да**, пока он не использован. |
| `account.txt` | Постоянный адрес аккаунта (`MAX#…`). | Нет. |
| `invite.txt` | Адрес плюс одноразовый код. | **Да**, пока код не использован. |
| `panel-ticket.txt` | Одноразовая ссылка для входа в локальную панель. | **Да.** |
| `local-device.key` | Ключ локального устройства панели. Удалите его и перезапустите узел, чтобы снова включить отозванную панель. | **Да.** |
| `devices.json` | Подключённые устройства. | Частное. |
| `cloud-settings.properties` | Настройки аккаунта. | Частное. |
| `node/`, `chat/`, `media/`, `private-files/` | Хранилище аккаунта, история чатов, медиафайлы. | Частное. |
| `relay/` | Состояние ретранслятора: каталог, элементы почтовых ящиков, хранилище блобов. | Частное. |
| `nft/` | Размещённые файлы NFT (`<sha256>.<ext>`, `c/<16-hex>/…`) плюс `tmp/` для загрузок в процессе. | Публичный контент. |
| `account-history/<import-id>/previous/` | Предыдущая личность после импорта личности. | **Да.** |
| `.seed-adopted` | Метка, которую записывает `--seed-from` скрипта развёртывания. | Нет. |

### Как проверить права доступа к каталогу данных

1. Выведите владельца и права каталога и секретных файлов:

   ```bash
   ls -ld /var/lib/parlons-node
   ls -l /var/lib/parlons-node/*.txt
   ```

2. У каталога должно быть `drwx------ maxima maxima`, а у каждого секретного `.txt` — `-rw------- maxima maxima`.
   Если это не так, исправьте:

   ```bash
   chown -R maxima:maxima /var/lib/parlons-node
   chmod 700 /var/lib/parlons-node
   chmod 600 /var/lib/parlons-node/*.txt
   ```

---

## 11. TLS-прокси: Caddy, nginx, Apache

Шлюз (9585) и DEX-кейп (9602) работают по обычному HTTP на loopback. Веб-сервер на
том же сервере завершает TLS и перенаправляет эти два пути:

| Публичный путь | Куда перенаправлять | Префикс |
|---|---|---|
| `https://node.example.com/parlons-node/…` | `http://127.0.0.1:9585/…` | убрать `/parlons-node` (тогда `/parlons-node/cmd` попадает на `/cmd`, а `/parlons-node/nft/…` — на `/nft/…`) |
| `https://node.example.com/dex-relay/…` | `http://127.0.0.1:9602/…` | убрать `/dex-relay` (тогда `/dex-relay/dex/health` попадает на `/dex/health`) |

Затем задайте в `parlons.node.public` и `parlons.dex.public` публичные URL (шаг 4 ниже).

Никогда не перенаправляйте административный RPC (`p2p+4`), панель (9587) или MDS.

Важные размеры и тайм-ауты:

- Шлюз принимает тела запросов до 1 МиБ (самый большой — подписанный `txnimport`) и отправляет
  ответы до 12 МиБ.
- Длинные опросы DEX держатся открытыми до 25 с. Конверты заявок — не более 16 КиБ; конверты
  сообщений — не более 256 КиБ.
- Тайм-аута чтения по умолчанию у большинства прокси (60 с) достаточно. Если вы его уменьшили, держите его больше 30 с.

### Как настроить любой TLS-прокси

1. Направьте DNS-имя (здесь `node.example.com`) на сервер и откройте TCP 443 (и 80 для проверки
   при получении сертификата):

   ```bash
   ufw allow 80/tcp
   ufw allow 443/tcp
   ```

2. Убедитесь, что две HTTP-службы узла отвечают на loopback (DEX — только если вы его включили):

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:9585/cmd   # 401 = gateway up
   curl -s http://127.0.0.1:9602/dex/health
   ```

3. Настройте Caddy, nginx или Apache по шагам ниже.
4. Задайте публичные URL в юните и перезапустите узел ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.node.public=https://node.example.com/parlons-node
   -Dparlons.dex.public=https://node.example.com/dex-relay
   ```

5. Проверьте с другой машины:

   ```bash
   curl -s https://node.example.com/dex-relay/dex/health
   curl -s -o /dev/null -w '%{http_code}\n' -X POST https://node.example.com/parlons-node/cmd   # 401
   ```

### Caddy

Caddy сам получает и продлевает сертификат.

#### Как поставить Caddy перед узлом

1. Установите Caddy из официальных пакетов (<https://caddyserver.com/docs/install>).
2. Сохраните резервную копию Caddyfile:

   ```bash
   cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.before-parlons-node
   ```

3. Добавьте эти блоки в блок сайта для вашего имени хоста в `/etc/caddy/Caddyfile`:

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

4. Проверьте файл, затем перезагрузите Caddy:

   ```bash
   caddy validate --config /etc/caddy/Caddyfile
   systemctl reload caddy
   ```

5. Проверьте с другой машины (шаг 5 из раздела [«Как настроить любой TLS-прокси»](#how-to-set-up-any-tls-front)).
   Если `/dex-relay/dex/health` возвращает вашу веб-страницу вместо JSON, вы попали в ловушку, описанную ниже.

`reverse_proxy` в Caddy по умолчанию отправляет `X-Forwarded-For`, поэтому DEX-кейп ограничивает частоту
для каждого клиента отдельно.

> **Ловушка порядка в Caddy: `handle_path` и `handle`.** Перед применением Caddy сортирует директивы в фиксированном
> порядке, и `handle` идёт **раньше** `handle_path`. Среди блоков `handle`
> более конкретные сопоставления путей идут первыми, а `handle` без сопоставления — последним. Блоки `handle`
> взаимоисключающие: срабатывает первый подходящий. Поэтому, если в блоке сайта есть
> универсальный `handle { … }`, `handle_path /dex-relay/*` оказывается **после** него и никогда не срабатывает:
> на каждый запрос DEX отвечает универсальный блок. Решение: напишите `handle /dex-relay/* { uri strip_prefix
> /dex-relay; reverse_proxy … }`, который сортируется вместе с другими блоками `handle` по пути. В
> блоке сайта без универсального `handle` вариант `handle_path /dex-relay/*` работает.

#### Как добавить ограничение частоты для каждого клиента в Caddy (необязательно)

Собственный лимит шлюза на один IP за прокси видит только `127.0.0.1`, поэтому ограничивайте каждого клиента
в Caddy. Для этого нужен сторонний модуль `rate_limit`, который не входит в стандартный Caddy.

1. Соберите или скачайте бинарный файл Caddy с модулем `rate_limit`.
2. Замените блок шлюза на:

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

3. Проверьте и перезагрузите, как в шагах 4 и 5 выше.

### nginx

#### Как поставить nginx перед узлом

1. Получите сертификат для имени хоста (например, с помощью certbot) и подготовьте для него блок
   `server { listen 443 ssl; server_name node.example.com; … }`.
2. Сохраните резервную копию файла сайта, который собираетесь менять:

   ```bash
   cp /etc/nginx/sites-available/<your site> /etc/nginx/sites-available/<your site>.before-parlons-node
   ```

   Если на сервере работает монитор целостности файлов, следящий за конфигурацией nginx, правка его вызовет. Предупредите
   того, кто отвечает за этот монитор, прежде чем менять файл.
3. Добавьте эти блоки внутрь блока `server { … }`:

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

4. Проверьте конфигурацию, затем перезагрузите nginx:

   ```bash
   nginx -t
   systemctl reload nginx
   ```

5. Проверьте с другой машины (шаг 5 из раздела [«Как настроить любой TLS-прокси»](#how-to-set-up-any-tls-front)).

Примечания:

- `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` нужен в блоке `/dex-relay/`.
  nginx по умолчанию не отправляет этот заголовок, и без него все клиенты DEX делят один
  лимит частоты ([§16](#16-the-dex-relay-cape-dexicon)).
- `client_max_body_size 2m` даёт запас сверх лимита тела запроса шлюза в 1 МиБ (по умолчанию в nginx —
  `1m`).

### Apache

#### Как поставить Apache перед узлом

1. Подготовьте виртуальный хост с TLS (`<VirtualHost *:443>`) для имени хоста, например с помощью certbot.
2. Включите модули прокси:

   ```bash
   a2enmod proxy proxy_http
   ```

3. Поместите это во фрагмент, подключаемый из виртуального хоста с TLS:

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

4. Проверьте конфигурацию, затем перезагрузите Apache:

   ```bash
   apachectl configtest
   systemctl reload apache2
   ```

5. Проверьте с другой машины (шаг 5 из раздела [«Как настроить любой TLS-прокси»](#how-to-set-up-any-tls-front)).

`ProxyPass` в Apache по умолчанию отправляет `X-Forwarded-For`. Для шлюза за Apache ограничением частоты
служат собственные общие лимиты (token buckets) узла.

---

## 12. Кейп ретранслятора и mesh-сеть

### Что делает кейп

Кейп — это полноценный ретранслятор Maxima. В сети он говорит на классическом протоколе Maxima, байт в байт, плюс
расширения Parlons: почтовый ящик с хранением и пересылкой, каталог, квитанции о доставке, gossip-обмен и mesh-сеть. У
телефона нет публичного адреса, поэтому он сам подключается к кейпу и держит соединение открытым; сообщения
для него приходят обратно по этому соединению. Обычный узел Minima тоже может использовать кейп как свой хост Maxima.

Кейп всегда работает как **хост пула**: хост каталога с открытым разрешением адресов и пересылающий узел mesh-сети.

### Личность

Личность кейпа (`Mx…`) выводится из `identity.txt`, который закрепляется из сид-фразы узла при
первом запуске. Сервер, на котором раньше работал `maxima-server.jar`, может сохранить личность своего ретранслятора:
флаг скрипта развёртывания `--seed-from /var/lib/maxima/seed.txt` пересинхронизирует кошелёк узла на эту фразу и закрепляет её.

### Публичный хост

Кейп должен знать свой публичный адрес. Этим адресом он называет себя в списках пиров, исключает
себя из собственной пересылки, привязывает к нему постоянный адрес аккаунта и строит объявляемый
DEX URL. Адрес ищется в таком порядке:

1. `-Dparlons.relay.host`, если вы его задали.
2. Иначе — публичный адрес, который узел Minima узнал от своих пиров
   (`network` → `details.p2p.address`), затем `status` → `network.host`.
3. Если ни один из них ещё не публичный, фоновый поток перепроверяет каждые 30 с в первые 30 минут,
   затем каждые 10 минут. Найдя адрес, он пишет в журнал
   `public address learned from peers: <ip> - the cape now names itself <ip>:<port>`.

Никогда не задавайте хостом ретранслятора адрес привязки вроде `0.0.0.0`. Классическая Minima считает адреса `0.x`
внутренними и отвергает весь ретранслятор.

### Gossip-обмен и проверенные пиры

Из `core/.../session/RelayPeers.java`:

- Пир может выдвинуть только **самого себя**. Заявка в приветствии принимается, только если заявленный хост
  совпадает с IP-адресом источника соединения.
- Каждая заявка **проверяется обратным подключением** (4 с на подключение, 2 с на чтение). Пир повышается, только если
  в ответ приходит настоящее приветствие Maxima.
- Пиры, узнанные из приветствий других ретрансляторов, тоже проверяются обратным подключением перед использованием.
  Непубличные адреса пропускаются.
- Хранится не более **250** проверенных пиров (`MAX_PEERS`). Каждое приветствие передаёт перемешанный список из не более
  чем **50** (`SHARE_LIMIT`).
- **TTL 2 ч** (`TTL_MS`). Проверенный пир, который не заявил о себе повторно и не был успешно перепроверен в течение
  2 часов, устаревает.
- **Повторное подключение каждые 10 мин** (`REDIAL_MS`). Когда очередь простаивает, поток проверки
  заново подключается к каждому проверенному пиру, к которому не подключался 10 минут. Так объявленные данные
  каждого пира (бит пула, DEX URL, шлюз) остаются актуальными, а успешное повторное подключение обновляет
  TTL. Неработающий пир не проходит подключение и устаревает.

### Mesh-сеть и `parlons.relay.peers`

- `parlons.relay.peers` — это **начальный** список, а не список доверия и не единая точка отказа.
  Доверие основано на подписи издателя, поэтому неработающий или враждебный пир просто пропускается.
- Если записи нет в каталоге, ретранслятор пересылает запрос своим пирам пула. Пересылка строго на один
  шаг, с разветвлением не более чем на 8. Ретранслятор проверяет подписанный ответ, ненадолго кэширует его и отвечает.
- Каждая принятая подписанная запись каталога копируется на `maxima.relay.replicate` (по умолчанию 3) случайных пиров
  пула, так что адрес продолжает разрешаться, пока его домашний ретранслятор недоступен.
- Если `parlons.relay.peers` не задан, используется встроенный в код начальный список. В журнале будет
  `mesh: N bootstrap peer(s) (default fleet list)`.
- Перечислите все остальные ретрансляторы, до которых вы хотите гарантированно достучаться, в виде `host:port`. Указывайте порт, на котором
  ретранслятор каждого пира действительно слушает; домашний сервер может использовать 8001 вместо 9501.

### Ёмкость

- Лимит подключений по умолчанию: 512 на платформенных потоках, 4096 на виртуальных потоках (JDK 21+). Переопределяется
  через `parlons.relay.maxconn`. При запуске ретранслятор пишет в журнал, какой режим и лимит он использует.
- Лимит на один IP-адрес источника: 32 (`maxima.relay.maxpersource`).
- Незарегистрированное подключение (которое так и не становится клиентом) разрывается через 10 минут. Простаивающие
  незарегистрированные подключения разрываются через 120 с.
- При числе зарегистрированных клиентов выше `maxima.relay.shed` (384) ретранслятор за каждый такт просит до 4 клиентов
  перейти на другой ретранслятор. Куда идти, выбирает клиент.

### Режим общего порта

При `-Dparlons.relay.port=shared` у ретранслятора нет собственного слушающего порта. P2P-слушатель Minima
передаёт ему каждое подключение, которое представляется как клиент Parlons. Тогда один публичный порт обслуживает и
цепочку, и ретранслятор. Настольные приложения используют этот режим, когда включено «contribute» (участие в сети). Серверы парка держат
явно заданный 9501.

### Как проверить, что кейп работает

1. Найдите строки кейпа и mesh-сети в журнале текущего запуска:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'Maxima cape|mesh:|public address learned|own relay' | grep -v seed
   ```

   Нужны строки `Maxima cape up on port 9501 — identity Mx…` и `mesh: N bootstrap peer(s)`.
2. Проверьте heartbeat. В нём должно быть `cape=up:9501`:

   ```bash
   journalctl -u parlons-node --since -2min --no-pager | grep heartbeat | tail -1
   ```

3. Проверьте всю цепочку целиком с другой машины ([§23](#23-verifying-from-outside), шаг 2).

### Как задать начальных пиров mesh-сети

1. Соберите `host:port` каждого ретранслятора, до которого хотите достучаться. Указывайте порт, на котором каждый ретранслятор действительно
   слушает.
2. Задайте их одним значением через запятую в юните, затем перезапустите узел ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.relay.peers=peer1.example.net:9501,peer2.example.net:9501,peer3.example.net:8001
   ```

   Со скриптом развёртывания укажите `--peers "peer1.example.net:9501,peer2.example.net:9501"`.
3. Если сервер по умолчанию блокирует исходящий трафик, разрешите порт ретранслятора каждого пира
   ([§25](#25-firewall-notes)).
4. Проверьте журнал запуска. В нём должно быть `mesh: N bootstrap peer(s)` с вашим количеством:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'mesh:'
   ```

### Как задать публичный хост

Сделайте это, когда в журнале есть `own relay NOT advertised` или когда у сервера есть постоянное имя, которое вы хотите
дать кейпу.

1. Добавьте публичное имя хоста или IP в юнит, затем перезапустите узел ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.relay.host=node.example.com
   ```

   Никогда не используйте адрес привязки вроде `0.0.0.0`, а также частный, loopback- или CGNAT-адрес.
2. Убедитесь, что предупреждение исчезло:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'own relay NOT advertised' || echo advertised-ok
   ```

### Как запустить ретранслятор на P2P-порту Minima (режим общего порта)

1. Задайте в юните порт ретранслятора `shared`, затем перезапустите узел ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.relay.port=shared
   ```

2. Откройте (или пробросьте) только P2P-порт, например 9001. Удалите правило для 9501, если оно больше не нужно:

   ```bash
   ufw delete allow 9501/tcp
   ```

3. Сообщите своим пользователям и пирам mesh-сети, что адрес этого ретранслятора — `node.example.com:9001`.

### Как включить хранилище медиаблобов

1. Задайте в юните размер хранилища в МБ, затем перезапустите узел ([§7](#how-to-change-the-unit)). Со
   скриптом развёртывания укажите `--blobstore 1024`:

   ```
   -Dparlons.relay.blob=1024
   ```

2. Убедитесь, что на диске свободно столько места (`df -h /var/lib`).
3. Проверьте журнал запуска. В нём должно быть `blob shelf: 1024 MB`:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'blob shelf'
   ```

### Как поднять лимит подключений

1. Запустите узел на JDK 21 или новее. Тогда лимит сам вырастет до 4096.
2. Чтобы задать свой лимит, добавьте его в юнит и перезапустите узел ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.relay.maxconn=8192
   ```

3. Если лимит приближается к `LimitNOFILE`, поднимите и его в юните (юнит задаёт 65536).

### Как выключить ретранслятор

Задайте в юните `-Dparlons.relay.port=0` и перезапустите узел. Тогда аккаунт использует другие ретрансляторы. Закройте
порт ретранслятора в файрволе.

---

## 13. Режим MegaMMR и корневой узел (rootnode)

### Корневой узел (rootnode)

В этом форке узла **список пиров по умолчанию пустой**. Укажите ему одно из следующего:

- `-Dparlons.node.rootnode=host:port`: пир Minima, через который узел ищет сеть. Дальше узел сам находит
  других пиров. Используйте любой доступный узел Minima, которому вы доверяете. `--help` выводит корневой узел,
  рекомендуемый проектом.
- `-Dparlons.node.connect=host:port,…`: фиксированный список с отключённым поиском пиров.
- Собственные флаги Minima `-connect` или `-nop2p` внутри `parlons.node.args`.

Без одного из них узел запускается и обслуживает свои локальные службы, но `block=` никогда не растёт.

#### Как задать корневой узел

1. Выведите корневой узел, рекомендуемый проектом, или выберите узел Minima, которому доверяете:

   ```bash
   java -jar /opt/maxima/parlons-node.jar --help | grep -i rootnode
   ```

2. Убедитесь, что сервер может до него достучаться (замените хост и порт):

   ```bash
   nc -z -w 8 seed-peer.example.net 9001 && echo reachable
   ```

3. Добавьте его в юнит, затем перезапустите узел ([§7](#how-to-change-the-unit)). Со скриптом развёртывания укажите
   `--rootnode seed-peer.example.net:9001`:

   ```
   -Dparlons.node.rootnode=seed-peer.example.net:9001
   ```

4. Следите, как растёт `block=` в heartbeat:

   ```bash
   journalctl -u parlons-node -f | grep heartbeat
   ```

### MegaMMR

На узле MegaMMR **включён по умолчанию**; в самой Minima по умолчанию он выключен. С MegaMMR узел
может доказывать монеты для **любого** адреса (`coins`/`balance megammr:true address:<any>`). Именно это
делает его шлюзом кошелька для телефонов.

**Начальная синхронизация не заполняет MegaMMR.** MegaMMR только что синхронизированного узла пуст (около 20
байт) и отслеживает только монеты, созданные с этого момента. Пока вы не импортируете снимок, балансы
существующих адресов возвращаются **пустыми**.

#### Как импортировать снимок MegaMMR

Сделайте это один раз, после первой синхронизации. Выполняйте команды на сервере от имени root.

1. Убедитесь, что административный RPC включён (`-Dparlons.node.rpc=true`, [§21](#21-the-admin-rpc)). Эта команда
   должна вывести статус в JSON:

   ```bash
   curl -s http://127.0.0.1:9005/status | head -c 200; echo
   ```

2. Проверьте текущий размер MegaMMR. Меньше 1 МБ означает, что он пуст:

   ```bash
   stat -c %s /var/lib/parlons-node/1.1/databases/megammr.mmr
   ```

3. Скачайте снимок в каталог данных и передайте его служебному пользователю (около 460 МБ;
   `-c` продолжает прерванную загрузку):

   ```bash
   cd /var/lib/parlons-node
   wget -c -O mega.mmr https://eurobuddha.com/mega.mmr
   chown maxima:maxima mega.mmr
   ```

   Чтобы использовать собственный снимок, см. следующую процедуру.
4. Импортируйте его через loopback RPC (P2P-порт 9001 означает RPC 9005):

   ```bash
   curl -s -m 3600 'http://127.0.0.1:9005/megammr%20action:import%20file:/var/lib/parlons-node/mega.mmr'
   ```

   Ответ должен содержать `"status":true`. Импорт заканчивается выключением узла.
5. Верните узел. `Restart=always` перезапускает его через 10 с; для надёжности выполните:

   ```bash
   systemctl restart parlons-node
   ```

6. Подождите около 20 с, затем убедитесь, что `megammr.mmr` теперь больше 1 МБ:

   ```bash
   stat -c %s /var/lib/parlons-node/1.1/databases/megammr.mmr
   ```

7. Запросите адрес, на котором точно есть средства. Его баланс не должен быть пустым:

   ```bash
   curl -s 'http://127.0.0.1:9005/balance%20megammr:true%20address:<a funded Mx or 0x address>'
   ```

8. Удалите скачанный файл:

   ```bash
   rm -f /var/lib/parlons-node/mega.mmr
   ```

Флаги скрипта развёртывания `--rpc --megammr-seed <URL>` выполняют все эти шаги и пропускают их, если
MegaMMR уже заполнен.

#### Как сделать собственный снимок

1. На любом подконтрольном вам узле MegaMMR с включённым административным RPC экспортируйте MegaMMR. Это берёт блокировку
   на чтение и не требует простоя:

   ```bash
   curl -s -m 3600 'http://127.0.0.1:9005/megammr%20action:export%20file:/var/lib/parlons-node/donor.megammr'
   ```

2. Скопируйте файл в каталог данных нового узла и передайте его служебному пользователю:

   ```bash
   scp /var/lib/parlons-node/donor.megammr root@node.example.com:/var/lib/parlons-node/mega.mmr
   ssh root@node.example.com chown maxima:maxima /var/lib/parlons-node/mega.mmr
   ```

3. Удалите экспорт на узле-доноре, затем выполните импорт на новом узле начиная с шага 4 процедуры выше.

#### Как запустить узел без MegaMMR

1. Добавьте `-Dparlons.node.megammr=false` в юнит (или передайте скрипту развёртывания `--no-megammr`).
2. Уменьшите кучу соответственно (768 МБ на сервере с 2 ГБ, [§3](#3-requirements)) и задайте `MemoryMax`
   на 512 МБ больше.
3. Перезапустите узел ([§7](#how-to-change-the-unit)). Узел больше не сможет быть шлюзом кошелька для других
   адресов.

**Если вы также принимаете сид-фразу, порядок важен:** выполните `--seed-from` **до** импорта MegaMMR.
`megammrsync` сбрасывает базы данных цепочки и выбрасывает импортированный MegaMMR.

Узел с `megammr=false` не может обслуживать чтения `megammr:true`. Его шлюз отвечает на такие запросы
HTTP 503 (`this node has no MegaMMR - use a MegaMMR gateway node`), и телефоны переключаются на
другой шлюз.

---

## 14. Шлюз кошелька и его токен

### Контракт

```
POST /cmd
Authorization: Bearer <token>
Content-Type: application/json

{"command":"balance megammr:true address:Mx..."}
```

Ответ — это собственный JSON узла. Ошибки возвращаются как `{"status":false,"error":"…"}` с такими HTTP-кодами:

| Код | Значение |
|---|---|
| 400 | некорректное тело JSON или пустая команда |
| 401 | bearer-токен отсутствует или неверен |
| 403 | команды нет в списке разрешённых или `coins`/`balance` без `address:` |
| 405 | не POST |
| 429 | превышен лимит частоты (проверяется **до** аутентификации) |
| 500 | внутренняя ошибка шлюза (подробности только в журнале узла) |
| 502 | ответ узла больше 12 МиБ |
| 503 | `megammr:true` на узле без MegaMMR |

### Список разрешённых команд

Проходят только эти команды. Проверяется каждый сегмент, разделённый `;`, потому что узел выполняет каждый
сегмент как отдельную команду:

- **Чтение:** `status`, `tokens`, `balance`, `coins`, `checkaddress`, `tokenvalidate`, `block`
- **Только отслеживание** (не могут перемещать средства): `newscript`, `coinexport`, `coinimport`
- **Рассылка транзакции, которую телефон подписал сам:** `txnimport`, `txnbasics`, `txnpost`, `txndelete`, `txncheck`

Всё остальное отклоняется: `send`, `vault`, `keys`, `sign`, `quit` и так далее. `coins` и
`balance` должны содержать `address:`, чтобы никто не мог получить через шлюз список собственных монет оператора.

### Токен

- Приоритет: `-Dparlons.gateway.token`, затем `<data>/gateway-token.txt`, затем новый случайный
  256-битный токен, записываемый в этот файл с правами 600.
- Токен даёт **только чтение и рассылку**. Он не может перемещать средства узла.
- **От телефонов он не секрет.** На узле MegaMMR с заданным `parlons.node.public` приветствие
  ретранслятора объявляет URL шлюза (`<public>/cmd`) и его ключ, так что телефоны находят шлюзы
  вместе с ретрансляторами. В журнале будет `wallet gateway advertised to phones: <url>`.
- Несколько шлюзов могут использовать один токен: скопируйте один и тот же `gateway-token.txt` на каждый сервер.

### Лимиты частоты

- Общий лимит: 50 запросов/с, всплеск 100 (`parlons.gateway.rate.global`). Это и есть настоящая
  защита за TLS-прокси.
- Лимит на один IP: 10 запросов/с (`parlons.gateway.rate.perip`). За loopback-прокси каждый
  запрос приходит с `127.0.0.1`, поэтому ограничивайте частоту для каждого клиента в прокси.
- У `/nft` свои отдельные лимиты (400 общий, 40 на один IP).

### Как опубликовать шлюз для телефонов

1. Запустите узел с включённым MegaMMR и импортируйте MegaMMR ([§13](#13-megammr-mode-and-the-rootnode)).
2. Держите шлюз на loopback (`-Dparlons.gateway.bind=127.0.0.1`, по умолчанию) и поставьте перед
   `/parlons-node/` TLS-прокси ([§11](#11-tls-fronting-caddy-nginx-apache)).
3. Задайте в юните публичный базовый URL без `/cmd` и перезапустите узел ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.node.public=https://node.example.com/parlons-node
   ```

4. Убедитесь, что узел его объявляет:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'wallet gateway (up|advertised|FAILED)'
   ```

   Нужна строка `wallet gateway advertised to phones: https://node.example.com/parlons-node/cmd`.
5. Проверьте его с другой машины (следующая процедура).

### Как проверить шлюз

1. На сервере прочитайте токен в переменную оболочки. Не вставляйте его в общие чаты или заявки в поддержку:

   ```bash
   TOKEN=$(sudo cat /var/lib/parlons-node/gateway-token.txt)
   ```

2. Отправьте команду `block`. Она должна вернуть JSON узла с `"status":true`:

   ```bash
   curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        -d '{"command":"block"}' https://node.example.com/parlons-node/cmd
   ```

3. Отправьте её без токена. Должен вернуться HTTP 401:

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' \
        -d '{"command":"block"}' https://node.example.com/parlons-node/cmd
   ```

4. Отправьте запрещённую команду. Должен вернуться HTTP 403:

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' -d '{"command":"vault"}' \
        https://node.example.com/parlons-node/cmd
   ```

### Как использовать один токен для нескольких шлюзов

1. Скопируйте `gateway-token.txt` с первого сервера на каждый из остальных:

   ```bash
   scp root@node.example.com:/var/lib/parlons-node/gateway-token.txt ./gateway-token.txt
   scp ./gateway-token.txt root@node2.example.com:/var/lib/parlons-node/gateway-token.txt
   rm ./gateway-token.txt
   ```

2. На каждом сервере, получившем его, исправьте владельца и права, затем перезапустите узел:

   ```bash
   chown maxima:maxima /var/lib/parlons-node/gateway-token.txt
   chmod 600 /var/lib/parlons-node/gateway-token.txt
   systemctl restart parlons-node
   ```

### Как заменить токен

1. Удалите файл токена и перезапустите узел. При запуске узел запишет новый 256-битный токен:

   ```bash
   rm /var/lib/parlons-node/gateway-token.txt
   systemctl restart parlons-node
   ```

2. Дождитесь готовности кошелька (`wallet gateway up` в журнале), затем прочитайте новый токен:

   ```bash
   sudo cat /var/lib/parlons-node/gateway-token.txt
   ```

3. Передайте новый токен всему, что было вручную настроено со старым. Телефоны, которые нашли
   шлюз через приветствие ретранслятора, получат новый ключ там же.

Если в юните задан `-Dparlons.gateway.token`, он имеет приоритет над файлом: тогда меняйте его там.

### Как изменить лимиты частоты шлюза

1. Добавьте в юнит свойства, которые хотите изменить (значения — запросы в секунду; `0`
   выключает лимит `/cmd`):

   ```
   -Dparlons.gateway.rate.global=100
   -Dparlons.gateway.rate.perip=0
   ```

2. Перезапустите узел ([§7](#how-to-change-the-unit)).
3. За TLS-прокси вместо этого ограничивайте каждого клиента в прокси
   ([§11, Caddy](#how-to-add-per-client-rate-limiting-in-caddy-optional)).

---

## 15. Хостинг NFT и `parlons.node.public`

Узел раздаёт файлы, на которые ссылаются метаданные токенов, так что маркетплейс или обозреватель блоков может
ссылаться прямо на ваш сервер.

- **Хранение:** `<data>/nft/`.
  - Отдельные файлы **адресуются по содержимому**: `<sha256>.<ext>`. URL никогда не меняется, и любой
    может сверить байты с хешем.
  - Коллекции: `c/<16-hex id>/<index>.<ext>` (индекс от 0 до 9999) с файлом `manifest.json`, в котором
    перечислен sha256 каждого элемента.
  - Не более **32 МБ** на файл. Расширения — от 2 до 5 строчных латинских букв или цифр.
  - Брошенные загрузки в `tmp/` удаляются через 24 ч.
- **Загрузка:** только с подключённых устройств, по зашифрованному каналу управления (`parlons.nft.put`,
  `parlons.nft.newcollection`, `parlons.nft.list`, `parlons.nft.delete`). Каждая загрузка отправляется
  частями и сверяется со своим sha256, прежде чем файл займёт своё место. Публичного адреса для загрузки нет.
- **Раздача:** `GET`/`HEAD /nft/<path>` на порту шлюза. Токен не нужен. Доступны только пути
  двух описанных выше видов. Ответы содержат:
  - `Cache-Control: public, max-age=31536000, immutable`
  - `X-Content-Type-Options: nosniff`
  - `Access-Control-Allow-Origin: *`
  - `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox`, поэтому SVG,
    открытый напрямую, не может выполнить скрипт на вашем домене
  - `Content-Disposition: inline`
- **Публичная база:** `-Dparlons.node.public=https://node.example.com/parlons-node`. Публичный URL
  файла — `<public>/nft/<path>`.
  - Без неё загрузки всё равно проходят, но возвращаемый `url` пуст. В журнале будет
    `nft hosting: … (no public base - set -Dparlons.node.public=https://host/parlons-node)`.
  - **Указывайте только URL, который действительно ведёт на порт 9585 этого сервера.** URL, скопированный с другого сервера,
    заставит кошельки публиковать ссылки на сервер, на котором этих файлов нет.
  - На узле MegaMMR то же свойство также объявляет шлюз телефонам ([§14](#14-the-wallet-gateway-and-its-token)).
- Типы содержимого задаются по расширению: png, jpg/jpeg, gif, webp, svg, avif, mp4, webm, mp3,
  json, txt, glb. Всё остальное — `application/octet-stream`.

### Как включить хостинг NFT

Хостинг NFT всегда включён; ему нужен только публичный URL.

1. Направьте `/parlons-node/` на `http://127.0.0.1:9585/` через TLS-прокси
   ([§11](#11-tls-fronting-caddy-nginx-apache)).
2. Задайте в юните публичную базу и перезапустите узел ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.node.public=https://node.example.com/parlons-node
   ```

3. Проверьте строку журнала:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'nft hosting'
   ```

   Она должна заканчиваться на `served at https://node.example.com/parlons-node/nft/<file>`, а не на
   `(no public base …)`.
4. Загрузите файл с подключённого устройства. Загрузка вернёт публичный `url` файла.
5. Проверьте файл с другой машины. Должно вернуться `200`:

   ```bash
   curl -sI https://node.example.com/parlons-node/nft/<sha256>.<ext> | head -1
   ```

### Как посмотреть и удалить размещённые файлы

1. Выведите, что хранит узел:

   ```bash
   ls -l /var/lib/parlons-node/nft/ /var/lib/parlons-node/nft/c/
   ```

2. Удаляйте файл с подключённого устройства (`parlons.nft.delete`). В `manifest.json` коллекции перечислен
   каждый элемент, поэтому не удаляйте файлы коллекций вручную.
3. Проверьте занятое место на диске:

   ```bash
   du -sh /var/lib/parlons-node/nft
   ```

---

## 16. DEX-кейп ретранслятора (Dexicon)

DEX-кейп ретранслятора (`dexrelay/`, версия `0.1.5`, без зависимостей) — это ретранслятор книги заявок для
Dexicon. Он работает **внутри** узла; это не отдельная служба. По замыслу ему не нужно доверять:

- Каждая заявка и каждое сообщение — это **подписанный конверт** (Ed25519), и ретранслятор проверяет подпись,
  прежде чем принять его.
- Принятые конверты передаются дальше байт в байт, так что каждый клиент проверяет те же байты, что проверил ретранслятор.
- **На диск ничего не сохраняется.** Перезапущенный ретранслятор начинает с пустого состояния, а мейкеры публикуют заявки заново при следующем такте.
- Аутентификации нет, CORS открыт, потому что каждое сообщение аутентифицирует себя само.

### Как включить DEX-кейп ретранслятора

Установщик одной командой ([§5 A](#5-three-ways-to-install)) уже делает всё это: он задаёт `parlons.dex.public` = `https://<имя>/dex-relay` (режим fleet затем включает кейп на loopback 9602) и ставит перед ним Caddy. Шаги ниже — для узла, установленного иначе.

1. Добавьте три свойства в юнит, затем перезапустите узел ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.dex.port=9602
   -Dparlons.dex.bind=127.0.0.1
   -Dparlons.dex.public=https://node.example.com/dex-relay
   ```

   Со скриптом развёртывания укажите `--dex 9602 --dex-public https://node.example.com/dex-relay`. Тогда
   скрипт сам задаст привязку к loopback.
2. Направьте `/dex-relay/` на `http://127.0.0.1:9602/` через TLS-прокси ([§11](#11-tls-fronting-caddy-nginx-apache)).
   В nginx добавьте строку `X-Forwarded-For`.
3. Проверьте журнал запуска:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'dex relay'
   ```

   Нужны эти две строки:

   ```
   [parlons-node] dex relay 0.1.5 up on 127.0.0.1:9602 (GET /dex/health)
   [parlons-node] dex relay announced in the mesh as https://node.example.com/dex-relay (GET /dex/relays)
   ```

   Если публичный URL или хост ещё не известен, вторая строка будет такой:
   `dex relay: no public host known yet - NOT announced in the mesh until one is learned …`.
4. Убедитесь, что кейп слушает только на loopback. Адрес должен быть `127.0.0.1:9602`:

   ```bash
   ss -ltnp | grep ':9602 '
   ```

5. Проверьте его с другой машины (см. [«Проверка»](#check-it) ниже).

**Привязка.** Явно заданный `parlons.dex.bind` всегда имеет приоритет. Если он не задан, кейп слушает
`127.0.0.1`, когда задан `parlons.dex.public`, и `0.0.0.0`, когда не задан. Поэтому, если вы зададите только
`parlons.dex.port`, кейп будет отвечать по обычному HTTP всему интернету. Задайте `parlons.dex.public`
(и, для ясности, `parlons.dex.bind=127.0.0.1`) и поставьте перед ним TLS.

### Как выключить DEX-кейп ретранслятора

1. Удалите три свойства `parlons.dex.*` из юнита (или задайте `-Dparlons.dex.port=0`) и
   перезапустите узел ([§7](#how-to-change-the-unit)).
2. Удалите блок `/dex-relay/` из TLS-прокси и перезагрузите его.

### API

| Метод и путь | Назначение | Ответы |
|---|---|---|
| `POST /dex/publish` | Опубликовать один конверт заявки (не более 16 КиБ). | `200 {"ok":true,"seq":…,"cursor":…}` · `400` некорректный · `409` устаревший seq · `413` слишком большой · `503` ретранслятор заполнен |
| `GET /dex/book?market=&since=&hold=` | Изменения книги заявок с момента курсора. Длинный опрос до `hold` секунд (не более 25). `since=0` возвращает полный снимок (`reset:true`). | `200 {"cursor":…,"reset":…,"orders":[…]}` · `503` занят |
| `POST /dex/msg` | Доставить одно запечатанное торговое сообщение (не более 256 КиБ). | `200 {"ok":true}` · `400` · `413` · `503` входящие заполнены |
| `GET /dex/inbox?pk=&since=&hold=` | Сообщения для публичного идентификатора (`pk` должен быть `0x` плюс 64 байта в hex). Длинный опрос до 25 с. | `200 {"cursor":…,"messages":[…]}` · `400` · `503` занят |
| `GET /dex/health` | Проверка работоспособности. | `{"ok":true,"version":"0.1.5","orders":<n>,"cursor":<n>,"uptime":<s>}` |
| `GET /dex/relays` | Все URL DEX-ретрансляторов, известные этому хосту, включая его самого, узнанные через mesh-сеть ретрансляторов. | `{"ok":true,"relays":[{"url":"…","seen":<epoch s>}]}` |

Любой другой путь возвращает 404. При ограничении частоты возвращается 429 с `{"ok":false,"error":"rate limited"}`.

### Ограничения (зафиксированы в коде)

| Ограничение | Значение |
|---|---|
| Активные заявки | 20 000 (после этого `503 relay full`) |
| Всего хранимых сообщений | 20 000 |
| Сообщений на одного получателя | 200 |
| Одновременных длинных опросов | 512 |
| Самый длинный длинный опрос | 25 с |
| Срок действия заявки | не более чем на 24 ч вперёд |
| Срок действия сообщения | не более чем на 600 с вперёд |
| Монет на заявку | 32 |
| Очистка устаревших записей | каждые 30 с |
| Частота | 200 запросов/с общая, 20 запросов/с на один IP (всплеск = запас на 2 с) |

**Ограничение частоты за прокси.** За loopback TLS-прокси каждый запрос приходит с `127.0.0.1`.
Когда запрос приходит с loopback, кейп ограничивает частоту по **самой правой записи `X-Forwarded-For`**
(той, которую добавил ваш прокси), а не по адресу соединения. Заголовок прямого вызывающего не с loopback
игнорируется, так что никто не может выбрать себе лимит сам. Ваш прокси должен отправлять этот заголовок:
`reverse_proxy` в Caddy и `ProxyPass` в Apache делают это по умолчанию; nginx нужна строка
`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` в блоке `/dex-relay/`. Без
заголовка лимит на один IP в 20 запросов/с (всплеск 40) действует на **весь публичный трафик вместе**.

### Обнаружение

- Каждый ретранслятор с DEX URL помещает этот URL в своё приветствие mesh-сети (`dex`). Пиры записывают его при
  обратном подключении и обновляют при повторном подключении раз в 10 минут.
- `GET /dex/relays` выводит собственный URL этого ретранслятора плюс все DEX URL, объявленные его проверенными
  пирами. Адреса-подстановки (`0.0.0.0`, `::`) никогда не выводятся.
- Новый ретранслятор появляется в списках других ретрансляторов примерно через один цикл повторного подключения (10 минут) после
  проверки.
- Клиенты Dexicon начинают со встроенного списка ретрансляторов и объединяют `/dex/relays` с каждого из них, так что
  медленная mesh-сеть никогда не мешает торговле.

### Проверка

1. С другой машины запросите запись о работоспособности. Она должна вернуть
   `{"ok":true,"version":"0.1.5",…}`:

   ```bash
   curl -s https://node.example.com/dex-relay/dex/health
   ```

2. Выведите DEX-ретрансляторы, известные этому. Его собственный URL должен быть в списке:

   ```bash
   curl -s https://node.example.com/dex-relay/dex/relays
   ```

3. Убедитесь, что обычный порт закрыт снаружи. Команда не должна ничего вывести:

   ```bash
   nc -z -w 5 node.example.com 9602 && echo "EXPOSED 9602"
   ```

4. Примерно через 10 минут запросите список у другого DEX-ретранслятора. URL этого узла тоже должен там появиться.

---

## 17. Аккаунт, подключение устройств и локальная панель

Каждый Parlons Node также запускает **аккаунт** Parlons: тот же код, что и `parlons-cloud.jar`, на
личности узла, с собственным кошельком узла и кейпом в роли ретранслятора. Телефоны подключаются к нему из
приложения Parlons Cloud. Выключается через `-Dparlons.account=false`.

### Как подключить телефон

Выполняйте эти команды на сервере в интерактивном SSH-терминале. Что делать в приложении на каждом шаге, описано в
[Руководстве по порталу Parlons Cloud](parlons-cloud-portal.md#4-pair-the-app-to-your-account).

1. Один раз задайте пароль администратора для подключения устройств:

   ```bash
   sudo parlons-pair --set-admin-password
   ```

2. Выведите адрес вашего аккаунта и одноразовое приглашение владельца. Если установлен `qrencode`,
   рисуется QR-код:

   ```bash
   sudo parlons-pair --admin
   ```

3. Отсканируйте или вставьте приглашение в приложении Parlons Cloud. Действующий код подключает устройство сразу.
4. Проверьте heartbeat. Число устройств в `account=up:… devices` увеличится на одно:

   ```bash
   journalctl -u parlons-node --since -2min --no-pager | grep heartbeat | tail -1
   ```

`parlons-pair` по умолчанию читает `/var/lib/parlons-node`. Если ваш каталог данных находится в другом месте,
укажите `--data DIR`.

Неиспользованный код хранится в `pair-code.txt`, пока его не используют. Использованный код больше никогда не действует.
Не берите коды из старых журналов или старых копий `invite.txt`.

### Как разместить гостевой аккаунт

Для гостевых аккаунтов нужна отдельная служба `parlons-tenants` рядом с узлом. Сначала настройте её
([Руководство по порталу Parlons Cloud, §21](parlons-cloud-portal.md#21-many-accounts-the-parlons-tenants-service)).
Укажите ей `--panel-port 9597`, чтобы она не конфликтовала с панелью узла на 9587.

1. Создайте гостевое приглашение для нового имени аккаунта:

   ```bash
   sudo parlons-pair --guest <name>
   ```

2. Отправьте приглашение гостю по закрытому каналу. Гость подключается из своего приложения Parlons
   Cloud.

Подробнее о приглашениях: [Руководство по порталу Parlons Cloud, §23](parlons-cloud-portal.md#23-owner-and-guest-invitations-from-the-server).

### Как запустить узел без аккаунта

Задайте в юните `-Dparlons.account=false` и перезапустите узел ([§7](#how-to-change-the-unit)). Тогда узел
работает только как ретранслятор и шлюз. Heartbeat показывает `account=off`.

### Собственный ретранслятор

Когда кейп включён и его публичный хост известен, аккаунт сначала подключается к собственному кейпу,
объявляет его первым и привязывает к нему свой постоянный адрес. Если хост неизвестен или частный,
в журнале будет `own relay NOT advertised: … set -Dparlons.relay.host=<public ip>`.

### Как открыть локальную панель

1. Со своего компьютера откройте SSH-туннель к порту панели и не закрывайте его:

   ```bash
   ssh -L 9587:127.0.0.1:9587 root@node.example.com
   ```

2. В этой SSH-сессии прочитайте одноразовую ссылку:

   ```bash
   sudo cat /var/lib/parlons-node/panel-ticket.txt
   ```

   Она выглядит как `http://127.0.0.1:9587/open?ticket=…`.
3. Откройте ссылку в браузере на своём компьютере. Как только ссылка использована, аккаунт создаёт новую.
4. Если раньше вы отозвали панель и хотите её вернуть, удалите ключ локального устройства и перезапустите узел:

   ```bash
   rm /var/lib/parlons-node/local-device.key
   systemctl restart parlons-node
   ```

Панель слушает только `127.0.0.1:9587` (`parlons.panel.port`; `0` выключает её). У неё нет доступа к
сид-фразе, резервным копиям, отправке средств из кошелька, платежам, консоли узла и хостингу NFT. Всё это остаётся на
подключённых телефонах и в командной строке. Что она показывает: [Руководство по порталу Parlons Cloud, §24](parlons-cloud-portal.md#24-the-local-web-panel).

### Как восстановить переносимый аккаунт на новом узле

Пакет `.pbk` создаётся на подключённом устройстве («Настройки» → **Создать резервную копию аккаунта…**, см.
[Руководство по порталу Parlons Cloud, §16](parlons-cloud-portal.md#16-backups-recovery-and-moving)). Он
содержит личность, подключённые устройства, настройки, контакты и чаты.

1. **Навсегда** остановите хост, с которого сделана резервная копия: одна личность — один работающий аккаунт.
2. Установите узел ([§5](#5-three-ways-to-install)), но не запускайте его, или остановите его и уберите его
   каталог данных в сторону. Для восстановления нужен **чистый** каталог данных:

   ```bash
   systemctl stop parlons-node
   mv /var/lib/parlons-node /var/lib/parlons-node.before-restore
   mkdir -p /var/lib/parlons-node
   chown maxima:maxima /var/lib/parlons-node
   chmod 700 /var/lib/parlons-node
   ```

3. Скопируйте пакет и передайте его служебному пользователю:

   ```bash
   cp backup.pbk /var/lib/parlons-node/backup.pbk
   chown maxima:maxima /var/lib/parlons-node/backup.pbk
   ```

4. Запустите восстановление от имени служебного пользователя в интерактивном терминале. Оно запросит парольную фразу
   пакета, запишет аккаунт и завершит работу:

   ```bash
   sudo -u maxima java -Dparlons.node.data=/var/lib/parlons-node \
       -Dparlons.restore=/var/lib/parlons-node/backup.pbk -jar /opt/maxima/parlons-node.jar
   ```

5. Удалите пакет и запустите узел:

   ```bash
   rm /var/lib/parlons-node/backup.pbk
   systemctl start parlons-node
   ```

6. Подключённые устройства переподключатся сами, потому что `MAX#` тот же.
7. Кошелёк узла остаётся его собственным хранилищем. Чтобы средства последовали за аккаунтом, пересинхронизируйте кошелёк на старую
   фразу с подключённого устройства ([§20](#how-to-resync-the-wallet-to-another-phrase)).

Приложение также может импортировать личность или `.pbk` прямо на работающий узел («Настройки» →
**Импортировать личность или резервную копию…**). Тогда узел завершается с кодом 3 и возвращается с импортированной
личностью. Предыдущая личность сохраняется в `account-history/<import-id>/previous/`.

---

## 18. Вспомогательный процесс keets (мост KeetChat)

Когда встроенный узел Minima запускает keets, чат-движок аккаунта получает транспорт keets,
а узел становится **единственным читателем** своих входящих keets.

**Fleet-jar делает всё это сам.** `parlons-node-fleet-<ver>.jar` несёт внутри среду Node.js и вспомогательный процесс. Запущенный с `-Dparlons.fleet=true` (это записывает установщик одной командой), он при первом старте распаковывает их в `<data>/runtime/`, включает роль сервера Minima и `-keets` и запускает вспомогательный процесс в режиме **serve**: один JavaScript-процесс, который одновременно является транспортом keets машины, её публичным узлом начальной загрузки HyperDHT (`49737/udp`, `-Dparlons.keets.dhtport`) и её почтовым ящиком keets (`<data>/keets-mailbox`). `-Dparlons.public.host=<ip>` даёт машине имя. В журнале загрузки печатается баннер `FLEET ENDPOINT` с публичным ключом keets, адресом начальной загрузки DHT и ключом почтового ящика, а также команда `mailboxes-sign.js`, которую выполняет сопровождающий списка; почтовый ящик хранит почту для телефонов офлайн только после подписи в опубликованный список. Флаги Minima, заданные вами в `parlons.node.args`, имеют приоритет над тем, что выставил бы режим fleet. Остальная часть раздела — ручной путь с обычным jar и внешним вспомогательным процессом.

- Включается флагами Minima в `parlons.node.args`:
  `-keets -keetssidecar /opt/maxima/keets-sidecar/index.js`. Настольные приложения также передают
  `-keetsnode <path to a node binary>`. Флаг скрипта развёртывания `--keets` загружает вспомогательный процесс (из
  `../keets/sidecar`, где нужен `node_modules`) и задаёт флаги.
- На сервере нужна среда выполнения **Node.js**. Вспомогательный процесс работает как дочерний процесс внутри песочницы службы.
- **Добавьте `AF_NETLINK` в `RestrictAddressFamilies`** в юните. Без этого каждый сокет, который открывает вспомогательный процесс,
  завершается ошибкой `EPERM`.
- **Входящий порт в файрволе не нужен.** Вспомогательный процесс использует пробивку NAT (hole punching) hyperswarm.
- При запуске мост пишет в журнал `keets chat bridge up (KeetChat interop)`, затем
  `keets identity: 0x<64 hex> (a KeetChat user adds this as a contact)`.
- Мост проверяет узел каждые 5 с. На запросы адреса для оплаты он отвечает только **контактам**,
  не чаще одного раза на пира в 10 минут.
- `-Dparlons.node.keetschat=off` позволяет приложению-хосту читать входящие keets самому (так делает minimaCore Desktop).
  На один узел всегда должен быть только один читатель.

### Как включить keets с помощью скрипта развёртывания

1. Установите Node.js на сервер. Без среды выполнения `node` скрипт отказывается продолжать:

   ```bash
   apt-get install -y nodejs
   node --version
   ```

2. На своём компьютере склонируйте репозиторий keets рядом с клоном maxima, чтобы вспомогательный процесс
   оказался в `../keets/sidecar`, и установите его пакеты:

   ```bash
   (cd ../keets/sidecar && npm ci --omit=dev)
   ```

   Чтобы использовать вспомогательный процесс из другого места, задайте `SIDECAR=/path/to/sidecar` перед запуском скрипта.
3. Запустите скрипт развёртывания повторно со всеми обычными флагами плюс `--keets`. Он загрузит вспомогательный процесс в
   `/opt/maxima/keets-sidecar`, добавит флаги и добавит `AF_NETLINK` в юнит.
4. Проверьте журнал:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'keets'
   ```

   Нужны строки `keets chat bridge up (KeetChat interop)` и `keets identity: 0x…`.

### Как включить keets вручную

1. Установите Node.js на сервер и скопируйте вспомогательный процесс вместе с его `node_modules` в
   `/opt/maxima/keets-sidecar` (там должен быть `index.js`).
2. Добавьте флаги keets в `parlons.node.args` в юните:

   ```
   "-Dparlons.node.args=-keets -keetssidecar /opt/maxima/keets-sidecar/index.js"
   ```

3. Добавьте `AF_NETLINK` в семейства адресов в юните:

   ```ini
   RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
   ```

4. Перезагрузите конфигурацию и перезапустите узел ([§7](#how-to-change-the-unit)), затем проверьте журнал, как в шаге 4 выше.
5. Передайте значение `keets identity: 0x…` пользователям KeetChat, которые хотят добавить узел в контакты.

---

## 19. MDS (MiniHUB) на узле

- `-Dparlons.mds=true` раздаёт MiniHUB и MiniDapps по адресу `https://127.0.0.1:<p2p+2>/`. Используется
  самоподписанный сертификат.
- **Он слушает loopback, если вы не укажете иное.** MiniHUB — это полноценный интерфейс кошелька за одним паролем.
  Никогда не открывайте его публично без собственного TLS и файрвола.
- Пароль берётся из conf-файла с правами 600 (`-Dparlons.node.conf=/path/minima.conf`, содержащего
  `mdspassword=…`). Иначе он генерируется: прочитайте его командой `mds` через административный RPC.
  Журнал запуска его скрывает.
- В комплект входят только хаб, страница входа, `mds.js`, publicmds и dapp Pending. Остальные
  dapp устанавливайте сами.

### Как включить MDS и открыть MiniHUB

1. Поместите пароль MDS в conf-файл ([§8, conf-файл](#how-to-pass-minimas-own-flags-in-a-conf-file))
   или пропустите этот шаг, и узел сгенерирует пароль сам.
2. Добавьте эти свойства в юнит и перезапустите узел ([§7](#how-to-change-the-unit)). Если вы пропустили шаг 1,
   не добавляйте строку `parlons.node.conf`: нечитаемый conf-файл останавливает узел
   с кодом завершения 2.

   ```
   -Dparlons.mds=true
   -Dparlons.mds.bind=127.0.0.1
   -Dparlons.node.conf=/var/lib/parlons-node/minima.conf
   ```

3. Если вы не задали пароль, прочитайте сгенерированный через административный RPC на сервере:

   ```bash
   curl -s http://127.0.0.1:9005/mds
   ```

4. Со своего компьютера откройте SSH-туннель к файловому порту MDS (P2P-порт 9001 означает 9003):

   ```bash
   ssh -L 9003:127.0.0.1:9003 root@node.example.com
   ```

5. Откройте `https://127.0.0.1:9003/` в браузере, примите самоподписанный сертификат и войдите
   с паролем.

---

## 20. Сид-фразы, личность и резервные копии

> **Сид-фраза — это кошелёк.** Фраза узла из 24 слов, `identity.txt` и любой принятый вами `seed.txt`
> позволяют тратить средства. Никогда не выводите их в журнал, заявку в поддержку, чат или терминал, который
> записывается. Когда вы ищете что-то через grep в файлах юнитов или журналах, добавляйте `| grep -v seed`.

### Два секрета, одно происхождение

| Что | Где | Для чего |
|---|---|---|
| **Сид-фраза кошелька** | хранилище (vault) узла в `<data>/<base version>/` | кошелёк узла (кошелёк аккаунта) |
| **Фраза личности** | `<data>/identity.txt` (права 600) | личность Maxima `Mx…`, `MAX#` аккаунта, подключение устройств, контакты |

При первом запуске узел создаёт собственную сид-фразу кошелька и копирует фразу в `identity.txt`. Если
фраза узла — произвольная, которую правило фраз не может воспроизвести, вместо неё сохраняется seed в hex.
С этого момента личность берётся из `identity.txt`, а не из хранилища. Поэтому:

- Кошелёк можно **пересинхронизировать** на новую фразу (с подключённого устройства: «Кошелёк» → «Сменить фразу кошелька…»),
  не меняя `MAX#`, подключённые устройства и контакты. Узел выполняет
  `megammrsync action:resync host:<parlons.node.archive> …` и завершается с кодом 3. Если пересинхронизация
  не удалась, узел продолжает работать, кошелёк не меняется, а причина показывается на устройстве.
- Чтобы заново закрепить личность за тем, что сейчас лежит в хранилище, удалите `identity.txt` и перезапустите узел.

### Как сделать резервную копию сид-фразы кошелька

1. Убедитесь, что административный RPC включён ([§21](#21-the-admin-rpc)).
2. На сервере, в сессии, которая не записывается, выведите хранилище:

   ```bash
   curl -s http://127.0.0.1:9005/vault
   ```

3. Запишите `phrase` из 24 слов на бумаге или сохраните в офлайн-хранилище. Не перенаправляйте её в файл в
   общедоступном месте.
4. Очистите историю прокрутки терминала.
5. Если фраза личности отличается от фразы кошелька (после пересинхронизации или импорта личности),
   сделайте резервную копию и `identity.txt` (следующая процедура).

### Как сделать резервную копию всего узла

Саму цепочку копировать не нужно: новый узел синхронизирует её заново. Сохраните секреты и свои
настройки.

1. Сделайте резервную копию сид-фразы кошелька (предыдущая процедура).
2. Скопируйте файлы личности и аккаунта в закрытое зашифрованное место под вашим контролем. Сначала остановите узел,
   чтобы файлы были согласованы:

   ```bash
   systemctl stop parlons-node
   tar -C /var/lib/parlons-node -czf /root/parlons-node-backup.tgz \
       --exclude='./1.1' --exclude='./nft/tmp' .
   systemctl start parlons-node
   chmod 600 /root/parlons-node-backup.tgz
   ```

   Архив содержит `identity.txt`, аккаунт (устройства, контакты, чаты), состояние ретранслятора, токен
   шлюза и размещённые файлы NFT. Это секрет уровня кошелька: обращайтесь с ним как с сид-фразой.
3. Скопируйте юнит и конфигурацию TLS-прокси:

   ```bash
   cp /etc/systemd/system/parlons-node.service /root/
   ```

4. Перенесите архив с сервера, затем удалите его там:

   ```bash
   scp root@node.example.com:/root/parlons-node-backup.tgz .
   ssh root@node.example.com rm /root/parlons-node-backup.tgz
   ```

5. Для переносимой копии только аккаунта создайте пакет `.pbk` на подключённом устройстве
   ([Руководство по порталу Parlons Cloud, §16](parlons-cloud-portal.md#16-backups-recovery-and-moving)).

Никогда не восстанавливайте этот архив на второй сервер, пока первый ещё работает. Одна личность работает в
одном месте.

### Как принять существующую фразу с помощью скрипта развёртывания

1. Поместите фразу из 24 слов в файл на сервере, владелец root, права 600. На сервере, где работал
   `maxima-server.jar`, она уже лежит в `/var/lib/maxima/seed.txt`.
2. Запустите скрипт развёртывания повторно с `--rpc --seed-from <file on the box>` плюс обычными флагами.
   Укажите `--archive <host:port>`, чтобы выбрать архивный узел. Если вы также импортируете MegaMMR,
   скрипт сначала принимает фразу, как и положено.
3. Прочитайте результат. `vault MATCHES <file> - identity preserved (and pinned in identity.txt)` означает,
   что всё получилось. Любой другой результат означает: не доверяйте узлу, пока не выясните причину.
4. Удалите файл с фразой с сервера, как только у вас будет офлайн-копия.

### Что делает `--seed-from`

Он сохраняет личность существующего ретранслятора или облачного аккаунта. Скрипт развёртывания:

1. Проверяет, что файл на сервере содержит ровно 24 слова.
2. Отправляет `megammrsync action:resync host:<--archive> phrase:"…"` как **тело POST-запроса из временного
   файла с правами 600**. Фраза никогда не попадает в argv и в журнал.
3. Перезапускает узел и сравнивает фразу в хранилище с файлом, не выводя ни ту, ни другую.
4. Если они совпадают, записывает `identity.txt` из файла и метку `.seed-adopted`, чтобы
   последующие запуски пропускали этот шаг.

Фраза никогда не покидает сервер.

### Как пересинхронизировать кошелёк на другую фразу

С подключённого устройства: «Кошелёк» → **Сменить фразу кошелька…**. Узел использует архивный хост
из `parlons.node.archive`, завершается с кодом 3 и возвращается с той же личностью.

Вручную на сервере (именно это делает `--seed-from` скрипта развёртывания):

1. Запишите команду в закрытый временный файл, чтобы фраза никогда не попала в argv или журнал:

   ```bash
   umask 077
   tmp=$(mktemp /var/lib/parlons-node/.resync.XXXXXX)
   printf 'megammrsync action:resync host:%s phrase:"%s"' 'archive.example.net:9001' '<24 words>' > "$tmp"
   ```

   Вводите фразу в сессии, которая не записывается, и после этого очистите историю оболочки.
2. Отправьте её административному RPC как тело POST-запроса. Это может занять несколько минут:

   ```bash
   curl -s -m 3600 --data-binary @"$tmp" http://127.0.0.1:9005/
   shred -u "$tmp"
   ```

   Ответ должен содержать `"status":true`. Пересинхронизация заканчивается выключением узла.
3. Перезапустите узел и дождитесь, пока RPC снова начнёт отвечать:

   ```bash
   systemctl restart parlons-node
   until curl -s -m 5 http://127.0.0.1:9005/status >/dev/null; do sleep 2; done
   ```

4. Убедитесь, что хранилище теперь содержит новую фразу (`curl -s http://127.0.0.1:9005/vault`), в
   сессии, которая не записывается.
5. Если вы используете MegaMMR, импортируйте его заново сейчас ([§13](#13-megammr-mode-and-the-rootnode)):
   пересинхронизация сбросила базы данных цепочки.

Личность остаётся закреплённой в `identity.txt`, поэтому `MAX#`, подключённые устройства и контакты не
меняются.

### Как заново закрепить личность за хранилищем

Это меняет личность Maxima и `MAX#`, если фраза хранилища отличается от
`identity.txt`. Тогда подключённые устройства и контакты указывают на старую личность.

1. Сохраните копию старого файла личности, затем удалите его:

   ```bash
   cp /var/lib/parlons-node/identity.txt /var/lib/parlons-node/identity.txt.before-repin
   rm /var/lib/parlons-node/identity.txt
   ```

2. Перезапустите узел. В журнале запуска будет `identity: pinned from the vault into identity.txt`:

   ```bash
   systemctl restart parlons-node
   journalctl -u parlons-node -b --no-pager | grep 'identity:' | grep -v seed
   ```

3. Удалите копию `.before-repin`, когда убедитесь, что старая личность вам не нужна.

### Как запустить узел, защищённый паролем

Если хранилище заблокировано, кейп, шлюз и аккаунт не могут запуститься, пока оно не разблокировано. Сам
узел Minima продолжает работать. Узел пытается разблокироваться ровно один раз за запуск — из файла или
из переменной окружения `PARLONS_NODE_PASSPHRASE`.

1. Запишите парольную фразу в файл в `/var/lib/parlons-node` или `/etc` (не в `/root` или
   `/home`: `ProtectHome=true` их скрывает). Парольная фраза не должна содержать пробельных символов, `"` или `;`:

   ```bash
   umask 077
   printf '%s\n' '<passphrase>' > /var/lib/parlons-node/passphrase.txt
   chown maxima:maxima /var/lib/parlons-node/passphrase.txt
   chmod 600 /var/lib/parlons-node/passphrase.txt
   ```

2. Добавьте её в юнит и перезапустите узел ([§7](#how-to-change-the-unit)). Со скриптом развёртывания укажите
   `--passphrase-file /var/lib/parlons-node/passphrase.txt`:

   ```
   -Dparlons.node.passphrase.file=/var/lib/parlons-node/passphrase.txt
   ```

3. Убедитесь, что кейп, шлюз и аккаунт поднялись (`cape=up`, `gateway=up` в heartbeat).
   `node is password-locked but no passphrase provided` в журнале означает, что файл не был прочитан.

### Никогда

- Никогда не передавайте `-seed`, `-anyseed` или `-dbpassword` в командной строке. Узел всё равно их отвергнет.
- Никогда не запускайте два процесса на одном каталоге данных или одну личность на двух хостах. Состояние почтовых ящиков и
  каталога разойдётся между ними, и сообщения будут теряться.
- Никогда не копируйте `identity.txt` на другой работающий сервер.

---

## 21. Административный RPC

- Включается через `-Dparlons.node.rpc=true`. Слушает `127.0.0.1:<p2p+4>` (9005, когда P2P —
  9001). Привязка к loopback жёстко задана в коде; никакая настройка не может открыть его наружу.
- Он принимает **любую** команду узла **без аутентификации**. Он доверяет локальным процессам. Он
  отклоняет кросс-доменные запросы из браузера (проверяет заголовок Host, Origin и Sec-Fetch-Site).
- Две формы запроса: `GET /<url-encoded command>` или `POST /` с командой в теле (не более
  64 КиБ).

  ```bash
  curl -s http://127.0.0.1:9005/status
  curl -s 'http://127.0.0.1:9005/balance'
  curl -s --data-binary 'network' http://127.0.0.1:9005/
  ```

- `quit` останавливает узел, и примерно через 0,8 с JVM завершается с кодом 0. При `Restart=always`
  (его записывает скрипт развёртывания) systemd снова запускает узел через 10 с. Чтобы остановить узел насовсем,
  используйте вместо этого `systemctl stop parlons-node`.
- **Никогда не ставьте его за прокси и никогда не открывайте его порт.** Защищённая публичная точка доступа — это шлюз.
- Если порт уже занят, узел пишет в журнал `REFUSING to run: admin rpc on 127.0.0.1:N cannot
  start …` и завершается с кодом 2.

### Как включить административный RPC

1. Добавьте `-Dparlons.node.rpc=true` в юнит и перезапустите узел ([§7](#how-to-change-the-unit)). Со
   скриптом развёртывания укажите `--rpc`.
2. Проверьте строку запуска. В ней должно быть `admin rpc 127.0.0.1:9005 (loopback-bound)`:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'admin rpc' | grep -v seed
   ```

3. На сервере выполните команду:

   ```bash
   curl -s http://127.0.0.1:9005/status
   ```

4. С другой машины убедитесь, что порт не отвечает. Команда не должна ничего вывести:

   ```bash
   nc -z -w 5 node.example.com 9005 && echo "EXPOSED 9005"
   ```

### Как выполнить команду с пробелами или кавычками

1. Для короткой команды закодируйте пробелы в URL как `%20`:

   ```bash
   curl -s 'http://127.0.0.1:9005/balance%20megammr:true%20address:<address>'
   ```

2. Всё, что длиннее или содержит кавычки, отправляйте как тело POST-запроса (до 64 КиБ):

   ```bash
   curl -s --data-binary 'coins relevant:true' http://127.0.0.1:9005/
   ```

### Как остановить или перезапустить узел

Используйте systemd, а не `quit`:

```bash
systemctl stop parlons-node
systemctl start parlons-node
systemctl restart parlons-node
```

---

## 22. Обновление и откат

По соглашению используются **jar-файлы с версиями плюс символическая ссылка**. Все jar-файлы хранятся в `/opt/maxima`, а
юнит всегда запускает `/opt/maxima/parlons-node.jar`, который является символической ссылкой.

### Как обновить один сервер

1. На своём компьютере скачайте новый jar-файл и проверьте его SHA-256 ([§4](#4-download-and-verify-the-jar)).
2. Скопируйте его на сервер под временным именем:

   ```bash
   B=parlons-node-0.2.144.jar
   scp $B root@node.example.com:/opt/maxima/$B.part
   ```

3. На сервере ещё раз проверьте хеш, затем поставьте jar-файл на место:

   ```bash
   cd /opt/maxima
   echo 'c5b114cc13294bdeee95d9872fc6710cd99c92472ffb63c1581081343663a6f5  parlons-node-0.2.144.jar.part' | sha256sum -c --quiet
   mv parlons-node-0.2.144.jar.part parlons-node-0.2.144.jar
   chmod 644 parlons-node-0.2.144.jar
   ```

4. Запишите, на какой jar-файл сейчас указывает символическая ссылка. Это нужно для отката:

   ```bash
   readlink /opt/maxima/parlons-node.jar
   ```

5. Сохраните резервную копию юнита:

   ```bash
   cp /etc/systemd/system/parlons-node.service /etc/systemd/system/parlons-node.service.before-parlons-node-0.2.144.jar
   ```

6. Обновите версию в строке `Description` юнита (необязательно; это только подпись):

   ```bash
   sed -i 's/wallet gateway ([0-9.]*)/wallet gateway (0.2.144)/' /etc/systemd/system/parlons-node.service
   ```

7. Направьте символическую ссылку на новый jar-файл:

   ```bash
   ln -sfn /opt/maxima/parlons-node-0.2.144.jar /opt/maxima/parlons-node.jar
   ```

8. Перезапустите узел:

   ```bash
   systemctl daemon-reload
   systemctl restart parlons-node
   ```

9. Подождите около 25 с, затем проверьте:

   ```bash
   systemctl is-active parlons-node
   journalctl -u parlons-node --since -40s --no-pager | grep -E 'Parlons Node|dex relay|Exception' | grep -v seed
   ```

   Строка запуска должна показывать `Parlons Node 0.2.144`, а DEX-кейп — `dex relay 0.1.5 up`.
10. Проверьте узел извне ([§23](#23-verifying-from-outside)).

Если у вас несколько серверов, обновляйте **по одному серверу за раз** и проверяйте каждый, прежде чем браться за следующий.

### Как обновить с помощью скрипта развёртывания

1. Положите новый проверенный jar-файл в `dist/` на своём компьютере.
2. Запустите скрипт повторно с **теми же флагами**, что и раньше, и новым jar-файлом:

   ```bash
   ops/deploy-parlons-node.sh root@node.example.com --jar dist/parlons-node-0.2.144.jar <your usual flags>
   ```

   Скрипт загружает и проверяет jar-файл, переключает символическую ссылку и перезаписывает юнит по своим флагам.
   Флаг, который вы не укажете, вернётся к значению по умолчанию.
3. Проверьте узел извне ([§23](#23-verifying-from-outside)).

### Как откатиться

1. Направьте символическую ссылку на предыдущий jar-файл (тот, который `readlink` показал на шаге 4 обновления):

   ```bash
   cd /opt/maxima && ln -sfn parlons-node-<old version>.jar parlons-node.jar
   ```

2. Если вы меняли юнит, восстановите его из резервной копии `.before-…`:

   ```bash
   cp /etc/systemd/system/parlons-node.service.before-<what> /etc/systemd/system/parlons-node.service
   ```

3. Перезапустите узел:

   ```bash
   systemctl daemon-reload
   systemctl restart parlons-node
   ```

4. Убедитесь, что строка запуска показывает старую версию, затем проверьте узел извне ([§23](#23-verifying-from-outside)).

### Как вернуться к `maxima-relay.service`

Если раньше на сервере работал `maxima-relay.service` и узел заменил его, можно откатиться и
к нему. Скрипт развёртывания оставил его юнит на диске:

```bash
systemctl disable --now parlons-node
systemctl enable --now maxima-relay
```

---

## 23. Проверка извне

`systemctl is-active` показывает только, что процесс запущен. Всегда проверяйте **с другой машины
в публичном интернете**. Не проверяйте с самого сервера или из его локальной сети: hairpin NAT даёт
ложные результаты в обе стороны.

1. **Доступность по TCP** портов P2P и ретранслятора:

   ```bash
   nc -z -w 8 node.example.com 9001 && echo p2p-open
   nc -z -w 8 node.example.com 9501 && echo relay-open
   ```

2. **Ретранслятор целиком.** Из клона этого репозитория, где `:core` и его тесты
   скомпилированы в `build/classes`:

   ```bash
   ops/verify-relay.sh node.example.com:9501
   ```

   Скрипт соединяет через ретранслятор двух настоящих клиентов и заставляет их общаться: приветствие, подключение, назначение
   адреса, обмен рукопожатиями контактов, доставка через ретранслятор в обе стороны, возврат квитанции о доставке и
   групповая рассылка. Он завершается с кодом 0, только если проходят все проверки.
3. **DEX-кейп:**

   ```bash
   curl -s https://node.example.com/dex-relay/dex/health    # {"ok":true,"version":"0.1.5",...}
   curl -s https://node.example.com/dex-relay/dex/relays
   ```

4. **Шлюз:** POST-запрос `block` с токеном ([§14](#14-the-wallet-gateway-and-its-token)). Запрос
   без токена должен вернуть 401.
5. **NFT:** `curl -sI https://node.example.com/parlons-node/nft/<sha256>.<ext>` возвращает 200 для
   размещённого файла и 404 для всего остального.
6. **Порты только для loopback НЕ должны отвечать извне:**

   ```bash
   for p in 9005 9585 9587 9602; do nc -z -w 5 node.example.com $p && echo "EXPOSED $p"; done
   ```

   Команда не должна ничего вывести.

---

## 24. Мониторинг и журналы

Весь вывод идёт в журнал (`StandardOutput=journal`). Полезные команды:

```bash
journalctl -u parlons-node -f                                   # follow
journalctl -u parlons-node -f | grep heartbeat                  # sync progress and subsystem health
journalctl -u parlons-node --since -1h | grep -E 'Exception|FAILED|REFUSING' | grep -v seed
journalctl -u parlons-node -b | grep -E 'Parlons Node|cape up|gateway up|dex relay|account up'
systemctl status parlons-node
```

Строка heartbeat, каждые 30 с:

```
[parlons-node] heartbeat: node block=<n> chainlen=<n> | cape=up:<port> | gateway=up:<port> | account=up:<n> hosts, <n> devices
```

| Поле | Норма |
|---|---|
| `block=` | растёт, затем держится на вершине цепочки |
| `cape=` | `up:<relay port>` (`down` означает, что кейп упал или выключен) |
| `gateway=` | `up:<gateway port>` |
| `account=` | `up:` с 1 или более хостами (`off` при `parlons.account=false`) |

Если вы видите `heartbeat: node not ready: …`, встроенный узел ещё не отвечает на `status`. Это
нормально в течение короткого времени после запуска.

### Как проверить состояние узла за одну минуту

1. Работает ли служба? `-w` ищет слово целиком, поэтому `inactive` не засчитывается:

   ```bash
   systemctl is-active parlons-node | grep -qw active && echo running
   ```

2. Прочитайте последний heartbeat:

   ```bash
   journalctl -u parlons-node --since -2min --no-pager | grep heartbeat | tail -1
   ```

3. Сверьте каждое поле с таблицей выше.
4. Поищите ошибки за последний час:

   ```bash
   journalctl -u parlons-node --since -1h --no-pager | grep -E 'Exception|FAILED|REFUSING|OutOfMemoryError' | grep -v seed
   ```

5. Проверьте диск и память:

   ```bash
   df -h /var/lib
   systemctl status parlons-node --no-pager | grep -E 'Memory|Tasks'
   ```

6. Выполните проверки извне ([§23](#23-verifying-from-outside)).

### Как настроить оповещения о проблемах

У узла нет адреса для метрик; интерфейсом служат heartbeat и строки журнала ниже. Простая
проверка — задание cron, которое отправляет оповещение, когда heartbeat пропадает или появляется тревожная строка:

1. Создайте скрипт проверки `/usr/local/bin/parlons-node-check`:

   ```bash
   #!/bin/sh
   # Exit 1 (and print why) when the node needs attention.
   systemctl is-active parlons-node | grep -qw active || { echo "parlons-node not active"; exit 1; }
   journalctl -u parlons-node --since -2min --no-pager | grep -q heartbeat || { echo "no heartbeat for 2 min"; exit 1; }
   bad=$(journalctl -u parlons-node --since -5min --no-pager | grep -E 'REFUSING|FAILED|OutOfMemoryError|password-locked' | grep -v seed)
   [ -z "$bad" ] || { echo "$bad"; exit 1; }
   ```

2. Сделайте его исполняемым и запускайте каждые 5 минут из crontab пользователя root со своей командой оповещения:

   ```bash
   chmod 755 /usr/local/bin/parlons-node-check
   crontab -e
   # */5 * * * * /usr/local/bin/parlons-node-check || <your alert command>
   ```

Строки журнала, на которые стоит настроить оповещения:

| Строка содержит | Значение |
|---|---|
| `REFUSING to start` / `REFUSING to run` | неверные флаги или конфликт портов (код 2) |
| `Maxima cape FAILED to start` | ретранслятор не поднялся |
| `wallet gateway FAILED` | шлюз не поднялся |
| `account layer FAILED to start` | аккаунт не поднялся |
| `node is password-locked but no passphrase provided` | кейп, шлюз и аккаунт ждут разблокировки |
| `node seed (vault) not available after 120s` | кошелёк не готов, поэтому кейп не может вывести свою личность |
| `the embedded node shut itself down` | после пересинхронизации, восстановления или сброса JVM завершается; перезапустите её |
| `wallet resync FAILED` | пересинхронизация с устройства не удалась; кошелёк не изменён |
| `OutOfMemoryError` | куча слишком мала (см. [§3](#3-requirements)) |

---

## 25. Заметки о файрволе

**Входящие, открыть:**

- TCP `<p2p port>` (9001 или то, что вы задали). Сюда подключаются пиры Minima.
- TCP `<relay port>` (9501). Сюда подключаются телефоны и пиры mesh-сети.
- TCP 443 (и 80 для ACME), если сервер обслуживает TLS-прокси.
- Необязательно: UDP `<relay port>` для STUN. Скрипт развёртывания его не открывает.

**Входящие, никогда не открывать:** `p2p+2`, `p2p+3`, `p2p+4`, 9585, 9587, 9602 и любые панели гостевых аккаунтов 959x.

**Облачные файрволы.** Файрвол провайдера (в консоли Vultr, Hetzner или AWS) может отбрасывать пакеты
ещё до того, как они дойдут до сервера. ufw этого не исправит. Если порт извне не отвечает по тайм-ауту, хотя он открыт
в ufw и отвечает на localhost, откройте его в консоли провайдера. Изменения правил могут применяться минуту или
две, поэтому проверьте ещё раз, прежде чем делать выводы.

**Запрет исходящих по умолчанию.** На сервере с политикой запрета исходящих по умолчанию (`ufw default deny
outgoing`) узел не может достучаться до пиров, которых вы не разрешили. Разрешите как минимум:

- исходящий TCP к вашему корневому узлу и пирам Minima;
- исходящий TCP к порту ретранслятора **каждого** пира mesh-сети, используя порт, на котором ретранслятор пира **действительно**
  слушает (некоторые пиры используют 8001, а не 9501): `ufw allow out to <peer-ip> port <port> proto tcp`;
- исходящий TCP к архивному узлу, используемому для пересинхронизаций;
- исходящий 443 для загрузки снимков.

Отсутствующее правило для исходящих проявляется как пир, которого нет в `/dex/relays`, или поиски в mesh-сети,
которые никогда не доходят до одного из ретрансляторов.

**Домашние роутеры.** Пробросьте P2P-порт и порт ретранслятора или используйте `-Dparlons.relay.port=shared`,
чтобы хватило одного проброшенного порта.

### Как открыть порты с помощью ufw

1. Сначала разрешите SSH, чтобы не отрезать себе доступ, затем публичные порты узла:

   ```bash
   ufw allow OpenSSH
   ufw allow 9001/tcp
   ufw allow 9501/tcp
   ufw allow 80/tcp
   ufw allow 443/tcp
   ```

2. Необязательно: разрешите STUN на порту ретранслятора:

   ```bash
   ufw allow 9501/udp
   ```

3. Включите ufw, если он выключен, и проверьте правила:

   ```bash
   ufw enable
   ufw status numbered
   ```

4. Откройте те же порты в файрволе облачного провайдера, если он есть.
5. Проверьте с другой машины ([§23](#23-verifying-from-outside)).

### Как открыть порты с помощью firewalld

1. Добавьте порты постоянно, затем перезагрузите правила, чтобы они вступили в силу:

   ```bash
   firewall-cmd --permanent --add-port=9001/tcp
   firewall-cmd --permanent --add-port=9501/tcp
   firewall-cmd --permanent --add-service=http
   firewall-cmd --permanent --add-service=https
   firewall-cmd --reload
   ```

2. Проверьте результат:

   ```bash
   firewall-cmd --list-all
   ```

Скрипт развёртывания добавляет порты с `--permanent`, но не перезагружает firewalld. На сервере с firewalld выполните
`firewall-cmd --reload` после скрипта.

### Как разрешить исходящий трафик на сервере с запретом по умолчанию

1. Разрешите порт ретранслятора каждого пира mesh-сети (повторите для каждого пира с его настоящим портом):

   ```bash
   ufw allow out to <peer ip> port 9501 proto tcp
   ```

2. Разрешите корневой узел, ваших пиров Minima и архивный узел (повторите для каждого):

   ```bash
   ufw allow out to <peer ip> port 9001 proto tcp
   ```

3. Разрешите исходящий HTTPS для загрузки снимков, а также DNS:

   ```bash
   ufw allow out 443/tcp
   ufw allow out 53
   ```

4. Убедитесь, что пир, которого не было в `/dex/relays`, появляется примерно через 10 минут.

### Как пробросить порты на домашнем роутере

1. Дайте серверу постоянный адрес в локальной сети (резервирование DHCP в роутере).
2. В роутере пробросьте TCP 9001 и TCP 9501 на этот адрес. При
   `-Dparlons.relay.port=shared` пробросьте только 9001.
3. Проверяйте извне домашней сети, например с телефона через мобильный интернет или с VPS. Проверка
   изнутри локальной сети недействительна (hairpin NAT).

---

## 26. Устранение неполадок

### Сначала разберитесь с «не удаётся подключиться»

Извне у `nc -z -w 8 <host> <port>` три возможных исхода:

| Исход | Означает | Куда смотреть |
|---|---|---|
| **Отказ** (refused), сразу | Хост ответил, но ничего не слушает. | `systemctl status parlons-node`; `ss -ltnp \| grep <port>`; журнал запуска |
| **Тайм-аут** (~8 с) | Фильтр пакетов молча отбрасывает пакеты. | Облачный файрвол или проброс порта на роутере, затем ufw |
| **Открыт** | Сетевой путь в порядке; проблема в приложении. | Журнал; `ops/verify-relay.sh` |

Проверки на сервере:

```bash
systemctl is-active parlons-node
ss -ltnp | grep -E ':(9001|9501|9585|9587|9602) '
ufw status numbered | head -20
bash -c '</dev/tcp/127.0.0.1/9501' && echo localhost-ok
```

Решающая проверка — перехватить пакеты на сервере, пока вы подключаетесь извне:

```bash
# on the box
timeout 25 tcpdump -ni any 'tcp port 9501' -c 5
# meanwhile, from outside
nc -z -w 5 node.example.com 9501
```

- Пакетов нет совсем: они отбрасываются выше по пути, и ничто на сервере этого не исправит.
- Приходит SYN, но SYN-ACK не возвращается: пакет отбрасывает сам сервер.

Осторожно с `systemctl is-active x | grep -q active`: это совпадает и с `inactive`. Используйте `grep -qw active`.

### «Invalid SyncBlock as NO PARENT» после перезапуска

Это сообщение встречалось в журнале сразу после перезапуска, пока узел догоняет вершину цепочки.
Дальше оно не исследовалось. Что делать:

1. Следите за heartbeat. Если `block=` продолжает расти и доходит до вершины, узел восстановился сам.
2. Если `block=` долго не меняется, проверьте, что корневой узел и пиры доступны
   (файрвол для исходящих, [§25](#25-firewall-notes)), и что часы идут правильно.

### Порт уже занят

| Симптом | Причина | Решение |
|---|---|---|
| `REFUSING to run: admin rpc on 127.0.0.1:9005 cannot start` | Другой узел Minima занимает `p2p+4`, или в JRE нет `jdk.httpserver`. | Перенесите P2P-порт (`parlons.node.port=9101` переносит RPC на 9105) или используйте полную JRE. |
| Конфликт порта ретранслятора; `cape=down` | `maxima-relay.service` или другой ретранслятор занимает 9501. | `systemctl disable --now maxima-relay` или выберите другой порт ретранслятора. |
| Панель не запускается на 9587 | Его занимает служба `parlons-tenants` (или второй узел). | Укажите службе tenants `--panel-port 9597` или задайте `parlons.panel.port`. |
| `wallet gateway FAILED: …BindException` | Порт 9585 чем-то занят. | Задайте `parlons.gateway.port` и измените TLS-прокси соответственно. |
| Узел не запускается на 9001 | На 9001 работает обычный узел Minima. | `parlons.node.port=9101` |

### Другие симптомы

| Симптом | Решение |
|---|---|
| Процесс сразу завершается и выводит справку | Вы передали аргумент командной строки. Все настройки — это свойства `-D` перед `-jar`. |
| `REFUSING to start: these Minima flags are not allowed` | Удалите перечисленные флаги из `parlons.node.args` или conf-файла (см. [§8.8](#88-minima-flags-the-node-refuses)). |
| `block=` остаётся на 0 | Нет корневого узла или списка connect, или исходящий доступ к нему заблокирован. |
| Цикл нехватки памяти, или служба оказывается в состоянии «failed» | Увеличьте кучу (минимум 3 ГБ с MegaMMR) и держите `MemoryMax` выше неё. Сбросьте лимит запусков командой `systemctl reset-failed parlons-node`. |
| Телефоны видят пустые балансы | MegaMMR ещё не импортирован ([§13](#13-megammr-mode-and-the-rootnode)). |
| Шлюз возвращает 503 `no MegaMMR` | Узел работает с `megammr=false`. Телефоны переключаются на другой шлюз. |
| Шлюз возвращает 403 `requires an address:` | Добавьте `address:` к `coins`/`balance`. |
| Шлюз возвращает 429 | Общий лимит исчерпан. Увеличьте `parlons.gateway.rate.global` или ограничивайте частоту в прокси. |
| Шлюз никогда не объявляется телефонам | Нужны включённый MegaMMR, заданный `parlons.node.public` и включённый ретранслятор. |
| `url` NFT пустой | Задайте в `parlons.node.public` URL, который действительно ведёт на порт 9585 этого сервера. |
| `own relay NOT advertised: public host unknown or private` | Задайте `-Dparlons.relay.host=<public host>`. |
| `dex relay: no public host known yet - NOT announced` | Задайте `-Dparlons.dex.public=<https url>` (или `parlons.relay.host`). |
| Пира нет в `/dex/relays` | Подождите один 10-минутный цикл повторного подключения. Затем проверьте, что этот сервер может подключиться **наружу** к порту ретранслятора этого пира. |
| DEX-кейп отвечает на публичном IP | `parlons.dex.bind` всё ещё `0.0.0.0`. Задайте `127.0.0.1` и перезапустите узел. |
| Caddy отдаёт ваш сайт по `/dex-relay/…` | Ловушка порядка `handle_path` и `handle` ([§11](#11-tls-fronting-caddy-nginx-apache)). |
| Аккаунт так и не сообщает «attached» | Нет исходящего доступа в интернет, или часы сильно сбиты. |
| Кейп, шлюз и аккаунт так и не запускаются; в журнале упоминается блокировка паролем | Укажите парольную фразу ([§20](#how-to-run-a-password-locked-node)). |
| Служба остановилась после `quit`, ручного импорта megammr или пересинхронизации | В юните всё ещё указано `Restart=on-failure`, а оно не перезапускает после штатного завершения. Переведите его на `Restart=always` (см. решения ниже). |
| Вспомогательный процесс keets падает с `EPERM` на сокетах | Добавьте `AF_NETLINK` в `RestrictAddressFamilies`. |
| Завершение зависшего процесса | Завершайте его по PID. Никогда не выполняйте `pkill -f parlons-node.jar`: шаблон может совпасть с вашей собственной SSH-сессией. |

### Пошаговые решения

#### Решение: порт административного RPC занят

1. Посмотрите, кто его занимает:

   ```bash
   ss -ltnp | grep ':9005 '
   ```

2. Если это обычный узел Minima, перенесите P2P-порт Parlons Node ([§9](#how-to-move-the-node-off-port-9001)).
3. Если его никто не занимает, в JRE нет `jdk.httpserver`. Проверьте и, если строки нет, установите полную JRE:

   ```bash
   java --list-modules | grep jdk.httpserver || apt-get install -y default-jre-headless
   ```

#### Решение: порт ретранслятора занят

1. Посмотрите, кто занимает 9501:

   ```bash
   ss -ltnp | grep ':9501 '
   ```

2. Если это старый отдельный ретранслятор, остановите его и перезапустите узел:

   ```bash
   systemctl disable --now maxima-relay
   systemctl restart parlons-node
   ```

3. Убедитесь, что heartbeat показывает `cape=up:9501`.

#### Решение: порт панели или шлюза занят

1. Посмотрите, кто его занимает:

   ```bash
   ss -ltnp | grep -E ':(9585|9587) '
   ```

2. Если на 9587 работает служба `parlons-tenants`, укажите ей `--panel-port 9597`
   ([Руководство по порталу Parlons Cloud, §21](parlons-cloud-portal.md#21-many-accounts-the-parlons-tenants-service)).
   Иначе перенесите узел: задайте `-Dparlons.panel.port=<free port>` или `-Dparlons.gateway.port=<free port>`.
3. Если вы перенесли шлюз, измените порт и в TLS-прокси, затем перезагрузите его.
4. Перезапустите узел ([§7](#how-to-change-the-unit)).

#### Решение: узел сразу завершается или отказывается запускаться

1. Прочитайте причину:

   ```bash
   journalctl -u parlons-node -n 30 --no-pager | grep -E 'REFUSING|usage|Usage' | grep -v seed
   ```

2. Если выведена справка, удалите в юните все аргументы после `-jar …parlons-node.jar`.
   Все настройки — это свойства `-D` перед `-jar`.
3. Если названы отвергнутые флаги Minima, удалите их из `parlons.node.args` или conf-файла
   ([§8.8](#88-minima-flags-the-node-refuses)).
4. Если сказано, что не удаётся прочитать `-conf`, проверьте путь conf-файла, его владельца (`maxima`) и права (600).
5. Перезапустите узел ([§7](#how-to-change-the-unit)).

#### Решение: `block=` остаётся на 0

1. Убедитесь, что в юните есть корневой узел или список connect:

   ```bash
   grep -E 'parlons.node.(rootnode|connect)' /etc/systemd/system/parlons-node.service
   ```

2. Если их нет, задайте ([§13](#how-to-set-the-rootnode)).
3. Убедитесь, что сервер может до него достучаться:

   ```bash
   nc -z -w 8 seed-peer.example.net 9001 && echo reachable
   ```

4. Если не может, разрешите исходящий трафик к нему ([§25](#how-to-allow-outbound-traffic-on-a-default-deny-box)).
5. Проверьте часы:

   ```bash
   timedatectl | grep -E 'synchronized|NTP'
   ```

#### Решение: цикл нехватки памяти, или служба в состоянии «failed»

1. Подтвердите причину:

   ```bash
   journalctl -u parlons-node --since -1h --no-pager | grep -E 'OutOfMemoryError|oom' | tail -5
   ```

2. Увеличьте `-Xmx` в юните (3g или больше с MegaMMR) и задайте `MemoryMax` хотя бы на 512 МБ больше,
   например `-Xmx3g` с `MemoryMax=3584M`.
3. Перезагрузите конфигурацию, сбросьте лимит запусков и запустите снова:

   ```bash
   systemctl daemon-reload
   systemctl reset-failed parlons-node
   systemctl restart parlons-node
   ```

4. Через минуту убедитесь, что служба всё ещё `active`.

#### Решение: телефоны видят пустые балансы

1. Проверьте размер MegaMMR. Меньше 1 МБ означает, что он так и не был импортирован:

   ```bash
   stat -c %s /var/lib/parlons-node/1.1/databases/megammr.mmr
   ```

2. Импортируйте его ([§13](#how-to-import-a-megammr-snapshot)).

#### Решение: шлюз никогда не объявляется телефонам

1. Проверьте три условия: MegaMMR включён, `parlons.node.public` задан, ретранслятор включён:

   ```bash
   grep -E 'megammr|node.public|relay.port' /etc/systemd/system/parlons-node.service
   ```

2. Исправьте то, чего не хватает, и перезапустите узел ([§14](#how-to-publish-the-gateway-for-phones)).
3. Найдите в журнале `wallet gateway advertised to phones:`.

#### Решение: кейп не объявляет себя, или DEX-кейп не анонсируется

1. Прочитайте предупреждение:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'own relay NOT advertised|dex relay: no public host'
   ```

2. Задайте публичный хост ([§12](#how-to-set-the-public-host)), а для DEX-кейпа также
   `-Dparlons.dex.public=https://node.example.com/dex-relay`.
3. Перезапустите узел и снова проверьте журнал.

#### Решение: DEX-кейп отвечает на публичном IP

1. Проверьте привязку:

   ```bash
   ss -ltnp | grep ':9602 '
   ```

2. Если показано `0.0.0.0:9602` или `*:9602`, добавьте в юнит `-Dparlons.dex.bind=127.0.0.1` (и
   `-Dparlons.dex.public=…`) и перезапустите узел.
3. Закройте порт в файрволе, если его открыл скрипт развёртывания:

   ```bash
   ufw delete allow 9602/tcp
   ```

4. Убедитесь извне, что 9602 больше не отвечает ([§23](#23-verifying-from-outside), шаг 6).

#### Решение: Caddy отдаёт ваш сайт по `/dex-relay/…`

1. Найдите блок DEX в Caddyfile:

   ```bash
   grep -n 'dex-relay' /etc/caddy/Caddyfile
   ```

2. Если там написано `handle_path /dex-relay/*`, замените это на `handle /dex-relay/*` с
   `uri strip_prefix /dex-relay` внутри ([§11](#how-to-front-the-node-with-caddy)).
3. Проверьте и перезагрузите Caddy, затем проверьте `/dex-relay/dex/health` извне.

#### Решение: за nginx все клиенты DEX ограничиваются по частоте вместе

1. Убедитесь, что блок `/dex-relay/` отправляет заголовок:

   ```bash
   grep -n 'X-Forwarded-For' /etc/nginx/sites-enabled/*
   ```

2. Если его нет, добавьте `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` в
   блок ([§11](#how-to-front-the-node-with-nginx)), затем выполните `nginx -t` и `systemctl reload nginx`.

#### Решение: служба остаётся остановленной после штатного завершения

1. Переведите юнит на `Restart=always` ([§7](#how-to-switch-an-old-unit-to-restartalways)).
2. Запустите узел:

   ```bash
   systemctl start parlons-node
   ```

#### Решение: вспомогательный процесс keets падает с `EPERM`

1. Добавьте `AF_NETLINK` в юнит:

   ```bash
   sed -i 's/^RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX$/RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK/' /etc/systemd/system/parlons-node.service
   ```

2. Перезагрузите конфигурацию и перезапустите узел:

   ```bash
   systemctl daemon-reload
   systemctl restart parlons-node
   ```

#### Решение: аккаунт так и не сообщает «attached»

1. Проверьте исходящий доступ в интернет и часы:

   ```bash
   curl -sI https://github.com | head -1
   timedatectl | grep synchronized
   ```

2. Если сервер блокирует исходящий трафик, разрешите порты ретрансляторов ([§25](#how-to-allow-outbound-traffic-on-a-default-deny-box)).
3. После перезапуска найдите в журнале `account up: attached to N relay(s)`.

#### Решение: зависший процесс

1. Найдите PID. Никогда не используйте `pkill -f parlons-node.jar`: шаблон может совпасть с вашей собственной SSH-сессией:

   ```bash
   systemctl show -p MainPID parlons-node
   ```

2. Попросите процесс остановиться, а принудительно завершайте, только если он не остановился:

   ```bash
   kill <pid>
   sleep 15
   kill -0 <pid> 2>/dev/null && kill -9 <pid>
   ```

3. Снова запустите его командой `systemctl start parlons-node`.

---

## 27. Чек-лист безопасности

- [ ] SHA-256 jar-файла проверен перед его активацией.
- [ ] Служба работает от имени непривилегированного пользователя `maxima` с защищённым юнитом ([§7](#7-the-systemd-unit-annotated)).
- [ ] У `/var/lib/parlons-node` права 700. У `identity.txt`, `gateway-token.txt`, `pair-code.txt`
      и `panel-ticket.txt` права 600.
- [ ] Сид-фраза и фраза кошелька сохранены офлайн и **не** встречаются ни в одном журнале, заявке или чате.
- [ ] Для входящих открыты только P2P-порт, порт ретранслятора и 443.
- [ ] Извне 9005 (административный RPC), 9585, 9587 и 9602 **не** отвечают ([§23](#23-verifying-from-outside), шаг 6).
- [ ] Везде, где задан `parlons.dex.port`, задан и `parlons.dex.public` (тогда кейп слушает `127.0.0.1`), либо задан `parlons.dex.bind=127.0.0.1`.
- [ ] `parlons.gateway.bind` равен `127.0.0.1` (никакого `--gateway-public` в рабочей среде).
- [ ] Административный RPC никогда не проксируется, а `parlons.mds.bind` никогда не публичный.
- [ ] `-Xmx` задан, и `MemoryMax` больше него.
- [ ] `parlons.node.public` указывает на URL, который действительно ведёт на этот сервер.
- [ ] В командной строке нет секретов. Парольные фразы и `mdspassword` лежат в файлах с правами 600.
- [ ] `parlons.node.selftest.send` **не** задан.
- [ ] Каждую личность и каждый каталог данных использует только один хост.
- [ ] Файрвол облачного провайдера согласован с ufw.
- [ ] Пароль администратора для подключения устройств задан, а старые приглашения использованы или удалены.

---

## 28. Настольные приложения, в которые встроен этот jar

`parlons-node.jar` также служит движком узла внутри настольных приложений. Они скачивают релиз `node-v*`
в свои ресурсы (`scripts/fetch-parlons-node-jar.sh`) и запускают его со своими флагами.

- **minimaDesk (MinimaClassic Desktop)** и **minimaCore Desktop** запускают его с
  `-Dparlons.node.rpc=true`, MegaMMR по выбору пользователя, `-Dparlons.relay.port=shared`,
  когда пользователь участвует в работе сети (иначе `0`), а также с портами панели и шлюза, производными от собственных настроек портов приложения.
- minimaDesk также передаёт `-Dparlons.mds=true -Dparlons.mds.bind=127.0.0.1` и
  `-Dparlons.node.conf=<file>`.
- minimaCore Desktop передаёт `-Dparlons.node.keetschat=off`, потому что сам читает входящие keets.
- Встроенная JRE должна включать `jdk.httpserver`.

Операторам серверных узлов ничего делать для этих приложений не нужно. Никогда не запускайте настольное приложение,
в котором хранятся реальные средства, только ради проверки чего-либо.

---

## 29. Глоссарий

| Термин | Значение |
|---|---|
| **Административный RPC (admin RPC)** | HTTP-адрес узла на `p2p+4`, доступный только через loopback, без аутентификации, принимающий любые команды. |
| **Архивный узел** | Узел Minima, хранящий достаточно истории (и MegaMMR) для пересинхронизаций через `megammrsync`. |
| **Bearer-токен** | Учётные данные шлюза в `gateway-token.txt`. Позволяют только чтение и рассылку. |
| **Начальные пиры (bootstrap peers)** | Первые ретрансляторы, с которыми связывается кейп (`parlons.relay.peers` или встроенный в код список). |
| **Кейп (cape)** | Ретранслятор Maxima, работающий внутри узла. Так же называется DEX-кейп ретранслятора. |
| **Курсор** | Число в DEX-ретрансляторе, которое только растёт. Клиенты запрашивают изменения «с момента» курсора. |
| **Dexicon** | DEX-приложение, книгу заявок которого переносит DEX-кейп ретранслятора. |
| **Конверт** | Подписанная JSON-заявка или сообщение, принятые DEX-ретранслятором. |
| **Шлюз** | Прокси `POST /cmd` со списком разрешённых команд, через который телефоны читают данные кошелька и рассылают транзакции. |
| **Heartbeat** | Строка состояния, которую узел пишет в журнал каждые 30 с. |
| **IBD** | Initial blockchain download — первая синхронизация. Она не заполняет MegaMMR. |
| **identity.txt** | Закреплённая фраза, из которой выводятся личность Maxima узла и `MAX#` аккаунта. Секрет уровня кошелька. |
| **keets / KeetChat** | Одноранговая (peer-to-peer) система чатов. Узел соединяется с ней через вспомогательный процесс на Node.js. |
| **Длинный опрос (long-poll)** | HTTP-запрос, который сервер держит открытым (здесь до 25 с), пока не появятся новости. |
| **MAX#** | Постоянный адрес аккаунта, которым можно делиться. |
| **MegaMMR** | Режим Minima, в котором хранятся доказательства для каждой монеты, так что узел может отвечать по любому адресу. |
| **Mesh-сеть** | Сеть ретрансляторов для пересылки запросов к каталогу и копирования записей каталога. |
| **MDS / MiniHUB** | Система MiniDapp в Minima и её интерфейс-хаб. |
| **MxUSD** | Токен Minima, привязанный к доллару, идентификатор токена `0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90`. Платёжные функции аккаунта распознают его по этому идентификатору. |
| **Личность Mx…** | Публичная личность Maxima ретранслятора или аккаунта. |
| **Панель** | Локальный веб-интерфейс и API аккаунта на `127.0.0.1:9587`. |
| **Хост пула** | Ретранслятор, который отвечает на открытые запросы к каталогу и участвует в mesh-сети. |
| **REDIAL_MS / TTL_MS** | Интервал повторного подключения ретранслятора (10 минут) и срок жизни проверенных пиров (2 часа). |
| **Корневой узел (rootnode)** | Пир Minima, через который узел впервые находит сеть. |
| **Общий порт** | Ретранслятор, работающий на P2P-порту Minima (`parlons.relay.port=shared`). |
| **STUN** | Небольшая UDP-служба на порту ретранслятора, которая сообщает клиентам их публичный адрес. |
| **Хост гостевых аккаунтов (tenants host)** | Отдельная служба `parlons-cloud`, которая запускает гостевые аккаунты рядом с узлом. |
| **TLS-прокси** | Веб-сервер (Caddy, nginx или Apache), который завершает HTTPS и перенаправляет запросы на loopback-порты. |
| **Хранилище (vault)** | Команда и хранилище Minima, где находится сид-фраза кошелька узла. |
| **Проверенный пир** | Ретранслятор, заявленный адрес которого подтверждён обратным подключением. |
