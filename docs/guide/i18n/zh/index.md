# 选择版本，完成设置，开始聊天

选择适合您的指南，从下载到第一次对话一步步操作。**两个 Android 应用用途不同：**Parlons! 将身份保存在手机上；Parlons Cloud 连接运行在其他机器上的账户。

<div class="cards">
<a href="start-android.html"><b>Android · 独立应用</b><span>无需安排账户服务器，身份保存在这部手机上。</span></a>
<a href="start-desktop.html"><b>Mac、Windows 或 Linux · 桌面应用</b><span>在电脑上使用独立身份聊天。</span></a>
<a href="start-node.html"><b>Parlons Cloud · 您的节点、设备和朋友</b><span>运行完整 Minima 节点和中继，配对设备，为朋友托管独立租户账户。</span></a>
<a href="start-pair.html"><b>我已有邀请 · Parlons Cloud</b><span>将 Android 手机连接到已有账户。</span></a>
<a href="start-host.html"><b>我想运行自己的常久在线账户</b><span>安装较轻量的 Cloud 服务器，再配对手机。</span></a>
</div>

iPhone 应用**尚未提供**。Android 应用需要 Android 9 或更新版本。桌面下载适用于 Apple 芯片 Mac、Windows 10/11 和 x86-64 Debian/Ubuntu。

## Android 上的 Parlons! {#chat-on-your-android-phone}

**适合您，如果：**您想安装一个应用就开始聊天，无需安排账户服务器。身份、联系人和聊天记录保存在这部手机上，应用通过 Parlons 中继网络连接。

### 安装并创建身份

1. 在手机上下载 [Parlons! {{ANDROID_VERSION}} Android 版]({{ANDROID_APK}})。
2. 打开 APK，点击 **Install（安装）**。如有提示，允许浏览器安装应用。
3. 打开 **Parlons!**，选择语言，点击 **Create new identity（创建新身份）**。
4. 按顺序在纸上写下全部 24 个词。检查后点击 **I've saved them（我已保存）**。这些词控制您的身份，也可能控制钱包，请保密。
5. 允许通知。在**三点菜单 → Settings（设置）**中填写 **Your name（您的名字）**，点击 **Set（设置）**。
6. 查看 **Network（网络）**。**Connected（已连接）**表示至少已连接一个中继，可以添加联系人了。

**已有身份？**恢复前先阅读[备份和迁移指南](parlons.html#20-backups-and-moving-to-a-new-device)。新身份有不同地址。在多台同时运行的设备上恢复同一助记词不能同步聊天。

**日常维护：**在 Android 电池设置中允许后台运行。用新版 APK 覆盖安装更新；卸载或清除数据前先备份。

## Parlons Desktop {#chat-on-your-computer}

**适合您，如果：**您想在电脑上聊天。这是在该电脑上的独立身份，与手机同时安装不会自动共享聊天。

### 为您的电脑安装

1. 下载匹配的安装程序：
   - **Apple 芯片 Mac：**[MaximaNode-{{DESKTOP_VERSION}}-notarized.dmg]({{DESKTOP_DMG}})。打开后将 **MaximaNode** 拖到 Applications（应用程序）。
   - **Windows 10/11：**[MaximaNode-{{DESKTOP_VERSION}}.msi]({{DESKTOP_MSI}})。打开并按照安装提示操作。
   - **Debian/Ubuntu，x86-64：**[maximanode_{{DESKTOP_VERSION}}_amd64.deb]({{DESKTOP_DEB}})。在下载目录运行 `sudo apt install ./maximanode_{{DESKTOP_VERSION}}_amd64.deb`。
2. 打开 **MaximaNode**，这是 Parlons Desktop 安装后的名称。
3. 在 **Back up your Maxima seed（备份 Maxima 助记词）**窗口中，将 24 个词依次写在纸上并妥善保管。
4. 打开**三点菜单**，填写 **Your name**，点击 **Set**。
5. 在 **Network** 中确认连接，然后按下方步骤添加联系人。桌面端粘贴地址，不支持摄像头扫描二维码。

**通话：**桌面端支持消息和文件，但不传输语音或视频通话。请使用 Android 应用通话。保持 Desktop 运行以保持连接；要退出时使用托盘图标的 **Quit（退出）**。

安装问题或迁移已有身份，请参阅 [Parlons! 手册](parlons.html)。

## Parlons Cloud：使用邀请连接 {#pair-an-account}

**适合您，如果：**您已有账户邀请，或在自己的机器上运行了账户。Android 应用是账户的远程界面：身份和聊天记录存放在主机上，多部配对手机访问同一账户。

**开始之前：**向托管者或已配对的设备获取邀请。如果没有主机，先[托管自己的账户](start-node.html)。朋友的联系人二维码用于和对方聊天；配对邀请授予账户访问权限，两者不同。

### 安装并配对

1. 下载 [Parlons Cloud {{PORTAL_VERSION}} Android 版]({{PORTAL_APK}})。打开 APK，按提示允许安装并点击 **Install**。
2. 打开 **Parlons Cloud**，点击 **Scan pairing QR（扫描配对二维码）**。或将完整邀请粘贴到 **Account address or pairing invite（账户地址或配对邀请）**。
3. 点击 **Connect & pair（连接并配对）**。状态先显示正在连接网络，再显示找到账户并配对，然后打开账户。
4. 允许通知。在 **More → Settings → This device（更多 → 设置 → 此设备）**中，如有 **Keep running in the background（保持后台运行）**，点击允许。
5. 打开 **Node（节点）**查看账户连接和已配对设备，然后按下方步骤开始对话。

**配对码失效？**它可能已使用或被新码替换，请索取新邀请。配对码只能使用一次；使用前请保密。只想让别人给您发消息时，分享联系人地址即可。

**添加另一部手机：**在已配对手机上打开 **Node → Pair my other device（配对我的另一台设备）**，如有提示输入管理员密码，在新手机扫描新邀请。丢失手机时，在 **Node → Paired devices → Revoke（已配对设备 → 撤销）**中断开它。

**使用他人主机：**管理员能访问机器上保存的密钥和历史记录，请选择可信赖的人。内置访客账户提供聊天、通话和文件，不提供钱包或节点控制。其他托管类型可能不同，详见 [Cloud 手册](cloud.html)。

## 替代方案：轻量账户托管 {#one-account-on-all-your-devices}

**适合您，如果：**您希望手机关闭时账户仍在线，并将多部手机配对到同一账户。**Parlons Cloud 服务器**较轻量，无需运行完整 Minima 区块链节点；Android Cloud 应用连接到它。

**准备：**一台保持唤醒和联网的 Mac、Linux 电脑、树莓派或 Windows PC。Mac 需先安装 Java 11+ 或具备 Homebrew；Linux 可能需要 sudo 安装依赖。账户不需要域名。这会创建账户；迁移已有身份请先阅读[迁移账户](cloud.html#move-an-account-to-another-machine)。

### 在主机上安装

1. **Mac 或 Linux：**在常开机器的终端运行：

   ```
   curl -fsSL {{INSTALL_SH}} | bash
   ```

   **Windows 10/11：**在 PowerShell 中运行：

   ```
   irm {{INSTALL_PS1}} | iex
   ```

   作者尚未在 Windows 上验证此安装程序。需要经过验证的服务器方案时，优先选择受支持的 Linux 主机。
2. 等待依赖安装、版本下载和账户启动完成。妥善保管输出的邀请。Mac/Linux 可显示二维码，Windows 输出可粘贴的邀请。
3. 在 Android 手机上按照 [Cloud 安装和配对指南](start-pair.html)操作。
4. 在主机打开新终端，运行 `parlons status` 检查状态。`parlons panel` 打开本地网页面板；`parlons newcode` 创建新邀请。
5. 在已配对手机的 **More → Settings → Show seed phrase（显示助记词）**中私下记录身份词语，再用 **Back up account…（备份账户…）**保存加密备份。

**主机须保持唤醒。**Mac 和 Windows 服务在登录时启动；休眠或离线的主机无法提供账户服务。安装程序默认还在 TCP 9501 启用中继。账户通过出站连接工作；为他人提供公共中继还需开放该端口。[托管选项和中继设置](cloud.html#19-one-account-on-your-own-machine-the-one-line-installers)。

**日常维护：**`parlons log` 查看日志。Mac/Linux 使用 `parlons update` 更新，Windows 重新运行安装程序。更新前备份。为朋友创建独立身份，请使用 **Node → Manage hosted accounts（管理托管账户）**，不要给出您自己账户的配对邀请。[托管朋友](cloud.html#11-hosting-friends-guest-invitations)。

## 运行您的 Parlons Cloud 节点 {#run-a-full-node}

**运行 Parlons Cloud，为网络提供最全面的支持。**您的常开机器运行**完整 Minima 节点和完整中继**，让您在多台配对设备上使用同一账户，还能邀请朋友使用**独立租户账户**。每位朋友都有自己的身份、联系人和对话。

完整 Cloud 方案安装的软件名为 **Parlons Node**。Android **Parlons Cloud** 应用将手机连接到它。未来 iPhone 应用也会使用这种方式，目前尚未提供。

**准备：**保持在线、带 systemd 和 sudo/root 权限的 Linux；可使用 64 位树莓派。参照[节点要求](node.html#3-requirements)规划内存和不断增长的链存储。手册建议普通节点配 2 GB 内存，MegaMMR 配 8 GB。安装程序可能在较小机器上开启 MegaMMR，请主动选择模式。

### 安装、配对和检查

1. **不带 MegaMMR 的普通完整节点**，在服务器终端运行：

   ```
   curl -fsSL {{NODE_INSTALL_SH}} | bash -s -- --no-megammr
   ```

   若要带 MegaMMR 的钱包网关，请按[完整安装指南](node.html#5-three-ways-to-install)配置内存。
2. 安装程序会按需添加 Java，启动系统服务，并在受支持且启用的主机防火墙上开放 TCP 9001、9501。还应检查云防火墙或家用路由器。区块链同步可能比账户启动更久。
3. 按提示设置**管理员配对密码**，再输入密码显示邀请二维码。以后可运行 `sudo parlons-pair --admin`。
4. 在手机上按 [Cloud 配对指南](start-pair.html)扫描邀请，点击 **Connect & pair**。
5. 用 `systemctl is-active parlons-node` 检查服务，用 `sudo journalctl -u parlons-node -n 50 --no-pager` 查看最近日志。
6. 按[助记词、身份与备份](node.html#20-seeds-identity-and-backups)备份。聊天身份和节点钱包助记词可能不同，需要时两者都保存。

使用手机的 **Node** 管理账户和设备。节点所有者拥有节点工具，访客没有。更新、监控和恢复见 [Node 手册](node.html)。

### 在其他设备上使用同一账户

1. 在已配对手机上打开 **Node → Pair my other device（配对我的另一台设备）**。
2. 按提示输入**管理员密码**，显示新的二维码或复制邀请。
3. 在另一部 Android 设备安装 **Parlons Cloud**，扫描或粘贴邀请，点击 **Connect & pair**。它打开相同账户、联系人和对话。每台设备使用一个新邀请。

配对授予已有账户的访问权限，不创建独立身份副本。电脑可通过[配对命令行客户端](cloud.html#how-to-drive-an-account-from-another-machine)访问账户；安装独立 Parlons Desktop 不会自动配对。

### 邀请朋友使用独立租户账户

**租户**是托管在您 Cloud 节点上的独立账户。朋友无需运行服务器，也能拥有自己的身份和对话。

1. 在已配对手机上打开 **Node → Manage hosted accounts（管理托管账户）**。
2. 首次选择 **Hosting options → Enable hosting（托管选项 → 启用托管）**。须先设置管理员密码；保持节点运行，朋友才能在线。
3. 点击 **Create account（创建账户）**，输入朋友的账户名称，再点击 **Create**。
4. 点击 **Refresh（刷新）**，直到显示 **running（运行中）**。选择账户并点击 **Show invitation（显示邀请）**。
5. 私下将此邀请交给朋友。朋友安装 **Parlons Cloud**，扫描或粘贴邀请，再点击 **Connect & pair**。
6. 为下一位朋友创建另一个租户账户。**Pair my other device** 仅用于添加您自己的设备。

内置托管最多支持十个仅聊天访客账户，可发消息、通话和传文件，没有您的钱包或节点控制权限。独立租户服务还支持带各自钱包的完整账户，见[托管选项](cloud.html#21-many-accounts-the-parlons-tenants-service)。作为主机管理员，您存储他们的数据和密钥，请邀请信任您的人，并帮助他们备份。

### 支持网络，为 iPhone 做准备

保持节点在线且完整中继可从外部访问，让它也能为其他人传递消息。设置防火墙或路由器后，请按[外部可达性指南](node.html#23-verifying-from-outside)检查。

**Parlons Cloud 将是在 iPhone 上使用 Parlons! 的方式。**iPhone 应用将连接您的 Cloud 节点，或朋友节点上的租户账户。应用尚未提供，但账户和托管方式已可使用。

## 第一次对话 {#add-a-friend}

1. 向朋友索取**联系人地址或二维码**，而不是配对邀请。
2. 打开 **Contacts → + Add（联系人 → 添加）**；桌面端为 **Add contact**。
3. Android 扫码或粘贴完整地址；桌面端粘贴。点击 **Introduce myself（介绍自己）**。
4. 等待对方设备回复介绍。如果对方离线，联系人可能不会立刻出现。
5. 打开聊天，输入消息，点击发送箭头，请对方回复。

让别人添加您时，从 **Contacts** 分享完整地址。使用不同 Parlons 版本的人可以互相发消息。

## 日常使用 {#everyday-use}

- **消息：**在 **Chats（聊天）**中打开对话并输入。排队图标表示等待；一个勾表示中继收到；两个勾表示接收设备确认收到。已读标志取决于是否开启已读回执。
- **照片和文件：**在聊天中选择 **Attach（附件）**。Android 可选 **Photo library（照片库）**或 **Private file（私密文件）**，选中后发送。桌面传输选项见[应用手册](parlons.html)。
- **通话：**Android 一对一聊天中点击 **Voice call（语音通话）**或 **Video call（视频通话）**，按提示允许麦克风和摄像头。桌面端不支持通话音视频。
- **群组：**Android 上打开 **Chats → New group（新建群组）**，命名、选择联系人并点击 **Create（创建）**。
- **通知：**在 Android 设置中允许通知和后台运行。Cloud 的账户主机也必须在线。

## 提前做好备份 {#backups}

**24 个词恢复身份，不恢复旧聊天记录。**将它们写在纸上私密保存，不要发给任何人或输入网站。若有独立钱包助记词，也要保存。

- **Parlons! Android：****Settings → Keys & security → Back up identity…** 创建加密 `.pbk`，包含身份、联系人和设置，不含聊天历史。
- **Parlons Cloud：****More → Settings → Keys & backup → Back up account…** 创建加密 `.pbk`，包含联系人和聊天历史，但不含媒体和私密文件内容。
- **桌面端：**保存助记词，更换电脑前阅读[备份和迁移](parlons.html#20-backups-and-moving-to-a-new-device)。

将备份保存在原设备之外，并保存好密码，密码无法代为找回。多部手机共用一个账户应采用配对，不要独立运行同一种子的多个副本。

## 遇到问题时 {#need-more}

| 问题 | 可以尝试 |
|---|---|
| Cloud 无法配对 | 检查主机在线，粘贴完整邀请，使用新的一次性配对码。 |
| 介绍一直等待 | 请对方打开应用并检查连接。 |
| 只有打开应用才收到消息 | 允许通知和后台运行；Cloud 还应检查主机。 |
| 始终一个勾 | 中继已收到，对方设备尚未确认，检查对方连接。 |
| 已配对手机丢失 | 从另一部已配对手机的 Node 页面撤销该设备。 |

完整说明：[Parlons! 手册](parlons.html)、[Cloud 手册](cloud.html)、[Node 手册](node.html)。[报告问题]({{ISSUES}})时请写明应用名称、版本、设备和确切错误，不要提供助记词、密码或未使用的配对邀请。

## 当前下载 {#downloads}

| 安装内容 | 当前版本 |
|---|---|
| Parlons! Android · 独立身份 | [{{ANDROID_VERSION}} · APK]({{ANDROID_APK}}) |
| Parlons Desktop · Apple 芯片 Mac | [{{DESKTOP_VERSION}} · DMG]({{DESKTOP_DMG}}) |
| Parlons Desktop · Windows 10/11 | [{{DESKTOP_VERSION}} · MSI]({{DESKTOP_MSI}}) |
| Parlons Desktop · Debian/Ubuntu x86-64 | [{{DESKTOP_VERSION}} · DEB]({{DESKTOP_DEB}}) |
| Parlons Cloud Android · 配对客户端 | [{{PORTAL_VERSION}} · APK]({{PORTAL_APK}}) |
| Parlons Cloud 服务器 | {{CLOUD_VERSION}} · 参照上方托管指南 |
| Parlons Node | [{{NODE_VERSION}} · JAR]({{NODE_JAR}}) · 参照 Node 指南 |

应用与服务器各有版本号，数字更大不代表更适合您。发布说明和校验和见 [GitHub]({{RELEASES}})。
