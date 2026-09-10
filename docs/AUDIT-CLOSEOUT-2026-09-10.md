# Parlons family audit closeout — 10 September 2026

**Status: this audit pass is closed at the owner’s requested boundary.**

The owner moved this audit into closeout: finish the gateway/relay repair already underway, validate it, propagate affected dependencies, and stop opening new audit branches. The detailed findings and per-repair evidence remain in [the audit record](AUDIT-2026-09-09.md).

**Wake-proxy centralisation remains unresolved. Decentralisation is not fully satisfied or certified by this audit.** The proxy's queue, response and rate-limit repairs improve reliability; they do not settle the architecture or its operator/APNs dependencies. Address that issue in a separate subsequent goal. No new wake architecture work is included in this closeout.

## Coverage

The review mapped the whole Parlons family: shared Java protocol/core, relay server, account layer, Node and Cloud, Android app and portal, standalone Swing desktop, classic Maxima compatibility module, optional wake proxy, Swift ParlonsKit, iOS app and notification extension, and the minimaCore Desktop/minimaDesk integrations and dependency packaging.

Detailed review and regression work concentrated on transport and lifecycle ownership, directory availability and independent relay resolution, mailbox delivery and acknowledgements, persistence and wallet counters, pairing and RPC, bounded queues/state, the shared account panel, native settings and polish. The embedded Minima fork was inspected as an integration dependency; it did not need a source change for these repairs. This was not an exhaustive proof of every source line, cryptographic construction, consensus path or end-to-end feature.

## Completed repairs

| Area | Result |
| --- | --- |
| Wallet counters and account persistence | Shared durable file-counter implementation; malformed state fails explicitly; signing reservations persist before use; pairing changes roll back on failed persistence; private atomic account writes. |
| Mailbox and storage | Ordered delivery completion before cumulative deletion; unreadable/pending mail blocks later acknowledgements; durable cursor allocation survives recipient removal; write failures release reservations; binary records validate keys, lengths and ciphertext hashes; bounded reads; damaged text does not silently become empty state. |
| Transport, RPC and lifecycle | Java/Swift interruption and cancellation ownership; stale callbacks cannot retire replacements; shutdown stops new admission; bounded pending RPC/ACK histories; safe timeout arithmetic; response/expiry completion isolation; late remote/cache work cannot overwrite newer sessions. |
| Relay independence and discovery | Signed directory replicas preserve local publisher capacity; permanent-address fallback works when the anchor is unreachable; independent-relay outage test disables built-in bootstrap and checks quorum plus encrypted delivery; probe ownership, false self-connection verification and discovery persistence races repaired. |
| iOS background work | Shared relay preferences reach the notification extension; expiry/completion handling and full catch-up deadlines; cache snapshot and refresh ownership; mailbox challenge validation and timestamp admission. |
| Wake reliability | Bounded account/proxy work and retained state; backoff rechecked for queued work; unused response bodies closed; APNs response collection/waits bounded; complete token digests isolate rate keys; capacity no longer clears active allowances. Centralisation remains unresolved. |
| Final gateway/relay repair | Gateway capacity retains active token buckets; relay minute tables enforce their stated cap; atomic admission/cleanup; probe helper reused; counters cannot wrap through refused requests; all relay rate tables participate in maintenance. |
| Cohesion and polish | Shared file-counter adapter and common account panel; stale-view protection, error/retry feedback, keyboard/focus support, contrast and narrow-layout fixes; desktop session reload handling; iOS settings correctness; installed iPhone version shown opposite relay/connection status. |

The final repair reproduced six failures against the original code. Its 12 regression cases pass, including concurrent admission, partial refill retention, expiry recovery and clock-rollback retention. Limits remain local to each operator; this repair introduces no central admission service or protocol change. At capacity, a legitimate new key can be refused until safe reclamation. Relay recovery depends on its existing maintenance loop. Record-count caps are not total heap-byte bounds or fairness/Sybil guarantees.

## Validation

| Evidence | Result and boundary |
| --- | --- |
| Final local JVM family gate | **424 tests**, zero failures/errors/skips: core 253, relay 70, Cloud 64, Node 10, classic maxjar 1, wake proxy 20, standalone desktop 6. Unchanged Gradle tasks may be up-to-date. |
| Wire compatibility | **63/63** golden vectors passed. |
| Final CI | Shared engine and panel workflow passed for the exact repair commit; includes the panel regressions. |
| Electron integration | **10/10** existing RPC/session checks passed across the two hosts. |
| Android/portal | Both release APKs built. Their unit-test tasks have no tests; no physical Android verification is claimed. |
| JVM artifacts | Node, Cloud, relay, Swing and wake artifacts built. Changed NodeGateway classes match the Node jar; changed RelayServer classes match Node, Cloud and relay jars. |
| Swift, earlier validated audit state | **94** package tests and **36** iOS simulator tests passed. No Swift change occurred in the final gateway/relay repair, so these were not rerun for closeout. |
| iPhone | Signed app and extension **0.1.31 build 29** were previously verified, installed and launched on the connected phone. The version label reads the installed bundle version. No phone operation was needed in closeout. |
| UI review | Shared-panel fake-data browser review covered conversation/settings/sheets, dark mode, keyboard dismissal and narrow layout. Earlier minimaDesk typecheck/Vite and changed minimaCore JavaScript checks passed. Full native visual and feature validation remains outstanding. |

Final repair source: `e55b1fbf6c5839ad072eb468978cdfb53402d79a`.

[Exact successful CI run](https://github.com/eurobuddha/maxima/actions/runs/34523086312).

## Versions and delivery status

| Variant | Final source version | Status |
| --- | --- | --- |
| Android Parlons | 0.6.119, code 719 | Release APK built; not installed on a phone or newly store-published by this pass. |
| Android Cloud portal | 0.2.66, code 110 | Release APK built; not installed or newly store-published by this pass. |
| Swing desktop | 1.5.98 | Jar built; no new signed/notarized installer release. |
| Parlons Cloud | 0.11.101 | Fat jar built; no live service deployment. |
| Parlons Node | 0.2.104 | Tested dependency release published; both Electron hosts pin and fetch it. |
| Relay server | 0.4.99 | Fat jar built; relay fleet not redeployed. |
| Optional wake proxy | 0.1.5 | Standalone release published and download verified in the preceding continuation; live proxy not redeployed. |
| iOS app/extension | 0.1.31, build 29 | Installed and launched as a development build; no App Store/TestFlight publication. |
| minimaCore Desktop | 0.16.78 | Node 0.2.104 dependency propagated in source and local resources; separate pre-existing pending working version remains 0.16.79. No new desktop installer release. |
| minimaDesk | 0.7.62 | Node 0.2.104 dependency propagated in source and local resources. No new desktop installer release. |

[Node dependency release](https://github.com/eurobuddha/maxima/releases/tag/node-v0.2.104), SHA-256:

`aa66cc38c78d68434faa4753a31251d5b2c66709bd67ba46e4e1b531d76ba71a`

[Standalone wake-proxy release](https://github.com/eurobuddha/maxima/releases/tag/wake-v0.1.5), SHA-256:

`8a480073e3873abd7cc631f5515826b8a31fd4292b6ac9ec2f15e02128a5bd4c`

Desktop source commits:

- minimaCore Desktop: `29c5f11d646a48baabef8fee526ff04a517d7bd4`.
- minimaDesk: `81bb89e51440b963c4ec07b81af03846895097b8`.

Both resource jars match the tested release byte-for-byte. Both commits change only the two package manifests; unrelated source changes and the pending minimaCore version were preserved.

Desktop propagation uses the existing checksum-verifying fetch scripts. Source/dependency preparation does not update already installed desktop apps. A desktop distribution release still requires all three platforms and signed, notarized and stapled Mac packaging. No funded transfer, live identity copy or fleet rollout was used for testing.

## Unresolved issues and deferred work

1. **Separate subsequent goal: wake-proxy centralisation.** Establish and implement an architecture that meets the owner's decentralisation requirement, including the wake operator and APNs dependencies, replacement/self-hosting behavior and failure modes. This remains unresolved despite the completed reliability fixes.
2. **Unverified end-to-end behavior:** live APNs HTTP/2/TLS delivery and connection reuse; iPhone startup latency; complete calls/media/payment flows; native visual QA; cross-language request/receipt/pairing cases beyond existing wire fixtures. No funded payment execution was performed.
3. **Persistence and lifecycle review targets:** remaining file-key representation/size and append/delete durability edges; power loss or external rollback; cross-process iOS account/cursor coordination; RPC restart/idempotency and total work across retired peers; overlapping drains, reconnect reconciliation and aggregate ACK/attachment limits. These are deferred review targets, not all established defects.
4. **Resource/adversarial review targets:** wake request-reader timeouts, gateway/relay throughput under sustained load, total byte budgets, discovery list bounds and poisoning/Sybil resistance, connection/executor shutdown under churn. The new admission bounds do not establish fairness or complete denial-of-service resistance.
5. **Test infrastructure:** older relay tests still use free-port close/rebind fixtures that have intermittently produced bind races. New independent-relay fixtures retain bound listeners; the older fixtures remain follow-up work. CI also reports deprecated action-runtime/setup-java versions; its final run passed, and action upgrades are deferred.
6. **Cohesion proposal:** a build-time compatibility manifest tying source commit, wire fixtures and bundled artifact hashes to host versions. It must not become a runtime registry or permission requirement for joining the network. Not implemented in this pass.

The pass ends at the owner's requested boundary. Its closure means the authorized in-flight repair, validation, dependency propagation and reporting are complete; it does not mean every bug is fixed or decentralisation is fully satisfied. No new audit branch is opened by this report.

## Subsequent authorised publication

The owner subsequently authorised publishing the completed session work. See [the publication report](PUBLICATION-2026-09-10.md) for the now-live store versions, desktop installers, IPFS snapshot and the remaining iPhone App Store Connect access blocker. The audit remains closed and wake-proxy centralisation remains unresolved.
