# Parlons on Linux

Two things a Linux machine can be. **Parlons Desktop** is the full app for a desktop Linux: your identity lives on this machine. **A Parlons account** is a small always-on program, ideal for a Raspberry Pi, a home server or a VPS, which your phones pair to. You can run both.

## Parlons Desktop

A 64-bit x86 package for Debian and Ubuntu (and their relatives). It brings its own Java.

### Install

```
wget {{DESKTOP_DEB}}
sudo apt install ./maximanode_{{DESKTOP_VERSION}}_amd64.deb
```

It appears in your applications menu as MaximaNode; the launcher is `/opt/maximanode/bin/MaximaNode`.

### First launch

The app creates your identity and shows a window titled **Back up your Maxima seed** with your 24 words. Write them down now; they are also saved, readable only by you, at `~/.maxima/seed.txt`. Then you are on the Chats tab.

Give yourself a name: the three-dot menu at the top right opens **Settings**; the first field is **Your name**.

### Your address, and adding people

- **Contacts** tab, card **Your Maxima address**, button **Share my address**: a QR for a phone to scan and the full address to copy.
- **Add contact**: paste the other person's address (`Mx…@host:port` or `MAX#…`) and click **Introduce myself**. There is no camera on the desktop; people scan you, you paste them. They appear once their device answers.

### Help the network

**Network** tab. **Check / make me reachable** asks your router for a public port (9536) and proves it from outside before advertising it; if the router refuses, **Set up manual port-forward** tells you the exact rule. **Run as a relay** carries other people's sealed traffic on port 9535. Nothing listens until you turn one of these on.

### Back up, update, data

- Settings, **Keys & security**, **Show seed phrase**; or copy `~/.maxima/seed.txt`.
- Update by installing the new package over the old one. Your data in `~/.maxima` stays.
- No ARM build of the desktop yet; a Raspberry Pi runs an account instead (below).

---

## Run a Parlons account on this machine

Any Linux that stays on: a Raspberry Pi, a home box, a VPS. As yourself (with sudo available) or as root:

```
curl -fsSL {{INSTALL_SH}} | bash
```

It installs Java if needed (apt, dnf, apk or pacman), downloads the current release and checks its checksum, registers a systemd service that survives reboots (a user service with lingering when you run it as yourself, a system service as root), starts your account, pairs this terminal as the first device, and ends with your address, a QR to scan from a phone, and the `parlons` command.

On a VPS with a public address, add `--relay` to also carry sealed traffic for others on port 9501:

```
curl -fsSL {{INSTALL_SH}} | bash -s -- --relay
```

Details and what to do next: [Run your own account](your-account.html).

## If it goes wrong

- **"This step needs root and there is no sudo here."** Run the command as root, or install Java first (`apt-get install default-jre-headless qrencode`) and run it again as yourself.
- **The desktop package will not install.** It is for 64-bit x86 only; on ARM run an account instead.
- **The account stops when I log out.** The installer enables lingering; if your distribution refused it, run `sudo loginctl enable-linger $USER` once.

Everything else: [Help](help.html).
