# Choose, set up, start talking

Pick the guide for the way you want to use Parlons. Each takes you from the download to your first conversation. **The two Android apps do different jobs:** Parlons! keeps your identity on your phone; Parlons Cloud connects to an account running elsewhere.

<div class="cards">
<a href="start-android.html"><b>Android · independent app</b><span>No account server to arrange. Your identity lives on this phone.</span></a>
<a href="start-desktop.html"><b>Mac, Windows or Linux · desktop app</b><span>Run Cloud on your computer: full node, relay, paired devices and hosted friends.</span></a>
<a href="start-node.html"><b>Parlons Cloud · your node, your devices, your friends</b><span>Run a full Minima node and relay. Pair your devices and host separate tenant accounts.</span></a>
<a href="start-pair.html"><b>I have an invitation · Parlons Cloud</b><span>Connect your Android phone to an existing account.</span></a>
<a href="start-host.html"><b>I want my own always-on account</b><span>Run the lighter Cloud server, then pair your phone.</span></a>
</div>

An iPhone app is **not available yet**. Android apps require Android 9 or newer. The desktop downloads below are for Apple silicon Macs, Windows 10/11, and Debian/Ubuntu on x86-64.

## Parlons! on Android {#chat-on-your-android-phone}

**Choose this if:** you want to install one app and chat, without arranging an account server. Your identity, contacts and chats live on this phone. It connects through the Parlons relay network.

### Install and create your identity

1. Download [Parlons! {{ANDROID_VERSION}} for Android]({{ANDROID_APK}}) on your phone.
2. Open the APK and tap **Install**. If Android asks, allow your browser to install apps.
3. Open **Parlons!**, choose your language and tap **Create new identity**.
4. Write all 24 words on paper, in order. Check them, then tap **I've saved them**. Keep them private: they control your identity and may also control a wallet.
5. Allow notifications. Open the **three dots → Settings**, enter **Your name**, and tap **Set**.
6. Check the **Network** tab: **Connected** means at least one relay is connected. You are ready to add a contact below.

**Already have an identity?** Use the [backup and moving guide](parlons.html#20-backups-and-moving-to-a-new-device) before restoring it. Creating a new identity gives you a different address. Restoring the same seed on several running devices is not a way to synchronise chats.

**Keep it working:** allow the app to run in the background in Android's battery settings. Update by installing the newer APK over the existing app; back up before uninstalling or clearing its data.

## Parlons Desktop {#chat-on-your-computer}

**Choose this if:** you want your computer to be your Parlons Cloud. **minimaCore Desktop** and **minimaDesk** run a full Parlons Node with the same account services as a Cloud server: messages, calls, files, payments, paired devices and separate hosted accounts. Enable network contribution and port forwarding to provide its public relay service.

### Run Cloud on your desktop

1. Install [minimaCore Desktop](https://github.com/eurobuddha/minimacore-desktop/releases/latest) or [minimaDesk](https://github.com/eurobuddha/minimaDesk/releases/latest) for your operating system. minimaDesk is also listed as **MinimaClassic Desktop** in the stores. You can find both through [eurobuddha](https://eurobuddha.com/).
2. Complete the node setup and back up the seed. Use the **Parlons Node** kind and open the **Parlons** tab. Older installations may still use the classic node; follow the app's migration flow when switching an existing wallet.
3. Enable **Contribute to the network** in the desktop app's node/network settings. In **Parlons → Node → Port forwarding**, read the external port, internal port and **Send to** LAN address. Forward that **TCP** port on your router to the address shown, and allow it through the computer's firewall. Use the displayed values, not a port copied from another app.
4. Reserve that LAN address on your router and leave the computer awake and online. Check the panel for **Reached from the internet**. The public port serves Minima and the Maxima relay; the local account panel and admin RPC stay local. Pairing can work through other relays before forwarding, but accepting public relay traffic needs reachability.
5. Open **Parlons → Devices**, set the admin pairing password if needed, and choose **New pairing code**. Install [Parlons Cloud on your phone](start-pair.html), scan the invitation and connect. Repeat with a fresh invitation for each of your devices. They share this computer's account and conversations.
6. In **Devices → Hosted accounts**, enable hosting, create a separate account for your friend, then choose **Show invitation** or **Copy tenant invite**. Your friend pairs to that account. Built-in guest accounts provide chat, calls and files without your wallet or node controls; do not give friends your owner invitation.

For hosting and backups, see the [Cloud manual](cloud.html). Calls in the desktop Parlons panel use its microphone/camera; allow access when prompted.

### Alternative: standalone chat with MaximaNode {#standalone-chat}

**MaximaNode {{DESKTOP_VERSION}}** is the separate standalone Parlons Desktop app. It can chat, share files and run a relay, but it is not the full desktop Cloud host described above. Choose minimaCore Desktop or minimaDesk for paired accounts and hosting friends.

1. Download the matching installer:
   - **Mac, Apple silicon:** [MaximaNode-{{DESKTOP_VERSION}}-notarized.dmg]({{DESKTOP_DMG}}). Open it and drag **MaximaNode** into Applications.
   - **Windows 10/11:** [MaximaNode-{{DESKTOP_VERSION}}.msi]({{DESKTOP_MSI}}). Open it and follow the installer.
   - **Debian/Ubuntu, x86-64:** [maximanode_{{DESKTOP_VERSION}}_amd64.deb]({{DESKTOP_DEB}}). In the download folder run `sudo apt install ./maximanode_{{DESKTOP_VERSION}}_amd64.deb`.
2. Open **MaximaNode**. This is the installed name of Parlons Desktop.
3. In **Back up your Maxima seed**, write the 24 words on paper in order and keep them private.
4. Open the **three dots** menu, enter **Your name** and click **Set**.
5. Check **Network** for a connection, then add a contact below. On desktop, paste their address; there is no QR camera scanner.

**Calls:** standalone MaximaNode supports messaging and files, but does not carry voice or video call audio. Use one of the Android apps for calls. Keep Desktop running to stay connected; use the tray icon's **Quit** when you want it to stop.

For installation problems or moving an existing identity, use the [Parlons! manual](parlons.html).

## Parlons Cloud: connect with an invitation {#pair-an-account}

**Choose this if:** you already have an account invitation, or an account running on your own machine. The Android app is a remote view of that account: the identity and chat history live on the host. Multiple paired phones see the same account.

**Before you start:** get an invitation from your host or an already-paired device. If you do not have a host, follow [host your own account](start-node.html) first. A friend's contact QR lets you chat with them; a pairing invitation gives access to an account. They are different.

### Install and pair

1. Download [Parlons Cloud {{PORTAL_VERSION}} for Android]({{PORTAL_APK}}). Open the APK, allow installation if asked, and tap **Install**.
2. Open **Parlons Cloud**. Tap **Scan pairing QR** and scan your invitation. Or paste the complete invitation into **Account address or pairing invite**.
3. Tap **Connect & pair**. You should see **Attaching to the network…**, then **Account found — pairing…**, then your account opens.
4. Allow notifications. In **More → Settings → This device**, choose **Keep running in the background** if shown.
5. Open **Node** to check the account's connection and paired devices. Continue to your first conversation below.

**If the code fails:** it may already have been used or replaced. Ask for a fresh invitation. Codes work once. Keep invitations private until used; share your contact address when you only want someone to message you.

**Add another phone:** on a paired phone open **Node → Pair my other device**. Enter the admin password if asked; scan the new invitation on the new phone. To disconnect a lost phone, use **Node → Paired devices → Revoke**.

**Using someone else's host:** the operator can access the keys and history stored on that machine. Choose someone you trust. Built-in guest accounts provide chat, calls and files; they do not include wallet or node controls. Other hosted account types may differ. See the [Cloud manual](cloud.html).

## Alternative: lighter account hosting {#one-account-on-all-your-devices}

**Choose this if:** you want your account to stay online while your phone is off, and want to pair several phones to it. **Parlons Cloud server** is the lighter option: it runs the account without a full Minima blockchain node. The Android Cloud app connects to it.

**You need:** a Mac, Linux computer, Raspberry Pi or Windows PC that stays awake and online. On Mac, install Java 11+ first, or have Homebrew available. Linux may ask for sudo to install dependencies. You do not need a domain for the account. This creates an account; for an existing identity, read [moving an account](cloud.html#move-an-account-to-another-machine) first.

### Install on the host machine

1. On **Mac or Linux**, open Terminal on the machine that will stay on and run:

   ```
   curl -fsSL {{INSTALL_SH}} | bash
   ```

   On **Windows 10/11**, open PowerShell and run:

   ```
   irm {{INSTALL_PS1}} | iex
   ```

   The Windows installer is supplied but has not yet been verified on Windows by its authors. Prefer a supported Linux host if you need an established server setup.
2. Wait for the installer to finish. It installs dependencies, downloads the release and starts the account. Keep the invitation it prints private. Mac/Linux can show a QR; Windows prints an invitation to paste.
3. On your Android phone, follow [install and pair Parlons Cloud](start-pair.html).
4. Open a new terminal on the host and run `parlons status` to check it. `parlons panel` opens its local web panel; `parlons newcode` creates another pairing invitation.
5. In the paired phone, use **More → Settings → Show seed phrase** to record your identity words privately, then **Back up account…** to save an encrypted backup.

**Leave the host awake.** Mac and Windows start the service at login; a sleeping or offline host cannot serve your account. The installer also enables a relay on TCP 9501 by default to help others. Account access works through outbound connections; public relay service additionally needs that port reachable. [Hosting options and relay settings](cloud.html#19-one-account-on-your-own-machine-the-one-line-installers).

**Everyday maintenance:** `parlons log` shows the log. On Mac/Linux, `parlons update` updates the server; on Windows rerun the installer. Keep backups before updates. To give a friend their own identity, use **Node → Manage hosted accounts**, not an invitation to your own account. [Hosting friends](cloud.html#11-hosting-friends-guest-invitations).

## Run your Parlons Cloud node {#run-a-full-node}

**Run Parlons Cloud to give the network its fullest support.** Your always-on machine runs a **full Minima node and a full relay**, keeps your account available on multiple paired devices, and lets you invite friends to **separate tenant accounts**. Each friend gets their own identity, contacts and conversations.

The software you install for this complete Cloud setup is called **Parlons Node**. The Android **Parlons Cloud** app connects your phone to it. The future iPhone app will use this same approach; it is not available yet.

**You need:** always-on Linux with systemd and sudo/root access; a 64-bit Raspberry Pi can be used. Plan RAM and growing chain storage using the [Node requirements](node.html#3-requirements). The manual recommends 2 GB RAM for a plain node or 8 GB for MegaMMR. The installer may enable MegaMMR on smaller machines, so select the mode deliberately.

### Install, pair and check

1. For a **plain full node without MegaMMR**, run this in the server's terminal:

   ```
   curl -fsSL {{NODE_INSTALL_SH}} | bash -s -- --no-megammr
   ```

   For a wallet gateway with MegaMMR, follow the [full installation guide](node.html#5-three-ways-to-install) to size memory and configure it.
2. The installer adds Java if needed, starts a system service and opens TCP 9001 and 9501 in supported active host firewalls. Check any provider firewall or home router too. Chain sync can take longer than account startup.
3. When asked, set an **admin pairing password**, then enter it to show your invitation QR. If you need it later, run `sudo parlons-pair --admin`.
4. On your Android phone, follow [install and pair Parlons Cloud](start-pair.html). Scan the invitation, then tap **Connect & pair**.
5. Check the server with `systemctl is-active parlons-node`. Read recent logs with `sudo journalctl -u parlons-node -n 50 --no-pager`.
6. Follow [seeds, identity and backups](node.html#20-seeds-identity-and-backups). The chat identity and node wallet phrase can differ; preserve both where applicable.

Use **Node** in the paired app for account and device management. Node owners also get node tools; guests do not. For updates, monitoring and recovery, keep the [Node manual](node.html) handy.

### Use your account on your other devices

1. On your already-paired phone, open **Node → Pair my other device**.
2. Enter your **admin password** when asked. Show the new QR or copy the invitation.
3. On your other Android device, install **Parlons Cloud**, scan or paste that invitation, and tap **Connect & pair**. It opens the same account, contacts and conversations. Create a fresh invitation for each device.

Pairing gives access to the existing account; it does not create another independent copy of your identity. For computer access through the paired command-line client, see [driving an account from another machine](cloud.html#how-to-drive-an-account-from-another-machine). Installing standalone Parlons Desktop does not pair it automatically.

### Invite friends to their own tenant accounts

A **tenant** is a separate account hosted on your Cloud node. Your friend can use Parlons without running their own server, while keeping their own identity and conversations.

1. On your paired phone, open **Node → Manage hosted accounts**.
2. The first time, select **Hosting options → Enable hosting**. The admin password must already be set. Keep your node running to keep your friends online.
3. Tap **Create account**, enter a name for your friend’s account, and tap **Create**.
4. Tap **Refresh** until it shows **running**. Select that account and choose **Show invitation**.
5. Give this invitation privately to your friend. They install **Parlons Cloud**, scan or paste it, and tap **Connect & pair**.
6. For another friend, create another tenant account. Use **Pair my other device** only when adding devices to your own account.

The built-in host supports up to ten chat-only guest accounts: messages, calls and files, without your wallet or node controls. A separate tenants service also supports full accounts with their own wallets; see [hosting options](cloud.html#21-many-accounts-the-parlons-tenants-service). As the host, you store their account data and keys, so invite people who trust you and help them keep backups.

### Help the network, and prepare for iPhone

Keep the node online and its full relay reachable so it can carry other people’s messages as well as yours. Check the [external reachability instructions](node.html#23-verifying-from-outside) after setting up your firewall or router.

**Parlons Cloud will be the way to use Parlons! on iPhone.** The iPhone app will connect to your own Cloud node or to a tenant account on a friend’s node. It is not available yet; the account and hosting model are already here.

## Your first conversation {#add-a-friend}

1. Ask your friend for their **contact address or contact QR**, not their pairing invitation.
2. Open **Contacts → + Add** (Desktop: **Add contact**).
3. On Android, scan their QR or paste the full address. On Desktop, paste it. Tap **Introduce myself**.
4. Wait for their device to answer the introduction. If it is offline, the contact may not appear immediately.
5. Open the contact's chat, type a message and press the send arrow. Ask them to reply.

To let someone add you, share your address from **Contacts**. Keep the whole address intact. People using different Parlons versions can message each other.

## Everyday use {#everyday-use}

- **Messages:** open **Chats**, choose the conversation and type. A queued indicator means waiting; one tick means a relay has the message; two ticks mean the recipient's device confirmed it. A read indicator depends on read receipts being enabled.
- **Photos and files:** open a chat and use **Attach**. On Android choose **Photo library** or **Private file**, select the item and send it. For Desktop's file-transfer options, see the [app manual](parlons.html).
- **Calls:** use **Voice call** or **Video call** in an Android chat or the Parlons panel inside minimaCore Desktop/minimaDesk, and allow microphone/camera access. The standalone MaximaNode chat app does not carry call audio or video.
- **Groups:** on Android open **Chats → New group**, name it, select contacts and tap **Create**.
- **Notifications:** allow notifications and background operation in Android settings. Cloud additionally needs its account host to stay online.
- **Payments:** in a wallet-enabled one-to-one chat, choose **Attach → Send payment**, select Minima or MxUSD and enter the amount. Check the recipient and amount before confirming. Payments settle on the public Minima blockchain; encrypted chat does not make them anonymous. Chat-only guest accounts do not offer payments. [Payment instructions](parlons.html#15-payments-minima-and-mxusd).

## Back up before you need it {#backups}

**The 24 words recover an identity, not your old chat history.** Write them on paper and store them privately. Do not send them to anyone or enter them on a website. Keep any separate wallet phrase too.

- **Parlons! Android:** **Settings → Keys & security → Back up identity…** creates an encrypted `.pbk`. It includes identity, contacts and settings, but not chat history.
- **Parlons Cloud:** **More → Settings → Keys & backup → Back up account…** creates an encrypted `.pbk` including contacts and chat history. Media and private-file payloads are not included.
- **Desktop:** record your seed; read [backups and moving](parlons.html#20-backups-and-moving-to-a-new-device) before changing computers.

Save the backup somewhere other than the device being backed up, and keep its password safe. It cannot be recovered for you. To use one account on several phones, pair them to a host; do not run copies of the same seed independently.

## If something does not work {#need-more}

| What you see | What to try |
|---|---|
| Cloud will not pair | Check the host is running, paste the full invitation and use a fresh one-time code. |
| An introduction is waiting | Ask the other person to open their app and check their connection. |
| Messages arrive only when you open the app | Allow notifications and background operation; for Cloud, check the host too. |
| One tick remains | A relay received it; the recipient has not confirmed receipt yet. Check their connection. |
| A lost paired phone | Revoke that device from another paired phone's Node tab. |

Full instructions: [Parlons! manual](parlons.html), [Cloud manual](cloud.html), [Node manual](node.html). If you [report a problem]({{ISSUES}}), include the app name, version, device and exact error. Never include seeds, passwords or unused pairing invitations.

## Current downloads {#downloads}

| What you are installing | Current release |
|---|---|
| Parlons! Android, independent identity | [{{ANDROID_VERSION}} · APK]({{ANDROID_APK}}) |
| Parlons Desktop · Apple silicon Mac | [{{DESKTOP_VERSION}} · DMG]({{DESKTOP_DMG}}) |
| Parlons Desktop · Windows 10/11 | [{{DESKTOP_VERSION}} · MSI]({{DESKTOP_MSI}}) |
| Parlons Desktop · Debian/Ubuntu x86-64 | [{{DESKTOP_VERSION}} · DEB]({{DESKTOP_DEB}}) |
| Parlons Cloud Android, paired client | [{{PORTAL_VERSION}} · APK]({{PORTAL_APK}}) |
| Parlons Cloud server | {{CLOUD_VERSION}} · use the host guide above |
| Parlons Node | [{{NODE_VERSION}} · JAR]({{NODE_JAR}}) · use the Node guide above |

The apps and servers have separate version numbers. A bigger number does not mean a better choice. Release notes and checksums are on [GitHub]({{RELEASES}}).
