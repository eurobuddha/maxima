# Parlons on iPhone

Parlons Cloud for iPhone pairs with a Parlons account and shows you its chats and contacts. The account, not the phone, holds your identity; that is the only way an iPhone can be reachable when the app is closed. iOS 17 or newer.

> **Status:** the app is in TestFlight ahead of App Store review. Ask for a TestFlight invite through the [issue tracker]({{ISSUES}}) until the store listing is live.

## You will need

1. A Parlons account. Either [run your own](your-account.html) on a machine that stays on, or get a [hosted one](hosted.html) from someone who runs a host.
2. The account's **invite**: a QR (or a line of text starting `MAX#`) that carries the account address and a one-time pairing code. Your own account prints it when it starts; a host hands it to you.

## Pair

1. Open Parlons. On the first screen tap **Scan account QR** and scan the invite. Both fields fill in. (Or copy the invite text and tap **Paste**.)
2. Tap **Connect & pair**. Within a few seconds the Chats tab appears.
3. Allow notifications when asked.

If someone else's device is already paired to the account and you have no code, leave the code empty and tap Connect & pair: the app says "Approve it from an already-paired device". On that device, open Settings, Devices, and approve the new iPhone.

## Notifications, honestly

When the app is closed, your account sends a content-free wake through a small relay run by the app's publisher to Apple's push service, and the phone then fetches the message from your account itself. The relay learns nothing but a device token. You can turn this off under Settings, Notifications, **Wake this phone through Parlons push**; the app then catches up when iOS lets it run in the background, which can be hours, and whenever you open it.

One thing to know if the iPhone has no SIM: Apple delivers pushes over a single connection that some home routers drop while idle. When that happens every app's notifications arrive minutes late, at the moment the phone reconnects. A SIM, or a router that keeps idle connections alive, fixes it; nothing in the app can.

## Everyday use

- **Contacts** shows your account's address as a QR under **My Parlons Cloud address**. Add someone by scanning their QR or pasting their address; they appear once their device answers the introduction.
- **Settings** has your display name, read receipts, the paired devices (revoke a lost one there, or mint a **New pairing code** for the next), an app lock with Face ID, the relays the phone starts from, and **Show seed phrase**, which reveals the account's 24 words over the sealed channel. Write them down; they are the account.

## If it goes wrong

- **"Could not resolve the cloud account".** The account is offline or not attached to a relay yet. Check it is running (on your own machine: `parlons status`); the phone retries by itself.
- **"Not connected yet".** The phone is still attaching to relays. On a fresh install the first connection takes a few seconds longer.
- **No notification, message shows when I open the app.** See Notifications above; check Settings, Notifications, Parlons allows alerts; try another network once to tell the router from the phone.
- **The account moved to another machine.** Nothing to do: the address stays the same and the phone reconnects on its own.

Everything else: [Help](help.html).
