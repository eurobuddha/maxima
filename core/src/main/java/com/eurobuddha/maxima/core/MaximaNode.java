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

    private volatile String mName = "noname";
    private volatile Capabilities mCapabilities = Capabilities.phoneDefaults();

    /** Application messages that are not ours - handed to the embedder. */
    public interface MessageListener {
        void onMessage(MaximaMessage zMessage, MiniData zMsgid);
    }

    private volatile MessageListener mListener;

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
    }

    public void setCapabilities(Capabilities zCaps) {
        mCapabilities = zCaps;
    }

    public Capabilities capabilities() {
        return mCapabilities;
    }

    public void setMessageListener(MessageListener zListener) {
        mListener = zListener;
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
                mContacts.remove(p.contact.publicKey.toUpperCase());
                return;
            }
            Contact existing = mContacts.get(p.contact.publicKey.toUpperCase());
            if (existing != null) {
                // Carry over what only we know.
                p.contact.myAddress = existing.myAddress;
            }
            mContacts.put(p.contact.publicKey.toUpperCase(), p.contact);

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
                mName, "0x00", "", "",
                mCapabilities, zIntro);

        sendRaw(zPeerAddress, ContactCtrl.APPLICATION,
                json.getBytes(StandardCharsets.UTF_8));
    }

    /** Tell every known contact our current addresses. Call after a relay change. */
    public int refreshContacts() {
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
            refreshContacts();
        }
        flushOutbox();
        mRpc.expire();
        mDirectory.flushExpired();
    }
}
