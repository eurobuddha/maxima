# Graph Report - maxima  (2026-08-18)

## Corpus Check
- 247 files · ~196,335 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 3174 nodes · 9258 edges · 128 communities (95 shown, 33 thin omitted)
- Extraction: 86% EXTRACTED · 14% INFERRED · 0% AMBIGUOUS · INFERRED: 1270 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `696b68a4`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MaximaNode
- ChatActivity
- MiniData
- DirectReachability
- java.io.DataInputStream
- ChatEngine
- MaximaIdentity
- .render
- BlobStore
- Kit
- Capabilities
- RelayServer
- .serialise
- HostConnection
- ContactsPage
- .build
- Coin
- MMRData
- android.view.View
- .postConversation
- Transaction
- Group
- .main
- WalletPage
- TreeKey
- MiniNumber
- .main
- MainActivity
- MMR
- MiniData
- ReachabilityManager
- MaximaWallet
- DirectEndpoint
- .main
- android.content.Context
- MiniString
- Tier1Services
- .build
- MMREntry
- Maxima — an annotated walkthrough
- .main
- Witness
- StateVariable
- .main
- TxPoW
- NodeLink
- ChatsPage
- AppLock
- WalletPublisher
- Token
- Hex
- MiniByte
- Mailbox
- DesktopMain
- .showTokenDetail
- Signature
- FileStore
- .start
- UpnpIgd
- RpcPeer
- RelayRuntime
- ChatPrefs
- GatewayNode
- MMREntryNumber
- MlsStore
- PaymentSender
- MaxTxPoW
- .main
- JSONWriter
- NatPmp
- .log
- Yylex
- PortMapper
- android.content.SharedPreferences
- .run
- JSONParser
- MaximumMessage
- .startPumping
- LanDiscovery
- SeedStore
- .redeliver
- MiniNumber
- ItemList
- .mark
- Phases
- .onCreate
- Util
- .parse
- CoinSelector
- Classic Maxima — complete feature audit
- BIP39
- Maths
- ParseException
- .resolve
- The phone app and contacts suite — design
- .deliver
- WalletLedger
- MiniFormat
- MLSPacketGETResp
- AutoStart
- The desktop node — set-and-forget relays for everyone
- ManualForward
- RelayStore
- .install
- ContainerFactory
- Maxima for Minima Core — what this is
- MlsStore
- Presence
- SeedCrypt
- Result
- Result
- .readDataStream
- .readDataStream
- Threat model and residual risks
- gradlew
- Chat
- MaximaApiMessages
- maxima — working rules
- install.sh
- pre-commit
- deploy-relay.sh
- verify-relay.sh

## God Nodes (most connected - your core abstractions)
1. `MaximaNode` - 177 edges
2. `MiniData` - 149 edges
3. `MiniNumber` - 111 edges
4. `MiniData` - 101 edges
5. `MaximaIdentity` - 101 edges
6. `ChatEngine` - 84 edges
7. `ChatActivity` - 67 edges
8. `JSONObject` - 64 edges
9. `WalletPage` - 63 edges
10. `Contact` - 63 edges

## Surprising Connections (you probably didn't know these)
- `AndroidContribution` --implements--> `ContributionPolicy`  [EXTRACTED]
  maxima/app/src/main/java/com/eurobuddha/maxima/app/AndroidContribution.java → maxima/core/src/main/java/com/eurobuddha/maxima/core/services/ContributionPolicy.java
- `MaximaService` --references--> `AndroidContribution`  [EXTRACTED]
  maxima/app/src/main/java/com/eurobuddha/maxima/app/MaximaService.java → maxima/app/src/main/java/com/eurobuddha/maxima/app/AndroidContribution.java
- `ChatsPage` --references--> `MainActivity`  [EXTRACTED]
  maxima/app/src/main/java/com/eurobuddha/maxima/app/ui/ChatsPage.java → maxima/app/src/main/java/com/eurobuddha/maxima/app/MainActivity.java
- `ContactsPage` --references--> `MainActivity`  [EXTRACTED]
  maxima/app/src/main/java/com/eurobuddha/maxima/app/ui/ContactsPage.java → maxima/app/src/main/java/com/eurobuddha/maxima/app/MainActivity.java
- `Kit` --references--> `MainActivity`  [EXTRACTED]
  maxima/app/src/main/java/com/eurobuddha/maxima/app/ui/Kit.java → maxima/app/src/main/java/com/eurobuddha/maxima/app/MainActivity.java

## Import Cycles
- None detected.

## Communities (128 total, 33 thin omitted)

### Community 0 - "MaximaNode"
Cohesion: 0.05
Nodes (12): Contact, Override, MaximaNode, MessageListener, ServerSocket, BooleanSupplier, LiveMailboxTest, LiveNetworkExchange (+4 more)

### Community 1 - "ChatActivity"
Cohesion: 0.06
Nodes (18): androidx.recyclerview.widget.RecyclerView, Adapter, ChatActivity, Bitmap, EditText, Entry, ImageView, Intent (+10 more)

### Community 2 - "MiniData"
Cohesion: 0.06
Nodes (16): Codec, MiniData, Streamable, DeterministicRsa, Hashes, MaximaCrypto, MxAddress, MaximaSender (+8 more)

### Community 3 - "DirectReachability"
Cohesion: 0.05
Nodes (26): AndroidContribution, Override, Blocker, CONTRIB_OFF, NEEDS_CHARGING, NEEDS_PUBLIC_IP, NEEDS_WIFI, NONE (+18 more)

### Community 4 - "java.io.DataInputStream"
Cohesion: 0.07
Nodes (16): Address, Override, Override, MiniString, TxPoWGenerator, JSONArray, JSONAware, JSONObject (+8 more)

### Community 5 - "ChatEngine"
Cohesion: 0.08
Nodes (5): ChatEngine, Entry, Listener, Summary, ChatMessage

### Community 6 - "MaximaIdentity"
Cohesion: 0.07
Nodes (10): ImportResult, Bip39, MiniData, Created, MaximaIdentity, ClassicThroughOurRelay, LanPeerTest, LiveProbeTest (+2 more)

### Community 7 - ".render"
Cohesion: 0.09
Nodes (6): Drawable, IpCallback, LinearLayout, Override, TextView, NetworkPage

### Community 8 - "BlobStore"
Cohesion: 0.07
Nodes (10): ChunkSource, Encoded, MediaCodec, MediaManifest, MediaService, MediaWire, BlobStore, MediaTest (+2 more)

### Community 9 - "Kit"
Cohesion: 0.12
Nodes (13): android.widget.LinearLayout, BottomSheetDialog, EditText, LayoutParams, LinearLayout, TextView, Kit, OnToggle (+5 more)

### Community 10 - "Capabilities"
Cohesion: 0.06
Nodes (8): ContactCtrl, Parsed, Keys, Capabilities, Override, Json, Writer, DirectoryUnitTest

### Community 11 - "RelayServer"
Cohesion: 0.09
Nodes (8): Frame, DataOutputStream, Stats, Conn, PrivateKey, ServerSocket, RateLimit, RelayServer

### Community 12 - ".serialise"
Cohesion: 0.07
Nodes (10): MiniData, Inbound, CodecUnitTest, Streamable, IdentityTest, MiniData, LiveSend, MessageUnitTest (+2 more)

### Community 13 - "HostConnection"
Cohesion: 0.08
Nodes (12): MiniNumber, MiniString, HostConnection, DataInputStream, DataOutputStream, MiniData, Override, Socket (+4 more)

### Community 14 - "ContactsPage"
Cohesion: 0.13
Nodes (9): ScanSink, ContactsPage, BottomSheetDialog, Drawable, EditText, LayoutParams, LinearLayout, Override (+1 more)

### Community 15 - ".build"
Cohesion: 0.07
Nodes (13): KeyPair, Hkdf, Result, MiniData, MiniData, MiniData, CryptoUnitTest, LiveContactTest (+5 more)

### Community 16 - "Coin"
Cohesion: 0.07
Nodes (8): Coin, DataInputStream, DataOutputStream, MiniByte, Override, CoinProof, DataInputStream, Override

### Community 17 - "MMRData"
Cohesion: 0.09
Nodes (10): MiniString, Override, MMRData, DataInputStream, MiniByte, Override, MMRProof, MMRProofChunk (+2 more)

### Community 18 - "android.view.View"
Cohesion: 0.09
Nodes (15): android.app.Activity, android.graphics.Color, android.graphics.Typeface, android.view.View, android.view.ViewGroup, android.widget.EditText, android.widget.TextView, DateVH (+7 more)

### Community 19 - ".postConversation"
Cohesion: 0.07
Nodes (12): android.graphics.Bitmap, android.graphics.Canvas, android.graphics.drawable.Drawable, android.graphics.Paint, ChatNotifier, Entry, Avatars, Qr (+4 more)

### Community 20 - "Transaction"
Cohesion: 0.07
Nodes (8): DataInputStream, Override, TxnRow, DataOutputStream, MiniData, MiniNumber, Override, Transaction

### Community 21 - "Group"
Cohesion: 0.09
Nodes (5): Group, Receipt, ChatTest, BooleanSupplier, LiveChatTest

### Community 22 - ".main"
Cohesion: 0.07
Nodes (10): MiniData, DedupCache, Verdict, ACCEPT, DUPLICATE, STALE, Item, Outbox (+2 more)

### Community 23 - "WalletPage"
Cohesion: 0.18
Nodes (8): SimpleDateFormat, BottomSheetDialog, Drawable, ImageView, LayoutParams, LinearLayout, TextView, WalletPage

### Community 24 - "TreeKey"
Cohesion: 0.08
Nodes (9): MiniData, DataOutputStream, MiniData, TreeKey, TreeKeyNode, MiniData, Winternitz, org.bouncycastle.pqc.crypto.gmss.util.WinternitzOTSignature (+1 more)

### Community 25 - "MiniNumber"
Cohesion: 0.08
Nodes (5): MathContext, Override, MiniNumber, GeneralParams, MiniNumber

### Community 26 - ".main"
Cohesion: 0.08
Nodes (6): HostPool, HostRecord, Override, LiveMultiHomeTest, BooleanSupplier, RelayKeepaliveTest

### Community 27 - "MainActivity"
Cohesion: 0.08
Nodes (14): ActivityResultLauncher, android.os.Bundle, androidx.appcompat.app.AppCompatActivity, androidx.viewpager.widget.ViewPager, ChatHub, Entry, Listener, Entry (+6 more)

### Community 28 - "MMR"
Cohesion: 0.12
Nodes (3): DataInputStream, DataOutputStream, MMR

### Community 29 - "MiniData"
Cohesion: 0.10
Nodes (3): DataOutputStream, Override, MiniData

### Community 30 - "ReachabilityManager"
Cohesion: 0.10
Nodes (10): Gates, Listener, pass(), ReachabilityManager, State, ADVERTISED, MAPPING, OFF (+2 more)

### Community 31 - "MaximaWallet"
Cohesion: 0.10
Nodes (4): MiniData, MaximaWallet, KeyUses, WalletCore

### Community 32 - "DirectEndpoint"
Cohesion: 0.09
Nodes (9): DirectEndpoint, DataOutputStream, Sink, Probe, DirectEndpointTest, ProbeTest, java.net.Socket, DataInputStream (+1 more)

### Community 33 - ".main"
Cohesion: 0.13
Nodes (7): MiniByte, MiniData, MiniString, Override, RpcEnvelope, Request, RpcUnitTest

### Community 34 - "android.content.Context"
Cohesion: 0.18
Nodes (6): android.content.Context, android.content.Intent, Intent, Override, Uri, MaximaApiReceiver

### Community 35 - "MiniString"
Cohesion: 0.10
Nodes (8): Override, MiniString, MiniString, Override, MLSPacketGETReq, MiniString, Override, MLSPacketSET

### Community 36 - "Tier1Services"
Cohesion: 0.10
Nodes (5): MiniData, Handler, ServiceRegistry, ContributionPolicy, Tier1Services

### Community 37 - ".build"
Cohesion: 0.11
Nodes (7): InputCoin, MiniData, MiniNumber, Output, TxnFactory, DataInputStream, MiniData

### Community 38 - "MMREntry"
Cohesion: 0.16
Nodes (4): MiniNumber, Override, Override, MMREntry

### Community 39 - "Maxima — an annotated walkthrough"
Cohesion: 0.06
Nodes (29): Design constraints, Layout, Limits & storage boundaries, Live validation against a running node, Maxima — decentralised information layer for Minima, Protocol notes worth knowing, Reading the code, Run a node (desktop) (+21 more)

### Community 40 - ".main"
Cohesion: 0.11
Nodes (5): RelayGossipClient, RelayPeers, java.util.concurrent.LinkedBlockingQueue, BooleanSupplier, RelayGossipTest

### Community 41 - "Witness"
Cohesion: 0.11
Nodes (4): BuiltTxn, ScriptProof, Override, Witness

### Community 42 - "StateVariable"
Cohesion: 0.11
Nodes (6): Override, MiniByte, MiniByte, MiniString, Override, StateVariable

### Community 43 - ".main"
Cohesion: 0.11
Nodes (3): Store, ConcurrencyTest, StoreTest

### Community 44 - "TxPoW"
Cohesion: 0.12
Nodes (7): MiniData, Override, Magic, Override, TxHeader, Override, TxPoW

### Community 45 - "NodeLink"
Cohesion: 0.11
Nodes (8): Cb, NodeLink, PairingListener, MinimaAPI, MinimaAPIListener, org.json.JSONObject, org.minimarex.minimaapi.MinimaAPI, org.minimarex.minimaapi.MinimaAPIListener

### Community 46 - "ChatsPage"
Cohesion: 0.16
Nodes (7): android.widget.BaseAdapter, android.widget.ListView, Adapter, ChatsPage, EditText, Override, Row

### Community 47 - "AppLock"
Cohesion: 0.12
Nodes (5): androidx.annotation.NonNull, androidx.fragment.app.FragmentActivity, AppLock, Callback, TextView

### Community 48 - "WalletPublisher"
Cohesion: 0.18
Nodes (4): Override, Cb, ROnly, WalletPublisher

### Community 49 - "Token"
Cohesion: 0.12
Nodes (7): DataInputStream, DataOutputStream, MiniData, MiniNumber, MiniString, Override, Token

### Community 50 - "Hex"
Cohesion: 0.09
Nodes (4): Hex, Override, Reads, Base32

### Community 51 - "MiniByte"
Cohesion: 0.13
Nodes (6): Override, MiniByte, MiniByte, MiniData, Override, MaximaCTRLMessage

### Community 52 - "Mailbox"
Cohesion: 0.20
Nodes (4): Box, Item, Mailbox, HardeningTest

### Community 53 - "DesktopMain"
Cohesion: 0.13
Nodes (10): Bootstrap, DesktopMain, java.awt.MenuItem, java.awt.TrayIcon, java.io.RandomAccessFile, java.nio.channels.FileLock, MenuItem, RandomAccessFile (+2 more)

### Community 54 - ".showTokenDetail"
Cohesion: 0.16
Nodes (5): android.widget.ImageView, Amounts, Agg, CoinAggregator, MiniNumber

### Community 55 - "Signature"
Cohesion: 0.14
Nodes (5): DataInputStream, Override, Signature, Override, SignatureProof

### Community 56 - "FileStore"
Cohesion: 0.20
Nodes (3): FileStore, Override, RelayHardeningTest

### Community 57 - ".start"
Cohesion: 0.12
Nodes (12): android.content.BroadcastReceiver, androidx.work.Worker, androidx.work.WorkerParameters, BootReceiver, Override, HeartbeatReceiver, Intent, Override (+4 more)

### Community 58 - "UpnpIgd"
Cohesion: 0.18
Nodes (4): UpnpIgd, ServerSocket, PortMapTest, DatagramSocket

### Community 59 - "RpcPeer"
Cohesion: 0.15
Nodes (4): Pending, ResponseHandler, RpcPeer, LiveRpcTest

### Community 60 - "RelayRuntime"
Cohesion: 0.16
Nodes (3): Main, RelayRuntime, Seed

### Community 61 - "ChatPrefs"
Cohesion: 0.18
Nodes (4): android.app.Application, ChatPrefs, Override, MaximaApp

### Community 62 - "GatewayNode"
Cohesion: 0.22
Nodes (3): android.os.Handler, Cb, GatewayNode

### Community 64 - "MlsStore"
Cohesion: 0.17
Nodes (3): MlsService, Entry, MlsStore

### Community 65 - "PaymentSender"
Cohesion: 0.13
Nodes (3): Arrival, Cb, PaymentSender

### Community 66 - "MaxTxPoW"
Cohesion: 0.20
Nodes (6): Built, Override, MaximaPackage, Override, MaxTxPoW, java.net.ServerSocket

### Community 68 - "JSONWriter"
Cohesion: 0.22
Nodes (3): Override, Override, JSONWriter

### Community 69 - "NatPmp"
Cohesion: 0.22
Nodes (4): NatPmp, Result, java.net.DatagramSocket, java.net.InetAddress

### Community 70 - ".log"
Cohesion: 0.21
Nodes (3): Crypto, MiniData, FastByteArrayStream

### Community 72 - "PortMapper"
Cohesion: 0.22
Nodes (4): Override, Mapping, PortMapper, PortMapLiveTest

### Community 73 - "android.content.SharedPreferences"
Cohesion: 0.27
Nodes (3): android.content.SharedPreferences, Override, PrefsKeyUses

### Community 74 - ".run"
Cohesion: 0.18
Nodes (3): ConnectionFinder, Listener, Explain

### Community 75 - "JSONParser"
Cohesion: 0.16
Nodes (3): JSONValue, JSONParser, Yytoken

### Community 76 - "MaximumMessage"
Cohesion: 0.24
Nodes (4): MiniData, Override, MaximumMessage, java.security.PrivateKey

### Community 77 - ".startPumping"
Cohesion: 0.20
Nodes (4): android.app.Notification, android.app.Service, android.os.IBinder, SwarmStore

### Community 78 - "LanDiscovery"
Cohesion: 0.22
Nodes (7): android.net.nsd.NsdManager, android.net.nsd.NsdServiceInfo, LanDiscovery, DiscoveryListener, MulticastLock, NsdServiceInfo, RegistrationListener

### Community 83 - ".mark"
Cohesion: 0.31
Nodes (5): BufferedImage, TrayIcons, java.awt.Color, java.awt.Graphics2D, java.awt.image.BufferedImage

### Community 84 - "Phases"
Cohesion: 0.15
Nodes (12): A. `:core` `portmap` — NAT-PMP and UPnP IGD clients  (pure JVM), B. `:core` `net.DirectEndpoint` — accepting a connection at all, C. Fleet `probe.dial` — third-party reachability proof  (server 0.1.6), D. `:app` `DirectReachability` — the policy loop, E. LAN discovery — mDNS/NSD  (second), F. Wi-Fi Direct / BLE — explicitly deferred, Order and estimates, Phases (+4 more)

### Community 88 - "CoinSelector"
Cohesion: 0.33
Nodes (3): CoinSelector, InsufficientFundsException, MiniNumber

### Community 89 - "Classic Maxima — complete feature audit"
Cohesion: 0.18
Nodes (10): 1. `maxima`, 2. `maxcontacts`, 3. `maxextra`, 4. Standalone crypto utilities, 5. Events published to apps, 6. Background behaviour, 6b. Transport-liveness parity (the NIO layer under Maxima), 7. Where classic's model is wrong for phones (+2 more)

### Community 94 - "The phone app and contacts suite — design"
Cohesion: 0.20
Nodes (9): 1. The central problem: identity is stable, addresses are not, 2. Resolution ladder, 3. First contact — the honest hard problem, 4. Contacts suite, 5. App architecture, 6. What "production grade" requires, 7. Classic parity still outstanding, 8. Deliberately NOT copied from classic (+1 more)

### Community 95 - ".deliver"
Cohesion: 0.36
Nodes (3): android.net.Uri, MiniData, MaximaApiDelivery

### Community 96 - "WalletLedger"
Cohesion: 0.33
Nodes (3): Row, WalletLedger, org.json.JSONArray

### Community 98 - "MLSPacketGETResp"
Cohesion: 0.33
Nodes (3): MiniString, Override, MLSPacketGETResp

### Community 101 - "The desktop node — set-and-forget relays for everyone"
Cohesion: 0.22
Nodes (8): Architecture, Deployment notes, Discovery — relay gossip, in classic's own vocabulary, Packaging, CI, signing, The desktop node — set-and-forget relays for everyone, The router magic — never advertise hope, Verification, Why a Sybil flood gets nothing

### Community 107 - "Maxima for Minima Core — what this is"
Cohesion: 0.33
Nodes (5): For Core specifically, Improved — invisibly, Maxima for Minima Core — what this is, The same — provably, The two artifacts, and how they hold each other up

### Community 111 - "Result"
Cohesion: 0.40
Nodes (5): Result, DUPLICATE, QUOTA_BYTES, QUOTA_COUNT, STORED

### Community 115 - "Threat model and residual risks"
Cohesion: 0.40
Nodes (4): Defended, Not yet addressed, Residual risks — known and accepted, Threat model and residual risks

### Community 116 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **97 isolated node(s):** `install.sh script`, `OFF`, `MAPPING`, `PROBING`, `ADVERTISED` (+92 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **33 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MaximaNode` connect `MaximaNode` to `ChatActivity`, `MiniData`, `DirectReachability`, `ChatEngine`, `MaximaIdentity`, `.render`, `BlobStore`, `Capabilities`, `HostConnection`, `android.view.View`, `.postConversation`, `Group`, `.main`, `.main`, `MainActivity`, `ReachabilityManager`, `DirectEndpoint`, `android.content.Context`, `Tier1Services`, `.main`, `.main`, `ChatsPage`, `Mailbox`, `DesktopMain`, `RpcPeer`, `MlsStore`, `.startPumping`, `LanDiscovery`, `.onCreate`, `.resolve`?**
  _High betweenness centrality (0.177) - this node is a cross-community bridge._
- **Why does `MiniNumber` connect `MiniNumber` to `PaymentSender`, `MiniFormat`, `java.io.DataInputStream`, `.build`, `WalletPublisher`, `Coin`, `android.view.View`, `MMRData`, `Token`, `Transaction`, `.showTokenDetail`, `CoinSelector`, `TreeKey`, `Maths`, `MMR`?**
  _High betweenness centrality (0.106) - this node is a cross-community bridge._
- **Why does `MiniData` connect `MiniData` to `MiniFormat`, `MiniData`, `.main`, `java.io.DataInputStream`, `.build`, `.log`, `Witness`, `WalletPublisher`, `MMRData`, `Coin`, `Token`, `Transaction`, `Signature`, `TreeKey`, `BIP39`, `Maths`, `MaximaWallet`?**
  _High betweenness centrality (0.106) - this node is a cross-community bridge._
- **What connects `install.sh script`, `OFF`, `MAPPING` to the rest of the system?**
  _97 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MaximaNode` be split into smaller, more focused modules?**
  _Cohesion score 0.04603836530442035 - nodes in this community are weakly interconnected._
- **Should `ChatActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.06142728093947606 - nodes in this community are weakly interconnected._
- **Should `MiniData` be split into smaller, more focused modules?**
  _Cohesion score 0.06234177215189873 - nodes in this community are weakly interconnected._