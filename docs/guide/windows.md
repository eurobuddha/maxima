# Parlons on Windows

Two things a Windows PC can be. **Parlons Desktop** is the full app: your identity lives on this PC. **A Parlons account** is a small always-on program for a PC that stays on, which your phones pair to. You can run both.

## Parlons Desktop

### Install

1. Download [MaximaNode-{{DESKTOP_VERSION}}.msi]({{DESKTOP_MSI}}).
2. Run it. Windows shows a blue **Windows protected your PC** box, because the installer is not yet signed with a Microsoft certificate. Click **More info**, then **Run anyway**.
3. Choose a folder (the default is fine). It installs for your user only, no administrator needed, and adds Parlons to the Start menu. It brings its own Java.

### First launch

The app creates your identity and shows a window titled **Back up your Maxima seed** with your 24 words. Write them down now; they are also saved, readable only by you, at `C:\Users\<you>\.maxima\seed.txt`. Then you are on the Chats tab.

Give yourself a name: the three-dot menu at the top right opens **Settings**; the first field is **Your name**.

### Your address, and adding people

- **Contacts** tab, card **Your Maxima address**, button **Share my address**: a QR for a phone to scan and the full address to copy.
- **Add contact**: paste the other person's address (`Mx…@host:port` or `MAX#…`) and click **Introduce myself**. There is no camera on the desktop; people scan you, you paste them. They appear once their device answers.

### Help the network

**Network** tab. **Check / make me reachable** asks your router for a public port (9536) and proves it from outside before advertising it; if the router refuses, **Set up manual port-forward** tells you the exact rule. **Run as a relay** carries other people's sealed traffic on port 9535. Windows Firewall asks once when you turn one of these on; allow it. Nothing listens until you do.

### Back up, update, data

- Settings, **Keys & security**, **Show seed phrase**; or copy `C:\Users\<you>\.maxima\seed.txt`.
- Update by running the new installer over the old one. Your data stays.
- Calls are not on the desktop yet; the buttons explain why.

---

## Run a Parlons account on this PC

For a PC that stays on. Open **PowerShell** (right-click Start, Terminal or Windows PowerShell) and paste:

```
irm {{INSTALL_PS1}} | iex
```

It installs Java through winget if needed, downloads the current release and checks its checksum, registers a scheduled task that starts your account at logon and keeps it running, starts it, pairs this PC as the first device, and ends with your address and the invite for your phone on the clipboard. Details and what to do next: [Run your own account](your-account.html).

> The Windows installer has not yet been exercised on a Windows machine by the authors. If it stops somewhere, the message says what to do; please also [report it]({{ISSUES}}).

## If it goes wrong

- **SmartScreen blocks the download itself.** In the browser's download list choose Keep, then Keep anyway.
- **"MaximaNode is already running".** Only one copy runs at a time; look in the system tray.
- **The seed window never appeared.** It shows only on the first launch; the words are in `C:\Users\<you>\.maxima\seed.txt`.

Everything else: [Help](help.html).
