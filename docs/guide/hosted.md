# Hosted accounts

Not everyone has a machine that stays on. Anyone who does can run a **Parlons host**: one process that keeps many separate accounts online, one per person. A hosted account is a whole Parlons account with its own identity, contacts, chats and paired devices; the difference is who runs the machine.

## Getting a hosted account

1. Ask someone who runs a host. They give you an **invite**: a QR or a line starting `MAX#` that carries your new account's address and a one-time pairing code.
2. On your phone, open Parlons Cloud (Android) or Parlons (iPhone), tap **Scan account QR**, scan or paste the invite, tap **Connect & pair**.
   A valid code pairs immediately and is consumed. You do not need approval from another device. Without a valid code, the existing pending-device approval flow still applies.
3. Give yourself a name (Settings on the iPhone, the Node tab on Android). Share your address from the Contacts tab. You are in.

## What the host can and cannot do

- **Cannot** read your messages in transit or anyone else's: everything between devices is sealed end to end.
- **Can** read what is stored on that machine: your account keeps your chat history and its keys there, so the operator has the same access to them that you would have on your own machine. Choose a host you trust the way you would trust someone holding your mailbox.
- Seeds on a host are encrypted at rest under a passphrase the operator holds, which protects against a stolen disk, not against the operator.

## Leaving

At any time: Settings, **Back up account** (iPhone) or the Node tab (Android) writes an encrypted bundle of the whole account. Restore it on your own machine as described in [Run your own account](your-account.html). Your address stays the same, so your contacts and your paired devices carry on without noticing. Then ask the host to stop the old copy.

---

## Hosting accounts for others

### From a Parlons Node owner account

Node **0.2.118**, the hosted-account service **0.11.112**, and Android Parlons Cloud
**0.2.80** add guest management to the owner's Node tab. Each guest has a separate
account. Their invitation gives no access to the owner's account, Minima terminal,
or hosting controls. A guest can manage their own account and pair their own devices.

1. Install Node 0.2.118 and its `parlons-pair` helper. For an existing server,
   preserve its current startup flags and data when updating.
2. From your computer, install the hosted-account service beside it:
   ```bash
   ops/deploy-parlons-tenants.sh root@your.box \
       --jar dist/parlons-cloud-0.11.112.jar --manage-from-node
   ```
   This adds a Node service drop-in and restarts Node. It preserves Node's existing
   command line. Back up `/etc/parlons-tenants.env` and the hosted account data.
3. In an interactive SSH terminal, set your admin pairing password once:
   ```bash
   sudo parlons-pair --set-admin-password
   ```
4. Run `sudo parlons-pair`. Enter the admin password for an owner invitation, or
   press Enter and choose a guest account name for a guest invitation. Explicit
   commands are `sudo parlons-pair --admin` and `sudo parlons-pair --guest alice`.
   An incorrect password fails; it never issues an owner code.
5. Scan the resulting QR or paste the complete invite on the receiving phone.
   A valid owner or guest code connects immediately, with no second approval.

An already-paired Android owner can use **Node → Hosted accounts → Manage hosted
accounts** to create an account, refresh until it is running, and show its invitation.
Pause and Resume retain its identity and chats. **Pair my other device** requests
the admin password before issuing an owner invite; leaving it blank opens guest
management. The password is checked on the server and is not saved on the phone.

Invitation issuance is available through owner controls and SSH; it is not an open
registration endpoint. The password controls the normal owner-invitation flow.
Someone who already has full SSH or owner terminal access still controls the server.
Existing owner devices remain owners after upgrading. An older iPhone client can
scan either invite; use SSH to issue owner invitations from an older client that
does not have an admin-password field.

Hosting must be enabled before a guest invite can be issued. The default creation
limit is ten accounts, including paused accounts. Node's `parlons.hosted.limit`
system property can change its management limit. The local `--tenant-new` command
uses the same default; pass the same JVM property there if you raise the limit.

This release restores the guest-hosting workflow. Connections still use the
existing relay fleet; it does not yet provide a direct-only connection to the host.

### Standalone host

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
