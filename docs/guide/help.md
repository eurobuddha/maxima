# Help and troubleshooting

Parlons is open source and community-run; support is this page, the platform pages, and the [issue tracker]({{ISSUES}}). There is no company help desk because there is no company account to look up.

## Messages

**A contact never appears after "Introduction sent".** Adding someone is a handshake. Their device must be on and reachable to answer; if it stays off, the introduction waits. If you both keep waiting, one of you should add the other from their side.

**Messages arrive late, or only when I open the app.**
- Android app: Settings, Node & power, Fix battery settings; on the Network tab check at least one host is connected.
- Android Parlons Cloud: Settings, This device, Keep running in the background.
- iPhone: Settings, Notifications, the wake switch is on, and iOS allows Parlons notifications. A phone without a SIM on a router that drops idle connections gets every app's pushes late; try another network to tell the two apart.
- The account itself: is it running? `parlons status` on its machine, or the Node tab on Android.

**A tick never turns double.** The other device has not confirmed receipt yet: it is off, or their app is being killed in the background. The message is not lost; it waits.

**"Not connected" or "offline" in the app.** No relay is holding a connection for you right now. The apps reconnect on their own; if it lasts, check the Network tab (Android, desktop) or the relays list (iPhone) and add a relay you know.

## Accounts

**"Could not resolve the cloud account".** The account is not attached to a relay: it is stopped, has no internet, or just started. On its machine `parlons status`; a Node: `journalctl -u parlons-node -f`.

**The installer stopped.** It says why and what to do; run it again afterwards. Nothing you had is lost. See the "If it goes wrong" list on [Run your own account](your-account.html).

**I want the account on a different machine.** Back it up as a bundle, restore it there, stop the old one. Same address, devices reconnect. Steps on [Run your own account](your-account.html).

## Identity

**Where are my 24 words?** Android app and desktop: Settings, Keys & security, Show seed phrase; an account: `seed.txt` in its data folder, or Settings, Show seed phrase on a paired phone. Never in any log.

**I lost my phone.** See [Security](security.html), section Losing things.

## Where the logs are

| | |
|---|---|
| Android app | Network tab, Event log |
| Android Parlons Cloud | Node tab, Logs |
| iPhone | Settings, Log (no message content) |
| Desktop | Network tab, event log at the bottom |
| An account you run | `parlons log`, or `~/.parlons/log/cloud.log` |
| A Parlons Node | `journalctl -u parlons-node -f` |
| A host | `journalctl -u parlons-tenants` |

## Reporting

[Open an issue]({{ISSUES}}) with the platform, the version (Settings, About), what you expected, what happened, and the relevant log lines. Logs never contain your messages or your words; do check before pasting anyway.
