# MxUSD payments in every Parlons build, and Ukrainian — 22 October 2026

## What changed

MxUSD, the Minima dollar-pegged token, can be sent everywhere Parlons sends Minima. Its token id:

```
0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90
```

- One shared engine, `com.eurobuddha.wallet.TokenInputs`, is used by the phone, desktop and cloud signers. It scales display amounts with the token's own scale. Every token input is fetched byte-exact with `coinexport` and never rebuilt from the `coins` listing. `TxnFactory.assertTokenIntegrity` refuses a mismatched token before a one-time key is reserved.
- Node-backed wallets (Parlons Node, RPC accounts) send with `send ... tokenid:`.
- The `parlons.chat.pay`, `parlons.wallet.send` and `parlons.wallet.buildsend` RPCs take an optional `tokenid`. Only `0x00` or a full 32-byte id is accepted, because the value reaches a node command.
- Android, Cloud Portal and Desktop offer Minima or MxUSD in both the wallet send sheet and the in-chat payment. The browser panel and iOS have no payment send UI.
- The Cloud Portal wallet no longer shortens counterparty addresses.

Ukrainian (`uk`) is a fourth language beside English, Simplified Chinese and Russian. All 3723 catalog messages are translated, in Android, Cloud Portal, Desktop, the browser panel and iOS. Ukrainian shares Russian's plural rules. The 0.6.144 MxUSD strings had been hand-edited into generated XML. They now live in `localization/catalog.json`, so `generate.cjs --check` and the panel i18n test pass again.

## Published versions

| Product | Version | Delivery |
|---|---|---|
| Parlons Android | 0.6.145 / 745 | GitHub, shared store |
| Cloud Portal Android | 0.2.89 / 289 | GitHub, shared store |
| Parlons Desktop | 1.5.115 | Mac (signed, notarized), Windows, Linux; shared store |
| Parlons Node | 0.2.135 | Versioned jar and checksum on GitHub |
| Parlons Cloud host | 0.11.121 | Versioned jar and checksum on GitHub |
| minimaCore Desktop | 0.18.21 | Mac, Windows, Linux; update feed; shared store |
| minimaDesk / MinimaClassic Desktop | 0.7.82 | Mac, Windows, Linux; update feed; shared store |
| Parlons iOS | source only | parlons-ios master 536fab4, no new build |

Both desktop wrappers pin Parlons Node 0.2.135, SHA-256 `5fc20f20a30328c1e2bd9863a9b585d2f85b71badae18456dcdb65e78d2003aa`.

Running account hosts were not upgraded. Cloud accounts need Cloud 0.11.121, and Portal users paired to a node need Node 0.2.135, before MxUSD sends work from the Portal.

## Verification

- Cloud 117, account 6, panel i18n 12, node 22, desktop 54, Android app 56, Portal 83 tests pass. New tests cover the mismatched-token refusal with zero keys used, the coinexport round-trip, a token send that signs once and conserves the token, the shared resolver, and RPC tokenid validation.
- iOS LocalizationTests, ErrorDisplayTests and BackgroundCompletionTests pass (22) on an iOS 26.5 simulator. SessionLifecycleTests fail with or without this change.
- Android 0.6.145 was installed on the Fold. The UI showed Ukrainian, and the send sheet offered Minima and MxUSD. No payment was sent.
- No real MxUSD transaction was sent from any build.

Source: maxima `c3670c21` on main.
