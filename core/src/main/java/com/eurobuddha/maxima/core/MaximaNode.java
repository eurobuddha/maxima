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
public final class MaximaNode {

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

    private volatile MessageListener mListener;
    private volatile EventListener mEvents;

    public MaximaNode(MaximaIdentity zIdentity, String zVersion, int zRelayTarget) {
        mIdentity = zIdentity;
        mVersion = zVersion;
        mPool = new HostPool(zIdentity, zVersion, zRelayTarget);
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

    static String contactToJson(Contact c) {
        return new Json.Writer()
                .put("publickey", c.publicKey)
                .put("name", c.name)
                .put("icon", c.icon)
                .put("addresses", String.join(",", c.addresses))
                .put("myaddress", c.myAddress == null ? "" : c.myAddress)
                .put("mls", c.mls == null ? "" : c.mls)
                .put("minimaaddress", c.minimaAddress == null ? "" : c.minimaAddress)
                .put("caps", c.capabilities.encode())
                .put("lastseen", Long.toString(c.lastSeen))
                .done();
    }

    static Contact contactFromJson(String zJson) {
        Map<String, String> m = Json.parse(zJson);
        Contact c = new Contact(m.get("publickey"));
        c.name = m.getOrDefault("name", "noname");
        c.icon = m.getOrDefault("icon", "0x00");
        c.myAddress = m.getOrDefault("myaddress", "");
        c.mls = m.getOrDefault("mls", "");
        c.minimaAddress = m.getOrDefault("minimaaddress", "");
        c.capabilities = Capabilities.decode(m.get("caps"));
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
        for (String h : mPool.activeHosts()) {
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

    /** Publish our address to our MLS - to BOTH the current server and the one
     *  we most recently rotated away from, so a contact who cached the old
     *  address still resolves us (the reference publishes to current + old). */
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
        for (String mls : targets) {
            try {
                any |= new com.eurobuddha.maxima.core.directory.MlsClient(mIdentity)
                        .publish(mls, myAddresses(), readers);
            } catch (Exception ignored) {
                // best effort per server
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

    public String directAddress() {
        // Sealed to the IDENTITY key, not a per-host key: on a direct link the
        // endpoint decrypts with the identity private key, and there is no relay
        // to hide the routing key from anyway (the address already exposes our
        // IP). Relay addresses keep their per-host keys for unlinkability.
        return mDirectAddress.isEmpty()
                ? "" : mIdentity.mxIdentity() + "@" + mDirectAddress;
    }

    /**
     * Every address we can be reached at, direct first.
     *
     * Direct leads because it is the cheapest path for a sender - no relay hop -
     * and senders already race the list and fail over, so a direct address that
     * dies costs them one timeout before they fall back to a relay.
     */
    public List<String> myAddresses() {
        List<String> out = new ArrayList<>();
        out.addAll(directAddresses());
        out.addAll(mPool.contactAddresses());
        return out;
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

    /** Route one inbound message. */
    public void handle(HostConnection.Inbound zInbound) {
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
            mHostVerified.add(new String(msg.mData.getBytes(), StandardCharsets.UTF_8));
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
        String json = ContactCtrl.build(
                mIdentity.publicKeyHex(),
                mPool.contactAddresses(),
                mName, mIcon, "", mlsAddress(),
                mCapabilities, zIntro);

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
        Exception last = null;
        String lan = mLanPeers.get(Keys.norm(zContact.publicKey));
        // A LAN-discovered address is tried FIRST: it is on the same network,
        // reaches the peer's direct endpoint with no relay, and works even with
        // the internet down. If it fails we fall straight through to the relay
        // addresses AND forget the LAN entry, so a peer who left the network
        // stops taxing every future send with a connect timeout - the entry
        // self-heals even if the mDNS "lost" event was missed.
        for (String addr : sendOrder(zContact)) {
            boolean isLan = addr.equals(lan);
            try {
                MaximaSender.Result r = sendRaw(addr, zApplication, zData);
                if (r.isOk()) {
                    return r;
                }
                if (isLan) {
                    forgetLanPeer(zContact.publicKey);
                }
            } catch (Exception e) {
                if (isLan) {
                    forgetLanPeer(zContact.publicKey);
                }
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("no reachable address for " + zContact.name);
    }

    /** LAN address (if any) first, then the contact's known addresses. */
    private List<String> sendOrder(Contact zContact) {
        List<String> out = new ArrayList<>();
        String lan = mLanPeers.get(Keys.norm(zContact.publicKey));
        if (lan != null) {
            out.add(lan);
        }
        out.addAll(zContact.addresses);
        return out;
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
     * Re-resolve, via their MLS, the current address of every contact we have not
     * heard from for {@link #MLS_STALE_MS} — the reference's MAXIMA_CHECK_MLS. A
     * contact who moved hosts advertises the new address to their MLS; without
     * this we would keep trying a dead address until they happen to message us.
     *
     * @return how many contacts got a fresh address
     */
    public int checkStaleMls() {
        long now = System.currentTimeMillis();
        int refreshed = 0;
        for (Contact c : mContacts.values()) {
            if (c.mls == null || c.mls.isEmpty()) {
                continue;                       // no MLS server known for them
            }
            if (now - c.lastSeen < MLS_STALE_MS) {
                continue;                       // heard from recently - not stale
            }
            try {
                com.eurobuddha.maxima.core.directory.MlsClient.Resolved r =
                        new com.eurobuddha.maxima.core.directory.MlsClient(mIdentity)
                                .resolve(c.mls, c.publicKey);
                if (r.ok() && r.address != null && !r.address.isEmpty()
                        && !r.address.equals(c.primaryAddress())) {
                    // Freshest address first; keep the rest as fallbacks. Do NOT
                    // bump lastSeen - an MLS lookup is not hearing from them, and
                    // bumping it would stop us re-checking a still-moving contact.
                    java.util.List<String> merged = new ArrayList<>();
                    merged.add(r.address);
                    for (String a : c.addresses) {
                        if (!a.equals(r.address)) {
                            merged.add(a);
                        }
                    }
                    c.setAddresses(merged);
                    saveContact(c);
                    fireContacts(c, false);
                    refreshed++;
                }
            } catch (Exception ignored) {
                // Best effort; the next loop retries.
            }
        }
        return refreshed;
    }
}
