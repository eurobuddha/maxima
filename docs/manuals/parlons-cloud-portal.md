# Parlons Cloud Portal manual

Your Parlons account, kept online on a machine that never sleeps, and driven from your phone.

This manual covers:

- **Parlons Cloud for Android** (the "Portal"), version **0.2.90** (Android package `com.eurobuddha.parlons.cloud`, Android 9 or newer).
- **Parlons Cloud**, the host program it talks to, version **0.11.124** (`parlons-cloud-0.11.124.jar`). It runs one account on your own machine, or many accounts at once as the `parlons-tenants` service (`--tenants <dir>`).

Downloads for both are on the releases page: <https://github.com/eurobuddha/maxima/releases>.

| Program | Release tag | File to download |
|---|---|---|
| Portal (Android) | `portal-v0.2.90` | `parlons-cloud-portal-0.2.90-release.apk` |
| Parlons Cloud (host) | `cloud-v0.11.124` | `parlons-cloud-0.11.124.jar` |

The SHA-256 of `parlons-cloud-0.11.124.jar` is:

```
0ae833429b641393f93b427695784b534e8a2a06a7194eba74e57aed4f19b231
```

Part A is for people who **use** an account through the Portal app. Part B is for people who **host** accounts. Every task has a numbered procedure. Labels you see on screen are in **bold**. Commands you type are in code blocks.

The companion manuals are [parlons.md](parlons.md), for the full Parlons! app, and [parlons-node.md](parlons-node.md), for the Parlons Node (a full Minima node with a Parlons account built in).

---

## Contents

**Before you start**

1. [What the Portal is for](#1-what-the-portal-is-for)
2. [Words this manual uses](#2-words-this-manual-uses)

**Part A. Using an account with the Portal app**

3. [Install the app](#3-install-the-app)
4. [Pair the app to your account](#4-pair-the-app-to-your-account)
5. [The main screen](#5-the-main-screen)
6. [Chats](#6-chats)
7. [Contacts](#7-contacts)
8. [Wallet](#8-wallet)
9. [Node](#9-node)
10. [Adding more devices: owner invitations](#10-adding-more-devices-owner-invitations)
11. [Hosting friends: guest invitations](#11-hosting-friends-guest-invitations)
12. [Node tools: Terminal IDE and control panel](#12-node-tools-terminal-ide-and-control-panel)
13. [Settings reference](#13-settings-reference)
14. [Notifications, calls and staying connected](#14-notifications-calls-and-staying-connected)
15. [Using your account from other Parlons apps](#15-using-your-account-from-other-parlons-apps)
16. [Backups, recovery and moving](#16-backups-recovery-and-moving)
17. [Troubleshooting for account holders](#17-troubleshooting-for-account-holders)

**Part B. Hosting accounts**

18. [Which kind of host do you need?](#18-which-kind-of-host-do-you-need)
19. [One account on your own machine: the one-line installers](#19-one-account-on-your-own-machine-the-one-line-installers)
20. [One account on a VPS: deploy-parlons-cloud.sh](#20-one-account-on-a-vps-deploy-parlons-cloudsh)
21. [Many accounts: the parlons-tenants service](#21-many-accounts-the-parlons-tenants-service)
22. [Creating, pausing and removing tenants](#22-creating-pausing-and-removing-tenants)
23. [Owner and guest invitations from the server](#23-owner-and-guest-invitations-from-the-server)
24. [The local web panel](#24-the-local-web-panel)
25. [Command-line flags reference](#25-command-line-flags-reference)
26. [The parlons command](#26-the-parlons-command)
27. [Files in a data directory](#27-files-in-a-data-directory)
28. [Ports, firewalls, TLS and reverse proxies](#28-ports-firewalls-tls-and-reverse-proxies)
29. [Upgrading and rolling back](#29-upgrading-and-rolling-back)
30. [Logs](#30-logs)
31. [Security notes for operators](#31-security-notes-for-operators)
32. [Troubleshooting for operators](#32-troubleshooting-for-operators)

**Reference**

33. [Glossary](#33-glossary)

---

## 1. What the Portal is for

A Parlons **account** is a small program that keeps your identity online. It holds your contacts and chat history, receives messages while your phone is off, and lets every device you pair see the same conversation. It needs a machine that stays on and has outbound internet.

The **Parlons Cloud** Android app (this manual calls it the Portal) is a window onto such an account. It holds **no chat identity of its own**. When you pair it, it creates only its own device key, and from then on it drives the account over Parlons' end-to-end encrypted transport. The app's own first screen puts it this way:

> Your identity, chats and wallet live on your always-on node — reachable from every device. This phone pairs to it as a secure client. Nobody else can pair without your one-time code, and you can revoke this phone anytime.

### Who uses it

- **People who run their own account** on a Raspberry Pi, a Mac mini, a home server or a VPS (Part B, sections 19 and 20), and want it on an Android phone.
- **People who run a Parlons Node** (the full Minima node with Parlons built in). A Parlons Node also runs a Parlons account, so the Portal pairs to it the same way. Node owners get extra tools in the app (section 12).
- **People with a hosted account.** Someone else runs a host that keeps many accounts online, one per person (Part B, section 21). The host operator gives you an **invite**, and you pair to your own account with it.

On an iPhone, the **Parlons** app does the same job; it pairs to the same kinds of account with the same invite. The full **Parlons!** app for Android is different: it keeps its identity on the phone itself and does not pair to an account (section 15).

### What an account is, and what a tenant is

| Term | What it is |
|---|---|
| **Account** | One Parlons identity (24 words), with its contacts, chats, paired devices, settings and, usually, a wallet. It runs inside `parlons-cloud`, a Parlons Node, or a desktop node app. |
| **Owner account** | The account that belongs to whoever runs the machine. On a Parlons Node this is the node's own identity. |
| **Tenant** | One account inside a multi-account host. Each tenant is a folder `<dir>/<name>/` with the same layout as a single account. Each has its own identity, devices, contacts, chats and backup. |
| **Hosted account (guest)** | The everyday name for a tenant created for a friend. Guests cannot reach the owner's account, the owner's Minima terminal, or the hosting controls. |
| **Chat-only account** | A guest created by an owner account's built-in hosting (Parlons Node, minimaCore Desktop, minimaDesk, or a standalone `parlons-cloud` with hosting enabled). It has chat, calls and files only; wallet, node terminal, NFT hosting and host management are switched off. |

### What the host can and cannot do

- It **cannot** read your messages in transit, or anyone else's: everything between devices is sealed end to end.
- It **can** read what is stored on that machine. Your account keeps its chat history and its keys there, so the operator has the same access to them that you would have on your own machine. Choose a host you trust the way you would trust someone holding your mailbox. Or run your own (Part B).

---

## 2. Words this manual uses

A full glossary is at the end (section 33). The few words needed straight away:

- **Address**: your account's permanent address. It starts with `MAX#`, is several hundred characters long, and never changes. Share it; it is how people reach you. This manual never shortens an address, and neither should you: always copy it whole.
- **Pairing code**: a one-time code in the form `ABCD-EFGH-JKLM` (three groups of four characters). It works once. Minting a new one replaces the old one.
- **Invite**: the address and a pairing code joined in one line: the address, then `?code=`, then the code. One QR of an invite pairs a phone.
- **Device**: anything paired to the account (a phone, the `parlons` command in a terminal, the host's own web panel). Each has its own key and can be revoked without changing the account's identity.

---

# Part A. Using an account with the Portal app

## 3. Install the app

### How to install the app

1. On your Android phone, open <https://github.com/eurobuddha/maxima/releases> and find the release tagged `portal-v0.2.90`.
2. Download the APK attached to it: `parlons-cloud-portal-0.2.90-release.apk`.
3. Open the downloaded file. If Android asks, allow your browser or file manager to install apps, then tap **Install**.
4. Open **Parlons Cloud**. The first screen is headed **Run Parlons in the cloud**. Go on to section 4 to pair it.

The app needs Android 9 or newer. It asks for these permissions when they are first needed: camera (scanning QR codes, video calls, photos), microphone (voice notes and calls), notifications, and running in the background.

### How to update the app

1. Download the newer `parlons-cloud-portal-<version>-release.apk` from the releases page.
2. Open it and tap **Install**. Android installs it over the old one.
3. Open **Parlons Cloud**. Your pairing is kept, so the app opens straight onto your account.

### How to uninstall the app

The app does not use Android's cloud backup, so uninstalling deletes this phone's device key. The account itself is not touched.

1. On another paired device, open **Node → Paired devices** and tap **Revoke** next to this phone (section 9).
2. Uninstall **Parlons Cloud** from Android's app settings.
3. To use the phone again later, install the app and pair it with a fresh code (section 10).

---

## 4. Pair the app to your account

You need an **invite** (or an address plus a pairing code) from one of these places:

| Where your account runs | Where the invite comes from |
|---|---|
| Your own computer, installed with the one-line installer | Printed at the end of the install, as text and as a QR (section 19). Later: `parlons invite` or `parlons newcode` on that computer. |
| Your own VPS, installed with `deploy-parlons-cloud.sh` | Printed by the deploy script (section 20). |
| A Parlons Node | `sudo parlons-pair --admin` on the server (section 23). |
| A hosted account | The host operator gives it to you, or the owner shows it from **Show invitation** (section 11). |
| An account you already use on another device | That device: **Node → Pair my other device** (section 10). |

### How to pair with an invite

1. Open Parlons Cloud. The first screen is headed **Run Parlons in the cloud**.
2. Do one of these:
   - Tap **Scan pairing QR** and point the camera at the invite QR. The app fills both fields and shows **Address and pairing code scanned**.
   - Or paste the complete invite into the field labelled **Account address or pairing invite**. The code half is split off into the **Pairing code** field when you tap **Connect & pair**.
   - Or paste just the address into the first field and type the code (for example `ABCD-EFGH-JKLM`) into **Pairing code**.
3. Tap **Connect & pair**.
4. The status line shows **Attaching to the network…**, then **Account found — pairing…**.
5. With a valid code the app opens straight onto your account. The code is now used up. No other device has to approve it.

The app remembers the address you entered, so a failed attempt does not make you type it again.

### How to pair without a code

Use this when you have the address but no code. Another device that is already paired must approve this phone.

1. Paste the full `MAX#` address into **Account address or pairing invite**.
2. Leave **Pairing code** empty.
3. Tap **Connect & pair**. The account puts this phone on its waiting list. The app shows:

   > Pairing requested. Approve it from an already-paired device, then reopen.

   followed by this device's key.
4. On a device that is already paired, approve the new phone. Use one of these:
   - In the Portal: **Node → Pending approval**, then **Approve** next to the key.
   - In a terminal with the `parlons` command:

     ```
     parlons approve <key>
     ```

   - In the web panel: **Devices**, then **Approve** under **Waiting for approval**.
5. Back on the new phone, reopen Parlons Cloud and tap **Connect & pair** again.

### If pairing fails

| Message | What it means | What to do |
|---|---|---|
| **Could not connect: …** with *Is the account online and the address correct?* | The account is stopped, has no internet, or has only just started. | Check the address is complete. Ask whoever runs the account to check it is running (section 17). Try again. |
| **Pairing failed: …** | The code is wrong, already used, or was replaced by a newer one. | Get a fresh invite and pair again. |
| **Enter or scan your account address** | The address field was empty. | Paste or scan the address. |

### How to read the server setup note

1. On the first screen, tap **New here? Set up your account server ▸**. A short note opens about running your own account server. It shows the deploy command and the location of the full guide (`cloud/SETUP.md` in the repository).
2. Tap either grey box to copy its text. The app shows **Copied**.
3. Tap **Set up your account server ▾** to fold the note away again.

Part B of this manual covers the same ground in more detail.

### How to change the app's language

1. Open the language list:
   - On the first screen, tap **Change language** at the top.
   - Once paired, open **More → Settings** and tap the top button, which reads **English · 简体中文 · Русский · Українська**.
2. Tap the language you want. The list closes and the app switches language. The choice applies to this device only.

---

## 5. The main screen

Once paired, the app opens on four tabs: **Chats**, **Contacts**, **Wallet** and **Node**. Swipe or tap to move between them.

### The top bar

- **Parlons Cloud** and the app version.
- **The status pill** shows whether the app can reach your account:
  - green, **online · N hosts** (or **N new** when there are unread messages): the account is reachable;
  - **Connecting…**: the app is still trying;
  - red, **can't reach**: the account did not answer.
- **Search** searches your account's messages, contacts and groups. The search runs on the account, so it covers everything stored there, not just what this phone has seen.
- **Theme** cycles Light → Dark → System. A short message confirms the new choice.
- **More** opens the account menu:

| Item | What it does |
|---|---|
| **Settings** | Opens the Settings screen (section 13). |
| **Set account name** | Jumps to the Node tab and asks for a new account name. |
| **Copy account address** | Copies the full `MAX#` address. |
| **Node status** | Jumps to the Node tab. |
| **Unpair this device** | Asks **Unpair this device?** and then forgets the account on this phone. |

### How to search the account

1. Tap **Search** in the top bar.
2. Type into **Search messages…**. Results appear under **Contacts & groups** and **Messages**. *Nothing matches.* means there is no hit.
3. Tap a result to open that chat. Tap **Back** to leave the search.

### How to change the theme

1. Tap **Theme** in the top bar. Each tap moves to the next choice: Light, Dark, System.
2. A short message such as *Theme: Dark* confirms the choice.

### How to copy the account address

1. Tap **More → Copy account address**.
2. The app shows **Account address copied**. The clipboard holds the full `MAX#` address.

### How to unpair this phone

1. Tap **More → Unpair this device**.
2. The dialog **Unpair this device?** explains that this phone will stop driving your cloud account.
3. Tap **Unpair**. The app returns to the first screen.
4. On another paired device, revoke this phone's entry (section 9). Until you do, the account still lists it as a paired device.

**Unpair this device** only clears the pairing on this phone. Your account and its identity stay on the node. Other devices keep working, and you can pair this phone again with a fresh code.

---

## 6. Chats

The **Chats** tab lists your conversations, newest first, with unread counts.

With no chats yet, the tab says: *No conversations yet. Add a contact in the Contacts tab, then message them.*

### How to open or find a chat

1. Open the **Chats** tab.
2. To narrow the list, type part of a name into the search field at the top.
3. Tap the chat to open it.

### How to start a group

You need at least one contact first. Otherwise the app says *Add some contacts first — a group needs members*.

1. On the **Chats** tab, tap the **New group** button.
2. Type a name into **Group name**.
3. Tick at least one member.
4. Tap **Create**. The app shows **Group created** and the group appears in the list.

If you tap **Create** with no name or no member, the app says *Name the group and pick at least one member*.

### How to mark a chat read or clear it

1. On the **Chats** tab, long-press the chat.
2. Tap **Mark as read** (shown when it has unread messages). The app shows **Marked read**.
3. Or tap **Clear messages**. The dialog **Clear this chat?** appears. Tap **Clear** to confirm. The app shows **Chat cleared**.

Clearing removes the messages from your account only. It does **not** unsend them or leave a group, and it cannot be undone.

### Inside a chat

The subtitle reads **on your cloud account**: the messages live on the account, and this screen is a view onto them.

#### How to send a message

1. Type into the message field at the bottom.
2. Tap the send button (it shows an arrow once there is text).

#### How to send a voice note

1. Leave the message field empty. The send button shows a microphone.
2. Tap the microphone. The **Voice note** dialog opens with a timer and the maximum length.
3. Speak.
4. Tap **Send** to send it, or **Cancel** to throw it away. **Too short - hold on a moment longer** means the recording was too brief.

You can also start a voice note from **Attach → Voice note**.

#### How to send a photo or a file

1. Tap **Attach**.
2. Choose one:
   - **Photo library**: pick a photo. The **Send photo** dialog lets you **Add a caption…**. Tap **Send**.
   - **Private file**: Android's file picker opens. Pick the file to send it into this chat.
   - **File transfers**: open the list of file transfers in this chat.
3. To take a new photo instead, tap the camera button beside the message field.

#### How to call a contact

1. Open a one-to-one chat.
2. Tap **Voice call** or **Video call** in the chat's top bar.
3. Allow the microphone (and the camera, for video) the first time the app asks.
4. In the call, use **Mute**, **Speaker**, **Flip** (switch camera) and **End**.

Calls are one-to-one only. Section 14 covers answering a call.

#### How to see contact or group details

1. Tap the info button in the chat's top bar.
2. For a contact, the dialog shows their software, when they were last seen, their public key, addresses and payment address. **Copy key** copies the key in full.
3. For a group, the **Group** dialog lists the members. If you are the group's admin, tap **Edit members**, tick or untick contacts and tap **Save**. The app shows **Group updated**.

#### How to act on one message

1. Long-press the message.
2. Choose one:
   - **Copy message** (or **Copy caption** on a photo, **Copy contact address** on a shared address).
   - **Save photo** or **Share photo** on an image. **Saved to Photos** confirms a save.
   - **Copy transaction id** on a payment.
   - **Info** opens **Message info**: when it was sent and arrived, its status (Sending…, Sent (relay took it), Delivered, Read, Failed to send) and, for payments, the transaction id.

The ticks mean the same as in every Parlons app:

| Mark | Meaning |
|---|---|
| ⋯ | queued, not handed to a host yet |
| ✓ | sent: a host accepted it |
| ✓✓ | delivered: the recipient's own device confirmed it |
| ✓✓ in blue | read |
| ✗ | failed: no host would take it; it stays in the outbox |

### How to resend a message that was not delivered

If your account could not reach the contact at any address it knows, a failed message shows ✗.

1. Tap the failed message. The dialog **Message not delivered** explains that your node could not reach the contact.
2. Tap **Reconnect** (or **Not now** to leave it). The app shows **Reconnecting…** and asks the directory for their current address.
3. **Fresh address found — resending shortly** means the failed messages are re-sent automatically. **Address unchanged — they may just be offline** means the contact is probably offline; the messages wait in the outbox.

### Paying a contact in a chat

1. In a one-to-one chat, tap **Attach → Send payment**. The dialog **Send payment to *name*** says *Paid from your cloud account's wallet.*
2. Choose **Minima** or **MxUSD**. MxUSD is the Minima dollar-pegged token, token id `0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90`.
3. Enter the amount (the field reads **Amount (MINIMA)** or **Amount (MxUSD)**) and, if you like, a **Memo (optional)**.
4. Tap **Send**. The app shows **Payment building on your node…**.

The payment is built and signed on the machine that runs your account, not on the phone. Chat-only accounts cannot pay (section 8).

If a payment shows **⚠ unconfirmed — check the balance before paying again**, check the Wallet tab before sending it a second time.

**MxUSD needs an up-to-date host.** Before an MxUSD payment, Portal 0.2.90 asks the account's host whether it honours the token id. A host that cannot prove it would ignore the token and pay the same amount in MINIMA, so the app refuses the send and shows:

> Payment failed: this account's host is too old to send MxUSD - update it (Parlons Node 0.2.135+, Parlons Cloud 0.11.121+, minimaCore Desktop 0.18.21+)

Nothing is sent. Ask whoever runs the host to update it to one of the versions named, then try again. Minima payments are not affected.

---

## 7. Contacts

The **Contacts** tab has three parts.

**Your account address.** The full `MAX#` address, with **Copy**, **Show QR** and **Share address**. *Share this so people can reach you — it stays the same on every device.* While the account is still attaching, it shows **(resolving…)**.

**Your contacts.** Headed **Contacts · N**, with **Search contacts** above the list. Tap a contact to see their details: **Software**, **Last seen** (or **Online now**), **Public key**, their **Addresses**, and their **Payment address (MINIMA)** when they have published one. From there:

- **Message** opens the chat.
- **More** offers **Rename**, **Reconnect now** and **Remove contact** (procedures below).

**Add a contact.** A field and **Scan QR** under **Add a contact**.

### How to share your address

1. Open **Contacts**.
2. Under **Your account address**, tap one of these:
   - **Copy**: the app shows **Address copied**. Paste it into a message to the person.
   - **Show QR**: let the person scan the QR from your screen.
   - **Share address**: Android's share sheet opens. Pick an app to send it with.

### How to add a contact

1. Open **Contacts** and scroll to **Add a contact**.
2. Get their address into the field. Do one of these:
   - Paste a full address. It starts with `MAX#`, or starts with `Mx` and ends in `@host:port`.
   - Tap **Scan QR** and point the camera at their address QR.
3. Tap **Add**. The app shows **Adding…**, then **Contact added**.

Adding someone is a handshake: your account introduces itself to their address and they reply with theirs. If the add fails, the app shows **Could not add: …** with the reason, for example *that account isn't reachable right now — check they're online, or re-scan their address*. If the text is not an address at all, it says *That doesn't look like a Parlons address — it should start with Mx… or MAX#…*.

### How to rename a contact

1. Open **Contacts** and tap the contact.
2. Tap **More → Rename**.
3. In **Rename contact**, type the new name into **Name for this contact**.
4. Tap **Rename**. The app shows **Renamed**. The name is local to your account.

### How to reconnect to a contact

1. Open **Contacts** and tap the contact.
2. Tap **More → Reconnect now**. The app shows **reconnecting…**.
3. **Fresh address found — reconnected** means it found their new address. **Address unchanged — they may just be offline** means nothing had changed.

### How to remove a contact

1. Open **Contacts** and tap the contact.
2. Tap **More → Remove contact**.
3. The dialog asks **Remove *name*?** and explains: *They keep your address and can still write to you. This only removes them from your list.*
4. Tap **Remove**. The app shows **Removed**.

---

## 8. Wallet

What the **Wallet** tab shows depends on the kind of account you are paired to.

### A chat-only account

The tab says **Chat-only account**: *This hosted account includes chat, calls and files. Wallet and host controls are disabled.* There is nothing else to do here.

### A Parlons Cloud account (own server, or a tenant on a `parlons-tenants` host)

The account has its own wallet. Its keys are derived from the account's seed and live on the machine that runs the account. Signing happens there.

What the tab shows:

- **Total balance**: *MINIMA · your account's wallet*, with *N sendable · keys live on your cloud node*.
- **Receive address**: the account's Minima address, with **Copy** and **Show QR**.
- **Tokens** held at that address, each with its token id.
- **Signing key**: *N / M signatures used*. Each key signs a fixed number of times.
- **Send**, **Detach wallet to this phone…** and **History**.

#### How to receive

1. Open **Wallet**.
2. Under **Receive address**, tap **Copy** (the app shows **Address copied**) or **Show QR**.
3. Give the full address to the sender, or let them scan the QR.

#### How to copy a token id

1. Open **Wallet** and find the token under **Tokens**.
2. Tap it. The app shows **Token id copied**. The clipboard holds the full token id.

#### How to send Minima or MxUSD

1. Open **Wallet** and tap **Send**.
2. Enter the recipient into **Mx… or 0x… address**, or tap **Scan QR** to scan it.
3. Choose **Minima** or **MxUSD**.
4. Enter the amount (**Amount (MINIMA)** or **Amount (MxUSD)**) and tap **Continue**.
5. Read the confirmation **Send *amount* *token*?**. It shows the full address and says *This signs and broadcasts a real transaction and cannot be undone.*
6. Tap **Sign & send**. The status reads **Signing on your node…**, then **Broadcasting via …**, then **Sent**.

If it fails, the app shows **Send failed: …** with the reason. For MxUSD on a host that cannot prove it honours the token id, the reason is *this account's host is too old to send MxUSD - update it (Parlons Node 0.2.135+, Parlons Cloud 0.11.121+, minimaCore Desktop 0.18.21+)*. Nothing is sent; ask the host's operator to update it (section 6).

#### How to look at a past payment

1. Open **Wallet** and scroll to **History**. It lists payments this phone has seen while paired (sends and received payments), newest first.
2. Tap a payment. The dialog shows its amount, the other party, when it happened, the memo and the transaction id.
3. Tap **Copy tx id** to copy the full transaction id.

#### How to set the signatures-used counter

Only do this if this seed has signed transactions on **another** device. The counter can only go up. Setting it too low risks reusing a one-time key and leaking it.

1. Open **Wallet** and, under **Signing key**, tap **Set signatures used…**.
2. Enter the new count into **New used count (> *current*)** and tap **Continue**.
3. Read **Raise to *N*?**. It warns that this cannot be undone.
4. Tap **Raise counter**. The app shows **Updating counter…**, then **Counter raised**.

#### How to detach the wallet to this phone

**Detach wallet to this phone…** moves **all** sendable funds to a wallet whose seed lives on this phone, in the Minima Core app. Your cloud identity stays. The server keeps a watch-only view of the new address.

1. Install Minima Core first: **More → Settings → Minima Core on this phone → Get Minima Core**. Without it the app shows **Minima Core needed**.
2. In Minima Core, open Wallet → Receive and copy your address.
3. In Parlons Cloud, open **Wallet** and tap **Detach wallet to this phone…**.
4. Paste the address into **Mx… address from Minima Core** and tap **Continue**.
5. Read **Sweep *amount* MINIMA?**. It shows the full address and says the cloud then watches it read-only.
6. Tap **Sweep & detach**. All sendable funds go to that address in one transaction.

If the app says *Nothing sendable right now — sync first or wait for confirmations*, wait and try again.

### A Parlons Node account

The node's own Minima wallet is the account's wallet.

- **Balance · whole wallet**: *MINIMA · sendable, across every address of your node's wallet*, with confirmed, locked and unconfirmed amounts, and the other tokens the node holds.
- **Open wallet** opens the full wallet: balances with token icons, NFT gallery, send (quick or coin control), receive with QR, every coin, on-chain history and minting. Everything runs on your node and is signed there.
- **Resync wallet to a new phrase…** moves the node's **wallet** to a new 24-word phrase.

#### How to open the full node wallet

1. Open **Wallet**.
2. Tap **Open wallet**. The full wallet opens, with its own tabs for balances, sending, receiving, coins, history and minting.

#### How to resync the node wallet to a new phrase

Use this when the node's signing keys are used up, or you want a fresh seed. Your identity, paired devices and contacts stay. Funds at the old phrase stay with the old phrase, so move them first.

1. Open **Wallet** and tap **Resync wallet to a new phrase…**.
2. Type the new phrase into **24 words, space separated**. Anything other than 24 words gives *That is not 24 words*.
3. Tap **Resync**. The app shows *Resync started - the node restarts in about a minute; same account, new wallet address*.
4. Wait about a minute. The app reconnects on its own.

If the tab later says *Last resync did NOT go through - the wallet is unchanged*, the reason follows. The old wallet is still in use.

### A watch-only address

If the account has no wallet address of its own, the tab offers **Watch-only wallet**. *Your spend keys never leave your device; the node can watch, never spend.*

1. Open **Wallet**.
2. Enter an `Mx…` address into **Mx… receive address**.
3. Tap **Set watch address**. The app shows **Setting…**, then **Watch address set**.
4. The tab now shows that address's balance, read-only.

---

## 9. Node

The **Node** tab is the account's control room. Pull down or revisit the tab to refresh it.

From top to bottom:

1. **Your name.** How you appear in your contacts' lists, on every device. Tap **Edit**, type the new name (for example *eurobuddha*) and save. *Shown to your contacts, and re-announced to all of them now.*
2. **Status.** **Always on — online** (or **Node unreachable**), with **Uptime**, **Relays attached** and **Mailbox held**. On an older host the card says *Update the node to parlons-cloud 0.4.1+ for full status.*
3. **Why a VPS node is king.** A reminder of what the account does for you: **Always on**, **One account, every device**, and whether it is a relay (**You are a relay**, with the number of mesh peers, or **Relay off**).
4. **Permanent address.** *The address that never changes — reaches you on any device.* **Copy** and **Show QR**.
5. **Hosted accounts** (only when this account manages a host): **Manage hosted accounts** (section 11).
6. **Node tools** and **Transport & hosts** (only when the host offers its node console, which a Parlons Node does): **Open Terminal IDE** and **Open control panel** (section 12).
7. **Paired devices (N).** Every device that can drive the account. This phone is marked **this device**; others show **paired**. Tap **Revoke** to cut one off. *It will lose access to your account. Your identity is NOT changed — other devices keep working. This can't be undone (they'd need a new code to re-pair).* The host's own web panel appears here too, as a local device; revoking it locks the panel out.
8. **Pending approval (N).** Devices that asked to pair without a code (**A device wants to pair**). Tap **Approve** only if you recognise the key.
9. **Pair my other device** (section 10), with a reminder of what device pairing means.

If the account does not answer, the tab shows **Can’t reach your node.** and the reason.

### How to set your account name

1. Open **Node**, or tap **More → Set account name**.
2. Under **Your name**, tap **Edit**.
3. Type the new name (for example *eurobuddha*). The dialog says *Shown to your contacts, and re-announced to all of them now.*
4. Tap **Set**. The app shows **Name set — announcing to your contacts…**.

### How to copy or show your permanent address

1. Open **Node** and find **Permanent address**.
2. Tap **Copy** to copy the full `MAX#` address, or **Show QR** to show it as a QR code.

### How to revoke a paired device

1. Open **Node** and scroll to **Paired devices (N)**.
2. Find the device. This phone is marked **this device**.
3. Tap **Revoke**.
4. Read **Revoke this device?** and tap **Revoke**. The app shows **Revoked**.

The device loses access at once. Your identity does not change, and other devices keep working. To come back, the device needs a new code. Revoking the host's own web panel locks the panel out (section 24).

### How to approve a device that asked to pair

1. Open **Node** and scroll to **Pending approval (N)**. Each entry reads **A device wants to pair**, with its key.
2. Compare the key with the one the new device shows (section 4, "How to pair without a code").
3. Tap **Approve** only if the keys match. The app shows **Approved**.

---

## 10. Adding more devices: owner invitations

Pairing a device gives it **full control of this account**: its chats, contacts and, where there is one, its wallet. *Share a device invite only with someone who should control this account.* To invite a friend, create a separate hosted account instead (section 11).

### From the Portal

1. Open **Node** and tap **Pair my other device**.
2. What happens next depends on the host:
   - **Hosts without an admin password service** (a tenant on a `parlons-tenants` host, or a guest account): a new code is minted at once.
   - **Hosts with an admin password service** (a standalone `parlons-cloud` account, a Parlons Node 0.2.118 or newer, and the desktop node apps): the app asks **Invite a device**. *Enter the admin password to give another device access to this owner account and Minima terminal. Leave it empty to create a separate hosted account for a friend.*
     - Enter the admin password and tap **Continue** to mint an owner invitation.
     - Leave it empty and tap **Continue** to open hosted-account management instead (or, if hosting is not set up, **Hosting is not enabled on this server yet**).
3. The app shows **New pairing code minted** with the one-time code and the full invite. Tap **Copy invite**, or **QR** to show it as a QR code for the new device to scan.

The password is checked on the server and is never saved on the phone. A wrong password never issues a code. After a wrong attempt, wait a couple of seconds before trying again (*Wait a few seconds before trying the admin password again*). If no admin password has been set yet, the server answers *Set the admin password in the host app's Parlons Devices page. For a command-line server, use: sudo parlons-pair --set-admin-password* (section 23 explains how to set it on each kind of host).

### Without an admin password: approve instead

You can always add a device without any code. Approval does not need the admin password.

1. On the new device, pair with the **Pairing code** field empty (section 4, "How to pair without a code").
2. On a device that is already paired, open **Node → Pending approval (N)**.
3. Tap **Approve** next to the new device's key.
4. On the new device, tap **Connect & pair** again.

### From the server

See section 23: `sudo parlons-pair --admin` on a Parlons Node, `parlons newcode` on a one-line-installer account, or the web panel's **Devices → New pairing code** on any host with a panel.

### How to cut off a lost or replaced phone

Revoking changes nothing about your identity.

1. On any other paired device, open **Node → Paired devices (N)**.
2. Find the lost phone and tap **Revoke**, then **Revoke** again to confirm.

Or, from a terminal with the `parlons` command:

1. List the devices with their full keys:

   ```
   parlons devices
   ```

2. Revoke the lost phone by its full key:

   ```
   parlons revoke <key>
   ```

---

## 11. Hosting friends: guest invitations

If your account manages a host, the Node tab shows a **Hosted accounts** card: *Host friends on this server. Each person gets their own account and invitation, without access to your account or Minima terminal.* This is how an owner creates accounts for people who will not run a server.

Two kinds of host appear here:

| Host | What guests get | Who sets it up |
|---|---|---|
| **Built-in chat hosting** in a Parlons Node, minimaCore Desktop, minimaDesk, or a standalone `parlons-cloud` account | Chat-only accounts: chat, calls and files. No wallet, terminal or host controls. Up to ten accounts. | The owner enables it from the app or the host's panel. |
| **A `parlons-tenants` service connected to a Parlons Node** (`--manage-from-node`, section 21) | Full tenant accounts with their own chats and wallets. They cannot control the owner account or node. | The server operator installs it. |

### How to host a friend

1. Open **Node → Manage hosted accounts**. A list appears, titled **Hosted accounts (N/limit)**, or **Hosting service offline**.
2. **Built-in hosting only:** the first time, tap **Hosting options → Enable hosting**. The dialog **Enable hosting?** explains: *Your computer will store guest account keys and messages and must stay running. Set the admin password in the host app’s Parlons Devices page first.* Tap **Enable**. Hosting cannot be enabled until the admin password is set (section 23). Tap **Refresh** after a few seconds (*Hosting is starting. Refresh in a few seconds.*).
3. Tap **Create account**. Under **Host a friend**, type an account name (for example *alice*) and tap **Create**. Names are 1 to 64 letters, numbers, dots, underscores or hyphens, starting with a letter or number.
4. Tap **Refresh** until the account shows **running**. Its state moves through **starting** to **running** (or **retrying** if it fails to start).
5. Tap the account, then **Show invitation**. The dialog **Invitation for *name*** shows the full invite. *This invitation pairs a device to name's account. Keep it private until used.* Tap **Copy invite** and send it privately, or let them scan it.
6. Your friend installs Parlons Cloud (Android) or Parlons (iPhone), scans or pastes the invite, and taps **Connect & pair**. A valid code pairs immediately. Their invite gives no access to your account.

### How to pause or resume a hosted account

1. Open **Node → Manage hosted accounts**.
2. Tap the account.
3. Tap **Pause account**. The dialog **Pause *name*?** says *Their account will go offline. Its identity and chats will be kept.* Tap **Pause**.
4. To bring it back, tap the account, then **Resume account**. The dialog **Resume *name*?** says *Their account will reconnect on this server.* Tap **Resume**.
5. Tap **Refresh** to see the new state.

### How to stop built-in hosting

1. Open **Node → Manage hosted accounts**.
2. Tap **Hosting options → Stop hosting**.
3. The dialog **Stop hosting?** says *All guest accounts will go offline. Their identities and chats will be kept.* Tap **Stop**.

To start it again, follow step 2 of "How to host a friend".

The limit counts paused accounts too. When it is reached, creating another fails with *Hosting limit reached (10 accounts)* (or whatever limit the host uses).

A guest can manage their own account and pair their own other devices. They can also back up their whole account and leave for another host at any time (section 16).

---

## 12. Node tools: Terminal IDE and control panel

These two cards appear only when the account runs on a host that offers its node console, which is a **Parlons Node**. A plain `parlons-cloud` account does not have them; manage its relays and directory from its web panel or the `parlons` command instead (sections 24 and 26).

### Open Terminal IDE

*The full Minima command line of YOUR node, plus the KISS script IDE and the manual-transaction workbench - every command runs on the node over this paired channel, output complete and copyable.* The **Terminal IDE** screen has four tabs: **Terminal**, **Scripts**, **Transactions** and **Logs**. Each command is recorded in the node log as run by a paired device. The `quit` command is refused; restart the node from the server.

#### How to run a Minima command on your node

1. Open **Node** and, under **Node tools**, tap **Open Terminal IDE**.
2. On the **Terminal** tab, type a command into **command…**, for example:

   ```
   balance
   ```

3. Tap **Run**. The full output appears above.
4. Use **Previous command** and **Next command** to step through what you ran. Hold the favourites button to save a command.
5. To keep the output, open the menu and choose **Copy all output**, **Select & copy output** or **Export session…**. **Clear terminal** empties the screen.

#### How to read the node log

1. In **Terminal IDE**, open the **Logs** tab.
2. Scroll the log. It is read from your node over the paired channel.

### Open control panel

*Hosts, reachability, relay stats, MLS/location and the live event log — everything a VPS operator runs.* The **Node control panel** screen has:

- **Status**: Version, Uptime, Contacts, Mailbox held, Outbox, the public relay and its **Relay check** (*verified — relays to this node*, *attached, verifying…* or *not attached*), **Directly reachable**, and the **Direct address** when there is one.
- **Relay (you carry traffic for the network)**: Clients attached, Messages relayed, Blobs stored, Mesh peers.
- **Hosts**: the relays the account is attached to. **Detach** removes one. Add one by typing `host:port` (for example `relay.example.com:9501`) or pasting a relay QR text, then **Add & connect**. The switch **Account uses the built-in relay list** turns the shipped list on or off; it cannot be switched off while you have no relay of your own.
- **Location (MLS)**: where your permanent address resolves. **Pin permanent** (or **Re-pin**), **Clear pin**, **Republish** and **Copy permanent address**. *Pinned — contacts always resolve you at a fixed permanent address.*
- **Event log**, with **Clear** and **Refresh**.

#### How to add a relay to the account

1. Open **Node → Open control panel**.
2. Under **Hosts**, type `host:port` (for example `relay.example.com:9501`) or paste a relay QR text.
3. Tap **Add & connect**. The app shows **Connecting…** and the relay appears in the list.

#### How to detach a relay

1. Open **Node → Open control panel**.
2. Under **Hosts**, tap **Detach** next to the relay. The app shows **Detaching…**.

#### How to turn the built-in relay list on or off

1. Open **Node → Open control panel**.
2. Under **Hosts**, flip **Account uses the built-in relay list**. You must add a relay of your own before you can switch it off.

#### How to pin your permanent address

1. Open **Node → Open control panel** and find **Location (MLS)**.
2. Tap **Pin permanent** (or **Re-pin** if one is set). The app shows **Permanent address pinned**.
3. **Copy permanent address** copies the full address.
4. To undo, tap **Clear pin**. To push your directory record out again now, tap **Republish**.

#### How to read and clear the event log

1. Open **Node → Open control panel** and scroll to **Event log**.
2. Tap **Refresh** for the newest lines, or **Clear** to empty it.

---

## 13. Settings reference

Open **More → Settings**.

| Section | Item | What it does |
|---|---|---|
| (top) | **Change language** | Chooses the app's language for this device. |
| **Your account** | **Send read receipts** | *Let people see when you've read their messages. Set on your node — applies from every device.* |
| **Privacy & security** | **App lock** | *Require your fingerprint or device PIN to open Parlons Cloud.* Needs a fingerprint or screen lock set up in Android first. |
| | **Allow screen sharing** | *On by default. Turn off to hide the app from screenshots and screen recording while it's locked.* |
| **Notifications** | **Message sound** | *Play the Parlons chime for new messages. Off keeps the notification but stays silent (it still vibrates).* |
| | **Incoming call settings** | Opens Android's settings for ringtone, vibration and full-screen alerts on the lock screen. |
| **Keys & backup** | **Show seed phrase** | Shows the account's 24 words (section 16). |
| | **Back up account…** | Writes an encrypted backup of the whole account to a file you choose (section 16). |
| | **Import identity or backup…** | Replaces the account on the server with another identity (section 16; Parlons Node 0.2.117 or newer only). |
| **Minima Core on this phone** | **Open Minima Core** / **Get Minima Core** | Minima Core runs a full Minima node on the phone. It is the home for a wallet independent of your cloud identity, and is needed for **Detach wallet to this phone…**. |
| **This device** | **Relays this phone starts from** | Where this phone first looks for your account (see below). |
| | **Notification settings** | Opens Android's notification settings for the app. |
| | **Keep running in the background** | Asks Android to exempt the app from battery optimisation. Shown only while it is not already exempt. |
| **About** | **Portal version** | The installed version. |
| | Account address | *Your account address (share it — it never changes)*, in full. |

### How to turn read receipts on or off

1. Open **More → Settings**.
2. Under **Your account**, flip **Send read receipts**. The setting is stored on your account, so it applies from every device.

### How to lock the app

1. Set up a fingerprint or a screen lock in Android's settings first. Otherwise the app says *Set up a fingerprint or screen lock in your phone's settings first.*
2. Open **More → Settings**.
3. Under **Privacy & security**, turn on **App lock**.
4. From now on, opening the app shows **Unlock Parlons Cloud**. Use your fingerprint or your device PIN.

To hide the app from screenshots and screen recording while it is locked, turn off **Allow screen sharing** in the same section.

### How to silence the message chime

1. Open **More → Settings**.
2. Under **Notifications**, turn off **Message sound**. New messages still notify and vibrate, without the chime.

### How to change ringtone and lock-screen call alerts

1. Open **More → Settings**.
2. Under **Notifications**, tap **Incoming call settings**. Android's own settings open.
3. Choose the ringtone and vibration, and allow full-screen alerts on the lock screen.

### How to keep the app connected in the background

1. Open **More → Settings** and scroll to **This device**.
2. Tap **Keep running in the background**. Android asks whether to let the app ignore battery optimisation.
3. Allow it. The item disappears once the app is exempt.
4. To check notification channels, tap **Notification settings** in the same section.

### How to open or install Minima Core

1. Open **More → Settings** and find **Minima Core on this phone**.
2. If Minima Core is installed, the card says **Minima Core is installed ✓**. Tap **Open Minima Core**.
3. If not, tap **Get Minima Core** to install it.

### How to set the relays this phone starts from

These are the relays the **phone** uses to find the account (the account's own relays are set on the account, section 12 or 24).

1. Tap **Relays this phone starts from**. The list labels each relay **built-in** or **yours**.
2. To add one, type `host:port`, or paste a relay QR text, and tap **Add**. Or tap **Scan a relay QR**. A relay QR text starts with `parlons-relay:`.
3. **Drop** removes a relay of your own. You must keep at least one; dropping your last one turns the built-in list back on.
4. **Use built-in relays (the list shipped with the app)** switches the shipped list on or off. You must add a relay of your own before switching it off.
5. **Reset** returns to the built-in list. **Done** closes the dialog.

Changes apply on the next reconnect.

---

## 14. Notifications, calls and staying connected

The app keeps a persistent encrypted connection to your account, as an Android foreground service, so messages notify and calls ring. Its ongoing notification reads **Connected to your cloud account**. After a phone restart, the app reconnects on its own if a device is paired.

Android notification channels:

| Channel | Used for |
|---|---|
| **Messages** / **Messages (muted)** | New messages on your cloud account (the muted one when **Message sound** is off). |
| **Incoming calls** | Ringing calls, including full-screen alerts on the lock screen. |
| **Cloud connection** | The ongoing connection notification. |
| **Mint updates** | Progress of NFT minting run from the wallet. |

**Calls.** Voice and video calls are one-to-one. Audio and video are encrypted between the two devices. The app asks for the microphone (and camera, for video) the first time.

### How to answer a call

1. An incoming call shows **Incoming Parlons call** (or **Incoming Parlons video call**), full screen on the lock screen if Android allows it.
2. Tap **Answer** to take it, or **Decline** to refuse it.
3. During the call, use **Mute** / **Unmute**, **Speaker** / **Earpiece**, **Flip** (switch camera, video only) and **End**.

To make a call, see section 6, "How to call a contact".

### If messages arrive late

1. Open **More → Settings → This device** and tap **Keep running in the background** if it is shown (section 13).
2. Make sure Android allows the app's notifications (**Notification settings** in the same section).
3. Check the status pill. It should read **online · N hosts**.
4. If the pill says **can't reach**, the account itself may be stopped (section 17).

---

## 15. Using your account from other Parlons apps

One account can be driven from several places at once. Each one is a separate paired device with its own key.

- **iPhone: the Parlons app (not available yet).** When it is released: scan or paste the same kind of invite; it pairs to the account exactly as the Portal does. Each further device needs its own fresh code.
- **A browser on the machine that runs the account: the web panel.** It looks like the Parlons! app (title bar **Parlons!**, tabs Chats, Contacts, Devices, Node, Settings) and works as a local device of the account (section 24).
- **A terminal: the `parlons` command.** Installed by the one-line installer. `parlons chats`, `parlons send <contact key> hello` and the other commands in section 26 drive the account like a phone would.
- **minimaCore Desktop and minimaDesk** embed the same panel on their Parlons page for the account their node runs.

**The full Parlons! app for Android is not a client.** It keeps its own identity on the phone and does not pair to an account. You can, however, move an identity between the two: a backup bundle made from your cloud account (**Back up account…**) restores in the Parlons! app (identity and contacts). Never run the same identity in two places at once (section 16).

### How to pair an iPhone to the same account

The iPhone app is not available yet. These are the steps once it is released.

1. On a device that is already paired, open **Node → Pair my other device** and mint a fresh invite (section 10).
2. On the iPhone, open the **Parlons** app.
3. Tap **Scan account QR** and scan the invite, or paste it.
4. Finish pairing in the iPhone app. A valid code pairs at once.

**Your account and other people's apps.** Anyone using any Parlons app, or a classic Minima node with Maxima, can add your `MAX#` address as a contact and write to you.

---

## 16. Backups, recovery and moving

### Your 24 words

Every account is 24 words. The account's address is derived from them, so the same words anywhere give the same address. They are also a Minima wallet: **anyone who reads them can spend whatever is held against them.**

#### How to see your 24 words

1. Make sure nobody can see your screen.
2. Open **More → Settings** and, under **Keys & backup**, tap **Show seed phrase**.
3. The dialog **Show seed phrase?** warns: *This phrase controls your Parlons identity. It may differ from the server’s wallet phrase. Make sure nobody is looking. It travels to this phone over the encrypted channel.* Tap **Show**.
4. **Your 24 words** appear. Write them on paper.
5. Tap **Close**. If you tap **Copy** instead, the app says **Copied — clipboard clears in 60s**.

The identity phrase and the wallet phrase can differ, for example after a **Resync wallet to a new phrase…** on a Parlons Node or after importing an identity. Keep both safe.

Write the words on paper and keep the paper offline. Never photograph them, email them or paste them into a chat.

### How to back up the whole account

1. Open **More → Settings** and, under **Keys & backup**, tap **Back up account…**.
2. In **Back up account**, type a passphrase into **Passphrase (min 6 characters)** and again into **Repeat passphrase**. *It is NOT recoverable if you lose it.*
3. Tap **Create backup**.
4. The app shows **Building encrypted backup…**, then asks where to save the `.pbk` file.
5. Save it somewhere safe. **Backup saved** confirms it. (If you wait too long before choosing a place, the app says **Backup expired — export again**.)

The bundle is *encrypted with your passphrase before it leaves the node*. It holds the identity, paired devices, settings, contacts and chat history, in the same `.pbk` format the phone app reads. Media and private-file payloads are not included. From a terminal the same file is made with `parlons backup <file.pbk>`.

### How to restore an account on your own machine

1. Make a backup bundle (above), or use one you already have.
2. On the machine that will run the account, restore the bundle into an empty data folder with `--restore` **before** starting the account. Section 19, "Move an account to another machine", has the exact commands.
3. Stop the old copy for good.

The address stays the same, so every paired device reconnects on its own.

### How to import an identity onto a Parlons Node from the app

This needs Parlons Node 0.2.117 or newer. On other hosts the app says *Update Parlons Node to 0.2.117 or later to import from this app*.

1. Stop the identity on its original device or server first.
2. Open **More → Settings** and, under **Keys & backup**, tap **Import identity or backup…**.
3. Choose what to import:
   - type the **Recovery phrase**; or
   - choose **Custom phrase (Minima anyphrase)** for a nonstandard phrase (every space and capital is kept); or
   - tap **Choose .pbk backup instead…**, pick the file and enter its **Backup password**.
4. Tap **Review import**. Check the new address.
5. Tick **I have stopped this identity on its original device or server**.
6. Tap **Import & restart**. The server restarts (*Server restarting. Reconnecting to the imported identity…*) and the app reconnects (**Identity imported and connected**).

This replaces the server's Parlons identity, contacts and chats. Its Minima wallet and wallet phrase stay unchanged. The previous account is kept on the server for recovery. If the connection drops, reopen the screen and tap **Resume / check import**. **Cancel prepared import** abandons it before confirmation. Your phrase is never stored on the phone.

### One identity, one place

Run each identity in **one** place at a time. Two copies confuse your contacts and can reuse wallet keys that must never be reused. Move, do not copy: stop the old copy for good before starting the new one.

### How to leave a host

You can leave at any time. Your address stays the same, so your contacts and paired devices carry on.

1. Back up the whole account (above) and keep the passphrase.
2. Ask the host to pause the old copy (section 22, "Pause and resume"), so the identity never runs in two places.
3. Restore the bundle on your own machine and start it (section 19, "Move an account to another machine").
4. Check that your phone reconnects to the new copy.
5. Ask the host to remove the old copy for good.

---

## 17. Troubleshooting for account holders

| Symptom | What to do |
|---|---|
| **Could not connect: …** when pairing, or the pill says **can't reach** | The account is not attached to a relay: it is stopped, has no internet, or just started. On its machine run `parlons status`; on a Parlons Node, `journalctl -u parlons-node -f`; on a hosted account, ask the operator. |
| **Pairing failed** | The code was wrong, already used, or replaced by a newer one. Ask for a fresh invite. |
| **Pairing requested. Approve it from an already-paired device** | You paired without a code. Approve this device from another paired device, then reopen the app. |
| **Admin password required for an owner-account invitation** | The host protects owner invitations. Enter the admin password, or approve a code-less pairing instead (section 10). |
| **Set the admin password in the host app's Parlons Devices page…** | Nobody has set the admin password yet. Section 23. |
| **Hosting is not enabled on this server yet** / **Hosting service offline** | Built-in hosting is off, or the host's tenants service is stopped. Section 11 or ask the operator. |
| **This account has chat, calls and files only; wallet and host controls are disabled** | You are on a chat-only guest account. |
| Messages arrive late, or only when the app is open | Settings → This device → **Keep running in the background**; allow notifications; check the pill. |
| A tick never turns double | The other device has not confirmed receipt yet. The message waits; it is not lost. |
| **Message not delivered** | Tap **Reconnect** in the dialog, or Contacts → the contact → More → **Reconnect now**. |
| The scan button does nothing | Allow the camera when asked. |
| Lost phone | Revoke it from another paired device (section 10). The account and its history are untouched. |
| App locked out after the fingerprint prompt failed | **Unlock Parlons Cloud** asks again; the device PIN works too. |

Where to look for logs: Node → **Open control panel → Event log** (Parlons Node); otherwise ask whoever runs the machine (section 30).

---

# Part B. Hosting accounts

## 18. Which kind of host do you need?

| You want | Use | Section |
|---|---|---|
| Your own account on a Mac, Linux box, Raspberry Pi or Windows PC | The one-line installer (`get-parlons-cloud.sh` / `.ps1`) | 19 |
| Your own account on a VPS you manage from your laptop, also carrying relay traffic | `ops/deploy-parlons-cloud.sh` | 20 |
| Accounts for several people on one box, with seeds encrypted at rest | `ops/deploy-parlons-tenants.sh` and the `parlons-tenants` service | 21 |
| A full Minima node with Parlons built in | Parlons Node (`cloud/NODE-SETUP.md`); add `parlons-tenants` beside it to host friends | 21, 23 |
| Chat-only guest accounts inside your own account | Built-in hosting (web panel or Portal) | 11, 24 |

Every host needs a machine that stays on and has outbound internet. A single account needs no domain, no TLS certificate and no reverse proxy. Inbound ports are needed only if the host also runs a relay for others.

---

## 19. One account on your own machine: the one-line installers

### How to install on a Mac, Linux, Raspberry Pi or VPS

1. Open a terminal on the machine that will run the account.
2. Paste this and press Enter:

   ```
   curl -fsSL https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-cloud.sh | bash
   ```

3. Wait for the five steps to finish. The last screen shows your address, an invite QR and the invite text.
4. On your phone, pair the Portal with that invite (section 4).
5. Back up the seed now (below).

### How to install on Windows 10 or 11

1. Open PowerShell.
2. Paste this and press Enter:

   ```
   irm https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-cloud.ps1 | iex
   ```

3. Wait for it to finish, then pair your phone with the invite it prints (section 4).
4. Back up the seed in `C:\Users\<you>\.parlons\seed.txt` (below).

The Windows script says of itself that it has not yet been verified on a Windows machine by its authors. Report anything that goes wrong.

### What the installer does

The installer:

1. installs Java 11 or newer if it is missing (Homebrew on a Mac, apt, dnf, apk or pacman on Linux, winget on Windows), plus `qrencode` where it can;
2. downloads the newest `parlons-cloud` release from GitHub and checks its SHA-256 checksum;
3. registers a service that survives reboots: a launchd agent on a Mac, a systemd user unit (with linger) or a system unit on Linux, a Scheduled Task named **Parlons Cloud** on Windows;
4. starts the account, pairs this terminal as the first device, and mints a fresh code;
5. prints the account address, the invite for your phone (as text and as a QR), and the `parlons` command.

It ends like this (the address and invite are printed in full):

```
  Your Parlons account is running (Parlons Cloud 0.11.124).

  Address (share it; it never changes):
  <the full MAX# address>

  Pair your phone: open the Parlons Cloud app, tap Scan account QR, point it at this:
  <a QR code, drawn in the terminal>
  or paste the invite (the code half works once):
  <the full invite>

  BACK UP YOUR SEED NOW:  ~/.parlons/seed.txt  -  it is your identity AND a wallet.
```

The installer's text says **Scan account QR**, which is the iPhone app's label. In the Android Portal the button is **Scan pairing QR**.

### Options

| Option | Effect |
|---|---|
| `… \| bash -s -- --no-relay` (Windows: `.\get-parlons-cloud.ps1 -NoRelay`) | Account only: do not relay for others. By default the account also runs a relay on TCP port 9501. |
| `… \| bash -s -- --update` | Fetch the newest release and restart. Running the install command again does the same. |
| `… \| bash -s -- --uninstall` (Windows: `-Uninstall`) | Stop and remove the service. Your account data is kept. |
| `PARLONS_DATA=<dir>` | Data folder (default `~/.parlons`). |
| `PARLONS_HEAP=<size>` | Java heap (default `256m`). |

By default the account runs with `--relay-port 9501 --no-direct`: it relays for others on port 9501, and on Linux the installer opens 9501 in ufw when ufw is active. Nothing breaks if 9501 cannot be reached from outside; the account still works through the relay fleet, it just carries no one else's messages. With `--no-relay` it opens no inbound port at all. Its web panel listens on `127.0.0.1:9587` (section 24).

#### How to make your relay reachable for others

1. Install as usual; the relay is on by default.
2. Make sure TCP port 9501 can be reached from the internet: allow it in your cloud provider's firewall, or forward it on your home router.
3. On Linux, the installer opens 9501 in ufw when ufw is active.
4. Prove the port from another machine (section 28).

#### How to install without a relay

```
curl -fsSL https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-cloud.sh | bash -s -- --no-relay
```

On Windows, download `get-parlons-cloud.ps1` and run `.\get-parlons-cloud.ps1 -NoRelay`.

#### How to use a different data folder or heap

1. Set the variable in front of `bash`:

   ```
   curl -fsSL https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-cloud.sh | PARLONS_DATA=/srv/parlons PARLONS_HEAP=512m bash
   ```

2. Use the same variables on every later run of the installer, so it finds the same folder.

#### How to update

1. Run:

   ```
   parlons update
   ```

   Or run the install command again. On Windows, run the install command again.
2. The installer fetches the newest release, checks its SHA-256 and restarts the account.
3. Check it is back:

   ```
   parlons status
   ```

#### How to remove the service and keep the data

1. Run:

   ```
   parlons uninstall
   ```

   On Windows, run `.\get-parlons-cloud.ps1 -Uninstall`.
2. The service stops and is removed. Your account data stays in the data folder.

### Every day

| | Mac / Linux | Windows |
|---|---|---|
| Is it running? | `parlons status` | `parlons status` |
| Watch the log | `parlons log` | `parlons log` |
| Stop / start / restart | `parlons stop`, `parlons start`, `parlons restart` | `parlons stop`, `parlons start` |
| Update | `parlons update` (or run the install command again) | run the install command again |
| Remove the service, keep the data | `parlons uninstall` | `.\get-parlons-cloud.ps1 -Uninstall` |
| Open the web panel | `parlons panel` | `parlons panel` |
| Where things live | `~/.parlons` (data), `~/.parlons/bin` (program), `~/.parlons-client` (the terminal's own device key) | `%USERPROFILE%\.parlons`, `%USERPROFILE%\.parlons-client` |

To remove the account itself (identity, chats, seed), the uninstaller tells you to delete the data folder and `~/.parlons-client` yourself. Back up first: deleting the seed deletes the identity and any funds held against it.

### Back up the seed

`~/.parlons/seed.txt` (on Windows `C:\Users\<you>\.parlons\seed.txt`) holds the 24 words. The installer never prints them.

1. Make sure nobody can see your screen.
2. Show the words:

   ```
   cat ~/.parlons/seed.txt
   ```

   Or, from any paired terminal, `parlons seed`.
3. Copy them to paper. Keep the paper safe and offline.
4. Clear the terminal.

### Move an account to another machine

1. On the old machine: `parlons backup my-account.pbk`, choose a passphrase.
2. Copy the file to the new machine.
3. On the new machine, restore it **before** running the installer, into the folder the installer will use:

   ```
   mkdir -p ~/.parlons
   curl -fsSL -o ~/parlons-cloud.jar "$(curl -fsSL https://api.github.com/repos/eurobuddha/maxima/releases | grep -oE 'https://github.com/[^"]+/cloud-v[^"]+/parlons-cloud-[0-9.]+\.jar' | head -1)"
   java -jar ~/parlons-cloud.jar --data ~/.parlons --restore my-account.pbk
   curl -fsSL https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-cloud.sh | bash
   ```

   `--restore` asks for the passphrase on the console, and refuses to run over an existing identity. It prints what it restored (name, contacts, key-use counters, paired devices and chat records).
4. Stop the old machine for good: `parlons uninstall` there, and keep its data only as a cold backup.

Paired devices reconnect on their own, because the address is the same.

---

## 20. One account on a VPS: deploy-parlons-cloud.sh

### How to deploy an account to a VPS

1. On your own computer, get a checkout of the repository.
2. Build the jar, or put a released `parlons-cloud-0.11.124.jar` into `dist/`:

   ```bash
   ./gradlew :cloud:fatJar
   ```

3. Make sure you can SSH to the VPS as root without a password prompt.
4. Run the deploy script:

   ```bash
   ops/deploy-parlons-cloud.sh root@vps.example.com --port 9501 --name "My account"
   ```

5. At the end, the script prints the account address and a one-time pairing code. Pair your phone with them (section 4).
6. Read the seed once and store it offline:

   ```bash
   ssh root@vps.example.com 'cat /var/lib/maxima/seed.txt'
   ```

7. Allow the relay port in your cloud provider's firewall, and prove it from another machine (section 28).

To update later, run the same command again. Install and update are the same operation.

### Options

`--port N` (relay port, default 9501), `--name "…"` (display name), `--jar FILE` (default: the newest `dist/parlons-cloud-*.jar`), `--heap 256m`, `--memmax 1024M`, `--blob 1024` (relay media shelf in MB), `--peers h:p,h:p` (mesh peers for the relay).

The script is idempotent: install and update are the same command. In order it:

1. installs a headless Java if the box has none;
2. creates an unprivileged `maxima` user and `/var/lib/maxima` (mode 700);
3. uploads the jar to `/opt/maxima`, checks its SHA-256 and only then points the `parlons-cloud.jar` symlink at it;
4. writes a hardened `parlons-cloud.service` (sandboxed, memory-capped, seccomp-filtered, cloud-metadata addresses blocked), running `--data /var/lib/maxima --relay-port <port> --no-direct --blobstore <MB>`;
5. opens the relay port in ufw or firewalld if either is active;
6. starts the service and proves it is listening;
7. prints the account address and the one-time pairing code.

It never prints the seed. Read it once over SSH, as in step 6 above.

### If the script printed no address or code

1. SSH to the VPS.
2. Find the address in the journal:

   ```bash
   journalctl -u parlons-cloud | grep 'permanent address' | tail -1
   ```

3. If a device is already paired, there is no bootstrap code on disk. Mint a new code from a paired device (section 10).

A manual install (four commands, a minimal unit) is described in `cloud/SETUP.md`. Note that when the account runs under systemd its output is a log, and the program deliberately never prints a new seed there; run it once in the foreground if you want to see the words on first start, or read `seed.txt` afterwards.

---

## 21. Many accounts: the parlons-tenants service

A multi-account host keeps one always-on account per folder under one directory, all in one process. It is for people who will not run their own server: an iPhone user, a friend, a reviewer. Every tenant has its own identity, devices, chat and backup, and can leave at any time as an encrypted bundle.

### How to install the tenants host

You need a Linux box that stays on (512 MB of RAM covers about ten accounts) and the repository on your own computer.

1. On your own computer, build the jar (`./gradlew :cloud:fatJar`) or put `parlons-cloud-0.11.124.jar` into `dist/`.
2. Make sure you can SSH to the box as root with a key (the script runs SSH in batch mode, so it cannot answer a password prompt).
3. Run:

   ```bash
   ops/deploy-parlons-tenants.sh root@your.box
   ```

4. Wait for the script to prove the host is up and polling for tenants.
5. Copy `/etc/parlons-tenants.env` from the box to safe offline storage, separate from data backups:

   ```bash
   scp root@your.box:/etc/parlons-tenants.env ./parlons-tenants.env.backup
   ```

6. Create the first tenant (section 22).

### Options

| Option | Default | Meaning |
|---|---|---|
| `--jar FILE` | newest `dist/parlons-cloud-*.jar`, else `cloud/build/libs/parlons-cloud.jar` | The jar to install. |
| `--heap SIZE` | `512m` | Java heap (about ten tenants). |
| `--memmax SIZE` | `768M` | systemd memory ceiling. |
| `--dir PATH` | `/var/lib/parlons-tenants` | The tenants directory (a plain absolute path). |
| `--peers h:p,h:p` | none | Extra fleet relays as seeds. |
| `--manage-from-node` | off | Connect the owner UI of a Parlons Node (0.2.118 or newer) on the same box to this service (below). |

The script:

1. installs a headless Java if missing;
2. creates the `maxima` user and the tenants directory (mode 700);
3. uploads the jar to `/opt/maxima`, checks its SHA-256 and points `parlons-cloud.jar` at it;
4. writes the unlock passphrase file `/etc/parlons-tenants.env` **once** (root-only, a random `PARLONS_UNLOCK=` value), and a hardened `parlons-tenants.service`;
5. restarts the service and proves the host is up and polling for tenants.

The unit runs:

```
/usr/bin/java -Xmx<heap> -jar /opt/maxima/parlons-cloud.jar \
    --tenants /var/lib/parlons-tenants --no-relay --no-direct --panel-port 9597 --unlock env
```

So by default the host opens **no inbound port** (it attaches to the relay fleet outbound only) and can sit beside a Parlons Node on the same box. New tenants are created with encrypted seeds (`seed.enc`) because an unlock passphrase is in force.

**Back up `/etc/parlons-tenants.env`.** Every `seed.enc` is encrypted under it. Without it, an encrypted seed cannot be opened.

### With a Parlons Node: --manage-from-node

Use this when a Parlons Node (0.2.118 or newer) already runs on the box and its owner should manage guests from the Portal.

1. Check that `parlons-node` is installed on the box. The script refuses to run without it.
2. Run:

   ```bash
   ops/deploy-parlons-tenants.sh root@your.box --jar dist/parlons-cloud-0.11.124.jar --manage-from-node
   ```

3. Wait for both services to restart.
4. In the Portal paired to the node, open **Node** and check that **Hosted accounts** appears, with **Manage hosted accounts**.

The script adds a drop-in `/etc/systemd/system/parlons-node.service.d/hosted-accounts.conf` (setting `PARLONS_HOSTED_DIR` to the tenants directory and allowing the node to write there), then restarts `parlons-node`. The node's existing command line and data are preserved. The script refuses to run if `parlons-node` is not installed. The owner can then manage guests from the Portal: **Node → Hosted accounts → Manage hosted accounts** (section 11), and from the server with `sudo parlons-pair --guest <name>` (section 23).

### Web panels for tenants

`deploy-parlons-tenants.sh` writes `--panel-port 9597` into the unit. Each tenant gets its own loopback web panel: the first to start takes 9597, the next 9598, and so on, in the order they start. The range starts above 9587 because a Parlons Node on the same box already holds `127.0.0.1:9587`, the default panel port.

Older units had `--no-panel`, so their tenants had no panel.

#### How to give tenants panels on an older unit

1. Re-run the deploy script. It rewrites the unit with `--panel-port 9597`:

   ```bash
   ops/deploy-parlons-tenants.sh root@your.box
   ```

2. Or edit the unit by hand on the box. Keep a copy first:

   ```bash
   cp /etc/systemd/system/parlons-tenants.service /etc/systemd/system/parlons-tenants.service.before-panels
   systemctl edit --full parlons-tenants
   ```

   In `ExecStart`, change `--no-panel` to `--panel-port 9597`. Then:

   ```bash
   systemctl daemon-reload && systemctl restart parlons-tenants
   ```

#### How to find a tenant's panel port

1. On the box, read the tenant's `panel.txt`:

   ```bash
   sudo cat /var/lib/parlons-tenants/alice/panel.txt
   ```

2. Use that port. Do not guess: numbers are handed out in sequence and not reused while the host runs, so a tenant that is paused and resumed comes back on the next number.

If a panel port is busy, that tenant's panel does not start (the log says *web panel could NOT start on 127.0.0.1:<port>*). The account itself carries on. Re-running `deploy-parlons-tenants.sh` rewrites the unit, so any hand edits you made are lost and must be made again.

Opening a tenant's panel from your computer is covered in section 24.

### Capacity

Every tenant attaches to about three fleet relays from the host's one IP address, and relays cap unregistered connections per source IP (32 by default). Plan on about ten tenants per host. The default creation limit for hosted accounts is ten, including paused ones (`-Dparlons.hosted.limit=<n>` changes it).

---

## 22. Creating, pausing and removing tenants

### Create

#### How to create a tenant from your computer

1. Pick a name: 1 to 64 characters, letters, digits, `.`, `_` and `-`, starting with a letter or digit.
2. Run:

   ```bash
   ops/tenant-new.sh root@your.box alice
   ```

   Add `--dir <path>` if you did not use the default tenants directory.
3. Wait. The script runs `parlons-cloud --tenant-new` on the box as the `maxima` user. It prints *waiting for hosted account alice to connect…* and waits up to 60 seconds for the running host to start the new account.
4. Copy the invite it prints and send it privately to the account's user, or turn it into a QR with any QR maker.

It prints, in full:

```
  address:   <the full MAX# address>
  pair code: <XXXX-XXXX-XXXX>   (one-time)
  invite:    <the full invite>
  A valid invite pairs immediately. Share it only with this account's user.
```

New accounts start within five seconds; nothing else restarts.

#### How to create a tenant on the box directly

1. SSH to the box as root.
2. Run:

   ```bash
   runuser -u maxima -- java -jar /opt/maxima/parlons-cloud.jar --tenant-new /var/lib/parlons-tenants alice
   ```

3. Send the printed invite privately to the account's user.

Other ways to create one: `sudo parlons-pair --guest alice` on a Parlons Node with `--manage-from-node` (section 23), or the owner's Portal (section 11).

### Pause and resume

Paused accounts keep their identity and chats. The owner's Portal **Pause account** / **Resume account** does the same thing.

#### How to pause a tenant

1. SSH to the box.
2. Create the tenant's `.stop` file:

   ```bash
   touch /var/lib/parlons-tenants/alice/.stop
   ```

3. Within five seconds the log shows `tenant alice: stopped (.stop)`.

#### How to resume a tenant

1. Remove the `.stop` file:

   ```bash
   rm /var/lib/parlons-tenants/alice/.stop
   ```

2. Within five seconds the log shows `hot-add: alice`, and the tenant starts again.

### A new invite for an existing tenant

A tenant's current invite is in `/var/lib/parlons-tenants/<name>/invite.txt` while a code is outstanding (`pair-code.txt`). Once used, both are deleted.

#### How to issue a new invite for a tenant

Use one of these:

1. **From your computer.** Run the create command again for the existing name:

   ```bash
   ops/tenant-new.sh root@your.box alice
   ```

   For an existing folder it does not replace the identity. It re-issues the outstanding code or mints a new one.
2. **From the tenant's own device.** The tenant opens **Node → Pair my other device** in their Portal. Tenants on a `parlons-tenants` host have no admin password, so a code is minted at once.
3. **From the owner's Portal** (with `--manage-from-node`): **Node → Manage hosted accounts**, tap the account, then **Show invitation**.

#### How to read an outstanding invite on the box

1. SSH to the box.
2. Run:

   ```bash
   sudo cat /var/lib/parlons-tenants/alice/invite.txt
   ```

   If the file is missing, the code has been used. Issue a new invite (above).

### Restore a tenant from a backup bundle

The host starts any new folder within five seconds, and a folder with no seed gets a **new** identity. `--restore` writes `seed.txt` last. So restore into a staging folder **outside** the tenants directory, then move it in.

1. Copy the `.pbk` file to the box, somewhere the `maxima` user can read it.
2. Pick a tenant name that does not exist yet. Use a staging path on the same filesystem as the tenants directory.
3. In an interactive SSH session (`--restore` asks for the passphrase on the console), run:

   ```bash
   sudo install -d -o maxima -g maxima -m 700 /var/lib/parlons-restore-alice
   sudo runuser -u maxima -- java -jar /opt/maxima/parlons-cloud.jar --data /var/lib/parlons-restore-alice --restore /path/alice.pbk
   ```

4. Type the backup's passphrase when asked. The command prints what it restored.
5. Move the folder in:

   ```bash
   sudo mv /var/lib/parlons-restore-alice /var/lib/parlons-tenants/alice
   ```

6. Within five seconds the host starts it with the restored identity.

The restored `seed.txt` is plaintext (mode 0600). To encrypt it, follow the next procedure.

### Encrypt existing plaintext seeds

1. Make sure nobody can see your screen, then read the unlock passphrase. You will type it in step 3:

   ```bash
   sudo grep PARLONS_UNLOCK /etc/parlons-tenants.env
   ```

2. Stop the host:

   ```bash
   systemctl stop parlons-tenants
   ```

3. In an interactive SSH session, run the encryption and type the same passphrase as `PARLONS_UNLOCK` when asked:

   ```bash
   sudo runuser -u maxima -- java -jar /opt/maxima/parlons-cloud.jar --tenants /var/lib/parlons-tenants --unlock prompt --encrypt-seeds
   ```

4. Start the host again:

   ```bash
   systemctl start parlons-tenants
   ```

Each `seed.txt` becomes `seed.enc`. Each is verified before its plaintext is deleted.

### Remove a tenant

There is no delete command. A tenant's folder holds the account's keys: treat it like a wallet.

1. Pause the tenant (`touch /var/lib/parlons-tenants/alice/.stop`).
2. Make sure the person has their backup bundle, or no longer needs the account.
3. Move the folder out of the tenants directory:

   ```bash
   sudo mv /var/lib/parlons-tenants/alice /var/lib/parlons-removed-alice
   ```

4. Keep it as a cold backup, or wipe it once you are sure.

---

## 23. Owner and guest invitations from the server

### The admin pairing password

Owner invitations (codes that give full control of the owner account) are protected by an **admin pairing password** on hosts that have the password service: a standalone `parlons-cloud` account, a Parlons Node 0.2.118 or newer, and the desktop node apps. Guest invitations never need it.

- It must be 10 to 1024 characters.
- The host stores only a verifier (encrypted with scrypt and AES-GCM) in `.admin-pairing-password` in the account's data folder; it never stores the password.
- After each attempt the host waits two seconds before accepting another.
- It can be set or changed only locally: in the host's web panel, in the desktop apps' Parlons Devices page, or on the command line. There is no remote password reset.
- Existing owner devices remain owners when you add it.

#### How to set the admin password on a Parlons Node

1. Open an interactive SSH session to the node's server.
2. Run:

   ```bash
   sudo parlons-pair --set-admin-password
   ```

3. At `Admin password (Enter for a guest invitation):` type the new password (10 to 1024 characters). At `Confirm admin password:` type it again.
4. The command prints **Admin pairing password saved.**

Without an interactive terminal it stops with *Use an interactive SSH terminal (ssh -t) for the admin password*.

#### How to set the admin password from the web panel

1. Open the account's web panel (section 24).
2. Open **Devices** and find **Set admin password** (or **Change admin password** if one is set).
3. To change an existing password, type it into **Current password**.
4. Type the new password into **New password** and again into **Confirm new password**. It must be at least 10 characters.
5. Click **Save password**. The panel confirms *Admin password saved. You can now create an owner invitation.*

#### How to set the admin password from the command line

1. Open an interactive terminal on the machine that runs the account.
2. For a `deploy-parlons-cloud.sh` account, run:

   ```bash
   sudo runuser -u maxima -- java -cp /opt/maxima/parlons-cloud.jar com.eurobuddha.maxima.cloud.AdminPairingPassword set /var/lib/maxima
   ```

   For a one-line-installer account, run it as yourself:

   ```bash
   java -cp ~/.parlons/bin/parlons-cloud.jar com.eurobuddha.maxima.cloud.AdminPairingPassword set ~/.parlons
   ```

3. Type the password twice. The command prints **Admin pairing password saved.**

On the desktop node apps, set it in the app's Parlons Devices page.

### sudo parlons-pair

`parlons-pair` is installed with a Parlons Node and shows the same invite as the app's Node tab.

| Command | What it does |
|---|---|
| `sudo parlons-pair` | Asks for the admin password. Enter it for an **owner** invitation; press Enter instead for a **guest** invitation, then type the guest's account name. |
| `sudo parlons-pair --admin` | Owner invitation. A wrong password fails and never issues a code. |
| `sudo parlons-pair --guest alice` | Creates (or reuses) hosted account `alice` in the tenants directory and prints its invitation. Needs the hosted-account service (section 21). |
| `sudo parlons-pair --set-admin-password` | Sets the admin password. |
| `--data DIR` | The owner account's data directory (default `/var/lib/parlons-node`). |
| `--tenants-dir DIR` | The tenants directory (default `PARLONS_HOSTED_DIR`, else `/var/lib/parlons-tenants`). |

Environment overrides: `PARLONS_HOSTED_DIR`, `PARLONS_NODE_JAR` (default `/opt/maxima/parlons-node.jar`), `PARLONS_CLOUD_JAR` (default `/opt/maxima/parlons-cloud.jar`).

It prints the full `MAX#` address, the one-time code, a QR (if `qrencode` is installed) and the complete invite. If there is no unused code it says so; run it again for a fresh one. Consumed codes are never recovered from old logs or stale files.

#### How to make an owner invitation on a Parlons Node

1. Open an interactive SSH session to the node's server.
2. Run:

   ```bash
   sudo parlons-pair --admin
   ```

3. Type the admin password at the `Admin password (Enter for a guest invitation):` prompt. A wrong password prints *Incorrect admin password* and never issues a code.
4. Scan the printed QR with the Portal (**Scan pairing QR**), or paste the full invite (section 4).

#### How to make a guest invitation on a Parlons Node

This needs the hosted-account service (`--manage-from-node`, section 21).

1. Open an SSH session to the node's server.
2. Run, with the guest's account name:

   ```bash
   sudo parlons-pair --guest alice
   ```

3. It creates the hosted account `alice` (or reuses it) and prints its invitation.
4. Send the invite privately to that person.

Running `sudo parlons-pair` with no option asks for the admin password. Type it for an owner invitation. Or press Enter instead, then type the guest's name at `Hosted account name:` for a guest invitation.

### Where else invites come from

| Source | Owner invite | Guest invite |
|---|---|---|
| Portal, **Node → Pair my other device** | yes (admin password, where the host has one) | leave the password empty → hosted accounts |
| Portal, **Node → Manage hosted accounts → Show invitation** | no | yes |
| Web panel, **Devices → New pairing code** | yes (admin password field when the host has one) | no |
| Web panel, **Devices → Hosted accounts → Show invitation** | no | yes |
| `ops/tenant-new.sh`, `--tenant-new` | no | yes (that tenant's own invite) |
| The bootstrap code on first start (`pair-code.txt`) | yes, for the very first device | n/a |

#### How to make an owner invitation with the parlons command

1. In a terminal where `parlons` is paired to the account, run:

   ```
   parlons newcode
   ```

2. When the host has an admin password, the command asks for it at the console with `Admin password:`. Type it. The password is never taken on the command line.
3. The command prints `code   :` and `invite :` lines. Scan or paste the invite in the Parlons Cloud app. The code half works once.

The prompt needs an interactive console. Run without one (for example from a script), the command fails with **Admin password required for an owner-account invitation**.

---

## 24. The local web panel

Every account host serves a web panel and API for its account on the loopback address only: `http://127.0.0.1:<port>/`. The default port is **9587**. It is the same interface a paired phone has, in a browser on the same machine, without pairing over the network.

### Open it

The link signs the browser in once. The account immediately writes the next unused link to `panel-ticket.txt`. If you open a used link you see **This link has been used or has expired**.

#### How to open the panel on the machine that runs the account

1. In a terminal on that machine, run:

   ```
   parlons panel
   ```

   It opens the browser on a one-time link.
2. Or open the link stored in `<data>/panel-ticket.txt` (for example `~/.parlons/panel-ticket.txt`) yourself.

A paired device on another machine can also ask the account for a link that is valid for 60 seconds. `parlons panel` does this when the account's folder is not on the same machine.

#### How to open the panel on a server from your computer

The panel refuses any `Host` other than `127.0.0.1:<port>` or `localhost:<port>`. So use an SSH tunnel with the **same port number on both ends**.

1. Find the port. A single account uses 9587. For a tenant, read its `panel.txt` (section 21).
2. On your computer, open the tunnel and leave it running:

   ```bash
   ssh -L 9587:127.0.0.1:9587 root@your.box
   ```

   For the first tenant on a node box, use 9597 on both ends instead:

   ```bash
   ssh -L 9597:127.0.0.1:9597 root@your.box
   ```

3. In that SSH session, print the one-time link. For a single account deployed with `deploy-parlons-cloud.sh`:

   ```bash
   sudo cat /var/lib/maxima/panel-ticket.txt
   ```

   For a tenant:

   ```bash
   sudo cat /var/lib/parlons-tenants/alice/panel-ticket.txt
   ```

4. Copy the link and open it in the browser on your computer.

### What it shows

The title bar reads **Parlons!** with the version and **Powered by Maxima**, a status pill, and Theme, Search and More buttons. The tabs:

- **Chats**: conversations, unread counts, search, new group, the chat view with photos, voice notes, files, and calls.
- **Contacts**: add, your address and QR, rename, resolve, remove, details.
- **Devices**:
  - **Pair your own device**: *This gives access to this account. Use Hosted accounts below to invite someone to a separate account.* **New pairing code** shows the invite as a QR and as text, with a copy button. Where the host has an admin password, the field **Admin password** appears above it, and the button stays disabled until a password is set.
  - **Set admin password** / **Change admin password** (hosts with the password service only): Current password, New password, Confirm new password (at least 10 characters), **Save password**.
  - **Hosted accounts** (where the account manages a host): **Enable hosting** / **Stop hosting**, **Refresh**, an **Account name** field with **Create account**, and a row per account with its state, **Show invitation** and **Pause** / **Resume**. The invitation shows as a QR and full text with **Copy tenant invite**.
  - **Devices**: every paired device with its full key and **Revoke**; devices **Waiting for approval** with **Approve**. The panel itself is listed as **this computer**.
- **Node**: Account (name, version, uptime, relays attached, mesh peers, paired devices, mailbox/outbox, the permanent address in full), **Your relay**, **Port forwarding** help when a relay is running, **Relays** (add `host:port` or a relay QR text, detach, **Use the built-in relay list**), **Directory anchor** (**Pin best relay**, **Clear**, **Republish**), and the **Log**.
- **Settings**: Language, Profile (**Your name**), Connected apps (minimaDocs), Privacy (**Read receipts**), Appearance, and **This panel** (**Open in a browser**, **Sign out**).

### Tasks in the panel

#### How to pair your own device from the panel

1. Click **Devices**.
2. Under **Pair your own device**, type the **Admin password** if the field is shown.
3. Click **New pairing code**. The panel shows **New code minted**, the invite as a QR and as text.
4. Scan the QR with the Portal (**Scan pairing QR**), or click the copy button and paste the invite on the new device.

#### How to approve or revoke a device in the panel

1. Click **Devices**.
2. Under **Waiting for approval**, click **Approve** next to a device you recognise. The panel shows **Approved**.
3. To cut a device off, click **Revoke** next to it and confirm *Revoke this device? It loses access to the account.* The panel shows **Revoked**.

#### How to host a friend from the panel

1. Click **Devices** and scroll to **Hosted accounts**.
2. Built-in hosting only: click **Enable hosting** and confirm. Set the admin password first.
3. Type a name into **Account name** (for example *alice*) and click **Create account**. The panel says *Account created. Refresh in a few seconds, then choose Show invitation when it is running.*
4. Click **Refresh** until the account's state is **running**.
5. Click **Show invitation**. The invitation shows as a QR and full text.
6. Click **Copy tenant invite** and send it privately to that person.

To pause an account, click **Pause** on its row and confirm. **Resume** brings it back. **Stop hosting** takes every built-in guest account offline; their identities and chats are kept.

#### How to add or remove a relay in the panel

1. Click **Node** and scroll to **Relays**.
2. Type `host:port` (for example `relay.example.com:9501`) or paste a relay QR text into the field, and click **Add**.
3. To remove one of your own relays, click **Remove** on its row. The panel shows **Detached**.
4. Flip **Use the built-in relay list** to turn the shipped list on or off.

#### How to pin the directory anchor in the panel

1. Click **Node** and scroll to **Directory anchor**.
2. Click **Pin best relay**. The line below shows *pinned: …* with the relay.
3. **Clear** removes the pin. **Republish** pushes your directory record out again now.

#### How to sign the browser out

1. Click **Settings** and scroll to **This panel**.
2. Click **Sign out**. To come back, open a fresh one-time link.

### What it will not do

The seed, the backup, wallet sends, payments, the node console and NFT hosting are not available from the panel. *A cookie on a desktop is weaker than a phone in your hand*; those stay on paired phones and the command line.

### Security of the panel

- It listens on `127.0.0.1` only.
- The ticket becomes an HttpOnly, SameSite=Strict session cookie (idle sessions expire after seven days).
- Requests with a foreign `Host` (DNS rebinding) or a foreign `Origin` are refused; only an allow-list of account methods can be called.
- To the account, the panel is a local device. Revoking **this computer** from any phone locks the panel out. To re-enable a revoked panel, stop the account, delete `<data>/local-device.key`, and start it again.
- Anyone who can read `panel-ticket.txt` can open the panel. It is mode 0600; keep it that way.

`--no-panel` turns it off. `--panel-port <n>` moves it.

---

## 25. Command-line flags reference

`java -jar parlons-cloud-0.11.124.jar [options]`. Every flag the program accepts:

### Single account

| Flag | Default | Meaning |
|---|---|---|
| `--data <dir>` | `~/.parlons` | The account's data directory. |
| `--name <name>` | keep the stored name | Display name for the account. |
| `--relay-port <n>` | `9501` | Port of the pool relay this account runs for the network. |
| `--no-relay` | (relay on) | Run the account without a pool relay. |
| `--direct-port <n>` | `9536` | Tier-2 direct reachability port. |
| `--no-direct` | (direct on) | Disable direct reachability. |
| `--panel-port <n>` | `9587` | The local web panel and API, on `127.0.0.1` only. |
| `--no-panel` | (panel on) | Run without the web panel. |
| `--wallet rpc:<port>[:<secret-file>]` | the account's own signer over its seed | Use a Minima node already running on this machine as the wallet, over its loopback RPC. The node signs; the account keeps the identity only. The file holds the node's `-rpcpassword`. |
| `--host <ip>` | say nothing | Public address to advertise. |
| `--relays <list>` | none | Extra fleet relays to attach to (comma-separated `host:port`). |
| `--no-builtin-relays` | (built-in list on) | Do not use the compiled-in relay list as a seed source; only `--relays` and relays added from the panel. Needs at least one. |
| `--peers <list>` | none (or `MAXIMA_PEERS`) | Mesh peers for the pool relay (comma-separated). |
| `--blobstore <MB>` | `1024` | Relay media shelf size in MB. |
| `--import-seed <file\|prompt>` | | Adopt an existing 24-word phrase, a custom Minima phrase, or a seed hex, into a fresh directory only, then exit. `prompt` reads it from the console. |
| `--restore <file.pbk>` | | Restore an encrypted backup bundle into a fresh directory, then exit. Needs an interactive console. |
| `-v`, `--version` | | Print `parlons-cloud 0.11.124` and exit. |
| `-h`, `--help` | | Print the help and exit. |

### Multi-account host

| Flag | Meaning |
|---|---|
| `--tenants <dir>` | Run every `<dir>/<name>/` account in this one process. Only the first tenant opens the relay and direct ports; the others share them. Each tenant's panel takes `--panel-port`, `+1`, `+2` … in start order (none with `--no-panel`). |
| `--unlock <prompt\|env>` | The passphrase that opens `seed.enc` files: asked on the console, or read from `PARLONS_UNLOCK` (for systemd). Needed when any tenant seed is encrypted. While it is in force, new tenants get encrypted seeds. |
| `--tenant-new <dir> <name>` | Operator command: make `<dir>/<name>/`, wait (60 s) for the **running** host to start it, print its address, pair code and invite, then exit. |
| `--encrypt-seeds` | With `--tenants` and `--unlock`: turn every tenant's `seed.txt` into `seed.enc` (scrypt and AES-GCM under the unlock passphrase), verify, delete the plaintext, and exit. |

### Environment and system properties

| Name | Meaning |
|---|---|
| `PARLONS_UNLOCK` | The unlock passphrase for `--unlock env`. Kept in `/etc/parlons-tenants.env` by the deploy script. |
| `MAXIMA_PEERS` | Mesh peers, used when `--peers` is not given. |
| `-Dparlons.hosted.limit=<n>` | Hosted-account creation limit (default 10). Pass it to `--tenant-new` too if you raise it. |
| `-Dmaxima.relay.maxpersource=<n>` | On relays you run: unregistered connections allowed per source IP (default 32). |

### Default ports at a glance

| Port | What | Exposure |
|---|---|---|
| 9501/tcp | pool relay | public, only if you run a relay |
| 9536/tcp | direct reachability | public, only if enabled |
| 9587/tcp | web panel and API | loopback only, never expose |
| 9597/tcp and up | tenant panels on a node box (when configured, section 21) | loopback only |

---

## 26. The parlons command

The one-line installer installs `parlons`. It is a paired device of its own (its key lives in `~/.parlons-client`), so it can drive an account on this machine or any other.

**Service commands** (this computer's account): `start`, `stop`, `restart`, `log [n]`, `update`, `uninstall`.

**Account commands:**

| Command | What it does |
|---|---|
| `whoami` | This device's key and which account it drives. |
| `connect <account MAX#>` | Point this device at an account. |
| `pair [<code>]` | Pair this device (with a code, or pending → approve). |
| `ping` | Account name and address. |
| `status` | Name, version, uptime, relays, pool relay, paired devices, permanent address. |
| `devices` | Paired and pending devices, with full keys. |
| `newcode` | Mint a fresh one-time code and invite. When the host has an admin password, it asks for it at the console (section 23). |
| `invite` | Print the outstanding invite from the account's folder on this machine. |
| `panel` | Open the account's web panel in a browser (one-time link). |
| `approve <key>` / `revoke <key>` | Approve a pending device / revoke a device. |
| `name <display name>` | Set the account's name (announced to contacts). |
| `contacts`, `add <addr\|MAX#>`, `rename <key> <name>`, `resolve <key>` | Manage contacts. |
| `chats`, `read <peer key>`, `send <peer key> <msg>`, `markread <peer key>` | Read and send messages. |
| `pay <key> <amt> [memo]` | Pay a contact from the account's wallet. |
| `wallet address`, `wallet balance`, `wallet set <Mx>`, `wallet resync <file>` | The account's wallet (resync: node accounts only). |
| `cmd <node command…>` | Run any Minima command on the account's node (Parlons Node only); full output. |
| `nft put <file> …`, `nft list`, `nft newcollection`, `nft delete <path>` | Host NFT art on the node (Parlons Node only). |
| `figures`, `hosts [add\|remove <host:port>]`, `mls pin\|clear\|republish`, `nodelog [clear]` | Relay figures, relays, directory anchor, the account's log. |
| `seed` | Reveal the account's 24 words (in your terminal only). |
| `backup <file.pbk>` | Write the encrypted portable account bundle. |

Run `parlons` alone for the list.

### How to drive an account from another machine

The `parlons` command on any machine can drive any account, once paired.

1. Install `parlons` on that machine with the one-line installer (section 19), or use one you already have.
2. Point it at the account, using the full `MAX#` address:

   ```
   parlons connect <account MAX#>
   ```

   It prints `account saved. next: parlons pair <code>`.
3. Pair it with a fresh code from a paired device (section 10):

   ```
   parlons pair ABCD-EFGH-JKLM
   ```

   It prints `paired ✓ — this device can now drive your account.`
4. Or run `parlons pair` with no code. It prints `pairing requested. Approve it from an already-paired device:` and the exact `parlons approve <key>` line to run on a paired device.
5. Check it works:

   ```
   parlons status
   ```

### How to read and send messages from a terminal

1. List your chats (name, last message, unread count):

   ```
   parlons chats
   ```

2. Find the contact's full key:

   ```
   parlons contacts
   ```

3. Read one conversation:

   ```
   parlons read <peer key>
   ```

4. Send a message:

   ```
   parlons send <peer key> hello
   ```

### How to pay a contact from a terminal

1. Find the contact's key with `parlons contacts`.
2. Pay them from the account's wallet (amount in MINIMA, memo optional):

   ```
   parlons pay <key> 1.5 lunch
   ```

3. It prints `payment building on your node ✓ (watch the chat bubble)`. The `pay` command sends Minima only. Send MxUSD from the Portal.

---

## 27. Files in a data directory

A single account's directory (`~/.parlons`, `/var/lib/maxima`) and each tenant folder share one layout. Files marked **secret** must stay mode 0600 and must never be logged, pasted or shared.

| File | Contents |
|---|---|
| `seed.txt` | **Secret.** The 24 words (or an imported phrase or seed hex). Identity **and** wallet. |
| `seed.enc` | **Secret.** A tenant's seed encrypted at rest under the unlock passphrase. |
| `account.txt` | The permanent `MAX#` address, written once the account has attached. Public. |
| `pair-code.txt` | **Secret.** The outstanding one-time pairing code. Deleted when used. |
| `invite.txt` | **Secret.** The full address, then `?code=`, then the code. Deleted when the code is used. |
| `panel.txt` | The panel's loopback URL. |
| `panel-ticket.txt` | **Secret.** One unused one-time panel link. |
| `local-device.key` | **Secret.** The panel's device key. Delete (with the account stopped) to re-enable a revoked panel. |
| `.admin-pairing-password` | The admin password verifier (never the password). |
| `devices.json` | Paired devices. |
| `wallet/` | The wallet's key-use counters. |
| `.stop` | (tenants) Present = this tenant is paused. |
| `hosted-chat/` | (owner accounts) Built-in chat-only guest accounts, one folder each; `.enabled` marks hosting as on. |
| `account-history/` | (Parlons Node) Accounts replaced by an identity import, kept for recovery. |
| `log/cloud.log` | (installer accounts) The account's log. |

The tenants directory also holds `.host-status.json`, the host's heartbeat (which tenants are running or failing), refreshed every five seconds.

---

## 28. Ports, firewalls, TLS and reverse proxies

- **A single account needs no inbound port**, no domain and no certificate. It reaches the network through the relay fleet, and phones reach it over Parlons' own encrypted transport, not a web port. The one-line installer (without `--relay`) and the tenants host both run with `--no-relay --no-direct`.
- **For others to reach your relay** (on by default; `--no-relay` turns it off), TCP 9501 (or your `--port`) must be reachable from the internet. The scripts open it in ufw or firewalld when active. A cloud provider's console firewall (Hetzner Cloud Firewall, Vultr Firewall Group, an AWS security group) must allow it too; it is the usual cause of "the phone connects but pairing is flaky". Prove it from another machine, not the box itself:

  ```bash
  nc -z -G 5 vps.example.com 9501 && echo reachable
  ```

- **The relay port is not HTTP.** It speaks the Maxima wire protocol directly. Do not put it behind an HTTP reverse proxy or TLS terminator; clients connect to it as plain TCP and the messages inside are already sealed end to end.
- **The web panel must never be exposed.** It binds to `127.0.0.1` and refuses requests whose `Host` is not its own loopback origin, so a reverse proxy under a domain name gets **wrong host**. Reach it through an SSH tunnel (section 24).
- **Firewalls with outbound rules.** The host needs outbound TCP to the relays it attaches to. A box with default-deny outbound needs rules for them.

### How to open and prove the relay port

Only needed when the host runs a relay.

1. On the box, allow the port in its own firewall. The scripts do this when ufw or firewalld is active. By hand, for ufw:

   ```bash
   ufw allow 9501/tcp
   ```

   For firewalld:

   ```bash
   firewall-cmd --permanent --add-port=9501/tcp && firewall-cmd --reload
   ```

2. In your cloud provider's console, allow inbound TCP 9501 in its firewall too.
3. From another machine (not the box itself), prove it:

   ```bash
   nc -z -G 5 vps.example.com 9501 && echo reachable
   ```

4. `reachable` means the port is open. No output means something still blocks it.

(TLS fronts in the fleet serve other things that run on Parlons Nodes, such as the wallet gateway and the DEX relay; they are not part of a Parlons Cloud host.)

---

## 29. Upgrading and rolling back

Every install keeps the versioned jar next to a stable symlink, so a rollback is one symlink change.

| Host | Upgrade | Roll back |
|---|---|---|
| One-line installer | `parlons update`, or run the install command again | `ln -sf parlons-cloud-<OLDVER>.jar ~/.parlons/bin/parlons-cloud.jar && parlons restart` |
| `deploy-parlons-cloud.sh` | Re-run the same command (with `--jar` for a specific build) | On the box: `cd /opt/maxima && ln -sf parlons-cloud-<OLDVER>.jar parlons-cloud.jar && systemctl restart parlons-cloud` |
| `parlons-tenants` | Re-run `ops/deploy-parlons-tenants.sh <box>` (it keeps `/etc/parlons-tenants.env`) | `ln -sf parlons-cloud-<OLDVER>.jar /opt/maxima/parlons-cloud.jar && systemctl restart parlons-tenants` |

### How to check a downloaded jar

1. Download `parlons-cloud-0.11.124.jar` from the release `cloud-v0.11.124`.
2. Compute its SHA-256. On a Mac:

   ```bash
   shasum -a 256 parlons-cloud-0.11.124.jar
   ```

   On Linux:

   ```bash
   sha256sum parlons-cloud-0.11.124.jar
   ```

3. Compare it with the published value. It must match exactly:

   ```
   0ae833429b641393f93b427695784b534e8a2a06a7194eba74e57aed4f19b231
   ```

The installer and the deploy scripts check the SHA-256 for you and refuse a mismatch.

### How to upgrade the tenants host

1. Back up `/etc/parlons-tenants.env` and the data directories.
2. If you have edited the unit, keep a copy:

   ```bash
   cp /etc/systemd/system/parlons-tenants.service /etc/systemd/system/parlons-tenants.service.before-0.11.124
   ```

3. From your computer, re-run the deploy script with the new jar:

   ```bash
   ops/deploy-parlons-tenants.sh root@your.box --jar dist/parlons-cloud-0.11.124.jar
   ```

   Add `--manage-from-node` again if you used it. The script keeps `/etc/parlons-tenants.env`. It rewrites the unit with `--panel-port 9597`, so re-apply any other edits of your own.
4. Check the service:

   ```bash
   systemctl is-active parlons-tenants
   journalctl -u parlons-tenants | grep 'multi-account host' | tail -1
   ```

   The line should name `Parlons Cloud 0.11.124`.
5. Check that each tenant reports in (section 30).

### How to roll back

1. On the box (or in `~/.parlons/bin` for an installer account), list the versioned jars that are still there:

   ```bash
   ls /opt/maxima/parlons-cloud-*.jar
   ```

2. Point the symlink at the older one and restart, using the line for your kind of host from the table above. For example, on a tenants host:

   ```bash
   ln -sf parlons-cloud-0.11.122.jar /opt/maxima/parlons-cloud.jar && systemctl restart parlons-tenants
   ```

3. Check the version in the log, as in step 4 above.

The Portal app updates independently: install the newer APK over the old one (section 3).

---

## 30. Logs

| Host | Where |
|---|---|
| One-line installer | `parlons log`, or `~/.parlons/log/cloud.log` |
| `deploy-parlons-cloud.sh` | `journalctl -u parlons-cloud -f` |
| `parlons-tenants` | `journalctl -u parlons-tenants` (each tenant's lines are tagged `parlons-cloud/<name>`) |
| Parlons Node | `journalctl -u parlons-node -f` |
| From a paired phone | Node → **Open control panel → Event log** (Parlons Node) |
| From the panel | Node → **Log** |

### How to follow the log

1. Pick the command for your host from the table above. For example, on a tenants host:

   ```bash
   journalctl -u parlons-tenants -f
   ```

2. To see only one tenant, filter by its tag:

   ```bash
   journalctl -u parlons-tenants | grep 'parlons-cloud/alice'
   ```

3. Press Ctrl+C to stop following.

Useful lines:

- `Parlons Cloud 0.11.124 multi-account host: N tenant(s) under <dir>`: the host started.
- `tenant <name>: <Mx identity> (seed encrypted at rest)`: a tenant started.
- `hot-add: <name>`: a new folder was picked up.
- `tenant <name>: stopped (.stop)` / `parked (.stop present)`: a tenant is paused.
- `tenant <name>: failed to start (…), retry in 60 s`.
- `web panel could NOT start on 127.0.0.1:<port>: …`.

The program never writes a seed to a log: under systemd, a newly generated seed is not printed; it tells you to read `seed.txt` once over SSH. Pairing codes are written only to `pair-code.txt`, never logged. Still, when you search logs or unit files, filter out anything mentioning seeds (`… | grep -v seed`), and check before pasting a log anywhere.

---

## 31. Security notes for operators

- **Seed files are wallets.** `seed.txt` and `seed.enc` are identities **and** spendable Minima wallets. Never print them into a log, a ticket, a chat or a screenshot. Never copy them to a machine that does not need them. Read a new seed once, over SSH, and store it offline.
- **One seed, one host, ever.** Two running copies of one identity will eventually reuse a one-time signature key. To move an account, restore its bundle on the new host and stop the old one for good.
- **The operator can read what the host stores.** An always-on account must hold its key in memory to act for its owner. At-rest encryption (`seed.enc`) protects disk images and backups, not against the person running the process. Tell your guests this; the guide pages say it too.
- **Built-in chat hosting stores guest seeds as private files** (mode 0600, plaintext) under the owner's `hosted-chat/` folder. Use the computer's disk encryption to protect a stolen disk. The admin password does not encrypt guest accounts.
- **The unlock passphrase** in `/etc/parlons-tenants.env` is root-only. Back it up separately from the data: losing it makes every `seed.enc` unreadable; leaking it alongside a data backup defeats the encryption.
- **Invites are keys to an account until used.** `pair-code.txt` and `invite.txt` let the first person to use them pair a device. Send invites privately, pair promptly, and if one may have leaked, mint a new code (which replaces the old one) or revoke the device that used it.
- **The admin password** guards owner invitations only. Anyone with full SSH or owner terminal access to the box still controls the server.
- **The panel ticket** grants a browser session as a local device of that account. Keep `panel-ticket.txt` private and do not expose the panel port.
- **Hardened units.** The deploy scripts run the host as the unprivileged `maxima` user, with `NoNewPrivileges`, no capabilities, a system-call allow-list, `ProtectSystem=strict`, a private `/tmp`, `UMask=0077`, the cloud metadata address range blocked, and a memory ceiling. Keep these if you write your own unit.
- **Never truncate identifiers** in anything you build or send around these hosts: addresses, keys, codes and transaction ids must always be shown and copied whole.

---

## 32. Troubleshooting for operators

| Symptom | Cause and fix |
|---|---|
| Installer: **not up after 90 s** | The last log lines are shown. Usually Java did not install or there is no internet. Fix it and run the installer again; nothing is lost. |
| Installer: **Could not reach GitHub to find the release** | Try later, or download the jar from <https://github.com/eurobuddha/maxima/releases> into `~/.parlons/bin` and run the installer again. |
| **ERROR: relay port 9501 is already in use** | Another relay or a Parlons Node holds it. Pick a free port with `--relay-port`, or run with `--no-relay`. With the one-line installer, install without `--relay`. |
| **web panel could NOT start on 127.0.0.1:9587** | Something else (often a Parlons Node) holds the port. Use `--panel-port` with a free port (9597 on node boxes) or `--no-panel`. The account works without its panel. |
| **\<name\>: seed.enc needs --unlock** | Start the host with `--unlock env` (and `PARLONS_UNLOCK` set) or `--unlock prompt`. |
| **--unlock env: PARLONS_UNLOCK is not set** | The unit's `EnvironmentFile=/etc/parlons-tenants.env` is missing or empty. Restore it from backup. |
| **--unlock prompt needs an interactive console (use --unlock env under systemd)** | Exactly that. |
| **no invite after 60 s - is the host running?** | `systemctl status parlons-tenants`. The host polls the directory every five seconds. |
| **Hosted-account service is offline. Ask the server operator to start it.** | The tenants host is not running, or has not written its heartbeat in the last 90 seconds. Start `parlons-tenants`. |
| **Wait until this account is running before creating an invite** / **Account is connecting. Refresh in a few seconds.** | The tenant has not attached yet. Wait, refresh. |
| **Hosted account is paused; resume it before issuing an invite** | Remove its `.stop` file. |
| **Hosting limit reached (10 accounts)** | Paused accounts count. Move an unused folder out, or raise `-Dparlons.hosted.limit`. |
| **Set your admin password before enabling hosting** | Set the admin password first (section 23). |
| **Enable hosting before managing guest accounts** | Built-in hosting is off; enable it (section 11). |
| **Guest hosting is unavailable. Check the data folder permissions and that another host is not using it.** | The owner's `hosted-chat/` folder is not writable, is a symbolic link, or another process holds its lock. |
| **Admin password required for an owner-account invitation** from `parlons newcode` | The command had no console to ask on (for example, it ran from a script). Run `parlons newcode` in an interactive terminal and type the password at `Admin password:`. Or mint the invite from the app or the panel, or pair code-less and approve. |
| **this account's host is too old to send MxUSD - update it (…)** on a phone | The account's host cannot prove it honours the token id. Update it to Parlons Cloud 0.11.121 or newer (0.11.124 is current), Parlons Node 0.2.135 or newer, or minimaCore Desktop 0.18.21 or newer. |
| **Incorrect admin password** from `parlons-pair` | Retry after two seconds. To change a forgotten password on a Parlons Node, use `sudo parlons-pair --set-admin-password` locally. |
| Restore: **wrong passphrase (or a damaged backup file)** | Check the passphrase. |
| Restore: refuses because an identity exists | `--restore` and `--import-seed` never overwrite. Move the old data away first, or use a fresh directory. |
| A phone connects but pairing is flaky (relay hosts) | A cloud console firewall is blocking the relay port. Allow it there too. |
| A tenant gets a **new** identity after you moved a restored folder in | The folder was picked up before `seed.txt` was written. Stop it (`.stop`), and restore again through a staging folder (section 22). Keep the unexpected identity's folder aside until you are sure nobody paired to it. |

### Step-by-step fixes

#### Relay port 9501 is already in use

1. Find what holds it:

   ```bash
   sudo ss -ltnp | grep ':9501'
   ```

2. If it is a Parlons Node or another relay, keep it. Run this account without its own relay instead: for the one-line installer, run `parlons uninstall` and install again with `--no-relay`. For a hand-written unit, change `--relay-port 9501` to `--no-relay`.
3. Or give this account a free port with `--relay-port <n>` (with `deploy-parlons-cloud.sh`, `--port <n>`), and open that port instead (section 28).
4. Restart the service and check the log (section 30).

#### The web panel could not start

1. The log says *web panel could NOT start on 127.0.0.1:<port>*. Find what holds the port:

   ```bash
   sudo ss -ltnp | grep ':9587'
   ```

2. Choose a free loopback port. On a box with a Parlons Node, use 9597 or higher.
3. On a `deploy-parlons-cloud.sh` host, edit the unit's `ExecStart` and add `--panel-port 9597` (or `--no-panel` to run without a panel). On a tenants host the unit already has `--panel-port 9597`; pick a different first port there if 9597 is taken.

   ```bash
   systemctl edit --full parlons-cloud
   systemctl daemon-reload && systemctl restart parlons-cloud
   ```

4. The account works without its panel in the meantime.

#### Encrypted seeds will not open (seed.enc needs --unlock, or PARLONS_UNLOCK is not set)

1. Check the unlock file exists and is not empty, without printing it:

   ```bash
   sudo test -s /etc/parlons-tenants.env && echo present
   ```

2. If it is missing, restore it from your offline backup to `/etc/parlons-tenants.env`, owned by root, mode 600:

   ```bash
   sudo install -o root -g root -m 600 ./parlons-tenants.env.backup /etc/parlons-tenants.env
   ```

3. Check the unit has `EnvironmentFile=/etc/parlons-tenants.env` and `--unlock env`:

   ```bash
   systemctl cat parlons-tenants | grep -E 'EnvironmentFile|--unlock'
   ```

4. Restart:

   ```bash
   systemctl restart parlons-tenants
   ```

Without the original passphrase, an encrypted seed cannot be opened. Restore the tenant from its backup bundle instead (section 22).

#### No invite after 60 seconds from tenant-new

1. Check the host is running:

   ```bash
   systemctl status parlons-tenants
   ```

2. If it is stopped, start it with `systemctl start parlons-tenants`.
3. Check the log for the tenant (`journalctl -u parlons-tenants | grep 'parlons-cloud/alice'`).
4. Run `ops/tenant-new.sh root@your.box alice` again. It does not replace the identity of an existing folder.

#### The hosted-account service is offline

1. On the box, start the tenants host:

   ```bash
   systemctl start parlons-tenants
   ```

2. Wait a few seconds for it to write its heartbeat (`.host-status.json` in the tenants directory).
3. In the Portal, open **Node → Manage hosted accounts** and tap **Refresh**.

#### A tenant got a new identity after a restore

1. Pause the unexpected tenant:

   ```bash
   touch /var/lib/parlons-tenants/alice/.stop
   ```

2. Move its folder out of the tenants directory and keep it aside until you are sure nobody paired to it.
3. Restore the bundle again through a staging folder, then move it in (section 22, "Restore a tenant from a backup bundle").

For anything else, open an issue on the project's GitHub with the version, what you expected, what happened, and the relevant log lines (checked first for anything secret).

---

## 33. Glossary

| Term | Meaning |
|---|---|
| **Account** | One Parlons identity with its contacts, chats, paired devices, settings and (usually) wallet, kept online by a host. |
| **Admin pairing password** | A local password that guards owner invitations on hosts that have the service. 10 to 1024 characters; stored only as a verifier. |
| **Address (`MAX#` form)** | An account's permanent address. Public, several hundred characters, never changes. Always copied whole. |
| **Address (`Mx` key with `@host:port`)** | The older form: the identity key plus the relay currently carrying it. The `host:port` part changes. |
| **Built-in hosting** | Chat-only guest hosting inside an owner account (Parlons Node, desktop node apps, standalone `parlons-cloud`). |
| **Bundle (`.pbk`)** | An encrypted backup of a whole account (identity, devices, settings, contacts, chats), readable by every Parlons host and, for identity and contacts, by the phone app. |
| **Chat-only account** | A guest account with chat, calls and files only. |
| **Device** | Anything paired to an account: a phone, a terminal, the host's panel. Revocable without changing the identity. |
| **Device key** | A paired device's own key. It is not the account's identity. |
| **Directory anchor / MLS** | The location service that resolves a permanent address to where the account is now. It can be pinned to one relay. |
| **Guest** | A person with a hosted account on someone else's host. |
| **Host** | A machine and program that keeps one or more accounts online. Also used for a relay an account attaches to. |
| **Hot-add** | The tenants host starting a new tenant folder within five seconds without a restart. |
| **Invite** | An address and a one-time pairing code in one line, joined by `?code=`. One QR pairs a phone. |
| **Local device / this computer** | The host's own web panel, acting as a paired device of the account. |
| **Mailbox** | Encrypted messages held for someone who was offline. |
| **Mesh peers** | Other relays this account's relay exchanges directory information with. |
| **MxUSD** | The Minima dollar-pegged token, token id `0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90`. |
| **Outbox** | Your own messages not yet accepted by a host; retried with growing gaps. |
| **Owner** | The person whose account runs the machine and controls its hosting. |
| **Owner invitation** | An invite that pairs a device to the owner account, with full control. |
| **Pairing code** | A one-time code, three groups of four characters, that lets one device pair. |
| **Panel ticket** | A one-time link that signs a browser into the local web panel. |
| **Parlons Cloud** | The host program (`parlons-cloud.jar`), and also the name of the Android Portal app and the iPhone pairing feature. |
| **Parlons Node** | A full Minima node with a relay, a wallet gateway and a Parlons account built in. Pairable from the Portal. |
| **parlons-tenants** | The systemd service running `parlons-cloud --tenants`. |
| **Pending approval** | A device that asked to pair without a code and waits for an existing device to approve it. |
| **Pool relay** | A relay an account runs to carry traffic for the network. |
| **Portal** | This manual's name for the Parlons Cloud Android app. |
| **Seed / 24 words** | The secret phrase that is the identity and a Minima wallet. |
| **Signing key uses** | How many one-time signatures a wallet key has used. Raise-only. |
| **Single-holder rule** | One identity runs in one place at a time. |
| **Tenant** | One account folder inside a multi-account host. |
| **Unlock passphrase** | The passphrase (`PARLONS_UNLOCK`) that decrypts tenants' `seed.enc` files. |
| **Watch-only wallet** | A wallet view of an address whose keys are elsewhere; it can show a balance but never spend. |
