# Parlons 入门

私密聊天，中间没有任何公司。不用注册，不用手机号。选择你想做的事，然后按步骤操作。

<div class="cards">
<a href="#chat-on-your-android-phone"><b>在 Android 手机上聊天</b><span>安装一个应用就能开始聊天。</span><em>Android 9+</em></a>
<a href="#chat-on-your-computer"><b>在电脑上聊天</b><span>Mac、Windows 或 Linux。</span><em>Parlons Desktop {{DESKTOP_VERSION}}</em></a>
<a href="#one-account-on-all-your-devices"><b>一个账户，所有设备通用</b><span>每部手机和每台电脑上都是同样的聊天。</span><em>Parlons Node {{NODE_VERSION}}</em></a>
</div>

> **你的 24 个词既是你的身份，也是一个钱包。** 它们显示出来时，请写在纸上。千万不要拍照。谁拿到这些词，谁就能冒充你。

## 在 Android 手机上聊天 {#chat-on-your-android-phone}

你需要 Android 9 或更高版本，还要准备纸和笔。

1. 在手机上下载 [parlons-{{ANDROID_VERSION}}.apk]({{ANDROID_APK}})。
2. 打开下载的文件，点 **安装**。如果 Android 询问，请允许从浏览器安装应用。
3. 点 **打开**。选择你的语言。
4. 点 **创建新身份**。
5. 把 24 个词按顺序写在纸上，连同它们的编号。逐个核对，然后点 **我已保存**。
6. Android 询问是否允许发送通知时，点 **允许**。
7. 点右上角的 **三个点**，再点 **设置**。在 **您的名称** 下面输入你的名字，然后点 **设置**。

现在去[添加好友](#add-a-friend)。

## 在电脑上聊天 {#chat-on-your-computer}

1. 下载适合你电脑的 Parlons Desktop：
   - 使用 Apple 芯片的 Mac：[MaximaNode-{{DESKTOP_VERSION}}-notarized.dmg]({{DESKTOP_DMG}})
   - Windows 10 或 11：[MaximaNode-{{DESKTOP_VERSION}}.msi]({{DESKTOP_MSI}})
   - Linux（Debian、Ubuntu）：[maximanode_{{DESKTOP_VERSION}}_amd64.deb]({{DESKTOP_DEB}})
2. 安装：
   - **Mac：** 打开磁盘映像，把 **MaximaNode** 拖进“应用程序”文件夹。
   - **Windows：** 运行安装程序。如果出现蓝色的 **Windows 已保护你的电脑** 提示框，点 **更多信息**，再点 **仍要运行**。
   - **Linux：** 在保存文件的文件夹里运行 `sudo apt install ./maximanode_{{DESKTOP_VERSION}}_amd64.deb`
3. 打开 **MaximaNode**。
4. 窗口里会显示你的 24 个词。按顺序把它们写在纸上。
5. 点右上角的 **三个点**。在 **您的名称** 下面输入你的名字，然后点 **设置**。

现在去[添加好友](#add-a-friend)。

## 一个账户，所有设备通用 {#one-account-on-all-your-devices}

你的账户运行在一台一直开着的 Linux 机器上，比如一台小服务器，或者一台装了 64 位系统的 Raspberry Pi。你的手机和电脑都连接到它，所以每台设备上都是同样的聊天；手机关机时，消息会在那里等你。

这个程序叫 **Parlons Node**：它把你的账户、一个帮助网络传送消息的中继和一个完整的 Minima 节点合在一起。

**在 Linux 机器上：**

1. 在它上面打开终端，或者用 SSH 连接到它。
2. 粘贴下面这行，然后按回车：

   ```
   curl -fsSL {{NODE_INSTALL_SH}} | bash
   ```

3. 等待。它会安装所需的一切，并启动你的节点。这需要几分钟。
4. 提示时，设置一个 **管理员密码**，输入两遍。以后每次添加设备都要用到它。
5. 再次提示时，输入一遍密码。屏幕上会出现一个二维码。

**在手机上：**

1. 在 Android 手机上安装 [Parlons Cloud]({{PORTAL_APK}})。（iPhone 应用正在开发中，暂时还不能用。）
2. 打开它，点 **扫描配对二维码**。用摄像头对准那个二维码。
3. 点 **连接并配对**。你的账户就会打开。

**之后：**

- 要添加另一部手机或电脑，在 Linux 机器上运行 `sudo parlons-pair --admin`，然后扫描新的二维码。
- 备份你节点的 24 个词：[如何备份助记词](node.html#20-seeds-identity-and-backups)。
- 用的是 Mac 或 Windows 电脑，而不是 Linux？那就运行更轻量、只含账户功能的程序 [Parlons Cloud](cloud.html#19-one-account-on-your-own-machine-the-one-line-installers)。

## 添加好友 {#add-a-friend}

添加好友就像握手：你先介绍自己，对方设备回应后，对方就会出现在你的列表里。

1. 打开 **联系人** 标签页。
2. 点 **+ 添加**（在电脑上点 **添加联系人**）。
3. 扫描对方的二维码，或者粘贴对方的地址。
4. 点 **发送介绍**。

把你的地址告诉别人：打开 **联系人**，点 **分享我的地址**。对方可以扫描二维码，你也可以把地址发给对方。

## 下载 {#downloads}

所有文件都在[发布页面]({{RELEASES}})上，并附有校验和。

| 适用于 | 下载 |
|---|---|
| Android 手机 | [parlons-{{ANDROID_VERSION}}.apk]({{ANDROID_APK}}) |
| Mac（Apple 芯片） | [MaximaNode-{{DESKTOP_VERSION}}-notarized.dmg]({{DESKTOP_DMG}}) |
| Windows | [MaximaNode-{{DESKTOP_VERSION}}.msi]({{DESKTOP_MSI}}) |
| Linux 电脑 | [maximanode_{{DESKTOP_VERSION}}_amd64.deb]({{DESKTOP_DEB}}) |
| 连接到你账户的 Android 手机 | [parlons-cloud-portal-{{PORTAL_VERSION}}-release.apk]({{PORTAL_APK}}) |
| Linux 服务器或 Raspberry Pi | `curl -fsSL {{NODE_INSTALL_SH}} \| bash` |

## 想了解更多？ {#need-more}

每个功能都有分步说明：[Parlons! 手册](parlons.html)（应用）、[Parlons Cloud 手册](cloud.html)（你的手机和账户）和 [Parlons Node 手册](node.html)（你的服务器）。遇到问题？每本手册的最后都有故障排除，你也可以到[问题反馈页]({{ISSUES}})提问。
