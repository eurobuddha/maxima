# Get started with Parlons

Parlons is private chat with no company in the middle. Your messages are sealed on your device and carried by relays that people like you run. There is no sign-up, no phone number, no account with anyone. What you need depends on one question:

## Do you want to chat on this device, or have one account on every device?

**On this device.** Install the Parlons app. It creates your identity right there, in 24 words, and it is ready in a minute. Android and computers can do this.

**One account on every device.** Then you run a small always-on program called a Parlons account, on a machine that stays on, and pair your phones and computers to it. The iPhone works this way only (Apple does not let an app stay reachable in the background), and it is also how you get the same chats on a phone and a laptop.

<div class="cards">
<a href="android.html"><b>Android</b><span>The full app. Your identity lives on the phone. Android 9 or newer.</span></a>
<a href="iphone.html"><b>iPhone</b><span>Pairs with a Parlons account. iOS 17 or newer.</span></a>
<a href="mac.html"><b>Mac, Windows, Linux</b><span>Parlons Desktop, the full app for a computer. Nothing else to install.</span></a>
<a href="your-account.html"><b>Run your own account</b><span>One command on a Mac, Linux box, Raspberry Pi or VPS. Then pair any device.</span></a>
<a href="hosted.html"><b>Hosted accounts</b><span>No server? Someone who runs a host can give you an account. Or host friends yourself.</span></a>
<a href="node.html"><b>Parlons Node</b><span>For operators: a full Minima node, a relay and your account in one process.</span></a>
</div>

## What you need

| | |
|---|---|
| Android | Android 9 or newer. The app is a direct download (not in the Play Store). |
| iPhone | iOS 17 or newer, and a Parlons account to pair with (your own, or hosted). |
| Mac | macOS 13 or newer, Apple Silicon or Intel. |
| Windows | Windows 10 or 11, 64-bit. |
| Linux | A 64-bit Debian or Ubuntu with a desktop, for the app; any Linux with Java for an account. |
| A Parlons account | Any machine that stays on with outbound internet: a Raspberry Pi, a home computer, a small VPS. Java 11 or newer (the installer adds it), 1 GB of RAM. No port forwarding needed. |

## Three things everyone should know

1. **Your 24 words are your identity and a wallet.** Write them down when they are shown, keep them offline, never photograph them. Whoever holds them is you. See [Security](security.html).
2. **Your address is a key, not a phone number.** Share it as a QR from the Contacts screen. Adding someone is a handshake: you introduce yourself, they appear once they answer.
3. **Nobody runs Parlons for you.** Relays carry sealed messages; they cannot read them. If you can, help the network: the Android app and the desktop do it automatically when conditions allow, and an account you run is a relay for others too.

## If it goes wrong

Every page ends with a section for its own platform, and [Help](help.html) collects the rest: an account that shows offline, notifications that arrive late, a contact that never appears, a lost phone. Report anything else at the [issue tracker]({{ISSUES}}) with the app version and the platform.
