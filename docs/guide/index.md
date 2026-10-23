# Get started with Parlons

Parlons is private chat with no company in the middle. Your messages are sealed on your device and carried by relays that people like you run. There is no sign-up, no phone number and no account with anyone.

What you install depends on one question.

## Chat on this device, or one account on every device?

**On this device.** Install the Parlons app on an Android phone or a computer. It creates your identity right there, as 24 words, and you can chat a minute later.

**One account on every device.** Run a small always-on program, a Parlons account, on a machine that stays on. Then pair your phones and computers to it with Parlons Cloud. You get the same chats everywhere, and messages wait for you while a phone is off. This is the only way an iPhone can receive messages while the app is closed.

<div class="cards">
<a href="parlons.html#41-android"><b>Android</b><span>The full app. Your identity lives on the phone. Android 9 or newer.</span><em>APK · {{ANDROID_VERSION}}</em></a>
<a href="parlons.html#42-mac"><b>Mac</b><span>Parlons Desktop, signed and notarized. Apple silicon, macOS 13 or newer.</span><em>DMG · {{DESKTOP_VERSION}}</em></a>
<a href="parlons.html#43-windows"><b>Windows</b><span>Parlons Desktop for your user, no administrator needed. Windows 10 or 11, 64-bit.</span><em>MSI · {{DESKTOP_VERSION}}</em></a>
<a href="parlons.html#44-linux"><b>Linux</b><span>Parlons Desktop for 64-bit Debian, Ubuntu and their relatives.</span><em>DEB · {{DESKTOP_VERSION}}</em></a>
<a href="cloud.html"><b>iPhone and Parlons Cloud</b><span>Pair a phone with an always-on account by scanning one QR code.</span><em>Android APK · {{PORTAL_VERSION}} · iOS 17+</em></a>
<a href="cloud.html#19-one-account-on-your-own-machine-the-one-line-installers"><b>Run your own account</b><span>One command on a Mac, a Linux box, a Raspberry Pi or a VPS. No port forwarding.</span><em>Parlons Cloud · {{CLOUD_VERSION}}</em></a>
</div>

## Download

Every download is on the [releases page]({{RELEASES}}), signed by its authors, with checksums.

| You have | Download | Then |
|---|---|---|
| An Android phone | [parlons-{{ANDROID_VERSION}}.apk]({{ANDROID_APK}}) | [Install on Android](parlons.html#41-android) |
| A Mac (Apple silicon) | [MaximaNode-{{DESKTOP_VERSION}}-notarized.dmg]({{DESKTOP_DMG}}) | [Install on a Mac](parlons.html#42-mac) |
| A Windows PC | [MaximaNode-{{DESKTOP_VERSION}}.msi]({{DESKTOP_MSI}}) | [Install on Windows](parlons.html#43-windows) |
| A Linux PC | [maximanode_{{DESKTOP_VERSION}}_amd64.deb]({{DESKTOP_DEB}}) | [Install on Linux](parlons.html#44-linux) |
| An Android phone, paired to an account | [parlons-cloud-portal-{{PORTAL_VERSION}}-release.apk]({{PORTAL_APK}}) | [Install Parlons Cloud](cloud.html#3-install-the-app) |
| An iPhone | Not in the App Store yet | [Parlons Cloud for iPhone](cloud.html) |
| A machine that stays on | The one-line installer below | [Run your own account](cloud.html#19-one-account-on-your-own-machine-the-one-line-installers) |

To run your own account on a Mac, Linux, a Raspberry Pi or a VPS, open a terminal and paste:

```
curl -fsSL {{INSTALL_SH}} | bash
```

It installs Java if needed, fetches the newest release and checks its checksum, registers a service that survives reboots, and ends by drawing the QR code your phone scans.

## The manuals

Each manual walks through every feature, step by step.

<div class="cards">
<a href="parlons.html"><b>Parlons! manual</b><span>The Android app and Parlons Desktop: identity, contacts, chats, groups, photos, files, calls, payments, the network, backups and troubleshooting.</span><em>For everyone</em></a>
<a href="cloud.html"><b>Parlons Cloud manual</b><span>Using an always-on account from your phone, inviting your other devices and friends, and hosting accounts on your own machine or a server.</span><em>Account holders and hosts</em></a>
<a href="node.html"><b>Parlons Node manual</b><span>A full Minima node, a relay and your account in one process: install, configure, put TLS in front, upgrade and troubleshoot.</span><em>Operators</em></a>
</div>

## Three things everyone should know

1. **Your 24 words are your identity and a wallet.** Write them on paper when they are shown and keep the paper offline. Never photograph them. Whoever holds them is you. Read [Security and privacy](parlons.html#22-security-and-privacy).
2. **Your address is a key, not a phone number.** Share it as a QR code from the Contacts screen. Adding someone is a handshake: you introduce yourself, and they appear once they answer.
3. **Nobody runs Parlons for you.** Relays carry sealed messages and cannot read them. The Android app and the desktop help carry the network when it is safe to, and an account you run can be a relay for others too.

## If something goes wrong

Each manual ends with a troubleshooting section: [the apps](parlons.html#23-troubleshooting), [accounts](cloud.html#17-troubleshooting-for-account-holders), [hosting](cloud.html#32-troubleshooting-for-operators) and [nodes](node.html). Anything else goes to the [issue tracker]({{ISSUES}}). Include the app version and your platform.
