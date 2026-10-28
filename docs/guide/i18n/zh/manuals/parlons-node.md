# Parlons Node — 运维手册

适用于 Parlons Node **0.2.144**（发布标签 `node-v0.2.144`），其内含 DEX 中继
**0.1.5**。该发布提供两个 jar：`parlons-node-0.2.144.jar` 是普通节点；`parlons-node-fleet-0.2.144.jar` 是同一节点并内嵌 Node.js 运行时和 keets 附属进程，因此一个 jar 还能运行 keets 传输、公开的 HyperDHT 引导节点和 keets 邮箱（[§18](#18-the-keets-sidecar-keetchat-bridge)）。只要机器能运行，一行命令安装程序就会选择 fleet jar。

示例中的主机名（`node.example.com`、`peer1.example.net` 等）都是
占位符，请换成你自己的。

每项任务都有一个带编号的 **如何……** 操作步骤，并给出确切的命令。这些命令假定使用
Debian 或 Ubuntu 服务器、部署脚本的路径（`/opt/maxima`、`/var/lib/parlons-node`）、
服务用户 `maxima` 以及默认端口。如果你的不同，请相应修改。

相关手册：

- [Parlons! 用户手册](parlons.md)：各个应用，以及它们使用的中继
  （[“网络”标签页](parlons.md#19-the-network-tab-relays-and-helping-the-network)）。
- [Parlons Cloud 门户手册](parlons-cloud-portal.md)：把手机与此节点上的账户
  配对，以及 `parlons-tenants` 服务。

---

## 目录

1. [什么是 Parlons Node](#1-what-a-parlons-node-is)
2. [架构](#2-architecture)
3. [系统要求](#3-requirements)
4. [下载并校验 jar](#4-download-and-verify-the-jar)
5. [三种安装方式](#5-three-ways-to-install)
6. [首次启动](#6-first-start)
7. [systemd 单元（带注释）](#7-the-systemd-unit-annotated)
8. [配置参考：全部属性](#8-configuration-reference-every-property)
9. [端口](#9-ports)
10. [数据目录结构](#10-data-directory-layout)
11. [TLS 前端：Caddy、nginx、Apache](#11-tls-fronting-caddy-nginx-apache)
12. [中继 cape 与网格](#12-the-relay-cape-and-the-mesh)
13. [MegaMMR 模式与 rootnode](#13-megammr-mode-and-the-rootnode)
14. [钱包网关及其令牌](#14-the-wallet-gateway-and-its-token)
15. [NFT 托管与 `parlons.node.public`](#15-nft-hosting-and-parlonsnodepublic)
16. [DEX 中继 cape（Dexicon）](#16-the-dex-relay-cape-dexicon)
17. [账户、配对与本地面板](#17-the-account-pairing-and-the-local-panel)
18. [keets 附属进程（KeetChat 桥接）](#18-the-keets-sidecar-keetchat-bridge)
19. [节点上的 MDS（MiniHUB）](#19-mds-minihub-on-the-node)
20. [助记词、身份与备份](#20-seeds-identity-and-backups)
21. [管理 RPC](#21-the-admin-rpc)
22. [升级与回滚](#22-upgrading-and-rolling-back)
23. [从外部验证](#23-verifying-from-outside)
24. [监控与日志](#24-monitoring-and-logs)
25. [防火墙说明](#25-firewall-notes)
26. [故障排除](#26-troubleshooting)
27. [安全检查清单](#27-security-checklist)
28. [内嵌此 jar 的桌面应用](#28-desktop-apps-that-embed-this-jar)
29. [术语表](#29-glossary)

---

## 1. 什么是 Parlons Node

Parlons Node 是一个 Java 进程，其中包含：

- **一个完整的 Minima 节点。** 它是 `minima-core` 分支（`org.minima`），以
  `node/libs/minima-node.jar` 的形式打包在 jar 中。它验证区块链、通过 P2P 与对等节点通信，并持有一个钱包。
- **Maxima 中继，称为“cape”。** 它与 `maxima-server.jar`（`:core` + `:server`）使用
  相同的净室中继代码。手机和其他客户端主动连接到它。它也参与
  中继网格。
- **一个钱包网关。** 这是一个经过加固、带允许列表的 `POST /cmd` 代理，手机可以借助
  此节点的 MegaMMR 读取余额，并广播它们自己签名的交易。
- **NFT 托管。** 节点在网关端口的 `/nft/...` 下提供代币所指向的图片文件。
- **Parlons 账户。** 它与 `parlons-cloud.jar` 运行的 `ParlonsCore` 相同：配对、
  聊天、联系人、推送，以及一个本地网页面板。它的钱包就是节点自己的钱包。
- **Dexicon DEX 中继 cape**（可选）。这是一个小型 HTTP 服务，负责分发已签名的
  订单簿信封。它在同一个进程内运行。
- **一个 keets 聊天桥接**（可选）。当内嵌节点运行 keets 附属进程时，它把账户的聊天
  连接到 KeetChat。

**一个助记词驱动整个节点。** 首次启动时，Maxima 中继和账户的身份
由节点的钱包助记词派生，随后固定保存在 `identity.txt` 中
（见 [§20](#20-seeds-identity-and-backups)）。

谁需要它？大多数人不需要。Parlons Cloud（`parlons-cloud.jar`）只需约 1 GB 内存
即可运行一个始终在线的账户。当你想保存一份完整的区块链副本、为中继网络
提供服务，并为手机提供钱包网关时，再运行 Parlons Node。

---

## 2. 架构

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

| 组件 | 代码 | 默认监听地址 | 默认状态 |
|---|---|---|---|
| Minima 节点 | 打包的 `node/libs/minima-node.jar` | TCP `9001`（`parlons.node.port`） | 开启 |
| 管理 RPC | `node/.../AdminRpc.java` | `127.0.0.1:<p2p+4>`，始终为回环地址 | 关闭（`parlons.node.rpc=false`） |
| MDS / MiniHUB | Minima 分支的 MDS | `127.0.0.1:<p2p+2>`（文件），`<p2p+3>`（命令） | 关闭 |
| 中继 cape | `server/.../RelayRuntime`、`RelayServer` | TCP `9501`，同一端口上还有 UDP STUN | 开启 |
| 钱包网关和 `/nft` | `node/.../NodeGateway.java`、`NftStore.java` | `127.0.0.1:9585` | 开启（钱包就绪后启动） |
| 账户面板 | `account/.../ParlonsLocal.java` | `127.0.0.1:9587` | 开启 |
| DEX 中继 cape | `dexrelay/.../DexRelay.java` | 设置了 `parlons.dex.public` 时为 `127.0.0.1:<port>`，否则为 `0.0.0.0:<port>`；显式设置的 `parlons.dex.bind` 优先 | 关闭（`parlons.dex.port=0`） |
| keets 桥接 | `node/.../KeetsChatBridge.java` | 无（轮询节点） | 仅当节点以 `-keets` 运行时开启 |

**启动顺序**（来自 `ParlonsNodeMain.main`）：

1. 先应用 Minima 自己的参数（`parlons.node.args` 和 `parlons.node.conf`），然后应用
   `parlons.node.*` 属性。
2. 内嵌的 Minima 节点启动。
3. 如果已启用，管理 RPC 启动。如果无法绑定端口，进程以退出码 2 停止。
4. 如果已启用，DEX cape 启动。它不依赖区块链，所以在节点同步之前就已运行。
5. 一个后台线程等待节点的 vault 可读（最多 120 秒）。然后它派生
   身份，并依次启动中继 cape、网关和 NFT 存储，以及账户。
6. 每 30 秒记录一行心跳日志。

---

## 3. 系统要求

### Java

- **Java 11 或更高版本。** 该 jar 编译为 Java 11 字节码（`node/build.gradle`）。
- **推荐 Java 21 或更高版本。** 在 JDK 21+ 上，中继 cape 使用虚拟线程，其
  默认连接上限从 512 提高到 4096（`RelayServer.DEFAULT_MAX_CONNECTIONS_VIRTUAL`）。
- **必须有 `jdk.httpserver` 模块。** 网关、管理 RPC、面板和
  DEX cape 都使用 `com.sun.net.httpserver`。完整的 JRE 带有这个模块。如果你用 jlink 构建
  精简运行时，请加上它：
  `--add-modules java.se,jdk.unsupported,jdk.httpserver`。没有它，管理 RPC 会以
  `NoClassDefFoundError` 失败，节点拒绝运行。
- 在 Debian/Ubuntu 上，如果找不到 `java`，部署脚本会安装 `default-jre-headless`。

### 内存与堆

| 角色 | 服务器内存 | Java 堆（`-Xmx`） |
|---|---|---|
| 带 MegaMMR 的节点（默认；作为钱包网关所必需） | 8 GB | 至少 3 GB |
| 不带 MegaMMR 的节点（`-Dparlons.node.megammr=false`） | 2 GB | 768 MB |
| Fleet jar（以上任一 + keets 附属进程：Node.js、DHT、邮箱） | 再加 1 GB，至少 2 GB；仅限 x86_64 或 aarch64 | 同上（`MemoryMax` + 1 GB） |

这些数字来自 `cloud/NODE-SETUP.md` 中的节点群笔记，是吃过亏才总结出来的：堆为 2 GB 和 2.5 GB 的 MegaMMR 节点陷入内存不足的循环，
而一个 3 GB 的节点崩溃了。

- **务必设置 `-Xmx`。** 不设上限的默认堆会拖垮一台小型 VPS。
- **把 systemd 的 `MemoryMax` 设得比堆大。** 部署脚本使用“堆 + 512 MB”。如果
  `MemoryMax` 等于 `-Xmx`，一旦 JVM 的堆外内存增长，cgroup 就会杀掉它。
- 反复崩溃的节点会触发 `StartLimitBurst` 并保持停止状态。每次修改堆之后，请检查
  `systemctl is-active parlons-node`。

### 磁盘

磁盘占用随区块链增长。代码没有设定固定数值。另外还要预留：

- 导入 MegaMMR 快照时的下载空间（根据 `cloud/NODE-SETUP.md`，约 460 MB）；
- 中继 blob 存储区（如果启用，`parlons.relay.blob`，单位 MB）；
- 托管的 NFT 文件（每个最多 32 MB）。

### 网络

- 一个公网 IPv4 地址，或路由器端口转发，用于 Minima P2P 端口和中继端口。
- 出站互联网访问，用于连接 Minima 对等节点、网格中继和归档节点。
- 如果你想公开提供网关、NFT 文件或 DEX cape，需要一个带 TLS 的域名。

### 如何在安装前检查服务器

1. 检查 Java 版本。必须是 11 或更高；21 或更高更好：

   ```bash
   java -version
   ```

2. 检查 `jdk.httpserver` 模块是否存在。下面的命令必须输出一行：

   ```bash
   java --list-modules | grep jdk.httpserver
   ```

3. 检查内存。MegaMMR 节点需要 8 GB，不带 MegaMMR 需要 2 GB：

   ```bash
   free -h
   ```

4. 检查数据目录所在位置的可用磁盘空间：

   ```bash
   df -h /var/lib
   ```

5. 检查节点需要的端口是否已被占用：

   ```bash
   ss -ltnp | grep -E ':(9001|9005|9501|9585|9587|9602) '
   ```

   这应当没有任何输出。如果某个普通 Minima 节点占用了 9001，请计划移动本节点的 P2P 端口
   （[§9](#9-ports)）。如果 `maxima-relay.service` 占用了 9501，请计划替换它（[§5](#5-three-ways-to-install)）。

---

## 4. 下载并校验 jar

发布版本位于 GitHub 的 `eurobuddha/maxima`，标签为 `node-v<version>`。它们以
`--latest=false` 发布，所以仓库的“Latest”标记**不会**指向节点。请始终
按标签选择节点版本。

1. 下载 jar 和校验和文件：

   ```bash
   VER=0.2.144
   curl -fLO https://github.com/eurobuddha/maxima/releases/download/node-v$VER/parlons-node-$VER.jar
   curl -fLO https://github.com/eurobuddha/maxima/releases/download/node-v$VER/SHA256SUMS
   ```

   如需 fleet jar（内含 keets 传输、DHT 引导节点和邮箱），改为从同一发布下载
   `parlons-node-fleet-$VER.jar`。其 SHA-256 为
   `3dfd655e2c2e3541f471252b3ddbd058c9040ba5814b2bbbbba40674771bf166`。

2. 检查 SHA-256 哈希。`parlons-node-0.2.144.jar` 的哈希是：

   ```
   c5b114cc13294bdeee95d9872fc6710cd99c92472ffb63c1581081343663a6f5
   ```

   ```bash
   echo "c5b114cc13294bdeee95d9872fc6710cd99c92472ffb63c1581081343663a6f5  parlons-node-0.2.144.jar" | sha256sum -c -
   # macOS: shasum -a 256 -c  (same input)
   ```

   它必须输出 `parlons-node-0.2.144.jar: OK`。如果不是，请删除该文件，不要运行它。

3. 确认版本。这不会启动节点：

   ```bash
   java -jar parlons-node-0.2.144.jar --version
   # parlons-node 0.2.144
   ```

4. 打印内置帮助，其中列出了主要属性。这同样不会启动节点：

   ```bash
   java -jar parlons-node-0.2.144.jar --help
   ```

### 如何改为从源代码构建 jar

1. 克隆仓库并构建 fat jar：

   ```bash
   git clone https://github.com/eurobuddha/maxima.git
   cd maxima
   ./gradlew :node:fatJar
   ```

2. 找到 jar。它是 `node/build/libs/parlons-node-<version>.jar`：

   ```bash
   ls node/build/libs/
   ```

3. 发布之前，检查 jar 是否包含你的修改。以前曾有过旧的 `core.jar` 以新版本号
   发布出去的情况。在你修改过的类中统计一个你新增的符号；计数
   必须为 1 或更多：

   ```bash
   unzip -p node/build/libs/parlons-node-<version>.jar <class path> | grep -ac <symbol>
   ```

4. 记下它的校验和，以便在服务器上运行之前再次核对：

   ```bash
   sha256sum node/build/libs/parlons-node-<version>.jar
   ```

> 该 jar **不接受任何命令行选项**。它唯一接受的参数是 `--version`/`-v`
> 和 `--help`/`-h`/`help`。任何其他参数都会把帮助文本打印到 stderr，并以退出码 2 退出。
> 所有设置都是放在 `-jar` **之前**的 `-D` 系统属性。

---

## 5. 三种安装方式

### A. 一行命令安装程序（从这里开始）

在 Linux 机器本身（VPS、装有 64 位系统的树莓派 4/5、家用服务器）上，以 root 或可用 `sudo` 的用户运行：

```bash
curl -fsSL {{NODE_INSTALL}} | bash
```

这一条命令就为网络提供一个完整的端点。默认情况下，这台机器在一个进程中运行：

- 一个服务器角色的**完整 Minima 节点**（P2P 9001/tcp），内存达到 3.5 GB 时启用 MegaMMR；
- **Parlons 中继**（Maxima cape，9501/tcp+udp）和您常驻在线的 **Parlons 账户**；
- **keets 传输**、一个公开的 **HyperDHT 引导节点**（49737/udp）和一个 **keets 邮箱**，由 fleet jar 内嵌的 Node.js 运行时和附属进程提供；
- **DEX 中继 cape**（Dexicon 订单簿）和**钱包网关**，由 Caddy 通过 HTTPS 在 `https://<名称>/dex-relay` 与 `https://<名称>/parlons-node` 提供。名称即 `--host` 传入的域名；不传时机器使用 `<a-b-c-d>.sslip.io`，这是一个解析到自身公网地址的免费名称，无需注册任何东西。

它依次做这些事：

1. 缺少时安装 Java 11+ 和 `qrencode`（apt、dnf、apk 或 pacman），并安装 Caddy 作为 HTTPS 前端（若已有其他 Web 服务器占用 80/443，则告诉您需要自行代理的两个路径，见[§11](#11-tls-fronting-caddy-nginx-apache)）。
2. 在 GitHub 上找到最新的 `node-v…` 发布，下载 `parlons-node-fleet-<ver>.jar`，sha256 与发布的 `SHA256SUMS` 不符则拒绝。
3. 创建 `maxima` 用户和 `/var/lib/parlons-node`（权限 700），安装 `/usr/local/bin/parlons-pair`，写入加固的 `parlons-node.service`（[§7](#7-the-systemd-unit-annotated)），其中含 `-Dparlons.fleet=true`、`-Dparlons.public.host=<您的公网 IPv4>`、`-Dparlons.dex.public=https://<名称>/dex-relay` 和 `-Dparlons.node.public=https://<名称>/parlons-node`。
4. 按机器调整节点：内存 3.5 GB 及以上时以 2 GB 堆运行 MegaMMR；低于此值则以 768 MB 堆运行普通完整节点。`MemoryMax` 为堆加 1.5 GB（留给附属进程）。从 `65.109.31.226:9001` 同步。
5. 在启用的 ufw 或 firewalld 中开放 9001/tcp、9501/tcp+udp、49737/udp 以及 80 + 443。
6. 启动节点，等待账户地址并完整打印。
7. 要求设置管理员配对密码（仅首次安装），再请输入一次，然后绘制手机在 Parlons Cloud 中扫描的二维码。
8. 打印机器的 keets 公钥、DHT 引导地址和邮箱密钥，以及列表维护者用来把邮箱签入已发布列表的命令。

完整方案需要 x86_64 或 aarch64 CPU、2 GB 内存和公网 IPv4 地址。机器缺少其中任一项时，安装程序会说明原因并退回普通 jar（`parlons-node-<ver>.jar`：Minima 节点、Parlons 中继和账户，无 keets、无 DEX），它可在任何 CPU 上以 1 GB 内存运行。

选项（放在 `bash -s --` 之后）：

| 选项 | 作用 |
|---|---|
| `--host node.example.com` | 用您自己的域名而非 sslip.io 名称提供 DEX 中继和钱包网关。先把该域名的 A 记录指向此机器；Caddy 会自行获取证书。 |
| `--public-host 1.2.3.4` | DHT 和中继对外通告的公网 IPv4。默认为机器的全局地址。在家用路由器后面时，填写您的公网地址并转发端口。 |
| `--basic` | 有意使用普通 jar：无 keets 传输、DHT、邮箱或 DEX。 |
| `--fleet` | 坚持使用完整 jar；失败而不是退回。 |
| `--update` | 获取最新发布并重启。再次运行安装命令效果相同。 |
| `--uninstall` | 停止并移除服务和 `parlons-pair`。保留 `/var/lib/parlons-node`（身份、钱包、聊天）。 |
| `--no-megammr` | 不带 MegaMMR 的普通完整节点（1 GB 内存足够）。内存低于 3.5 GB 的机器会自动选择。 |
| `--heap <大小>` | 不同的 Java 堆，例如 `--heap 3g`。 |

例如，使用自己的域名：`curl -fsSL {{NODE_INSTALL}} | bash -s -- --host node.example.com`

安装程序不设置中继网状对等节点（使用内置引导列表），也不设置 blob 存储。如有需要，使用部署脚本（B）或编辑单元文件（[§7](#7-the-systemd-unit-annotated)）。Mac 或 Windows 电脑请改装 Parlons Desktop。若机器完全无法运行节点，仅账户的 `get-parlons-cloud.sh` 可在 1 GB 内运行（[Parlons Cloud Portal 手册](parlons-cloud-portal.md)）。

#### 如何用一行命令安装程序安装

1. 在云服务商的防火墙中开放 9001/tcp、9501/tcp 和 9501/udp、49737/udp 以及 80 + 443/tcp，或在家用路由器上转发它们。账户没有它们也能工作；为他人中继、提供区块、DHT 和 DEX 中继需要开放它们。
2. 若有域名，现在就把 A 记录指向此机器，以便安装结束时 Caddy 获取证书。否则跳过：sslip.io 名称无需任何操作。
3. 在 SSH 会话或机器的终端中运行上面的命令。不要在没有终端的脚本中运行：那样会跳过密码和二维码步骤。
4. 按提示输入两次管理员配对密码。请保存好；为自己设备生成的每个新邀请都需要它。
5. 再次提示时输入一次密码。二维码和完整邀请随即出现。
6. 在手机的 Parlons Cloud 中点击 **Scan pairing QR** 并扫描。
7. 阅读最后几行。它们给出 DEX 中继 URL 和钱包网关 URL，并打印机器的 keets 公钥、DHT 引导地址和邮箱密钥。把邮箱那一行发给列表维护者；在签入已发布列表之前，机器会中继和引导，但不会为离线手机保存邮件。
8. 观察链同步：`journalctl -u parlons-node -f | grep heartbeat`。
9. 从另一台机器检查 DEX 中继：`curl -s https://<名称>/dex-relay/dex/health` 必须有响应。首次启动后证书可能需要一分钟。
10. 备份助记词（[§20](#20-seeds-identity-and-backups)）。

若跳过了第 4 或第 5 步，先运行一次 `sudo parlons-pair --set-admin-password`，再运行 `sudo parlons-pair --admin`。

### B. 部署脚本（从你自己的电脑运行）

在你自己电脑上的仓库检出副本中运行它，目标是一台你可以用 root 通过 SSH 访问的全新 Linux
服务器。再次运行它就会更新节点；安装和更新是
同一个操作。

#### 如何用部署脚本安装

1. 在你的电脑上克隆仓库。其中包含 `ops/deploy-parlons-node.sh` 和
   `ops/parlons-pair`：

   ```bash
   git clone https://github.com/eurobuddha/maxima.git
   cd maxima
   ```

2. 把校验过的 jar 放进 `dist/`（[§4](#4-download-and-verify-the-jar)）。不加 `--jar` 时，
   脚本使用最新的 `dist/parlons-node-*.jar`：

   ```bash
   mkdir -p dist
   mv ~/Downloads/parlons-node-0.2.144.jar dist/
   ```

3. 检查以 root 身份基于密钥的 SSH 是否可用。脚本使用 `BatchMode=yes`，所以无法应答
   密码提示：

   ```bash
   ssh -o BatchMode=yes root@node.example.com true && echo ssh-ok
   ```

4. 如果服务器已经在 9501 上运行 `maxima-relay.service`，请在下一条命令中加上 `--replace-relay`。
5. 运行脚本。这是一个带 TLS 前端的典型 MegaMMR 节点；下表列出了所有参数：

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

6. 阅读最后的摘要。它会打印账户钱包地址、Maxima 身份
   （`Mx…`）和网关 bearer 令牌。请保密该令牌
   （[§14](#14-the-wallet-gateway-and-its-token)）。
7. 设置 TLS 前端（[§11](#11-tls-fronting-caddy-nginx-apache)）。
8. 配对你的手机（[§17](#17-the-account-pairing-and-the-local-panel)）并备份助记词
   （[§20](#20-seeds-identity-and-backups)）。
9. 从另一台机器验证节点（[§23](#23-verifying-from-outside)）。

#### 脚本做了什么

脚本依次执行以下步骤：

1. 如果缺少 `java`，安装无头 JRE，并尝试安装 `qrencode`（用于配对二维码）。
2. 创建无特权的 `maxima` 系统用户，并以权限 700 创建 `/var/lib/parlons-node`。
3. 把 jar 上传到 `/opt/maxima/parlons-node-<ver>.jar` 并检查其 SHA-256。只有通过后，才会
   把符号链接 `/opt/maxima/parlons-node.jar` 指向新的 jar。
4. 安装 `/usr/local/bin/parlons-pair`。
5. 写入加固版单元文件 `/etc/systemd/system/parlons-node.service`。
6. 在 ufw 或 firewalld 中开放 P2P 端口和中继端口。只有在给出 `--dex` 而没有 `--dex-public` 时，
   才开放 DEX 端口；只有给出 `--gateway-public` 时，才开放网关端口。
7. 启动节点，并检查中继端口是否在监听。
8. 可选：`--seed-from` 采用一个已有的 24 词助记词（[§20](#20-seeds-identity-and-backups)）。
9. 可选：`--megammr-seed` 导入一个 MegaMMR 快照（[§13](#13-megammr-mode-and-the-rootnode)）。
10. 打印账户钱包地址、Maxima 身份、网关令牌和配对说明。

| 脚本参数 | 对应设置 | 默认值 |
|---|---|---|
| `--jar FILE` | 要部署的 jar | 最新的 `dist/parlons-node-*.jar` |
| `--heap 3g` | `-Xmx` | `2g`（对 MegaMMR 来说太小；请传入 `3g` 或更大） |
| `--memmax 3584M` | 单元的 `MemoryMax` | 堆 + 512M |
| `--p2p-port N` | `-Dparlons.node.port` | `9001` |
| `--relay-port N` | `-Dparlons.relay.port` | `9501` |
| `--gateway-port N` | `-Dparlons.gateway.port` | `9585` |
| `--gateway-public` | `-Dparlons.gateway.bind=0.0.0.0`，并开放该端口 | 回环地址 |
| `--public URL` | `-Dparlons.node.public` | 未设置 |
| `--rootnode h:p` | `-Dparlons.node.rootnode` | 未设置（节点不会同步） |
| `--no-megammr` | `-Dparlons.node.megammr=false` | MegaMMR 开启 |
| `--rpc` | `-Dparlons.node.rpc=true` | 关闭 |
| `--peers h:p,…` | `-Dparlons.relay.peers` | 未设置（使用编译内置的引导列表） |
| `--blobstore MB` | `-Dparlons.relay.blob` | `0` |
| `--dex PORT` | `-Dparlons.dex.port` | `0`（关闭） |
| `--dex-public URL` | `-Dparlons.dex.bind=127.0.0.1 -Dparlons.dex.public=URL` | 未设置 |
| `--passphrase-file F` | `-Dparlons.node.passphrase.file` | 未设置 |
| `--node-args "…"` | `-Dparlons.node.args` | 空 |
| `--keets` | 在节点参数中加入 `-keets -keetssidecar /opt/maxima/keets-sidecar/index.js`，并上传附属进程 | 关闭 |
| `--seed-from F --archive h:p` | 一次性把钱包重新同步到 F 中的助记词（需要 `--rpc`），然后把它固定到 `identity.txt` | —（`--archive` 默认为写在脚本中的一个归档节点） |
| `--megammr-seed URL` | 一次性导入 MegaMMR（需要 `--rpc`） | — |
| `--replace-relay` | 停止并禁用 `maxima-relay.service`，让节点接管它的端口 | — |

> **`--dex-public` 务必与 `--dex` 一起传入。** 有了它，脚本会设置
> `parlons.dex.bind=127.0.0.1` 并保持端口关闭。没有它，DEX cape 会绑定 `0.0.0.0`，
> 脚本还会以明文 HTTP 把该端口开放到互联网。

脚本在单元文件中写入 `Restart=always`，所以节点在正常退出后
（`quit`、MegaMMR 导入、重新同步）也会重新启动。

### C. 手动安装

最简命令是：

```bash
java -Xmx3g -Dparlons.node.data=/var/lib/parlons-node -jar parlons-node.jar
```

这样启动的节点没有 rootnode，所以不会同步。

#### 如何手动安装

以下步骤在服务器上完成部署脚本所做的工作。请以 root 身份运行。

1. 如果缺少 Java，请安装它；如果你需要配对二维码，再安装 `qrencode`：

   ```bash
   apt-get update
   apt-get install -y default-jre-headless qrencode
   ```

2. 创建服务用户和目录：

   ```bash
   id maxima >/dev/null 2>&1 || useradd --system --home /var/lib/maxima --shell /usr/sbin/nologin maxima
   mkdir -p /opt/maxima /var/lib/parlons-node
   chown -R maxima:maxima /var/lib/parlons-node
   chmod 700 /var/lib/parlons-node
   ```

3. 把校验过的 jar 复制到 `/opt/maxima`，并把符号链接指向它：

   ```bash
   cp parlons-node-0.2.144.jar /opt/maxima/
   cd /opt/maxima
   echo "c5b114cc13294bdeee95d9872fc6710cd99c92472ffb63c1581081343663a6f5  parlons-node-0.2.144.jar" | sha256sum -c -
   chmod 644 parlons-node-0.2.144.jar
   ln -sfn parlons-node-0.2.144.jar parlons-node.jar
   ```

4. 可选：从仓库检出副本安装配对辅助工具：

   ```bash
   install -m 755 ops/parlons-pair /usr/local/bin/parlons-pair
   ```

5. 参照 [§7](#7-the-systemd-unit-annotated) 中的示例编写单元文件 `/etc/systemd/system/parlons-node.service`。
   根据服务器情况修改主机名、堆和 `MemoryMax`。
6. 开放 P2P 端口和中继端口（[§25](#25-firewall-notes)）：

   ```bash
   ufw allow 9001/tcp
   ufw allow 9501/tcp
   ```

7. 启动节点，然后按照 [§6](#6-first-start) 操作：

   ```bash
   systemctl daemon-reload
   systemctl enable --now parlons-node
   ```

---

## 6. 首次启动

1. 启动服务（一行命令安装程序和部署脚本已经完成了这一步）：

   ```bash
   systemctl enable --now parlons-node
   systemctl is-active parlons-node
   ```

2. 跟踪日志：

   ```bash
   journalctl -u parlons-node -f
   ```

3. 找到启动行。它列出了版本、数据文件夹和端口：

   ```
   [parlons-node] Parlons Node 0.2.144 — booting embedded Minima node <ver> at /var/lib/parlons-node/<base> (p2p 9001, megammr true, admin rpc 127.0.0.1:9005 (loopback-bound))
   ```

4. 观察区块链同步。每 30 秒会有一行心跳日志打印区块高度：

   ```
   [parlons-node] heartbeat: node block=<n> chainlen=<n> | cape=up:9501 | gateway=up:9585 | account=up:<n> hosts, <n> devices
   ```

   当 `block=` 达到当前链顶时，首次同步就完成了。这没有固定的时间，
   取决于服务器和网络连接。
5. 钱包就绪后，会出现以下几行：
   - `identity: pinned from the vault into identity.txt`（仅首次启动）；
   - `Maxima cape up on port 9501 — identity Mx…`；
   - `account wallet = node wallet: 0x… (Mx…)`；
   - `wallet gateway up on 127.0.0.1:9585/cmd (… bearer token in /var/lib/parlons-node/gateway-token.txt)`；
   - `account up: attached to N relay(s)…`。
6. 在 MegaMMR 节点上，导入一次 MegaMMR（[§13](#13-megammr-mode-and-the-rootnode)）。在此之前，
   已有地址的余额会返回空值。
7. 配对你的手机（[§17](#17-the-account-pairing-and-the-local-panel)）：

   ```bash
   sudo parlons-pair --set-admin-password
   sudo parlons-pair --admin
   ```

8. 备份助记词（[§20](#20-seeds-identity-and-backups)）。

---

## 7. systemd 单元（带注释）

这个单元文件依照 `ops/deploy-parlons-node.sh` 写入的版本，并使用节点群在
2026 年 10 月采用的参数：MegaMMR 节点、中继在 9501、网关和 DEX cape 位于 TLS 之后的回环地址上，以及一个
显式的网格引导列表。

systemd 不允许在行尾或续行的 `ExecStart` 内部写注释，所以
说明都写在 `ExecStart` 上方的注释块中。

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

说明：

- 整个 `-Dparlons.node.args=…` 属性都加了引号，因为它是传给 JVM 的一个参数，
  并且可能包含空格。systemd 支持双引号。不要在值内部再放双引号。
- 如果你在 `parlons.node.args` 中传入 Minima 的 `-data <dir>`，请把该目录加入 `ReadWritePaths`。
- 对于有密码锁定的节点，添加 `-Dparlons.node.passphrase.file=/var/lib/parlons-node/passphrase.txt`
  （所有者 `maxima`，权限 600），或在权限为 600 的 `EnvironmentFile=` 中设置 `PARLONS_NODE_PASSPHRASE`。
- 每次修改前都备份单元文件。命名惯例是
  `parlons-node.service.before-<what>`。

### 如何修改单元文件

1. 备份当前的单元文件：

   ```bash
   cp /etc/systemd/system/parlons-node.service /etc/systemd/system/parlons-node.service.before-<what>
   ```

2. 编辑它：

   ```bash
   nano /etc/systemd/system/parlons-node.service
   ```

3. 检查单元文件是否有错误：

   ```bash
   systemd-analyze verify /etc/systemd/system/parlons-node.service
   ```

4. 加载它并重启节点：

   ```bash
   systemctl daemon-reload
   systemctl restart parlons-node
   ```

5. 等待约 25 秒，然后检查它是否在运行，并阅读启动行：

   ```bash
   systemctl is-active parlons-node
   journalctl -u parlons-node --since -40s --no-pager | grep -E 'Parlons Node|minima flags|REFUSING' | grep -v seed
   ```

6. 如果它没有启动，把备份放回去，然后重复第 4 步：

   ```bash
   cp /etc/systemd/system/parlons-node.service.before-<what> /etc/systemd/system/parlons-node.service
   ```

### 如何把旧单元文件切换为 `Restart=always`

1. 查看单元文件中的设置：

   ```bash
   grep -n '^Restart=' /etc/systemd/system/parlons-node.service
   ```

2. 如果它输出 `Restart=on-failure`，就修改它：

   ```bash
   cp /etc/systemd/system/parlons-node.service /etc/systemd/system/parlons-node.service.before-restart-always
   sed -i 's/^Restart=on-failure$/Restart=always/' /etc/systemd/system/parlons-node.service
   systemctl daemon-reload
   ```

   新设置立即生效，无需重启。

---

## 8. 配置参考：全部属性

所有属性都是放在 `-jar` 之前的 JVM 系统属性（`-Dname=value`）。这个列表来自
对打包进 jar 的各模块中所有 `System.getProperty`、`Integer.getInteger`、`Long.getLong` 和 `getenv` 调用
的 grep 搜索。

### 如何修改设置

1. 在下面的表格中找到该属性。
2. 在单元文件的 `ExecStart` 中、`-jar` 之前添加或修改它的 `-D…` 行，然后重启
   节点（[§7，如何修改单元文件](#how-to-change-the-unit)）。如果你是用脚本部署的，
   也可以改为带上相应参数重新运行脚本（[§5](#5-three-ways-to-install)）；它会
   根据参数重写单元文件。
3. 检查节点是否已采用该设置。启动行显示端口，`minima flags:` 行显示
   Minima 自己的参数：

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'Parlons Node|minima flags' | grep -v seed
   ```

### 如何在配置文件中传入 Minima 自己的参数

对 `mdspassword` 这类机密使用此方法，这样它们永远不会出现在命令行上。

1. 编写文件，每行一个 `key=value`。单独的 `key` 是布尔参数。省略
   开头的 `-`：

   ```bash
   cat > /var/lib/parlons-node/minima.conf <<'EOF'
   mdspassword=<your MDS password>
   EOF
   chown maxima:maxima /var/lib/parlons-node/minima.conf
   chmod 600 /var/lib/parlons-node/minima.conf
   ```

2. 在单元文件中添加 `-Dparlons.node.conf=/var/lib/parlons-node/minima.conf` 并重启
   （[§7](#how-to-change-the-unit)）。
3. 检查日志中的 `minima flags:` 行。其中 `mdspassword` 的值会被遮盖。

### 8.1 节点与区块链

| 属性 | 含义 | 默认值 | 示例 |
|---|---|---|---|
| `parlons.node.data` | 数据目录。优先级：此属性，其次是 Minima 的 `-data`（其父文件夹），再次是 `~/.parlons-node`。 | `~/.parlons-node` | `/var/lib/parlons-node` |
| `parlons.node.port` | Minima P2P 端口（公开）。派生端口随之变化：MDS 文件 `+2`，MDS 命令 `+3`，管理 RPC `+4`。 | `9001`（Minima 的默认值，除非参数中有 `-port`） | `9101` |
| `parlons.node.rootnode` | 用于发现区块链的一个 Minima 对等节点（`host:port`）。此分支自带的默认对等节点列表是**空的**，所以没有它（或 `connect`）时，节点能启动但永远不会同步。 | 空 | `seed-peer.example.net:9001` |
| `parlons.node.connect` | 固定的对等节点列表。设置它会**关闭 P2P 发现**：只使用静态对等节点。 | 空 | `a.example.net:9001,b.example.net:9001` |
| `parlons.node.megammr` | 保留完整的 MegaMMR，使 `coins`/`balance megammr:true address:<any>` 对任何地址都有效。钱包网关需要它。优先级：此属性，其次是 Minima 的 `-megammr`，再次是 `true`。 | `true` | `false` |
| `parlons.node.rpc` | 在 `127.0.0.1:<p2p+4>` 上启动回环管理 RPC。如果无法绑定，节点以退出码 2 退出。 | `false` | `true` |
| `parlons.node.args` | Minima 自己的启动参数，由 Minima 自己的解析器处理。部分参数会被拒绝（见 8.8）。环境变量后备：`PARLONS_NODE_ARGS`。 | 空 | `"-host 203.0.113.10 -archive"` |
| `parlons.node.conf` | 一个包含 Minima 参数的 `key=value` 文件，在 `parlons.node.args` **之前**读取。`mdspassword` 这类机密应放在这里，文件权限为 600。单独的 `key` 行是布尔参数。 | 空 | `/var/lib/parlons-node/minima.conf` |
| `parlons.node.passphrase.file` | 保存有密码锁定节点的口令的文件。节点在启动时尝试解锁一次。环境变量替代方式：`PARLONS_NODE_PASSPHRASE`。口令中不得包含空白字符、`"` 或 `;`。 | 空 | `/var/lib/parlons-node/passphrase.txt` |
| `parlons.node.archive` | 由已配对设备发起的**钱包重新同步**（`megammrsync action:resync host:<this>`）所使用的归档/MegaMMR 主机。 | 编译内置的项目归档节点（见 `NodeAccountWallet.java`） | `archive.example.net:9001` |
| `parlons.node.selftest.send` | **仅供开发者测试。** 启动时从节点钱包发送 `<address>[,<amount>]`（默认金额 `0.001`）。在有资金的节点上，**这会发送真实资金**。切勿在生产环境中设置。 | 空 | — |

### 8.2 中继 cape（Maxima）

| 属性 | 含义 | 默认值 | 示例 |
|---|---|---|---|
| `parlons.relay.port` | 中继端口。设为数字时，中继有自己的监听端口。`0` 关闭中继（账户随后使用节点群的中继）。`shared` 让中继共用 Minima P2P 端口：节点把每个以 Parlons 客户端身份打招呼的连接交给中继，这样只需要一个公开端口。 | `9501` | `8001`、`0`、`shared` |
| `parlons.relay.host` | 中继的公网主机名或 IP。如果未设置，节点使用从对等节点处得知的公网地址，并持续复查（在前 30 分钟内每 30 秒一次，直到获得地址，此后每 10 分钟一次，因此能跟上变化的动态 IP）。私有地址、回环地址和 CGNAT 地址永远不会被使用。 | 自动获知 | `node.example.com` |
| `parlons.relay.peers` | 网格引导列表（`host:port`，逗号分隔）。如果未设置，使用编译内置的 `Bootstrap.RELAYS` 列表。账户也把这些用作网格对等节点。 | 编译内置的列表 | `peer1.example.net:9501,peer2.example.net:8001` |
| `parlons.relay.blob` | 媒体 blob 存储区的大小，单位 MB（用于存放用户的照片）。`0` = 关闭。 | `0` | `1024` |
| `parlons.relay.maxconn` | 中继保持的最大连接数。`0` 保留内置默认值：平台线程为 512，虚拟线程（JDK 21+）为 4096。 | `0` | `8192` |

来自 `RelayServer` 的中继级属性（在节点内同样适用）：

| 属性 | 含义 | 默认值 |
|---|---|---|
| `maxima.relay.maxpersource` | 来自同一个源 IP 的最大并发连接数（位于 NAT 之后的一个家庭算作一个来源）。 | `32` |
| `maxima.relay.shed` | 客户端软目标数。超过它时，中继每个周期最多请 4 个客户端转移到别处。`0` = 从不请求。 | `384` |
| `maxima.relay.replicate` | 每条已签名的目录条目会被复制到多少个随机的已验证池对等节点。`0` = 关闭。 | `3` |
| `maxima.relay.vthreads` | 在 JDK 支持时使用虚拟线程。`false` 强制使用平台线程。 | `true` |
| `maxima.pow.enforce` | 拒绝缺少协议最低工作量证明的消息。为 false 时只做计数。环境变量：`MAXIMA_POW_ENFORCE=true`。 | `false` |
| `maxima.mls.open` | 开放解析池的默认值。**对节点无效：**节点始终开启池模式。 | — |

在代码中固定（不可配置）：协议 `1.0.48`；中继速率上限为每个目的地每分钟 600 条消息。

环境变量：`MAXIMA_STUN=false` 关闭中继端口上的 UDP STUN 应答器。

### 8.3 钱包网关与 NFT

| 属性 | 含义 | 默认值 | 示例 |
|---|---|---|---|
| `parlons.gateway.port` | 钱包网关（`/cmd`）和 NFT 服务器（`/nft`）的端口。 | `9585` | `9585` |
| `parlons.gateway.bind` | 网关监听的网络接口。请保持在 TLS 之后的回环地址上。 | `127.0.0.1` | `127.0.0.1` |
| `parlons.gateway.token` | Bearer 令牌。如果未设置，节点读取 `<data>/gateway-token.txt`，或在该处生成一个 256 位令牌（权限 600）。 | 来自文件 | — |
| `parlons.gateway.threads` | 处理线程数。每个线程在执行一条节点命令时会阻塞。 | `8` | `16` |
| `parlons.gateway.rate.global` | 全局 `/cmd` 请求速率（请求数/秒；突发量 = 2 秒的量）。`0` = 关闭。 | `50` | `100` |
| `parlons.gateway.rate.perip` | 每个 IP 的 `/cmd` 速率。只有在网关直接暴露时才有意义，因为在代理之后，所有请求都来自 127.0.0.1。 | `10` | `0` |
| `parlons.gateway.rate.nft.global` | 全局 `/nft` 速率。 | `400` | — |
| `parlons.gateway.rate.nft.perip` | 每个 IP 的 `/nft` 速率。 | `40` | — |
| `parlons.node.public` | 网关 TLS 前端的公开基础 URL，**不含** `/cmd`。它决定 NFT 的 URL。在开启了中继的 MegaMMR 节点上，它还会在中继问候中向手机通告网关。 | 空 | `https://node.example.com/parlons-node` |

### 8.4 DEX 中继 cape

| 属性 | 含义 | 默认值 | 示例 |
|---|---|---|---|
| `parlons.dex.port` | DEX 中继 cape 的端口。`0` = 关闭。 | `0` | `9602` |
| `49737`（`parlons.keets.dhtport`，仅 fleet jar） | UDP | keets HyperDHT 引导节点 | 全部 | **公开。**在防火墙中开放。 |
| `p2p+5`（`9006`，带 `-keets` 时） | TCP/HTTP | keets 回环 API（令牌在 `<data>/keets/apitoken`） | `127.0.0.1`，硬编码 | 绝不暴露。 |
| `parlons.dex.bind` | 它监听的网络接口。显式设置的值优先。如果未设置，当设置了 `parlons.dex.public` 时，cape 绑定 `127.0.0.1`，否则绑定 `0.0.0.0`。请保持在回环地址上，并用 TLS 作前端。 | 有 `parlons.dex.public` 时为 `127.0.0.1`，否则为 `0.0.0.0` | `127.0.0.1` |
| `parlons.dex.public` | 在网格中通告的公开基础 URL。如果未设置，节点一旦得知自己的公网主机，就通告 `http://<public host>:<port>`。 | 空 | `https://node.example.com/dex-relay` |

在代码中固定：DEX 中继版本 `0.1.5`；全局速率每秒 200 个请求，每个 IP 每秒 20 个请求
（这在代理之后意味着什么，见 [§16](#16-the-dex-relay-cape-dexicon)）。

### 8.5 账户与面板

| 属性 | 含义 | 默认值 | 示例 |
|---|---|---|---|
| `parlons.account` | 运行 Parlons 账户。`false` = 只做中继和网关的节点。 | `true` | `false` |
| `parlons.account.name` | 显示名称（仅在首次启动时使用）。 | 空 | `Example Node` |
| `parlons.account.relays` | 账户额外连接的中继（csv）。 | 空 | `peer1.example.net:9501` |
| `parlons.account.builtin` | `false` = 不从编译内置的中继列表初始化。账户随后从 `account.relays` 以及它记住的中继开始。 | `true` | `false` |
| `parlons.account.direct` | 账户直连（Tier-2）监听器的端口。`0` = 关闭。 | `0` | — |
| `parlons.panel.port` | 本地网页面板和 JSON API，仅在 `127.0.0.1` 上。`0` = 关闭。 | `9587` | `0` |
| `parlons.hosted.dir` | 托管（访客）账户的目录。环境变量：`PARLONS_HOSTED_DIR`。如果未设置，节点会启用托管管理模式，配合单独的租户服务使用。 | 未设置 | `/var/lib/parlons-tenants` |
| `parlons.hosted.limit` | 托管账户的最大数量。 | `10` | — |
| `parlons.restore` | 把一个可移植账户包（`.pbk`）恢复到**全新的**数据目录，然后退出。需要交互式控制台来输入口令。 | 空 | `/root/backup.pbk` |

### 8.6 MDS 与 keets

| 属性 | 含义 | 默认值 | 示例 |
|---|---|---|---|
| `parlons.mds` | 在 `https://<bind>:<p2p+2>/` 上提供 MDS（MiniHUB 和 MiniDapp）。等同于 Minima 的 `-mdsenable`。 | 关闭 | `true` |
| `parlons.mds.bind` | MDS 监听的网络接口。优先级：此属性，其次是 Minima 的 `-mdsbind`，再次是 `127.0.0.1`。`0.0.0.0` 或 `*` 表示所有接口。 | `127.0.0.1` | `127.0.0.1` |
| `parlons.node.keetschat` | `off`/`false`/`0`/`no` 会把 keets 收件箱交给宿主应用，桥接不会启动。 | `on` | `off` |

### 8.7 环境变量

| 变量 | 含义 |
|---|---|
| `PARLONS_NODE_ARGS` | `parlons.node.args` 的后备。 |
| `PARLONS_NODE_PASSPHRASE` | 锁定节点的口令（请使用权限为 600 的 `EnvironmentFile`）。 |
| `PARLONS_HOSTED_DIR` | `parlons.hosted.dir` 的后备。 |
| `MAXIMA_STUN` | `false` 禁用 UDP STUN 应答器。 |
| `MAXIMA_POW_ENFORCE` | `true` 强制执行工作量证明下限。 |

### 8.8 节点拒绝的 Minima 参数

`parlons.node.args` 或 `parlons.node.conf` 中的参数会传给 Minima 自己的解析器，但下面这些**除外**。
节点会打印原因，并在触碰数据目录之前以退出码 2 退出。
未知参数同样以退出码 2 退出。

| 参数 | 原因 | 改用 |
|---|---|---|
| `-rpc -rpcenable -rpcpassword -rpccrlf` | 原版 RPC 绑定所有网络接口，并拥有完整的管理权限。 | `-Dparlons.node.rpc=true`（仅回环地址） |
| `-seed -anyseed -dbpassword` | argv 中的机密可被所有进程读取（`ps`）。 | `--seed-from` / `--passphrase-file` |
| `-clean -genesis -test -solo -testchainlength` | 它们会清空数据目录或离开主网。 | — |
| `-daemon -noshutdownhook -jnlp -help` | 节点的 JVM 自己管理 stdin、退出和关闭钩子。 | — |

显式的 `-Dparlons.node.*` 属性在 Minima 参数之后应用，冲突时以它们为准。
启动日志以 `minima flags: …` 显示已应用的参数，其中 `mdspassword` 的值被遮盖。

### 8.9 退出码

| 退出码 | 含义 |
|---|---|
| `0` | 正常停止：通过管理 RPC 执行 `quit`，或内嵌节点在重新同步、恢复或重置后自行关闭。 |
| `2` | 拒绝启动：未知的命令行参数、被拒绝或未知的 Minima 参数、无法读取的 `-conf` 文件，或管理 RPC 无法绑定。 |
| `3` | 请求重启：来自已配对设备的钱包重新同步或身份导入。systemd 应当重启它（`RestartForceExitStatus=3`）。 |

---

## 9. 端口

| 端口（默认） | 协议 | 用途 | 绑定 | 暴露方式 |
|---|---|---|---|---|
| `9001`（`parlons.node.port`） | TCP | Minima P2P | 全部 | **公开。** 请在防火墙中开放。 |
| `p2p+2`（`9003`） | TCP/HTTPS | MDS 文件服务器 / MiniHUB（仅在 `parlons.mds=true` 时） | `127.0.0.1` | 回环地址。没有你自己的 TLS 和防火墙时，切勿公开。 |
| `p2p+3`（`9004`） | TCP | MDS 命令端口（仅在 MDS 开启时） | 跟随 MDS | 回环地址。 |
| `p2p+4`（`9005`） | TCP/HTTP | 管理 RPC（仅在 `parlons.node.rpc=true` 时） | `127.0.0.1`，硬编码 | **切勿暴露。切勿代理。** |
| `9501`（`parlons.relay.port`） | TCP | Maxima 中继 cape | 全部 | **公开。** 手机和网格对等节点连接到这里。 |
| `9501` | UDP | 中继端口上的 STUN 绑定应答器 | 全部 | 可选。部署脚本不会开放 UDP。 |
| `9585`（`parlons.gateway.port`） | TCP/HTTP | 钱包网关 `/cmd` 和 `/nft` | `127.0.0.1` | 回环地址，位于 `/parlons-node/` 的 TLS 前端之后。 |
| `9587`（`parlons.panel.port`） | TCP/HTTP | 账户面板和 API | `127.0.0.1`，硬编码 | 回环地址。通过 SSH 隧道访问。 |
| `9602`（`parlons.dex.port`，默认关闭） | TCP/HTTP | DEX 中继 cape | 设置了 `parlons.dex.public` 时为 `127.0.0.1`，**否则为 `0.0.0.0`**；`parlons.dex.bind` 可覆盖 | 回环地址，位于 `/dex-relay/` 的 TLS 前端之后。 |

需要了解的端口冲突：

- **同一台服务器上的普通 Minima 节点**已经占用 9001（以及 9005）。用
  `-Dparlons.node.port=9101` 移动 Parlons Node。它的派生端口也会随之移动（MDS 9103/9104，RPC 9105）。
- **`maxima-relay.service`**（旧的独立中继）占用 9501。请先停止并禁用它
  （部署脚本的 `--replace-relay`）。
- 节点旁边的 **`parlons-tenants` 服务**不得使用 9587。给它设置 `--panel-port 9597`；
  租户随后在回环地址上依次使用 9597、9598 等端口。
- 在只转发了一个端口的服务器上（家用路由器），请使用 `-Dparlons.relay.port=shared`，或
  选择一个你能转发的中继端口。

### 如何把节点从 9001 端口移开

当服务器上已经运行着普通 Minima 节点时使用此方法。

1. 选一个空闲的基础端口，例如 9101。检查它及其后的几个端口是否空闲：

   ```bash
   ss -ltnp | grep -E ':(9101|9103|9104|9105) '
   ```

2. 在单元文件中设置 `-Dparlons.node.port=9101` 并重启（[§7](#how-to-change-the-unit)）。使用
   部署脚本时，改为传入 `--p2p-port 9101`。
3. 开放新的 P2P 端口：

   ```bash
   ufw allow 9101/tcp
   ```

4. 在本手册的所有 `curl` 命令中改用新的管理 RPC 端口（`9105`）。

### 如何查看节点占用了哪些端口

```bash
ss -ltnp | grep java
ss -lunp | grep java
```

第一条命令列出 TCP 监听端口及其绑定地址；第二条列出 UDP STUN
应答器。

---

## 10. 数据目录结构

所有路径都相对于 `parlons.node.data`（部署脚本中默认为 `/var/lib/parlons-node`）。
该目录和每个机密文件都必须归服务用户所有，并且只有它能访问。

| 路径 | 内容 | 是否机密？ |
|---|---|---|
| `<base version>/` | Minima 节点自己的数据：数据库、钱包、区块链。MegaMMR 文件是 `<base version>/databases/megammr.mmr`（部署脚本使用路径 `1.1/databases/megammr.mmr`）。 | **是。** 其中有钱包。 |
| `identity.txt` | 固定保存的身份助记词（或助记词十六进制），Maxima 身份由它派生。权限 600。 | **是。它就是助记词。** |
| `identity-anyphrase.txt` | 自定义（非 BIP39）身份短语，在导入过时存在。 | **是。** |
| `gateway-token.txt` | 钱包网关 bearer 令牌。权限 600。 | 低（仅能读取和转发，且会向手机通告；见 [§14](#14-the-wallet-gateway-and-its-token)）。 |
| `pair-code.txt` | 当前尚未使用的一次性配对码。 | **是**，直到被使用。 |
| `account.txt` | 账户的永久地址（`MAX#…`）。 | 否。 |
| `invite.txt` | 地址加一次性配对码。 | **是**，直到配对码被使用。 |
| `panel-ticket.txt` | 本地面板的一次性登录链接。 | **是。** |
| `local-device.key` | 面板的本地设备密钥。删除它并重启，可重新启用你曾撤销的面板。 | **是。** |
| `devices.json` | 已配对的设备。 | 私密。 |
| `cloud-settings.properties` | 账户设置。 | 私密。 |
| `node/`、`chat/`、`media/`、`private-files/` | 账户存储、聊天记录、媒体。 | 私密。 |
| `relay/` | 中继状态：目录、信箱条目、blob 存储区。 | 私密。 |
| `nft/` | 托管的 NFT 文件（`<sha256>.<ext>`、`c/<16-hex>/…`），以及存放上传中文件的 `tmp/`。 | 公开内容。 |
| `account-history/<import-id>/previous/` | 身份导入之后的旧身份。 | **是。** |
| `.seed-adopted` | 部署脚本的 `--seed-from` 写入的标记。 | 否。 |

### 如何检查数据目录的权限

1. 列出目录和机密文件的所有者与权限：

   ```bash
   ls -ld /var/lib/parlons-node
   ls -l /var/lib/parlons-node/*.txt
   ```

2. 目录必须是 `drwx------ maxima maxima`，每个 `.txt` 机密文件必须是 `-rw------- maxima maxima`。
   如果不是，请修正：

   ```bash
   chown -R maxima:maxima /var/lib/parlons-node
   chmod 700 /var/lib/parlons-node
   chmod 600 /var/lib/parlons-node/*.txt
   ```

---

## 11. TLS 前端：Caddy、nginx、Apache

网关（9585）和 DEX cape（9602）在回环地址上使用明文 HTTP。同一台服务器上的
Web 服务器负责终止 TLS，并转发这两个路径：

| 公开路径 | 转发到 | 前缀 |
|---|---|---|
| `https://node.example.com/parlons-node/…` | `http://127.0.0.1:9585/…` | 去掉 `/parlons-node`（这样 `/parlons-node/cmd` 到达 `/cmd`，`/parlons-node/nft/…` 到达 `/nft/…`） |
| `https://node.example.com/dex-relay/…` | `http://127.0.0.1:9602/…` | 去掉 `/dex-relay`（这样 `/dex-relay/dex/health` 到达 `/dex/health`） |

然后把 `parlons.node.public` 和 `parlons.dex.public` 设为公开 URL（见下面第 4 步）。

切勿转发管理 RPC（`p2p+4`）、面板（9587）或 MDS。

需要注意的大小和超时：

- 网关接受最大 1 MiB 的请求体（已签名的 `txnimport` 最大），并发送
  最大 12 MiB 的响应。
- DEX 长轮询最多保持 25 秒。订单信封最大 16 KiB；消息
  信封最大 256 KiB。
- 大多数代理的默认读取超时（60 秒）已经足够。如果你调低过它，请保持在 30 秒以上。

### 如何设置任意一种 TLS 前端

1. 把一个 DNS 名称（这里是 `node.example.com`）指向服务器，并开放 TCP 443（以及用于
   证书验证的 80）：

   ```bash
   ufw allow 80/tcp
   ufw allow 443/tcp
   ```

2. 检查节点的两个 HTTP 服务在回环地址上是否有响应（DEX 服务仅在你开启它时）：

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:9585/cmd   # 401 = gateway up
   curl -s http://127.0.0.1:9602/dex/health
   ```

3. 按照下面的步骤配置 Caddy、nginx 或 Apache 中的一种。
4. 在单元文件中设置公开 URL 并重启节点（[§7](#how-to-change-the-unit)）：

   ```
   -Dparlons.node.public=https://node.example.com/parlons-node
   -Dparlons.dex.public=https://node.example.com/dex-relay
   ```

5. 从另一台机器测试：

   ```bash
   curl -s https://node.example.com/dex-relay/dex/health
   curl -s -o /dev/null -w '%{http_code}\n' -X POST https://node.example.com/parlons-node/cmd   # 401
   ```

### Caddy

Caddy 会自行获取并续期证书。

#### 如何用 Caddy 作节点前端

1. 从官方软件包安装 Caddy（<https://caddyserver.com/docs/install>）。
2. 备份 Caddyfile：

   ```bash
   cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.before-parlons-node
   ```

3. 在 `/etc/caddy/Caddyfile` 中你的主机名对应的站点块里加入以下块：

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

4. 检查文件，然后重新加载 Caddy：

   ```bash
   caddy validate --config /etc/caddy/Caddyfile
   systemctl reload caddy
   ```

5. 从另一台机器测试（[如何设置任意一种 TLS 前端](#how-to-set-up-any-tls-front)的第 5 步）。
   如果 `/dex-relay/dex/health` 返回的是你的网页而不是 JSON，说明你遇到了下面的陷阱。

Caddy 的 `reverse_proxy` 默认发送 `X-Forwarded-For`，所以 DEX cape 会对每个
客户端分别限速。

> **Caddy 排序陷阱：`handle_path` 与 `handle`。** Caddy 在应用指令之前，会按固定的指令
> 顺序对它们排序，而 `handle` 排在 `handle_path` **之前**。在多个 `handle` 块之间，
> 路径匹配更具体的排在前面，没有匹配器的 `handle` 排在最后。`handle`
> 块互相排斥：第一个匹配的块生效。所以，如果站点块中有一个
> 兜底的 `handle { … }`，`handle_path /dex-relay/*` 会排在它**之后**，永远不会被执行：
> 兜底块会应答所有 DEX 请求。解决方法：写成 `handle /dex-relay/* { uri strip_prefix
> /dex-relay; reverse_proxy … }`，它会和其他 `handle` 块一起按路径排序。在
> 没有兜底 `handle` 的站点块中，`handle_path /dex-relay/*` 可以正常工作。

#### 如何在 Caddy 中添加按客户端限速（可选）

在代理之后，节点自己的按 IP 网关令牌桶只能看到 `127.0.0.1`，所以请改在 Caddy 中
对每个客户端限速。这需要第三方 `rate_limit` 模块，它不属于原版 Caddy。

1. 构建或下载一个包含 `rate_limit` 模块的 Caddy 程序。
2. 把网关块替换为：

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

3. 按照上面第 4 步和第 5 步验证并重新加载。

### nginx

#### 如何用 nginx 作节点前端

1. 为主机名获取证书（例如用 certbot），并为它准备一个
   `server { listen 443 ssl; server_name node.example.com; … }` 块。
2. 备份你将要修改的站点文件：

   ```bash
   cp /etc/nginx/sites-available/<your site> /etc/nginx/sites-available/<your site>.before-parlons-node
   ```

   如果服务器上有监视 nginx 配置的文件完整性监控，编辑会触发它。修改文件前，
   请先告知该监控的负责人。
3. 在 `server { … }` 块内加入以下块：

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

4. 检查配置，然后重新加载 nginx：

   ```bash
   nginx -t
   systemctl reload nginx
   ```

5. 从另一台机器测试（[如何设置任意一种 TLS 前端](#how-to-set-up-any-tls-front)的第 5 步）。

说明：

- `/dex-relay/` 块中需要 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`。
  nginx 默认不发送这个请求头，没有它，所有 DEX 客户端会共用一个
  限速令牌桶（[§16](#16-the-dex-relay-cape-dexicon)）。
- `client_max_body_size 2m` 在网关 1 MiB 请求体上限之上留出余量（nginx 的默认值是
  `1m`）。

### Apache

#### 如何用 Apache 作节点前端

1. 为主机名准备一个 TLS 虚拟主机（`<VirtualHost *:443>`），例如通过 certbot。
2. 启用代理模块：

   ```bash
   a2enmod proxy proxy_http
   ```

3. 把以下内容放进一个由 TLS 虚拟主机引入的配置片段中：

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

4. 检查配置，然后重新加载 Apache：

   ```bash
   apachectl configtest
   systemctl reload apache2
   ```

5. 从另一台机器测试（[如何设置任意一种 TLS 前端](#how-to-set-up-any-tls-front)的第 5 步）。

Apache 的 `ProxyPass` 默认发送 `X-Forwarded-For`。对于网关，在 Apache 之后，节点自己的全局
令牌桶就是速率限制。

---

## 12. 中继 cape 与网格

### cape 的作用

cape 是一个完整的 Maxima 中继。它在线路上逐字节地使用经典 Maxima 协议，并加上
Parlons 扩展：存储转发信箱、目录、送达回执、gossip 和网格。手机
没有公网地址，所以它主动连接 cape 并保持连接；发给它的消息会沿着这条连接
回传。普通 Minima 节点也可以把 cape 用作自己的 Maxima 主机。

cape 始终以**池主机**身份运行：一个开放解析的目录主机和一个网格转发器。

### 身份

cape 的身份（`Mx…`）由 `identity.txt` 派生，而该文件在首次启动时由节点的助记词
固定生成。以前运行过 `maxima-server.jar` 的服务器可以保留其中继身份：部署脚本的
`--seed-from /var/lib/maxima/seed.txt` 会把节点钱包重新同步到该助记词并固定下来。

### 公网主机

cape 必须知道自己的公网地址。它用这个地址在对等节点列表中标识自己、避免转发给
自己、锚定账户的永久地址，并构建它所通告的 DEX URL。查找顺序如下：

1. `-Dparlons.relay.host`，如果你设置了它。
2. 否则，使用 Minima 节点从对等节点处得知的公网地址
   （`network` → `details.p2p.address`），然后是 `status` → `network.host`。
3. 如果两者都还不是公网地址，一个后台线程会在前 30 分钟内每 30 秒复查一次，
   此后每 10 分钟一次。找到地址后，它会记录
   `public address learned from peers: <ip> - the cape now names itself <ip>:<port>`。

切勿把中继主机设为 `0.0.0.0` 这类绑定地址。经典 Minima 把 `0.x`
地址视为内部地址，会拒绝整个中继。

### Gossip 与已验证的对等节点

来自 `core/.../session/RelayPeers.java`：

- 对等节点只能提名**自己**。只有当声明的主机等于连接的源 IP 时，问候中的声明才会被接受。
- 每个声明都要**通过回拨验证**（连接 4 秒，读取 2 秒）。只有收到真正的 Maxima 问候，
  对等节点才会被提升。
- 从其他中继的问候中得知的对等节点，在使用前同样要回拨验证。
  非公网地址会被跳过。
- 最多保存 **250** 个已验证的对等节点（`MAX_PEERS`）。每次问候分享一个最多
  **50** 个的乱序列表（`SHARE_LIMIT`）。
- **TTL 2 小时**（`TTL_MS`）。一个已验证的对等节点如果在 2 小时内没有被重新声明或成功重新回拨，
  就会过期。
- **每 10 分钟重新回拨**（`REDIAL_MS`）。当队列空闲时，检查
  线程会重新回拨每个 10 分钟内未拨过的已验证对等节点。这样能让每个对等节点
  通告的信息（池标志、DEX URL、网关）保持最新，成功的重新回拨会刷新
  TTL。失效的对等节点拨号失败后会过期。

### 网格与 `parlons.relay.peers`

- `parlons.relay.peers` 是一个**引导**列表，不是信任列表，也不是单点故障。
  信任来自发布者的签名，所以失效或恶意的对等节点只会被跳过。
- 目录未命中时，中继把查询转发给它的池对等节点。转发严格只有一跳，
  扇出上限为 8。中继验证签名后的答复，短暂缓存后再回复。
- 每条被接受的已签名目录条目会被复制到 `maxima.relay.replicate`（默认 3）个随机池
  对等节点，所以即使某地址的主中继宕机，它仍能被解析。
- 如果没有设置 `parlons.relay.peers`，使用编译内置的引导列表。日志会显示
  `mesh: N bootstrap peer(s) (default fleet list)`。
- 把每个你希望确保能连通的其他中继，以 `host:port` 形式列出。使用每个对等节点的
  中继实际监听的端口；家用服务器可能使用 8001 而不是 9501。

### 容量

- 默认连接上限：平台线程为 512，虚拟线程（JDK 21+）为 4096。可用
  `parlons.relay.maxconn` 覆盖。中继在启动时会记录它使用的模式和上限。
- 每个源 IP 的上限：32（`maxima.relay.maxpersource`）。
- 未注册的连接（始终没有成为客户端的连接）在 10 分钟后被断开。空闲的
  未注册连接在 120 秒后被断开。
- 当已注册客户端超过 `maxima.relay.shed`（384）时，中继每个周期最多请 4 个客户端
  转移到另一个中继。去哪里由客户端自己选择。

### 共用端口模式

使用 `-Dparlons.relay.port=shared` 时，中继没有自己的监听端口。Minima P2P 监听器
会把每个以 Parlons 客户端身份打招呼的连接交给它。这样一个公开端口就同时服务于
区块链和中继。桌面应用在开启“贡献”时使用这种模式。节点群的服务器保持显式的 9501。

### 如何检查 cape 是否已运行

1. 在本次启动的日志中查找 cape 和网格相关的行：

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'Maxima cape|mesh:|public address learned|own relay' | grep -v seed
   ```

   你应当看到 `Maxima cape up on port 9501 — identity Mx…` 和 `mesh: N bootstrap peer(s)`。
2. 检查心跳。它必须显示 `cape=up:9501`：

   ```bash
   journalctl -u parlons-node --since -2min --no-pager | grep heartbeat | tail -1
   ```

3. 从另一台机器做端到端测试（[§23](#23-verifying-from-outside)，第 2 步）。

### 如何设置网格引导对等节点

1. 收集你想连接的每个中继的 `host:port`。使用每个中继实际
   监听的端口。
2. 在单元文件中把它们设为一个逗号分隔的值，然后重启（[§7](#how-to-change-the-unit)）：

   ```
   -Dparlons.relay.peers=peer1.example.net:9501,peer2.example.net:9501,peer3.example.net:8001
   ```

   使用部署脚本时，传入 `--peers "peer1.example.net:9501,peer2.example.net:9501"`。
3. 如果服务器默认阻止出站流量，请放行每个对等节点的中继端口
   （[§25](#25-firewall-notes)）。
4. 检查启动日志。它必须显示 `mesh: N bootstrap peer(s)`，且数量与你设置的一致：

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'mesh:'
   ```

### 如何设置公网主机

当日志显示 `own relay NOT advertised`，或服务器有一个你希望 cape 使用的固定名称时，
请这样做。

1. 把公网主机名或 IP 加入单元文件，然后重启（[§7](#how-to-change-the-unit)）：

   ```
   -Dparlons.relay.host=node.example.com
   ```

   切勿使用 `0.0.0.0` 这类绑定地址，也不要使用私有、回环或 CGNAT 地址。
2. 检查警告是否已消失：

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'own relay NOT advertised' || echo advertised-ok
   ```

### 如何在 Minima P2P 端口上运行中继（共用端口模式）

1. 在单元文件中把中继端口设为 `shared`，然后重启（[§7](#how-to-change-the-unit)）：

   ```
   -Dparlons.relay.port=shared
   ```

2. 只开放（或转发）P2P 端口，例如 9001。如果不再需要 9501 的规则，请删除它：

   ```bash
   ufw delete allow 9501/tcp
   ```

3. 告诉你的用户和网格对等节点，用 `node.example.com:9001` 作为这个中继的地址。

### 如何开启媒体 blob 存储区

1. 在单元文件中设置存储区大小（单位 MB），然后重启（[§7](#how-to-change-the-unit)）。使用
   部署脚本时，传入 `--blobstore 1024`：

   ```
   -Dparlons.relay.blob=1024
   ```

2. 确认磁盘有这么多可用空间（`df -h /var/lib`）。
3. 检查启动日志。它必须显示 `blob shelf: 1024 MB`：

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'blob shelf'
   ```

### 如何提高连接上限

1. 在 JDK 21 或更高版本上运行节点。上限会自动提高到 4096。
2. 如需自定义上限，把它加入单元文件并重启（[§7](#how-to-change-the-unit)）：

   ```
   -Dparlons.relay.maxconn=8192
   ```

3. 如果上限接近 `LimitNOFILE`，也请在单元文件中调高它（单元文件设为 65536）。

### 如何关闭中继

在单元文件中设置 `-Dparlons.relay.port=0` 并重启。账户随后使用其他中继。请在防火墙中关闭
中继端口。

---

## 13. MegaMMR 模式与 rootnode

### Rootnode

此节点分支自带一个**空的默认对等节点列表**。请给它以下其中一种：

- `-Dparlons.node.rootnode=host:port`：用于发现的一个 Minima 对等节点。节点随后会自行找到更多
  对等节点。使用任意一个你信任且能连通的 Minima 节点。`--help` 会打印项目推荐的
  rootnode。
- `-Dparlons.node.connect=host:port,…`：固定列表，关闭发现功能。
- 在 `parlons.node.args` 中使用 Minima 自己的 `-connect` 或 `-nop2p` 参数。

如果一个都没有，节点能启动并提供本地服务，但 `block=` 永远不会变化。

#### 如何设置 rootnode

1. 打印项目推荐的 rootnode，或选一个你信任的 Minima 节点：

   ```bash
   java -jar /opt/maxima/parlons-node.jar --help | grep -i rootnode
   ```

2. 检查服务器能否连通它（替换主机和端口）：

   ```bash
   nc -z -w 8 seed-peer.example.net 9001 && echo reachable
   ```

3. 把它加入单元文件，然后重启（[§7](#how-to-change-the-unit)）。使用部署脚本时，传入
   `--rootnode seed-peer.example.net:9001`：

   ```
   -Dparlons.node.rootnode=seed-peer.example.net:9001
   ```

4. 在心跳中观察 `block=` 增长：

   ```bash
   journalctl -u parlons-node -f | grep heartbeat
   ```

### MegaMMR

在本节点上，MegaMMR **默认开启**；Minima 自己的默认值是关闭。有了 MegaMMR，节点
可以为**任何**地址证明币（`coins`/`balance megammr:true address:<any>`）。正是这一点
让它能为手机充当钱包网关。

**初始同步不会填充 MegaMMR。** 刚同步完的节点的 MegaMMR 是空的（约 20
字节），只跟踪从那时起新创建的币。在你导入快照之前，已有地址的余额会返回
**空值**。

#### 如何导入 MegaMMR 快照

在首次同步后做一次。以 root 身份在服务器上运行这些命令。

1. 确认管理 RPC 已开启（`-Dparlons.node.rpc=true`，[§21](#21-the-admin-rpc)）。下面的命令
   必须输出一段 JSON 状态：

   ```bash
   curl -s http://127.0.0.1:9005/status | head -c 200; echo
   ```

2. 检查当前 MegaMMR 的大小。小于 1 MB 表示它是空的：

   ```bash
   stat -c %s /var/lib/parlons-node/1.1/databases/megammr.mmr
   ```

3. 把快照下载到数据目录，并交给服务用户（约 460 MB；
   `-c` 可续传中断的下载）：

   ```bash
   cd /var/lib/parlons-node
   wget -c -O mega.mmr https://eurobuddha.com/mega.mmr
   chown maxima:maxima mega.mmr
   ```

   如果要改用你自己的快照，请看下一个步骤。
4. 通过回环 RPC 导入它（P2P 端口 9001 意味着 RPC 是 9005）：

   ```bash
   curl -s -m 3600 'http://127.0.0.1:9005/megammr%20action:import%20file:/var/lib/parlons-node/mega.mmr'
   ```

   它必须回复 `"status":true`。导入结束时节点会关闭。
5. 让节点重新运行。`Restart=always` 会在 10 秒后重启它；为保险起见，运行：

   ```bash
   systemctl restart parlons-node
   ```

6. 等待约 20 秒，然后检查 `megammr.mmr` 现在是否超过 1 MB：

   ```bash
   stat -c %s /var/lib/parlons-node/1.1/databases/megammr.mmr
   ```

7. 查询一个你确定有资金的地址。它的余额不能为空：

   ```bash
   curl -s 'http://127.0.0.1:9005/balance%20megammr:true%20address:<a funded Mx or 0x address>'
   ```

8. 删除下载的文件：

   ```bash
   rm -f /var/lib/parlons-node/mega.mmr
   ```

部署脚本的 `--rpc --megammr-seed <URL>` 会执行以上所有步骤，并在
MegaMMR 已有数据时跳过它们。

#### 如何制作你自己的快照

1. 在任意一个你控制的、已开启管理 RPC 的 MegaMMR 节点上，导出 MegaMMR。这只需读
   锁，不需要停机：

   ```bash
   curl -s -m 3600 'http://127.0.0.1:9005/megammr%20action:export%20file:/var/lib/parlons-node/donor.megammr'
   ```

2. 把文件复制到新节点的数据目录，并交给服务用户：

   ```bash
   scp /var/lib/parlons-node/donor.megammr root@node.example.com:/var/lib/parlons-node/mega.mmr
   ssh root@node.example.com chown maxima:maxima /var/lib/parlons-node/mega.mmr
   ```

3. 删除源节点上的导出文件，然后从上面步骤的第 4 步开始，在新节点上导入。

#### 如何运行不带 MegaMMR 的节点

1. 在单元文件中添加 `-Dparlons.node.megammr=false`（或给部署脚本传入 `--no-megammr`）。
2. 相应调低堆（2 GB 的服务器上为 768 MB，[§3](#3-requirements)），并把 `MemoryMax`
   设为比它高 512 MB。
3. 重启（[§7](#how-to-change-the-unit)）。节点将无法再为其他地址充当钱包网关。

**如果你同时要采用一个助记词，顺序很重要：**先运行 `--seed-from`，**再**导入 MegaMMR。
`megammrsync` 会重置区块链数据库，并丢弃已导入的 MegaMMR。

`megammr=false` 的节点无法提供 `megammr:true` 读取。它的网关对这类请求回复
HTTP 503（`this node has no MegaMMR - use a MegaMMR gateway node`），于是手机会切换到
另一个网关。

---

## 14. 钱包网关及其令牌

### 接口约定

```
POST /cmd
Authorization: Bearer <token>
Content-Type: application/json

{"command":"balance megammr:true address:Mx..."}
```

回复是节点自己的 JSON。错误以 `{"status":false,"error":"…"}` 返回，并带有以下 HTTP 状态码：

| 状态码 | 含义 |
|---|---|
| 400 | JSON 请求体有误，或命令为空 |
| 401 | 缺少 bearer 令牌或令牌错误 |
| 403 | 命令不在允许列表中，或 `coins`/`balance` 没有带 `address:` |
| 405 | 不是 POST 请求 |
| 429 | 超出速率限制（在身份验证**之前**检查） |
| 500 | 网关内部错误（详情只在节点日志中） |
| 502 | 节点响应超过 12 MiB |
| 503 | 在没有 MegaMMR 的节点上使用 `megammr:true` |

### 允许列表

只有以下命令可以通过。每个以 `;` 分隔的片段都会被检查，因为节点会把每个
片段当作独立的命令来执行：

- **读取：** `status`、`tokens`、`balance`、`coins`、`checkaddress`、`tokenvalidate`、`block`
- **仅跟踪**（无法转移资金）：`newscript`、`coinexport`、`coinimport`
- **转发手机自己签名的交易：** `txnimport`、`txnbasics`、`txnpost`、`txndelete`、`txncheck`

其他一切都会被拒绝：`send`、`vault`、`keys`、`sign`、`quit` 等等。`coins` 和
`balance` 必须带 `address:`，这样就没有人能通过网关列出运营者自己的币。

### 令牌

- 优先级：`-Dparlons.gateway.token`，其次是 `<data>/gateway-token.txt`，再次是一个新的随机
  256 位令牌，以权限 600 写入该文件。
- 该令牌**只能读取和转发**。它无法转移节点的资金。
- **它对手机来说并不是秘密。** 在设置了 `parlons.node.public` 的 MegaMMR 节点上，中继
  问候会通告网关 URL（`<public>/cmd`）及其密钥，这样手机就能在发现中继的同时
  发现网关。日志会显示 `wallet gateway advertised to phones: <url>`。
- 一组网关可以共用一个令牌：把同一个 `gateway-token.txt` 复制到每台服务器。

### 速率限制

- 全局令牌桶：每秒 50 个请求，突发 100（`parlons.gateway.rate.global`）。这是 TLS 前端之后
  真正的最后防线。
- 每个 IP 的令牌桶：每秒 10 个请求（`parlons.gateway.rate.perip`）。在回环代理之后，所有
  请求都来自 `127.0.0.1`，所以请在代理中按客户端限速。
- `/nft` 有自己独立的令牌桶（全局 400，每个 IP 40）。

### 如何为手机发布网关

1. 以开启 MegaMMR 的方式运行节点，并导入 MegaMMR（[§13](#13-megammr-mode-and-the-rootnode)）。
2. 让网关保持在回环地址上（`-Dparlons.gateway.bind=127.0.0.1`，默认值），并用 TLS 作
   `/parlons-node/` 的前端（[§11](#11-tls-fronting-caddy-nginx-apache)）。
3. 在单元文件中设置公开基础 URL（不含 `/cmd`）并重启（[§7](#how-to-change-the-unit)）：

   ```
   -Dparlons.node.public=https://node.example.com/parlons-node
   ```

4. 检查节点是否在通告它：

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'wallet gateway (up|advertised|FAILED)'
   ```

   你应当看到 `wallet gateway advertised to phones: https://node.example.com/parlons-node/cmd`。
5. 从另一台机器测试它（见下一个步骤）。

### 如何测试网关

1. 在服务器上把令牌读入一个 shell 变量。不要把它粘贴到共享聊天或工单中：

   ```bash
   TOKEN=$(sudo cat /var/lib/parlons-node/gateway-token.txt)
   ```

2. 发送一条 `block` 命令。它必须返回带 `"status":true` 的节点 JSON：

   ```bash
   curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        -d '{"command":"block"}' https://node.example.com/parlons-node/cmd
   ```

3. 不带令牌发送。它必须返回 HTTP 401：

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' \
        -d '{"command":"block"}' https://node.example.com/parlons-node/cmd
   ```

4. 发送一条会被拒绝的命令。它必须返回 HTTP 403：

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' -d '{"command":"vault"}' \
        https://node.example.com/parlons-node/cmd
   ```

### 如何在多个网关之间共用一个令牌

1. 把 `gateway-token.txt` 从第一台服务器复制到其他每台服务器：

   ```bash
   scp root@node.example.com:/var/lib/parlons-node/gateway-token.txt ./gateway-token.txt
   scp ./gateway-token.txt root@node2.example.com:/var/lib/parlons-node/gateway-token.txt
   rm ./gateway-token.txt
   ```

2. 在每台收到它的服务器上，修正所有者和权限，然后重启：

   ```bash
   chown maxima:maxima /var/lib/parlons-node/gateway-token.txt
   chmod 600 /var/lib/parlons-node/gateway-token.txt
   systemctl restart parlons-node
   ```

### 如何更换令牌

1. 删除令牌文件并重启。节点在启动时会写入一个新的 256 位令牌：

   ```bash
   rm /var/lib/parlons-node/gateway-token.txt
   systemctl restart parlons-node
   ```

2. 等到钱包就绪（日志中出现 `wallet gateway up`），然后读取新令牌：

   ```bash
   sudo cat /var/lib/parlons-node/gateway-token.txt
   ```

3. 把新令牌交给所有曾手动配置旧令牌的程序。通过中继问候发现网关的手机
   会在问候中收到新密钥。

如果单元文件中设置了 `-Dparlons.gateway.token`，它优先于文件：请改为在那里修改。

### 如何修改网关速率限制

1. 把你想修改的属性加入单元文件（数值单位为每秒请求数；`0`
   关闭某个 `/cmd` 令牌桶）：

   ```
   -Dparlons.gateway.rate.global=100
   -Dparlons.gateway.rate.perip=0
   ```

2. 重启（[§7](#how-to-change-the-unit)）。
3. 在 TLS 前端之后，请改为在代理中对每个客户端限速
   （[§11，Caddy](#how-to-add-per-client-rate-limiting-in-caddy-optional)）。

---

## 15. NFT 托管与 `parlons.node.public`

节点提供代币元数据所链接的文件，这样交易市场或区块浏览器可以
直接链接到你的服务器。

- **存储：** `<data>/nft/`。
  - 单个文件采用**内容寻址**：`<sha256>.<ext>`。URL 永远不变，任何人
    都可以对照哈希核验文件内容。
  - 合集：`c/<16-hex id>/<index>.<ext>`（索引 0 到 9999），并附带一个 `manifest.json`，
    列出每一项的 sha256。
  - 每个文件最大 **32 MB**。扩展名为 2 到 5 个小写字母或数字。
  - `tmp/` 中被放弃的上传会在 24 小时后删除。
- **上传：** 只限已配对设备，通过加密控制通道（`parlons.nft.put`、
  `parlons.nft.newcollection`、`parlons.nft.list`、`parlons.nft.delete`）。每次上传都分块
  发送，并在放置到位之前对照其 sha256 进行校验。没有公开的上传端点。
- **提供访问：** 网关端口上的 `GET`/`HEAD /nft/<path>`。不需要令牌。只有上述
  两种形式的路径可以访问。响应带有：
  - `Cache-Control: public, max-age=31536000, immutable`
  - `X-Content-Type-Options: nosniff`
  - `Access-Control-Allow-Origin: *`
  - `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox`，这样直接打开的
    SVG 无法在你的域名上运行脚本
  - `Content-Disposition: inline`
- **公开基础地址：** `-Dparlons.node.public=https://node.example.com/parlons-node`。文件的公开 URL
  是 `<public>/nft/<path>`。
  - 没有它，上传仍会成功，但返回的 `url` 为空。日志会显示
    `nft hosting: … (no public base - set -Dparlons.node.public=https://host/parlons-node)`。
  - **只能把它设为真正路由到本服务器 9585 端口的 URL。** 从另一台服务器复制来的 URL
    会让钱包发布指向一台并不存有这些文件的服务器的链接。
  - 在 MegaMMR 节点上，同一个属性还会向手机通告网关（[§14](#14-the-wallet-gateway-and-its-token)）。
- 内容类型根据扩展名设置：png、jpg/jpeg、gif、webp、svg、avif、mp4、webm、mp3、
  json、txt、glb。其他类型均为 `application/octet-stream`。

### 如何开启 NFT 托管

NFT 托管始终开启；它只需要一个公开 URL。

1. 用 TLS 把 `/parlons-node/` 前置到 `http://127.0.0.1:9585/`
   （[§11](#11-tls-fronting-caddy-nginx-apache)）。
2. 在单元文件中设置公开基础地址并重启（[§7](#how-to-change-the-unit)）：

   ```
   -Dparlons.node.public=https://node.example.com/parlons-node
   ```

3. 检查日志行：

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'nft hosting'
   ```

   它必须以 `served at https://node.example.com/parlons-node/nft/<file>` 结尾，而不是
   `(no public base …)`。
4. 从已配对设备上传一个文件。上传会返回该文件的公开 `url`。
5. 从另一台机器检查该文件。它必须返回 `200`：

   ```bash
   curl -sI https://node.example.com/parlons-node/nft/<sha256>.<ext> | head -1
   ```

### 如何查看和删除托管的文件

1. 列出节点保存的内容：

   ```bash
   ls -l /var/lib/parlons-node/nft/ /var/lib/parlons-node/nft/c/
   ```

2. 从已配对设备删除文件（`parlons.nft.delete`）。合集的 `manifest.json` 列出了
   每一项，所以不要手动删除合集文件。
3. 检查磁盘占用：

   ```bash
   du -sh /var/lib/parlons-node/nft
   ```

---

## 16. DEX 中继 cape（Dexicon）

DEX 中继 cape（`dexrelay/`，版本 `0.1.5`，无依赖）是 Dexicon 的订单簿中继。
它运行在节点**内部**，不是单独的服务。它在设计上就是不可信的：

- 每个订单和消息都是一个**已签名的信封**（Ed25519），中继在接受之前会检查签名。
- 被接受的信封会逐字节转发，所以每个客户端检查的都是中继检查过的同一份字节。
- **不保存任何内容到磁盘。** 重启后的中继从空白开始，做市方会在下一个周期重新发布。
- 没有身份验证，CORS 是开放的，因为每条消息都能自行证明身份。

### 如何开启 DEX 中继 cape

一行命令安装程序（[§5 A](#5-three-ways-to-install)）已经完成了这一切：它把 `parlons.dex.public` 设为 `https://<名称>/dex-relay`（fleet 模式随即在回环 9602 上开启该 cape），并用 Caddy 做前端。下面的步骤适用于以其他方式安装的节点。

1. 把这三个属性加入单元文件，然后重启（[§7](#how-to-change-the-unit)）：

   ```
   -Dparlons.dex.port=9602
   -Dparlons.dex.bind=127.0.0.1
   -Dparlons.dex.public=https://node.example.com/dex-relay
   ```

   使用部署脚本时，传入 `--dex 9602 --dex-public https://node.example.com/dex-relay`。
   脚本会替你设置回环绑定。
2. 把 `/dex-relay/` 前置到 `http://127.0.0.1:9602/`（[§11](#11-tls-fronting-caddy-nginx-apache)）。
   使用 nginx 时，请加上 `X-Forwarded-For` 那一行。
3. 检查启动日志：

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'dex relay'
   ```

   你应当看到这两行：

   ```
   [parlons-node] dex relay 0.1.5 up on 127.0.0.1:9602 (GET /dex/health)
   [parlons-node] dex relay announced in the mesh as https://node.example.com/dex-relay (GET /dex/relays)
   ```

   如果还不知道公开 URL 或主机，第二行会显示为
   `dex relay: no public host known yet - NOT announced in the mesh until one is learned …`。
4. 检查 cape 是否只在回环地址上监听。地址必须是 `127.0.0.1:9602`：

   ```bash
   ss -ltnp | grep ':9602 '
   ```

5. 从另一台机器检查它（见下面的[检查](#check-it)）。

**绑定地址。** 显式设置的 `parlons.dex.bind` 始终优先。如果未设置，当设置了 `parlons.dex.public` 时，
cape 绑定 `127.0.0.1`，没有设置时绑定 `0.0.0.0`。所以，如果你只设置了
`parlons.dex.port`，cape 会以明文 HTTP 应答整个互联网。请设置 `parlons.dex.public`
（为明确起见，再加上 `parlons.dex.bind=127.0.0.1`），并在前面加上 TLS。

### 如何关闭 DEX 中继 cape

1. 从单元文件中删除这三个 `parlons.dex.*` 属性（或设置 `-Dparlons.dex.port=0`），然后
   重启（[§7](#how-to-change-the-unit)）。
2. 从 TLS 前端删除 `/dex-relay/` 块，并重新加载它。

### API

| 方法和路径 | 用途 | 响应 |
|---|---|---|
| `POST /dex/publish` | 发布一个订单信封（最大 16 KiB）。 | `200 {"ok":true,"seq":…,"cursor":…}` · `400` 无效 · `409` seq 过旧 · `413` 太大 · `503` 中继已满 |
| `GET /dex/book?market=&since=&hold=` | 自某个游标以来的订单簿变化。长轮询最多 `hold` 秒（最多 25）。`since=0` 返回完整快照（`reset:true`）。 | `200 {"cursor":…,"reset":…,"orders":[…]}` · `503` 繁忙 |
| `POST /dex/msg` | 投递一条密封的交易消息（最大 256 KiB）。 | `200 {"ok":true}` · `400` · `413` · `503` 收件箱已满 |
| `GET /dex/inbox?pk=&since=&hold=` | 发给某个公开 id 的消息（`pk` 必须是 `0x` 加 64 字节的十六进制）。长轮询最多 25 秒。 | `200 {"cursor":…,"messages":[…]}` · `400` · `503` 繁忙 |
| `GET /dex/health` | 存活检查。 | `{"ok":true,"version":"0.1.5","orders":<n>,"cursor":<n>,"uptime":<s>}` |
| `GET /dex/relays` | 本主机通过中继网格得知的所有 DEX 中继 URL，包括它自己。 | `{"ok":true,"relays":[{"url":"…","seen":<epoch s>}]}` |

其他任何路径都返回 404。触发速率限制时返回 429 和 `{"ok":false,"error":"rate limited"}`。

### 限制（在代码中固定）

| 限制项 | 值 |
|---|---|
| 活跃订单 | 20,000（超过后返回 `503 relay full`） |
| 总共保存的消息 | 20,000 |
| 每个接收者的消息 | 200 |
| 并发长轮询 | 512 |
| 最长长轮询 | 25 秒 |
| 订单过期时间 | 最多 24 小时之后 |
| 消息过期时间 | 最多 600 秒之后 |
| 每个订单的币 | 32 |
| 过期条目清理 | 每 30 秒 |
| 速率 | 全局每秒 200 个请求，每个 IP 每秒 20 个请求（突发 = 2 秒的量） |

**代理之后的速率限制。** 在回环 TLS 前端之后，所有请求都来自 `127.0.0.1`。
当请求来自回环地址时，cape 按**最右边的 `X-Forwarded-For` 条目**
（即你的代理追加的那一项）限速，而不是按连接地址限速。直接的、非回环调用方的
请求头会被忽略，所以没有人能自选令牌桶。你的前端必须发送这个请求头：
Caddy 的 `reverse_proxy` 和 Apache 的 `ProxyPass` 默认会发送；nginx 需要在 `/dex-relay/` 块中加入
`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`。没有
这个请求头时，每秒 20 个请求（突发 40）的按 IP 令牌桶会作用于**所有公开流量的总和**。

### 发现

- 每个有 DEX URL 的中继都会把该 URL 放进它的网格问候（`dex`）中。对等节点在回拨时
  记录它，并在每 10 分钟的重新回拨时刷新它。
- `GET /dex/relays` 列出本中继自己的 URL，以及它的已验证对等节点通告的每个 DEX URL。
  通配地址（`0.0.0.0`、`::`）永远不会被列出。
- 新中继在通过验证后，大约一个重新回拨周期（10 分钟）内就会出现在其他中继的列表中。
- Dexicon 客户端从内置的中继列表开始，并合并每个中继的 `/dex/relays`，所以
  网格再慢也不会阻碍交易。

### 检查

1. 从另一台机器请求健康记录。它必须返回
   `{"ok":true,"version":"0.1.5",…}`：

   ```bash
   curl -s https://node.example.com/dex-relay/dex/health
   ```

2. 列出本中继知道的 DEX 中继。它自己的 URL 必须在列表中：

   ```bash
   curl -s https://node.example.com/dex-relay/dex/relays
   ```

3. 检查明文端口从外部看是关闭的。这必须没有任何输出：

   ```bash
   nc -z -w 5 node.example.com 9602 && echo "EXPOSED 9602"
   ```

4. 大约 10 分钟后，向另一个 DEX 中继请求它的列表。本节点的 URL 也应当出现在那里。

---

## 17. 账户、配对与本地面板

每个 Parlons Node 还运行 Parlons **账户**：与 `parlons-cloud.jar` 相同的代码，使用
节点的身份，以节点自己的钱包为钱包，以 cape 为中继。手机通过
Parlons Cloud 应用与它配对。用 `-Dparlons.account=false` 关闭它。

### 如何配对你的手机

在服务器上的交互式 SSH 终端中运行以下命令。每一步在应用一侧的操作见
[Parlons Cloud 门户手册](parlons-cloud-portal.md#4-pair-the-app-to-your-account)。

1. 设置一次管理员配对密码：

   ```bash
   sudo parlons-pair --set-admin-password
   ```

2. 显示你账户的地址和一个一次性所有者邀请。如果安装了 `qrencode`，
   还会绘制二维码：

   ```bash
   sudo parlons-pair --admin
   ```

3. 在 Parlons Cloud 应用中扫描或粘贴邀请。有效的配对码会立即完成配对。
4. 检查心跳。`account=up:… devices` 中的设备数会增加一个：

   ```bash
   journalctl -u parlons-node --since -2min --no-pager | grep heartbeat | tail -1
   ```

`parlons-pair` 默认读取 `/var/lib/parlons-node`。如果你的数据目录在别处，
请传入 `--data DIR`。

未使用的配对码保存在 `pair-code.txt` 中，直到被使用。用过的配对码永远不会再次生效。
不要从旧日志或旧的 `invite.txt` 副本中获取配对码。

### 如何托管访客账户

访客账户需要在节点旁边运行单独的 `parlons-tenants` 服务。请先设置它
（[Parlons Cloud 门户手册，§21](parlons-cloud-portal.md#21-many-accounts-the-parlons-tenants-service)）。
给它设置 `--panel-port 9597`，以免与节点在 9587 上的面板冲突。

1. 为一个新账户名生成访客邀请：

   ```bash
   sudo parlons-pair --guest <name>
   ```

2. 通过私密渠道把邀请发给你的访客。他们在自己的 Parlons
   Cloud 应用中完成配对。

关于邀请的更多内容：[Parlons Cloud 门户手册，§23](parlons-cloud-portal.md#23-owner-and-guest-invitations-from-the-server)。

### 如何运行不带账户的节点

在单元文件中设置 `-Dparlons.account=false` 并重启（[§7](#how-to-change-the-unit)）。节点
随后只作为中继和网关运行。心跳显示 `account=off`。

### 自己的中继

当 cape 开启并且已知其公网主机时，账户会优先连接自己的 cape，
优先通告它，并把自己的永久地址锚定在它上面。如果主机未知或是私有地址，
日志会显示 `own relay NOT advertised: … set -Dparlons.relay.host=<public ip>`。

### 如何打开本地面板

1. 在你的电脑上，打开一条到面板端口的 SSH 隧道，并保持连接：

   ```bash
   ssh -L 9587:127.0.0.1:9587 root@node.example.com
   ```

2. 在该 SSH 会话中，读取一次性链接：

   ```bash
   sudo cat /var/lib/parlons-node/panel-ticket.txt
   ```

   它看起来像 `http://127.0.0.1:9587/open?ticket=…`。
3. 在你电脑上的浏览器中打开该链接。链接一经使用，账户就会生成一个新的链接。
4. 如果你以前撤销过面板，现在想恢复它，请删除本地设备密钥并重启：

   ```bash
   rm /var/lib/parlons-node/local-device.key
   systemctl restart parlons-node
   ```

面板只监听 `127.0.0.1:9587`（`parlons.panel.port`；`0` 关闭它）。它无法
访问助记词、备份、钱包发送、付款、节点控制台或 NFT 托管。这些功能仍只能在
已配对的手机和命令行中使用。面板显示的内容：[Parlons Cloud 门户手册，§24](parlons-cloud-portal.md#24-the-local-web-panel)。

### 如何把可移植账户恢复到新节点

`.pbk` 包由已配对设备生成（设置 → **备份账户…**，见
[Parlons Cloud 门户手册，§16](parlons-cloud-portal.md#16-backups-recovery-and-moving)）。它
包含身份、已配对设备、设置、联系人和聊天。

1. **永久**停止备份来源的那台主机：一个身份，只能有一个在线账户。
2. 安装节点（[§5](#5-three-ways-to-install)）但不要启动它，或者停止它并把它的
   数据目录移走。恢复需要一个**全新的**数据目录：

   ```bash
   systemctl stop parlons-node
   mv /var/lib/parlons-node /var/lib/parlons-node.before-restore
   mkdir -p /var/lib/parlons-node
   chown maxima:maxima /var/lib/parlons-node
   chmod 700 /var/lib/parlons-node
   ```

3. 把备份包复制进去，并交给服务用户：

   ```bash
   cp backup.pbk /var/lib/parlons-node/backup.pbk
   chown maxima:maxima /var/lib/parlons-node/backup.pbk
   ```

4. 在交互式终端中以服务用户身份运行恢复。它会询问备份包的
   口令，写入账户，然后退出：

   ```bash
   sudo -u maxima java -Dparlons.node.data=/var/lib/parlons-node \
       -Dparlons.restore=/var/lib/parlons-node/backup.pbk -jar /opt/maxima/parlons-node.jar
   ```

5. 删除备份包并启动节点：

   ```bash
   rm /var/lib/parlons-node/backup.pbk
   systemctl start parlons-node
   ```

6. 已配对的设备会自动重新连接，因为 `MAX#` 地址没有变。
7. 节点的钱包仍然是它自己的 vault。若要让资金跟随过来，请从已配对设备把钱包重新同步到旧的
   助记词（[§20](#how-to-resync-the-wallet-to-another-phrase)）。

应用也可以把身份或 `.pbk` 直接导入到正在运行的节点上（设置 →
**导入身份或备份…**）。节点随后以退出码 3 退出，并以导入的
身份重新运行。旧身份保存在 `account-history/<import-id>/previous/` 中。

---

## 18. keets 附属进程（KeetChat 桥接）

当内嵌的 Minima 节点运行 keets 时，账户的聊天引擎会获得 keets 传输方式，
节点也会成为其 keets 收件箱的**唯一读取者**。

**Fleet jar 自行完成这一切。**`parlons-node-fleet-<ver>.jar` 内含 Node.js 运行时和附属进程。以 `-Dparlons.fleet=true`（一行命令安装程序写入的设置）启动时，它首次启动会把它们解压到 `<data>/runtime/`，开启 Minima 服务器角色和 `-keets`，并以 **serve** 模式运行附属进程：一个 JavaScript 进程，同时是机器的 keets 传输、公开的 HyperDHT 引导节点（`49737/udp`，`-Dparlons.keets.dhtport`）和 keets 邮箱（`<data>/keets-mailbox`）。`-Dparlons.public.host=<ip>` 为机器命名。启动日志打印 `FLEET ENDPOINT` 横幅，含 keets 公钥、DHT 引导地址和邮箱密钥，以及列表维护者运行的 `mailboxes-sign.js` 命令；邮箱只有在签入已发布列表后才会为离线手机保存邮件。您在 `parlons.node.args` 中设置的 Minima 参数优先于 fleet 模式的设置。本节其余部分是使用普通 jar 和外部附属进程的手动方式。

- 在 `parlons.node.args` 中用 Minima 参数开启它：
  `-keets -keetssidecar /opt/maxima/keets-sidecar/index.js`。桌面应用还会传入
  `-keetsnode <path to a node binary>`。部署脚本的 `--keets` 会上传附属进程（来自
  `../keets/sidecar`，需要 `node_modules`）并设置这些参数。
- 服务器需要 **Node.js** 运行时。附属进程作为子进程在服务沙箱内运行。
- **在单元文件的 `RestrictAddressFamilies` 中加入 `AF_NETLINK`。** 没有它，附属进程打开的
  每个套接字都会以 `EPERM` 失败。
- **不需要开放任何入站防火墙端口。** 附属进程使用 hyperswarm 打洞。
- 启动时，桥接会记录 `keets chat bridge up (KeetChat interop)`，然后是
  `keets identity: 0x<64 hex> (a KeetChat user adds this as a contact)`。
- 桥接每 5 秒检查一次节点。收款地址请求只对**联系人**应答，
  对每个对等方每 10 分钟最多应答一次。
- `-Dparlons.node.keetschat=off` 让宿主应用自己读取 keets 收件箱（minimaCore Desktop
  就是这样做的）。每个节点永远只能有一个读取者。

### 如何用部署脚本开启 keets

1. 在服务器上安装 Node.js。没有 `node` 运行时，脚本会拒绝继续：

   ```bash
   apt-get install -y nodejs
   node --version
   ```

2. 在你的电脑上，把 keets 仓库检出到 maxima 检出目录旁边，使附属进程
   位于 `../keets/sidecar`，并安装它的依赖包：

   ```bash
   (cd ../keets/sidecar && npm ci --omit=dev)
   ```

   如果要使用别处的附属进程，请在运行脚本前设置 `SIDECAR=/path/to/sidecar`。
3. 带上你平常使用的所有参数，再加上 `--keets`，重新运行部署脚本。它会把附属进程上传到
   `/opt/maxima/keets-sidecar`，添加这些参数，并在单元文件中加入 `AF_NETLINK`。
4. 检查日志：

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'keets'
   ```

   你应当看到 `keets chat bridge up (KeetChat interop)` 和 `keets identity: 0x…`。

### 如何手动开启 keets

1. 在服务器上安装 Node.js，并把一个带 `node_modules` 的附属进程复制到
   `/opt/maxima/keets-sidecar`（其中必须有 `index.js`）。
2. 在单元文件的 `parlons.node.args` 中加入 keets 参数：

   ```
   "-Dparlons.node.args=-keets -keetssidecar /opt/maxima/keets-sidecar/index.js"
   ```

3. 在单元文件的地址族中加入 `AF_NETLINK`：

   ```ini
   RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
   ```

4. 重新加载并重启（[§7](#how-to-change-the-unit)），然后按上面第 4 步检查日志。
5. 把 `keets identity: 0x…` 的值交给想把节点添加为联系人的 KeetChat 用户。

---

## 19. 节点上的 MDS（MiniHUB）

- `-Dparlons.mds=true` 在 `https://127.0.0.1:<p2p+2>/` 上提供 MiniHUB 和 MiniDapp。它使用
  自签名证书。
- **除非你另行指定，它只绑定回环地址。** MiniHUB 是一个只靠一个密码保护的完整钱包界面。
  没有你自己的 TLS 和防火墙时，切勿把它绑定到公网。
- 密码来自一个权限为 600 的配置文件（`-Dparlons.node.conf=/path/minima.conf`，其中包含
  `mdspassword=…`）。否则会自动生成一个：通过管理 RPC 用 `mds` 命令读取它。
  启动日志会遮盖它。
- 只打包了 hub、登录页、`mds.js`、publicmds 和 Pending dapp。其他 dapp 请自行
  安装。

### 如何开启 MDS 并打开 MiniHUB

1. 把 MDS 密码放进配置文件（[§8，配置文件](#how-to-pass-minimas-own-flags-in-a-conf-file)），
   或跳过这一步，让节点自动生成一个。
2. 把以下属性加入单元文件并重启（[§7](#how-to-change-the-unit)）。如果你跳过了第 1 步，
   请省略 `parlons.node.conf` 那一行：无法读取的配置文件会让节点
   以退出码 2 停止。

   ```
   -Dparlons.mds=true
   -Dparlons.mds.bind=127.0.0.1
   -Dparlons.node.conf=/var/lib/parlons-node/minima.conf
   ```

3. 如果你没有设置密码，请在服务器上通过管理 RPC 读取自动生成的密码：

   ```bash
   curl -s http://127.0.0.1:9005/mds
   ```

4. 在你的电脑上，打开一条到 MDS 文件端口的 SSH 隧道（P2P 端口 9001 意味着 9003）：

   ```bash
   ssh -L 9003:127.0.0.1:9003 root@node.example.com
   ```

5. 在浏览器中打开 `https://127.0.0.1:9003/`，接受自签名证书，并用该密码
   登录。

---

## 20. 助记词、身份与备份

> **助记词就是钱包。** 节点的 24 词助记词、`identity.txt`，以及你采用的任何 `seed.txt`，
> 都能动用资金。切勿把它们打印到日志、工单、聊天或正在被录制的
> 终端中。用 grep 搜索单元文件或日志时，请加上 `| grep -v seed`。

### 两个机密，同一来源

| 内容 | 位置 | 用途 |
|---|---|---|
| **钱包助记词** | 节点的 vault，位于 `<data>/<base version>/` 下 | 节点钱包（即账户的钱包） |
| **身份助记词** | `<data>/identity.txt`（权限 600） | Maxima `Mx…` 身份、账户的 `MAX#`、配对、联系人 |

首次启动时，节点创建自己的钱包助记词，并把助记词复制到 `identity.txt` 中。如果
节点的助记词是助记词规则无法重现的自定义短语，它会改为保存助记词的十六进制值。
从此以后，身份来自 `identity.txt`，而不是来自 vault。因此：

- 钱包可以**重新同步**到新的助记词（在已配对设备上：钱包 →“将钱包重新同步到新助记词…”），
  而不会改变 `MAX#`、已配对设备或联系人。节点运行
  `megammrsync action:resync host:<parlons.node.archive> …` 并以退出码 3 退出。如果重新同步
  失败，节点保持运行，钱包不变，失败原因会显示在设备上。
- 若要把身份重新固定到 vault 当前保存的助记词，删除 `identity.txt` 并重启。

### 如何备份钱包助记词

1. 确认管理 RPC 已开启（[§21](#21-the-admin-rpc)）。
2. 在服务器上，在一个不被录制的会话中打印 vault：

   ```bash
   curl -s http://127.0.0.1:9005/vault
   ```

3. 把 24 个词的 `phrase` 写在纸上，或存入离线存储。不要把它重定向到共享位置的
   文件中。
4. 清除终端的回滚记录。
5. 如果身份助记词与钱包助记词不同（在重新同步或身份导入之后），
   也要备份 `identity.txt`（见下一个步骤）。

### 如何备份整个节点

区块链本身不需要备份；新节点会重新同步它。请备份机密和你的
设置。

1. 备份钱包助记词（上一个步骤）。
2. 把身份和账户文件复制到一个你控制的、私密且加密的位置。先停止节点，
   使文件保持一致：

   ```bash
   systemctl stop parlons-node
   tar -C /var/lib/parlons-node -czf /root/parlons-node-backup.tgz \
       --exclude='./1.1' --exclude='./nft/tmp' .
   systemctl start parlons-node
   chmod 600 /root/parlons-node-backup.tgz
   ```

   其中包含 `identity.txt`、账户（设备、联系人、聊天）、中继状态、网关
   令牌和托管的 NFT 文件。它是与钱包同等级别的机密：请像对待助记词一样对待它。
3. 复制单元文件和你的 TLS 前端配置：

   ```bash
   cp /etc/systemd/system/parlons-node.service /root/
   ```

4. 把归档文件移出服务器，然后在服务器上删除它：

   ```bash
   scp root@node.example.com:/root/parlons-node-backup.tgz .
   ssh root@node.example.com rm /root/parlons-node-backup.tgz
   ```

5. 如果只需要账户的可移植副本，请从已配对设备生成一个 `.pbk` 包
   （[Parlons Cloud 门户手册，§16](parlons-cloud-portal.md#16-backups-recovery-and-moving)）。

切勿在第一台服务器仍在运行时，把这个归档恢复到第二台服务器上。一个身份只能在
一个地方运行。

### 如何用部署脚本采用已有的助记词

1. 把 24 词助记词放进服务器上的一个文件中，所有者为 root，权限 600。运行过
   `maxima-server.jar` 的服务器已经在 `/var/lib/maxima/seed.txt` 中有这个文件。
2. 带上 `--rpc --seed-from <file on the box>` 以及你平常使用的参数，重新运行部署脚本。
   传入 `--archive <host:port>` 来选择归档节点。如果你还要导入 MegaMMR，
   脚本会按要求先采用助记词。
3. 阅读结果。`vault MATCHES <file> - identity preserved (and pinned in identity.txt)` 表示
   成功。任何其他结果都意味着：在弄清原因之前，不要信任该节点。
4. 在你有了离线副本之后，从服务器上删除助记词文件。

### `--seed-from` 做了什么

它保留一个已有中继或云账户的身份。部署脚本会：

1. 检查服务器上的文件是否恰好包含 24 个词。
2. 把 `megammrsync action:resync host:<--archive> phrase:"…"` 作为**来自权限为 600 的临时
   文件的 POST 请求体**发送。助记词永远不会出现在 argv 或日志中。
3. 重启节点，并比较 vault 中的助记词与文件内容，两者都不打印。
4. 如果一致，用该文件写入 `identity.txt`，并写入 `.seed-adopted` 标记，这样
   以后的运行会跳过这一步。

助记词永远不会离开服务器。

### 如何把钱包重新同步到另一个助记词

在已配对设备上：钱包 → **将钱包重新同步到新助记词…**。节点使用 `parlons.node.archive` 中的
归档主机，以退出码 3 退出，并以同一个身份重新运行。

在服务器上手动操作（这就是部署脚本的 `--seed-from` 所做的）：

1. 把命令写进一个私密的临时文件，这样助记词永远不会出现在 argv 或日志中：

   ```bash
   umask 077
   tmp=$(mktemp /var/lib/parlons-node/.resync.XXXXXX)
   printf 'megammrsync action:resync host:%s phrase:"%s"' 'archive.example.net:9001' '<24 words>' > "$tmp"
   ```

   请在不被录制的会话中输入助记词，之后清除 shell 历史记录。
2. 把它作为 POST 请求体发送给管理 RPC。这可能需要几分钟：

   ```bash
   curl -s -m 3600 --data-binary @"$tmp" http://127.0.0.1:9005/
   shred -u "$tmp"
   ```

   它必须回复 `"status":true`。重新同步结束时节点会关闭。
3. 重启，并等待 RPC 再次响应：

   ```bash
   systemctl restart parlons-node
   until curl -s -m 5 http://127.0.0.1:9005/status >/dev/null; do sleep 2; done
   ```

4. 在不被录制的会话中，检查 vault 现在是否保存着新的助记词（`curl -s http://127.0.0.1:9005/vault`）。
5. 如果你要导入 MegaMMR，现在请重新导入（[§13](#13-megammr-mode-and-the-rootnode)）：
   重新同步重置了区块链数据库。

身份仍然固定在 `identity.txt` 中，所以 `MAX#`、已配对设备和联系人都不会
改变。

### 如何把身份重新固定到 vault

只要 vault 中的助记词与 `identity.txt` 不同，这就会改变 Maxima 身份和 `MAX#`。
已配对设备和联系人随后会指向旧的身份。

1. 保留一份旧身份文件的副本，然后删除它：

   ```bash
   cp /var/lib/parlons-node/identity.txt /var/lib/parlons-node/identity.txt.before-repin
   rm /var/lib/parlons-node/identity.txt
   ```

2. 重启。启动日志会显示 `identity: pinned from the vault into identity.txt`：

   ```bash
   systemctl restart parlons-node
   journalctl -u parlons-node -b --no-pager | grep 'identity:' | grep -v seed
   ```

3. 当你确定不再需要旧身份时，删除 `.before-repin` 副本。

### 如何运行有密码锁定的节点

如果 vault 被锁定，cape、网关和账户要等到解锁后才能启动。
Minima 节点本身会继续运行。节点在每次启动时恰好尝试解锁一次，口令来自一个文件或
`PARLONS_NODE_PASSPHRASE` 环境变量。

1. 把口令写进 `/var/lib/parlons-node` 或 `/etc` 下的一个文件中（不要放在 `/root` 或
   `/home` 下：`ProtectHome=true` 会隐藏它们）。口令中不得包含空白字符、`"` 或 `;`：

   ```bash
   umask 077
   printf '%s\n' '<passphrase>' > /var/lib/parlons-node/passphrase.txt
   chown maxima:maxima /var/lib/parlons-node/passphrase.txt
   chmod 600 /var/lib/parlons-node/passphrase.txt
   ```

2. 把它加入单元文件并重启（[§7](#how-to-change-the-unit)）。使用部署脚本时，传入
   `--passphrase-file /var/lib/parlons-node/passphrase.txt`：

   ```
   -Dparlons.node.passphrase.file=/var/lib/parlons-node/passphrase.txt
   ```

3. 检查 cape、网关和账户是否已启动（心跳中显示 `cape=up`、`gateway=up`）。
   日志中出现 `node is password-locked but no passphrase provided` 表示文件没有被读取。

### 切勿

- 切勿在命令行上传入 `-seed`、`-anyseed` 或 `-dbpassword`。节点无论如何都会拒绝它们。
- 切勿让两个进程使用同一个数据目录，也不要让一个身份在两台主机上运行。信箱和
  目录状态会在两者之间分裂，消息会丢失。
- 切勿把 `identity.txt` 复制到另一台正在运行的服务器上。

---

## 21. 管理 RPC

- 用 `-Dparlons.node.rpc=true` 开启它。它监听 `127.0.0.1:<p2p+4>`（P2P 为 9001 时是 9005）。
  回环绑定是硬编码的；没有任何设置能把它暴露出去。
- 它接受**所有**节点命令，并且**没有身份验证**。它信任本地进程。它
  拒绝浏览器的跨源请求（它会检查 Host 请求头、Origin 和 Sec-Fetch-Site）。
- 两种请求形式：`GET /<url-encoded command>`，或以命令作为请求体的 `POST /`（最大
  64 KiB）。

  ```bash
  curl -s http://127.0.0.1:9005/status
  curl -s 'http://127.0.0.1:9005/balance'
  curl -s --data-binary 'network' http://127.0.0.1:9005/
  ```

- `quit` 会停止节点，JVM 在约 0.8 秒后以退出码 0 退出。在 `Restart=always`
  （部署脚本写入的设置）下，systemd 会在 10 秒后再次启动它。若要永久停止节点，
  请改用 `systemctl stop parlons-node`。
- **切勿把它放在代理之后，也切勿开放它的端口。** 网关才是经过加固的公开接口。
- 如果端口已被占用，节点会记录 `REFUSING to run: admin rpc on 127.0.0.1:N cannot
  start …` 并以退出码 2 退出。

### 如何开启管理 RPC

1. 在单元文件中添加 `-Dparlons.node.rpc=true` 并重启（[§7](#how-to-change-the-unit)）。使用
   部署脚本时，传入 `--rpc`。
2. 检查启动行。它必须显示 `admin rpc 127.0.0.1:9005 (loopback-bound)`：

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'admin rpc' | grep -v seed
   ```

3. 在服务器上运行一条命令：

   ```bash
   curl -s http://127.0.0.1:9005/status
   ```

4. 从另一台机器检查该端口没有响应。这必须没有任何输出：

   ```bash
   nc -z -w 5 node.example.com 9005 && echo "EXPOSED 9005"
   ```

### 如何运行带空格或引号的命令

1. 对于短命令，把空格 URL 编码为 `%20`：

   ```bash
   curl -s 'http://127.0.0.1:9005/balance%20megammr:true%20address:<address>'
   ```

2. 对于更长的命令或带引号的命令，作为 POST 请求体发送（最大 64 KiB）：

   ```bash
   curl -s --data-binary 'coins relevant:true' http://127.0.0.1:9005/
   ```

### 如何停止或重启节点

请使用 systemd，而不是 `quit`：

```bash
systemctl stop parlons-node
systemctl start parlons-node
systemctl restart parlons-node
```

---

## 22. 升级与回滚

惯例是**带版本号的 jar 加一个符号链接**。每个 jar 都保存在 `/opt/maxima` 中，
单元文件始终运行 `/opt/maxima/parlons-node.jar`，它是一个符号链接。

### 如何升级一台服务器

1. 在你的电脑上下载新的 jar 并检查其 SHA-256（[§4](#4-download-and-verify-the-jar)）。
2. 以临时文件名把它复制到服务器上：

   ```bash
   B=parlons-node-0.2.144.jar
   scp $B root@node.example.com:/opt/maxima/$B.part
   ```

3. 在服务器上再次检查哈希，然后把 jar 放到位：

   ```bash
   cd /opt/maxima
   echo 'c5b114cc13294bdeee95d9872fc6710cd99c92472ffb63c1581081343663a6f5  parlons-node-0.2.144.jar.part' | sha256sum -c --quiet
   mv parlons-node-0.2.144.jar.part parlons-node-0.2.144.jar
   chmod 644 parlons-node-0.2.144.jar
   ```

4. 记下符号链接当前指向的 jar。回滚时需要它：

   ```bash
   readlink /opt/maxima/parlons-node.jar
   ```

5. 备份单元文件：

   ```bash
   cp /etc/systemd/system/parlons-node.service /etc/systemd/system/parlons-node.service.before-parlons-node-0.2.144.jar
   ```

6. 更新单元文件 `Description` 行中的版本号（可选；它只是一个标签）：

   ```bash
   sed -i 's/wallet gateway ([0-9.]*)/wallet gateway (0.2.144)/' /etc/systemd/system/parlons-node.service
   ```

7. 把符号链接指向新的 jar：

   ```bash
   ln -sfn /opt/maxima/parlons-node-0.2.144.jar /opt/maxima/parlons-node.jar
   ```

8. 重启：

   ```bash
   systemctl daemon-reload
   systemctl restart parlons-node
   ```

9. 等待约 25 秒，然后检查：

   ```bash
   systemctl is-active parlons-node
   journalctl -u parlons-node --since -40s --no-pager | grep -E 'Parlons Node|dex relay|Exception' | grep -v seed
   ```

   启动行必须显示 `Parlons Node 0.2.144`，DEX cape 必须显示 `dex relay 0.1.5 up`。
10. 从外部验证（[§23](#23-verifying-from-outside)）。

如果你运行多台服务器，请**一次升级一台**，每台验证通过后再开始下一台。

### 如何用部署脚本升级

1. 把校验过的新 jar 放进你电脑上的 `dist/`。
2. 用与之前**相同的参数**和新的 jar 重新运行脚本：

   ```bash
   ops/deploy-parlons-node.sh root@node.example.com --jar dist/parlons-node-0.2.144.jar <your usual flags>
   ```

   脚本会上传并检查 jar，切换符号链接，并根据参数重写单元文件。
   你省略的参数会恢复为默认值。
3. 从外部验证（[§23](#23-verifying-from-outside)）。

### 如何回滚

1. 把符号链接指向之前的 jar（即升级第 4 步中 `readlink` 显示的那个）：

   ```bash
   cd /opt/maxima && ln -sfn parlons-node-<old version>.jar parlons-node.jar
   ```

2. 如果你修改过单元文件，从 `.before-…` 备份中恢复它：

   ```bash
   cp /etc/systemd/system/parlons-node.service.before-<what> /etc/systemd/system/parlons-node.service
   ```

3. 重启：

   ```bash
   systemctl daemon-reload
   systemctl restart parlons-node
   ```

4. 检查启动行显示的是旧版本，然后从外部验证（[§23](#23-verifying-from-outside)）。

### 如何回到 `maxima-relay.service`

如果服务器以前运行 `maxima-relay.service`，后来被节点取代，你也可以回滚到它。
部署脚本把它的单元文件保留在了磁盘上：

```bash
systemctl disable --now parlons-node
systemctl enable --now maxima-relay
```

---

## 23. 从外部验证

`systemctl is-active` 只能说明进程在运行。请始终**从公共互联网上的另一台机器**
测试。不要在服务器本身或它所在的局域网内测试：回环 NAT（hairpin NAT）在两个方向上都会给出
错误的结果。

1. P2P 端口和中继端口的 **TCP 可达性**：

   ```bash
   nc -z -w 8 node.example.com 9001 && echo p2p-open
   nc -z -w 8 node.example.com 9501 && echo relay-open
   ```

2. **中继端到端测试。** 在本仓库的检出副本中，把 `:core` 及其测试
   编译到 `build/classes` 后运行：

   ```bash
   ops/verify-relay.sh node.example.com:9501
   ```

   它通过中继连接两个真实的客户端，并让它们互相通信：问候、连接、地址
   分配、联系人握手、双向中继投递、送达回执的返回，以及
   群组分发。只有每项检查都通过时，它才以 0 退出。
3. **DEX cape：**

   ```bash
   curl -s https://node.example.com/dex-relay/dex/health    # {"ok":true,"version":"0.1.5",...}
   curl -s https://node.example.com/dex-relay/dex/relays
   ```

4. **网关：** 带令牌发送一个 `block` POST 请求（[§14](#14-the-wallet-gateway-and-its-token)）。
   不带令牌的请求必须返回 401。
5. **NFT：** `curl -sI https://node.example.com/parlons-node/nft/<sha256>.<ext>` 对托管的文件返回 200，
   对其他任何内容返回 404。
6. **仅限回环地址的端口从外部必须没有响应：**

   ```bash
   for p in 9005 9585 9587 9602; do nc -z -w 5 node.example.com $p && echo "EXPOSED $p"; done
   ```

   这必须没有任何输出。

---

## 24. 监控与日志

所有输出都进入 journal 日志（`StandardOutput=journal`）。常用命令：

```bash
journalctl -u parlons-node -f                                   # follow
journalctl -u parlons-node -f | grep heartbeat                  # sync progress and subsystem health
journalctl -u parlons-node --since -1h | grep -E 'Exception|FAILED|REFUSING' | grep -v seed
journalctl -u parlons-node -b | grep -E 'Parlons Node|cape up|gateway up|dex relay|account up'
systemctl status parlons-node
```

心跳行，每 30 秒一次：

```
[parlons-node] heartbeat: node block=<n> chainlen=<n> | cape=up:<port> | gateway=up:<port> | account=up:<n> hosts, <n> devices
```

| 字段 | 健康状态 |
|---|---|
| `block=` | 持续增长，之后保持在链顶 |
| `cape=` | `up:<relay port>`（`down` 表示 cape 启动失败或已关闭） |
| `gateway=` | `up:<gateway port>` |
| `account=` | `up:` 且有 1 个或更多主机（`parlons.account=false` 时为 `off`） |

如果你看到 `heartbeat: node not ready: …`，说明内嵌节点还没有响应 `status`。在启动后
短时间内这是正常的。

### 如何在一分钟内检查节点健康状况

1. 服务在运行吗？`-w` 匹配完整单词，所以 `inactive` 不算：

   ```bash
   systemctl is-active parlons-node | grep -qw active && echo running
   ```

2. 读取最新的心跳：

   ```bash
   journalctl -u parlons-node --since -2min --no-pager | grep heartbeat | tail -1
   ```

3. 对照上表检查每个字段。
4. 查找过去一小时内的错误：

   ```bash
   journalctl -u parlons-node --since -1h --no-pager | grep -E 'Exception|FAILED|REFUSING|OutOfMemoryError' | grep -v seed
   ```

5. 检查磁盘和内存：

   ```bash
   df -h /var/lib
   systemctl status parlons-node --no-pager | grep -E 'Memory|Tasks'
   ```

6. 运行外部检查（[§23](#23-verifying-from-outside)）。

### 如何对问题发出告警

节点没有指标端点；心跳和下面的日志行就是接口。一种
简单的检查方式是用 cron 任务在心跳缺失或出现告警行时发出告警：

1. 编写检查脚本 `/usr/local/bin/parlons-node-check`：

   ```bash
   #!/bin/sh
   # Exit 1 (and print why) when the node needs attention.
   systemctl is-active parlons-node | grep -qw active || { echo "parlons-node not active"; exit 1; }
   journalctl -u parlons-node --since -2min --no-pager | grep -q heartbeat || { echo "no heartbeat for 2 min"; exit 1; }
   bad=$(journalctl -u parlons-node --since -5min --no-pager | grep -E 'REFUSING|FAILED|OutOfMemoryError|password-locked' | grep -v seed)
   [ -z "$bad" ] || { echo "$bad"; exit 1; }
   ```

2. 使其可执行，并在 root 的 crontab 中每 5 分钟运行一次，配上你自己的告警命令：

   ```bash
   chmod 755 /usr/local/bin/parlons-node-check
   crontab -e
   # */5 * * * * /usr/local/bin/parlons-node-check || <your alert command>
   ```

值得告警的日志行：

| 日志行包含 | 含义 |
|---|---|
| `REFUSING to start` / `REFUSING to run` | 参数有误或端口冲突（退出码 2） |
| `Maxima cape FAILED to start` | 中继没有启动 |
| `wallet gateway FAILED` | 网关没有启动 |
| `account layer FAILED to start` | 账户没有启动 |
| `node is password-locked but no passphrase provided` | cape、网关和账户正在等待解锁 |
| `node seed (vault) not available after 120s` | 钱包未就绪，所以 cape 无法派生身份 |
| `the embedded node shut itself down` | 在重新同步、恢复或重置后 JVM 退出；请重启它 |
| `wallet resync FAILED` | 从设备发起的重新同步失败；钱包未改变 |
| `OutOfMemoryError` | 堆太小（见 [§3](#3-requirements)） |

---

## 25. 防火墙说明

**入站，需开放：**

- TCP `<p2p port>`（9001，或你设置的端口）。Minima 对等节点连接到这里。
- TCP `<relay port>`（9501）。手机和网格对等节点连接到这里。
- TCP 443（以及用于 ACME 的 80），如果服务器提供 TLS 前端。
- 可选：用于 STUN 的 UDP `<relay port>`。部署脚本不会开放它。

**入站，切勿开放：**`p2p+2`、`p2p+3`、`p2p+4`、9585、9587、9602，以及任何 959x 租户面板。

**云防火墙。** 服务商的防火墙（在 Vultr、Hetzner 或 AWS 控制台中）可能在数据包到达服务器之前
就把它们丢弃。ufw 无法解决这个问题。如果某个端口在 ufw 中已开放、在 localhost 上能响应，
但从外部访问超时，请在服务商控制台中开放它。规则更改可能需要一两分钟
才能生效，所以在下结论之前请再测试一次。

**出站默认拒绝。** 在出站策略为默认拒绝（`ufw default deny
outgoing`）的服务器上，节点无法连接你没有放行的对等节点。至少放行：

- 到你的 rootnode 和 Minima 对等节点的出站 TCP；
- 到**每个**网格对等节点中继端口的出站 TCP，使用该对等节点的中继**实际**
  监听的端口（有些对等节点使用 8001，而不是 9501）：`ufw allow out to <peer-ip> port <port> proto tcp`；
- 到用于重新同步的归档节点的出站 TCP；
- 用于下载快照的出站 443。

缺少出站规则的表现是：某个对等节点没有出现在 `/dex/relays` 中，或者网格查询
永远到达不了某个中继。

**家用路由器。** 转发 P2P 端口和中继端口，或使用 `-Dparlons.relay.port=shared`，
这样只转发一个端口就够了。

### 如何用 ufw 开放端口

1. 先放行 SSH，以免把自己锁在外面，然后放行节点的公开端口：

   ```bash
   ufw allow OpenSSH
   ufw allow 9001/tcp
   ufw allow 9501/tcp
   ufw allow 80/tcp
   ufw allow 443/tcp
   ```

2. 可选：在中继端口上放行 STUN：

   ```bash
   ufw allow 9501/udp
   ```

3. 如果 ufw 处于关闭状态，请开启它，并检查规则：

   ```bash
   ufw enable
   ufw status numbered
   ```

4. 如果云服务商有防火墙，也在其中开放相同的端口。
5. 从另一台机器测试（[§23](#23-verifying-from-outside)）。

### 如何用 firewalld 开放端口

1. 永久添加端口，然后重新加载使其生效：

   ```bash
   firewall-cmd --permanent --add-port=9001/tcp
   firewall-cmd --permanent --add-port=9501/tcp
   firewall-cmd --permanent --add-service=http
   firewall-cmd --permanent --add-service=https
   firewall-cmd --reload
   ```

2. 检查结果：

   ```bash
   firewall-cmd --list-all
   ```

部署脚本用 `--permanent` 添加端口，但不会重新加载 firewalld。在使用 firewalld 的服务器上，
请在运行脚本后执行 `firewall-cmd --reload`。

### 如何在出站默认拒绝的服务器上放行出站流量

1. 放行每个网格对等节点的中继端口（对每个对等节点重复，使用其真实端口）：

   ```bash
   ufw allow out to <peer ip> port 9501 proto tcp
   ```

2. 放行 rootnode、你的 Minima 对等节点和归档节点（对每一个重复）：

   ```bash
   ufw allow out to <peer ip> port 9001 proto tcp
   ```

3. 放行用于下载快照的出站 HTTPS，以及 DNS：

   ```bash
   ufw allow out 443/tcp
   ufw allow out 53
   ```

4. 检查之前在 `/dex/relays` 中缺失的对等节点是否在约 10 分钟内出现。

### 如何在家用路由器上转发端口

1. 给服务器在局域网中分配一个固定地址（在路由器中设置 DHCP 保留）。
2. 在路由器中，把 TCP 9001 和 TCP 9501 转发到该地址。使用
   `-Dparlons.relay.port=shared` 时，只转发 9001。
3. 从你的家庭网络之外测试，例如用使用移动数据的手机或一台 VPS。从局域网内部的
   测试是无效的（hairpin NAT）。

---

## 26. 故障排除

### 首先，弄清“无法连接”的情况

从外部看，`nc -z -w 8 <host> <port>` 有三种结果：

| 结果 | 含义 | 检查 |
|---|---|---|
| **立即被拒绝** | 主机有应答，但没有程序在监听。 | `systemctl status parlons-node`；`ss -ltnp \| grep <port>`；启动日志 |
| **超时**（约 8 秒） | 某个包过滤器在悄悄丢弃数据包。 | 云防火墙或路由器端口转发，然后是 ufw |
| **开放** | 网络路径没问题；问题出在应用程序上。 | journal 日志；`ops/verify-relay.sh` |

在服务器上检查：

```bash
systemctl is-active parlons-node
ss -ltnp | grep -E ':(9001|9501|9585|9587|9602) '
ufw status numbered | head -20
bash -c '</dev/tcp/127.0.0.1/9501' && echo localhost-ok
```

决定性的测试是：在你从外部连接的同时，在服务器上抓包：

```bash
# on the box
timeout 25 tcpdump -ni any 'tcp port 9501' -c 5
# meanwhile, from outside
nc -z -w 5 node.example.com 9501
```

- 完全没有数据包：它们在上游被丢弃了，服务器上做什么都无济于事。
- 收到 SYN 但没有回送 SYN-ACK：是服务器本身在丢弃它。

小心 `systemctl is-active x | grep -q active`：它也会匹配 `inactive`。请使用 `grep -qw active`。

### 重启后出现“Invalid SyncBlock as NO PARENT”

曾在重启后、节点追赶链顶期间，在日志中看到过这条信息。
尚未进一步调查。处理方法：

1. 观察心跳。如果 `block=` 持续增长并达到链顶，说明节点已自行恢复。
2. 如果 `block=` 长时间不动，请检查 rootnode 和对等节点是否可达
   （出站防火墙，[§25](#25-firewall-notes)），以及时钟是否正确。

### 端口已被占用

| 症状 | 原因 | 解决方法 |
|---|---|---|
| `REFUSING to run: admin rpc on 127.0.0.1:9005 cannot start` | 另一个 Minima 节点占用了 `p2p+4`，或 JRE 缺少 `jdk.httpserver`。 | 移动 P2P 端口（`parlons.node.port=9101` 会把 RPC 放到 9105），或使用完整的 JRE。 |
| 中继端口冲突；`cape=down` | `maxima-relay.service` 或另一个中继占用了 9501。 | `systemctl disable --now maxima-relay`，或另选一个中继端口。 |
| 面板在 9587 上启动失败 | `parlons-tenants` 服务（或第二个节点）占用了它。 | 给租户服务设置 `--panel-port 9597`，或设置 `parlons.panel.port`。 |
| `wallet gateway FAILED: …BindException` | 有程序占用了 9585。 | 设置 `parlons.gateway.port`，并相应更新 TLS 前端。 |
| 节点无法在 9001 上启动 | 9001 上有一个普通 Minima 节点。 | `parlons.node.port=9101` |

### 其他症状

| 症状 | 解决方法 |
|---|---|
| 进程立即退出，并打印帮助文本 | 你传入了命令行参数。所有设置都是 `-jar` 之前的 `-D` 属性。 |
| `REFUSING to start: these Minima flags are not allowed` | 从 `parlons.node.args` 或配置文件中删除所列参数（见 [§8.8](#88-minima-flags-the-node-refuses)）。 |
| `block=` 一直为 0 | 没有 rootnode 或 connect 列表，或者到它的出站访问被阻止。 |
| 内存不足循环，或服务最终变为“failed” | 调高堆（使用 MegaMMR 时至少 3 GB），并让 `MemoryMax` 保持在它之上。用 `systemctl reset-failed parlons-node` 清除启动限制。 |
| 手机看到的余额为空 | 还没有导入 MegaMMR（[§13](#13-megammr-mode-and-the-rootnode)）。 |
| 网关返回 503 `no MegaMMR` | 节点以 `megammr=false` 运行。手机会切换到另一个网关。 |
| 网关返回 403 `requires an address:` | 给 `coins`/`balance` 加上 `address:`。 |
| 网关返回 429 | 全局令牌桶已耗尽。调高 `parlons.gateway.rate.global`，或在代理中限速。 |
| 网关从未向手机通告 | 它需要开启 MegaMMR、设置 `parlons.node.public`，并开启中继。 |
| NFT `url` 为空 | 把 `parlons.node.public` 设为真正路由到本服务器 9585 端口的 URL。 |
| `own relay NOT advertised: public host unknown or private` | 设置 `-Dparlons.relay.host=<public host>`。 |
| `dex relay: no public host known yet - NOT announced` | 设置 `-Dparlons.dex.public=<https url>`（或 `parlons.relay.host`）。 |
| 某个对等节点不在 `/dex/relays` 中 | 等待一个 10 分钟的重新回拨周期。然后检查本服务器能否**出站**连接到该对等节点的中继端口。 |
| DEX cape 在公网 IP 上有应答 | `parlons.dex.bind` 仍然是 `0.0.0.0`。把它设为 `127.0.0.1` 并重启。 |
| Caddy 在 `/dex-relay/…` 上返回你的网站 | `handle_path` 与 `handle` 的排序陷阱（[§11](#11-tls-fronting-caddy-nginx-apache)）。 |
| 账户始终不显示“attached” | 没有出站互联网，或时钟严重错误。 |
| cape、网关和账户始终不启动；日志提到密码锁定 | 提供口令（[§20](#how-to-run-a-password-locked-node)）。 |
| 在 `quit`、手动导入 megammr 或重新同步后，服务停止了 | 单元文件仍然是 `Restart=on-failure`，它不会在正常退出后重启。把它改为 `Restart=always`（见下面的解决步骤）。 |
| keets 附属进程在套接字上以 `EPERM` 失败 | 在 `RestrictAddressFamilies` 中加入 `AF_NETLINK`。 |
| 结束卡住的进程 | 按 PID 结束它。切勿运行 `pkill -f parlons-node.jar`：这个模式可能匹配到你自己的 SSH 会话。 |

### 分步解决方法

#### 解决：管理 RPC 端口被占用

1. 查看是谁占用了它：

   ```bash
   ss -ltnp | grep ':9005 '
   ```

2. 如果是普通 Minima 节点，移动 Parlons Node 的 P2P 端口（[§9](#how-to-move-the-node-off-port-9001)）。
3. 如果没有程序占用，说明 JRE 缺少 `jdk.httpserver`。检查一下，如果没有那一行输出，就安装完整的 JRE：

   ```bash
   java --list-modules | grep jdk.httpserver || apt-get install -y default-jre-headless
   ```

#### 解决：中继端口被占用

1. 查看是谁占用了 9501：

   ```bash
   ss -ltnp | grep ':9501 '
   ```

2. 如果是旧的独立中继，停止它并重启节点：

   ```bash
   systemctl disable --now maxima-relay
   systemctl restart parlons-node
   ```

3. 检查心跳是否显示 `cape=up:9501`。

#### 解决：面板或网关端口被占用

1. 查看是谁占用了它：

   ```bash
   ss -ltnp | grep -E ':(9585|9587) '
   ```

2. 如果是 9587 上的 `parlons-tenants` 服务，给它设置 `--panel-port 9597`
   （[Parlons Cloud 门户手册，§21](parlons-cloud-portal.md#21-many-accounts-the-parlons-tenants-service)）。
   否则就移动节点：设置 `-Dparlons.panel.port=<free port>` 或 `-Dparlons.gateway.port=<free port>`。
3. 如果你移动了网关，也要修改 TLS 前端中的端口，然后重新加载它。
4. 重启节点（[§7](#how-to-change-the-unit)）。

#### 解决：节点立即退出或拒绝启动

1. 查看原因：

   ```bash
   journalctl -u parlons-node -n 30 --no-pager | grep -E 'REFUSING|usage|Usage' | grep -v seed
   ```

2. 如果打印了帮助文本，请删除单元文件中 `-jar …parlons-node.jar` 之后的所有参数。
   所有设置都是 `-jar` 之前的 `-D` 属性。
3. 如果它列出了被拒绝的 Minima 参数，请从 `parlons.node.args` 或配置文件中删除它们
   （[§8.8](#88-minima-flags-the-node-refuses)）。
4. 如果它说无法读取 `-conf`，请检查配置文件的路径、所有者（`maxima`）和权限（600）。
5. 重启（[§7](#how-to-change-the-unit)）。

#### 解决：`block=` 一直为 0

1. 检查单元文件中是否有 rootnode 或 connect 列表：

   ```bash
   grep -E 'parlons.node.(rootnode|connect)' /etc/systemd/system/parlons-node.service
   ```

2. 如果都没有，设置一个（[§13](#how-to-set-the-rootnode)）。
3. 检查服务器能否连通它：

   ```bash
   nc -z -w 8 seed-peer.example.net 9001 && echo reachable
   ```

4. 如果无法连通，放行到它的出站流量（[§25](#how-to-allow-outbound-traffic-on-a-default-deny-box)）。
5. 检查时钟：

   ```bash
   timedatectl | grep -E 'synchronized|NTP'
   ```

#### 解决：内存不足循环，或服务处于“failed”状态

1. 确认原因：

   ```bash
   journalctl -u parlons-node --since -1h --no-pager | grep -E 'OutOfMemoryError|oom' | tail -5
   ```

2. 在单元文件中调高 `-Xmx`（使用 MegaMMR 时 3g 或更多），并把 `MemoryMax` 设为至少比它高 512 MB，
   例如 `-Xmx3g` 配 `MemoryMax=3584M`。
3. 重新加载，清除启动限制，然后再次启动：

   ```bash
   systemctl daemon-reload
   systemctl reset-failed parlons-node
   systemctl restart parlons-node
   ```

4. 一分钟后检查它是否仍为 `active`。

#### 解决：手机看到的余额为空

1. 检查 MegaMMR 的大小。小于 1 MB 表示从未导入过：

   ```bash
   stat -c %s /var/lib/parlons-node/1.1/databases/megammr.mmr
   ```

2. 导入它（[§13](#how-to-import-a-megammr-snapshot)）。

#### 解决：网关从未向手机通告

1. 检查三个条件：MegaMMR 开启、设置了 `parlons.node.public`、中继开启：

   ```bash
   grep -E 'megammr|node.public|relay.port' /etc/systemd/system/parlons-node.service
   ```

2. 补上缺少的那一项并重启（[§14](#how-to-publish-the-gateway-for-phones)）。
3. 在日志中查找 `wallet gateway advertised to phones:`。

#### 解决：cape 没有通告自己，或 DEX cape 没有被公告

1. 阅读警告：

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'own relay NOT advertised|dex relay: no public host'
   ```

2. 设置公网主机（[§12](#how-to-set-the-public-host)），对于 DEX cape 还要设置
   `-Dparlons.dex.public=https://node.example.com/dex-relay`。
3. 重启并再次检查日志。

#### 解决：DEX cape 在公网 IP 上有应答

1. 检查绑定地址：

   ```bash
   ss -ltnp | grep ':9602 '
   ```

2. 如果显示 `0.0.0.0:9602` 或 `*:9602`，在单元文件中添加 `-Dparlons.dex.bind=127.0.0.1`（以及
   `-Dparlons.dex.public=…`），然后重启。
3. 如果部署脚本开放了该端口，请在防火墙中关闭它：

   ```bash
   ufw delete allow 9602/tcp
   ```

4. 从外部检查 9602 不再有响应（[§23](#23-verifying-from-outside)，第 6 步）。

#### 解决：Caddy 在 `/dex-relay/…` 上返回你的网站

1. 在 Caddyfile 中找到 DEX 块：

   ```bash
   grep -n 'dex-relay' /etc/caddy/Caddyfile
   ```

2. 如果写的是 `handle_path /dex-relay/*`，把它改为 `handle /dex-relay/*`，并在其中加上
   `uri strip_prefix /dex-relay`（[§11](#how-to-front-the-node-with-caddy)）。
3. 验证并重新加载 Caddy，然后从外部测试 `/dex-relay/dex/health`。

#### 解决：在 nginx 之后，所有 DEX 客户端被一起限速

1. 检查 `/dex-relay/` 块是否发送了该请求头：

   ```bash
   grep -n 'X-Forwarded-For' /etc/nginx/sites-enabled/*
   ```

2. 如果没有，在该块中加入 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`
   （[§11](#how-to-front-the-node-with-nginx)），然后运行 `nginx -t` 和 `systemctl reload nginx`。

#### 解决：服务在正常退出后保持停止

1. 把单元文件切换为 `Restart=always`（[§7](#how-to-switch-an-old-unit-to-restartalways)）。
2. 启动节点：

   ```bash
   systemctl start parlons-node
   ```

#### 解决：keets 附属进程以 `EPERM` 失败

1. 在单元文件中加入 `AF_NETLINK`：

   ```bash
   sed -i 's/^RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX$/RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK/' /etc/systemd/system/parlons-node.service
   ```

2. 重新加载并重启：

   ```bash
   systemctl daemon-reload
   systemctl restart parlons-node
   ```

#### 解决：账户始终不显示“attached”

1. 检查出站互联网和时钟：

   ```bash
   curl -sI https://github.com | head -1
   timedatectl | grep synchronized
   ```

2. 如果服务器阻止出站流量，请放行各中继的端口（[§25](#how-to-allow-outbound-traffic-on-a-default-deny-box)）。
3. 重启后，在日志中查找 `account up: attached to N relay(s)`。

#### 解决：进程卡住

1. 找到 PID。切勿使用 `pkill -f parlons-node.jar`：这个模式可能匹配到你自己的 SSH 会话：

   ```bash
   systemctl show -p MainPID parlons-node
   ```

2. 先请求它停止，只有在它不停止时才强制结束：

   ```bash
   kill <pid>
   sleep 15
   kill -0 <pid> 2>/dev/null && kill -9 <pid>
   ```

3. 用 `systemctl start parlons-node` 重新启动它。

---

## 27. 安全检查清单

- [ ] 在启用 jar 之前检查过它的 SHA-256。
- [ ] 服务以无特权的 `maxima` 用户运行，并使用加固版单元文件（[§7](#7-the-systemd-unit-annotated)）。
- [ ] `/var/lib/parlons-node` 的权限为 700。`identity.txt`、`gateway-token.txt`、`pair-code.txt`
      和 `panel-ticket.txt` 的权限为 600。
- [ ] 助记词和钱包助记词已离线备份，并且**没有**出现在任何日志、工单或聊天中。
- [ ] 只有 P2P 端口、中继端口和 443 对入站开放。
- [ ] 从外部看，9005（管理 RPC）、9585、9587 和 9602 都**没有**响应（[§23](#23-verifying-from-outside)，第 6 步）。
- [ ] 只要设置了 `parlons.dex.port`，就同时设置了 `parlons.dex.public`（cape 随之绑定 `127.0.0.1`），或设置了 `parlons.dex.bind=127.0.0.1`。
- [ ] `parlons.gateway.bind` 为 `127.0.0.1`（生产环境中不使用 `--gateway-public`）。
- [ ] 管理 RPC 从未被代理，`parlons.mds.bind` 从未公开。
- [ ] 设置了 `-Xmx`，并且 `MemoryMax` 高于它。
- [ ] `parlons.node.public` 指向一个真正路由到本服务器的 URL。
- [ ] 命令行上没有机密。口令和 `mdspassword` 都在权限为 600 的文件中。
- [ ] **没有**设置 `parlons.node.selftest.send`。
- [ ] 每个身份和每个数据目录都只在一台主机上运行。
- [ ] 云服务商的防火墙与 ufw 的设置一致。
- [ ] 已设置管理员配对密码，旧邀请都已使用或作废。

---

## 28. 内嵌此 jar 的桌面应用

`parlons-node.jar` 也是桌面应用中的节点引擎。它们把一个 `node-v*` 版本下载到
自己的资源中（`scripts/fetch-parlons-node-jar.sh`），并以自己的参数启动它。

- **minimaDesk（MinimaClassic Desktop）**和 **minimaCore Desktop** 启动它时使用
  `-Dparlons.node.rpc=true`，MegaMMR 根据用户的选择设置，用户选择贡献时使用 `-Dparlons.relay.port=shared`
  （否则为 `0`），面板和网关端口由应用自己的端口设置派生。
- minimaDesk 还会传入 `-Dparlons.mds=true -Dparlons.mds.bind=127.0.0.1` 和
  `-Dparlons.node.conf=<file>`。
- minimaCore Desktop 传入 `-Dparlons.node.keetschat=off`，因为它自己读取 keets 收件箱。
- 打包的 JRE 必须包含 `jdk.httpserver`。

服务器节点的运维者无需为这些应用做任何事。切勿为了测试某些东西而启动
持有真实资金的桌面应用。

---

## 29. 术语表

| 术语 | 含义 |
|---|---|
| **管理 RPC**（Admin RPC） | 节点在 `p2p+4` 上的仅限回环、无身份验证、可执行全部命令的 HTTP 端点。 |
| **归档节点**（Archive node） | 保存足够历史（以及 MegaMMR）、可供 `megammrsync` 重新同步使用的 Minima 节点。 |
| **Bearer 令牌**（Bearer token） | `gateway-token.txt` 中的网关凭据。它只允许读取和转发。 |
| **引导对等节点**（Bootstrap peers） | cape 最先联系的中继（`parlons.relay.peers`，或编译内置的列表）。 |
| **Cape** | 在节点内运行的 Maxima 中继。也用于指 DEX 中继 cape。 |
| **游标**（Cursor） | DEX 中继中一个只增不减的数字。客户端轮询自某个游标“以来”的变化。 |
| **Dexicon** | 一个 DEX 应用，DEX 中继 cape 承载的就是它的订单簿。 |
| **信封**（Envelope） | DEX 中继接受的已签名 JSON 订单或消息。 |
| **网关**（Gateway） | 带允许列表的 `POST /cmd` 代理，手机用它读取钱包信息和广播交易。 |
| **心跳**（Heartbeat） | 节点每 30 秒记录一次的状态行。 |
| **IBD** | 初始区块链下载：即首次同步。它不会填充 MegaMMR。 |
| **identity.txt** | 固定保存的助记词，节点的 Maxima 身份和账户的 `MAX#` 由它派生。与钱包同等级别的机密。 |
| **keets / KeetChat** | 一个点对点聊天系统。节点通过一个 Node.js 附属进程与它桥接。 |
| **长轮询**（Long-poll） | 服务器保持打开（这里最多 25 秒）直到有新消息的 HTTP 请求。 |
| **MAX#** | 账户永久、可分享的地址。 |
| **MegaMMR** | Minima 的一种模式，为每个币保存证明，使节点能为任何地址作答。 |
| **网格**（Mesh） | 中继之间用于转发目录查询和复制目录条目的网络。 |
| **MDS / MiniHUB** | Minima 的 MiniDapp 系统及其 hub 界面。 |
| **MxUSD** | Minima 上与美元挂钩的代币，代币 id 为 `0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90`。账户的支付功能通过这个 id 识别它。 |
| **Mx… 身份**（Mx… identity） | 中继或账户的 Maxima 公开身份。 |
| **面板**（Panel） | 账户在 `127.0.0.1:9587` 上的本地网页界面和 API。 |
| **池主机**（Pool host） | 应答开放目录查询并参与网格的中继。 |
| **REDIAL_MS / TTL_MS** | 中继对已验证对等节点的 10 分钟重新回拨间隔和 2 小时过期时间。 |
| **Rootnode** | 节点最初用来发现网络的一个 Minima 对等节点。 |
| **共用端口**（Shared port） | 中继借用 Minima P2P 端口运行（`parlons.relay.port=shared`）。 |
| **STUN** | 中继端口上的一个小型 UDP 服务，告诉客户端它们的公网地址。 |
| **租户主机**（Tenants host） | 一个单独的 `parlons-cloud` 服务，在节点旁边运行访客账户。 |
| **TLS 前端**（TLS front） | 终止 HTTPS 并转发到回环端口的 Web 服务器（Caddy、nginx 或 Apache）。 |
| **Vault** | 保存节点钱包助记词的 Minima 命令和存储。 |
| **已验证的对等节点**（Verified peer） | 其声明的地址已通过回拨得到确认的中继。 |
