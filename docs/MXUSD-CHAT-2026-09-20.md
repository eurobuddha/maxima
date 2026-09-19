# In-chat send value: MINIMA or MxUSD (2026-09-20)

"Send value" inside a Parlons chat was Minima-only — every layer hardcoded `0x00` / `"MINIMA"`.
It now offers two currencies: native Minima and **MxUSD**
(`0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90`). Not a token list: no
registry, no icons, no arbitrary-decimals UI. Minima is always preselected and nothing is
remembered between sends, so muscle memory cannot pay the wrong currency.

## How the currency travels

`ChatMessage` already encoded a `tok` field for `TYPE_PAYMENT`; it was simply never populated.
A payment BODY never crosses the wire (`ChatMessage.payment()` sets no body and the receiver
re-wraps one locally in `handlePayment`), so with exactly two currencies the token NAME already in
the body is a sufficient discriminator: `ChatPay.tokenId(body)` derives the id from it. The body
format is therefore unchanged — every payment already stored on disk keeps parsing, and an old peer,
which renders amount + name and nothing else, is unaffected. A third currency would need the id
carried explicitly (a 5th SOH field BEFORE the memo, `parse()` tolerating 4-field rows).

## Units — the part that moves money

A token coin's on-chain `amount` is Minima-scaled; `tokenamount` is the human value. **MxUSD's scale
is 36**, so 25 MxUSD is stored as 25e-36. `TxnFactory` takes RAW units and does no scaling, so
`TokenAmount.toRaw` converts once and that single value feeds both `CoinSelector.selectToCover` and
`buildSend` — the two can never disagree about units. It refuses rather than approximates: a
non-positive amount, a token the wallet holds no coins of, a coin with no `tokenamount`, and above
all an amount finer than the token's grain.

The scale is cross-checked against the node's own two views of the coin (`amount` scaled by
10^scale must equal `tokenamount`) rather than by recomputing the tokenid from the descriptor:
MxUSD's `token.name` is a JSON **object**, whose key order does not survive re-serialization, so an
id recompute would false-fail on the very token being added. The transaction bytes never depended on
the descriptor — `Coin.writeDataStream` carries only the tokenid, and outputs are set from the
caller's id via `resetTokenID`.

The node's own `send` does the same thing for the RPC path: `send.java:203` reads `tokenid:`,
`:479-483` scales with `getScaledMinimaAmount` and then round-trips with `getScaledTokenAmount`,
throwing "Invalid Token amount to send" if precision was lost. So the RPC/node path passes HUMAN
amounts plus `tokenid:`, and our local signers mirror the node's own guard.

## Command-injection guard (verified live)

The node's command parser is space-tokenised and LAST-WINS, so any value interpolated into a command
string must be validated first. Probed against the dev node at 127.0.0.1:4446:

```
send address:MxG085…H amount:1 tokenid:0x7D39…A90   -> "No Coins of tokenid:0x7D39…A90 available!"
send address:MxG085…H amount:1 tokenid:0x00 address:0xEVIL
                                                    -> "Invalid HEX string in decode16 : 0xEVIL"
```

The first proves `tokenid:` is accepted and fails on coins, not parsing. The second proves the
hazard is real: the SECOND `address:` won, and was only rejected for not being hex. Hence
`^0x[0-9A-Fa-f]{2,64}$` before interpolation in `NodeWallet.send` and `RpcAccountWallet.build`, plus
a two-currency allowlist at the `M_PAY` boundary. No `burn:` is ever appended to a token send.

## What shipped, per product

| product | version | what changed |
|---|---|---|
| `:core` | (shared) | `ChatPay` ids/labels + derived `tokenId()`/`nameFor()`/`isSendable()`; `ChatEngine` populates `tok` |
| Parlons Android | 0.6.130 / 730 | currency chooser in the chat pay sheet; `PaymentSender.send(..., tokenid, ...)`; ledger rows carry the tokenid |
| Cloud Android (portal) | 0.2.80 / 280 | currency chooser; pre-flights `M_PING mxusd` before offering MxUSD; ledger + `extract()` fix |
| Parlons Node | 0.2.114 | `M_PAY` tokenid (allowlisted), `M_PING mxusd:true`, `NodeWallet.send(..., tokenid)`, panel payment bubbles |
| Cloud host | 0.11.110 | `AccountWallet.build(to, amount, tokenid)` fail-closed default + 3 impls, `CloudPaymentSender` scaling, coin-lag fix |
| Standalone Parlons Desktop | 1.5.104 | currency chooser in the Swing chat payment dialog |

Also fixed, because a second currency makes them load-bearing:

- **`verifyIncomingPayment` matched address + amount only** — a 5 MINIMA output would have ticked
  "✓ Confirmed" on a 5 MxUSD claim, which is the badge a receiver ships goods on. It now matches the
  tokenid too, via `TokenAmount.paysUs`. `hasIncomingCoin` (no callers, same bug in its purest form)
  was deleted.
- `CloudAccountWallet.upkeep` only noticed gateway coin lag on the `0x00` row, so a token coin the
  gateway holds no proof for would sign and then die at `txnbasics`.
- `CloudWalletPage.extract()` fell back to the FIRST balance row when no `0x00` row existed,
  labelling a token balance as the Minima balance.

## Tests

469 JVM tests + `ChatTest` 40 + panel 12, all green:
`:core:test` 266 (+10 `ChatPayTest`), `:core:parityTest`, `:cloud:test` 92 (+15 `TokenAmountTest`,
+5 `ChatPayCurrencyTest` driving the real `M_PAY` handler, +3 `RpcAccountWalletTest` pinning the
exact command strings), `:server:test` 73, `:maxjar:test` 1, `:app:testDebugUnitTest` 19,
`:portal:testDebugUnitTest` 18, `node --test account/src/test/js/panel.test.cjs` 12.

**Still required: a live two-device MxUSD send between fresh synthetic identities, plus a Minima
send to prove no regression, and the same MxUSD send to an un-updated peer.** Local tests cannot
prove that; it needs funded MxUSD and two phones.

## Panel: display only, on purpose

The web panel (the Parlons UI inside both Electron desktops) rendered payment bodies as raw SOH
control characters; it now shows amount + currency, the memo and the txid **in full**. Sending from
the panel is still refused: `ParlonsLocal.ALLOWED` deliberately keeps "wallet sends, payments … on
paired phones and the CLI — a cookie on a desktop is weaker than a phone in your hand", and the owner
chose on 2026-09-20 to keep that boundary. Desktop users see payments in both currencies and send
from the phone or CLI.

## Fleet update — NOT done

Every Parlons Node box was found running an older version, so this roll carries the whole pending
backlog, not just MxUSD. `deploy-parlons-node.sh` rewrites the systemd unit **from its CLI flags on
every run**, so an omitted flag silently reconfigures a box: recover each box's flags from its live
`ExecStart` first, then re-run with `--jar` plus exactly those flags, one box at a time, verifying
with `ops/verify-relay.sh` before the next. Rollback is cheap — `/opt/maxima/parlons-node.jar` is a
symlink to a versioned jar, so relink and restart.

Per-box commands (they carry host detail, so they stay out of this repo) were handed to the owner
directly; the fleet inventory lives in the `maxima-relay` runbook. The Pi is deliberately excluded
until its own unit is read: it runs a much older node in a different shape and also serves
apache/ipfs/KeyUses behind an active ufw. Do **not** run `deploy-relay.sh` or `/maxima-relay update`
against any node box — it re-enables the old relay on the port the node now holds.

Cloud / tenant hosts take `cloud/build/libs/parlons-cloud.jar` (`parlons-cloud 0.11.110`) via
`ops/deploy-parlons-cloud.sh` / `ops/deploy-parlons-tenants.sh`.

## The two Electron desktops

They do not carry the panel: each pins a published Parlons Node release
(`package.json` `parlonsNode`, currently `0.2.112` in minimacore-desktop and `0.2.108` in
minimaDesk) and `scripts/fetch-parlons-node-jar.sh` downloads it with a SHA256 check. So they need,
in order: a `node-v0.2.114` GitHub release, the pin bumped in each app, then each app's own
`scripts/release-desktop.sh` — which means a **signed, notarized** Mac DMG. Mac notarization
authorization was still an open blocker in `CLAUDE-HANDOVER-2026-09-16.md`; nothing here changes that.

Do not launch minimaDesk on the build Mac to test: it hosts the slush node and real funds.
