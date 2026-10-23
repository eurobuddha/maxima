# Parlons! user manual

Private chat with nobody in the middle.

This manual covers:

- **Parlons! for Android**, version **0.6.145**. This is the full app. Your identity lives on the phone.
- **Parlons Desktop**, version **1.5.115**, for Mac, Windows and Linux. This is the full app for a computer. It installs as **MaximaNode**.
- **Parlons Cloud** for Android (version **0.2.90**) and iPhone, briefly. These pair with an always-on Parlons account. The [Parlons Cloud manual](parlons-cloud-portal.md) covers the Portal in depth, and the [Parlons Node manual](parlons-node.md) covers running an account server.

All downloads are on the releases page: <https://github.com/eurobuddha/maxima/releases>

Every task in this manual has numbered steps. Labels in **bold** are the exact words you see on screen in English. Where Android and Desktop differ, each has its own steps.

---

## Contents

1. [What Parlons is](#1-what-parlons-is)
2. [How it works](#2-how-it-works)
3. [Which app do I need?](#3-which-app-do-i-need)
4. [Install](#4-install)
5. [First run: your identity](#5-first-run-your-identity)
6. [A tour of the app](#6-a-tour-of-the-app)
7. [Your address](#7-your-address)
8. [Adding people](#8-adding-people)
9. [Chats](#9-chats)
10. [Groups](#10-groups)
11. [Photos, voice notes and the photo gallery](#11-photos-voice-notes-and-the-photo-gallery)
12. [Private files](#12-private-files)
13. [Voice and video calls](#13-voice-and-video-calls)
14. [Links in chats](#14-links-in-chats)
15. [Payments: Minima and MxUSD](#15-payments-minima-and-mxusd)
16. [Languages](#16-languages)
17. [Notifications](#17-notifications)
18. [Settings reference](#18-settings-reference)
19. [The Network tab: relays and helping the network](#19-the-network-tab-relays-and-helping-the-network)
20. [Backups and moving to a new device](#20-backups-and-moving-to-a-new-device)
21. [One account on every device: Parlons Cloud, your own account, hosted accounts](#21-one-account-on-every-device-parlons-cloud-your-own-account-hosted-accounts)
22. [Security and privacy](#22-security-and-privacy)
23. [Troubleshooting](#23-troubleshooting)
24. [Glossary](#24-glossary)

---

## 1. What Parlons is

Parlons is private chat with no company in the middle.

- There is **no sign-up**. No phone number, no email, no account with anyone.
- Your messages are **sealed on your device** before they leave it. Only the person you wrote to can open them.
- Sealed messages are carried by **relays** that people like you run. Relays cannot read them.
- Your identity is **24 words**. The same words are also a **Minima wallet**. Whoever holds the words is you.

What you can do with it:

- One-to-one chats and groups of up to 12 people.
- Photos, voice notes, and shared contacts.
- Private files up to 512 MB, sent encrypted, straight between the people in the chat.
- Voice and video calls (Android).
- Send Minima or **MxUSD**, the Minima dollar token, inside a chat.
- Use the app in English, Simplified Chinese, Russian or Ukrainian.

Parlons is open source and community-run. There is no help desk, because there is no company account to look up. This manual, the in-app "What is this?" explanations, and the issue tracker are the support.

---

## 2. How it works

You do not need to understand this to use Parlons. It helps when something goes wrong.

### Your device has no fixed address, so it borrows one

A phone on Wi-Fi or mobile data cannot normally accept incoming connections. So Parlons connects **out** to a few relays (the app also calls them **hosts**). Each relay holds that connection open. Anything addressed to you comes back down it.

- **Connected** means at least one relay is holding a connection for you.
- **Not connected** means nobody can reach you right now. You can still write. Messages go out when a relay comes back.

Parlons attaches to **several relays at once**. If one goes down, the others still carry you.

### Messages are sealed end to end

Every message is encrypted on the sender's device with the recipient's key and signed by the sender. A relay sees a sealed blob and a routing key. It can tell that someone is sending to a key. It can never tell what.

### Messages to someone who is offline wait for them

If the person you write to is offline, the sealed message is held in a **mailbox** until they come back. The app also keeps your own unsent messages in an **outbox** and retries them.

### The ticks tell you the truth

| Mark | Meaning |
|---|---|
| ⋯ (Sending…) | Queued. Not handed to a relay yet. |
| ✓ one grey tick (Sent, relay took it) | A relay accepted the message. |
| ✓✓ two grey ticks (Delivered) | The other person's own device confirmed it has the message. In a group, every current member has it. |
| ✓✓ two blue ticks (Read) | They read it. Only shown if they turned on read receipts. |
| ✗ (Failed to send) | No relay would take it. It stays in your outbox and is retried. |

Two ticks come from the recipient's device itself, not from a relay. That is why you can trust them.

---

## 3. Which app do I need?

Answer one question: do you want to chat on **this device**, or have **one account on every device**?

| You want | Use | Notes |
|---|---|---|
| Chat on an Android phone | **Parlons!** (Android app) | Your identity lives on the phone. Android 9 or newer. |
| Chat on a computer | **Parlons Desktop** | Mac, Windows, Linux. Your identity lives on the computer. Brings its own Java. |
| The same chats on phone and laptop, or an iPhone | A **Parlons account** plus **Parlons Cloud** on each device | The account runs on a machine that stays on. See [section 21](#21-one-account-on-every-device-parlons-cloud-your-own-account-hosted-accounts). |

> **Important:** the Android app and Parlons Desktop each hold their **own** identity. If you install both and create an identity on each, you get two different addresses. To use one identity in two places, use a Parlons account. Never restore the same 24 words on two devices that run at the same time.

### What each app can do

| Feature | Android app | Parlons Desktop |
|---|---|---|
| Chats and groups | Yes | Yes |
| Photos | Yes, including the camera | Yes, including paste from the clipboard |
| Voice notes | Yes (up to 60 seconds) | Yes |
| Private files (up to 512 MB) | Yes | Yes |
| Voice and video calls | Yes | No. Incoming calls are shown and declined politely. Answer on your phone. |
| Send Minima or MxUSD | Yes | Yes |
| Wallet tab | Yes | Yes |
| Scan a QR code | Yes | No camera. Paste addresses instead. |
| Encrypted identity backup file | Yes | No. Keep your 24 words. |
| App lock (fingerprint or PIN) | Yes | No |
| Text size shortcuts | No | Yes |

Parlons Cloud for Android can also place and answer calls and send payments for a paired account. Parlons for iPhone has no calls and no payment sending yet.

---

## 4. Install

### 4.1 Android

You need an Android phone running **Android 9 or newer**, and a pen and paper.

Parlons is **not in the Play Store**. It is a direct download, signed by its authors.

1. On the phone, open the releases page: <https://github.com/eurobuddha/maxima/releases>
2. Find the Parlons Android release **0.6.145** and tap its `.apk` file to download it.
3. Android asks whether to allow installs from your browser. Allow it for this install. You can turn it off again afterwards in Settings, Apps, Special app access, Install unknown apps.
4. Tap **Install**, then **Open**.

If you already use the PandaApps store on your phone, Parlons! is listed there too and updates through it.

**Updating.** Install the new APK over the old one. Your identity, contacts and chats stay.

### 4.2 Mac

1. Download the Parlons Desktop **1.5.115** disk image (`.dmg`) from the releases page. It is signed and notarized, so macOS opens it without warnings.
2. Open the disk image. Drag **MaximaNode** into Applications.
3. Open it from Applications. It brings its own Java. There is nothing else to install.

Requires an Apple silicon Mac (M1 or later) with macOS 13 or newer. The disk image is built for arm64 only.

**Updating.** Install the new disk image over the old app. Your data in `~/.maxima` stays.

### 4.3 Windows

1. Download the Parlons Desktop **1.5.115** installer (`.msi`) from the releases page.
2. Run it. Windows may show a blue **Windows protected your PC** box, because the installer is not yet signed with a Microsoft certificate. Click **More info**, then **Run anyway**.
3. Choose a folder. The default is fine. It installs for your user only, needs no administrator, and adds Parlons to the Start menu. It brings its own Java.

Requires Windows 10 or 11, 64-bit.

**Updating.** Run the new installer over the old one. Your data stays.

### 4.4 Linux

Parlons Desktop is a 64-bit x86 package for Debian, Ubuntu and their relatives. It brings its own Java.

1. Download the Parlons Desktop **1.5.115** `.deb` package from the releases page.
2. In a terminal, in the folder where you saved it, run:

   ```
   sudo apt install ./maximanode_1.5.115_amd64.deb
   ```

3. Open **MaximaNode** from your applications menu. The launcher is `/opt/maximanode/bin/MaximaNode`.

There is no ARM build of the desktop app. On a Raspberry Pi, run a Parlons account instead ([section 21](#21-one-account-on-every-device-parlons-cloud-your-own-account-hosted-accounts)).

**Updating.** Install the new package over the old one. Your data in `~/.maxima` stays.

### 4.5 iPhone

The iPhone app is **Parlons Cloud for iPhone**. It does not hold an identity of its own. It pairs with a Parlons account. Apple does not let an app stay reachable in the background, so this is the only way an iPhone can receive messages while the app is closed.

The iPhone app is **not available yet**: it is not in the App Store, and there is no test build to download. It will need iOS 17 or newer. Until then, use Parlons on Android or a computer. See [section 21](#21-one-account-on-every-device-parlons-cloud-your-own-account-hosted-accounts) for how pairing will work.

---

## 5. First run: your identity

### 5.1 Android

1. **Choose a language.** The first time you open Parlons, a window titled **Language · 语言 · Язык · Мова** appears. Tap your language. You can change it later.
2. The welcome screen says **Parlons!** and offers:
   - **Create new identity**
   - **Restore from seed phrase**
   - **Change language**

#### Create a new identity

1. Tap **Create new identity**.
2. A window titled **Write these 24 words down** appears. The words are numbered. Screenshots are blocked on this screen.
3. Write all 24 words on paper, **in order**, with their numbers.
4. Check every word against the screen.
5. Tap **I've saved them** only when you have.

There is a **Copy** button. It puts the words on the clipboard and clears it after 60 seconds. Avoid it if you can. Other apps can read the clipboard, and a clipboard manager may keep a copy.

> This is the **only** way to recover your identity and any funds. Keep the paper offline and private. Anyone with these words is you.

#### Restore an identity you already have

Use this only if the device that held those words is **no longer running** Parlons. One identity must live in one place at a time.

1. Tap **Restore from seed phrase**.
2. Type or paste your phrase in **Enter your seed phrase**. Screenshots are blocked on this screen.
3. If your phrase came from Minima's "anyphrase" mode (a custom phrase, not 24 standard words), tick **Custom phrase (Minima anyphrase)**. Then type it **exactly**, with every capital letter and space as it was.
4. Tap **Restore**.
5. You see **Your identity is restored on this phone.** Tap **Start**.

You may also see one of these notes:

- **"Note: no BIP39 checksum — normal for a phrase from a Minima node. Restored anyway."** This is normal for a phrase that came from a Minima node.
- **"Custom phrase restored exactly as entered."** You used custom phrase mode.

The restore window also warns about **key uses**. Your wallet signs with one-time keys. The words alone do not carry the count of keys already used. If this phrase has ever signed transactions on another wallet or node, do not send funds from Parlons until you have checked. A full encrypted backup ([section 20](#20-backups-and-moving-to-a-new-device)) carries the real count.

> A seed phrase brings back your **identity** only. It does not bring back old chats or contacts. Your contacts can still reach you, because your address is the same.

#### After the first screen

1. Android asks to send you notifications. **Allow it.** Without it you will not see messages arriving.
2. Open **Settings**: tap the **three dots** (top right), then **Settings**. Under **Node & power**, check **Battery exemption**. If it says **not set**, tap **Fix battery settings** and allow Parlons to run without restrictions. Android kills background apps, and for a chat app that means missed messages, especially overnight.
3. In **Settings**, under **Your name**, type the name your contacts should see and tap **Set**. Names are up to 32 characters.

### 5.2 Parlons Desktop

1. Open **MaximaNode**.
2. The app creates your identity and shows a window titled **Back up your Maxima seed**, with the message "Your Maxima node created a new identity." and your 24 words.
3. Write them down now, in order.
4. The words are also saved, readable only by your user account, in a file:

   | System | Seed file |
   |---|---|
   | Mac | `~/.maxima/seed.txt` |
   | Linux | `~/.maxima/seed.txt` |
   | Windows | `C:\Users\<you>\.maxima\seed.txt` |

5. You land on the **Chats** tab.
6. Give yourself a name. Click the **three dots** button (top right). It opens **Settings**. Under **Your name**, type your name in **Display name** and click **Set**.

The backup window appears only on the very first launch. If you missed it, see [section 18.2](#182-desktop-settings) to show the words again.

---

## 6. A tour of the app

### 6.1 Android

The top bar shows **Parlons!**, the version, and a status pill. The pill shows how many relays (hosts) you are connected to, or **offline**.

Top-bar buttons:

| Button | What it does |
|---|---|
| Search (magnifier) | Search messages, contacts and groups. |
| Theme | Cycles System, Light and Dark. A short note shows the new choice, for example "Theme: Dark". |
| Three dots | Menu: **New group**, **Scan QR**, **Search**, **Settings**. |

Four tabs show under the top bar. Tap a tab, or swipe sideways:

| Tab | What is on it |
|---|---|
| **Chats** | Your conversations, newest first. |
| **Contacts** | Your address card, your contacts, and **+ Add**. |
| **Wallet** | Your Minima wallet: balance, tokens, send and receive, history. |
| **Network** | Connection status, relays, help-the-network, event log. |

**Settings** has no tab of its own. It holds language, name, privacy, appearance, power, keys and backup.

#### How to open Settings (Android)

1. Tap the **three dots** at the top right.
2. Tap **Settings**.

You can also swipe left once more from the **Network** tab.

#### How to change the theme (Android)

1. Tap the **Theme** button in the top bar.
2. Each tap moves to the next choice: System, Light, Dark.

Or open **Settings**, go to **Appearance**, and tap **System**, **Light** or **Dark**.

Many cards have a **What is this?** link, a **?** or an info button. It opens a short, plain explanation. Use them.

### 6.2 Parlons Desktop

The window has four tabs: **Chats**, **Contacts**, **Wallet**, **Network**. The top bar has a **Theme** button, a **Search** button and a **three dots** button. The bottom says "Powered by Maxima".

| Button | What it does |
|---|---|
| Theme (sun or moon) | Switches between Light and Dark. |
| Search (magnifier) | Jumps to the **Chats** tab and puts the cursor in **Search chats**. |
| Three dots | Opens **Settings**. |

The app also puts an icon in the menu bar (Mac) or system tray (Windows, Linux). Its menu has:

- **Copy my public address**
- **Re-check reachability**
- **Open data folder**
- **Change language**
- **Quit**

Only one copy runs at a time. If you open it twice you see **"Maxima is already running."**

#### How to open Settings (Desktop)

1. Click the **three dots** button at the top right of the window.
2. The **Settings** page opens.

#### How to change the theme (Desktop)

1. Click the **Theme** button (sun or moon) in the top bar. It switches between Light and Dark.
2. To follow your computer's own setting instead, open **Settings**, go to **Appearance**, and click **System**.

#### How to change the text size (Desktop)

1. Press **Ctrl +** to make text bigger, or **Ctrl −** to make it smaller. On a Mac, **Command +** and **Command −** work too.
2. Each press moves one step. The range is 80% to 150%.
3. Press **Ctrl 0** (or **Command 0**) to go back to 100%.

The size is remembered the next time you open the app.

#### How to use the tray menu (Desktop)

1. Click the Parlons icon in the menu bar (Mac) or the system tray (Windows, Linux).
2. Choose an item:
   - **Copy my public address** puts your full address on the clipboard.
   - **Re-check reachability** tests again whether the internet can reach this computer.
   - **Open data folder** opens the `.maxima` folder.
   - **Change language** opens the language list.
   - **Quit** closes the app completely.

---

## 7. Your address

Your address is how people reach you. It is a public key, not a phone number.

### The two forms

| Form | Looks like | Changes? |
|---|---|---|
| **Current address** | `Mx<your key>@<host>:<port>` | The `Mx` part never changes. The part after `@` is the relay carrying you right now, and it changes when your relays change. Your contacts are told automatically. |
| **Permanent address** | `MAX#<key>#<location service>` | Never changes, even when you move between relays. Share this one when you have it. |

Both are long. They are always shown in full, and you should always copy them in full. A shortened address cannot be used.

### Show and share your address (Android)

1. Open the **Contacts** tab. The card at the top says **Your Parlons address**.
2. Tap **Share my address**.
3. A QR code appears, with your full address underneath. Tap the address to copy it.
4. Tap **Share…** to send it by any other app, or let a friend scan the QR.

If you have a permanent address, the sheet says: "Your PERMANENT address — it survives every host move." Otherwise it says how many relays you are reachable through.

If you see **"Couldn't resolve a shareable IP address."**, check your connection and open the sheet again.

### Show and share your address (Desktop)

1. Open the **Contacts** tab. The card says **Your Maxima address**.
2. Click **Share my address**. A window opens with a QR code for a phone to scan.
3. Under the QR, each address is shown in full. With a permanent address you see **permanent MAX# address** and **current address**. Without one you see **maxima address**. Click an address to copy it. The tooltip says **Click to copy**.
4. Paste it into an email or any other message to the person.

If you see "Couldn't resolve a shareable address. Try again when online.", check your connection and click **Share my address** again.

### Get a permanent address

A permanent `MAX#` address comes from a **location service** (also called MLS). It answers one question: "what is the current address for this identity?"

#### How to get a permanent address (Android)

1. Open the **Network** tab.
2. Find the card **Location service · finds you when you move** and tap **Location settings**.
3. Choose one:
   - **Use the eurobuddha pool (register)**. You see "Registered — you have a permanent MAX# address".
   - **Make this my permanent address**, to pin the location service your relay offers you.
   - Enter a location service yourself (it must be in the form `Mx<key>@<host>:<port>`) and tap **Pin this MLS**.
   - **Use the host's directory** goes back to the default.

#### How to get a permanent address (Desktop)

1. Open the **Network** tab.
2. Find the card **Location service · finds you when you move**. It shows your **location address** and its **Mode**: **static (pinned)** or **rotating (host-assigned)**.
3. Click **Location settings**.
4. Choose one:
   - **Use the eurobuddha pool (register)**. You see "Registered — permanent MAX# address active".
   - Enter a location service in the form `Mx<key>@<host>:<port>` and click **Pin this MLS**.
   - **Use the host's directory** goes back to the default.

Your permanent address then shows as **permanent MAX# address**. Click it to copy it in full.

---

## 8. Adding people

Adding someone is a **handshake**. You introduce yourself to their address. They appear in your list when their device answers. If their device is off, the introduction waits.

### 8.1 Android: scan or paste

1. Open the **Contacts** tab and tap **+ Add**.
2. Either:
   - Tap **Scan their QR** and point the camera at their screen. Allow the camera the first time. Or
   - Paste their address in the field (it starts with `Mx` or `MAX#`).
3. Tap **Introduce myself**.
4. You see **"Introduction sent — waiting for them to reply"**.

They appear in your list the moment their device answers.

You can also tap the **three dots**, then **Scan QR**. A scanned address goes to the Contacts tab.

### 8.2 Desktop: paste

There is no camera on the desktop. People scan you; you paste them.

1. Open the **Contacts** tab and click **Add contact**.
2. Paste their address (`Mx<key>@<host>:<port>` or `MAX#<key>#<location service>` permanent form).
3. Click **Introduce myself**.
4. You see "Introduction sent. They'll appear once they reply."

### 8.3 Share a contact with someone else

You can pass one contact's address to another contact as a **contact card**.

#### How to send a contact card (Android)

1. Open the **Contacts** tab and tap the person you want to share.
2. Tap **Send to a contact…**. (**Share…** next to it sends the address through another app instead.)
3. In **Send {name} to…**, tap who should receive it.
4. You see "Contact sent to {name}".

#### How to send a contact card (Desktop)

1. Open the **Contacts** tab and click the person you want to share.
2. Click **Send to a contact…**.
3. Click who should receive it.

To copy the address yourself instead, click **Copy share address** in the same window.

#### How to add someone from a contact card (Android)

1. In the chat, tap the card. It says **Shared contact** and **Tap to add this contact**.
2. A window asks "Add {name}?" and shows their full address.
3. Tap **Add contact** to send them an introduction. Or tap **Copy address** to copy it in full.
4. You see "Introduction sent - they appear in Contacts once they answer".

### 8.4 A contact's details

Tap (Android) or click (Desktop) a contact in the **Contacts** tab. You see:

- **Message**, to open the chat.
- **Parlons address** (Desktop: **maxima address**), and each host they are reachable through (**Host 1**, **Host 2** …). Tap to copy.
- **Share address (permanent)**, if they have one.
- **Share…** (Android) or **Copy share address** (Desktop), and **Send to a contact…**
- **Payment address (MINIMA)**, once they have shared a wallet address.
- **Software**, and **Last seen** (or **Online now**).
- **Remove contact**.

#### How to remove a contact (Android)

1. Open the **Contacts** tab and tap the person.
2. Scroll down and tap **Remove contact**.
3. A window asks "Remove {name}?". Tap **Remove**.

#### How to remove a contact (Desktop)

1. Open the **Contacts** tab and click the person.
2. Click **Remove contact**. The contact is removed at once. There is no second question.

Removing someone only removes them from **your** list. They keep your address and can still write to you.

#### How to find a contact

1. Open the **Contacts** tab.
2. Type part of a name in the search field (**Search contacts** on Android, **Search** on Desktop).
3. The list shows only the people who match.

### 8.5 Status words next to contacts

| Word | Meaning |
|---|---|
| online / Online now | Their device is reachable now. |
| last seen … | When their device was last heard from. |
| not reached yet | You sent an introduction; they have not answered. |
| no address yet | There is no known address for them. |
| not in your contacts | You are in a chat with a key that is not in your list. |
| core / classic | A label: an always-on core node, or someone on classic Maxima. |

People on classic Maxima can chat with you. They cannot take calls or play voice notes.

---

## 9. Chats

### 9.1 Start a chat

#### How to start a chat (Android)

1. Open the **Contacts** tab.
2. Tap the person.
3. Tap **Message**.

To go back to a chat you already have, open the **Chats** tab and tap it.

#### How to start a chat (Desktop)

1. Open the **Chats** tab.
2. Click the round **New chat** button.
3. Click the person in the list.

Or open the **Contacts** tab, click the person, and click **Message**.

A new chat says: "This is the start of your conversation with {name}. Messages are end-to-end encrypted - only the two of you can read them."

#### How to send a message

1. Open the chat.
2. Type in the **Message** field.
3. Tap (or click) **Send**. On Desktop you can also press **Enter**.

#### How to add an emoji

1. In the chat, tap (or click) the **Emoji** button.
2. Pick a group: **Smileys**, **Gestures**, **Hearts**, **Animals & nature**, **Food & drink** or **Objects & symbols**.
3. Tap an emoji. It goes into the message field. Send as usual.

### 9.2 The message bar (Android)

From left to right:

| Control | What it does |
|---|---|
| Emoji | Opens the emoji panel. |
| Attach | Opens a menu: **Photo library**, **Private file**, **File transfers**, **Voice note**, and **Send payment** (one-to-one chats only). |
| Camera | Takes a photo and sends it with an optional caption. |
| Send / microphone | With text in the field, it sends. With an empty field, it shows a microphone and records a voice note. |

The chat header has **Video call**, **Voice call**, a theme button and an **info** button. In a group, info shows the members.

#### How to see a contact's details from a chat

1. Open the chat.
2. Android: tap the **info** button in the header. Desktop: click the name at the top of the conversation.
3. You see the contact's name, full key and addresses. In a group you see the **Members**, with **(admin)** next to each admin.
4. On Android, tap **What do the ticks mean?** for a short explanation of the ticks. Tap **Close** when done.

### 9.3 Long-press a message (Android)

#### How to copy, save or check a message (Android)

1. Long-press the message bubble.
2. Tap one of the choices. You only see the ones that fit that message:
   - **Copy message**, **Copy caption** or **Copy contact address**.
   - **Copy transaction id**, on a payment. The full id is copied. You see "Transaction id copied".
   - **Save photo** or **Share photo**, on a photo. A saved photo shows "Saved to Photos".
   - **Info**: who sent it, when, and its state (Sending…, Sent (relay took it), Delivered, Read, Failed to send). On a payment, Info also shows the full **Transaction id** and a **Copy tx id** button.

### 9.4 Right-click a message (Desktop)

#### How to copy, open or check a message (Desktop)

1. Right-click the message.
2. Choose one of the items. You only see the ones that fit that message:
   - **Copy message**, **Copy caption** or **Copy contact address**.
   - **Copy transaction id**, on a payment. The full id is copied.
   - **Open image**, on a photo.
   - **Info**. It shows who sent it, **Sent:** and **Arrived:** times, and its status. A message that arrived late shows "sent … · arrived …".

### 9.5 Manage a chat

#### How to mark a chat as read

1. Open the **Chats** tab.
2. Long-press the chat (Android) or right-click it (Desktop).
3. Choose **Mark as read**.

#### How to clear a chat

**Clear messages** deletes every message in that chat **on this device only**. It does not unsend anything and does not leave a group. It cannot be undone.

1. Open the **Chats** tab.
2. Long-press the chat (Android) or right-click it (Desktop).
3. Choose **Clear messages**.
4. A window asks **Clear this chat?**. Confirm with **Clear** (Android) or **OK** (Desktop).

### 9.6 Search

#### How to search (Android)

1. Tap the magnifier in the top bar. Or tap the **three dots**, then **Search**.
2. Type in **Search messages…**.
3. Results are grouped under **Contacts & groups** and **Messages**. Tap a result to open it.

#### How to search (Desktop)

1. Click the magnifier in the top bar. The app jumps to the **Chats** tab.
2. Type in **Search chats**. The chat list shows only the matches.
3. Click a result to open it.

### 9.7 Read receipts

Read receipts are **off** by default. Delivery (two grey ticks) is always on. That is the transport doing its job.

#### How to turn read receipts on or off

1. Open **Settings** (Android: **three dots**, then **Settings**. Desktop: the **three dots** button).
2. Under **Privacy**, find **Read receipts**.
3. Turn the switch on or off. When on, the line under it says "Contacts are told when you read their message". When off it says "Contacts see delivery only, never when you read".

The setting applies to everyone. There is no per-contact setting.

---

## 10. Groups

### How groups work

A group message is sealed **separately** to every member and sent to each of them. There is **no shared group key**. This means:

- Removing someone removes them immediately. There is no key they still hold.
- Breaking one member's keys does not open the group.
- Only an **admin** can change who is in the group.
- Only current members can post. This is checked on arrival, not just in the app.

### Limits

| Limit | Value |
|---|---|
| Members | **Up to 12, including you.** |
| Calls | Group calls are not supported. Calls are one-to-one only. |
| Payments | Payments are one-to-one only. |
| Private files | Allowed. Only current members can take part. |

If you pick too many people you see "A group holds at most 12 people including you — pick fewer".

### Create a group (Android)

1. Tap the three dots in the top bar, then **New group**.
2. Under **Who is in the group?**, tick the people. Tap **Next**.
3. Under **Name the group**, type a name. Tap **Create**.

You need at least one contact first. Tap **How groups work** in the picker for the rules above.

### Create a group (Desktop)

1. On the **Chats** tab, click the round **New chat** button.
2. Click **New group** at the top of the list.
3. Type a name in **Group name**.
4. Under **Who is in the group?**, tick at least one person.
5. Click **Create**.

Click **How groups work** in the same window for the rules above.

### Change members

Only an admin can do this. You are the admin of a group you create.

#### How to change members (Android)

1. Open the group and tap the **info** button.
2. Tap **Edit members**. You only see it if you are an admin.
3. In **Members**, tick or untick people.
4. Tap **Save**.

#### How to change members (Desktop)

1. Open the group and click its name at the top of the conversation.
2. Click **Edit members**. You only see it if you are an admin.
3. In **Members**, tick or untick people.
4. Click **Save**.

You always stay in a group you are admin of. If you tick too many people you see the 12-person limit message and nothing changes.

There is no "leave group" button. To stop seeing a group, use **Clear messages** ([section 9.5](#95-manage-a-chat)), or ask an admin to remove you.

---

## 11. Photos, voice notes and the photo gallery

### Size limit

A photo, voice note or other media in a message can be **at most 16 MB**. A larger file shows "Too big: 16 MB is the most a message can carry". For bigger files, use a **private file** ([section 12](#12-private-files)).

### Send a photo (Android)

1. In a chat, tap **Attach**, then **Photo library**. Or tap the **camera** button to take a new photo.
2. Pick the photo, or take it.
3. Under **Send photo**, add an optional caption in **Add a caption…**.
4. Tap **Send**. You see "Sending photo…".

### Send a photo (Desktop)

#### How to send a photo from a file (Desktop)

1. In a chat, click **Attach**, then **Photo…**.
2. Pick an image file.
3. In **Send a photo**, add an optional **Caption (optional)**.
4. Send it.

#### How to paste a photo (Desktop)

1. Copy a screenshot or image, or an image file in your file manager.
2. Click in the conversation.
3. Press **Ctrl V** (**Command V** on a Mac). A preview appears.
4. Add a caption if you like.
5. Choose **Send**, or **Cancel**.

Pasted images are limited to 32 MB on the clipboard before they are prepared for sending. Ordinary text copy and paste work as usual.

### The photo gallery

Tapping any photo in a chat opens a gallery of every photo in that conversation, sent and received, in chat order.

#### How to browse, save and share photos (Android)

1. In a chat, tap a photo. The gallery opens on it.
2. Swipe sideways to move to newer or older photos.
3. Pinch to zoom, drag to pan, double-tap to reset.
4. Tap **Save** to save it to Photos, or **Share** to send it through another app.
5. If a photo shows "Photo unavailable · tap to retry", tap it.
6. Use the back button or the close button to leave the gallery.

#### How to browse and save photos (Desktop)

1. In a chat, click a photo. The viewer opens on it.
2. Press the left and right arrow keys, or drag sideways, to move between photos.
3. Use the mouse wheel to zoom, drag to pan, and double-click to reset.
4. Click **Save image** to save a copy.
5. Press **Escape** to close the viewer.

The gallery is a snapshot. Close and reopen it to include photos that arrive while it is open.

### Voice notes

#### How to record a voice note (Android)

1. In a chat with an empty message field, tap the **microphone** button. Or tap **Attach**, then **Voice note**.
2. Allow the microphone the first time.
3. Speak. A timer and waveform show the recording. The limit is **60 seconds**.
4. Tap **Send**, or **Cancel**.

If the recording is under a second you see "Too short - hold on a moment longer".

#### How to record a voice note (Desktop)

1. In a chat, click **Attach**, then **Voice note**.
2. Allow the microphone if your computer asks. The window shows **●  Recording** and a waveform.
3. Speak. The limit is **60 seconds**. At the limit, recording stops and the note is sent.
4. Click **Send**, or **Cancel**.

If there is no microphone you see "No microphone available."

#### How to play a voice note

1. Tap (or click) **Play** on the voice note.
2. Tap it again to stop.

Voice notes need Parlons on both ends; a contact on classic Maxima cannot receive them.

---

## 12. Private files

Private files let you send a file of up to **512 MB** inside a chat. The file is encrypted with a fresh key and sent in encrypted pieces **directly between the people in the conversation**. There is no central server and no public index.

### Send a private file

#### How to send a private file (Android)

1. In a chat, tap **Attach**, then **Private file**.
2. Pick a file (up to 512 MB).
3. A window titled **Sending private file** shows "Preparing…" while the file is encrypted. Tap **Hide** to close the window; the work carries on.

#### How to send a private file (Desktop)

1. In a chat, click **Attach**, then **Private file…**.
2. In **Send private file (up to 512 MB)**, pick the file.

The chat shows a file card with the name and size.

### Receive a private file

Receiving does **not** start by itself. You choose.

#### How to receive a private file

1. Tap (or click) the file card. It says "Tap Download to receive this encrypted file. Keep a participant online until it finishes."
2. Tap **Download**.
3. Wait. The status moves through **Connecting**, **Downloading** and **Verifying**.
4. When it says **Complete** or **Ready · sharing**, tap **Save file…**.
5. Choose where to save it. You see "File saved".

### Transfer controls

#### How to pause, resume, save or remove a transfer

1. In the chat, tap (or click) **Attach**, then **File transfers**. You see every transfer this device keeps. (Or tap the file card itself.)
2. Tap (or click) a transfer.
3. Use the control you need:

| Control | What it does |
|---|---|
| **Pause sharing** / **Resume** | Stop or restart this transfer. |
| **Save file…** | Save the finished file. |
| **Remove local transfer** | Delete this device's transfer and stop sharing it. Copies you saved are kept. It asks first. Confirm it (on Android the button is **Remove**). |

If there are none you see "No local file transfers".

While you are downloading, your device may also **upload encrypted pieces** to the other people in the chat. That helps everyone finish faster.

After a restart, transfers that were active **resume by themselves**. Transfers you paused stay paused.

### Status words

| Status | Meaning |
|---|---|
| Preparing | The file is being encrypted. |
| Waiting for peers | Nobody who has the file is reachable yet. |
| Connecting / Downloading / Verifying | In progress. |
| Ready · sharing | You have the file and are helping others get it. |
| Ready · paused | You have the file; sharing is paused. |
| Paused / Failed | Stopped. Tap **Resume** to try again. |

### Limits and conditions

| Limit | Value |
|---|---|
| One file | 512 MB |
| Stored transfer data on a device | 2 GB |
| Transfers kept | 32 |
| Transfers active at once | 4 |

- **At least one person in the chat must be reachable**, either directly from the internet ([section 19](#19-the-network-tab-relays-and-helping-the-network)) or on the same local network. If nobody is reachable, the transfer waits. There is no central server to fall back on.
- **Both sides need a version of Parlons that supports private files.** An older app shows "Private file · update Parlons to open".
- Once someone has the file and its key, you cannot take it back.
- People in the transfer can see each other's internet addresses. Private files are encrypted, not anonymous.

---

## 13. Voice and video calls

Calls work in the **Android app** and in **Parlons Cloud for Android**. Parlons Desktop does not carry call audio or video: it shows an incoming call ("Answer on your phone — the desktop app does not carry call audio/video yet.") and declines it politely so the caller is not left ringing. The **Video call** and **Voice call** buttons on Desktop only explain this. The iPhone app has no calls yet.

### Place a call (Android)

1. Open a **one-to-one** chat.
2. Tap **Voice call** or **Video call** in the chat header.
3. Allow the microphone (and the camera, for video) when asked.
4. The screen shows **Calling…**, then **Connecting…**, then the call.

During a call:

| Button | What it does |
|---|---|
| **Mute** / **Unmute** | Your microphone. |
| **Speaker** / **Earpiece** | Where the sound plays. |
| **Flip** | Switch between front and back camera (video). |
| **End** | Hang up. |

### Answer a call (Android)

An incoming call rings with your ringtone and shows **Incoming Parlons call** or **Incoming Parlons video call**. It can show over the lock screen. An unanswered call stops ringing after about 45 seconds.

1. Tap **Answer** to take the call, or **Decline** to refuse it.
2. Allow the microphone (and camera, for video) if asked.
3. Use the in-call buttons as above. Tap **End** to hang up.

#### How to make calls ring on the lock screen (Android)

1. Open **Settings** (**three dots**, then **Settings**).
2. Under **Privacy**, find **Incoming calls**.
3. If it says "Allow full-screen alerts to show calls on the lock screen", tap the row (it shows **ALLOW**) and allow it in the Android screen that opens.
4. Once allowed, the row says "Ringtone, vibration and call alerts" and shows **SETTINGS**. Tap it to choose ringtone and vibration.

### Call limits

- Calls are **one-to-one**. "Group calls are not supported yet".
- Both people need Parlons. "Calls need Parlons on both ends".
- Calls set up a **direct** connection between the two devices. Parlons does not route call audio through a relay server. Some networks (some mobile carriers, some strict routers) block direct connections. Then the call shows **Could not connect** or **Call couldn't connect**. Try again with one or both people on Wi-Fi.

Call history appears in the chat, for example "Voice call · 2:35", **Missed call**, **No answer**, **Busy**, or "Answered on another device".

---

## 14. Links in chats

Web links in messages and photo captions are **clickable**. This includes `http://`, `https://`, `www.` and bare domain names (which open as HTTPS).

#### How to open a link

1. Tap (or click) the link in the message.
2. It opens in your normal browser.

#### How to copy or share a link (Android)

1. Long-press the link.
2. Tap **Open link**, **Copy link** or **Share link**.

#### How to copy or share a link (Desktop)

1. Right-click the link.
2. Choose **Open link**, **Copy link** or **Copy link to share**.
3. After **Copy link to share**, paste the link into the conversation or app you want to share it with.

Parlons never fetches link previews, and never runs HTML or formatting from a received message. Nothing is loaded until you tap.

---

## 15. Payments: Minima and MxUSD

Your 24 words are also a Minima wallet. Parlons can send two kinds of money:

- **Minima**, the native coin.
- **MxUSD**, the Minima dollar token. Its token id is:

  ```
  0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90
  ```

Payments are **real** and **cannot be undone**. Transactions are built and signed **on your device**. Only the signed transaction leaves it.

### 15.1 Pay someone in a chat (Android)

1. Open a **one-to-one** chat. Payments are not available in groups.
2. Tap **Attach**, then **Send payment**.
3. In **Pay {name}**, choose **Minima** or **MxUSD**.
4. Enter the amount in **Amount (MINIMA)** or **Amount (MxUSD)**, and an optional **Note (optional)**.
5. Tap **Send**. A confirmation says "Send {amount} {token}?" and "This signs and broadcasts a real transaction and cannot be undone."
6. Tap **Confirm**. You see "Signing payment…", then **Payment sent**.

The chat shows a payment bubble. Long-press it and tap **Copy transaction id** to copy the full id.

If you see "No wallet address yet — ask them to open this chat", the other person's app has not shared its wallet address with you yet. Ask them to open your chat.

### 15.2 Pay someone in a chat (Desktop)

1. Open a one-to-one chat with a contact. Click **Attach**, then **Send payment**.
2. The window says **To {name}**. Choose **Minima** or **MxUSD**.
3. Enter an **amount** and an optional **note (optional)**.
4. Click **Sign & send**.

The chat shows "↑ You sent" or "↓ You received". Right-click the payment and choose **Copy transaction id** to copy the full id.

If you see "No wallet address for this contact yet — they need to share one.", ask them to open your chat in their app.

### 15.3 The Wallet tab

The Wallet tab (Android and Desktop) has two views: **Balance** and **History**. Tap (or click) either name at the top to switch.

**Balance** shows:

- Your total balance and the amount **sendable now**. Coins that are still confirming are not sendable yet.
- **YOUR TOKENS** (Desktop: **Tokens**), including MxUSD.
- **Signatures used**: "{remaining} of {maximum} signatures left · each send spends one". See [section 15.4](#154-signature-uses-read-this-before-you-import-a-phrase).
- **Send** and **Receive**.

#### How to unlock coins that are still syncing (Android)

1. Open the **Wallet** tab.
2. If the line under the balance says "… sendable · tap Sync to unlock the rest", tap that line.
3. You see "Syncing coins…", then "Coins synced — … now spendable".

#### How to see a token's details

1. Open the **Wallet** tab, on **Balance**.
2. Tap (or click) a token under **YOUR TOKENS** (Desktop: **Tokens**).
3. You see its full **Token ID**, **Total supply** and **Coins**. Tap the Token ID to copy it.

#### How to receive Minima or MxUSD

1. Open the **Wallet** tab.
2. Tap (or click) **Receive**.
3. The **Receive MINIMA** window shows your full Minima address and a QR.
4. Tap (or click) the address to copy the complete string, or let the sender scan the QR.

Anyone can send Minima or MxUSD to this address.

#### How to send to an address (Android)

1. Open the **Wallet** tab and tap **Send**.
2. Enter the address in the field (it starts with `Mx` or `0x`), or tap the scan button to scan it.
3. Choose **Minima** or **MxUSD**.
4. Enter the amount.
5. Tap **Sign & send**. You see "Selecting coins…", "Signing on this device…", "Publishing via …", then "Sent — mining into a block".

#### How to send to an address (Desktop)

1. Open the **Wallet** tab and click **Send**.
2. Paste the address in **Mx… address**.
3. Choose **Minima** or **MxUSD**.
4. Enter the **amount**.
5. Click **Sign & send**. You see "Selecting coins…", then "Publishing via …".

"Mining into a block" is normal. The payment confirms when it is in a block.

#### How to look at your payment history

1. Open the **Wallet** tab and tap (or click) **History**.
2. Tap **All**, **Sent** or **Received** to filter the list.
3. Tap a payment. You see its **Direction**, **Amount**, **Token**, **When**, the full **To** or **From** address, and the full **Transaction ID**.

### 15.4 Signature uses: read this before you import a phrase

Minima wallets sign with one-time keys. Each key can sign a limited number of times. Parlons counts uses so it never reuses one. Reusing a one-time signing key can expose it and lose money.

- The counter **only goes up**.
- Signing reserves one use, even if the payment does not complete.
- A words-only restore cannot know the old count. An encrypted backup ([section 20](#20-backups-and-moving-to-a-new-device)) carries it.

#### How to raise the signature counter (Android)

Do this only if you know this seed has signed more times elsewhere.

1. Open the **Wallet** tab, on **Balance**.
2. Long-press the **Key uses** card (it says **Signatures used** and **Long-press to adjust**).
3. In **Set signatures used**, enter a number at least as high as the uses this seed has had anywhere.
4. Tap **Set**. You see "Set to …".

You cannot lower it. A smaller number shows "Ignored — can only raise above …". Tap **Cancel** to read **Why this matters** instead.

On Desktop, click the **?** next to **Signatures used** (**About signature uses**) for the same explanation. The Desktop wallet has no control to raise the counter.

### 15.5 Wallet settings (Android)

Open the **Wallet** tab, scroll to the bottom, and tap **Wallet settings**. The sheet says which seed the wallet uses now.

#### How to use your own wallet node (Android)

By default the wallet reads the chain through the Parlons fleet of Minima nodes, with automatic failover. You can point it at your own Parlons Node instead. Your seed never leaves the phone.

1. In **Wallet settings**, tap **Wallet node**.
2. Enter your node's endpoint in the first field (it looks like `https://your-node…/cmd`), and its **access token** in the second.
3. Tap **Save node**. You see "Wallet node saved".

To undo it, open **Wallet node** again and tap **Reset to the Parlons fleet**. See [the Parlons Node manual](parlons-node.md#14-the-wallet-gateway-and-its-token) for the endpoint and token.

#### How to import a different wallet phrase (Android)

This replaces the wallet's seed with another 12- or 24-word phrase. The address and funds change. Your chat identity does not change. Only import a phrase whose key has **not** signed on another wallet or node.

1. In **Wallet settings**, tap **Import a phrase**.
2. In **Import a wallet seed**, read the warning.
3. Type the phrase in **12 / 24-word seed phrase**.
4. Tap **Import**. You see "Importing wallet seed…".

#### How to go back to your Parlons seed (Android)

1. In **Wallet settings**, tap **Use Parlons seed**.
2. A window asks **Use the Parlons seed?**. Tap **Use Parlons seed**.
3. You see "Back to the Parlons seed…".

The imported wallet is not deleted. Import its phrase again to return to it.

### 15.6 Payment problems

| Message | What to do |
|---|---|
| Not enough confirmed funds. | Wait for incoming coins to confirm. On Android, tap the "tap Sync to unlock the rest" line under the balance. |
| Invalid Mx address. / bad checksum | Copy the address again, in full. |
| That's the burn address. | You entered an address that destroys coins. Check the address. |
| Payments are one-to-one for now | Open a one-to-one chat. |
| ⚠ unconfirmed — check the balance before paying again | The payment may still be confirming. Check the balance and history before you send again. |

---

## 16. Languages

Parlons speaks **English**, **Simplified Chinese (简体中文)**, **Russian (Русский)** and **Ukrainian (Українська)**. Every screen is translated, on Android, Desktop, Parlons Cloud and iPhone.

### Change the language

The first launch on Android asks you to choose. After that, use these steps.

#### How to change the language (Android)

1. Open **Settings** (**three dots**, then **Settings**).
2. The first card says "Choose the language for this device." Tap the button **English · 简体中文 · Русский · Українська**.
3. Tap your language. The app switches without restarting.

On the welcome screen, before you have an identity, tap **Change language** instead.

#### How to change the language (Desktop)

1. Open **Settings** (the **three dots** button).
2. Under **Language**, click **Change language**.
3. Click your language.

You can also choose **Change language** from the tray or menu-bar icon.

The language is a setting on this device only. Your contacts see your messages exactly as you typed them.

---

## 17. Notifications

### Android

Allow notifications when Parlons asks. On Android 13 and newer there are **no** chat notifications without this permission.

#### How to turn notifications back on (Android)

If you said no the first time:

1. Open the Android **Settings** app.
2. Go to **Apps**, then **Parlons!**, then **Notifications**. (The exact names vary a little by phone maker.)
3. Turn notifications on.

Parlons uses separate notification channels, which you can adjust on the same Android screen:

| Channel | Used for |
|---|---|
| Messages | New messages. |
| Messages (muted) | New messages when the Parlons sound is off. |
| Incoming calls | Ringing calls, with Answer and Decline. |
| A transport notification | Shows Parlons is running and how many relays are connected. It keeps the connection alive. |

#### How to turn the message sound on or off (Android)

1. Open **Settings** (**three dots**, then **Settings**).
2. Under **Privacy**, find **Message sound**.
3. Turn the switch on for "Pssst! when a message arrives". Turn it off for "Silent - notifications still appear and vibrate".

#### How to make notifications arrive on time (Android)

1. Open **Settings**.
2. Under **Node & power**, look at **Battery exemption**.
3. If it says **not set**, tap **Fix battery settings**.
4. In the Android window that opens, allow Parlons to run in the background. The row now says **granted**.
5. Open the **Network** tab and check that at least one host is connected.

### Desktop

Desktop shows a notification for a new message while the app runs.

#### How to turn the message sound on or off (Desktop)

1. Open **Settings** (the **three dots** button).
2. Under **Privacy**, find **Message sound**.
3. Turn it on for "Pssst! when a message arrives", or off for **Silent**.

---

## 18. Settings reference

### 18.1 Android settings

Open **Settings**: tap the **three dots** at the top right, then **Settings**.

| Section | Setting | What it does |
|---|---|---|
| (top card) | Language | Choose English, 简体中文, Русский or Українська. |
| Your name | Name, **Set** | What your contacts see. Changing it sends the update to everyone. |
| Your name | maxima publickey | Your full public key. Tap to copy. |
| Privacy | **Read receipts** | Off by default. When on, contacts see when you read their message. |
| Privacy | **Message sound** | "Pssst!" on new messages, or silent. |
| Privacy | **Incoming calls** | **ALLOW** full-screen call alerts; **SETTINGS** for ringtone and vibration. |
| Privacy | **Delivery receipts** | Always on. Shown for information. |
| Privacy | **App lock** | Require fingerprint or device PIN to open Parlons. Needs a screen lock on the phone. |
| Privacy | **Allow screen sharing** | On by default. Turn it off to hide Parlons from mirroring, casting and screenshots while App lock is on. |
| Appearance | System, Light, Dark | The look. Nothing about it leaves the phone. |
| Node & power | **Run as core node** | Only changes the label contacts see ("core" instead of phone). Use it on a phone you leave always on. |
| Node & power | **Classic engine** | Routes through classic Maxima instead of the built-in engine. Parlons restarts. Leave it off unless you know you need it. Private files need the built-in engine. |
| Node & power | **Battery exemption** | **Fix battery settings** keeps the connection alive. |
| Keys & security | **Show seed phrase** | Shows your 24 words after a warning. Screenshots blocked. |
| Keys & security | **Restore a seed phrase** | Replaces this phone's identity. See [section 20](#20-backups-and-moving-to-a-new-device). |
| Keys & security | **Back up identity…** | Writes an encrypted backup file. |
| Keys & security | **Restore from backup…** | Restores an encrypted backup file. |
| Apps using Maxima | Approve or revoke | Other apps on this phone that ask to use your Parlons identity as their transport. Only approve apps you trust. |
| About | Version | The app version. |

#### How to set your name (Android)

1. Open **Settings**.
2. Under **Your name**, type the name your contacts should see. Up to 32 characters.
3. Tap **Set**. You see "Name set to {name}". Your contacts are told.

#### How to copy your public key (Android)

1. Open **Settings**.
2. Under **Your name**, tap the **maxima publickey** field. The full key is copied. You see "maxima publickey copied".

#### How to turn on App lock (Android)

Your phone must have a screen lock (PIN, pattern, password or fingerprint) first. Otherwise the row says "Set a screen lock on this device to use this".

1. Open **Settings**.
2. Under **Privacy**, turn on **App lock**.
3. Android asks you to confirm it is you (**Turn on app lock**, "Confirm it's you"). Use your fingerprint or device PIN.
4. You see "App lock on". From now on Parlons asks for your fingerprint or PIN when you open it.

To turn it off, turn off the same switch and confirm it is you. You see "App lock off".

#### How to hide Parlons from screen sharing (Android)

1. Open **Settings**.
2. Under **Privacy**, turn off **Allow screen sharing**.
3. The row now says "Screen is hidden from mirroring/casting when App lock is on".

This only takes effect while **App lock** is on.

#### How to mark this phone as a core node (Android)

1. Open **Settings**.
2. Under **Node & power**, turn on **Run as core node**.
3. Contacts now see "core" next to you instead of an ordinary phone.

#### How to switch to the classic engine (Android)

Leave this off unless you know you need it. Private files need the built-in engine.

1. Open **Settings**.
2. Under **Node & power**, turn on **Classic engine**.
3. You see "Switching engine - Parlons restarts in a moment". Parlons restarts by itself.

Turn the switch off again to go back. Contacts added on the classic engine are carried across.

#### How to approve or revoke another app (Android)

Another app on the phone may ask to use your Parlons identity as its transport. It shows under **Apps using Maxima** with the word **pending**.

1. Open **Settings** and scroll to **Apps using Maxima**.
2. Tap the app that says "{app} wants access".
3. A window asks "Let {app} use Parlons?". Tap **Approve**, or **Deny**.

To take access away later:

1. Tap the app that says "Approved — tap to revoke".
2. A window asks "Revoke {app}?". Tap **Revoke**.

Tap the **?** next to **Apps using Maxima** for a plain explanation.

### 18.2 Desktop settings

Click the **three dots** button at the top right. It opens **Settings**.

| Section | Setting | What it does |
|---|---|---|
| Language | **Change language** | Choose the language. |
| Your name | Display name, **Set** | What your contacts see. Also shows your full maxima publickey. |
| Connected apps | **Connect minimaDocs** / **Revoke minimaDocs** | Lets the minimaDocs app manage contacts and exchange document invitations for this account. The connection link expires in two minutes and works once. |
| Privacy | **Read receipts** | As on Android. |
| Privacy | **Message sound** | On or Silent. |
| Privacy | **Delivery receipts** | Always on. |
| Appearance | System, Light, Dark | The look. |
| Node | **Run as core node** | Label only. |
| Node | **Classic engine** | As on Android. |
| Node | **Node link (KeetChat)** | Links a minimaCore Desktop node on this same computer, so KeetChat contacts work here. Needs its **RPC URL** and **RPC password**. |
| Keys & security | **Show seed phrase** | Shows your 24 words. **Copy (auto-clears in 60s)** is offered; writing them down is safer. |
| Keys & security | **Restore a seed phrase** | Replaces this computer's identity. Contacts and chats here are cleared. The app then closes; open it again. |
| About | Version | The app version. |

#### How to set your name (Desktop)

1. Open **Settings**.
2. Under **Your name**, type in **Display name**.
3. Click **Set**. Your contacts are told.

Your full **maxima publickey** shows just below. Click it to copy it.

#### How to connect minimaDocs (Desktop)

1. Open **Settings**.
2. Under **Connected apps**, click **Connect minimaDocs**.
3. A window titled **Connect minimaDocs · {name}** shows a **Connection link**.
4. Copy the link and paste it into minimaDocs, in **People**. The link expires in two minutes and works once.

#### How to disconnect minimaDocs (Desktop)

1. Open **Settings**.
2. Under **Connected apps**, click **Revoke minimaDocs**.
3. Confirm "Disconnect every minimaDocs workspace from this Parlons account?". Document access is unchanged.

#### How to link a minimaCore Desktop node for KeetChat (Desktop)

You need minimaCore Desktop 0.18.4 or newer running on this same computer.

1. Open **Settings**.
2. Under **Node**, find **Node link (KeetChat)**.
3. Enter the node's **RPC URL**, and its **RPC password (if set)**.
4. Click **Save & connect**.
5. The status changes to "Linked — KeetChat contacts available". Click **Clear** to remove the link.

---

## 19. The Network tab: relays and helping the network

You do not need to touch this tab. It is here so you can see what is happening and fix it when needed.

### 19.1 Connection (Android)

The top card shows **Reachable**, **Starting** or **Offline**, and **Hosts connected**: the relays carrying your traffic right now. "Independent routes to you. More is safer."

Other counters:

| Counter | Meaning |
|---|---|
| Your outbox | Your messages waiting for a relay to accept them. A number that never goes down means no relay is reachable. |
| Mailbox held for others | Encrypted messages this phone is storing for offline people. It cannot read them. |
| Services you answer | Lookups and receipts this phone answers for others. |

Tap any counter for a short explanation.

#### How to reconnect quickly (Android and Desktop)

1. Open the **Network** tab.
2. Tap (or click) **Auto-connect** in the top card. It says **Working…** while it runs.
3. The app tries the relays it knows, then tries to make you directly reachable. A note tells you how many relays you are connected through.

### 19.2 Relays (hosts)

Parlons ships with a built-in list of relays and also learns relays from the network.

Any public Maxima node works as a relay, including a classic Minima node with Maxima turned on.

#### How to add a relay you trust (Android)

1. Open the **Network** tab and find **Hosts · they relay your traffic**.
2. Type `host:port` in the **add relay  host:port** field, or paste the text of a relay QR.
3. Tap **Add & connect**.
4. A window asks **Add and connect?**. Tap **Connect**. The transport restarts. Your chats and wallet are unaffected.
5. After a few seconds a window says **Connected** (or **Reconnecting** if the relay has not accepted you yet). Tap **OK**.

To scan a relay's QR instead: tap **Manage**, then **Scan a relay QR**, and point the camera at it.

#### How to manage your relays (Android)

1. Open the **Network** tab. Under **Hosts · they relay your traffic**, tap **Manage**.
2. The **Manage hosts** sheet lists **Configured hosts**. Each shows "built-in" or "yours", and **Connected** or **Idle**.
3. Next to a relay, tap:
   - **Connect** to connect to an idle relay now.
   - **QR** to share it (see below).
   - **Remove** to drop it.
4. Tap **Reset to defaults** at the bottom to bring the shipped list back.

You must keep at least one relay. If you try to remove the last one you see "Keep at least one relay - add your own before dropping this one".

#### How to turn the built-in relays off or on (Android)

1. Open the **Network** tab and tap **Manage**.
2. Turn off **Use built-in relays**. Now only relays you added or that this phone remembers are used.
3. Turn it on again to use the shipped list plus yours.

If you have no relay of your own you see "Add a relay of your own first - the phone needs somewhere to start".

#### How to share a relay (Android)

1. Open the **Network** tab and tap **Manage**.
2. Tap **QR** next to the relay.
3. The **Share relay** sheet shows a QR. Anyone who scans it adds that relay to their relays. The full text is under it in **Share text**; tap to copy it.

#### How to add and manage relays (Desktop)

1. Open the **Network** tab. Find **Hosts · they relay your traffic**.
2. To add one, type `host:port` (or paste a relay QR text) in **add relay  host:port** and click **Add & connect**.
3. Each relay in the list shows "built-in", "yours" or "learned". Next to it, click **QR** to share it, **Connect** to connect now, or **Remove** to drop it.
4. Use the switch for built-in relays. It reads "Built-in relays: on (the list shipped with the app, plus yours)" or "Built-in relays: off (only relays you added or this machine remembers)".
5. Click **Reset to defaults** to bring the shipped list back.

### 19.3 Help the network (Android)

Parlons only works if enough people carry traffic. On the Network tab, the **Help the network** switch is **on** by default. It does the most the phone safely can, in three steps you can watch:

| Step | What it means | When it runs |
|---|---|---|
| **Contributing** | Holds encrypted mail for offline people, answers lookups, signs delivery receipts, stores small encrypted pieces. | Most of the time. Heavy duties pause on mobile data if **Wi-Fi only** is on (it is, by default). Pauses on low battery. |
| **Directly reachable** | Your phone gets a real public address, so contacts reach you one hop shorter. | Only on Wi-Fi, while charging, and only if your router opens a port. |
| **Relay ⚡** | Your phone carries other people's sealed traffic, on port **9535**. | Only on Wi-Fi, while charging, with battery at 60% or more. Stops when you unplug. |

None of this costs you messages. If your router will not open a port, you stay reachable through relays as always. The app tells you why a step is not running, for example "Relay needs Wi-Fi", "Plug in to relay for others" or "Charge above 60% to relay".

#### How to turn Help the network on or off (Android)

1. Open the **Network** tab.
2. Under **Help the network**, turn the **Help the network** switch on or off.
3. With it on, watch the three steps below the switch: **Contributing**, **Directly reachable** and **Relay ⚡**. If a step is not running, a note under them says why and what to do, for example **Open Wi-Fi settings**.

#### How to let heavy duties run on mobile data (Android)

1. Open the **Network** tab.
2. Under **Help the network**, turn off **Wi-Fi only**. The line changes to "Heavy duties run on any network".
3. Turn it on again to keep heavy duties to Wi-Fi ("Heavy duties pause on mobile data").

#### How to see what to set on your router (Android)

1. Open the **Network** tab.
2. Find the card **This phone on your network · what to forward**. It shows **This phone (LAN)**, **Your router**, **Your public address**, and, when they apply, **Router rule for direct reach** and **Router rule for the relay**. Tap any of them to copy it.
3. Tap **Open the router's admin page** to open your router in the browser.

#### How to set up a manual port-forward (Android)

Use this if your router does not open a port by itself.

1. Open the **Network** tab and find **Direct reachability · a public address when possible**.
2. Tap **Set up manual port-forward**. The **Manual port-forward** sheet opens.
3. Under **Forward to this phone** you see the local address and port. By default the port is **9536**. You can change it in **Port to forward**. Port 9535 is reserved for relay mode.
4. On your router, add a rule that forwards that **TCP** port to that local address.
5. Back in the sheet, check **Your public IP (found automatically)**. Correct it if it is wrong.
6. Tap **Enable & verify**. You see "Verifying …". If it works you see "You're directly reachable", and the button on the Network tab reads **Manual forward · on**.

To stop, open the sheet again and tap **Turn manual off**.

If it still fails, your internet provider probably shares one public address across many homes (carrier-grade NAT). No forward can fix that. You stay connected through relays.

### 19.4 Help the network (Desktop)

Nothing listens on your computer until you turn one of these on. On Windows, the firewall asks once; allow it.

#### How to make your computer directly reachable (Desktop)

1. Open the **Network** tab and find **Direct reachability · a public address when possible**.
2. Click **Check / make me reachable**. Parlons asks your router for a public port and proves it from outside before advertising it.
3. If it works, your **Public address** shows. The button now reads **Re-check**.
4. If the router refuses, set up a manual port-forward (next).

#### How to set up a manual port-forward (Desktop)

1. On the **Network** tab, click **Set up manual port-forward**.
2. Under **Forward to this computer** you see the local address and port. Change the port in **Port to forward** if you need to. Port 9535 is reserved for relay mode.
3. On your router, add a rule that forwards that **TCP** port to that local address.
4. Check **Your public IP (found automatically)**, or type it.
5. Click **Enable & verify**. You see "Enabled — verifying … from outside…". The button then reads **Manual port-forward · on**.

To stop, open it again and click **Turn manual off**.

#### How to run your computer as a relay (Desktop)

1. Open the **Network** tab and find **Help the network**.
2. Turn on **Run as a relay**. It carries other people's sealed traffic on port 9535 and grows the network.
3. The card shows "You're carrying other people's traffic on port 9535", with **Clients attached** and **Messages relayed**.

Turn the switch off to stop.

### 19.5 Event log

The bottom of the Network tab has an **Event log**: everything the transport did, newest first. When something does not work, this tells you why. It never contains your messages or your words.

#### How to read or clear the event log

1. Open the **Network** tab and scroll to the bottom.
2. Read the **Event log**. The newest line is at the top.
3. Tap (or click) **Clear** next to the title to empty it.

---

## 20. Backups and moving to a new device

### 20.1 What each backup carries

| Backup | Identity (address) | Name | Contacts | Wallet key-use count | Chat history |
|---|---|---|---|---|---|
| Your 24 words | Yes | No | No | **No** | No |
| Android **Back up identity…** file (`.pbk`) | Yes | Yes | Yes | **Yes** | **No** |
| Parlons account backup (`.pbk`) | Yes | Yes | Yes | Yes | Yes |

The Android backup file does **not** contain your chat history. Restoring it on a new phone gives you your identity, name and contacts. Chats start empty.

### 20.2 Make an encrypted backup (Android)

1. Open **Settings** (**three dots**, then **Settings**).
2. Under **Keys & security**, tap **Back up identity…**
3. In **Back up identity**, type a password of at least 6 characters in **Password**, and again in **Repeat password**. It is **not** recoverable if you lose it.
4. Tap **OK**.
5. Choose where to save the file. The suggested name is `parlons-backup.pbk`.
6. You see "Backup saved".
7. Keep the file somewhere safe, for example on a computer or a USB stick you control.

### 20.3 See your 24 words again

#### How to show your 24 words (Android)

1. Make sure nobody is looking.
2. Open **Settings**. Under **Keys & security**, tap **Show seed phrase**.
3. A window asks **Show seed phrase?** and warns you. Tap **Reveal**.
4. The window **Write these down, offline** shows the words. Screenshots are blocked.
5. Tap **Close** when done. Avoid **Copy** if you can; it clears the clipboard after 60 seconds.

#### How to show your 24 words (Desktop)

1. Make sure nobody is looking.
2. Open **Settings**. Under **Keys & security**, click **Show seed phrase**.
3. Confirm **Show seed phrase?**.
4. The window **Your seed phrase** shows the words. **Copy (auto-clears in 60s)** is offered; writing them down is safer.

Or open the seed file (see [section 5.2](#52-parlons-desktop)).

### 20.4 Move to a new Android phone

One identity must run in **one place** at a time. Move it; do not copy it.

1. On the **old** phone, make an encrypted backup ([20.2](#202-make-an-encrypted-backup-android)) and copy the `.pbk` file somewhere you can reach from the new phone.
2. If the old phone held funds, consider moving them first.
3. **Stop using the old phone for Parlons.** Uninstall it, or at least close it and do not open it again.
4. On the **new** phone, install Parlons ([section 4.1](#41-android)).
5. On the welcome screen, tap **Restore from seed phrase** and enter your 24 words.
6. Open **Settings**. Under **Keys & security**, tap **Restore from backup…**
7. Pick the `.pbk` file.
8. In **Restore from backup**, enter the backup password in **Password** and tap **OK**.
9. A window asks **Restore this backup?** It replaces the identity, contacts and chats on this phone with the ones in the backup. Tap **Restore**.
10. You see "Backup restored — reconnecting".

If you have no backup file, stop after step 5. That restores your identity. Your contacts can still reach you, because your address is the same. You will need to re-add them to see them in your list.

If a backup says "This backup was made by a newer Parlons — update the app first.", install the newest app and try again.

### 20.5 Restore a seed phrase over an existing identity (Android)

This **replaces** the identity on this phone. Contacts and chats stored here are cleared, and the transport restarts.

1. Open **Settings**. Under **Keys & security**, tap **Restore a seed phrase**.
2. Read the warning in the **Restore a seed phrase** window.
3. Type or paste the phrase in **Enter your seed phrase**.
4. For a nonstandard Minima phrase, tick **Custom phrase (Minima anyphrase)** and type it exactly.
5. Tap **Restore**.
6. A window **Restored** says "Your identity is restored." Tap **OK**.
7. You see "Identity restored — reconnecting".

### 20.6 Move Parlons Desktop to a new computer

1. Make sure you have your 24 words.
2. Quit Parlons Desktop on the old computer, for good (tray icon, **Quit**).
3. Install it on the new computer. It creates a new identity on first launch.
4. Open **Settings**. Under **Keys & security**, click **Restore a seed phrase**.
5. Paste your 24 words and confirm.
6. You see "Identity restored. Maxima will now close — reopen it to use the restored identity." The app closes.
7. Open it again. It now uses your identity.

Chats and contacts from the old computer do not move this way. You can also copy the whole `.maxima` data folder from the old computer to the new one **before** first launch; this is not a documented procedure, so keep your 24 words safe either way.

### 20.7 Lost your device

- **Lost phone, Android app:** restore your words on a new phone. Anyone holding the old phone can read chats stored on it, so move any funds first.
- **Lost phone paired to a Parlons account:** revoke that phone from another paired device ([how to revoke a device](#how-to-revoke-a-lost-device)). The account and its history are untouched.
- **Lost the words and every device:** the identity is gone. There is no reset, because there is nobody who could do it.

---

## 21. One account on every device: Parlons Cloud, your own account, hosted accounts

This is a short overview. The [Parlons Cloud manual](parlons-cloud-portal.md) covers the phone app in depth. The [Parlons Node manual](parlons-node.md) covers running an account server.

### What a Parlons account is

A **Parlons account** is a small always-on program. It keeps your identity online, holds your contacts and chat history, receives messages while your phone is off, and lets every device you pair see the same conversations. It needs a machine that stays on with outbound internet: a Raspberry Pi, a home computer, or a small server. It needs no port forwarding.

You can get one three ways:

- **Run your own.** One install command on a Mac, Linux, Raspberry Pi or Windows machine. It prints your address and a pairing **invite**.
- **Get a hosted account.** Someone who runs a host makes an account for you and gives you an invite.
- **Parlons Node, minimaCore Desktop or minimaDesk** also include an account.

### Pair a phone

The **invite** is a QR code, or a line of text starting `MAX#`, that carries the account's address and a one-time pairing code.

#### How to install Parlons Cloud (Android)

1. On the phone, open the releases page: <https://github.com/eurobuddha/maxima/releases>
2. Find the release tagged `portal-v0.2.90`.
3. Tap the file `parlons-cloud-portal-0.2.90-release.apk` to download it.
4. Allow installs from your browser if Android asks, then tap **Install** and **Open**.

#### How to pair a phone

1. Open **Parlons Cloud** (Android) or the iPhone app.
2. On the first screen, **Run Parlons in the cloud**, tap **Scan pairing QR** and scan the invite. Or paste it into **Account address or pairing invite**. Both the address and the code fill in.
3. Tap **Connect & pair**. You see "Attaching to the network…", then "Account found — pairing…".
4. Allow notifications.
5. On Android, open **Settings**, and under **This device** tap **Keep running in the background** if offered.

If you have no code, leave **Pairing code** empty and tap **Connect & pair**. The app says "Pairing requested. Approve it from an already-paired device". Approve it on a device that is already paired (next steps).

#### How to approve a device waiting to pair

1. On a phone that is already paired, open the **Node** tab.
2. Scroll to **Pending approval (N)**. Each entry reads **A device wants to pair**, with its key.
3. Check the key matches the one the new device shows.
4. Tap **Approve** only if it matches.

#### How to pair another device from a paired phone

1. On a paired phone, open the **Node** tab.
2. Tap **Pair my other device**.
3. If the app asks **Invite a device**, enter the account's admin password and tap **Continue**.
4. The app shows **New pairing code minted**. Tap **Copy invite**, or **QR** to show it as a QR code.
5. On the new device, follow [How to pair a phone](#how-to-pair-a-phone).

The [Parlons Cloud manual](parlons-cloud-portal.md#10-adding-more-devices-owner-invitations) explains the admin password.

#### How to revoke a lost device

1. On a phone that is still paired, open the **Node** tab.
2. Scroll to **Paired devices (N)** and find the lost device.
3. Tap **Revoke**.
4. Read **Revoke this device?** and tap **Revoke**.

#### How to unpair this phone

1. In Parlons Cloud, tap **More**, then **Unpair this device**.
2. Read **Unpair this device?** and tap **Unpair**. The app returns to the first screen.
3. On another paired device, revoke this phone's entry ([How to revoke a lost device](#how-to-revoke-a-lost-device)).

Your account and identity stay on the account machine.

### What Parlons Cloud shows

- **Chats** and **Contacts**, like the Android app. Your address is shown as **My Parlons Cloud address**. It stays the same on every device.
- **Node**: your account's uptime, relays, paired devices (**Revoke** a lost one), devices waiting for approval, and **Pair my other device** for a new pairing code.
- **Settings**: name, read receipts, app lock, notifications, the relays the phone starts from, **Show seed phrase**, **Back up account…** (an encrypted `.pbk` of the whole account, including chat history), and **Import identity or backup…**.

For every Parlons Cloud screen step by step, see [Pair the app to your account](parlons-cloud-portal.md#4-pair-the-app-to-your-account) and the rest of the [Parlons Cloud manual](parlons-cloud-portal.md).

### Hosted accounts: what the host can and cannot do

- The host **cannot** read your messages in transit.
- The host **can** read what is stored on their machine, including your chat history. Choose a host you trust the way you would trust someone holding your mailbox.
- You can leave at any time. Back up the account and restore it on your own machine. Your address stays the same.

Hosted guest accounts have **chat, calls and files only**: no wallet, terminal or host controls.

#### How to back up a Parlons account

1. In Parlons Cloud, tap **More**, then **Settings**.
2. Under **Keys & backup**, tap **Back up account…**.
3. Type a passphrase of at least 6 characters, twice. It is not recoverable if you lose it.
4. Tap **Create backup**, then choose where to save the `.pbk` file.

To move the account to your own machine, follow [How to restore an account on your own machine](parlons-cloud-portal.md#how-to-restore-an-account-on-your-own-machine).

---

## 22. Security and privacy

### Your 24 words

- They are your identity **and** a Minima wallet. Anyone who reads them can be you and spend your funds.
- Write them on paper when they are shown. Keep the paper offline.
- Never photograph them, email them, or type them into another app or website.
- Parlons never asks for them except when you restore. Nobody from Parlons will ever ask for them.
- One identity must run in one place at a time. Restoring the same words on a second device while the first still runs confuses your contacts and can reuse wallet keys that must never be reused.

### What Parlons protects

- **Message content.** Sealed end to end, signed by the sender. Relays see only a sealed blob and a routing key.
- **Groups.** Each message is sealed separately to each member.
- **Forgery.** A message is only accepted if its signature matches the sender.
- **Replays.** Duplicate and stale messages are rejected.
- **Screens with your words.** Screenshots are blocked on the seed, restore and backup screens.

### What Parlons does not protect against

- **Someone holding your unlocked device.** Chats stored on it are readable there. Use the device lock, and **App lock** on Android.
- **Someone holding your 24 words.** They are you.
- **A host reading what its machine stores**, for a hosted account.
- **Traffic analysis by a relay.** A relay knows which routing keys talk to it, not what they say. Using several relays, which Parlons does, limits what any one sees.
- **Direct connections reveal IP addresses.** Calls, private files and direct reachability connect devices directly, so the other side can see your internet address.
- **A contact you added.** Only add people you mean to.

### Privacy

The authors collect **nothing**. There is no analytics, no crash reporting service, no advertising identifier, no tracking, no third-party SDK, and no account to sign up for.

- The Android app and Parlons Desktop keep your identity, contacts and chats on the device.
- Parlons Cloud on a phone keeps only a device key and a cache. The account holds the rest.
- **iPhone wake path.** iOS cannot keep a background connection. When the iPhone app is closed, your account sends a content-free wake ("message" or "call", no sender, no recipient, no text) through a small relay run by the app's publisher to Apple's push service. You can turn it off in the iPhone app's Settings, Notifications.

---

## 23. Troubleshooting

### Common fixes, step by step

#### How to fix messages that arrive late (Android)

1. Open **Settings** (**three dots**, then **Settings**).
2. Under **Node & power**, check **Battery exemption**. If it says **not set**, tap **Fix battery settings** and allow Parlons to run without restrictions.
3. Make sure notifications are allowed ([section 17](#17-notifications)).
4. Open the **Network** tab. Check **Hosts connected** is 1 or more.
5. If it is 0, tap **Auto-connect**.

#### How to get back online

1. Open the **Network** tab.
2. Tap (or click) **Auto-connect** and wait for it to finish.
3. If you are still offline, check your phone or computer has internet.
4. On Android, tap **Manage** and make sure **Use built-in relays** is on. On Desktop, check the built-in relays switch reads "Built-in relays: on".
5. If you know a relay that works, add it ([section 19.2](#192-relays-hosts)).
6. Read the **Event log** at the bottom of the tab for the reason.

#### How to fix a missing camera or microphone (Android)

1. Open the Android **Settings** app.
2. Go to **Apps**, then **Parlons!**, then **Permissions**. (The names vary a little by phone maker.)
3. Allow **Camera** and **Microphone**.
4. Go back to Parlons and try again.

### Messages and contacts

| Symptom | Fix |
|---|---|
| "Introduction sent" but the contact never appears | Their device is off or unreachable. The handshake completes when it comes back. If it never does, ask them to add you instead. |
| "Not a Parlons address" / "That doesn't look like a Parlons address" | The address must start with `Mx` or `MAX#`. Copy it again, in full. |
| Messages arrive late, or only when I open the app (Android) | **Settings**, **Node & power**, **Fix battery settings**. Check the **Network** tab shows at least one host connected. Allow notifications. |
| One tick that never becomes two | The other device has not confirmed receipt yet. It is off, or their app is killed in the background. The message waits; it is not lost. |
| A red cross on my message | No relay would take it. It stays in your outbox and is retried. Check your internet connection and the Network tab. |
| Status pill says "offline" | No relay is holding a connection for you. The app reconnects by itself. If it lasts, open the Network tab, check **Use built-in relays** is on, or add a relay you know. |
| "Couldn't resolve a shareable IP address" | You are offline. Reconnect and open **Share my address** again. |
| No address shown: "No address yet — waiting for a host" | The app is still connecting. Wait a few seconds. |
| Contact on classic Maxima cannot get my voice note or call | Voice notes and calls need Parlons on both ends. Send text instead. |
| I want to stop someone writing to me | Removing a contact only removes them from your list. They can still write. |

### Media and files

| Symptom | Fix |
|---|---|
| "Too big: 16 MB is the most a message can carry" | Send it as a **Private file** (up to 512 MB). |
| "Photo unavailable · tap to retry" | Tap it. The sender's device or relays may be offline; try later. |
| "Update Parlons to open this file" / "Private file · update Parlons to open" | Install the newest version. Both sides need a version with private files. |
| A private file stays on "Waiting for peers" | Someone who has the file must be online and reachable. Ask the sender to open Parlons. If nobody in the chat is directly reachable or on your local network, the transfer cannot start. |
| "Preparation was interrupted" | Remove the transfer and choose the file again. |
| "Choose a file up to 512 MB" | The file is too large for a private file. |
| Camera or "Scan their QR" does nothing | Allow the camera when asked. The permission is requested at the first scan. |
| "Recording failed" / "Microphone unavailable" | Allow the microphone for Parlons in Android settings. |

### Calls

| Symptom | Fix |
|---|---|
| "Calls need Parlons on both ends" | The contact uses classic Maxima. Calls are not possible. |
| "Group calls are not supported yet" | Call people one at a time. |
| Call rings but shows "Could not connect" or "Call couldn't connect" | Your networks blocked a direct connection. Try with one or both people on Wi-Fi. |
| "Microphone permission is needed for this call" | Allow the microphone (and camera for video) in Android settings. |
| Incoming calls are silent or do not show on the lock screen | **Settings**, **Privacy**, **Incoming calls**: tap **ALLOW** for full-screen alerts, then **SETTINGS** for ringtone. Check the phone is not on silent or Do Not Disturb. |
| Desktop shows a call but I cannot answer | Parlons Desktop does not carry call audio or video. Answer on your phone. |

### Payments

| Symptom | Fix |
|---|---|
| No **Send payment** in the attach menu | You are in a group, or the person is not a known contact. Payments are one-to-one. |
| "No wallet address yet — ask them to open this chat" | The other person needs to open your chat once, so their app shares its wallet address. |
| "Not enough confirmed funds." | Wait for incoming coins to confirm. On Android, tap the "tap Sync to unlock the rest" line if it shows ([section 15.3](#153-the-wallet-tab)). |
| "Wallet still opening…" | Wait a moment and try again. |
| Balance is lower than expected after a restore | Coins may still be syncing. Check **History**. |

### Identity and backups

| Symptom | Fix |
|---|---|
| Where are my 24 words? | Android and Desktop: **Settings**, **Keys & security**, **Show seed phrase**. Desktop also: the `seed.txt` file in `.maxima`. |
| The Desktop seed window never appeared | It shows only on the very first launch. The words are in the seed file ([section 5.2](#52-parlons-desktop)). On a Mac, in Finder use Go, Go to Folder, and type `~/.maxima`. |
| "Not a Parlons backup file" / "Not a valid backup" | Pick the `.pbk` file made by **Back up identity…**, and check the password. |
| "This backup was made by a newer Parlons — update the app first." | Update the app, then restore. |
| I restored my words but my contacts are gone | A words-only restore brings back the identity only. Restore an encrypted backup, or re-add contacts. They can still reach you at the same address. |
| "Note: no BIP39 checksum" | Normal for a phrase from a Minima node. |

### Installing

| Symptom | Fix |
|---|---|
| Android will not install the APK | Allow installs from your browser for this install (Settings, Apps, Special app access, Install unknown apps). |
| Mac: "MaximaNode is damaged" or a Gatekeeper warning | You have a build that is not the signed release. Download the disk image from the releases page. |
| Windows SmartScreen blocks the download | In the browser's download list choose Keep, then Keep anyway. Then **More info**, **Run anyway** on the installer. |
| "Maxima is already running." | Only one copy runs at a time. Look in the Dock, menu bar or system tray. |
| The Linux package will not install | It is for 64-bit x86 only. On ARM, run a Parlons account instead. |

### Where the logs are

| App | Log |
|---|---|
| Android app | Network tab, **Event log** |
| Parlons Cloud (Android) | Node tab, logs |
| iPhone | Settings, Log (no message content) |
| Parlons Desktop | Network tab, event log at the bottom |

### Reporting a problem

1. Note your platform and app version. Open **Settings** and scroll to **About**.
2. Write down what you expected, and what happened.
3. Open the **Network** tab and copy the relevant **Event log** lines.
4. Open the project's issue tracker: <https://github.com/eurobuddha/maxima/issues>
5. Open a new issue and paste all of the above.

Logs never contain your messages or your words. Check before pasting anyway, and never paste your seed phrase anywhere.

---

## 24. Glossary

| Term | Meaning |
|---|---|
| **Parlons!** | The private chat app. Android, desktop and iPhone versions. |
| **Maxima** | The messaging layer under Parlons. It is wire-compatible with classic Minima Maxima. |
| **Minima** | The blockchain behind your wallet. Also the name of its native coin. |
| **MxUSD** | The Minima dollar-pegged token. Token id `0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90`. |
| **Identity** | Your key pair, derived from your 24 words. It is who you are on Parlons. |
| **Seed phrase / 24 words** | The words that create your identity and your wallet. Anyone with them is you. |
| **Custom phrase (anyphrase)** | A nonstandard Minima phrase, used exactly as typed, including capitals and spaces. |
| **Address** | How people reach you. `Mx<key>@<host>:<port>` changes with your relay; `MAX#<key>#<location service>` is permanent. |
| **Public key** | The `Mx` part of your address, and the full "maxima publickey" in Settings. Safe to share. |
| **Relay / host** | A public Maxima node that holds a connection open for you and carries sealed traffic. It cannot read your messages. |
| **Multi-homing** | Being connected to several relays at once, so one going down does not cut you off. |
| **Location service (MLS)** | A directory that answers "what is this identity's current address?" A pinned one gives you a permanent `MAX#` address. |
| **Introduction** | The handshake that adds a contact. They appear once their device replies. |
| **Mailbox** | Sealed messages held for someone who is offline, handed over when they come back. |
| **Outbox** | Your own messages waiting for a relay to accept them. |
| **Ticks** | Message state: sending, sent (relay took it), delivered (their device has it), read. |
| **Read receipts** | Optional. Tells contacts when you have read their messages. Off by default. |
| **Group** | A chat of up to 12 people including you. Each message is sealed to each member. |
| **Admin** | A group member who can change the members. |
| **Private file** | An encrypted file of up to 512 MB shared directly between the people in a chat. |
| **Directly reachable** | Your device has a real public address, proven from outside, so contacts reach you without a relay. |
| **Carrier-grade NAT (CGNAT)** | When an internet provider shares one public address among many customers. No port-forward can open it. |
| **Help the network** | The Android switch that lets your phone hold mail, answer lookups and, when charging on Wi-Fi, relay. |
| **Signature uses / key uses** | Minima wallets sign with one-time keys. The counter makes sure a key is never reused. It only goes up. |
| **`.pbk` backup** | An encrypted Parlons backup file, protected by your password. |
| **Parlons account** | An always-on program holding your identity, so many devices can share it. |
| **Parlons Cloud** | The app (Android or iPhone) that pairs with a Parlons account. It holds no identity of its own. |
| **Invite / pairing code** | A QR or `MAX#` text with an account address and a one-time code, used to pair a device. |
| **Hosted account** | A Parlons account that someone else runs for you on their machine. |
| **Parlons Node** | A server package: a full Minima node, a relay and a Parlons account in one. |
| **Classic engine** | An option to route through classic Maxima. Most people should leave it off. |
| **Core node** | A label for an always-on device. It changes only what contacts see. |
| **KeetChat** | Contacts from a linked minimaCore Desktop node. Needs that node on the same computer. |
| **minimaDocs** | A separate documents app that can be connected to your Parlons account in Settings. |
