# Graph Report - maxima  (2026-08-20)

## Corpus Check
- 325 files · ~255,488 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 4379 nodes · 13280 edges · 167 communities (133 shown, 34 thin omitted)
- Extraction: 86% EXTRACTED · 14% INFERRED · 0% AMBIGUOUS · INFERRED: 1878 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `45ce233d`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MaximaNode
- ChatActivity
- MaximaSender.java
- AndroidContribution
- Streamable
- ChatEngine
- Bip39
- .render
- BlobStore
- Kit
- Capabilities
- .body
- .serialise
- HostConnection
- ContactsPage
- .sendRaw
- Coin
- MMR
- android.view.View
- Avatars
- Transaction
- Group
- Outbox
- WalletPage
- .getInstance
- MiniNumber
- .fromPhrase
- MainActivity
- MaximaManager.java
- Theme
- ReachabilityManager
- MaximaWallet
- DirectEndpoint
- .main
- MaximaApiReceiver
- MLSPacketSET
- Tier1Services
- MiniData
- Dimension
- Maxima — an annotated walkthrough
- .main
- Witness
- javax.swing.JComponent
- .main
- MiniNumber
- NodeLink
- ChatsPage
- LockGate
- WalletPublisher
- MaximaManager
- java.io.DataInputStream
- MiniByte
- Mailbox
- .run
- .showTokenDetail
- Signature
- FileStore
- .start
- UpnpIgd
- RpcPeer
- RelayRuntime
- android.content.Context
- GatewayNode
- MessageProcessor
- MlsStore
- .sendPayment
- MaxTxPoW
- .fullProtocolRoundTrip
- .log
- PortMapper
- FastByteArrayStream
- CryptoPackage
- DirectReachability
- android.content.SharedPreferences
- .run
- MMREntryNumber
- .main
- .startPumping
- LanDiscovery
- SeedStore
- .deliver
- ChatsPanel
- .processMessage
- .mark
- Phases
- MaximaService
- org.json.JSONObject
- Message
- JSONObject
- Classic Maxima — complete feature audit
- WalletCore
- Maths
- DesktopNode
- MlsClient
- The phone app and contacts suite — design
- .deliver
- DesktopWalletLedger
- MaximaWindow
- MLSPacketGETResp
- AutoStart
- The desktop node — set-and-forget relays for everyone
- .manageLanAndListener
- .processMessage
- .run
- SocketTransport
- Maxima for Minima Core — what this is
- MMRData
- Presence
- javax.crypto.SecretKey
- Result
- RelayServer.java
- MaximaInternal
- MaximaMessage
- Threat model and residual risks
- gradlew
- MaximaIdentity
- MaximaApiMessages
- maxima — working rules
- install.sh
- pre-commit
- deploy-relay.sh
- verify-relay.sh
- JSONParser
- MiniByte
- .ChatsPanel
- Main
- TxPoW
- MLSPacketSET
- JarEngine
- RelayServer
- SettingsPage
- WalletPanel
- MiniFile
- RelayHost
- MMRProof
- DesktopWalletPublisher
- JSONWriter
- KeyUses
- Yylex
- RelayPeers
- ItemList
- ChatPort
- Writer
- ChatHub
- DesktopNodeLink
- ParityTest.java
- Greeting
- .doWork
- MLSPacketGETReq
- MaximaErrorMsg
- MLSPacketGETResp
- Blocker
- CryptoPackage
- SimpleDoc
- MLSPacketGETReq
- .run
- MaximaApp
- State
- .main

## God Nodes (most connected - your core abstractions)
1. `MiniData` - 231 edges
2. `MaximaNode` - 186 edges
3. `MiniNumber` - 138 edges
4. `JSONObject` - 113 edges
5. `MaximaIdentity` - 108 edges
6. `MiniData` - 102 edges
7. `ChatEngine` - 95 edges
8. `Streamable` - 95 edges
9. `Contact` - 82 edges
10. `WalletPage` - 72 edges

## Surprising Connections (you probably didn't know these)
- `AndroidContribution` --implements--> `ContributionPolicy`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/maxima/app/AndroidContribution.java → core/src/main/java/com/eurobuddha/maxima/core/services/ContributionPolicy.java
- `MaximaService` --references--> `ChatEngine`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/maxima/app/MaximaService.java → core/src/main/java/com/eurobuddha/maxima/core/chat/ChatEngine.java
- `MaximaService` --references--> `MaximaNode`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/maxima/app/MaximaService.java → core/src/main/java/com/eurobuddha/maxima/core/MaximaNode.java
- `MaximaService` --references--> `RelayGossipClient`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/maxima/app/MaximaService.java → core/src/main/java/com/eurobuddha/maxima/core/session/RelayGossipClient.java
- `ImportResult` --references--> `MaximaIdentity`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/maxima/app/SeedStore.java → core/src/main/java/com/eurobuddha/maxima/core/identity/MaximaIdentity.java

## Import Cycles
- None detected.

## Communities (167 total, 34 thin omitted)

### Community 0 - "MaximaNode"
Cohesion: 0.05
Nodes (8): Parsed, EventListener, MiniData, Override, LogListener, MaximaNode, LanPeerTest, MaximaLoopTest

### Community 1 - "ChatActivity"
Cohesion: 0.06
Nodes (19): androidx.recyclerview.widget.RecyclerView, Adapter, ChatActivity, Bitmap, EditText, Entry, ImageView, Intent (+11 more)

### Community 2 - "MaximaSender.java"
Cohesion: 0.09
Nodes (9): Sha3Provider, DeterministicRsa, Hashes, Hkdf, MaximaCrypto, MxAddress, MaximaSender, java.security.SecureRandom (+1 more)

### Community 4 - "Streamable"
Cohesion: 0.07
Nodes (13): DataInputStream, MaximaMessage, MaximaPackage, TxPoW, MaxTxPoW, MaximaBrainSmokeTest, Address, Override (+5 more)

### Community 5 - "ChatEngine"
Cohesion: 0.09
Nodes (5): ChatEngine, Entry, Listener, Summary, ChatMessage

### Community 6 - "Bip39"
Cohesion: 0.19
Nodes (3): Bip39, MiniData, Bip39Check

### Community 7 - ".render"
Cohesion: 0.10
Nodes (4): Drawable, IpCallback, Override, NetworkPage

### Community 8 - "BlobStore"
Cohesion: 0.14
Nodes (6): ChunkSource, Encoded, MediaCodec, MediaManifest, BlobStore, MediaTest

### Community 9 - "Kit"
Cohesion: 0.18
Nodes (12): android.widget.LinearLayout, BottomSheetDialog, EditText, LayoutParams, LinearLayout, TextView, Kit, OnToggle (+4 more)

### Community 10 - "Capabilities"
Cohesion: 0.09
Nodes (5): ContactCtrl, Capabilities, Override, Json, DirectoryUnitTest

### Community 11 - ".body"
Cohesion: 0.20
Nodes (6): DataOutputStream, Greeting, Conn, DataInputStream, DataOutputStream, MaximaCTRLMessage

### Community 12 - ".serialise"
Cohesion: 0.08
Nodes (10): MiniData, MiniData, CodecUnitTest, Streamable, LiveContactTest, LiveNodeCheck, LiveSend, ParityTest (+2 more)

### Community 13 - "HostConnection"
Cohesion: 0.07
Nodes (13): HostConnection, Inbound, DataInputStream, DataOutputStream, MiniData, Override, Socket, Sink (+5 more)

### Community 14 - "ContactsPage"
Cohesion: 0.14
Nodes (8): ContactsPage, BottomSheetDialog, Drawable, EditText, LayoutParams, LinearLayout, Override, TextView

### Community 15 - ".sendRaw"
Cohesion: 0.10
Nodes (7): Result, MiniData, MediaWire, LiveMultiHomeTest, LiveRelayTest, SizeLimitTest, Annotate

### Community 16 - "Coin"
Cohesion: 0.05
Nodes (13): Coin, DataInputStream, DataOutputStream, MiniByte, Override, Override, DataInputStream, DataOutputStream (+5 more)

### Community 17 - "MMR"
Cohesion: 0.11
Nodes (4): MiniString, MiniString, MMR, MMREntry

### Community 18 - "android.view.View"
Cohesion: 0.16
Nodes (11): android.graphics.Typeface, android.os.Bundle, android.view.View, android.widget.EditText, android.widget.TextView, androidx.appcompat.app.AppCompatActivity, DateVH, EventLog (+3 more)

### Community 19 - "Avatars"
Cohesion: 0.13
Nodes (9): android.graphics.Bitmap, android.graphics.Canvas, android.graphics.drawable.Drawable, android.graphics.Paint, Avatars, Qr, Identicon, Canvas (+1 more)

### Community 20 - "Transaction"
Cohesion: 0.06
Nodes (11): MiniByte, MiniString, Override, StateVariable, DataOutputStream, MiniData, MiniNumber, Override (+3 more)

### Community 21 - "Group"
Cohesion: 0.11
Nodes (4): Group, Receipt, ChatTest, MaximaMessage

### Community 22 - "Outbox"
Cohesion: 0.10
Nodes (8): DedupCache, Verdict, ACCEPT, DUPLICATE, STALE, Item, Outbox, ReliabilityUnitTest

### Community 23 - "WalletPage"
Cohesion: 0.19
Nodes (6): BottomSheetDialog, ImageView, LayoutParams, LinearLayout, TextView, WalletPage

### Community 24 - ".getInstance"
Cohesion: 0.09
Nodes (7): MiniData, TreeKey, TreeKeyNode, MiniData, Winternitz, org.bouncycastle.pqc.crypto.gmss.util.WinternitzOTSignature, WinternitzOTSignature

### Community 25 - "MiniNumber"
Cohesion: 0.05
Nodes (11): CoinAggregator, MiniNumber, MiniData, MiniNumber, Override, Magic, MiniNumber, MathContext (+3 more)

### Community 26 - ".fromPhrase"
Cohesion: 0.06
Nodes (15): Inbound, MessageListener, BooleanSupplier, LiveChatTest, LiveProbeTest, ServerSocket, SelfTest, FullSendTest (+7 more)

### Community 27 - "MainActivity"
Cohesion: 0.14
Nodes (8): ActivityResultLauncher, androidx.viewpager.widget.ViewPager, Entry, Override, MainActivity, ScanSink, ObjectAnimator, ScanOptions

### Community 28 - "MaximaManager.java"
Cohesion: 0.06
Nodes (13): MinimaDB, TxPoWTree, TxPoWTreeNode, UserDB, ScriptRow, Wallet, DataInputStream, MaximaInternal (+5 more)

### Community 29 - "Theme"
Cohesion: 0.07
Nodes (19): Graphics, QrDisc, Avatar, DKit, HoverButton, IntConsumer, PlaceholderField, RoundPanel (+11 more)

### Community 30 - "ReachabilityManager"
Cohesion: 0.10
Nodes (11): Gates, Listener, pass(), ReachabilityManager, State, ADVERTISED, MAPPING, OFF (+3 more)

### Community 31 - "MaximaWallet"
Cohesion: 0.14
Nodes (3): EditText, MiniData, MaximaWallet

### Community 32 - "DirectEndpoint"
Cohesion: 0.09
Nodes (9): DirectEndpoint, DataOutputStream, ServerSocket, Sink, Probe, DirectEndpointTest, ProbeTest, java.net.ServerSocket (+1 more)

### Community 33 - ".main"
Cohesion: 0.17
Nodes (6): MiniByte, MiniData, MiniString, Override, RpcEnvelope, RpcUnitTest

### Community 34 - "MaximaApiReceiver"
Cohesion: 0.18
Nodes (5): android.content.Intent, Intent, Override, Uri, MaximaApiReceiver

### Community 35 - "MLSPacketSET"
Cohesion: 0.21
Nodes (3): MiniString, Override, MLSPacketSET

### Community 36 - "Tier1Services"
Cohesion: 0.08
Nodes (6): MiniData, Handler, Request, ServiceRegistry, ContributionPolicy, Tier1Services

### Community 37 - "MiniData"
Cohesion: 0.06
Nodes (11): BuiltTxn, InputCoin, MiniData, MiniNumber, Output, TxnFactory, MaximaMessage, MiniData (+3 more)

### Community 38 - "Dimension"
Cohesion: 0.10
Nodes (13): JDialog, JLabel, ContactsPanel, JDialog, JLabel, JTextField, BufferedImage, JLabel (+5 more)

### Community 39 - "Maxima — an annotated walkthrough"
Cohesion: 0.06
Nodes (29): Design constraints, Layout, Limits & storage boundaries, Live validation against a running node, Maxima — decentralised information layer for Minima, Protocol notes worth knowing, Reading the code, Run a node (desktop) (+21 more)

### Community 40 - ".main"
Cohesion: 0.19
Nodes (3): RelayGossipClient, BooleanSupplier, RelayGossipTest

### Community 41 - "Witness"
Cohesion: 0.08
Nodes (7): DataInputStream, Override, TxnRow, Override, ScriptProof, Override, Witness

### Community 42 - "javax.swing.JComponent"
Cohesion: 0.07
Nodes (18): JDialogRef, ScrollableColumn, WrapText, Tab, NetworkPanel, JDialog, SettingsPanel, java.awt.Component (+10 more)

### Community 43 - ".main"
Cohesion: 0.08
Nodes (5): Store, BooleanSupplier, LiveMailboxTest, LiveNetworkExchange, StoreTest

### Community 44 - "MiniNumber"
Cohesion: 0.08
Nodes (8): Override, MiniNumber, MiniData, Override, Magic, Override, TxHeader, java.math.MathContext

### Community 45 - "NodeLink"
Cohesion: 0.18
Nodes (7): Cb, NodeLink, PairingListener, MinimaAPI, MinimaAPIListener, org.minimarex.minimaapi.MinimaAPI, org.minimarex.minimaapi.MinimaAPIListener

### Community 46 - "ChatsPage"
Cohesion: 0.14
Nodes (7): android.widget.BaseAdapter, android.widget.ListView, Adapter, ChatsPage, EditText, Override, Row

### Community 47 - "LockGate"
Cohesion: 0.12
Nodes (4): androidx.fragment.app.FragmentActivity, AppLock, Callback, LockGate

### Community 48 - "WalletPublisher"
Cohesion: 0.14
Nodes (4): Override, Cb, ROnly, WalletPublisher

### Community 49 - "MaximaManager"
Cohesion: 0.06
Nodes (4): MaxJarNode, MaximaContactManager, MaximaManager, org.junit.After

### Community 50 - "java.io.DataInputStream"
Cohesion: 0.05
Nodes (19): Codec, Override, MiniData, Override, MiniString, Reads, Streamable, java.io.DataInputStream (+11 more)

### Community 51 - "MiniByte"
Cohesion: 0.10
Nodes (6): Override, MiniByte, MiniByte, MiniData, Override, MaximaCTRLMessage

### Community 52 - "Mailbox"
Cohesion: 0.19
Nodes (4): Box, Item, Mailbox, HardeningTest

### Community 53 - ".run"
Cohesion: 0.17
Nodes (9): DesktopMain, State, java.awt.MenuItem, java.awt.TrayIcon, java.io.RandomAccessFile, java.nio.channels.FileLock, MenuItem, RandomAccessFile (+1 more)

### Community 54 - ".showTokenDetail"
Cohesion: 0.19
Nodes (4): android.widget.ImageView, Drawable, Amounts, Agg

### Community 55 - "Signature"
Cohesion: 0.15
Nodes (5): DataInputStream, Override, Signature, Override, SignatureProof

### Community 56 - "FileStore"
Cohesion: 0.20
Nodes (3): FileStore, Override, RelayHardeningTest

### Community 57 - ".start"
Cohesion: 0.15
Nodes (8): android.app.Notification, android.content.BroadcastReceiver, BootReceiver, Override, HeartbeatReceiver, Intent, Override, Intent

### Community 58 - "UpnpIgd"
Cohesion: 0.18
Nodes (4): UpnpIgd, ServerSocket, PortMapTest, DatagramSocket

### Community 59 - "RpcPeer"
Cohesion: 0.13
Nodes (4): Pending, ResponseHandler, RpcPeer, LiveTier1Test

### Community 61 - "android.content.Context"
Cohesion: 0.13
Nodes (4): android.content.Context, ChatPrefs, JarMigration, MlsStore

### Community 62 - "GatewayNode"
Cohesion: 0.22
Nodes (3): android.os.Handler, Cb, GatewayNode

### Community 63 - "MessageProcessor"
Cohesion: 0.06
Nodes (7): Override, MessageProcessor, MessageStack, Override, TimerMessage, Override, TimerProcessor

### Community 64 - "MlsStore"
Cohesion: 0.16
Nodes (3): MlsService, Entry, MlsStore

### Community 65 - ".sendPayment"
Cohesion: 0.11
Nodes (3): Arrival, Cb, PaymentSender

### Community 66 - "MaxTxPoW"
Cohesion: 0.09
Nodes (9): Built, Override, MaximaPackage, Override, MaxTxPoW, MiniData, Override, TxPoW (+1 more)

### Community 67 - ".fullProtocolRoundTrip"
Cohesion: 0.13
Nodes (6): MiniData, MiniData, ServerSocket, DataOutputStream, org.junit.Test, VectorGen

### Community 68 - ".log"
Cohesion: 0.08
Nodes (6): java.sql.Connection, java.sql.PreparedStatement, Override, MaximaDB, MaximaHost, SqlDB

### Community 69 - "PortMapper"
Cohesion: 0.12
Nodes (8): NatPmp, Result, Override, Mapping, PortMapper, PortMapLiveTest, java.net.DatagramSocket, java.net.InetAddress

### Community 71 - "CryptoPackage"
Cohesion: 0.09
Nodes (9): java.sql.ResultSet, CryptoPackage, DataInputStream, MiniData, Override, EncryptDecrypt, GenerateKey, MiniData (+1 more)

### Community 73 - "android.content.SharedPreferences"
Cohesion: 0.17
Nodes (4): android.content.SharedPreferences, HomeStore, Override, PrefsKeyUses

### Community 74 - ".run"
Cohesion: 0.11
Nodes (7): android.app.Activity, android.view.ViewGroup, ConnectionFinder, Listener, Explain, TextView, Ui

### Community 75 - "MMREntryNumber"
Cohesion: 0.09
Nodes (7): DataInputStream, DataOutputStream, MiniNumber, Override, Override, Override, MMREntryNumber

### Community 76 - ".main"
Cohesion: 0.11
Nodes (9): MiniData, Override, MaximumMessage, CryptoUnitTest, IdentityTest, MiniData, CryptoPackage, java.security.PrivateKey (+1 more)

### Community 78 - "LanDiscovery"
Cohesion: 0.22
Nodes (7): android.net.nsd.NsdManager, android.net.nsd.NsdServiceInfo, LanDiscovery, DiscoveryListener, MulticastLock, NsdServiceInfo, RegistrationListener

### Community 81 - "ChatsPanel"
Cohesion: 0.12
Nodes (4): SimpleDateFormat, ChatsPanel, Entry, ImageIcon

### Community 82 - ".processMessage"
Cohesion: 0.15
Nodes (4): MiniData, MaximaContact, Override, MiniData

### Community 83 - ".mark"
Cohesion: 0.24
Nodes (5): BufferedImage, TrayIcons, DesktopQr, java.awt.Graphics2D, java.awt.image.BufferedImage

### Community 84 - "Phases"
Cohesion: 0.15
Nodes (12): A. `:core` `portmap` — NAT-PMP and UPnP IGD clients  (pure JVM), B. `:core` `net.DirectEndpoint` — accepting a connection at all, C. Fleet `probe.dial` — third-party reachability proof  (server 0.1.6), D. `:app` `DirectReachability` — the policy loop, E. LAN discovery — mDNS/NSD  (second), F. Wi-Fi Direct / BLE — explicitly deferred, Order and estimates, Phases (+4 more)

### Community 85 - "MaximaService"
Cohesion: 0.11
Nodes (6): android.app.Service, android.os.IBinder, Override, MaximaService, MediaService, NetworkCallback

### Community 87 - "Message"
Cohesion: 0.11
Nodes (7): Result, maxima, MiniData, Override, MaxMsgHandler, Message, MessageListener

### Community 88 - "JSONObject"
Cohesion: 0.05
Nodes (13): CoinSelector, InsufficientFundsException, MiniNumber, TxPoWGenerator, Override, JsonDB, MiniUtil, JSONArray (+5 more)

### Community 89 - "Classic Maxima — complete feature audit"
Cohesion: 0.18
Nodes (10): 1. `maxima`, 2. `maxcontacts`, 3. `maxextra`, 4. Standalone crypto utilities, 5. Events published to apps, 6. Background behaviour, 6b. Transport-liveness parity (the NIO layer under Maxima), 7. Where classic's model is wrong for phones (+2 more)

### Community 90 - "WalletCore"
Cohesion: 0.11
Nodes (5): MiniData, WalletCore, BIP39, MiniData, DesktopWallet

### Community 92 - "DesktopNode"
Cohesion: 0.12
Nodes (4): DesktopEventLog, DesktopNode, State, JLabel

### Community 94 - "The phone app and contacts suite — design"
Cohesion: 0.20
Nodes (9): 1. The central problem: identity is stable, addresses are not, 2. Resolution ladder, 3. First contact — the honest hard problem, 4. Contacts suite, 5. App architecture, 6. What "production grade" requires, 7. Classic parity still outstanding, 8. Deliberately NOT copied from classic (+1 more)

### Community 95 - ".deliver"
Cohesion: 0.31
Nodes (3): android.net.Uri, MiniData, MaximaApiDelivery

### Community 96 - "DesktopWalletLedger"
Cohesion: 0.20
Nodes (5): Row, WalletLedger, DesktopWalletLedger, Row, org.json.JSONArray

### Community 97 - "MaximaWindow"
Cohesion: 0.09
Nodes (12): IntConsumer, JLabel, JPanel, MaximaWindow, Responsive, StatusDot, TabStrip, java.awt.CardLayout (+4 more)

### Community 98 - "MLSPacketGETResp"
Cohesion: 0.24
Nodes (3): MiniString, Override, MLSPacketGETResp

### Community 101 - "The desktop node — set-and-forget relays for everyone"
Cohesion: 0.20
Nodes (9): Architecture, Deployment notes, Discovery — relay gossip, in classic's own vocabulary, Packaging, CI, signing, The desktop app — chat client + set-and-forget relay, The desktop node — set-and-forget relays for everyone, The router magic — never advertise hope, Verification (+1 more)

### Community 103 - ".processMessage"
Cohesion: 0.10
Nodes (5): Override, Override, NIOClient, NIOManager, TrafficListener

### Community 104 - ".run"
Cohesion: 0.11
Nodes (5): Bootstrap, DesktopConnectionFinder, Listener, DesktopRelayStore, java.util.concurrent.ThreadFactory

### Community 105 - "SocketTransport"
Cohesion: 0.14
Nodes (8): DataInputStream, DataOutputStream, MaximaCTRLMessage, MiniByte, Override, Socket, Peer, SocketTransport

### Community 107 - "Maxima for Minima Core — what this is"
Cohesion: 0.33
Nodes (5): For Core specifically, Improved — invisibly, Maxima for Minima Core — what this is, The same — provably, The two artifacts, and how they hold each other up

### Community 108 - "MMRData"
Cohesion: 0.13
Nodes (5): Override, MMRData, MiniByte, Override, MMRProofChunk

### Community 111 - "Result"
Cohesion: 0.40
Nodes (5): Result, DUPLICATE, QUOTA_BYTES, QUOTA_COUNT, STORED

### Community 112 - "RelayServer.java"
Cohesion: 0.14
Nodes (4): Result, Frame, KeepaliveUnitTest, WireProbe

### Community 114 - "MaximaMessage"
Cohesion: 0.09
Nodes (6): Greeting, MiniNumber, MiniString, Override, Override, MaximaMessage

### Community 115 - "Threat model and residual risks"
Cohesion: 0.40
Nodes (4): Defended, Not yet addressed, Residual risks — known and accepted, Threat model and residual risks

### Community 116 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

### Community 117 - "MaximaIdentity"
Cohesion: 0.08
Nodes (11): Chat, Contact, Override, KeyPair, Keys, Created, MiniData, MaximaIdentity (+3 more)

### Community 128 - "JSONParser"
Cohesion: 0.12
Nodes (4): ContainerFactory, ContentHandler, JSONParser, Yytoken

### Community 129 - "MiniByte"
Cohesion: 0.11
Nodes (5): DataInputStream, MaximaCTRLMessage, MiniData, Override, MiniByte

### Community 130 - ".ChatsPanel"
Cohesion: 0.12
Nodes (7): JTextArea, JTextField, SendFab, SendFabLike, Btn, Icons, java.awt.Graphics

### Community 131 - "Main"
Cohesion: 0.12
Nodes (5): MaximaTransport, Main, NotifyListener, NetworkManager, P2PManager

### Community 132 - "TxPoW"
Cohesion: 0.11
Nodes (7): DataInputStream, MiniData, TxHeader, DataOutputStream, MiniData, Override, TxPoW

### Community 133 - "MLSPacketSET"
Cohesion: 0.10
Nodes (5): DataInputStream, MiniString, Override, MLSPacketSET, MLSService

### Community 134 - "JarEngine"
Cohesion: 0.15
Nodes (4): Inbound, JarEngine, MaximaMessage, Override

### Community 135 - "RelayServer"
Cohesion: 0.14
Nodes (4): Stats, PrivateKey, RateLimit, RelayServer

### Community 136 - "SettingsPage"
Cohesion: 0.16
Nodes (3): EditText, Override, SettingsPage

### Community 137 - "WalletPanel"
Cohesion: 0.16
Nodes (3): JLabel, Row, WalletPanel

### Community 139 - "RelayHost"
Cohesion: 0.13
Nodes (10): Blocker, CONTRIB_OFF, NEEDS_BATTERY, NEEDS_CHARGING, NEEDS_WIFI, NONE, RelayHost, State (+2 more)

### Community 140 - "MMRProof"
Cohesion: 0.16
Nodes (4): CoinProof, DataInputStream, DataInputStream, MMRProof

### Community 141 - "DesktopWalletPublisher"
Cohesion: 0.24
Nodes (3): Cb, DesktopWalletPublisher, Override

### Community 142 - "JSONWriter"
Cohesion: 0.20
Nodes (3): JSONValue, Override, JSONWriter

### Community 147 - "ChatPort"
Cohesion: 0.20
Nodes (3): ChatNotifier, Entry, ChatPort

### Community 149 - "ChatHub"
Cohesion: 0.21
Nodes (3): ChatHub, Entry, Listener

### Community 152 - "Greeting"
Cohesion: 0.25
Nodes (4): Greeting, MiniNumber, MiniString, Override

### Community 153 - ".doWork"
Cohesion: 0.27
Nodes (6): androidx.annotation.NonNull, androidx.work.Worker, androidx.work.WorkerParameters, Override, Result, MaximaWorker

### Community 154 - "MLSPacketGETReq"
Cohesion: 0.27
Nodes (3): MiniString, Override, MLSPacketGETReq

### Community 155 - "MaximaErrorMsg"
Cohesion: 0.29
Nodes (4): DataInputStream, MiniString, Override, MaximaErrorMsg

### Community 157 - "Blocker"
Cohesion: 0.29
Nodes (7): Blocker, CONTRIB_OFF, NEEDS_CHARGING, NEEDS_PUBLIC_IP, NEEDS_WIFI, NONE, ROUTER_NO_PORT

### Community 159 - "SimpleDoc"
Cohesion: 0.38
Nodes (3): SimpleDoc, DocumentEvent, DocumentListener

### Community 162 - "MaximaApp"
Cohesion: 0.50
Nodes (3): android.app.Application, Override, MaximaApp

### Community 163 - "State"
Cohesion: 0.40
Nodes (5): State, ADVERTISED, MAPPING, OFF, PROBING

## Knowledge Gaps
- **100 isolated node(s):** `install.sh script`, `OFF`, `MAPPING`, `PROBING`, `ADVERTISED` (+95 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **34 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MaximaNode` connect `MaximaNode` to `MaximaSender.java`, `.render`, `BlobStore`, `Capabilities`, `RelayHost`, `HostConnection`, `.sendRaw`, `android.view.View`, `ChatPort`, `Outbox`, `.fromPhrase`, `ReachabilityManager`, `DirectEndpoint`, `MaximaApiReceiver`, `Tier1Services`, `Dimension`, `.main`, `javax.swing.JComponent`, `.main`, `Mailbox`, `.run`, `RpcPeer`, `MlsStore`, `DirectReachability`, `.startPumping`, `LanDiscovery`, `MaximaService`, `DesktopNode`, `MlsClient`, `.run`, `RelayServer.java`, `MaximaMessage`, `MaximaIdentity`?**
  _High betweenness centrality (0.196) - this node is a cross-community bridge._
- **Why does `MiniData` connect `MiniData` to `MiniByte`, `MaximaSender.java`, `Streamable`, `TxPoW`, `MLSPacketSET`, `MMRProof`, `Coin`, `MMR`, `Transaction`, `.getInstance`, `MiniNumber`, `Greeting`, `MaximaErrorMsg`, `MaximaManager.java`, `MLSPacketGETResp`, `MaximaWallet`, `MLSPacketGETReq`, `Witness`, `MaximaManager`, `java.io.DataInputStream`, `Signature`, `.fullProtocolRoundTrip`, `.log`, `CryptoPackage`, `android.content.SharedPreferences`, `.processMessage`, `Message`, `JSONObject`, `WalletCore`, `Maths`, `.processMessage`, `MMRData`, `MaximaIdentity`?**
  _High betweenness centrality (0.121) - this node is a cross-community bridge._
- **Why does `MaximaIdentity` connect `MaximaIdentity` to `MaximaNode`, `MaximaSender.java`, `RelayServer`, `BlobStore`, `Capabilities`, `HostConnection`, `.sendRaw`, `android.view.View`, `Group`, `.fromPhrase`, `MaximaWallet`, `DirectEndpoint`, `Tier1Services`, `.main`, `.main`, `.main`, `java.io.DataInputStream`, `.run`, `FileStore`, `RpcPeer`, `RelayRuntime`, `MlsStore`, `android.content.SharedPreferences`, `.main`, `SeedStore`, `MaximaService`, `WalletCore`, `DesktopNode`, `MlsClient`, `RelayServer.java`, `MaximaMessage`?**
  _High betweenness centrality (0.083) - this node is a cross-community bridge._
- **What connects `install.sh script`, `OFF`, `MAPPING` to the rest of the system?**
  _100 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MaximaNode` be split into smaller, more focused modules?**
  _Cohesion score 0.0506155950752394 - nodes in this community are weakly interconnected._
- **Should `ChatActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.058050645007166744 - nodes in this community are weakly interconnected._
- **Should `MaximaSender.java` be split into smaller, more focused modules?**
  _Cohesion score 0.09125188536953242 - nodes in this community are weakly interconnected._