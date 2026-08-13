package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.contacts.ContactCtrl;
import com.eurobuddha.maxima.core.directory.MlsStore;
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
    private final HostPool mPool;
    private final ServiceRegistry mServices = new ServiceRegistry();
    private final RpcPeer mRpc;
    private final Tier1Services mTier1;

    private final Map<String, Contact> mContacts = new ConcurrentHashMap<>();
    private final DedupCache mDedup = new DedupCache();
    private final Outbox mOutbox = new Outbox();
    private final Mailbox mMailbox = new Mailbox();
    private final MlsStore mDirectory = new MlsStore();

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
                if (c != null && c.publicKey != null) {
                    mContacts.put(c.publicKey.toUpperCase(), c);
                    loaded++;
                }
            } catch (Exception ignored) {
                // One unreadable contact must not stop the rest loading.
            }
        }
        if (loaded > 0) {
            mDedup.clear();
        }
    }

    /** Add or update a contact and persist it. */
    public void storeContact(Contact zContact) {
        mContacts.put(zContact.publicKey.toUpperCase(), zContact);
        saveContact(zContact);
        fireContacts(zContact, false);
    }

    private void saveContact(Contact zContact) {
        mStore.put(C_CONTACTS, zContact.publicKey.toUpperCase(), contactToJson(zContact));
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
     * first one a host offered us, exactly as classic falls back.
     */
    public String mlsAddress() {
        if (!mStaticMls.isEmpty()) {
            return mStaticMls;
        }
        for (String h : mPool.activeHosts()) {
            HostConnection c = mPool.connection(h);
            if (c != null && c.getTheirMlsAddress() != null) {
                return c.getTheirMlsAddress();
            }
        }
        return "";
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
        mAllowedContacts.add(zPublicKeyHex.toUpperCase());
    }

    public java.util.List<String> allowedContacts() {
        return new ArrayList<>(mAllowedContacts);
    }

    public void clearAllowedContacts() {
        mAllowedContacts.clear();
    }

    // ---- classic: maxextra addpermanent / listpermanent / clearpermanent ----
    public void addPermanent(String zPublicKeyHex) {
        mPermanent.add(zPublicKeyHex.toUpperCase());
        mDirectory.addPermanent(zPublicKeyHex);
    }

    public java.util.List<String> permanentKeys() {
        return new ArrayList<>(mPermanent);
    }

    public void removePermanent(String zPublicKeyHex) {
        mPermanent.remove(zPublicKeyHex.toUpperCase());
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

    /** Publish our address to our MLS. Classic does this on every refresh. */
    public boolean publishToMls() {
        String mls = mlsAddress();
        if (mls.isEmpty() || myAddresses().isEmpty()) {
            return false;
        }
        try {
            java.util.List<String> readers = new ArrayList<>();
            for (Contact c : mContacts.values()) {
                readers.add(c.publicKey);
            }
            return new com.eurobuddha.maxima.core.directory.MlsClient(mIdentity)
                    .publish(mls, myAddresses(), readers);
        } catch (Exception e) {
            return false;
        }
    }

    // ---- classic: maxcontacts action:remove ----
    /** Remove a contact AND tell them, as classic does. */
    public boolean removeContact(String zPublicKeyHex) {
        Contact c = mContacts.remove(zPublicKeyHex.toUpperCase());
        if (c == null) {
            return false;
        }
        mStore.remove(C_CONTACTS, zPublicKeyHex.toUpperCase());
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
        return mContacts.get(zPublicKeyHex.toUpperCase());
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
        mPool.closeAll();
        mStore.flush();
    }

    public List<String> myAddresses() {
        return mPool.contactAddresses();
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

        String app = msg.mApplication.toString();

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
                Contact gone = mContacts.remove(p.contact.publicKey.toUpperCase());
                mStore.remove(C_CONTACTS, p.contact.publicKey.toUpperCase());
                if (gone != null) {
                    fireContacts(gone, true);
                }
                return;
            }
            // Classic gate: may a stranger add us at all?
            boolean known = mContacts.containsKey(p.contact.publicKey.toUpperCase());
            if (!known && !mAllowAllContacts
                    && !mAllowedContacts.contains(p.contact.publicKey.toUpperCase())) {
                return;
            }

            Contact existing = mContacts.get(p.contact.publicKey.toUpperCase());
            if (existing != null) {
                // Carry over what only we know.
                p.contact.myAddress = existing.myAddress;
            }
            recordAddressHistory(p.contact, existing);
            mContacts.put(p.contact.publicKey.toUpperCase(), p.contact);
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
        for (String addr : zContact.addresses) {
            try {
                MaximaSender.Result r = sendRaw(addr, zApplication, zData);
                if (r.isOk()) {
                    return r;
                }
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("no reachable address for " + zContact.name);
    }

    /** Send an application message to an address, no reliability wrapper. */
    public MaximaSender.Result sendRaw(String zAddress, String zApplication, byte[] zData)
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

        return MaximaSender.send(host, port, built.unit, built.msgid);
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
        int before = mPool.activeCount();
        mPool.reconcile(zAttachTimeoutMs);
        List<String> addrs = mPool.contactAddresses();
        mRpc.setMyAddresses(addrs);

        // Relay set changed, so contacts hold stale addresses for us.
        if (mPool.activeCount() != before) {
            for (String h : mPool.activeHosts()) {
                fireHosts(h, true);
            }
            refreshContacts();
        }
        flushOutbox();
        mRpc.expire();
        mDirectory.flushExpired();
    }
}
