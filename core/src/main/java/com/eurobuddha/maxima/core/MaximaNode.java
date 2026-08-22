package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.contacts.ContactCtrl;
import com.eurobuddha.maxima.core.directory.MlsStore;
import com.eurobuddha.maxima.core.identity.Keys;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.mailbox.Mailbox;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.net.HostConnection;
import com.eurobuddha.maxima.core.reliability.DedupCache;
import com.eurobuddha.maxima.core.reliability.Outbox;
import com.eurobuddha.maxima.core.rpc.Capabilities;
import com.eurobuddha.maxima.core.rpc.RpcPeer;
import com.eurobuddha.maxima.core.rpc.ServiceRegistry;
import com.eurobuddha.maxima.core.services.Tier1Services;
import com.eurobuddha.maxima.core.session.HostPool;
import com.eurobuddha.maxima.core.store.Store;
import com.eurobuddha.maxima.core.util.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One Maxima node: identity, relays, contacts, services, reliability.
 *
 * Both hosts use this - the Android app and the headless server differ only in
 * how they schedule {@link #pump} and {@link #maintain}, not in what they do.
 * Nothing here touches a platform API, a filesystem or a clock it did not ask
 * for, which is what keeps :core portable.
 *
 * Deliberately NOT threaded internally: Android wants work driven from a
 * foreground service and exact alarms, a server wants a plain loop. Imposing a
 * thread model here would fight both.
 */
public final class MaximaNode implements ChatPort {

    private final MaximaIdentity mIdentity;
    private final String mVersion;
    private final HostPool mPool;
    private final ServiceRegistry mServices = new ServiceRegistry();
    private final RpcPeer mRpc;
    private final Tier1Services mTier1;

    private final Map<String, Contact> mContacts = new ConcurrentHashMap<>();
    private final DedupCache mDedup = new DedupCache();
    private final Outbox mOutbox = new Outbox();
    private final Mailbox mMailbox = new Mailbox();
    private final MlsStore mDirectory = new MlsStore();

    // ---- Maxima maintenance loop, matching the reference cadence ----
    /** The reference re-publishes to MLS and re-announces to every contact on a
     *  20-min loop (MAXIMA_LOOP_DELAY), first firing 3 min after boot. We drive
     *  the same from the heartbeat rather than a dedicated timer. */
    private static final long MAXIMA_LOOP_MS = 20 * 60 * 1000L;
    private static final long FIRST_LOOP_MS = 3 * 60 * 1000L;
    /** Only re-resolve a contact via MLS if we have not heard from them for this
     *  long — the reference's MAXIMA_CHECK_MLS 30-min threshold. */
    private static final long MLS_STALE_MS = 30 * 60 * 1000L;
    private final long mStartedAt = System.currentTimeMillis();
    private volatile long mLastMaximaLoop;   // 0 until the first loop runs
    /** Guards the self-heal resolver so a slow pass never piles up or blocks the
     *  heartbeat thread. */
    private final java.util.concurrent.atomic.AtomicBoolean mResolveBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** Short socket timeout for a directory lookup during self-heal - we may try
     *  several, and must not hang on a slow one. */
    private static final int SELFHEAL_TIMEOUT_MS = 5000;

    // ---- check-connect: verify a host actually RELAYS, not just answers ----
    /** Application tag of the self-addressed check-connect probe. Internal:
     *  intercepted in {@link #handle} and never surfaced to app listeners. */
    static final String CHECK_APP = "__maxchk";
    /** Grace before an attached-but-unverified host is dropped — the reference's
     *  MAXIMA_CHECK_CONNECTED 30s window. */
    private static final long CHECK_GRACE_MS = 30_000L;
    /** Tight socket timeout for a check-connect send so the heartbeat that drives
     *  the audit never stalls on a dead host. */
    private static final int CHECK_TIMEOUT_MS = 4000;
    /** Hosts whose self-addressed probe came back — proven to relay TO us. */
    private final java.util.Set<String> mHostVerified =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** hostPort -> when we sent its outstanding check-connect probe. */
    private final Map<String, Long> mHostCheckSent = new ConcurrentHashMap<>();

    // ---- MLS server rotation, matching the reference's 12h cadence ----
    /** Our current Location Service, and the previous one we still publish to so
     *  contacts holding the old address can still resolve us. The reference
     *  rotates at most once / 12h (MLSService.newMLSNode). */
    private volatile String mCurrentMls = "";
    private volatile String mOldMls = "";
    private volatile long mLastMlsRotate;
    private static final long MLS_ROTATE_MS = 12L * 60 * 60 * 1000;

    /** Tier 2 inbound listener. Null until {@link #startDirect}. */
    private volatile com.eurobuddha.maxima.core.net.DirectEndpoint mDirect;
    /** Proven public ip:port, or empty. Set only after external proof. */
    private volatile String mDirectAddress = "";
    /** Our LAN ip:port (site-local) while on Wi-Fi with the direct listener up.
     *  Advertised as an identity-keyed source so a SAME-LAN peer dials our phone
     *  directly for our hosted blobs. Empty off Wi-Fi. */
    private volatile String mLanAddress = "";
    /** Our own hosted blobs, handed to the direct endpoint so a same-LAN peer can
     *  pull our profile/media from this phone directly. Set by the app at startup. */
    private volatile com.eurobuddha.maxima.core.store.BlobStore mLocalBlobs;
    /** Ephemeral LAN-discovered addresses: contact identity (norm) -> Mx@lanIp:port. */
    private final Map<String, String> mLanPeers = new ConcurrentHashMap<>();

    /** Short connect leash for a chat send: a real connection (direct or relay) completes in
     *  well under a second, so a stale/dead direct address fails here in a few seconds and we
     *  fall through to a relay, instead of eating the patient 20s default. The real defence
     *  against a dead direct is only advertising it while actually reachable (DirectReachability
     *  re-proves the manual forward every ~60s); this just bounds the residual window. */
    private static final int SEND_CONNECT_TIMEOUT_MS = 5000;


    /**
     * Durable storage. Defaults to memory-only so nothing breaks if a host
     * forgets to supply one, but a real deployment MUST call
     * {@link #setStore} or it loses every contact on restart.
     */
    private volatile Store mStore = Store.MEMORY_ONLY;

    private static final String C_CONTACTS = "contacts";
    private static final String C_SETTINGS = "settings";

    private volatile String mName = "noname";

    /**
     * Our Maxima Location Service, as classic understands it.
     *
     * Classic picks an MLS from whichever public peer offered one, and lets you
     * pin it with `maxextra action:staticmls`. A pinned MLS matters more for us
     * than for a server: a phone's address changes every time a relay drops, and
     * the MLS is how contacts find the new one.
     */
    private volatile String mStaticMls = "";
    private volatile Capabilities mCapabilities = Capabilities.phoneDefaults();
    /** Our self-declared node kind, advertised in contact-ctrl ("core" for a
     *  full/desktop node, "" for the phone app). Lets a peer's contact list
     *  label us. */
    private volatile String mNodeKind = "";
    private volatile String mIcon = "0x00";

    /**
     * Whether strangers may add us as a contact.
     *
     * Classic: `maxextra action:allowallcontacts`. Default true, matching
     * classic, with an explicit allow-list for when it is false.
     */
    private volatile boolean mAllowAllContacts = true;
    private final java.util.Set<String> mAllowedContacts =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /**
     * Identities this node will resolve for ANYONE, not just their own contacts.
     * Classic: `maxextra action:addpermanent`, used on a static MLS host so a
     * stranger can look you up from a MAX# address.
     */
    private final java.util.Set<String> mPermanent =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /** Application messages that are not ours - handed to the embedder. */
    public interface MessageListener {
        void onMessage(MaximaMessage zMessage, MiniData zMsgid);
    }

    /**
     * The two events classic publishes alongside MAXIMA, without which an app
     * using us as transport cannot react to a contact appearing or a host
     * dropping. Classic: MAXIMACONTACTS and MAXIMAHOSTS.
     */
    public interface EventListener {
        /** A contact was added, updated or removed. */
        void onContactsChanged(Contact zContact, boolean zRemoved);

        /** A host attached or dropped. */
        void onHostsChanged(String zHostPort, boolean zConnected);
    }

    /** Diagnostic lines for the embedder's event log. Optional; unset = silent. */
    public interface LogListener {
        void onLog(String zLine);
    }

    private volatile MessageListener mListener;
    private volatile EventListener mEvents;
    private volatile LogListener mLog;

    public MaximaNode(MaximaIdentity zIdentity, String zVersion, int zRelayTarget) {
        mIdentity = zIdentity;
        mVersion = zVersion;
        mPool = new HostPool(zIdentity, zVersion, zRelayTarget);
        // Push receive: every attached host gets a dedicated reader that hands
        // inbound straight to handle() the instant the relay pushes it. handle()
        // is synchronized, so inbound stays effectively single-threaded exactly
        // as it was under the old one-thread pump loop.
        mPool.setSink(new com.eurobuddha.maxima.core.net.HostConnection.Sink() {
            @Override
            public void onInbound(com.eurobuddha.maxima.core.net.HostConnection.Inbound zIn) {
                handle(zIn);
            }

            @Override
            public void onDead(String zHostPort) {
                // Bank the uptime and free the slot; the caller's maintain
                // heartbeat re-attaches. No cooldown: a NAT-reaped socket says
                // nothing bad about the relay.
                mPool.detach(zHostPort);
            }
        });
        mRpc = new RpcPeer(zIdentity, mServices);
        mTier1 = new Tier1Services(zIdentity, mMailbox, mDirectory);
        mTier1.registerAll(mServices);
    }

    public MaximaIdentity identity() {
        return mIdentity;
    }

    public HostPool pool() {
        return mPool;
    }

    public ServiceRegistry services() {
        return mServices;
    }

    public RpcPeer rpc() {
        return mRpc;
    }

    public Tier1Services tier1() {
        return mTier1;
    }

    public Mailbox mailbox() {
        return mMailbox;
    }

    public MlsStore directory() {
        return mDirectory;
    }

    public Outbox outbox() {
        return mOutbox;
    }

    public DedupCache dedup() {
        return mDedup;
    }

    public void setName(String zName) {
        mName = zName;
        mStore.put(C_SETTINGS, "name", zName);
    }

    /** Our display name - what contacts (including classic peers) see. */
    /** ChatPort: my identity public key hex. */
    @Override
    public String publicKeyHex() {
        return mIdentity.publicKeyHex();
    }

    public String name() {
        return mName;
    }

    /** Our wallet RECEIVE address (Mx...), advertised in the contact handshake's
     *  {@code minimaaddress} field - exactly where classic peers expect to find
     *  where to pay us. Empty until the app's wallet reports it. */
    private volatile String mWalletAddress = "";

    public void setWalletAddress(String zMxAddress) {
        mWalletAddress = zMxAddress == null ? "" : zMxAddress;
    }

    /**
     * Attach durable storage and load whatever is already there.
     * Call once, before {@link #start}.
     */
    public void setStore(Store zStore) {
        mStore = zStore == null ? Store.MEMORY_ONLY : zStore;
        loadFromStore();
    }

    public Store store() {
        return mStore;
    }

    private void loadFromStore() {
        // settings
        String n = mStore.get(C_SETTINGS, "name");
        if (n != null && !n.isEmpty()) {
            mName = n;
        }
        String mls = mStore.get(C_SETTINGS, "staticmls");
        if (mls != null) {
            mStaticMls = mls;
        }
        String icon = mStore.get(C_SETTINGS, "icon");
        if (icon != null && !icon.isEmpty()) {
            mIcon = icon;
        }
        String allow = mStore.get(C_SETTINGS, "allowall");
        if (allow != null) {
            mAllowAllContacts = Boolean.parseBoolean(allow);
        }

        // contacts
        int loaded = 0;
        for (Map.Entry<String, String> e : mStore.all(C_CONTACTS).entrySet()) {
            try {
                Contact c = contactFromJson(e.getValue());
                if (c == null || c.publicKey == null || c.publicKey.isEmpty()) {
                    // Do not silently drop it - a contact vanishing with no
                    // trace is worse than a noisy skip.
                    System.err.println("[chat] skipping unreadable contact record " + e.getKey());
                    continue;
                }
                String key = Keys.norm(c.publicKey);
                mContacts.put(key, c);
                // Migrate records written under the older 0X... form.
                if (!key.equals(e.getKey())) {
                    mStore.remove(C_CONTACTS, e.getKey());
                    mStore.put(C_CONTACTS, key, e.getValue());
                }
                loaded++;
            } catch (Exception ex) {
                System.err.println("[chat] bad contact record " + e.getKey() + ": " + ex);
            }
        }
        if (loaded > 0) {
            mDedup.clear();
        }
    }

    /**
     * A classic-style handshake (e.g. from the classic engine) carries no
     * capability flags, so a Parlons peer can be mislabelled CLASSIC - which
     * blocks capability-gated features like voice notes. Inbound chat traffic
     * is PROOF they run Parlons; upgrade on first sight.
     */
    @Override
    public void noteCapable(String zPublicKey) {
        Contact c = contact(zPublicKey);
        if (c != null && c.isClassic()) {
            c.capabilities = com.eurobuddha.maxima.core.rpc.Capabilities.phoneDefaults();
            storeContact(c);
            log("capability learned: " + (c.name == null ? "contact" : c.name)
                    + " runs Parlons");
        }
    }

    /** Add or update a contact and persist it. */
    public void storeContact(Contact zContact) {
        mContacts.put(Keys.norm(zContact.publicKey), zContact);
        saveContact(zContact);
        fireContacts(zContact, false);
    }

    private void saveContact(Contact zContact) {
        mStore.put(C_CONTACTS, Keys.norm(zContact.publicKey), contactToJson(zContact));
    }

    /**
     * Note when a contact's address set changes.
     *
     * A contact moving host is the single most common cause of "my message did
     * not arrive", and without a record of WHEN it moved the failure is
     * unexplainable. Kept as an append log rather than in the contact record so
     * it cannot bloat the hot path.
     */
    private void recordAddressHistory(Contact zNew, Contact zOld) {
        if (zNew.addresses.isEmpty()) {
            return;
        }
        String now = zNew.primaryAddress();
        String was = zOld == null ? null : zOld.primaryAddress();
        if (was != null && was.equals(now)) {
            return;
        }
        mStore.append("addrhistory",
                System.currentTimeMillis() + "\t" + zNew.publicKey + "\t" + now);
    }

    /** Every observed address change, newest last. */
    public java.util.List<String> addressHistory() {
        return mStore.read("addrhistory");
    }

    /** Address changes for one contact. */
    public java.util.List<String> addressHistory(String zPublicKeyHex) {
        java.util.List<String> out = new ArrayList<>();
        String key = zPublicKeyHex.toUpperCase();
        for (String l : mStore.read("addrhistory")) {
            String[] p = l.split("\t");
            if (p.length >= 3 && p[1].toUpperCase().equals(key)) {
                out.add(l);
            }
        }
        return out;
    }

    /** Public: engine-flip sync writes jar contacts into this book's format. */
    public static String contactToJson(Contact c) {
        return new Json.Writer()
                .put("publickey", c.publicKey)
                .put("name", c.name)
                .put("icon", c.icon)
                .put("addresses", String.join(",", c.addresses))
                .put("myaddress", c.myAddress == null ? "" : c.myAddress)
                .put("mls", c.mls == null ? "" : c.mls)
                .put("minimaaddress", c.minimaAddress == null ? "" : c.minimaAddress)
                .put("caps", c.capabilities.encode())
                .put("kind", c.kind == null ? "" : c.kind)
                .put("lastseen", Long.toString(c.lastSeen))
                .done();
    }

    /** Public: the jar-engine migration replays the persisted records. */
    public static Contact contactFromJson(String zJson) {
        Map<String, String> m = Json.parse(zJson);
        Contact c = new Contact(m.get("publickey"));
        c.name = m.getOrDefault("name", "noname");
        c.icon = m.getOrDefault("icon", "0x00");
        c.myAddress = m.getOrDefault("myaddress", "");
        c.mls = m.getOrDefault("mls", "");
        c.minimaAddress = m.getOrDefault("minimaaddress", "");
        c.capabilities = Capabilities.decode(m.get("caps"));
        c.kind = m.getOrDefault("kind", "");
        try {
            c.lastSeen = Long.parseLong(m.getOrDefault("lastseen", "0"));
        } catch (NumberFormatException ignored) {
        }
        String addrs = m.getOrDefault("addresses", "");
        java.util.List<String> list = new ArrayList<>();
        for (String a : addrs.split(",")) {
            if (!a.trim().isEmpty()) {
                list.add(a.trim());
            }
        }
        c.setAddresses(list);
        return c;
    }

    public void setCapabilities(Capabilities zCaps) {
        mCapabilities = zCaps;
    }

    /** Declare our node kind (e.g. "core" for a full/desktop node). Advertised
     *  to contacts so their list can label us; empty for the phone app. */
    public void setNodeKind(String zKind) {
        mNodeKind = zKind == null ? "" : zKind.trim();
    }

    public String nodeKind() {
        return mNodeKind;
    }

    public Capabilities capabilities() {
        return mCapabilities;
    }

    /** Pin our Location Service. Empty means "use whatever a host offers". */
    public void setStaticMls(String zMlsAddress) {
        mStaticMls = zMlsAddress == null ? "" : zMlsAddress.trim();
        mStore.put(C_SETTINGS, "staticmls", mStaticMls);
    }

    public boolean isStaticMls() {
        return !mStaticMls.isEmpty();
    }

    /**
     * The MLS we advertise to contacts - the pinned one if set, otherwise the
     * STABLE current server chosen by {@link #updateMlsServers}. It no longer
     * recomputes from the live host order on every call: that flip-flopped our
     * advertised MLS whenever the host set reordered, stranding contacts who
     * cached the previous one. Rotation is now bounded to once / 12h with the
     * previous server retained.
     */
    public String mlsAddress() {
        if (!mStaticMls.isEmpty()) {
            return mStaticMls;
        }
        if (mCurrentMls.isEmpty()) {
            updateMlsServers();   // lazily adopt the first offer on first use
        }
        return mCurrentMls;
    }

    /**
     * Choose and rotate our MLS server on the reference's schedule. Candidate =
     * the pinned static MLS, else the first MLS a host offers. We adopt the first
     * candidate immediately, but ROTATE to a different one only when the current
     * server's host has dropped (its MLS is dead) or 12h have passed - and we
     * keep the previous server as {@link #mOldMls} so contacts holding the old
     * address still resolve us. Idempotent and cheap; safe to call often.
     */
    void updateMlsServers() {
        if (!mStaticMls.isEmpty()) {
            mCurrentMls = mStaticMls;
            return;
        }
        String candidate = firstHostMls();
        boolean currentDead = !mCurrentMls.isEmpty() && !mlsHostActive(mCurrentMls);
        String[] next = decideMls(mCurrentMls, mOldMls, mLastMlsRotate,
                System.currentTimeMillis(), candidate, currentDead, MLS_ROTATE_MS);
        mCurrentMls = next[0];
        mOldMls = next[1];
        mLastMlsRotate = Long.parseLong(next[2]);
    }

    /**
     * Pure rotation decision, factored out so it can be tested without sockets.
     * Returns {@code [newCurrent, newOld, newLastRotateMillisAsString]}.
     */
    static String[] decideMls(String zCurrent, String zOld, long zLastRotate, long zNow,
                              String zCandidate, boolean zCurrentDead, long zRotateMs) {
        String current = zCurrent == null ? "" : zCurrent;
        String old = zOld == null ? "" : zOld;
        if (zCandidate == null || zCandidate.isEmpty()) {
            return new String[]{current, old, Long.toString(zLastRotate)};   // nothing offered
        }
        if (current.isEmpty()) {
            return new String[]{zCandidate, old, Long.toString(zNow)};       // first adoption
        }
        if (zCandidate.equals(current)) {
            return new String[]{current, old, Long.toString(zLastRotate)};   // still valid
        }
        if (zCurrentDead || zNow - zLastRotate >= zRotateMs) {
            return new String[]{zCandidate, current, Long.toString(zNow)};   // rotate, retain old
        }
        return new String[]{current, old, Long.toString(zLastRotate)};       // hold (too soon)
    }

    private String firstHostMls() {
        // Best-scoring host first (merit: reliability x uptime x capacity, no
        // node type), so the MLS we adopt as our perm anchor is the most reliable
        // directory we can reach - phone or jar, chosen the same way.
        for (String h : mPool.activeHostsByScore()) {
            HostConnection c = mPool.connection(h);
            String m = c == null ? null : c.getTheirMlsAddress();
            if (m != null && !m.isEmpty()) {
                return m;
            }
        }
        return "";
    }

    private boolean mlsHostActive(String zMls) {
        int at = zMls.lastIndexOf('@');
        if (at < 0) {
            return false;
        }
        return mPool.activeHosts().contains(zMls.substring(at + 1));
    }

    /**
     * The single address to hand out, matching classic's `contact` field.
     * Multi-homing publishes all of them in the contact metadata, but a human
     * copying one address should get one address.
     */
    public String primaryAddress() {
        List<String> a = mPool.contactAddresses();
        return a.isEmpty() ? null : a.get(0);
    }

    // ---- classic: maxima action:seticon ----
    public void setIcon(String zIcon) {
        mIcon = zIcon == null || zIcon.isEmpty() ? "0x00" : zIcon;
        mStore.put(C_SETTINGS, "icon", mIcon);
    }

    public String icon() {
        return mIcon;
    }

    // ---- classic: maxextra action:allowallcontacts / addallowed / listallowed ----
    public void setAllowAllContacts(boolean zAllow) {
        mAllowAllContacts = zAllow;
        mStore.put(C_SETTINGS, "allowall", Boolean.toString(zAllow));
    }

    public boolean allowAllContacts() {
        return mAllowAllContacts;
    }

    public void addAllowedContact(String zPublicKeyHex) {
        mAllowedContacts.add(Keys.norm(zPublicKeyHex));
    }

    public java.util.List<String> allowedContacts() {
        return new ArrayList<>(mAllowedContacts);
    }

    public void clearAllowedContacts() {
        mAllowedContacts.clear();
    }

    // ---- classic: maxextra addpermanent / listpermanent / clearpermanent ----
    public void addPermanent(String zPublicKeyHex) {
        mPermanent.add(Keys.norm(zPublicKeyHex));
        mDirectory.addPermanent(zPublicKeyHex);
    }

    public java.util.List<String> permanentKeys() {
        return new ArrayList<>(mPermanent);
    }

    public void removePermanent(String zPublicKeyHex) {
        mPermanent.remove(Keys.norm(zPublicKeyHex));
    }

    public void clearPermanent() {
        mPermanent.clear();
    }

    /**
     * Who is using US as their Location Service, and who may resolve them.
     * Classic: {@code maxextra action:mlsinfo}.
     */
    public java.util.List<String> mlsInfo() {
        java.util.List<String> out = new ArrayList<>();
        for (java.util.Map.Entry<String, String> e
                : mStore.all("mlsserved").entrySet()) {
            out.add(e.getKey() + " -> " + e.getValue());
        }
        return out;
    }

    /**
     * Our permanent address: {@code MAX#<pubkey>#<mls>}.
     *
     * Classic's answer to "my contact address keeps changing". It is not
     * routable itself - a sender resolves it through the MLS to get a live
     * Mx...@host:port. Only useful once the MLS operator has us on its
     * permanent list.
     */
    public String permanentAddress() {
        String mls = mlsAddress();
        if (mls.isEmpty()) {
            return "";
        }
        return "MAX#" + mIdentity.publicKeyHex() + "#" + mls;
    }

    /**
     * Resolve a MAX# permanent address to a live contact address.
     * Classic: `maxextra action:getaddress`.
     */
    public String resolvePermanent(String zMaxAddress) throws Exception {
        if (!zMaxAddress.startsWith("MAX#")) {
            throw new IllegalArgumentException("not a MAX# address");
        }
        int a = zMaxAddress.indexOf('#');
        int b = zMaxAddress.indexOf('#', a + 1);
        if (b < 0) {
            throw new IllegalArgumentException("malformed MAX# address");
        }
        String targetKey = zMaxAddress.substring(a + 1, b);
        String mls = zMaxAddress.substring(b + 1);

        com.eurobuddha.maxima.core.directory.MlsClient c =
                new com.eurobuddha.maxima.core.directory.MlsClient(mIdentity);
        com.eurobuddha.maxima.core.directory.MlsClient.Resolved r = c.resolve(mls, targetKey);
        if (!r.ok()) {
            throw new IllegalStateException(r.error);
        }
        return r.address;
    }

    /** Publish our address to the directory - to our current MLS, the one we most
     *  recently rotated away from, AND every relay we are attached to. Publishing
     *  broadly is the rendezvous half of self-heal: a contact that shares ANY
     *  relay with us can then resolve our current address there, even if it never
     *  learned our specific MLS (the reference only publishes to current + old). */
    public boolean publishToMls() {
        if (myAddresses().isEmpty()) {
            return false;
        }
        updateMlsServers();
        java.util.List<String> readers = new ArrayList<>();
        for (Contact c : mContacts.values()) {
            readers.add(c.publicKey);
        }
        boolean any = false;
        java.util.Set<String> targets = new java.util.LinkedHashSet<>();
        if (!mlsAddress().isEmpty()) {
            targets.add(mlsAddress());
        }
        if (mOldMls != null && !mOldMls.isEmpty()) {
            targets.add(mOldMls);
        }
        targets.addAll(reachableDirectories());   // every attached relay's directory
        for (String mls : targets) {
            try {
                any |= new com.eurobuddha.maxima.core.directory.MlsClient(mIdentity)
                        .publish(mls, myAddresses(), readers,
                                SELFHEAL_TIMEOUT_MS, SELFHEAL_TIMEOUT_MS);
            } catch (Exception ignored) {
                // best effort per server - a slow relay must not hold up the rest
            }
        }
        return any;
    }

    // ---- classic: maxcontacts action:remove ----
    /** Remove a contact AND tell them, as classic does. */
    public boolean removeContact(String zPublicKeyHex) {
        Contact c = mContacts.remove(Keys.norm(zPublicKeyHex));
        if (c == null) {
            return false;
        }
        mStore.remove(C_CONTACTS, Keys.norm(zPublicKeyHex));
        fireContacts(c, true);
        String json = ContactCtrl.buildDelete(mIdentity.publicKeyHex());
        for (String addr : c.addresses) {
            try {
                sendRaw(addr, ContactCtrl.APPLICATION,
                        json.getBytes(StandardCharsets.UTF_8));
                break;
            } catch (Exception ignored) {
                // They may be gone already; the local removal still stands.
            }
        }
        return true;
    }

    // ---- classic: maxcontacts action:export / import ----
    /** Comma-separated contact addresses, the format classic uses. */
    public String exportContacts() {
        StringBuilder sb = new StringBuilder();
        for (Contact c : mContacts.values()) {
            String a = c.primaryAddress();
            if (a != null) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(a);
            }
        }
        return sb.toString();
    }

    /**
     * Import by introducing ourselves to each address.
     * Classic warns these go stale fast - an address is only good while that
     * host connection lives.
     *
     * @return how many introductions were sent
     */
    public int importContacts(String zCsv) {
        int n = 0;
        for (String a : zCsv.split(",")) {
            String addr = a.trim();
            if (addr.isEmpty()) {
                continue;
            }
            try {
                introduce(addr, true);
                n++;
            } catch (Exception ignored) {
            }
        }
        return n;
    }

    /** Classic: maxcontacts action:search */
    public java.util.List<Contact> searchContacts(String zQuery) {
        String q = zQuery.toLowerCase();
        java.util.List<Contact> out = new ArrayList<>();
        for (Contact c : mContacts.values()) {
            if (c.name.toLowerCase().contains(q)
                    || c.publicKey.toLowerCase().contains(q)) {
                out.add(c);
            }
        }
        return out;
    }

    // ---- classic: maxima action:sendall ----
    /** Send to every contact. Returns how many were accepted. */
    public int sendAll(String zApplication, byte[] zData) {
        int ok = 0;
        for (Contact c : mContacts.values()) {
            for (String addr : c.addresses) {
                try {
                    if (sendRaw(addr, zApplication, zData).isOk()) {
                        ok++;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return ok;
    }

    public void setMessageListener(MessageListener zListener) {
        mListener = zListener;
    }

    public void setEventListener(EventListener zListener) {
        mEvents = zListener;
    }

    public void setLogListener(LogListener zListener) {
        mLog = zListener;
    }

    /** Hand a diagnostic line to the embedder's log; no-op when none is set.
     *  Failures used to be swallowed here in core (fanOut, mlsLookup, resend)
     *  which left a dead outbound path completely invisible - the 15-minute
     *  blind window of 2026-08-20. Silence is never a valid failure mode. */
    public void log(String zLine) {
        LogListener l = mLog;
        if (l != null) {
            try {
                l.onLog(zLine);
            } catch (Exception ignored) {
            }
        }
    }

    private void fireContacts(Contact zContact, boolean zRemoved) {
        EventListener l = mEvents;
        if (l != null) {
            try {
                l.onContactsChanged(zContact, zRemoved);
            } catch (Exception ignored) {
                // A listener must never break the transport.
            }
        }
    }

    private void fireHosts(String zHostPort, boolean zConnected) {
        EventListener l = mEvents;
        if (l != null) {
            try {
                l.onHostsChanged(zHostPort, zConnected);
            } catch (Exception ignored) {
            }
        }
    }

    public List<Contact> contacts() {
        return new ArrayList<>(mContacts.values());
    }

    public Contact contact(String zPublicKeyHex) {
        return mContacts.get(Keys.norm(zPublicKeyHex));
    }

    /**
     * Find a contact by the short {@link Keys#fingerprint} of their public key.
     * LAN discovery carries only the fingerprint in its NSD TXT record (the full
     * key is too large for the 255-byte cap), so this maps a discovered peer back
     * to the contact whose key hashes to it. Null if none matches.
     */
    public Contact contactByFingerprint(String zFingerprint) {
        if (zFingerprint == null || zFingerprint.isEmpty()) {
            return null;
        }
        for (Contact c : mContacts.values()) {
            if (zFingerprint.equals(Keys.fingerprint(c.publicKey))) {
                return c;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // lifecycle
    // ---------------------------------------------------------------

    /** Attach to relays and start publishing our addresses. */
    public int start(List<String> zRelays, int zTimeoutMs) {
        mPool.addCandidates(zRelays);
        int n = mPool.fill(zTimeoutMs);
        mRpc.setMyAddresses(mPool.contactAddresses());
        return n;
    }

    public void stop() {
        stopDirect();
        mPool.closeAll();
        mStore.flush();
    }

    /**
     * Tier 2: start accepting direct connections on zPort (0 = any free port).
     *
     * The endpoint does not, by itself, make us reachable - a NAT still sits in
     * front. The caller (the Android reachability manager) maps a public port
     * to it, PROVES the port from outside, and only then calls
     * {@link #setDirectAddress} so the address is advertised. Starting the
     * listener and advertising an address are deliberately two steps.
     *
     * @return the bound port, or -1 on failure
     */
    public synchronized int startDirect(int zPort) {
        if (mDirect != null && mDirect.isRunning()) {
            return mDirect.port();
        }
        mDirect = new com.eurobuddha.maxima.core.net.DirectEndpoint(
                mIdentity, mVersion, this::handle, mLocalBlobs);
        return mDirect.start(zPort);
    }

    public synchronized void stopDirect() {
        if (mDirect != null) {
            mDirect.stop();
            mDirect = null;
        }
        mDirectAddress = "";
    }

    public int directPort() {
        return mDirect == null ? -1 : mDirect.port();
    }

    /** Wire our own blob store so the direct endpoint can serve our hosted files
     *  to same-LAN peers. Call before {@link #startDirect}. */
    public void setLocalBlobs(com.eurobuddha.maxima.core.store.BlobStore zBlobs) {
        mLocalBlobs = zBlobs;
    }

    /**
     * Advertise (or withdraw, with "") a PROVEN direct address of the form
     * Mx&lt;identity&gt;@ip:port. Only call this AFTER the port has been shown
     * reachable from outside - advertising an unverified address is the classic
     * sin this whole layer refuses to repeat.
     */
    public void setDirectAddress(String zIpPort) {
        mDirectAddress = zIpPort == null ? "" : zIpPort.trim();
    }

    /**
     * Whether we currently have a PROVEN public direct address - the signal that
     * we can actually serve the reachability-gated roles (directory/mailbox/
     * storage). {@link #setDirectAddress} sets this only after the port has been
     * shown reachable from outside, so it is never a guess.
     */
    public boolean isDirectlyReachable() {
        return !mDirectAddress.isEmpty();
    }

    public String directAddress() {
        // Sealed to the IDENTITY key, not a per-host key: on a direct link the
        // endpoint decrypts with the identity private key, and there is no relay
        // to hide the routing key from anyway (the address already exposes our
        // IP). Relay addresses keep their per-host keys for unlinkability.
        return mDirectAddress.isEmpty()
                ? "" : mIdentity.mxIdentity() + "@" + mDirectAddress;
    }

    /**
     * Every EXTERNALLY-REACHABLE address we can be reached at, direct first.
     *
     * This is what we hand to remote contacts, publish to MLS, show as "your
     * address", and expose over IPC - so it must never contain our own LAN /
     * site-local IP. A NAT'd phone has no routable address of its own; its
     * reachable addresses are the relays it dialled out to (and a direct address
     * only once it has been PROVEN public). This mirrors classic exactly, which
     * advertises a connected OUTGOING host and refuses any internal-IP host
     * (MaximaManager.java:515-573): we filter the advertised set through the same
     * internal-IP predicate. The LAN direct address is deliberately excluded -
     * it lives only in {@link #directAddresses()} for same-LAN blob serving.
     *
     * Direct leads because it is the cheapest path for a sender - no relay hop.
     * (The 0.5.4 relay-first ordering was reverted: it removed the dead-direct
     * timeout but forced a permanent 2-hop relay on every send to a reachable
     * peer. The proper fix is a hedged send in sendToContact - see Part B - which
     * makes a dead direct cheap so direct-first is safe again.)
     */
    public List<String> myAddresses() {
        List<String> out = new ArrayList<>();
        String pub = directAddress();               // proven-public direct only; NOT the LAN address
        if (!pub.isEmpty() && !isInternalAddress(pub)) {
            out.add(pub);
        }
        for (String a : mPool.contactAddresses()) {  // relays we dialled out to
            if (!isInternalAddress(a)) {
                out.add(a);
            }
        }
        // Classic-scale addressing: advertise ONLY the home relays we actually
        // attend (k is small). Staleness after a home move is healed the classic
        // way - refreshContacts() pushes the new set on change, and a failed
        // send does an on-demand MLS lookup (mlsLookup) - instead of being
        // papered over by attending the whole fleet.
        return out;
    }

    /** Bypass the internal-IP filter, exactly as classic's {@code -allowallip}.
     *  Off by default: a phone is always NAT'd, so a LAN address is never a
     *  valid thing to advertise. */
    public static volatile boolean ALLOW_ALL_IP = false;

    /**
     * Classic's internal-IP test, mirrored EXACTLY (MaximaManager.java:519-527):
     * a naive dotted-decimal prefix match on the host, intentionally broad. A
     * classic node refuses to adopt (and therefore never advertises) any host
     * whose address starts with one of these, so neither do we.
     */
    public static boolean isInternalHost(String zHost) {
        if (ALLOW_ALL_IP || zHost == null || zHost.isEmpty()) {
            return false;
        }
        String h = zHost.trim();
        return h.startsWith("127.") || h.startsWith("10.") || h.startsWith("100.")
                || h.startsWith("0.") || h.startsWith("169.") || h.startsWith("172.")
                || h.startsWith("198.") || h.startsWith("192.");
    }

    /** As {@link #isInternalHost} but for a full {@code Mx…@host:port} address. */
    public static boolean isInternalAddress(String zMxAddress) {
        if (zMxAddress == null) {
            return false;
        }
        int at = zMxAddress.lastIndexOf('@');
        return isInternalHost(at < 0 ? zMxAddress : zMxAddress.substring(at + 1));
    }

    public void setLanAddress(String zIpPort) {
        mLanAddress = zIpPort == null ? "" : zIpPort.trim();
    }

    /** LAN direct address (identity-keyed), or "" when off Wi-Fi. Same identity-key
     *  form as {@link #directAddress()}: a direct link decrypts with the identity key. */
    public String lanDirectAddress() {
        return mLanAddress.isEmpty() ? "" : mIdentity.mxIdentity() + "@" + mLanAddress;
    }

    /**
     * Addresses a peer can dial to reach our DirectEndpoint (proven-public first,
     * then LAN) — the ONLY places our phone can serve its own hosted blobs. Relay
     * addresses are excluded here: a relay only serves blobs it holds, and can't
     * carry OUR blob response, so a relay-routed own address is a dead blob source.
     */
    public List<String> directAddresses() {
        List<String> out = new ArrayList<>();
        String pub = directAddress();
        if (!pub.isEmpty()) {
            out.add(pub);
        }
        String lan = lanDirectAddress();
        if (!lan.isEmpty() && !lan.equals(pub)) {
            out.add(lan);
        }
        return out;
    }

    /**
     * Drain one relay connection.
     *
     * Call this per attached relay, from whatever thread the host prefers.
     *
     * @return true if a message was processed
     */
    public boolean pump(String zHostPort, int zTimeoutMs) throws Exception {
        HostConnection conn = mPool.connection(zHostPort);
        if (conn == null) {
            return false;
        }
        HostConnection.Inbound in = conn.receive(zTimeoutMs);
        if (in == null) {
            return false;
        }
        handle(in);
        return true;
    }

    /** Route one inbound message. Synchronized: with one reader thread per
     *  attached host, inbound arrives concurrently; the chat engine and dedup
     *  were written for the old single pump thread, so serialise here. */
    public synchronized void handle(HostConnection.Inbound zInbound) {
        MaximaMessage msg = zInbound.message;

        // Replay and duplicate protection - neither exists in classic.
        DedupCache.Verdict v = mDedup.check(
                zInbound.msgid.to0xString(), msg.mTimeMilli.getAsLong());
        if (v != DedupCache.Verdict.ACCEPT) {
            return;
        }

        // Mark the sender seen NOW - any accepted inbound message is proof of
        // life, which is what the contact list's connectivity indicator reads.
        // Classic bumps a contact's lastseen only on a contact-ctrl refresh
        // (~20-min loop); we also count chat/RPC so the dot tracks a live
        // conversation, not just the last handshake. In-memory only: the UI
        // polls these Contact objects directly, and the periodic contact-ctrl
        // refresh persists lastSeen. (Self-addressed check-connect probes carry
        // our own key as the sender, so they match no contact and are ignored.)
        Contact seen = mContacts.get(Keys.norm(msg.mFrom.to0xString()));
        if (seen != null) {
            seen.lastSeen = System.currentTimeMillis();
        }

        String app = msg.mApplication.toString();

        // Check-connect reply: our own self-addressed probe came back down a
        // host, proving that host actually RELAYS to us (not just answers
        // keep-alives). The payload names the host it was sent through. Internal
        // - never surfaced to an app listener.
        if (CHECK_APP.equals(app)) {
            // The self-probe carries OUR OWN identity as the sender. Without
            // this check any contact could forge a __maxchk to pin a
            // black-holing relay as "verified", defeating the check-connect
            // audit that would otherwise detach it.
            if (Keys.same(msg.mFrom.to0xString(), mIdentity.publicKeyHex())) {
                mHostVerified.add(new String(msg.mData.getBytes(),
                        StandardCharsets.UTF_8));
            }
            return;
        }
        if (ContactCtrl.APPLICATION.equals(app)) {
            handleContactCtrl(msg);
            return;
        }
        if (mRpc.onInbound(msg)) {
            return;
        }
        MessageListener l = mListener;
        if (l != null) {
            l.onMessage(msg, zInbound.msgid);
        }
    }

    private void handleContactCtrl(MaximaMessage zMsg) {
        try {
            ContactCtrl.Parsed p = ContactCtrl.parse(
                    new String(zMsg.mData.getBytes(), StandardCharsets.UTF_8),
                    zMsg.mFrom.to0xString());

            if (p.delete) {
                Contact gone = mContacts.remove(Keys.norm(p.contact.publicKey));
                mStore.remove(C_CONTACTS, Keys.norm(p.contact.publicKey));
                if (gone != null) {
                    fireContacts(gone, true);
                }
                return;
            }
            // Classic gate: may a stranger add us at all?
            boolean known = mContacts.containsKey(Keys.norm(p.contact.publicKey));
            if (!known && !mAllowAllContacts
                    && !mAllowedContacts.contains(Keys.norm(p.contact.publicKey))) {
                return;
            }

            Contact existing = mContacts.get(Keys.norm(p.contact.publicKey));
            if (existing != null) {
                // Carry over what only we know.
                p.contact.myAddress = existing.myAddress;
            }
            recordAddressHistory(p.contact, existing);
            mContacts.put(Keys.norm(p.contact.publicKey), p.contact);
            saveContact(p.contact);
            fireContacts(p.contact, false);

            // Reciprocate an introduction, as the reference does.
            if (p.intro) {
                try {
                    introduce(p.contact.primaryAddress(), false);
                } catch (Exception ignored) {
                    // Best effort; the refresh cycle will retry.
                }
            }
        } catch (IllegalArgumentException e) {
            // Bad JSON, or the publickey did not match the signer. Drop it.
        }
    }

    // ---------------------------------------------------------------
    // contacts
    // ---------------------------------------------------------------

    /** Send a contact-ctrl introduction (or update) to an address. */
    public void introduce(String zPeerAddress, boolean zIntro) throws Exception {
        if (zPeerAddress == null) {
            return;
        }
        // A MAX# permanent address resolves to the peer's CURRENT address via
        // their MLS first - same behaviour as the classic engine. Without this
        // the raw MAX# string reaches the socket parser and dies on the port.
        if (zPeerAddress.trim().startsWith("MAX#")) {
            zPeerAddress = resolvePermanent(zPeerAddress.trim());
            log("MAX# resolved to " + zPeerAddress);
        }
        String json = ContactCtrl.build(
                mIdentity.publicKeyHex(),
                myAddresses(),   // externally-reachable, internal-IP-filtered set
                mName, mIcon, mWalletAddress, mlsAddress(),
                // Advertise the SERVER roles (directory/mailbox/storage) + host
                // capacity only while we are actually directly reachable - a NAT'd
                // node keeps its client-side roles (so it stays non-classic) but
                // never promises a service it cannot serve. Reachability, not
                // device type, is the gate.
                mCapabilities.gateForReachability(isDirectlyReachable()),
                mNodeKind, zIntro);

        sendRaw(zPeerAddress, ContactCtrl.APPLICATION,
                json.getBytes(StandardCharsets.UTF_8));
    }

    /** Tell every known contact our current addresses. Call after a relay change. */
    public int refreshContacts() {
        publishToMls();
        int ok = 0;
        for (Contact c : mContacts.values()) {
            for (String addr : c.addresses) {
                try {
                    introduce(addr, false);
                    ok++;
                    break;
                } catch (Exception ignored) {
                    // try the next address
                }
            }
        }
        return ok;
    }

    // ---------------------------------------------------------------
    // sending
    // ---------------------------------------------------------------

    /**
     * Send to a CONTACT rather than a raw address, trying every address they
     * have. Classic lets you send by contact id for the same reason: a human
     * should not have to know which host someone is on today.
     */
    public MaximaSender.Result sendToContact(Contact zContact, String zApplication, byte[] zData)
            throws Exception {
        // 1. LAN FIRST. A same-network hit is a REAL delivery straight to the peer's
        //    endpoint (not a mailbox), so on success we are done - instant, no relay.
        //    A short connect leash means a stale LAN entry fails in a few seconds and
        //    self-heals via forgetLanPeer, and we fall through to the relays.
        String lan = mLanPeers.get(Keys.norm(zContact.publicKey));
        if (lan != null) {
            try {
                MaximaSender.Result r = sendRaw(lan, zApplication, zData,
                        SEND_CONNECT_TIMEOUT_MS, MaximaSender.READ_TIMEOUT_MS);
                if (r.isOk()) {
                    return r;
                }
                forgetLanPeer(zContact.publicKey);
            } catch (Exception e) {
                forgetLanPeer(zContact.publicKey);
            }
        }

        // 2. FAN OUT to EVERY advertised address (direct + all relays), not just the
        //    first that accepts. A relay only stores-and-forwards; the peer pulls from
        //    whichever relay it is attached to RIGHT NOW. On a weak/flapping network the
        //    peer's live relay set drifts away from any single relay we would pick, so a
        //    one-relay delivery strands in a mailbox the peer never checks - the cause of
        //    the multi-minute stalls and lost messages. sendRaw opens a fresh connection
        //    to each relay, so we can drop a copy in ALL of the peer's mailboxes; wherever
        //    the peer is actually listening, a copy lands. The peer dedups by message id
        //    (ChatMessage.id), so the extra copies are harmless. This is the reliability
        //    guarantee: as long as the peer is reachable via ANY of its relays, it wins.
        MaximaSender.Result okResult = fanOut(zContact, zApplication, zData);
        if (okResult != null) {
            return okResult;
        }

        // 3. THE CLASSIC HEAL: every address failed, so the contact's advertised
        //    set is stale (they moved homes while we weren't told). Ask their MLS
        //    - the phone book classic uses for exactly this - for their CURRENT
        //    address, and retry once. This is what lets a small home-relay set
        //    (k=2, not the whole fleet) stay reliable: staleness is healed on
        //    demand instead of being papered over with redundancy.
        if (mlsLookup(zContact)) {
            okResult = fanOut(zContact, zApplication, zData);
            if (okResult != null) {
                return okResult;
            }
        }
        throw new IllegalStateException("no reachable address for " + zContact.name);
    }

    /** One pass over the contact's advertised addresses. First OK wins the
     *  return but every address still gets a copy (mailbox redundancy). Null
     *  if nothing accepted. */
    private MaximaSender.Result fanOut(Contact zContact, String zApplication, byte[] zData) {
        MaximaSender.Result okResult = null;
        // Per-address outcome, reported ONLY on total failure. A silent
        // all-addresses-dead fan-out is how an outbound path stays invisibly
        // broken for minutes while inbound flows.
        StringBuilder fails = new StringBuilder();
        for (String addr : zContact.addresses) {
            try {
                MaximaSender.Result r = sendRaw(addr, zApplication, zData,
                        SEND_CONNECT_TIMEOUT_MS, MaximaSender.READ_TIMEOUT_MS);
                if (r.isOk() && okResult == null) {
                    okResult = r;
                }
                if (!r.isOk()) {
                    fails.append(fails.length() == 0 ? "" : " | ")
                            .append(addr).append(" -> ").append(r.statusName);
                }
            } catch (Exception e) {
                fails.append(fails.length() == 0 ? "" : " | ")
                        .append(addr).append(" -> ")
                        .append(e.getClass().getSimpleName())
                        .append(e.getMessage() == null ? "" : ": " + e.getMessage());
            }
        }
        if (okResult == null) {
            log("send DEAD to " + zContact.name + " (" + zContact.addresses.size()
                    + " addr): " + fails);
        }
        return okResult;
    }

    /**
     * Ask a contact's MLS server for their CURRENT address (classic's
     * {@code **maxima_mls_get**}; the answer rides back in the ack channel as a
     * serialised MLSPacketGETResp - see {@link MaximaSender.Result#replyData}).
     * On success the fresh address is put at the FRONT of the contact's set
     * (old ones kept as fallback) and persisted.
     *
     * @return true if the contact's address set was updated
     */
    public boolean mlsLookup(Contact zContact) {
        String mls = zContact.mls;
        if (mls == null || mls.isEmpty() || zContact.publicKey == null) {
            log("mls lookup " + zContact.name + ": no mls address stored");
            return false;
        }
        try {
            final String nonce = new MiniData(
                    com.eurobuddha.maxima.core.crypto.MaximaCrypto
                            .randomBytes(16)).to0xString();
            com.eurobuddha.maxima.core.msg.MLSPacketGETReq req =
                    new com.eurobuddha.maxima.core.msg.MLSPacketGETReq(
                            zContact.publicKey, nonce);
            MaximaSender.Result r = sendRaw(mls,
                    com.eurobuddha.maxima.core.directory.MlsService.APP_GET,
                    com.eurobuddha.maxima.core.codec.Codec.serialise(req),
                    SEND_CONNECT_TIMEOUT_MS, MaximaSender.READ_TIMEOUT_MS);
            if (r == null || !r.hasPayload()) {
                log("mls lookup " + zContact.name + " @ " + mls + ": no answer");
                return false;
            }
            com.eurobuddha.maxima.core.msg.MLSPacketGETResp resp =
                    com.eurobuddha.maxima.core.msg.MLSPacketGETResp
                            .fromBytes(r.replyData.getBytes());
            String addr = resp.getAddress();
            if (addr == null || addr.isEmpty()
                    || !Keys.same(resp.getPublicKey(), zContact.publicKey)
                    || !nonce.equals(resp.getRandomUID())) {
                // The nonce check is the redirect-attack defence a malicious
                // relay could otherwise use to hand us a forged address; the
                // background resolve path (MlsClient) already enforces it.
                log("mls lookup " + zContact.name + " @ " + mls
                        + ": empty, mismatched, or replayed record");
                return false;
            }
            // Never accept an internal-IP address from the directory.
            if (isInternalAddress(addr)) {
                return false;
            }
            if (!addr.equals(zContact.primaryAddress())) {
                java.util.List<String> merged = new ArrayList<>();
                merged.add(addr);
                merged.addAll(zContact.addresses);
                zContact.setAddresses(merged);
                saveContact(zContact);
                fireContacts(zContact, false);
            }
            return true;
        } catch (Exception e) {
            log("mls lookup " + zContact.name + " @ " + mls + ": "
                    + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            return false;
        }
    }

    /**
     * Note a peer just discovered on the local network (Tier 2 LAN, phase E).
     *
     * The address is sealed to the peer's IDENTITY key - exactly what their
     * direct endpoint decrypts with - so it is built the same way our own direct
     * address is. Kept OUT of the persisted contact: a LAN address is true only
     * while both devices are on that network, so it is ephemeral by design.
     *
     * @param zPeerIdentityHex the peer's identity public key (0x hex)
     * @param zLanHostPort     e.g. 192.168.1.42:9601
     */
    public void noteLanPeer(String zPeerIdentityHex, String zLanHostPort) {
        Contact c = contact(zPeerIdentityHex);
        if (c == null) {
            return;   // only peers we already know as contacts
        }
        String mx = com.eurobuddha.maxima.core.identity.MxAddress.make(
                new MiniData(zPeerIdentityHex));
        mLanPeers.put(Keys.norm(zPeerIdentityHex), mx + "@" + zLanHostPort);
    }

    public void forgetLanPeer(String zPeerIdentityHex) {
        mLanPeers.remove(Keys.norm(zPeerIdentityHex));
    }

    /** The LAN address currently known for a contact, or null. */
    public String lanAddressFor(String zPeerIdentityHex) {
        return mLanPeers.get(Keys.norm(zPeerIdentityHex));
    }

    /** Send an application message to an address, no reliability wrapper. */
    public MaximaSender.Result sendRaw(String zAddress, String zApplication, byte[] zData)
            throws Exception {
        return sendRaw(zAddress, zApplication, zData,
                MaximaSender.CONNECT_TIMEOUT_MS, MaximaSender.READ_TIMEOUT_MS);
    }

    /**
     * As {@link #sendRaw(String, String, byte[])} but with caller-chosen socket
     * timeouts - blob replication uses a short leash (see {@code MediaWire.put}).
     */
    public MaximaSender.Result sendRaw(String zAddress, String zApplication, byte[] zData,
                                       int zConnectTimeoutMs, int zReadTimeoutMs)
            throws Exception {
        int at = zAddress.indexOf('@');
        int colon = zAddress.indexOf(':');
        MiniData routing = com.eurobuddha.maxima.core.identity.MxAddress
                .convert(zAddress.substring(0, at));
        String host = zAddress.substring(at + 1, colon);
        int port = Integer.parseInt(zAddress.substring(colon + 1));

        MaximaSender.Built built = MaximaSender.build(
                mIdentity.publicKey(), mIdentity.keyPair().getPrivate(),
                routing.getBytes(), zApplication, zData, System.currentTimeMillis());

        return MaximaSender.send(host, port, built.unit, built.msgid,
                zConnectTimeoutMs, zReadTimeoutMs);
    }

    /**
     * Send with retry: queue in the outbox, try every known address for the
     * peer, and keep the item until it succeeds or the attempts run out.
     */
    public String sendReliable(Contact zPeer, String zApplication, byte[] zData) {
        String msgid = new MiniData(
                com.eurobuddha.maxima.core.crypto.MaximaCrypto.randomBytes(16)).to0xString();
        mOutbox.add(msgid, zPeer.publicKey, zPeer.addresses, zApplication, zData);
        return msgid;
    }

    /**
     * Work the outbox once. Drive from a heartbeat.
     *
     * @return how many were delivered this pass
     */
    public int flushOutbox() {
        int sent = 0;
        for (Outbox.Item item : mOutbox.due()) {
            String addr = item.currentAddress();
            if (addr == null) {
                mOutbox.failed(item, "no address");
                continue;
            }
            try {
                MaximaSender.Result r = sendRaw(addr, item.application, item.payload);
                if (r.isOk()) {
                    mOutbox.acknowledge(item.msgid);
                    sent++;
                } else {
                    mOutbox.failed(item, r.statusName);
                }
            } catch (Exception e) {
                mOutbox.failed(item, e.getClass().getSimpleName());
            }
        }
        return sent;
    }

    /** Periodic upkeep: relays, outbox, expiries. Drive from a heartbeat. */
    public void maintain(int zAttachTimeoutMs) {
        java.util.Set<String> before = new java.util.HashSet<>(mPool.activeHosts());
        mPool.reconcile(zAttachTimeoutMs);
        List<String> addrs = mPool.contactAddresses();
        mRpc.setMyAddresses(addrs);

        // Compare the host SET, not just its size: a black-hole host dropped and
        // replaced by a live one leaves the count unchanged but our reachable
        // address CHANGED, so contacts hold a stale address and must be told the
        // new one (classic's MAXIMA_DISCONNECTED -> reassign-contacts flow). The
        // old count-only check missed exactly this same-count swap.
        java.util.Set<String> after = new java.util.HashSet<>(mPool.activeHosts());
        if (!before.equals(after)) {
            for (String h : after) {
                fireHosts(h, true);
            }
            for (String h : before) {
                if (!after.contains(h)) {
                    fireHosts(h, false);   // a host we lost - apps can react
                }
            }
            refreshContacts();
        }
        auditHosts();
        updateMlsServers();   // adopt/rotate our Location Service on schedule
        flushOutbox();
        mRpc.expire();
        mDirectory.flushExpired();

        // The periodic Maxima loop: re-publish + re-announce + re-resolve stale
        // contacts, on the reference's cadence, INDEPENDENT of a host-set change.
        // Classic does this every 20 min (first at 3 min) so a contact who moved
        // hosts, or an MLS entry that expired, is refreshed even when nothing on
        // our side changed. The heartbeat calls maintain() far more often than
        // this; the time gate makes it fire on the reference schedule.
        long nowT = System.currentTimeMillis();
        long due = (mLastMaximaLoop == 0)
                ? mStartedAt + FIRST_LOOP_MS
                : mLastMaximaLoop + MAXIMA_LOOP_MS;
        if (nowT >= due) {
            mLastMaximaLoop = nowT;
            maximaLoop();
        }
    }

    /**
     * Check-connect audit (the reference's MAXIMA_SENDCHKCONNECT /
     * MAXIMA_CHECK_CONNECTED). Keep-alive proves a host's SOCKET is alive; this
     * proves the host actually RELAYS a message addressed to us - a host can hold
     * the socket and answer pings while silently dropping relayed traffic.
     *
     * For each attached host not yet verified: send a self-addressed probe
     * through it (once), and if the probe has not come back within the grace
     * window, detach the host so reconcile fills a working one. A verified host
     * is never re-probed while it stays attached; a host that drops loses its
     * verification and is re-probed on re-attach.
     */
    void auditHosts() {
        java.util.List<String> active = mPool.activeHosts();
        // Forget state for hosts no longer attached (re-attach re-verifies).
        mHostVerified.retainAll(active);
        mHostCheckSent.keySet().retainAll(active);
        long now = System.currentTimeMillis();
        for (String hp : active) {
            if (mHostVerified.contains(hp)) {
                continue;
            }
            Long sent = mHostCheckSent.get(hp);
            if (sent == null) {
                // Only record it as sent if the relay ACCEPTED it for relay; a
                // failed send is our problem, not proof the host does not relay.
                if (sendCheckConnect(hp)) {
                    mHostCheckSent.put(hp, now);
                }
            } else if (now - sent > CHECK_GRACE_MS) {
                // Accepted for relay, graced, never delivered back: this host is
                // not relaying to us. Drop it; reconcile refills with a live one.
                mPool.detach(hp);
                mHostCheckSent.remove(hp);
            }
        }
    }

    /** Whether a host has passed its check-connect (proven to relay to us). */
    public boolean isHostVerified(String zHostPort) {
        return mHostVerified.contains(zHostPort);
    }

    /** Send a self-addressed check-connect probe through one host. The relay
     *  routes it to our per-host key, which is registered only on THIS host, so
     *  it can only arrive back down this host's connection. Returns true if the
     *  relay accepted it for relay. */
    private boolean sendCheckConnect(String zHostPort) {
        HostConnection c = mPool.connection(zHostPort);
        if (c == null || !c.isAttached()) {
            return false;
        }
        try {
            MaximaSender.Result r = sendRaw(c.contactAddress(), CHECK_APP,
                    zHostPort.getBytes(StandardCharsets.UTF_8),
                    CHECK_TIMEOUT_MS, CHECK_TIMEOUT_MS);
            return r.isOk();
        } catch (Exception e) {
            return false;   // retry next tick
        }
    }

    /** One turn of the reference's MAXIMA_LOOP: re-publish our address to MLS and
     *  re-announce to every contact (MAXIMA_REFRESH), then re-resolve contacts we
     *  have not heard from recently (MAXIMA_CHECK_MLS). */
    void maximaLoop() {
        refreshContacts();      // publishToMls() + re-introduce to all contacts
        checkStaleMls();
        mPool.purgeOldHosts();  // reference deleteOldHosts: forget 7-day-dead relays
    }

    /**
     * Self-heal stale contacts (the reference's MAXIMA_CHECK_MLS, made robust).
     * For every contact we have not heard from for {@link #MLS_STALE_MS}, look up
     * its CURRENT address again - not only via the one MLS it last advertised
     * (which may be empty or itself stale), but across EVERY directory we can
     * reach: their cached MLS first, then each relay we are attached to. Because
     * we also publish ourselves to all those relays ({@link #publishToMls}), two
     * nodes that share ANY relay converge without a manual re-add - closing the
     * mutual-orphaning gap where both sides hold each other's old address.
     *
     * The network work runs off-thread (a slow directory must never stall the
     * heartbeat), guarded so passes cannot pile up.
     *
     * @return how many stale contacts were scheduled for re-resolution
     */
    public int checkStaleMls() {
        long now = System.currentTimeMillis();
        java.util.List<Contact> stale = new ArrayList<>();
        for (Contact c : mContacts.values()) {
            if (now - c.lastSeen >= MLS_STALE_MS) {
                stale.add(c);
            }
        }
        if (stale.isEmpty()) {
            return 0;
        }
        java.util.List<String> dirs = reachableDirectories();
        // Nothing to query with (no relay, no cached MLS on any stale contact) -
        // do not spin up a thread that can only fail.
        boolean anyMls = false;
        for (Contact c : stale) {
            if (c.mls != null && !c.mls.isEmpty()) {
                anyMls = true;
                break;
            }
        }
        if (dirs.isEmpty() && !anyMls) {
            return 0;
        }
        if (!mResolveBusy.compareAndSet(false, true)) {
            return 0;   // a resolve pass is already running
        }
        Thread t = new Thread(() -> {
            try {
                resolveStale(stale, dirs);
            } finally {
                mResolveBusy.set(false);
            }
        }, "mls-selfheal");
        t.setDaemon(true);
        t.start();
        return stale.size();
    }

    /** Every directory we can currently query: each attached relay's offered MLS,
     *  plus our own current/old MLS. Deduped. */
    private java.util.List<String> reachableDirectories() {
        // Best-scoring hosts first (merit, no node type). The set is a
        // LinkedHashSet so query order follows score: the most reliable / highest
        // -capacity directories are tried first, and the 2-relay-agreement in
        // resolveVia still governs which answer we trust.
        java.util.LinkedHashSet<String> dirs = new java.util.LinkedHashSet<>();
        for (String h : mPool.activeHostsByScore()) {
            HostConnection hc = mPool.connection(h);
            String m = hc == null ? null : hc.getTheirMlsAddress();
            if (m != null && !m.isEmpty()) {
                dirs.add(m);
            }
        }
        if (!mlsAddress().isEmpty()) {
            dirs.add(mlsAddress());
        }
        if (mOldMls != null && !mOldMls.isEmpty()) {
            dirs.add(mOldMls);
        }
        return new ArrayList<>(dirs);
    }

    private void resolveStale(java.util.List<Contact> zStale, java.util.List<String> zDirs) {
        for (Contact c : zStale) {
            String fresh = resolveVia(c, zDirs);
            if (fresh == null || fresh.equals(c.primaryAddress())) {
                continue;
            }
            // Freshest address first; keep the rest as fallbacks. Do NOT bump
            // lastSeen - a lookup is not hearing from them, and bumping it would
            // stop us re-checking a still-moving contact.
            java.util.List<String> merged = new ArrayList<>();
            merged.add(fresh);
            for (String a : c.addresses) {
                if (!a.equals(fresh)) {
                    merged.add(a);
                }
            }
            c.setAddresses(merged);
            saveContact(c);
            fireContacts(c, false);
            // Push our current info to them at the fresh address so THEIR cache of
            // us updates too - this is what fixes mutual orphaning without a
            // manual re-add on either side.
            try {
                introduce(fresh, false);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Resolve a contact's current address SAFELY. A resolved address carries the
     * per-host key we then ENCRYPT to, so a directory that lies about it can make
     * us encrypt a message to an attacker's key. We therefore trust two things:
     *
     *  1. The contact's OWN advertised MLS ({@code c.mls}) - the contact chose
     *     that directory, so a single answer from it is the classic trust model.
     *  2. Otherwise, a relay we merely happen to use is NOT an authority on the
     *     contact's key, so a relay answer is accepted ONLY when at least two
     *     INDEPENDENT relays AGREE on it. A single malicious/compromised relay in
     *     our pool then cannot redirect our traffic to a key it controls.
     *
     * Requiring agreement also fixes the "fastest stale answer wins" race: a lone
     * out-of-date directory can no longer overwrite a good address.
     */
    private String resolveVia(Contact c, java.util.List<String> zRelayDirs) {
        if (c.mls != null && !c.mls.isEmpty()) {
            String a = tryResolve(c.mls, c.publicKey);
            if (a != null) {
                return a;                       // contact-vouched directory
            }
        }
        java.util.Map<String, Integer> votes = new java.util.LinkedHashMap<>();
        for (String dir : zRelayDirs) {
            if (dir.equals(c.mls)) {
                continue;                       // already tried above
            }
            String a = tryResolve(dir, c.publicKey);
            if (a != null && votes.merge(a, 1, Integer::sum) >= 2) {
                return a;                       // >=2 independent relays agree
            }
        }
        return null;
    }

    private String tryResolve(String zDir, String zTargetKey) {
        try {
            com.eurobuddha.maxima.core.directory.MlsClient.Resolved r =
                    new com.eurobuddha.maxima.core.directory.MlsClient(mIdentity)
                            .resolve(zDir, zTargetKey, SELFHEAL_TIMEOUT_MS, SELFHEAL_TIMEOUT_MS);
            return r.ok() && r.address != null && !r.address.isEmpty() ? r.address : null;
        } catch (Exception e) {
            return null;
        }
    }
}
