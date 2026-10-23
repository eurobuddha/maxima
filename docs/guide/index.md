# Get started with Parlons

Private chat with no company in the middle. No sign-up, no phone number. Pick what you want to do and follow the steps.

<div class="cards">
<a href="#chat-on-your-android-phone"><b>Chat on your Android phone</b><span>Install one app and start chatting.</span><em>Android 9+</em></a>
<a href="#chat-on-your-computer"><b>Chat on your computer</b><span>Mac, Windows or Linux.</span><em>Parlons Desktop {{DESKTOP_VERSION}}</em></a>
<a href="#one-account-on-all-your-devices"><b>One account on all your devices</b><span>The same chats on every phone and computer.</span><em>Parlons Node {{NODE_VERSION}}</em></a>
</div>

> **Your 24 words are your identity and a wallet.** Write them on paper when they are shown. Never photograph them. Anyone who has them is you.

## Chat on your Android phone

You need Android 9 or newer, and a pen and paper.

1. On the phone, download [parlons-{{ANDROID_VERSION}}.apk]({{ANDROID_APK}}).
2. Open the download and tap **Install**. If Android asks, allow installs from your browser.
3. Tap **Open**. Choose your language.
4. Tap **Create new identity**.
5. Write the 24 words on paper, in order, with their numbers. Check each one, then tap **I've saved them**.
6. When Android asks to send notifications, tap **Allow**.
7. Tap the **three dots** (top right), then **Settings**. Under **Your name**, type your name and tap **Set**.

Now [add a friend](#add-a-friend).

## Chat on your computer

1. Download Parlons Desktop for your computer:
   - Mac with Apple silicon: [MaximaNode-{{DESKTOP_VERSION}}-notarized.dmg]({{DESKTOP_DMG}})
   - Windows 10 or 11: [MaximaNode-{{DESKTOP_VERSION}}.msi]({{DESKTOP_MSI}})
   - Linux (Debian, Ubuntu): [maximanode_{{DESKTOP_VERSION}}_amd64.deb]({{DESKTOP_DEB}})
2. Install it:
   - **Mac:** open the disk image and drag **MaximaNode** into Applications.
   - **Windows:** run the installer. If a blue **Windows protected your PC** box appears, click **More info**, then **Run anyway**.
   - **Linux:** in the folder where you saved it, run `sudo apt install ./maximanode_{{DESKTOP_VERSION}}_amd64.deb`
3. Open **MaximaNode**.
4. A window shows your 24 words. Write them on paper, in order.
5. Click the **three dots** (top right). Under **Your name**, type your name and click **Set**.

Now [add a friend](#add-a-friend).

## One account on all your devices

Your account runs on a Linux machine that stays on, like a small server or a Raspberry Pi with a 64-bit system. Your phones and computers connect to it, so you get the same chats on every device, and messages wait while a phone is off.

The program is **Parlons Node**: your account, a relay that helps carry the network, and a full Minima node, in one.

**On the Linux machine:**

1. Open a terminal on it, or connect to it with SSH.
2. Paste this and press Enter:

   ```
   curl -fsSL {{NODE_INSTALL_SH}} | bash
   ```

3. Wait. It installs everything it needs and starts your node. This takes a few minutes.
4. When asked, choose an **admin password** and type it twice. You need it every time you add a device.
5. When asked again, type the password once. A QR code appears.

**On your phone:**

1. On your Android phone, install [Parlons Cloud]({{PORTAL_APK}}). (An iPhone app is coming; it is not available yet.)
2. Open it and tap **Scan pairing QR**. Point the camera at the QR code.
3. Tap **Connect & pair**. Your account opens.

**Afterwards:**

- To add another phone or computer, run `sudo parlons-pair --admin` on the Linux machine and scan the new QR code.
- Back up your node's 24 words: [how to back up the seed](node.html#20-seeds-identity-and-backups).
- Using a Mac or Windows PC instead of Linux? Run the lighter account-only program, [Parlons Cloud](cloud.html#19-one-account-on-your-own-machine-the-one-line-installers).

## Add a friend

Adding someone is a handshake: you introduce yourself, and they appear when their device answers.

1. Open the **Contacts** tab.
2. Tap **+ Add** (on a computer, click **Add contact**).
3. Scan their QR code, or paste their address.
4. Tap **Introduce myself**.

To give someone your address: open **Contacts** and tap **Share my address**. They can scan the QR code, or you can send them the address.

## Downloads

Everything is on the [releases page]({{RELEASES}}), with checksums.

| For | Download |
|---|---|
| Android phone | [parlons-{{ANDROID_VERSION}}.apk]({{ANDROID_APK}}) |
| Mac (Apple silicon) | [MaximaNode-{{DESKTOP_VERSION}}-notarized.dmg]({{DESKTOP_DMG}}) |
| Windows | [MaximaNode-{{DESKTOP_VERSION}}.msi]({{DESKTOP_MSI}}) |
| Linux desktop | [maximanode_{{DESKTOP_VERSION}}_amd64.deb]({{DESKTOP_DEB}}) |
| Android phone, connecting to your account | [parlons-cloud-portal-{{PORTAL_VERSION}}-release.apk]({{PORTAL_APK}}) |
| Linux server or Raspberry Pi | `curl -fsSL {{NODE_INSTALL_SH}} \| bash` |

## Need more?

Every feature, step by step: the [Parlons! manual](parlons.html) (the apps), the [Parlons Cloud manual](cloud.html) (your phone and account) and the [Parlons Node manual](node.html) (your server). Something wrong? Each manual ends with troubleshooting, or ask on the [issue tracker]({{ISSUES}}).
