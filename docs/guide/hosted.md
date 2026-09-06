# Hosted accounts

Not everyone has a machine that stays on. Anyone who does can run a **Parlons host**: one process that keeps many separate accounts online, one per person. A hosted account is a whole Parlons account with its own identity, contacts, chats and paired devices; the difference is who runs the machine.

## Getting a hosted account

1. Ask someone who runs a host. They give you an **invite**: a QR or a line starting `MAX#` that carries your new account's address and a one-time pairing code.
2. On your phone, open Parlons Cloud (Android) or Parlons (iPhone), tap **Scan account QR**, scan or paste the invite, tap **Connect & pair**.
3. Give yourself a name (Settings on the iPhone, the Node tab on Android). Share your address from the Contacts tab. You are in.

## What the host can and cannot do

- **Cannot** read your messages in transit or anyone else's: everything between devices is sealed end to end.
- **Can** read what is stored on that machine: your account keeps your chat history and its keys there, so the operator has the same access to them that you would have on your own machine. Choose a host you trust the way you would trust someone holding your mailbox.
- Seeds on a host are encrypted at rest under a passphrase the operator holds, which protects against a stolen disk, not against the operator.

## Leaving

At any time: Settings, **Back up account** (iPhone) or the Node tab (Android) writes an encrypted bundle of the whole account. Restore it on your own machine as described in [Run your own account](your-account.html). Your address stays the same, so your contacts and your paired devices carry on without noticing. Then ask the host to stop the old copy.

---

## Hosting accounts for others

You need a Linux box that stays on (a VPS or a home server; 512 MB of RAM covers about ten accounts) and the Parlons repository on your own computer.

1. Install the host on the box (it opens no inbound port and can sit next to anything else):
   ```
   ops/deploy-parlons-tenants.sh root@your.box
   ```
   It creates the passphrase file `/etc/parlons-tenants.env` on the box; back it up, the accounts' seeds are encrypted under it.
2. Make an account for someone:
   ```
   ops/tenant-new.sh root@your.box alice
   ```
   It prints Alice's address, her one-time code and the invite in one line. Turn the invite into a QR with any QR maker, or send her the text.
3. Repeat step 2 per person. New accounts start within five seconds; nothing restarts. To pause one, on the box: `touch /var/lib/parlons-tenants/alice/.stop` (remove the file to start it again).

The host's log is `journalctl -u parlons-tenants`. Updating is running step 1 again. Every account can leave as a bundle at any time; that is the deal.
