# Parlons on a Mac

Two things a Mac can be. **Parlons Desktop** is the full app: your identity lives on this Mac, like the Android app. **A Parlons account** is a small always-on program for a Mac that stays on, which your iPhone and other devices pair to. You can run both.

## Parlons Desktop

### Install

1. Download [MaximaNode-{{DESKTOP_VERSION}}.dmg]({{DESKTOP_DMG}}). It is signed and notarized, so macOS opens it without warnings.
2. Open the disk image and drag **MaximaNode** into Applications.
3. Open it from Applications. It brings its own Java; nothing else to install.

### First launch

The app creates your identity and shows a window titled **Back up your Maxima seed** with your 24 words. Write them down now; they are also saved, readable only by you, at `~/.maxima/seed.txt`. Then you are on the Chats tab.

Give yourself a name: the three-dot menu at the top right opens **Settings**; the first field is **Your name**.

### Your address, and adding people

- **Contacts** tab, card **Your Maxima address**, button **Share my address**: a QR for a phone to scan and the full address to copy.
- **Add contact**: paste the other person's address (`Mx…@host:port` or `MAX#…`) and click **Introduce myself**. There is no camera on the desktop; people scan you, you paste them. They appear once their device answers.

### Help the network

**Network** tab. **Check / make me reachable** asks your router for a public port (9536) and proves it from outside before advertising it; if the router refuses, **Set up manual port-forward** tells you the exact rule. **Run as a relay** carries other people's sealed traffic on port 9535 and grows the network. Nothing listens on your Mac until you turn one of these on.

### Back up, update, data

- Settings, **Keys & security**, **Show seed phrase**; or copy `~/.maxima/seed.txt`.
- Update by installing the new disk image over the old app. Your data in `~/.maxima` stays.
- Calls are not on the desktop yet; the buttons explain why.

---

## Run a Parlons account on this Mac

Do this on a Mac that stays on (a Mac mini in a cupboard is ideal). It gives your iPhone and any other device one shared account, reachable whenever the Mac is.

Open Terminal (Applications, Utilities) and paste:

```
curl -fsSL {{INSTALL_SH}} | bash
```

It installs Java if needed, downloads the current release and checks its signature, registers a background service that starts at login and survives reboots, starts your account, pairs this Terminal as the first device, and ends with your address, a QR to scan from a phone, and the `parlons` command. Full details, the Windows and Linux versions, and what to do next: [Run your own account](your-account.html).

## If it goes wrong

- **"MaximaNode is damaged" or a Gatekeeper warning.** You have a build that is not the signed release; download the DMG from the link above.
- **The seed window never appeared.** It shows only on the very first launch. The words are in `~/.maxima/seed.txt` (Finder: Go, Go to Folder, type `~/.maxima`).
- **"Maxima is already running".** Only one copy runs at a time; find it in the Dock or the menu bar.

Everything else: [Help](help.html).
