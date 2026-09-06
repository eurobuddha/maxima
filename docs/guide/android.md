# Parlons on Android

The full app: your identity lives on the phone, the phone relays for others when it can, and there is nothing else to run. Android 9 or newer.

## You will need

- An Android phone, version 9 or later.
- Five minutes and a pen: the app shows you 24 words once, and you write them down.

## Install

Parlons is not in the Play Store. It is a direct download, signed by its authors.

1. On the phone, open [parlons-{{ANDROID_VERSION}}.apk]({{ANDROID_APK}}) (the current release; all releases are [here]({{RELEASES}})).
2. Android asks whether to allow installs from your browser. Allow it for this install; you can turn it off again afterwards under Settings, Apps, Special app access, Install unknown apps.
3. Tap Install, then Open.

If you already use the PandaApps store on your phone, Parlons! is listed there too and updates through it.

> **Updating:** install the new APK over the old one. Your identity, contacts and chats stay.

## First run: your identity

The welcome screen offers two choices.

- **Create new identity.** The app generates your 24 words and shows them on a screen that cannot be screenshotted. Write them down now, in order, on paper. Tap **I've saved them** only when you have. There is no way to recover them later.
- **Restore from seed phrase.** If you already have 24 Parlons words from another device, paste or type them. Use this only if that other device is no longer running: one identity must live in one place at a time.

After that the app asks to send you notifications. Allow it; without it you will not see messages arriving.

## Keep it connected

Android likes to kill background apps, and for a chat app that means silently missed messages, especially overnight. Once, do this:

1. Open the menu (three dots, top right) and choose **Settings**.
2. Under **Node & power**, if the battery line says "not set", tap **Fix battery settings** and allow Parlons to run without restrictions.

## Your address, and adding people

1. Open the **Contacts** tab. The card at the top says **Your Parlons address**.
2. Tap **Share my address**. A QR appears, with the full address underneath (tap it to copy) and a Share button.
3. To add someone: tap **Add**, then **Scan their QR** and point the camera at their screen, or paste their address into the field, then **Introduce myself**.

The app says "Introduction sent, waiting for them to reply". They appear in your list the moment their device answers; if it is off, the introduction waits.

Your address has two parts joined by `@`: `Mx…` is you and never changes; the part after `@` is whichever relay currently carries your traffic and does change. Contacts are told automatically. If you pin a location service (Network tab, Location settings) you get a permanent `MAX#…` address that never changes at all; share that one when you can.

## Chat

Chats, groups, photos, voice notes, read receipts. The ticks under a message mean: one grey tick, a relay accepted it; two ticks, the other person's device has it; two blue ticks, read; a cross, no relay would take it and it waits in the outbox.

## Help the network

On the **Network** tab, the **Help the network** switch is on by default. It does the most the phone safely can, in three steps you can watch: contributing (holding mail and answering lookups for others), directly reachable (a real public address, one hop shorter for your contacts), and relay (carrying other people's sealed traffic on port 9535). The heavy steps only happen on Wi-Fi, charging, with the battery above 60 percent, and never cost you messages. If your router does not open a port by itself, the app tells you exactly what to forward and to which address; or leave it, the phone stays reachable through relays either way.

## Back up

Settings, **Keys & security**:

- **Show seed phrase** shows the 24 words again (twice confirmed, screenshots blocked).
- **Back up identity** writes an encrypted file that also keeps your wallet's key counters, which the words alone do not. Keep it somewhere safe.

---

## Parlons Cloud for Android

If you also want the same account on an iPhone, a second phone or a laptop, or you want to be reachable when this phone is off, run a [Parlons account](your-account.html) and pair to it with a different app: **Parlons Cloud**. It holds no identity of its own; it is a window onto your account.

1. Install [parlons-cloud-portal-{{PORTAL_VERSION}}-release.apk]({{PORTAL_APK}}) the same way as above.
2. Open it. On **Run Parlons in the cloud**, tap **Scan account QR** and scan the invite your account printed (or paste it: it fills the address and the pairing code together). Tap **Connect & pair**.
3. Allow notifications. Then in Settings, under **This device**, tap **Keep running in the background** if it is offered.

The **Node** tab shows your account: uptime, relays, the paired devices (with **Revoke** for a lost phone), any device waiting for approval, and **New pairing code** for the next device.

## If it goes wrong

- **"Introduction sent" but no contact appears.** Their device is off or unreachable; the handshake completes when it comes back. If it never does, ask them to add you instead.
- **Messages arrive late or only when I open the app.** Fix battery settings (above), and check the Network tab shows at least one host connected.
- **I lost the phone.** Restore your 24 words on the new phone. Anyone holding the old phone can read chats stored on it, so move any funds first if it had a balance.
- **The scan button does nothing.** Allow the camera when asked; the permission is requested at the first scan.

Everything else: [Help](help.html).
