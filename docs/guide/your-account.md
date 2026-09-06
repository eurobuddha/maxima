# Run your own Parlons account

A Parlons account is a small program that keeps your identity online: it holds your contacts and chat history, receives messages while your phone is off, and lets every device you pair see the same conversation. It is what an iPhone pairs to, and what makes one identity work on several devices. It needs a machine that stays on and has outbound internet. It does not need a public address or any port forwarding.

## You will need

- A machine that stays on: a Raspberry Pi, a Mac mini, an old laptop with the lid closed, or a small VPS (1 GB of RAM is plenty).
- Java 11 or newer. The installer adds it if it is missing.
- Five minutes.

## Install

**Mac, Linux, Raspberry Pi, VPS.** Open a terminal and paste:

```
curl -fsSL {{INSTALL_SH}} | bash
```

**Windows.** Open PowerShell and paste:

```
irm {{INSTALL_PS1}} | iex
```

Either one installs Java if needed, downloads the current Parlons Cloud release (version {{CLOUD_VERSION}} today) and checks its checksum, registers a service that starts at login and survives reboots, starts your account, pairs the terminal you are in as the first device, and ends with this:

```
  Your Parlons account is running (Parlons Cloud 0.11.40).

  Address (share it; it never changes):
  MAX#0x30819F300D06092A864886F70D010101050003818D0030818902818100D696B38C553EDD4C24F24F778097CA5409ADBC41D0C1230ED7014D12C3BF55630A1D1FFFC30DF1E72D542A9E3C4987FB77477A1CEC55EC62CEC826478FE87BD01532F43633118F4415CD9C1D45A74FCE6FFE1F5C5F16C9163CF99036AE5015E8DAFC694366A3DD7B0DCB12BB57561F6B40F52C1EEF9E33A62BCB4CADD0A528730203010001#MxG18HGG6FJ038614Y8CW46US6G20810K0070CD00Z83282G60G1N6GADSYR77EHV3BAHBTFEJKHWG32CCGFZSAVJGMBZ6G2U51H933ZM4B9GQFUCVFG3TS0M81BCPWC7CQTFMTMGYV07U3AFBM7FJMQ71HYPVEG0VQGYN20N3ACMETUZ3WN8Y1PJF6226NZES69TJ1JYM4RDP2SD07A5UAVB53Q22BSHCTBJ7JP4D21Z0CGUZA0QYWGYNHBNEQ0C10608006B4GMP9@45.77.246.226:9501

  Pair your phone: open the Parlons Cloud app, tap Scan account QR, point it at this:
  [a QR code, drawn in the terminal]
  or paste the invite (the code half works once):
  MAX#0x30819F300D06092A864886F70D010101050003818D0030818902818100D696B38C553EDD4C24F24F778097CA5409ADBC41D0C1230ED7014D12C3BF55630A1D1FFFC30DF1E72D542A9E3C4987FB77477A1CEC55EC62CEC826478FE87BD01532F43633118F4415CD9C1D45A74FCE6FFE1F5C5F16C9163CF99036AE5015E8DAFC694366A3DD7B0DCB12BB57561F6B40F52C1EEF9E33A62BCB4CADD0A528730203010001#MxG18HGG6FJ038614Y8CW46US6G20810K0070CD00Z83282G60G1N6GADSYR77EHV3BAHBTFEJKHWG32CCGFZSAVJGMBZ6G2U51H933ZM4B9GQFUCVFG3TS0M81BCPWC7CQTFMTMGYV07U3AFBM7FJMQ71HYPVEG0VQGYN20N3ACMETUZ3WN8Y1PJF6226NZES69TJ1JYM4RDP2SD07A5UAVB53Q22BSHCTBJ7JP4D21Z0CGUZA0QYWGYNHBNEQ0C10608006B4GMP9@45.77.246.226:9501?code=QRQH-UMMV-N8KS

  BACK UP YOUR SEED NOW:  ~/.parlons/seed.txt  -  it is your identity AND a wallet.
```

(That is a real, now discarded, test account. Addresses are long, and they are always shown whole.)

## Pair your devices

The **invite** is your address plus a one-time pairing code. Scan it from the phone, or paste it: on Android install [Parlons Cloud]({{PORTAL_APK}}), on an iPhone the Parlons app; both have **Scan account QR** on the first screen and a Paste button. Tap **Connect & pair**.

Each further device needs a fresh code. Either:

- on the machine: `parlons newcode` prints a new invite and QR, or
- on a paired phone: Settings (iPhone) or the Node tab (Android), **New pairing code**, or
- pair with no code at all: the new device shows "waiting for approval" and any paired device can approve it.

`parlons devices` lists what is paired; `parlons revoke <key>` cuts a lost one off. Revoking changes nothing about your identity.

## Back up the seed

`~/.parlons/seed.txt` (on Windows `C:\Users\<you>\.parlons\seed.txt`) holds your 24 words. They are your identity and a Minima wallet: copy them to paper and keep the paper safe. The installer never prints them.

## Every day

| | Mac / Linux | Windows |
|---|---|---|
| Is it running? | `parlons status` | `parlons status` |
| Watch the log | `parlons log` | `parlons log` |
| Stop / start | `parlons stop`, `parlons start` | `parlons stop`, `parlons start` |
| Update to the newest release | `parlons update` (or run the install command again) | run the install command again |
| Remove the service, keep the data | `parlons uninstall` | `.\get-parlons-cloud.ps1 -Uninstall` |
| Where things live | `~/.parlons` (data), `~/.parlons/bin` (program) | `%USERPROFILE%\.parlons` |
| The service itself | Mac: a launchd agent; Linux: `systemctl --user status parlons-cloud` | Task Scheduler, task "Parlons Cloud" |

`parlons` also drives the account like a phone would: `parlons contacts`, `parlons chats`, `parlons send <contact key> hello`, `parlons name "Alice"`, `parlons seed`, `parlons backup account.pbk`. Run `parlons` alone for the list.

## On a VPS: also be a relay

A machine with a public IP can carry sealed traffic for other people, which is what keeps the network independent. Install with `--relay`:

```
curl -fsSL {{INSTALL_SH}} | bash -s -- --relay
```

That keeps a relay open on TCP port 9501 (the installer opens it in ufw when ufw is active; a cloud console firewall must allow it too). Everything else is the same. If you would rather have the full package on a server, a Minima node, a relay and a wallet gateway included, see [Parlons Node](node.html).

## Move the account to another machine

`parlons backup my-account.pbk` writes everything (identity, devices, contacts, chats, settings) into one file encrypted with a passphrase you choose. Copy that file to the new machine. There, restore it **before** running the installer, into the folder the installer will use:

```
mkdir -p ~/.parlons
curl -fsSL -o ~/parlons-cloud.jar "$(curl -fsSL https://api.github.com/repos/eurobuddha/maxima/releases | grep -oE 'https://github.com/[^"]+/cloud-v[^"]+/parlons-cloud-[0-9.]+\.jar' | head -1)"
java -jar ~/parlons-cloud.jar --data ~/.parlons --restore my-account.pbk
curl -fsSL {{INSTALL_SH}} | bash
```

The restore refuses to run over an existing identity, so it cannot overwrite an account by accident. The address stays the same, so every paired device reconnects on its own. Then stop the old machine for good: one identity must run in one place.

## If it goes wrong

- **"Not up after 90 s".** The last log lines are shown; most often Java did not install or the machine has no internet. Fix that and run the installer again; nothing is lost.
- **"Could not reach GitHub".** Try again later, or download the jar from the [releases page]({{RELEASES}}) into `~/.parlons/bin` and run the installer again.
- **The phone says "Could not resolve the cloud account".** The account is off or not attached yet: `parlons status` on the machine.
- **I ran it twice.** Fine: the second run updates and restarts, and prints a fresh invite.
- **Port 9501 is already in use** (only with `--relay`). Another relay or node is on this machine; install without `--relay`.

Everything else: [Help](help.html).
