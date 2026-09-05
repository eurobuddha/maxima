package com.eurobuddha.maxima.core.services;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.Hashes;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.directory.MlsService;
import com.eurobuddha.maxima.core.directory.MlsStore;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.mailbox.Mailbox;
import com.eurobuddha.maxima.core.rpc.ServiceRegistry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TIER 1 - everything a phone can contribute from the deepest CGNAT.
 *
 * All of it is push-based or reply-as-message, so none of it needs inbound
 * reachability. All of it is idempotent and safe to abandon mid-flight, because
 * a phone WILL vanish mid-request and the network must not care.
 *
 * Phones are good at storage, redundancy and directory replication; they are
 * bad at routing and uptime. These services are chosen to sit squarely on the
 * first list.
 */
public final class Tier1Services {

    // ---- method names ----
    public static final String MAILBOX_STORE = "mailbox.store";
    public static final String MAILBOX_FETCH = "mailbox.fetch";
    public static final String MAILBOX_ACK = "mailbox.ack";
    public static final String MAILBOX_COUNT = "mailbox.count";

    public static final String GOSSIP_TELL = "gossip.tell";
    public static final String GOSSIP_ASK = "gossip.ask";

    public static final String BLOB_PUT = "blob.put";
    public static final String BLOB_GET = "blob.get";
    public static final String BLOB_HAS = "blob.has";

    public static final String WITNESS_SIGN = "witness.sign";

    public static final String PING = "ping";

    private final MaximaIdentity mIdentity;
    private final Mailbox mMailbox;
    private final MlsStore mDirectory;

    /** Content-addressed blobs: sha3(content) -> content. */
    private final Map<String, byte[]> mBlobs = new ConcurrentHashMap<>();

    /** Addresses learned for other identities, shared with mutual contacts. */
    private final Map<String, String> mGossip = new ConcurrentHashMap<>();

    private long mMaxBlobBytes = 4L * 1024 * 1024;
    private int mMaxBlobs = 512;

    /** Asked before every duty we would perform for someone else. */
    private volatile ContributionPolicy mPolicy = ContributionPolicy.ALWAYS;

    // Simple counters, so a device can SHOW what it is contributing. Being able
    // to see your own usefulness is most of why anyone leaves it switched on.
    private final java.util.concurrent.atomic.AtomicLong mMailHeld =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong mLookupsServed =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong mBlobsStored =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong mReceiptsSigned =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong mGossipLearned =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong mRefused =
            new java.util.concurrent.atomic.AtomicLong();

    public Tier1Services(MaximaIdentity zIdentity, Mailbox zMailbox, MlsStore zDirectory) {
        mIdentity = zIdentity;
        mMailbox = zMailbox;
        mDirectory = zDirectory;
    }

    public Mailbox mailbox() {
        return mMailbox;
    }

    public Map<String, byte[]> blobs() {
        return mBlobs;
    }

    public Map<String, String> gossip() {
        return mGossip;
    }

    public void setPolicy(ContributionPolicy zPolicy) {
        mPolicy = zPolicy == null ? ContributionPolicy.ALWAYS : zPolicy;
    }

    public ContributionPolicy policy() {
        return mPolicy;
    }

    /** What this device has actually done for other people. */
    public String contributionSummary() {
        return "mail held " + mMailHeld.get()
                + " | lookups " + mLookupsServed.get()
                + " | blobs " + mBlobsStored.get()
                + " | receipts " + mReceiptsSigned.get()
                + " | addresses shared " + mGossipLearned.get()
                + (mRefused.get() > 0 ? " | declined " + mRefused.get() : "");
    }

    public long mailHeld() { return mMailHeld.get(); }
    public long lookupsServed() { return mLookupsServed.get(); }
    public long blobsStored() { return mBlobsStored.get(); }
    public long receiptsSigned() { return mReceiptsSigned.get(); }
    public long declined() { return mRefused.get(); }

    public void setBlobLimits(int zMaxBlobs, long zMaxBytes) {
        mMaxBlobs = zMaxBlobs;
        mMaxBlobBytes = zMaxBytes;
    }

    /** Register everything on an RPC registry. */
    public void registerAll(ServiceRegistry zReg) {
        registerPing(zReg);
        registerMailbox(zReg);
        registerGossip(zReg);
        registerBlobs(zReg);
        registerWitness(zReg);
        new MlsService(mDirectory).registerRpc(zReg);
    }

    private void registerPing(ServiceRegistry zReg) {
        zReg.register(PING, req -> "pong".getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------
    // Mailbox - hold ciphertext for an offline contact, deliver on return
    // ---------------------------------------------------------------
    private void registerMailbox(ServiceRegistry zReg) {

        // payload: "<recipientKeyHex>|<base16 ciphertext>"
        zReg.register(MAILBOX_STORE, req -> {
            String[] parts = req.payloadAsString().split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("expected recipient|hex");
            }
            if (!mPolicy.acceptMailbox()) {
                mRefused.incrementAndGet();
                return "DECLINED".getBytes(StandardCharsets.UTF_8);
            }
            byte[] cipher = new MiniData(parts[1]).getBytes();
            Mailbox.Result r = mMailbox.store(parts[0], cipher);
            if (r == Mailbox.Result.STORED) {
                mMailHeld.incrementAndGet();
            }
            return r.name().getBytes(StandardCharsets.UTF_8);
        });

        // A peer may only fetch its OWN mail - the requester key is the box key,
        // never anything the caller supplies.
        zReg.register(MAILBOX_FETCH, req -> {
            String me = new MiniData(req.fromPublicKey).to0xString();
            long after = parseLong(req.payloadAsString(), 0);
            List<Mailbox.Item> items = mMailbox.fetch(me, after, 16);
            StringBuilder sb = new StringBuilder();
            for (Mailbox.Item i : items) {
                byte[] ct = i.ciphertext();
                if (ct == null) {
                    continue;   // gone from the store underneath us: nothing to hand over
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(i.sequence).append('|').append(new MiniData(ct).to0xString());
            }
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        });

        zReg.register(MAILBOX_ACK, req -> {
            String me = new MiniData(req.fromPublicKey).to0xString();
            int removed = mMailbox.acknowledge(me, parseLong(req.payloadAsString(), 0));
            return Integer.toString(removed).getBytes(StandardCharsets.UTF_8);
        });

        zReg.register(MAILBOX_COUNT, req -> {
            String who = req.payloadAsString().trim();
            if (who.isEmpty()) {
                who = new MiniData(req.fromPublicKey).to0xString();
            }
            return Integer.toString(mMailbox.count(who)).getBytes(StandardCharsets.UTF_8);
        });
    }

    // ---------------------------------------------------------------
    // Gossip - share learned addresses for mutual contacts
    //
    // This attacks the orphaning race in classic: if both peers lose their
    // relays at the same moment, each announces its new address to the other's
    // DEAD address and the pair is stranded. A third party that knows both can
    // reconnect them.
    // ---------------------------------------------------------------
    private void registerGossip(ServiceRegistry zReg) {

        // payload: "<identityKeyHex>|<address>"
        zReg.register(GOSSIP_TELL, req -> {
            String[] p = req.payloadAsString().split("\\|", 2);
            if (p.length != 2) {
                throw new IllegalArgumentException("expected key|address");
            }
            if (!mPolicy.acceptDirectory()) {
                mRefused.incrementAndGet();
                return "DECLINED".getBytes(StandardCharsets.UTF_8);
            }
            mGossip.put(p[0].trim().toUpperCase(), p[1].trim());
            mGossipLearned.incrementAndGet();
            return "ok".getBytes(StandardCharsets.UTF_8);
        });

        zReg.register(GOSSIP_ASK, req -> {
            String key = req.payloadAsString().trim().toUpperCase();
            String addr = mGossip.get(key);
            if (addr != null) {
                mLookupsServed.incrementAndGet();
            }
            return (addr == null ? "" : addr).getBytes(StandardCharsets.UTF_8);
        });
    }

    // ---------------------------------------------------------------
    // Content availability - encrypted blobs, content addressed
    //
    // msgid-style addressing comes free: the id IS sha3(content), so storage is
    // idempotent and replication is trivially verifiable.
    // ---------------------------------------------------------------
    private void registerBlobs(ServiceRegistry zReg) {

        zReg.register(BLOB_PUT, req -> {
            byte[] content = req.payload;
            if (content.length > mMaxBlobBytes) {
                throw new IllegalArgumentException("blob too large");
            }
            if (mBlobs.size() >= mMaxBlobs && !mBlobs.containsKey(idOf(content))) {
                throw new IllegalArgumentException("blob store full");
            }
            if (!mPolicy.acceptStorage()) {
                mRefused.incrementAndGet();
                throw new IllegalStateException("not accepting storage right now");
            }
            String id = idOf(content);
            if (mBlobs.put(id, content) == null) {
                mBlobsStored.incrementAndGet();
            }
            return id.getBytes(StandardCharsets.UTF_8);
        });

        zReg.register(BLOB_GET, req -> {
            byte[] b = mBlobs.get(req.payloadAsString().trim().toUpperCase()
                    .replace("0X", "0x"));
            return b == null ? new byte[0] : b;
        });

        zReg.register(BLOB_HAS, req -> {
            boolean has = mBlobs.containsKey(req.payloadAsString().trim());
            return Boolean.toString(has).getBytes(StandardCharsets.UTF_8);
        });
    }

    // ---------------------------------------------------------------
    // Witness - countersign "I saw msgid X at time T"
    //
    // Gives the network delivery evidence, which classic has none of: its ack
    // is sent by whichever node terminated the socket, before the application
    // ever sees the message.
    // ---------------------------------------------------------------
    private void registerWitness(ServiceRegistry zReg) {
        zReg.register(WITNESS_SIGN, req -> {
            if (!mPolicy.acceptWitness()) {
                mRefused.incrementAndGet();
                throw new IllegalStateException("not witnessing right now");
            }
            mReceiptsSigned.incrementAndGet();
            String msgid = req.payloadAsString().trim();
            long now = System.currentTimeMillis();
            String statement = msgid + "|" + now;
            byte[] sig = MaximaCrypto.sign(mIdentity.keyPair().getPrivate(),
                    statement.getBytes(StandardCharsets.UTF_8));
            return (statement + "|" + new MiniData(sig).to0xString())
                    .getBytes(StandardCharsets.UTF_8);
        });
    }

    /** Verify a witness receipt against the witness's identity key. */
    public static boolean verifyWitness(byte[] zWitnessPublicKey, String zReceipt) {
        String[] p = zReceipt.split("\\|");
        if (p.length != 3) {
            return false;
        }
        String statement = p[0] + "|" + p[1];
        return MaximaCrypto.verify(zWitnessPublicKey,
                statement.getBytes(StandardCharsets.UTF_8),
                new MiniData(p[2]).getBytes());
    }

    /** Content id: SHA3-256 of the bytes. Public so callers can verify replication. */
    public static String idOf(byte[] zContent) {
        return new MiniData(Hashes.sha3(zContent)).to0xString();
    }

    private static long parseLong(String zValue, long zDefault) {
        try {
            return Long.parseLong(zValue.trim());
        } catch (Exception e) {
            return zDefault;
        }
    }

    public List<String> methodNames() {
        return new ArrayList<>(java.util.Arrays.asList(
                PING, MAILBOX_STORE, MAILBOX_FETCH, MAILBOX_ACK, MAILBOX_COUNT,
                GOSSIP_TELL, GOSSIP_ASK, BLOB_PUT, BLOB_GET, BLOB_HAS,
                WITNESS_SIGN, MlsService.RPC_LOOKUP, MlsService.RPC_PUBLISH));
    }
}
