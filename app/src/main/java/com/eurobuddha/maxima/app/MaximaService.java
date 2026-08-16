package com.eurobuddha.maxima.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The always-on transport.
 *
 * Every choice here is dictated by what actually survives on Android, learned
 * the hard way elsewhere in this fleet:
 *
 *  - foregroundServiceType=specialUse, because dataSync is time-capped on
 *    Android 14/15 and dies overnight
 *  - work is driven by an exact allow-while-idle alarm, because
 *    Handler.postDelayed does not survive Doze
 *  - WorkManager is a resurrection belt, not the primary mechanism
 *  - startForeground can be refused; bail cleanly and let the belts retry
 *  - a ConnectivityManager callback re-dials on network change, because a
 *    cellular NAT will silently half-close an idle flow
 *
 * NOTE: this is the one part of the project that cannot be proven from a
 * laptop. Multi-hour behaviour under Doze, on a real handset, on a real
 * carrier, is untested.
 */
public final class MaximaService extends Service {

    public static final String TAG = "MaximaService";
    private static final String CHANNEL_ID = "maxima_transport";
    private static final int NOTIF_ID = 4242;

    public static final String ACTION_TICK = "com.eurobuddha.maxima.app.TICK";

    private static volatile MaximaNode sNode;
    private static volatile AndroidContribution sPolicy;
    private static volatile com.eurobuddha.maxima.core.chat.ChatEngine sChat;
    private static volatile com.eurobuddha.maxima.app.direct.DirectReachability sDirect;
    private static volatile com.eurobuddha.maxima.app.direct.LanDiscovery sLan;
    private final AtomicBoolean mPumping = new AtomicBoolean(false);
    private Thread mPumpThread;
    private ConnectivityManager.NetworkCallback mNetCallback;

    private static volatile com.eurobuddha.maxima.core.media.MediaService sMedia;

    /** Relay-swarm discovery: learns live relays from the ones we're attached to
     *  (probe-before-adopt, capped) so the phone is never pinned to a static
     *  list. Same client the desktop node and the relays already run. */
    private static volatile com.eurobuddha.maxima.core.session.RelayGossipClient sGossip;

    /** Consecutive pump failures per host — a host that keeps failing is dropped
     *  so reconcile swaps in a live one instead of tight-looping on a dead one. */
    private final java.util.Map<String, Integer> mPumpFails =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** One swarm-discovery round at a time; gossip runs off the pump thread. */
    private final AtomicBoolean mGossipBusy = new AtomicBoolean(false);

    /** How many multi-homed relays the swarm tries to hold open at once. */
    private static final int RELAY_TARGET = 4;

    /** Drop a host after this many consecutive pump failures. */
    private static final int PUMP_FAIL_DROP = 3;

    /** The media publish/fetch service (self-hosted blobs), or null if not up. */
    public static com.eurobuddha.maxima.core.media.MediaService media() {
        return sMedia;
    }

    public static MaximaNode node() {
        return sNode;
    }

    public static AndroidContribution policy() {
        return sPolicy;
    }

    public static com.eurobuddha.maxima.core.chat.ChatEngine chat() {
        return sChat;
    }

    public static com.eurobuddha.maxima.app.direct.DirectReachability direct() {
        return sDirect;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Sha3Provider.install();
        createChannel();

        MaximaIdentity id = SeedStore.loadOrCreateIdentity(this);
        sNode = new MaximaNode(id, "1.0.48", RELAY_TARGET);
        // Discovery client: seeded from the bootstrap floor, it adopts relays the
        // swarm gossips about. Announcing (this phone AS a host) only kicks in if
        // Tier-2 reachability proves an open port - wired in the pump loop.
        sGossip = new com.eurobuddha.maxima.core.session.RelayGossipClient(
                id, "1.0.48", 8);

        // Persistence, BEFORE anything reads or writes node state. Until now
        // the app ran memory-only, so every contact, every held mailbox item
        // and every address-history line died with the process - which on
        // Android means whenever the OS felt like it.
        sNode.setStore(new com.eurobuddha.maxima.core.store.FileStore(
                new java.io.File(getFilesDir(), "node")));

        sNode.setName(SeedStore.displayName(this));
        sNode.setStaticMls(MlsStore.get(this));

        // The media layer: THIS phone is the source of truth for media it
        // publishes (own BlobStore under files/media), and it fetches others'
        // media chunk-by-chunk via relays / direct. Bounded so the app can't
        // fill the phone; the owner's relays hold replicas for when we sleep.
        sMedia = new com.eurobuddha.maxima.core.media.MediaService(sNode,
                new com.eurobuddha.maxima.core.store.BlobStore(
                        new java.io.File(getFilesDir(), "media"),
                        512L * 1024 * 1024));

        com.eurobuddha.maxima.core.chat.ChatEngine chat =
                new com.eurobuddha.maxima.core.chat.ChatEngine(sNode);
        chat.setStore(new com.eurobuddha.maxima.core.store.FileStore(
                new java.io.File(getFilesDir(), "chat")));
        chat.setSendReadReceipts(ChatPrefs.readReceipts(this));
        chat.setMediaService(sMedia);   // photos/videos in chat, self-hosted
        final MaximaNode node = sNode;
        chat.setListener(new com.eurobuddha.maxima.core.chat.ChatEngine.Listener() {
            public void onMessage(com.eurobuddha.maxima.core.chat.ChatEngine.Entry e) {
                if (!e.mine) {
                    com.eurobuddha.maxima.app.chat.ChatNotifier.onInbound(
                            MaximaService.this, chat, e, node);
                }
                com.eurobuddha.maxima.app.chat.ChatHub.dispatchMessage(e);
            }

            public void onStateChanged(com.eurobuddha.maxima.core.chat.ChatEngine.Entry e) {
                com.eurobuddha.maxima.app.chat.ChatHub.dispatchState(e);
            }

            public void onGroupChanged(com.eurobuddha.maxima.core.chat.Group g) {
                com.eurobuddha.maxima.app.chat.ChatHub.dispatchGroup(g);
            }
        });
        sChat = chat;
        com.eurobuddha.maxima.app.chat.ChatNotifier.createChannel(this);

        // Surface inbound chat so the UI has something to show. Anything that
        // is not ours is logged too - silence is the enemy of debugging on a
        // device with no console.
        sNode.setMessageListener((msg, msgid) -> {
            String app = msg.mApplication.toString();
            String body = new String(msg.mData.getBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            EventLog.add("inbound [" + app + "] " + msg.mData.getLength() + " bytes from "
                    + shortKey(msg.mFrom.to0xString()));

            // Chat is OURS - it is the one application string this app owns,
            // so it never goes out over IPC. Everything else is handed to
            // whichever app subscribed to that application string.
            com.eurobuddha.maxima.core.chat.ChatEngine ce = sChat;
            if (ce != null && ce.onInbound(msg)) {
                return;
            }
            com.eurobuddha.maxima.app.ipc.MaximaApiDelivery.deliver(
                    MaximaService.this, msg, msgid);
        });

        // Classic publishes MAXIMACONTACTS and MAXIMAHOSTS; apps using us as
        // transport need both to react to a contact appearing or a host going.
        sNode.setEventListener(new MaximaNode.EventListener() {
            public void onContactsChanged(
                    com.eurobuddha.maxima.core.contacts.Contact c, boolean removed) {
                EventLog.add((removed ? "contact removed: " : "contact updated: ") + c.name);
                com.eurobuddha.maxima.app.ipc.MaximaApiDelivery.event(
                        MaximaService.this,
                        com.eurobuddha.maxima.app.ipc.MaximaApiMessages.EVENT_CONTACTS,
                        c.publicKey, removed);
            }

            public void onHostsChanged(String hostPort, boolean connected) {
                com.eurobuddha.maxima.app.ipc.MaximaApiDelivery.event(
                        MaximaService.this,
                        com.eurobuddha.maxima.app.ipc.MaximaApiMessages.EVENT_HOSTS,
                        hostPort, connected);
            }
        });
        // Contribution is gated on real device state, re-evaluated on every
        // request rather than latched at startup.
        sPolicy = new AndroidContribution(this);
        sNode.tier1().setPolicy(sPolicy);
        // Tier 2 opportunistic reachability. Driven from the heartbeat below.
        sDirect = new com.eurobuddha.maxima.app.direct.DirectReachability(
                this, sNode, sPolicy);
        sLan = new com.eurobuddha.maxima.app.direct.LanDiscovery(this, sNode);

        EventLog.add("identity " + id.mxIdentity().substring(0, 20) + "...");
        EventLog.add("contributing: " + sPolicy.describe().split("\\n")[0]);

        registerNetworkCallback();
        Log.i(TAG, "created, identity " + id.mxIdentity().substring(0, 24) + "...");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIF_ID, buildNotification("Connecting..."));
        } catch (Exception e) {
            // Android can refuse the FGS (residual time budget, background
            // start restrictions). Do not crash - the alarm and WorkManager
            // will bring us back.
            Log.w(TAG, "startForeground refused: " + e);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Re-arm the heartbeat FIRST, so a crash below still leaves us scheduled.
        HeartbeatReceiver.schedule(this);
        MaximaWorker.enqueue(this);

        startPumping();
        return START_STICKY;
    }

    private void startPumping() {
        if (!mPumping.compareAndSet(false, true)) {
            return;
        }
        mPumpThread = new Thread(() -> {
            MaximaNode node = sNode;
            try {
                // Seed candidates from the trusted floor + the swarm we remember
                // from last time; gossip grows this at runtime. The pool scores
                // and picks the best RELAY_TARGET; dead entries just age out.
                java.util.LinkedHashSet<String> seed =
                        new java.util.LinkedHashSet<>(RelayStore.get(MaximaService.this));
                SwarmStore.prune(MaximaService.this);
                seed.addAll(SwarmStore.recent(MaximaService.this));
                List<String> relays = new ArrayList<>(seed);
                EventLog.add("attaching to " + relays.size() + " candidate relay(s)");
                int attached = node.start(relays, 30000);
                for (String a : node.myAddresses()) {
                    int at = a.indexOf('@');
                    if (at >= 0) SwarmStore.seen(MaximaService.this, a.substring(at + 1));
                }
                updateNotification(attached + " relay(s) connected");
                if (attached == 0) {
                    EventLog.add("NO RELAYS REACHED - check the relay list and connectivity");
                } else {
                    for (String a : node.myAddresses()) {
                        EventLog.add("attached: " + a.substring(a.indexOf('@')));
                    }
                }
                Log.i(TAG, "attached to " + attached + " relays: " + node.myAddresses());

                long lastMaintain = System.currentTimeMillis();
                while (mPumping.get()) {
                    boolean any = false;
                    for (String hp : node.pool().activeHosts()) {
                        try {
                            any |= node.pump(hp, 1500);
                            mPumpFails.remove(hp);   // a good pump clears the count
                        } catch (Exception e) {
                            // A relay that greets then keeps failing (an old-protocol
                            // node, a moved port) used to spin here forever. Drop it
                            // after a few strikes so reconcile swaps in a live one -
                            // this is what makes relay-switching automatic.
                            int fails = mPumpFails.merge(hp, 1, Integer::sum);
                            if (fails >= PUMP_FAIL_DROP) {
                                node.pool().detach(hp);
                                mPumpFails.remove(hp);
                                EventLog.add("dropped dead relay " + hp
                                        + " (" + fails + " strikes) - reconciling");
                            } else {
                                Log.w(TAG, "pump error on " + hp + ": " + e);
                            }
                        }
                    }
                    if (System.currentTimeMillis() - lastMaintain > 60_000) {
                        int before = node.pool().activeCount();
                        node.maintain(20000);
                        int after = node.pool().activeCount();
                        if (after != before) {
                            EventLog.add("relays " + before + " -> " + after);
                        }
                        // Write-behind: state changes are batched, so they must
                        // actually be flushed on the heartbeat.
                        if (sChat != null) {
                            sChat.flushState();
                        }
                        // Tier 2 listener + LAN discovery under a LIGHT gate:
                        // contribution on and unmetered Wi-Fi. This runs the
                        // direct listener (which serves LAN peers directly and
                        // is the target for a public mapping) without requiring
                        // charging - that stricter gate is only for the heavy
                        // map/probe inside DirectReachability.
                        try {
                            manageLanAndListener();
                        } catch (Exception e) {
                            Log.w(TAG, "lan/listener: " + e);
                        }
                        // Tier 2: map -> probe -> advertise / renew. Non-blocking
                        // (runs on its own state thread).
                        if (sDirect != null) {
                            try {
                                sDirect.tick();
                            } catch (Exception e) {
                                Log.w(TAG, "direct reachability tick: " + e);
                            }
                        }
                        // SWARM DISCOVERY runs on its OWN thread: gossip.tick probes
                        // unknown peers (up to ~6s each) and announceNow greets the
                        // bootstrap relays (up to 5s each) - blocking those on the
                        // pump thread would stall message delivery. The busy-flag
                        // stops a slow round from piling up behind the 60s heartbeat.
                        if (sGossip != null && mGossipBusy.compareAndSet(false, true)) {
                            final MaximaNode gnode = node;
                            Thread gt = new Thread(() -> {
                                try {
                                    boolean reachable = sDirect != null
                                            && sDirect.state() == com.eurobuddha.maxima.app
                                                .direct.DirectReachability.State.ADVERTISED
                                            && sDirect.publicAddress() != null
                                            && !sDirect.publicAddress().isEmpty();
                                    if (reachable) {
                                        String ep = sDirect.publicAddress();
                                        sGossip.setSelfEndpoint(ep);
                                        gnode.pool().setAdvertisedEndpoint(ep);
                                        sGossip.announceNow(
                                            com.eurobuddha.maxima.core.session.Bootstrap.RELAYS);
                                    } else {
                                        sGossip.setSelfEndpoint(null);
                                    }
                                    int learnedBefore = sGossip.learnedCount();
                                    sGossip.tick(gnode);
                                    if (sGossip.learnedCount() != learnedBefore) {
                                        EventLog.add("discovered relay(s) via gossip - swarm now "
                                                + sGossip.learnedCount() + " learned");
                                    }
                                    for (String a : gnode.myAddresses()) {
                                        int at = a.indexOf('@');
                                        if (at >= 0) {
                                            SwarmStore.seen(MaximaService.this, a.substring(at + 1));
                                        }
                                    }
                                } catch (Exception e) {
                                    Log.w(TAG, "gossip tick: " + e);
                                } finally {
                                    mGossipBusy.set(false);
                                }
                            }, "maxima-gossip");
                            gt.setDaemon(true);
                            gt.start();
                        }
                        // Forget strike counts for relays no longer attached, so a
                        // reconcile-dropped host doesn't carry a stale count back.
                        mPumpFails.keySet().retainAll(node.pool().activeHosts());
                        lastMaintain = System.currentTimeMillis();
                        updateNotification(after + " relay(s) connected");
                    }
                    if (!any) {
                        Thread.sleep(200);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e(TAG, "pump loop died", e);
            }
        }, "maxima-pump");
        mPumpThread.setDaemon(true);
        mPumpThread.start();
    }

    /**
     * A mobile socket dies silently on handover. Re-dial rather than waiting for
     * a read timeout that may never come.
     */
    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm == null) {
                return;
            }
            mNetCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Log.i(TAG, "network available - reconciling relays");
                    MaximaNode n = sNode;
                    if (n != null) {
                        new Thread(() -> n.maintain(20000), "maxima-renet").start();
                    }
                    // A changed network means our mapped port is almost
                    // certainly dead; drop it before advertising a stale route.
                    com.eurobuddha.maxima.app.direct.DirectReachability d = sDirect;
                    if (d != null) {
                        // Non-blocking: the manager queues the withdraw on its
                        // own state thread and returns at once.
                        d.onNetworkChanged();
                    }
                }

                @Override
                public void onLost(Network network) {
                    Log.i(TAG, "network lost");
                }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(), mNetCallback);
        } catch (Exception e) {
            Log.w(TAG, "network callback unavailable: " + e);
        }
    }

    @Override
    public void onDestroy() {
        mPumping.set(false);
        if (mPumpThread != null) {
            mPumpThread.interrupt();
        }
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null && mNetCallback != null) {
                cm.unregisterNetworkCallback(mNetCallback);
            }
        } catch (Exception ignored) {
        }
        com.eurobuddha.maxima.app.direct.LanDiscovery lan = sLan;
        if (lan != null) {
            lan.stop();
        }
        com.eurobuddha.maxima.app.direct.DirectReachability d = sDirect;
        if (d != null) {
            d.shutdown();
        }
        com.eurobuddha.maxima.core.chat.ChatEngine ch = sChat;
        if (ch != null) {
            // Flush deferred state and release the receipt pool.
            ch.close();
        }
        MaximaNode n = sNode;
        if (n != null) {
            n.stop();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Maxima transport",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String zText) {
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("Maxima")
                .setContentText(zText)
                .setSmallIcon(R.drawable.ic_stat_maxima)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String zText) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification(zText));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Own the direct listener and LAN discovery under the light Wi-Fi gate.
     *
     * On unmetered Wi-Fi with contribution enabled, the listener runs (so LAN
     * peers can reach us and a public mapping has something to point at) and we
     * advertise/discover on the local segment. Off that gate, both stop - a
     * phone on cellular has no LAN peers and its listener would be unreachable.
     */
    private void manageLanAndListener() {
        boolean gate = AndroidContribution.isEnabled(this) && sPolicy != null
                && sPolicy.isUnmetered();
        MaximaNode n = sNode;
        if (n == null) {
            return;
        }
        if (gate) {
            int port = n.startDirect(0);   // idempotent
            if (port > 0 && sLan != null) {
                sLan.start(port);
            }
        } else {
            if (sLan != null) {
                sLan.stop();
            }
            // Only stop the listener if no direct address is advertised.
            // Guard on the ACTUAL invariant (is an address set) rather than the
            // state enum, which the other thread sets AFTER setDirectAddress -
            // so the enum can briefly lag a live address.
            if (n.directAddress().isEmpty()) {
                n.stopDirect();
            }
        }
    }

    static String shortKey(String zHex) {
        return zHex.length() > 20 ? zHex.substring(0, 20) + "..." : zHex;
    }

    /** Send a chat message. Returns null on success, or an error to show. */
    public static String sendChat(String zAddress, String zText) {
        MaximaNode n = sNode;
        if (n == null) {
            return "transport not running";
        }
        try {
            com.eurobuddha.maxima.core.MaximaSender.Result r =
                    n.sendRaw(zAddress, Chat.APPLICATION,
                            zText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (r.isOk()) {
                EventLog.add("sent to " + zAddress.substring(zAddress.indexOf('@')) + ": " + zText);
                return null;
            }
            EventLog.add("send failed: " + r.statusName);
            return r.statusName + (r.status == 2
                    ? " - the relay has no route for them (are they online?)" : "");
        } catch (Exception e) {
            EventLog.add("send error: " + e.getMessage());
            return String.valueOf(e.getMessage());
        }
    }

    public static void start(Context zContext) {
        Intent i = new Intent(zContext, MaximaService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            zContext.startForegroundService(i);
        } else {
            zContext.startService(i);
        }
    }
}
