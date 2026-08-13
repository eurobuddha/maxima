# dist — released builds

Append-only. Every build that leaves this machine gets a version number and a
checksum here, matching the fleet convention in `apks/*/releases/`.

Verify before you run anything:

```bash
shasum -a 256 -c SHA256SUMS
```

## 0.1.1 — 2026-08-13

Server CLI fixes. **Upgrade from 0.1.0** — the old one had two real bugs.

| Artifact | Size | Status |
|---|---|---|
| `maxima-server-0.1.1.jar` | 136 KB | **tested** |
| `maxima-app-0.1.0-debug.apk` | 21 MB | unchanged; still never run on a device |

- `--help` / `-h` now prints usage. In 0.1.0 it printed nothing, fell through,
  and **generated a seed phrase on disk** — an informational flag creating
  wallet-grade material.
- A port already in use now explains itself instead of throwing a raw
  `BindException` stack trace, and suggests `--port 9501`.
- Unknown flags are an error. In 0.1.0 `--pot 9501` was silently ignored and the
  relay listened on the default port.
- `--version` prints the build version. The old `--version` (greeting string) is
  now `--protocol`.
- Port range is validated.

`--port` existed and worked in 0.1.0 — but with no `--help` and a stack trace on
a busy port, there was no way to discover that.

## 0.1.0 — 2026-08-13

| Artifact | Size | Status |
|---|---|---|
| `maxima-server-0.1.0.jar` | 136 KB | **tested** |
| `maxima-app-0.1.0-debug.apk` | 21 MB | **built only — never run on a device** |

### maxima-server-0.1.0.jar

Headless relay, directory and mailbox. Java 11+, zero third-party dependencies
(the whole jar is our own code plus the 13 KB BIP39 wordlist).

```bash
java -jar maxima-server-0.1.0.jar --port 9501 --data ~/.maxima
```

`--port` (default 9001) · `--data` (default `~/.maxima`) · `--rate` messages per
minute per destination (default 600)

On first run it generates a seed phrase, prints it once, and writes
`<data>/seed.txt` with mode 600. **That phrase is also a spendable Minima wallet
seed — back it up like money and never commit it.**

The port must be reachable from the public internet or the relay cannot relay
for anyone.

Verified: a full 19/19 Tier 1 session ran through this jar, it starts from an
otherwise empty directory with no classpath, and a **stock unmodified Minima
node sent a message through it successfully** (`delivered: true`).

### maxima-app-0.1.0-debug.apk

Android always-on transport. `com.eurobuddha.maxima.app`, versionCode 100,
minSdk 28, targetSdk 35.

```bash
adb install -r maxima-app-0.1.0-debug.apk
adb logcat -s MaximaService
```

**This has never been executed.** It compiles and packages; that is the entire
claim. The always-on machinery — `specialUse` foreground service, exact
allow-while-idle heartbeat, WorkManager belt, `NetworkCallback` re-dial — is
implemented but completely unverified against Doze, a real carrier, or OEM
battery killers.

It is **debug-signed**, so a later release build will not upgrade over it;
expect to uninstall first.

Grant the battery-optimisation exemption it asks for on first launch. Without
it the OS will not let the heartbeat restart the service and the transport
silently stops.

Known gap: there is no contact-exchange UI yet. The phone can run every Tier 1
service but you cannot yet add a contact from the screen, so it is suitable for
a soak test, not for messaging someone.
