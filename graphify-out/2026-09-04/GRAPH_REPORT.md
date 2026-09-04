# Graph Report - maxima  (2026-08-21)

## Corpus Check
- 357 files · ~318,121 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 5075 nodes · 15382 edges · 171 communities (130 shown, 41 thin omitted)
- Extraction: 86% EXTRACTED · 14% INFERRED · 0% AMBIGUOUS · INFERRED: 2212 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `b7a423e2`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- android.view.View
- MiniNumber
- ChatActivity
- java.io.DataInputStream
- MiniData
- .render
- UpnpIgd
- android.content.Context
- CallManager
- .add
- Coin
- MaximaManager.java
- JsonDB
- MiniByte
- ChatsPanel
- .serialise
- ChatEngine
- MiniNumber
- DesktopJarEngine
- MaximaIdentity
- Contact
- MiniData
- .startPumping
- JPanel
- TxPoW
- .fromPhrase
- java.awt.Color
- WalletPage
- javax.swing.JComponent
- .log
- Group
- Message
- .manualForward
- MaximaManager
- MaximaNode
- GenerateKey
- HostConnection
- ChatMedia
- .sendRaw
- HostPool
- LockGate
- .onCreate
- DesktopNode
- JarEngine
- java.awt.image.BufferedImage
- .processMessage
- SocketTransport
- DesktopWalletPublisher
- Tier1Services
- .processMessage
- Address
- Main
- BlobStore
- Classic Maxima Feature Audit
- .build
- MaximaWindow
- .record
- Outbox
- Maxima — an annotated walkthrough
- MMRData
- MessageProcessor
- WalletPublisher
- RelayServer
- android.graphics.Bitmap
- .bootJarEngine
- CodecUnitTest.java
- MiniByte
- DesktopMain
- .chat
- MaximaNode.java
- .recordVoiceDialog
- ChatsPage
- android.app.Activity
- ContactsPage
- MaximaWallet
- ReachabilityManager
- .deserialise
- .render
- .bubble
- Mailbox
- MMR
- FileStore
- .derive
- .bindMessage
- ContactsPanel
- MLSPacketSET
- .main
- .showContactCard
- org.junit.Test
- NodeLink
- NatPmp
- DirectEndpoint
- .run
- ReachabilityManager.java
- JSONWriter
- ZoomImageView
- MaximaMessage
- Greeting
- PortMapper
- MiniStunTest
- Yylex
- Override
- GatewayNode
- DesktopWalletLedger
- KeyUses
- SearchActivity
- ItemList
- .parse
- RelayRuntime
- Util
- .merge
- MLSPacketSET
- MaximaContactManager
- Avatars
- DesktopNodeLink
- Phases
- .postConversation
- .toast
- MLSPacketGETReq
- Annotated Maxima Frame (1225 bytes)
- Greeting
- .decode
- Desktop Node Build Workflow
- Classic Maxima — complete feature audit
- Maxima — wire-compatible reimplementation
- .main
- The phone app and contacts suite — design
- The desktop node — set-and-forget relays for everyone
- MaximaErrorMsg
- MLSPacketGETResp
- DirectReachability.java
- .parse
- .main
- AutoStart
- ParseException
- Amounts
- MaximaDB
- .mailboxAckCanonical
- .main
- Maxima for Minima Core — what this is
- CoinAggregator
- Maths
- :core JVM library
- The Interop Gate (byte-exactness)
- ContainerFactory
- Presence
- FastByteArrayStream
- Threat model and residual risks
- gradlew
- Yytoken
- MaximaApiMessages
- ScanSink
- maxima — working rules
- Seed derivation from BIP39 mnemonic
- JSONStreamAware
- Pre-commit version-bump hook
- install.sh
- pre-commit
- deploy-relay.sh
- verify-relay.sh
- Classic carriers have a body (opaque re-emit)
- CTRL/TYPE_MLS bare Mx key (no @host) bug
- Reliability (dedup, replay window, outbox retry)

## God Nodes (most connected - your core abstractions)
1. `MiniData` - 233 edges
2. `MaximaNode` - 188 edges
3. `MiniNumber` - 140 edges
4. `JSONObject` - 118 edges
5. `MaximaIdentity` - 110 edges
6. `ChatEngine` - 104 edges
7. `MiniData` - 104 edges
8. `Contact` - 101 edges
9. `Streamable` - 95 edges
10. `ChatActivity` - 94 edges

## Surprising Connections (you probably didn't know these)
- `Desktop Node Build Workflow` --references--> `:desktop node`  [INFERRED]
  .github/workflows/desktop-node.yml → README.md
- `tools/vectorgen/Annotate.java` --references--> `Annotated Maxima Frame (1225 bytes)`  [INFERRED]
  WALKTHROUGH.md → docs/annotated-frame.txt
- `AndroidContribution` --implements--> `ContributionPolicy`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/maxima/app/AndroidContribution.java → core/src/main/java/com/eurobuddha/maxima/core/services/ContributionPolicy.java
- `MaximaService` --references--> `ChatEngine`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/maxima/app/MaximaService.java → core/src/main/java/com/eurobuddha/maxima/core/chat/ChatEngine.java
- `MaximaService` --references--> `MaximaNode`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/maxima/app/MaximaService.java → core/src/main/java/com/eurobuddha/maxima/core/MaximaNode.java

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Maxima frame nested envelope structure** — walkthrough_maxima_message, walkthrough_maxima_internal, walkthrough_crypto_package, walkthrough_maxima_package [EXTRACTED 0.90]
- **Byte-exact interop gate toolchain** — readme_interop_gate, readme_parity_test, readme_vectorgen, readme_minima_reference_jar, walkthrough_carrier_check [EXTRACTED 0.85]
- **Tier 2 map-probe-advertise reachability pipeline** — docs_design_tier2_portmap, docs_design_tier2_direct_endpoint, docs_design_tier2_probe_dial, docs_design_tier2_direct_reachability, docs_design_desktop_reachability_manager [EXTRACTED 0.85]

## Communities (171 total, 41 thin omitted)

### Community 0 - "android.view.View"
Cohesion: 0.07
Nodes (28): ActivityResultLauncher, android.app.Notification, android.app.Service, android.graphics.Typeface, android.os.Bundle, android.os.Handler, android.view.View, android.widget.EditText (+20 more)

### Community 1 - "MiniNumber"
Cohesion: 0.06
Nodes (10): Override, MiniNumber, MiniData, Override, Magic, Override, TxHeader, MiniData (+2 more)

### Community 2 - "ChatActivity"
Cohesion: 0.10
Nodes (10): androidx.recyclerview.widget.RecyclerView, ChatActivity, Bitmap, EditText, Intent, Uri, ImageButton, Listener (+2 more)

### Community 3 - "java.io.DataInputStream"
Cohesion: 0.07
Nodes (15): TxPoWGenerator, java.io.DataInputStream, java.io.DataOutputStream, java.math.MathContext, java.nio.charset.Charset, Override, MiniString, Crypto (+7 more)

### Community 4 - "MiniData"
Cohesion: 0.06
Nodes (20): Codec, MiniData, Override, MiniString, Streamable, DeterministicRsa, Hashes, MaximaCrypto (+12 more)

### Community 5 - ".render"
Cohesion: 0.09
Nodes (15): BottomSheetDialog, Drawable, EditText, LayoutParams, LinearLayout, TextView, Kit, OnToggle (+7 more)

### Community 6 - "UpnpIgd"
Cohesion: 0.17
Nodes (4): UpnpIgd, ServerSocket, PortMapTest, DatagramSocket

### Community 7 - "android.content.Context"
Cohesion: 0.05
Nodes (27): android.content.BroadcastReceiver, android.content.Context, android.content.Intent, android.net.Uri, androidx.annotation.NonNull, androidx.work.Worker, androidx.work.WorkerParameters, BootReceiver (+19 more)

### Community 8 - "CallManager"
Cohesion: 0.05
Nodes (32): android.media.Ringtone, LinearLayout, Override, TextView, CallManager, Override, Listener, Sdp (+24 more)

### Community 9 - ".add"
Cohesion: 0.05
Nodes (25): AndroidContribution, Override, Blocker, CONTRIB_OFF, NEEDS_CHARGING, NEEDS_PUBLIC_IP, NEEDS_WIFI, NONE (+17 more)

### Community 10 - "Coin"
Cohesion: 0.03
Nodes (22): BuiltTxn, InputCoin, MiniData, MiniNumber, Output, TxnFactory, Coin, DataInputStream (+14 more)

### Community 11 - "MaximaManager.java"
Cohesion: 0.08
Nodes (14): java.sql.ResultSet, DataInputStream, Override, MaximaInternal, DataInputStream, Override, MaximaMessage, NIOMessage (+6 more)

### Community 12 - "JsonDB"
Cohesion: 0.07
Nodes (6): UserDB, MiniData, MiniNumber, Override, JsonDB, MiniUtil

### Community 13 - "MiniByte"
Cohesion: 0.10
Nodes (6): DataInputStream, Override, MaximaCTRLMessage, MiniData, Override, MiniByte

### Community 14 - "ChatsPanel"
Cohesion: 0.07
Nodes (11): SimpleDateFormat, Summary, ChatsPanel, Hit, Entry, JDialog, JLabel, JScrollPane (+3 more)

### Community 15 - ".serialise"
Cohesion: 0.06
Nodes (13): Sha3Provider, MxAddressTest, Result, MlsClient, Resolved, MiniData, MiniData, IdentityTest (+5 more)

### Community 16 - "ChatEngine"
Cohesion: 0.07
Nodes (5): CallSignals, ChatEngine, Entry, ChatMessage, ChatPort

### Community 17 - "MiniNumber"
Cohesion: 0.03
Nodes (18): CoinSelector, InsufficientFundsException, MiniNumber, DataInputStream, DataOutputStream, MiniData, MiniNumber, MiniString (+10 more)

### Community 18 - "DesktopJarEngine"
Cohesion: 0.12
Nodes (5): DesktopJarEngine, Inbound, MaximaMessage, Override, Preferences

### Community 19 - "MaximaIdentity"
Cohesion: 0.07
Nodes (6): Bip39, Created, MaximaIdentity, Probe, ClassicThroughOurRelay, Bip39Check

### Community 20 - "Contact"
Cohesion: 0.09
Nodes (6): Chat, Contact, Override, Parsed, Inbound, ConcurrencyTest

### Community 21 - "MiniData"
Cohesion: 0.04
Nodes (17): DataInputStream, Override, Signature, Override, SignatureProof, MiniData, TreeKey, TreeKeyNode (+9 more)

### Community 22 - ".startPumping"
Cohesion: 0.08
Nodes (9): android.content.SharedPreferences, HomeStore, ConnectionFinder, Listener, RelayStore, SwarmStore, Override, PrefsKeyUses (+1 more)

### Community 23 - "JPanel"
Cohesion: 0.06
Nodes (14): DesktopEventLog, JLabel, JDialog, JLabel, NetworkPanel, JLabel, JComponent, JDialog (+6 more)

### Community 24 - "TxPoW"
Cohesion: 0.06
Nodes (13): DataInputStream, MiniData, Override, TxHeader, DataOutputStream, MiniData, Override, TxPoW (+5 more)

### Community 25 - ".fromPhrase"
Cohesion: 0.12
Nodes (4): BooleanSupplier, MediaRelayTest, BooleanSupplier, RelayKeepaliveTest

### Community 26 - "java.awt.Color"
Cohesion: 0.06
Nodes (12): Bubble, Override, PlayButton, Avatar, RoundPanel, Btn, Icons, IconButton (+4 more)

### Community 27 - "WalletPage"
Cohesion: 0.15
Nodes (8): BottomSheetDialog, Drawable, ImageView, LayoutParams, LinearLayout, TextView, WalletPage, Agg

### Community 28 - "javax.swing.JComponent"
Cohesion: 0.06
Nodes (21): JTextArea, JDialog, DesktopExplain, Override, ScrollableColumn, WrapText, Tab, JDialog (+13 more)

### Community 29 - ".log"
Cohesion: 0.07
Nodes (4): java.sql.Connection, MiniFile, SqlDB, MiniData

### Community 30 - "Group"
Cohesion: 0.09
Nodes (6): Group, Receipt, ChatTest, MaximaMessage, BooleanSupplier, LiveChatTest

### Community 31 - "Message"
Cohesion: 0.10
Nodes (7): Result, Result, maxima, MiniData, Override, Message, MessageListener

### Community 32 - ".manualForward"
Cohesion: 0.14
Nodes (4): Chirp, DesktopManualForward, java.util.prefs.Preferences, javax.sound.sampled.Clip

### Community 33 - "MaximaManager"
Cohesion: 0.08
Nodes (4): MiniData, MaximaManager, MaxMsgHandler, ServerSocket

### Community 34 - "MaximaNode"
Cohesion: 0.05
Nodes (7): EventListener, Override, LogListener, MaximaNode, MaximaLoopTest, BooleanSupplier, RelaySelfHealTest

### Community 35 - "GenerateKey"
Cohesion: 0.17
Nodes (5): MiniData, EncryptDecrypt, GenerateKey, MiniData, PasswordCrypto

### Community 36 - "HostConnection"
Cohesion: 0.10
Nodes (10): HostConnection, DataInputStream, DataOutputStream, MaximaCTRLMessage, MiniData, Override, Socket, Sink (+2 more)

### Community 38 - ".sendRaw"
Cohesion: 0.12
Nodes (4): MiniData, Result, MediaWire, LanPeerTest

### Community 39 - "HostPool"
Cohesion: 0.14
Nodes (5): HostPool, HostRecord, Override, LiveMultiHomeTest, Sink

### Community 40 - "LockGate"
Cohesion: 0.12
Nodes (4): androidx.fragment.app.FragmentActivity, AppLock, Callback, LockGate

### Community 41 - ".onCreate"
Cohesion: 0.07
Nodes (4): Listener, Store, StoreTest, DesktopJarMigration

### Community 42 - "DesktopNode"
Cohesion: 0.06
Nodes (18): DownFab, JTextField, SendFabLike, DesktopCalls, Override, DesktopNode, State, DKit (+10 more)

### Community 43 - "JarEngine"
Cohesion: 0.10
Nodes (6): Inbound, JarEngine, MaximaMessage, Override, MaximaMessage, Override

### Community 44 - "java.awt.image.BufferedImage"
Cohesion: 0.08
Nodes (18): BufferedImage, TrayIcons, DesktopImagePrep, BufferedImage, Result, DesktopQr, Canvas, ImageViewer (+10 more)

### Community 45 - ".processMessage"
Cohesion: 0.07
Nodes (8): MiniData, MaximaHost, Override, MLSService, Override, NIOClient, NIOManager, TrafficListener

### Community 46 - "SocketTransport"
Cohesion: 0.07
Nodes (12): MaxJarNode, DataInputStream, DataOutputStream, MaximaCTRLMessage, MiniByte, Override, ServerSocket, Socket (+4 more)

### Community 47 - "DesktopWalletPublisher"
Cohesion: 0.09
Nodes (5): PayResult, DesktopWallet, Cb, DesktopWalletPublisher, org.json.JSONObject

### Community 48 - "Tier1Services"
Cohesion: 0.05
Nodes (14): MiniByte, MiniData, MiniString, Override, RpcEnvelope, Pending, ResponseHandler, RpcPeer (+6 more)

### Community 49 - ".processMessage"
Cohesion: 0.13
Nodes (4): MiniData, MaximaContact, Override, MiniData

### Community 50 - "Address"
Cohesion: 0.08
Nodes (7): MiniData, WalletCore, Address, DataOutputStream, Override, BaseConverter, VectorGen

### Community 51 - "Main"
Cohesion: 0.11
Nodes (5): MaximaTransport, Main, NotifyListener, NetworkManager, P2PManager

### Community 52 - "BlobStore"
Cohesion: 0.09
Nodes (7): ChunkSource, Encoded, MediaCodec, MediaManifest, MediaService, BlobStore, MediaTest

### Community 53 - "Classic Maxima Feature Audit"
Cohesion: 0.07
Nodes (34): Contact record (contacts/Contact.java), Phone App and Contacts Suite Design, First contact (QR + invite link, MAX#pubkey#mls), Identity is stable, addresses are ephemeral, Resolution ladder (cached, MLS, gossip, mailbox), Never advertise hope (prove port before announce), ReachabilityManager (router magic), Relay gossip discovery (+26 more)

### Community 54 - ".build"
Cohesion: 0.07
Nodes (12): MiniData, Override, MaximumMessage, CryptoUnitTest, RelayHardeningTest, SizeLimitTest, CryptoPackage, java.security.PrivateKey (+4 more)

### Community 55 - "MaximaWindow"
Cohesion: 0.08
Nodes (13): BufferedImage, IntConsumer, JLabel, JPanel, Listener, Timer, MaximaWindow, Responsive (+5 more)

### Community 56 - ".record"
Cohesion: 0.11
Nodes (12): Graphics, JDialog, Override, Timer, LiveWave, Recorder, Sink, VoiceNotes (+4 more)

### Community 57 - "Outbox"
Cohesion: 0.10
Nodes (9): MiniData, DedupCache, Verdict, ACCEPT, DUPLICATE, STALE, Item, Outbox (+1 more)

### Community 58 - "Maxima — an annotated walkthrough"
Cohesion: 0.06
Nodes (29): Design constraints, Layout, Limits & storage boundaries, Live validation against a running node, Maxima — decentralised information layer for Minima, Protocol notes worth knowing, Reading the code, Run a node (desktop) (+21 more)

### Community 59 - "MMRData"
Cohesion: 0.04
Nodes (19): DataInputStream, Override, TxnRow, CoinProof, DataInputStream, Override, MiniString, Override (+11 more)

### Community 60 - "MessageProcessor"
Cohesion: 0.06
Nodes (7): Override, MessageProcessor, MessageStack, Override, TimerMessage, Override, TimerProcessor

### Community 61 - "WalletPublisher"
Cohesion: 0.07
Nodes (7): Override, Arrival, Cb, PaymentSender, Cb, ROnly, WalletPublisher

### Community 62 - "RelayServer"
Cohesion: 0.13
Nodes (11): DataOutputStream, Greeting, Conn, DataInputStream, DataOutputStream, MaximaCTRLMessage, MaximaPackage, PrivateKey (+3 more)

### Community 63 - "android.graphics.Bitmap"
Cohesion: 0.15
Nodes (8): android.graphics.Bitmap, android.graphics.Canvas, android.graphics.Paint, android.util.AttributeSet, Override, Qr, Identicon, Paint

### Community 64 - ".bootJarEngine"
Cohesion: 0.07
Nodes (10): android.net.nsd.NsdManager, android.net.nsd.NsdServiceInfo, android.os.IBinder, LanDiscovery, Peers, Override, DiscoveryListener, MulticastLock (+2 more)

### Community 65 - "CodecUnitTest.java"
Cohesion: 0.10
Nodes (6): Hex, Override, Reads, Base32, CodecUnitTest, Streamable

### Community 66 - "MiniByte"
Cohesion: 0.11
Nodes (6): Override, MiniByte, MiniByte, MiniData, Override, MaximaCTRLMessage

### Community 67 - "DesktopMain"
Cohesion: 0.10
Nodes (11): DesktopMain, Preferences, State, java.awt.MenuItem, java.awt.TrayIcon, java.io.RandomAccessFile, java.nio.channels.FileLock, MenuItem (+3 more)

### Community 69 - "MaximaNode.java"
Cohesion: 0.15
Nodes (5): ContactCtrl, Keys, Capabilities, Override, Json

### Community 70 - ".recordVoiceDialog"
Cohesion: 0.13
Nodes (4): android.media.MediaRecorder, VoiceNote, WaveformView, MediaRecorder

### Community 71 - "ChatsPage"
Cohesion: 0.15
Nodes (7): android.widget.BaseAdapter, android.widget.ListView, Adapter, ChatsPage, EditText, Override, Row

### Community 72 - "android.app.Activity"
Cohesion: 0.16
Nodes (5): android.app.Activity, android.view.ViewGroup, Explain, TextView, Ui

### Community 73 - "ContactsPage"
Cohesion: 0.14
Nodes (3): ContactsPage, Drawable, Override

### Community 74 - "MaximaWallet"
Cohesion: 0.11
Nodes (5): EditText, MiniData, MaximaWallet, SeedCrypt, javax.crypto.SecretKey

### Community 75 - "ReachabilityManager"
Cohesion: 0.19
Nodes (3): Gates, ReachabilityManager, java.util.function.IntSupplier

### Community 76 - ".deserialise"
Cohesion: 0.10
Nodes (6): MiniString, Override, MLSPacketGETReq, MiniString, Override, MLSPacketGETResp

### Community 77 - ".render"
Cohesion: 0.09
Nodes (7): android.app.Application, ChatPrefs, Override, MaximaApp, EditText, Override, SettingsPage

### Community 79 - "Mailbox"
Cohesion: 0.15
Nodes (9): Box, Item, Mailbox, Result, DUPLICATE, QUOTA_BYTES, QUOTA_COUNT, STORED (+1 more)

### Community 80 - "MMR"
Cohesion: 0.07
Nodes (9): DataInputStream, DataOutputStream, MiniNumber, Override, MMR, Override, MMREntry, Override (+1 more)

### Community 81 - "FileStore"
Cohesion: 0.11
Nodes (5): ClassicChat, FileStore, Override, Writer, DirectoryUnitTest

### Community 82 - ".derive"
Cohesion: 0.11
Nodes (9): DeterministicRsaTest, KeyPair, Hkdf, LiveContactTest, LiveRelayTest, java.security.interfaces.RSAPublicKey, java.security.KeyPair, CarrierCheck (+1 more)

### Community 83 - ".bindMessage"
Cohesion: 0.18
Nodes (4): ImageView, LinearLayout, TextView, MsgVH

### Community 84 - "ContactsPanel"
Cohesion: 0.10
Nodes (10): ContactsPanel, Graphics, JLabel, JTextField, QrDisc, SimpleDoc, BufferedImage, DocumentEvent (+2 more)

### Community 85 - "MLSPacketSET"
Cohesion: 0.14
Nodes (4): DataInputStream, MiniString, Override, MLSPacketSET

### Community 86 - ".main"
Cohesion: 0.12
Nodes (6): MessageListener, BooleanSupplier, LiveMailboxTest, LiveNetworkExchange, FullSendTest, BooleanSupplier

### Community 88 - ".showContactCard"
Cohesion: 0.29
Nodes (5): BottomSheetDialog, EditText, LayoutParams, LinearLayout, TextView

### Community 89 - "org.junit.Test"
Cohesion: 0.10
Nodes (6): MiniDataTest, MiniNumberTest, Bip39Test, KeysTest, MiniData, org.junit.Test

### Community 90 - "NodeLink"
Cohesion: 0.16
Nodes (7): Cb, NodeLink, PairingListener, MinimaAPI, MinimaAPIListener, org.minimarex.minimaapi.MinimaAPI, org.minimarex.minimaapi.MinimaAPIListener

### Community 91 - "NatPmp"
Cohesion: 0.23
Nodes (4): NatPmp, Result, java.net.DatagramSocket, java.net.InetAddress

### Community 92 - "DirectEndpoint"
Cohesion: 0.12
Nodes (9): DirectEndpoint, DataOutputStream, ServerSocket, Sink, DirectEndpointTest, LiveProbeTest, ProbeTest, java.net.ServerSocket (+1 more)

### Community 93 - ".run"
Cohesion: 0.16
Nodes (4): DesktopConnectionFinder, Listener, Listener, DesktopRelayStore

### Community 94 - "ReachabilityManager.java"
Cohesion: 0.12
Nodes (8): Listener, pass(), State, ADVERTISED, MAPPING, OFF, PROBING, java.util.concurrent.ThreadFactory

### Community 95 - "JSONWriter"
Cohesion: 0.20
Nodes (3): JSONValue, Override, JSONWriter

### Community 96 - "ZoomImageView"
Cohesion: 0.16
Nodes (9): android.graphics.Matrix, android.view.GestureDetector, android.view.MotionEvent, android.view.ScaleGestureDetector, Override, ZoomImageView, GestureDetector, ImageView (+1 more)

### Community 97 - "MaximaMessage"
Cohesion: 0.08
Nodes (8): MiniData, MlsService, Entry, MlsStore, MiniData, Override, MaximaMessage, Inbound

### Community 98 - "Greeting"
Cohesion: 0.15
Nodes (4): Greeting, MiniNumber, MiniString, Override

### Community 99 - "PortMapper"
Cohesion: 0.21
Nodes (4): Override, Mapping, PortMapper, PortMapLiveTest

### Community 100 - "MiniStunTest"
Cohesion: 0.22
Nodes (6): java.net.DatagramPacket, DatagramPacket, MiniStunTest, DatagramPacket, Override, MiniStun

### Community 102 - "Override"
Cohesion: 0.26
Nodes (3): Adapter, Override, ViewHolder

### Community 104 - "DesktopWalletLedger"
Cohesion: 0.20
Nodes (5): Row, WalletLedger, DesktopWalletLedger, Row, org.json.JSONArray

### Community 106 - "SearchActivity"
Cohesion: 0.29
Nodes (6): Hit, EditText, LinearLayout, Override, TextView, SearchActivity

### Community 109 - "RelayRuntime"
Cohesion: 0.12
Nodes (3): Main, RelayRuntime, Stats

### Community 112 - "MLSPacketSET"
Cohesion: 0.23
Nodes (3): MiniString, Override, MLSPacketSET

### Community 116 - "Phases"
Cohesion: 0.15
Nodes (12): A. `:core` `portmap` — NAT-PMP and UPnP IGD clients  (pure JVM), B. `:core` `net.DirectEndpoint` — accepting a connection at all, C. Fleet `probe.dial` — third-party reachability proof  (server 0.1.6), D. `:app` `DirectReachability` — the policy loop, E. LAN discovery — mDNS/NSD  (second), F. Wi-Fi Direct / BLE — explicitly deferred, Order and estimates, Phases (+4 more)

### Community 117 - ".postConversation"
Cohesion: 0.12
Nodes (5): ChatHub, Entry, Listener, ChatNotifier, Entry

### Community 119 - "MLSPacketGETReq"
Cohesion: 0.24
Nodes (3): DataInputStream, Override, MLSPacketGETReq

### Community 120 - "Annotated Maxima Frame (1225 bytes)"
Cohesion: 0.23
Nodes (12): Annotated Maxima Frame (1225 bytes), Per-message delivery state (end-to-end ack), MSG_PING (type 8) ack channel + five ack bodies, tools/vectorgen/Annotate.java, Synthetic TxPoW carrier — why we do not mine, CryptoPackage {iv, secret, ciphertext}, HostConnection.receive (receive path), MaximaInternal {from, data, signature} (+4 more)

### Community 121 - "Greeting"
Cohesion: 0.25
Nodes (4): Greeting, MiniNumber, MiniString, Override

### Community 123 - "Desktop Node Build Workflow"
Cohesion: 0.20
Nodes (11): Desktop App Design (chat client + relay), org.beryx.runtime (jlink + jpackage), RelayRuntime (in-process full relay), Two personalities, one binary, one seed, Native jpackage Matrix (mac/win/linux), Dormant Code Signing (macOS/Windows), -PskipAndroid configuration flag, Desktop Node Build Workflow (+3 more)

### Community 124 - "Classic Maxima — complete feature audit"
Cohesion: 0.18
Nodes (10): 1. `maxima`, 2. `maxcontacts`, 3. `maxextra`, 4. Standalone crypto utilities, 5. Events published to apps, 6. Background behaviour, 6b. Transport-liveness parity (the NIO layer under Maxima), 7. Where classic's model is wrong for phones (+2 more)

### Community 125 - "Maxima — wire-compatible reimplementation"
Cohesion: 0.18
Nodes (11): MaximaManager.java:525-533 -allowallip restriction, :app Android daemon, Wire framing int32 BE length | uint8 type | payload, Live gates (send/receive against real network), Maxima — wire-compatible reimplementation, Mx base32 address encoding, Limits & storage boundaries (16 MB mesh cap), Maxima Annotated Walkthrough (+3 more)

### Community 127 - "The phone app and contacts suite — design"
Cohesion: 0.20
Nodes (9): 1. The central problem: identity is stable, addresses are not, 2. Resolution ladder, 3. First contact — the honest hard problem, 4. Contacts suite, 5. App architecture, 6. What "production grade" requires, 7. Classic parity still outstanding, 8. Deliberately NOT copied from classic (+1 more)

### Community 128 - "The desktop node — set-and-forget relays for everyone"
Cohesion: 0.20
Nodes (9): Architecture, Deployment notes, Discovery — relay gossip, in classic's own vocabulary, Packaging, CI, signing, The desktop app — chat client + set-and-forget relay, The desktop node — set-and-forget relays for everyone, The router magic — never advertise hope, Verification (+1 more)

### Community 129 - "MaximaErrorMsg"
Cohesion: 0.33
Nodes (4): DataInputStream, MiniString, Override, MaximaErrorMsg

### Community 130 - "MLSPacketGETResp"
Cohesion: 0.24
Nodes (3): DataInputStream, Override, MLSPacketGETResp

### Community 133 - ".main"
Cohesion: 0.11
Nodes (5): RelayGossipClient, RelayPeers, java.util.concurrent.LinkedBlockingQueue, BooleanSupplier, RelayGossipTest

### Community 137 - "MaximaDB"
Cohesion: 0.08
Nodes (8): java.sql.PreparedStatement, Override, MaximaDB, MinimaDB, TxPoWTree, TxPoWTreeNode, ScriptRow, Wallet

### Community 140 - "Maxima for Minima Core — what this is"
Cohesion: 0.29
Nodes (6): For Core specifically, Improved — invisibly, Maxima for Minima Core — what this is, The same — provably, The two artifacts, and how they hold each other up, Store-and-forward mailbox

### Community 145 - ":core JVM library"
Cohesion: 0.33
Nodes (6): Chat as application string (maxima_chat_v1), MaximaService (foreground transport), Maxima Overview — what this is, Two artifacts hold each other up (server + APK on one :core), :core JVM library, Java 11 language level constraint

### Community 146 - "The Interop Gate (byte-exactness)"
Cohesion: 0.33
Nodes (6): The same, provably (wire-for-wire interop), The Interop Gate (byte-exactness), Reference minima.jar, ParityTest / golden vectors, tools/vectorgen, CarrierCheck (reference accepts our unit)

### Community 154 - "Threat model and residual risks"
Cohesion: 0.40
Nodes (4): Defended, Not yet addressed, Residual risks — known and accepted, Threat model and residual risks

### Community 155 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

### Community 161 - "Seed derivation from BIP39 mnemonic"
Cohesion: 0.67
Nodes (3): Seed derivation from BIP39 mnemonic, BIP39 English wordlist (2048 words), Bip39Check seed parity harness

## Knowledge Gaps
- **127 isolated node(s):** `install.sh script`, `IDLE`, `OUTGOING_RINGING`, `INCOMING_RINGING`, `CONNECTING` (+122 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **41 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MaximaNode` connect `MaximaNode` to `android.view.View`, `DirectReachability.java`, `MiniData`, `.render`, `.main`, `android.content.Context`, `.add`, `ChatEngine`, `MaximaIdentity`, `Contact`, `.startPumping`, `JPanel`, `.fromPhrase`, `javax.swing.JComponent`, `Group`, `.manualForward`, `HostConnection`, `.sendRaw`, `HostPool`, `.onCreate`, `DesktopNode`, `Tier1Services`, `.processMessage`, `BlobStore`, `Outbox`, `.bootJarEngine`, `DesktopMain`, `MaximaNode.java`, `ReachabilityManager`, `Mailbox`, `FileStore`, `.main`, `DirectEndpoint`, `ReachabilityManager.java`, `MaximaMessage`, `.main`?**
  _High betweenness centrality (0.115) - this node is a cross-community bridge._
- **Why does `MiniData` connect `MiniData` to `MaximaErrorMsg`, `MLSPacketGETResp`, `java.io.DataInputStream`, `Coin`, `MaximaManager.java`, `JsonDB`, `MiniByte`, `Maths`, `MiniNumber`, `MaximaIdentity`, `TxPoW`, `.log`, `Message`, `MaximaManager`, `GenerateKey`, `JarEngine`, `.processMessage`, `.processMessage`, `Address`, `.build`, `MMRData`, `MaximaWallet`, `MLSPacketSET`, `MLSPacketGETReq`, `Greeting`?**
  _High betweenness centrality (0.093) - this node is a cross-community bridge._
- **Why does `JSONObject` connect `java.io.DataInputStream` to `MLSPacketGETResp`, `MaximaDB`, `Coin`, `MaximaManager.java`, `JsonDB`, `MiniNumber`, `DesktopJarEngine`, `MiniData`, `JPanel`, `TxPoW`, `WalletPage`, `.log`, `MaximaManager`, `JSONStreamAware`, `JarEngine`, `.processMessage`, `DesktopWalletPublisher`, `.processMessage`, `Address`, `Main`, `MMRData`, `MMR`, `MLSPacketSET`, `JSONWriter`, `GatewayNode`, `ItemList`, `DesktopNodeLink`?**
  _High betweenness centrality (0.065) - this node is a cross-community bridge._
- **What connects `install.sh script`, `IDLE`, `OUTGOING_RINGING` to the rest of the system?**
  _127 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `android.view.View` be split into smaller, more focused modules?**
  _Cohesion score 0.07343987823439878 - nodes in this community are weakly interconnected._
- **Should `MiniNumber` be split into smaller, more focused modules?**
  _Cohesion score 0.06382978723404255 - nodes in this community are weakly interconnected._
- **Should `ChatActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.09957325746799431 - nodes in this community are weakly interconnected._