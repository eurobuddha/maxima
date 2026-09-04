# Graph Report - maxima  (2026-09-04)

## Corpus Check
- 434 files · ~469,065 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 6409 nodes · 19408 edges · 221 communities (164 shown, 57 thin omitted)
- Extraction: 86% EXTRACTED · 14% INFERRED · 0% AMBIGUOUS · INFERRED: 2705 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `f6c7e273`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- android.view.View
- TxPoW
- ChatActivity
- Streamable
- MiniData
- .render
- UpnpIgd
- MaximaApiReceiver
- .onCreate
- DirectReachability
- Coin
- JSONObject
- JsonDB
- MiniByte
- ChatsPanel
- .serialise
- ChatEngine
- MiniNumber
- DesktopJarEngine
- MaximaIdentity
- CloudChatActivity
- MiniData
- android.content.SharedPreferences
- javax.swing.JComponent
- TxPoW
- .fromPhrase
- Theme
- WalletPage
- javax.swing.JLabel
- MiniFile
- Group
- Message
- .startReachability
- MaximaManager
- MaximaNode
- CryptoPackage
- HostConnection
- ChatPay
- .registerOn
- HostPool
- LockGate
- .DesktopNode
- .build
- JarEngine
- DesktopImagePrep
- .processMessage
- SocketTransport
- WalletPanel
- ServiceRegistry
- .log
- ParlonsCore
- .dp
- MediaService
- Classic Maxima Feature Audit
- .deserialise
- MaximaWindow
- .record
- Outbox
- Maxima — an annotated walkthrough
- java.io.DataInputStream
- MessageProcessor
- WalletPublisher
- RelayServer
- android.graphics.Canvas
- MaximaService
- .main
- MiniByte
- DesktopMain
- ChatPort
- Writer
- .recordVoiceDialog
- ChatsPage
- android.app.Activity
- Contact
- WalletCore
- ReachabilityManager
- MLSPacketGETResp
- .render
- NodeGateway
- Mailbox
- MMR
- FileStore
- DeterministicRsaTest
- Cb
- WalletPublisher
- MLSPacketSET
- .main
- PortalCallManager
- org.junit.Test
- org.json.JSONObject
- NatPmp
- DirectEndpoint
- DesktopNode
- ReachabilityManager.java
- JSONParser
- ZoomImageView
- Tier1Services
- Greeting
- PortMapper
- MiniStunTest
- CloudSession
- android.content.Context
- GatewayNode
- DesktopWalletLedger
- BackupActivity
- SearchActivity
- ItemList
- CloudWalletPage
- .stats
- Util
- .merge
- .publicKeyHex
- MaximaContactManager
- android.graphics.Bitmap
- .onSignal
- Phases
- MainActivity
- DevicePairing
- .main
- Annotated Maxima Frame (1225 bytes)
- CloudChatsPage
- .decode
- Desktop Node Build Workflow
- Classic Maxima — complete feature audit
- Maxima — wire-compatible reimplementation
- .main
- The phone app and contacts suite — design
- The desktop node — set-and-forget relays for everyone
- .onSignal
- MLSPacketGETResp
- DirectReachability.java
- Capabilities
- .main
- AutoStart
- JSONArray
- Amounts
- Wallet
- .mailboxAckCanonical
- RelayRuntime
- Maxima for Minima Core — what this is
- CoinAggregator
- CallManager
- Maths
- :core JVM library
- The Interop Gate (byte-exactness)
- RelayHost
- .keyPair
- OnboardingActivity
- Presence
- FastByteArrayStream
- MaxTxPoW
- AppLock
- Threat model and residual risks
- gradlew
- OnboardingActivity
- SeedStore
- MaximaApiMessages
- PaymentSender
- maxima — working rules
- Seed derivation from BIP39 mnemonic
- GatewayNode
- Pre-commit version-bump hook
- install.sh
- pre-commit
- deploy-relay.sh
- verify-relay.sh
- PaymentSender
- Classic carriers have a body (opaque re-emit)
- CTRL/TYPE_MLS bare Mx key (no @host) bug
- Reliability (dedup, replay window, outbox retry)
- SqlDB
- .onPushedMessage
- VoiceNote
- SeedStore
- .toSeed
- .main
- .rebuild
- DirAnswer
- PrefsKeyUses
- PortalService.java
- javax.crypto.SecretKey
- java.awt.image.BufferedImage
- WalletLedger
- BackupManager.java
- .doWork
- CoinSelector
- Parlons Node — VPS setup
- .deliver
- Set up Parlons Cloud on your own VPS
- MLSPacketGETReq
- WaveformView
- .install
- Canvas
- ImageViewer
- TransferableImage
- Blocker
- Parlons Cloud — threat model
- BackupCrypto
- Sdp
- BackupCrypto
- Sdp
- Main
- ImageTools
- .parse
- State
- Presence
- DesktopQr
- JSONAware
- deploy-parlons-cloud.sh
- deploy-parlons-node.sh

## God Nodes (most connected - your core abstractions)
1. `JSONObject` - 277 edges
2. `MiniData` - 237 edges
3. `MaximaNode` - 204 edges
4. `MiniNumber` - 144 edges
5. `MaximaIdentity` - 139 edges
6. `ChatEngine` - 114 edges
7. `MiniData` - 113 edges
8. `Contact` - 104 edges
9. `ChatActivity` - 95 edges
10. `Streamable` - 95 edges

## Surprising Connections (you probably didn't know these)
- `Desktop Node Build Workflow` --references--> `:desktop node`  [INFERRED]
  .github/workflows/desktop-node.yml → README.md
- `tools/vectorgen/Annotate.java` --references--> `Annotated Maxima Frame (1225 bytes)`  [INFERRED]
  WALKTHROUGH.md → docs/annotated-frame.txt
- `ChatActivity` --references--> `PaymentSender`  [EXTRACTED]
  maxima/app/src/main/java/com/eurobuddha/maxima/app/chat/ChatActivity.java → app/src/main/java/com/eurobuddha/maxima/app/wallet/PaymentSender.java
- `WalletPage` --references--> `MaximaWallet`  [EXTRACTED]
  maxima/app/src/main/java/com/eurobuddha/maxima/app/ui/WalletPage.java → app/src/main/java/com/eurobuddha/maxima/app/wallet/MaximaWallet.java
- `WalletPage` --references--> `NodeLink`  [EXTRACTED]
  maxima/app/src/main/java/com/eurobuddha/maxima/app/ui/WalletPage.java → app/src/main/java/com/eurobuddha/maxima/app/wallet/NodeLink.java

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Maxima frame nested envelope structure** — walkthrough_maxima_message, walkthrough_maxima_internal, walkthrough_crypto_package, walkthrough_maxima_package [EXTRACTED 0.90]
- **Byte-exact interop gate toolchain** — readme_interop_gate, readme_parity_test, readme_vectorgen, readme_minima_reference_jar, walkthrough_carrier_check [EXTRACTED 0.85]
- **Tier 2 map-probe-advertise reachability pipeline** — docs_design_tier2_portmap, docs_design_tier2_direct_endpoint, docs_design_tier2_probe_dial, docs_design_tier2_direct_reachability, docs_design_desktop_reachability_manager [EXTRACTED 0.85]

## Communities (221 total, 57 thin omitted)

### Community 0 - "android.view.View"
Cohesion: 0.06
Nodes (34): android.app.Notification, android.graphics.drawable.Drawable, android.graphics.drawable.GradientDrawable, android.graphics.Typeface, android.os.Bundle, android.os.Handler, android.view.View, android.widget.BaseAdapter (+26 more)

### Community 1 - "TxPoW"
Cohesion: 0.09
Nodes (8): MiniData, Override, Magic, Override, TxHeader, MiniData, Override, TxPoW

### Community 2 - "ChatActivity"
Cohesion: 0.07
Nodes (16): Adapter, ChatActivity, Bitmap, EditText, Entry, ImageView, LinearLayout, LruCache (+8 more)

### Community 3 - "Streamable"
Cohesion: 0.05
Nodes (25): java.nio.charset.Charset, java.security.SecureRandom, java.sql.ResultSet, MinimaDB, Main, MaximaErrorMsg, DataInputStream, MaximaInternal (+17 more)

### Community 4 - "MiniData"
Cohesion: 0.05
Nodes (18): Codec, Hex, MiniData, Override, MiniNumber, Override, MiniString, Streamable (+10 more)

### Community 5 - ".render"
Cohesion: 0.08
Nodes (14): BottomSheetDialog, Drawable, EditText, LayoutParams, LinearLayout, TextView, Kit, OnToggle (+6 more)

### Community 7 - "MaximaApiReceiver"
Cohesion: 0.10
Nodes (14): android.content.BroadcastReceiver, android.content.Intent, BootReceiver, Override, HeartbeatReceiver, Intent, Override, Intent (+6 more)

### Community 8 - ".onCreate"
Cohesion: 0.09
Nodes (15): CallActivity, LinearLayout, OnClickListener, Override, SurfaceViewRenderer, TextView, Listener, State (+7 more)

### Community 9 - "DirectReachability"
Cohesion: 0.13
Nodes (3): AndroidContribution, Override, DirectReachability

### Community 10 - "Coin"
Cohesion: 0.02
Nodes (25): BuiltTxn, InputCoin, MiniData, MiniNumber, Output, TxnFactory, DataInputStream, Override (+17 more)

### Community 11 - "JSONObject"
Cohesion: 0.07
Nodes (4): Client, ParlonsRemote, PushListener, JSONObject

### Community 12 - "JsonDB"
Cohesion: 0.07
Nodes (6): UserDB, MiniData, MiniNumber, Override, JsonDB, MiniUtil

### Community 13 - "MiniByte"
Cohesion: 0.05
Nodes (12): MaximaTransport, NotifyListener, DataInputStream, Override, MaximaCTRLMessage, MiniData, NIOManager, TrafficListener (+4 more)

### Community 14 - "ChatsPanel"
Cohesion: 0.07
Nodes (5): SimpleDateFormat, ChatsPanel, Hit, Entry, java.awt.event.MouseEvent

### Community 15 - ".serialise"
Cohesion: 0.05
Nodes (17): MxAddressTest, KeyPair, Result, MlsClient, MiniData, MiniData, Built, MiniData (+9 more)

### Community 16 - "ChatEngine"
Cohesion: 0.09
Nodes (5): CallSignals, ChatEngine, Entry, Listener, ChatMessage

### Community 17 - "MiniNumber"
Cohesion: 0.04
Nodes (11): DataInputStream, MiniNumber, Token, MiniData, MiniNumber, Override, Magic, MathContext (+3 more)

### Community 18 - "DesktopJarEngine"
Cohesion: 0.09
Nodes (5): DesktopJarEngine, Inbound, MaximaMessage, Override, Preferences

### Community 19 - "MaximaIdentity"
Cohesion: 0.06
Nodes (13): DeterministicRsa, Hkdf, MaximaCrypto, Bip39, Keys, Created, MaximaIdentity, MaximaSender (+5 more)

### Community 20 - "CloudChatActivity"
Cohesion: 0.07
Nodes (17): Adapter, CloudChatActivity, Holder, Bitmap, EditText, ImageView, Intent, LinearLayout (+9 more)

### Community 21 - "MiniData"
Cohesion: 0.03
Nodes (24): MiniData, DataInputStream, Signature, SignatureProof, MiniData, TreeKey, TreeKeyNode, MiniData (+16 more)

### Community 22 - "android.content.SharedPreferences"
Cohesion: 0.10
Nodes (4): android.content.SharedPreferences, HomeStore, SwarmStore, PrefsKeyUses

### Community 23 - "javax.swing.JComponent"
Cohesion: 0.08
Nodes (21): Summary, JLabel, ContactsPanel, Graphics, JDialog, JLabel, JTextField, QrDisc (+13 more)

### Community 24 - "TxPoW"
Cohesion: 0.07
Nodes (10): TxPoWTree, TxPoWTreeNode, DataInputStream, MiniData, Override, TxHeader, DataOutputStream, MiniData (+2 more)

### Community 25 - ".fromPhrase"
Cohesion: 0.11
Nodes (6): Resolved, MeshForwardTest, BooleanSupplier, MediaRelayTest, BooleanSupplier, RelayKeepaliveTest

### Community 26 - "Theme"
Cohesion: 0.04
Nodes (28): Preferences, DownFab, JTextField, Override, PlayButton, SendFab, SendFabLike, SimpleDoc (+20 more)

### Community 27 - "WalletPage"
Cohesion: 0.14
Nodes (9): BottomSheetDialog, Drawable, ImageView, LayoutParams, LinearLayout, Override, TextView, WalletPage (+1 more)

### Community 28 - "javax.swing.JLabel"
Cohesion: 0.07
Nodes (19): Bubble, JDialogRef, DesktopExplain, JLabel, Override, PlaceholderField, ScrollableColumn, WrapText (+11 more)

### Community 30 - "Group"
Cohesion: 0.08
Nodes (6): Group, Receipt, ChatTest, MaximaMessage, BooleanSupplier, LiveChatTest

### Community 31 - "Message"
Cohesion: 0.09
Nodes (8): Result, Result, maxima, MiniData, Override, MaxMsgHandler, Message, MessageListener

### Community 33 - "MaximaManager"
Cohesion: 0.05
Nodes (6): BackupBundle, MaxJarNode, MaximaManager, ServerSocket, Override, org.junit.After

### Community 34 - "MaximaNode"
Cohesion: 0.06
Nodes (8): EventListener, Inbound, MiniData, Override, LogListener, MaximaNode, MediaWire, LanPeerTest

### Community 35 - "CryptoPackage"
Cohesion: 0.07
Nodes (12): javax.crypto.Cipher, MaximaMessage, MiniData, CryptoPackage, DataInputStream, MiniData, Override, EncryptDecrypt (+4 more)

### Community 36 - "HostConnection"
Cohesion: 0.11
Nodes (11): HostConnection, Inbound, DataInputStream, DataOutputStream, MaximaCTRLMessage, MiniData, Override, Socket (+3 more)

### Community 37 - "ChatPay"
Cohesion: 0.12
Nodes (4): ChatMediaTest, ChatPayTest, ChatMedia, ChatPay

### Community 38 - ".registerOn"
Cohesion: 0.05
Nodes (10): BackupSource, Entry, Live, NodeControl, ParlonsControl, PaySource, SettingsSink, StatusSource (+2 more)

### Community 39 - "HostPool"
Cohesion: 0.09
Nodes (6): MeritOrderTest, HostPool, HostRecord, Override, LiveMultiHomeTest, Sink

### Community 40 - "LockGate"
Cohesion: 0.09
Nodes (5): androidx.fragment.app.FragmentActivity, AppLock, Callback, LockGate, LockGate

### Community 42 - ".build"
Cohesion: 0.13
Nodes (7): JDialog, JScrollPane, JTextArea, HoverButton, JDialog, SettingsPanel, MiniNumber

### Community 43 - "JarEngine"
Cohesion: 0.12
Nodes (4): Inbound, JarEngine, MaximaMessage, Override

### Community 44 - "DesktopImagePrep"
Cohesion: 0.27
Nodes (3): DesktopImagePrep, BufferedImage, Result

### Community 45 - ".processMessage"
Cohesion: 0.11
Nodes (5): MiniData, MaximaHost, Override, Override, NIOClient

### Community 46 - "SocketTransport"
Cohesion: 0.10
Nodes (10): java.net.Socket, DataInputStream, DataOutputStream, MaximaCTRLMessage, MiniByte, Override, ServerSocket, Socket (+2 more)

### Community 47 - "WalletPanel"
Cohesion: 0.05
Nodes (9): Chirp, PayResult, WalletPanel, DesktopNodeLink, DesktopWallet, Cb, DesktopWalletPublisher, java.util.prefs.Preferences (+1 more)

### Community 48 - "ServiceRegistry"
Cohesion: 0.08
Nodes (11): MiniByte, MiniData, MiniString, Override, RpcEnvelope, Pending, ResponseHandler, RpcPeer (+3 more)

### Community 49 - ".log"
Cohesion: 0.10
Nodes (7): java.sql.PreparedStatement, MiniData, MaximaContact, Override, MaximaDB, Override, MiniData

### Community 50 - "ParlonsCore"
Cohesion: 0.06
Nodes (8): Built, CloudPaymentSender, CloudWallet, Entry, Listener, SimpleDateFormat, ParlonsCore, WatchWallet

### Community 51 - ".dp"
Cohesion: 0.09
Nodes (13): LinearLayout, Call, CloudNodePage, Dev, LinearLayout, Override, Override, LinearLayout (+5 more)

### Community 52 - "MediaService"
Cohesion: 0.09
Nodes (7): ChunkSource, Encoded, MediaCodec, MediaManifest, MediaService, BlobStore, MediaTest

### Community 53 - "Classic Maxima Feature Audit"
Cohesion: 0.07
Nodes (34): Contact record (contacts/Contact.java), Phone App and Contacts Suite Design, First contact (QR + invite link, MAX#pubkey#mls), Identity is stable, addresses are ephemeral, Resolution ladder (cached, MLS, gossip, mailbox), Never advertise hope (prove port before announce), ReachabilityManager (router magic), Relay gossip discovery (+26 more)

### Community 54 - ".deserialise"
Cohesion: 0.09
Nodes (11): MiniData, Override, MaximumMessage, CryptoUnitTest, IdentityTest, MiniData, CryptoPackage, java.security.PrivateKey (+3 more)

### Community 55 - "MaximaWindow"
Cohesion: 0.07
Nodes (14): BufferedImage, IntConsumer, JPanel, Listener, Timer, MaximaWindow, Responsive, StatusDot (+6 more)

### Community 56 - ".record"
Cohesion: 0.07
Nodes (15): DesktopCalls, Override, Graphics, JDialog, Override, Timer, LiveWave, Recorder (+7 more)

### Community 57 - "Outbox"
Cohesion: 0.10
Nodes (8): DedupCache, Verdict, ACCEPT, DUPLICATE, STALE, Item, Outbox, ReliabilityUnitTest

### Community 58 - "Maxima — an annotated walkthrough"
Cohesion: 0.06
Nodes (29): Design constraints, Layout, Limits & storage boundaries, Live validation against a running node, Maxima — decentralised information layer for Minima, Protocol notes worth knowing, Reading the code, Run a node (desktop) (+21 more)

### Community 59 - "java.io.DataInputStream"
Cohesion: 0.03
Nodes (29): DataInputStream, DataOutputStream, Override, Override, Override, Override, DataOutputStream, MiniData (+21 more)

### Community 60 - "MessageProcessor"
Cohesion: 0.06
Nodes (7): Override, MessageProcessor, MessageStack, Override, TimerMessage, Override, TimerProcessor

### Community 61 - "WalletPublisher"
Cohesion: 0.15
Nodes (3): Cb, ROnly, WalletPublisher

### Community 62 - "RelayServer"
Cohesion: 0.14
Nodes (12): DataOutputStream, Greeting, Conn, DataInputStream, DataOutputStream, MaximaCTRLMessage, MaximaPackage, MiniData (+4 more)

### Community 63 - "android.graphics.Canvas"
Cohesion: 0.18
Nodes (5): android.graphics.Canvas, android.graphics.Paint, android.util.AttributeSet, Override, Identicon

### Community 64 - "MaximaService"
Cohesion: 0.09
Nodes (11): android.net.nsd.NsdManager, android.net.nsd.NsdServiceInfo, LanDiscovery, Peers, Override, MaximaService, DiscoveryListener, MulticastLock (+3 more)

### Community 65 - ".main"
Cohesion: 0.14
Nodes (4): Override, Reads, CodecUnitTest, Streamable

### Community 66 - "MiniByte"
Cohesion: 0.11
Nodes (6): Override, MiniByte, MiniByte, MiniData, Override, MaximaCTRLMessage

### Community 67 - "DesktopMain"
Cohesion: 0.17
Nodes (7): Bootstrap, DesktopMain, State, java.awt.MenuItem, java.awt.TrayIcon, java.io.RandomAccessFile, RandomAccessFile

### Community 68 - "ChatPort"
Cohesion: 0.10
Nodes (5): Intent, MiniNumber, ChatNotifier, Entry, ChatPort

### Community 69 - "Writer"
Cohesion: 0.09
Nodes (7): JsonTest, ClassicChat, ContactCtrl, Parsed, Json, Writer, DirectoryUnitTest

### Community 70 - ".recordVoiceDialog"
Cohesion: 0.12
Nodes (6): VoiceNote, WaveformView, MediaRecorder, VoiceNote, WaveformView, Handler

### Community 71 - "ChatsPage"
Cohesion: 0.21
Nodes (4): Adapter, ChatsPage, Override, Row

### Community 72 - "android.app.Activity"
Cohesion: 0.08
Nodes (9): android.app.Activity, android.view.ViewGroup, EditText, Explain, TextView, Ui, Explain, TextView (+1 more)

### Community 73 - "Contact"
Cohesion: 0.09
Nodes (13): Chat, ScanSink, ContactsPage, BottomSheetDialog, Drawable, EditText, LayoutParams, LinearLayout (+5 more)

### Community 74 - "WalletCore"
Cohesion: 0.08
Nodes (6): EditText, MaximaWallet, Address, WalletCore, MiniData, MaximaWallet

### Community 76 - "MLSPacketGETResp"
Cohesion: 0.24
Nodes (3): MiniString, Override, MLSPacketGETResp

### Community 77 - ".render"
Cohesion: 0.13
Nodes (5): EditText, LinearLayout, Override, TextView, SettingsPage

### Community 78 - "NodeGateway"
Cohesion: 0.07
Nodes (13): com.sun.net.httpserver.HttpExchange, com.sun.net.httpserver.HttpServer, MiniNumber, Bucket, NodeGateway, RateLimiter, Address, Balance (+5 more)

### Community 79 - "Mailbox"
Cohesion: 0.11
Nodes (10): Box, Item, Mailbox, Result, DUPLICATE, QUOTA_BYTES, QUOTA_COUNT, STORED (+2 more)

### Community 80 - "MMR"
Cohesion: 0.05
Nodes (15): Address, MiniString, ScriptProof, MiniString, DataInputStream, DataOutputStream, MMR, MMRData (+7 more)

### Community 81 - "FileStore"
Cohesion: 0.06
Nodes (11): KeyUses, BackupBundle, CloudBackupManager, BackupBundle, CloudKeyUses, Main, Config, FileStore (+3 more)

### Community 83 - "Cb"
Cohesion: 0.08
Nodes (8): android.widget.Switch, C, CloudContactsPage, EditText, Override, Cb, CloudSettingsActivity, ActivityResultLauncher

### Community 84 - "WalletPublisher"
Cohesion: 0.11
Nodes (3): Cb, ROnly, WalletPublisher

### Community 85 - "MLSPacketSET"
Cohesion: 0.10
Nodes (5): DataInputStream, MiniString, Override, MLSPacketSET, MLSService

### Community 86 - ".main"
Cohesion: 0.17
Nodes (5): MessageListener, BooleanSupplier, LiveMailboxTest, FullSendTest, BooleanSupplier

### Community 88 - "PortalCallManager"
Cohesion: 0.09
Nodes (15): LinearLayout, OnClickListener, Override, SurfaceViewRenderer, TextView, PortalCallActivity, Listener, PortalCallManager (+7 more)

### Community 89 - "org.junit.Test"
Cohesion: 0.10
Nodes (5): MiniDataTest, MiniNumberTest, KeysTest, CapacityScoreTest, org.junit.Test

### Community 90 - "org.json.JSONObject"
Cohesion: 0.06
Nodes (11): Cb, NodeLink, PairingListener, MinimaAPI, MinimaAPIListener, org.json.JSONObject, org.minimarex.minimaapi.MinimaAPI, org.minimarex.minimaapi.MinimaAPIListener (+3 more)

### Community 91 - "NatPmp"
Cohesion: 0.27
Nodes (3): NatPmp, Result, java.net.InetAddress

### Community 92 - "DirectEndpoint"
Cohesion: 0.10
Nodes (8): DirectEndpoint, DataOutputStream, ServerSocket, Sink, Probe, DirectEndpointTest, LiveProbeTest, ProbeTest

### Community 93 - "DesktopNode"
Cohesion: 0.06
Nodes (8): DesktopConnectionFinder, Listener, DesktopEventLog, DesktopNode, Listener, State, DesktopRelayStore, NetworkPanel

### Community 94 - "ReachabilityManager.java"
Cohesion: 0.14
Nodes (8): Listener, pass(), State, ADVERTISED, MAPPING, OFF, PROBING, java.util.function.IntSupplier

### Community 95 - "JSONParser"
Cohesion: 0.05
Nodes (9): JSONStreamAware, JSONValue, Override, JSONWriter, ContainerFactory, ContentHandler, JSONParser, Yylex (+1 more)

### Community 96 - "ZoomImageView"
Cohesion: 0.11
Nodes (13): android.graphics.Matrix, android.view.GestureDetector, android.view.MotionEvent, android.view.ScaleGestureDetector, GestureDetector, Override, ScaleGestureDetector, ZoomImageView (+5 more)

### Community 97 - "Tier1Services"
Cohesion: 0.06
Nodes (6): MiniData, MlsService, Entry, MlsStore, ContributionPolicy, Tier1Services

### Community 98 - "Greeting"
Cohesion: 0.07
Nodes (9): Greeting, MiniNumber, MiniString, Override, Override, MaximaInternal, Frame, KeepaliveUnitTest (+1 more)

### Community 99 - "PortMapper"
Cohesion: 0.22
Nodes (4): Override, Mapping, PortMapper, PortMapLiveTest

### Community 100 - "MiniStunTest"
Cohesion: 0.22
Nodes (6): java.net.DatagramPacket, DatagramPacket, MiniStunTest, DatagramPacket, Override, MiniStun

### Community 101 - "CloudSession"
Cohesion: 0.09
Nodes (7): android.app.Application, Override, MaximaApp, CloudSession, PortalCalls, Override, PortalApp

### Community 102 - "android.content.Context"
Cohesion: 0.11
Nodes (5): android.content.Context, Pssst, ChatPrefs, JarMigration, MlsStore

### Community 104 - "DesktopWalletLedger"
Cohesion: 0.14
Nodes (7): Row, WalletLedger, DesktopWalletLedger, Row, org.json.JSONArray, Row, WalletLedger

### Community 105 - "BackupActivity"
Cohesion: 0.11
Nodes (8): androidx.activity.result.ActivityResultLauncher, BackupActivity, EditText, Override, PwCallback, ConnectionFinder, Listener, RelayStore

### Community 106 - "SearchActivity"
Cohesion: 0.27
Nodes (6): Hit, EditText, LinearLayout, Override, TextView, SearchActivity

### Community 108 - "CloudWalletPage"
Cohesion: 0.08
Nodes (4): CloudWalletPage, Override, Listener, PortalHub

### Community 112 - ".publicKeyHex"
Cohesion: 0.16
Nodes (4): MlsProofTest, MiniString, Override, MLSPacketSET

### Community 114 - "android.graphics.Bitmap"
Cohesion: 0.08
Nodes (8): android.graphics.Bitmap, android.util.LruCache, Avatars, Qr, Avatars, Qr, Paint, ImageLoader

### Community 115 - ".onSignal"
Cohesion: 0.13
Nodes (3): IceCandidate, SessionDescription, PortalIncomingCall

### Community 116 - "Phases"
Cohesion: 0.15
Nodes (12): A. `:core` `portmap` — NAT-PMP and UPnP IGD clients  (pure JVM), B. `:core` `net.DirectEndpoint` — accepting a connection at all, C. Fleet `probe.dial` — third-party reachability proof  (server 0.1.6), D. `:app` `DirectReachability` — the policy loop, E. LAN discovery — mDNS/NSD  (second), F. Wi-Fi Direct / BLE — explicitly deferred, Order and estimates, Phases (+4 more)

### Community 117 - "MainActivity"
Cohesion: 0.09
Nodes (10): ChatHub, Entry, Listener, ActivityResultLauncher, Entry, ObjectAnimator, Override, ScanOptions (+2 more)

### Community 118 - "DevicePairing"
Cohesion: 0.14
Nodes (7): Device, DevicePairing, Result, ALREADY, AUTHORIZED, PENDING, SuppressWarnings

### Community 119 - ".main"
Cohesion: 0.13
Nodes (3): MaximaLoopTest, BooleanSupplier, RelaySelfHealTest

### Community 120 - "Annotated Maxima Frame (1225 bytes)"
Cohesion: 0.23
Nodes (12): Annotated Maxima Frame (1225 bytes), Per-message delivery state (end-to-end ack), MSG_PING (type 8) ack channel + five ack bodies, tools/vectorgen/Annotate.java, Synthetic TxPoW carrier — why we do not mine, CryptoPackage {iv, secret, ciphertext}, HostConnection.receive (receive path), MaximaInternal {from, data, signature} (+4 more)

### Community 121 - "CloudChatsPage"
Cohesion: 0.13
Nodes (5): Adapter, Call, CloudChatsPage, Override, Row

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

### Community 130 - "MLSPacketGETResp"
Cohesion: 0.24
Nodes (3): DataInputStream, Override, MLSPacketGETResp

### Community 132 - "Capabilities"
Cohesion: 0.18
Nodes (3): ReachabilityGateTest, Capabilities, Override

### Community 133 - ".main"
Cohesion: 0.12
Nodes (5): RelayGossipClient, RelayPeers, java.util.concurrent.LinkedBlockingQueue, BooleanSupplier, RelayGossipTest

### Community 135 - "JSONArray"
Cohesion: 0.09
Nodes (4): TxPoWGenerator, JSONArray, ParseException, MiniFormat

### Community 140 - "Maxima for Minima Core — what this is"
Cohesion: 0.29
Nodes (6): For Core specifically, Improved — invisibly, Maxima for Minima Core — what this is, The same — provably, The two artifacts, and how they hold each other up, Store-and-forward mailbox

### Community 142 - "CallManager"
Cohesion: 0.20
Nodes (15): android.media.Ringtone, CallManager, org.webrtc.AudioSource, org.webrtc.AudioTrack, org.webrtc.CameraVideoCapturer, org.webrtc.EglBase, org.webrtc.IceCandidate, org.webrtc.PeerConnection (+7 more)

### Community 145 - ":core JVM library"
Cohesion: 0.33
Nodes (6): Chat as application string (maxima_chat_v1), MaximaService (foreground transport), Maxima Overview — what this is, Two artifacts hold each other up (server + APK on one :core), :core JVM library, Java 11 language level constraint

### Community 146 - "The Interop Gate (byte-exactness)"
Cohesion: 0.33
Nodes (6): The same, provably (wire-for-wire interop), The Interop Gate (byte-exactness), Reference minima.jar, ParityTest / golden vectors, tools/vectorgen, CarrierCheck (reference accepts our unit)

### Community 147 - "RelayHost"
Cohesion: 0.13
Nodes (10): Blocker, CONTRIB_OFF, NEEDS_BATTERY, NEEDS_CHARGING, NEEDS_WIFI, NONE, RelayHost, State (+2 more)

### Community 148 - ".keyPair"
Cohesion: 0.19
Nodes (3): RelayHardeningTest, SizeLimitTest, Annotate

### Community 149 - "OnboardingActivity"
Cohesion: 0.26
Nodes (5): android.widget.Button, Button, LayoutParams, Override, OnboardingActivity

### Community 152 - "MaxTxPoW"
Cohesion: 0.16
Nodes (4): Override, MaximaPackage, Override, MaxTxPoW

### Community 154 - "Threat model and residual risks"
Cohesion: 0.40
Nodes (4): Defended, Not yet addressed, Residual risks — known and accepted, Threat model and residual risks

### Community 155 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

### Community 156 - "OnboardingActivity"
Cohesion: 0.23
Nodes (5): Button, EditText, Override, TextView, OnboardingActivity

### Community 157 - "SeedStore"
Cohesion: 0.22
Nodes (3): BackupBundle, ImportResult, SeedStore

### Community 159 - "PaymentSender"
Cohesion: 0.17
Nodes (3): Arrival, Cb, PaymentSender

### Community 161 - "Seed derivation from BIP39 mnemonic"
Cohesion: 0.67
Nodes (3): Seed derivation from BIP39 mnemonic, BIP39 English wordlist (2048 words), Bip39Check seed parity harness

### Community 174 - "PaymentSender"
Cohesion: 0.17
Nodes (3): Arrival, Cb, PaymentSender

### Community 180 - "VoiceNote"
Cohesion: 0.18
Nodes (3): android.media.MediaRecorder, MediaRecorder, VoiceNote

### Community 183 - ".main"
Cohesion: 0.23
Nodes (5): ServerSocket, PortMapTest, DatagramSocket, java.net.DatagramSocket, java.net.ServerSocket

### Community 184 - ".rebuild"
Cohesion: 0.24
Nodes (3): CloudNodePanelActivity, LinearLayout, Override

### Community 185 - "DirAnswer"
Cohesion: 0.21
Nodes (3): DirAnswer, MiniData, Override

### Community 187 - "PortalService.java"
Cohesion: 0.24
Nodes (5): android.app.Service, android.os.IBinder, Intent, Override, PortalService

### Community 188 - "javax.crypto.SecretKey"
Cohesion: 0.23
Nodes (3): SeedCrypt, javax.crypto.SecretKey, SeedCrypt

### Community 189 - "java.awt.image.BufferedImage"
Cohesion: 0.28
Nodes (5): BufferedImage, TrayIcons, java.awt.image.BufferedImage, MenuItem, TrayIcon

### Community 191 - "BackupManager.java"
Cohesion: 0.24
Nodes (3): BackupManager, Done, IdentityRestore

### Community 192 - ".doWork"
Cohesion: 0.24
Nodes (6): androidx.annotation.NonNull, androidx.work.Worker, androidx.work.WorkerParameters, Override, Result, MaximaWorker

### Community 193 - "CoinSelector"
Cohesion: 0.33
Nodes (3): CoinSelector, InsufficientFundsException, MiniNumber

### Community 194 - "Parlons Node — VPS setup"
Cohesion: 0.18
Nodes (10): 1. Build the jar, 2. Size the box, 3. Give it a sync peer, 4. Seed: fresh vs. migrated (fund-critical — do this by hand), 5. The wallet gateway (phones), 6. Make the fleet the phone default (last step, after ≥2 nodes are live + synced), Locked node → supply the passphrase, Parlons Node — VPS setup (+2 more)

### Community 195 - ".deliver"
Cohesion: 0.31
Nodes (3): android.net.Uri, MiniData, MaximaApiDelivery

### Community 196 - "Set up Parlons Cloud on your own VPS"
Cohesion: 0.20
Nodes (9): Back up your seed — this is money, Firewall, Managing it, Pair your phone, Set up Parlons Cloud on your own VPS, The easy way — one command, The manual way, What's actually running (+1 more)

### Community 197 - "MLSPacketGETReq"
Cohesion: 0.27
Nodes (3): MiniString, Override, MLSPacketGETReq

### Community 200 - "Canvas"
Cohesion: 0.28
Nodes (3): Identicon, Canvas, java.awt.Point

### Community 201 - "ImageViewer"
Cohesion: 0.36
Nodes (3): ImageViewer, javax.swing.JButton, JButton

### Community 202 - "TransferableImage"
Cohesion: 0.36
Nodes (4): TransferableImage, java.awt.datatransfer.DataFlavor, java.awt.datatransfer.Transferable, java.awt.Image

### Community 203 - "Blocker"
Cohesion: 0.29
Nodes (7): Blocker, CONTRIB_OFF, NEEDS_CHARGING, NEEDS_PUBLIC_IP, NEEDS_WIFI, NONE, ROUTER_NO_PORT

### Community 204 - "Parlons Cloud — threat model"
Cohesion: 0.29
Nodes (6): Assets, Controls (built ✓ / planned ◻), Parlons Cloud — threat model, Residual risk (honest), Trust model, Verification

### Community 212 - "State"
Cohesion: 0.40
Nodes (5): State, ADVERTISED, MAPPING, OFF, PROBING

## Knowledge Gaps
- **159 isolated node(s):** `install.sh script`, `IDLE`, `OUTGOING_RINGING`, `INCOMING_RINGING`, `CONNECTING` (+154 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **57 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MaximaNode` connect `MaximaNode` to `android.view.View`, `DirectReachability.java`, `Capabilities`, `.render`, `.main`, `MaximaApiReceiver`, `DirectReachability`, `JSONObject`, `RelayRuntime`, `MaximaIdentity`, `RelayHost`, `android.content.SharedPreferences`, `.fromPhrase`, `javax.swing.JLabel`, `Group`, `.startReachability`, `HostConnection`, `.registerOn`, `HostPool`, `.DesktopNode`, `ServiceRegistry`, `.log`, `ParlonsCore`, `MediaService`, `Outbox`, `MaximaService`, `DesktopMain`, `ChatPort`, `Writer`, `Contact`, `ReachabilityManager`, `.render`, `Mailbox`, `.main`, `org.junit.Test`, `DirectEndpoint`, `DesktopNode`, `ReachabilityManager.java`, `JSONParser`, `Tier1Services`, `Greeting`, `CloudSession`, `.main`, `.main`?**
  _High betweenness centrality (0.128) - this node is a cross-community bridge._
- **Why does `JSONObject` connect `JSONObject` to `android.view.View`, `MLSPacketGETResp`, `Streamable`, `JSONArray`, `Coin`, `JsonDB`, `CoinAggregator`, `MiniByte`, `CallManager`, `MiniNumber`, `DesktopJarEngine`, `CloudChatActivity`, `MiniData`, `android.content.SharedPreferences`, `javax.swing.JComponent`, `TxPoW`, `WalletPage`, `MaximaManager`, `GatewayNode`, `ChatPay`, `.registerOn`, `JarEngine`, `.processMessage`, `WalletPanel`, `.log`, `ParlonsCore`, `SqlDB`, `.dp`, `.onPushedMessage`, `.rebuild`, `java.io.DataInputStream`, `PortalService.java`, `WalletLedger`, `CoinSelector`, `NodeGateway`, `MMR`, `Cb`, `WalletPublisher`, `MLSPacketSET`, `.parse`, `JSONAware`, `PortalCallManager`, `JSONParser`, `CloudSession`, `GatewayNode`, `ItemList`, `CloudWalletPage`, `.onSignal`, `CloudChatsPage`?**
  _High betweenness centrality (0.125) - this node is a cross-community bridge._
- **Why does `MiniData` connect `MiniData` to `MLSPacketGETResp`, `Streamable`, `JSONArray`, `Coin`, `JsonDB`, `MiniByte`, `Maths`, `MiniNumber`, `MaximaIdentity`, `android.content.SharedPreferences`, `TxPoW`, `Message`, `MaximaManager`, `CryptoPackage`, `.processMessage`, `.log`, `SqlDB`, `java.io.DataInputStream`, `WalletCore`, `MMR`, `MLSPacketSET`?**
  _High betweenness centrality (0.112) - this node is a cross-community bridge._
- **What connects `install.sh script`, `IDLE`, `OUTGOING_RINGING` to the rest of the system?**
  _159 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `android.view.View` be split into smaller, more focused modules?**
  _Cohesion score 0.0597979797979798 - nodes in this community are weakly interconnected._
- **Should `TxPoW` be split into smaller, more focused modules?**
  _Cohesion score 0.09247311827956989 - nodes in this community are weakly interconnected._
- **Should `ChatActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.06564364876385337 - nodes in this community are weakly interconnected._