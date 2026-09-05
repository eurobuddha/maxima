package com.eurobuddha.maxima.core.chat;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.MaximaSender;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.identity.Keys;
import com.eurobuddha.maxima.core.msg.MaximaMessage;
import com.eurobuddha.maxima.core.store.Store;
import com.eurobuddha.maxima.core.util.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Chat over Maxima: 1:1, groups, and two-tick delivery.
 *
 * The group rules are FreezePeach's, which is running code rather than a design
 * I invented (see ChatMessage). The receipt layer is new, and only possible
 * because a reply here can be a fresh outbound message rather than a
 * socket-level ack - classic cannot express "the recipient actually got it".
 *
 * This lives in :core so it can be tested with no device, and so the Android
 * app is only UI over it.
 */
public final class ChatEngine {

    /** One sent or received message, with its delivery state. */
    public static final class Entry {
        public final String id;
        public final String peer;        // 1:1 identity, or "" for a group
        public final String groupId;     // "" for 1:1
        public final String sender;      // who wrote it
        public final String body;
        public final long time;
        public final boolean mine;
        public volatile String state;

        /** When this message ARRIVED on this device (epoch ms). 0 for our own
         *  messages and for entries stored before this field existed. The
         *  send-time lives in {@link #time}; showing both makes a late relay
         *  delivery visible ("sent 13:56 · arrived 14:02"). */
        public volatile long arrived;

        /**
         * Group bookkeeping - deliveredBy / classicSent / resendAttempts / resendNotBefore /
         * undeliveredBy - is allocated LAZILY. Five synchronized collections per entry cost
         * ~1 KB each on a 1:1 message that never uses them; at 50 000 stored messages that was
         * tens of MB of empty sets on a phone. Readers get an empty view when nothing was
         * ever recorded; writers create on first use.
         */
        private volatile java.util.Set<String> mDeliveredBy;
        private volatile java.util.Set<String> mClassicSent;
        private volatile java.util.Map<String, Integer> mResendAttempts;
        private volatile java.util.Map<String, Long> mResendNotBefore;
        private volatile java.util.Set<String> mUndeliveredBy;

        /** For a group: who has confirmed delivery so far (read view; empty if none). */
        public java.util.Set<String> deliveredBy() {
            java.util.Set<String> v = mDeliveredBy;
            return v == null ? java.util.Collections.emptySet() : v;
        }

        synchronized java.util.Set<String> deliveredByMut() {
            if (mDeliveredBy == null) {
                mDeliveredBy = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
            }
            return mDeliveredBy;
        }

        /** Classic members we've already sent to once (redeliver dedup only -
         *  NOT a delivery confirmation, kept out of deliveredBy). */
        java.util.Set<String> classicSent() {
            java.util.Set<String> v = mClassicSent;
            return v == null ? java.util.Collections.emptySet() : v;
        }

        synchronized java.util.Set<String> classicSentMut() {
            if (mClassicSent == null) {
                mClassicSent = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
            }
            return mClassicSent;
        }

        java.util.Map<String, Integer> resendAttempts() {
            java.util.Map<String, Integer> v = mResendAttempts;
            return v == null ? java.util.Collections.emptyMap() : v;
        }

        synchronized java.util.Map<String, Integer> resendAttemptsMut() {
            if (mResendAttempts == null) {
                mResendAttempts = new java.util.concurrent.ConcurrentHashMap<>();
            }
            return mResendAttempts;
        }

        java.util.Map<String, Long> resendNotBefore() {
            java.util.Map<String, Long> v = mResendNotBefore;
            return v == null ? java.util.Collections.emptyMap() : v;
        }

        synchronized java.util.Map<String, Long> resendNotBeforeMut() {
            if (mResendNotBefore == null) {
                mResendNotBefore = new java.util.concurrent.ConcurrentHashMap<>();
            }
            return mResendNotBefore;
        }

        /** Group members the bounded resend has given up on (read view; empty if none). */
        public java.util.Set<String> undeliveredBy() {
            java.util.Set<String> v = mUndeliveredBy;
            return v == null ? java.util.Collections.emptySet() : v;
        }

        synchronized java.util.Set<String> undeliveredByMut() {
            if (mUndeliveredBy == null) {
                mUndeliveredBy = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
            }
            return mUndeliveredBy;
        }

        Entry(String zId, String zPeer, String zGroupId, String zSender,
              String zBody, long zTime, boolean zMine, String zState) {
            id = zId;
            peer = zPeer;
            groupId = zGroupId;
            sender = zSender;
            body = zBody;
            time = zTime;
            mine = zMine;
            state = zState;
        }

        public boolean isGroup() {
            return !groupId.isEmpty();
        }
    }

    /** UI hook. */
    public interface Listener {
        void onMessage(Entry zEntry);

        void onStateChanged(Entry zEntry);

        void onGroupChanged(Group zGroup);
    }

    private final com.eurobuddha.maxima.core.ChatPort mNode;
    private final Map<String, Group> mGroups = new ConcurrentHashMap<>();
    /**
     * Concurrent, not a LinkedHashMap.
     *
     * onInbound() runs on the transport pump thread while the UI calls send()
     * and conversation() on the main thread, so these genuinely overlap. A
     * plain HashMap read during a resize does not throw - it can spin forever -
     * which presents as a hung thread with nothing pointing here. Ordering is
     * not needed: every reader sorts explicitly.
     */
    private final Map<String, Entry> mMessages = new ConcurrentHashMap<>();

    /**
     * One small pool for outbound receipts.
     *
     * Previously a thread per receipt. Draining a mailbox holding 128 messages
     * spawned 128 threads, each opening a socket; markRead() could spawn one
     * per message in the conversation. Both are an ANR or OOM on a phone.
     */
    /** Group fan-out: members in PARALLEL (bounded), each on the first relay that accepts.
     *  A dead member costs one leash in parallel with the others, not a serial 15-60 s. */
    private static final int GROUP_FANOUT_THREADS = 8;
    private final ExecutorService mGroupPool = Executors.newFixedThreadPool(GROUP_FANOUT_THREADS, r -> {
        Thread t = new Thread(r, "parlons-group-fanout");
        t.setDaemon(true);
        return t;
    });
    /** Bounded group resend: attempts per member, with backoff between them. */
    static final int GROUP_RESEND_MAX = 3;
    private static final long[] GROUP_RESEND_BACKOFF_MS = { 45_000L, 5 * 60_000L, 30 * 60_000L };
    /** Coalesced group receipts: per sender, the newest message received in the window; one
     *  receipt (upto) per sender per window instead of one full send per message. */
    private static final long GROUP_RECEIPT_WINDOW_MS = 10_000L;
    private final java.util.Map<String, Entry> mPendingGroupReceipts = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean mReceiptFlushArmed = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ScheduledExecutorService mReceiptFlusher =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "parlons-group-receipts");
                t.setDaemon(true);
                return t;
            });

    private final ExecutorService mReceiptPool = Executors.newFixedThreadPool(2,
            new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "chat-receipt");
                    t.setDaemon(true);
                    return t;
                }
            });

    /**
     * State changes are written behind rather than immediately.
     *
     * FileStore rewrites a whole collection on every put, so persisting each
     * receipt made a burst of N receipts cost O(N^2) file writes. NEW messages
     * still persist immediately - losing one is real data loss - but a state
     * change is recoverable: the worst case after a crash is a tick showing
     * stale, and the next receipt or restart corrects it.
     */
    private final java.util.Set<String> mDirty =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
    private volatile Listener mListener;

    /** Off by default. Read receipts are a privacy choice, not a default. */
    private volatile boolean mSendReadReceipts = false;

    private volatile Store mStore = Store.MEMORY_ONLY;

    private static final String C_GROUPS = "chat_groups";
    private static final String C_MESSAGES = "chat_messages";
    private static final String C_READ = "chat_read";
    private static final String C_WALLET = "chat_wallet";

    /**
     * A contact's shared WALLET receive address, keyed by their identity pubkey.
     * A contact's Maxima identity address is not their wallet address (the
     * wallet is a separate key), so to pay someone from a chat we need them to
     * tell us where. Persisted so it survives a restart and you can pay without
     * waiting for them to re-announce.
     */
    private final Map<String, String> mWalletAddr = new ConcurrentHashMap<>();

    /**
     * When each conversation was last read by US.
     *
     * Separate from the delivery receipt a peer sends: this is purely local
     * bookkeeping so the list can show an unread badge and a notification can
     * be suppressed for a thread already on screen. Persisted, because opening
     * the app after a restart to find everything unread again is worse than
     * useless - it trains you to ignore the badge.
     */
    private final Map<String, Long> mLastRead = new ConcurrentHashMap<>();

    /**
     * Cap on retained messages per conversation.
     *
     * A phone cannot hold an unbounded history and a file-backed store rewrites
     * the whole collection on change, so this is a real limit rather than a
     * nicety. Oldest are dropped first.
     */
    private volatile int mMaxPerConversation = 500;

    public ChatEngine(com.eurobuddha.maxima.core.ChatPort zNode) {
        mNode = zNode;
    }

    /** Attach storage and reload groups and threads. Call before use. */
    public void setStore(Store zStore) {
        mStore = zStore == null ? Store.MEMORY_ONLY : zStore;
        // Before the engine acknowledges held mail, everything we have written must be on
        // disk - this is what makes a coalescing (write-behind) chat store safe.
        mNode.addFlushHook(() -> mStore.flush());
        mStoreAttached = true;
        load();
        mLoaded.countDown();
    }

    /**
     * As {@link #setStore} but the reload runs on its own thread: parsing tens of thousands
     * of message records on the Android main thread was an ANR at ~30 000 messages. Every
     * read or send waits (bounded) for the load; inbound arriving meanwhile queues on the
     * node's inbound lane. {@code zOnLoaded} runs on the loader thread when done.
     */
    public void setStoreAsync(Store zStore, Runnable zOnLoaded) {
        mStore = zStore == null ? Store.MEMORY_ONLY : zStore;
        mNode.addFlushHook(() -> mStore.flush());
        mStoreAttached = true;
        Thread t = new Thread(() -> {
            try {
                load();
            } finally {
                mLoaded.countDown();
            }
            if (zOnLoaded != null) {
                try {
                    zOnLoaded.run();
                } catch (Exception ignored) {
                }
            }
        }, "chat-load");
        t.setDaemon(true);
        t.start();
    }

    /** Counts down once the store is loaded (immediately for a memory-only engine). */
    private final java.util.concurrent.CountDownLatch mLoaded = new java.util.concurrent.CountDownLatch(1);

    /** True once loaded; a UI can poll this instead of blocking. */
    public boolean isLoaded() {
        return mLoaded.getCount() == 0;
    }

    /** Block (bounded) until the store is loaded. */
    private volatile boolean mStoreAttached;

    private void awaitLoaded() {
        if (!mStoreAttached || mLoaded.getCount() == 0) {
            return;   // a memory-only engine (tests, no store) is loaded by definition
        }
        try {
            mLoaded.await(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void setMaxPerConversation(int zMax) {
        mMaxPerConversation = zMax;
    }

    private void load() {
        // Tolerant of a bad record, but never silent about it - a conversation
        // vanishing with no trace is worse than a noisy skip.
        for (Map.Entry<String, String> e : mStore.all(C_GROUPS).entrySet()) {
            try {
                Group g = groupFromJson(e.getValue());
                if (g == null || g.id == null || g.id.isEmpty()) {
                    System.err.println("[chat] skipping group record with no id: " + e.getKey());
                    continue;
                }
                mGroups.put(g.id, g);
            } catch (Exception ex) {
                System.err.println("[chat] bad group record " + e.getKey() + ": " + ex);
            }
        }
        for (Map.Entry<String, String> e : mStore.all(C_READ).entrySet()) {
            try {
                mLastRead.put(e.getKey(), Long.parseLong(e.getValue().trim()));
            } catch (Exception ex) {
                System.err.println("[chat] bad read mark " + e.getKey() + ": " + ex);
            }
        }
        for (Map.Entry<String, String> e : mStore.all(C_WALLET).entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                mWalletAddr.put(Keys.norm(e.getKey()), e.getValue());
            }
        }
        for (Map.Entry<String, String> e : mStore.all(C_MESSAGES).entrySet()) {
            try {
                Entry en = entryFromJson(e.getValue());
                if (en == null || en.id == null || en.id.isEmpty()) {
                    System.err.println("[chat] skipping message record with no id: " + e.getKey());
                    continue;
                }
                mMessages.put(en.id, en);
                mConvKeys.add(en.isGroup() ? en.groupId : en.peer);
            } catch (Exception ex) {
                System.err.println("[chat] bad message record " + e.getKey() + ": " + ex);
            }
        }
    }

    // ---- serialisation ----

    static String groupToJson(Group g) {
        return new Json.Writer()
                .put("id", g.id)
                .put("name", g.name)
                .put("members", String.join(",", g.members()))
                .put("admins", g.adminsCsv())
                .put("last", Long.toString(g.lastActivity))
                .done();
    }

    static Group groupFromJson(String zJson) {
        Map<String, String> m = Json.parse(zJson);
        Group g = new Group(m.get("id"));
        g.name = m.getOrDefault("name", "Group");
        g.setMembers(Group.parseCsv(m.getOrDefault("members", "")));
        g.setAdmins(Group.parseCsv(m.getOrDefault("admins", "")));
        // An admin must always also be a member, even if a stored roster drifted.
        for (String a : Group.parseCsv(m.getOrDefault("admins", ""))) {
            g.addMember(a);
        }
        try {
            g.lastActivity = Long.parseLong(m.getOrDefault("last", "0"));
        } catch (NumberFormatException ignored) {
        }
        return g;
    }

    static String entryToJson(Entry e) {
        return new Json.Writer()
                .put("id", e.id)
                .put("peer", e.peer)
                .put("gid", e.groupId)
                .put("sender", e.sender)
                .put("body", e.body)
                .put("time", Long.toString(e.time))
                .put("arrived", Long.toString(e.arrived))
                .put("mine", Boolean.toString(e.mine))
                .put("state", e.state)
                .put("delivered", String.join(",", e.deliveredBy()))
                .done();
    }

    static Entry entryFromJson(String zJson) {
        Map<String, String> m = Json.parse(zJson);
        Entry e = new Entry(
                m.get("id"),
                m.getOrDefault("peer", ""),
                m.getOrDefault("gid", ""),
                m.getOrDefault("sender", ""),
                m.getOrDefault("body", ""),
                parseLong(m.get("time")),
                Boolean.parseBoolean(m.getOrDefault("mine", "false")),
                m.getOrDefault("state", Receipt.SENT));
        try {
            e.arrived = Long.parseLong(m.getOrDefault("arrived", "0"));
        } catch (Exception ignored) {
        }
        for (String d : m.getOrDefault("delivered", "").split(",")) {
            if (!d.trim().isEmpty()) {
                e.deliveredByMut().add(d.trim());
            }
        }
        return e;
    }

    private static long parseLong(String v) {
        try {
            return Long.parseLong(v);
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    /** Immediate - a new message must not be lost. */
    private void persist(Entry e) {
        mStore.put(C_MESSAGES, e.id, entryToJson(e));
    }

    /** Deferred - state changes are cheap to lose and expensive to write. */
    private void persistLater(Entry e) {
        mDirty.add(e.id);
    }

    /**
     * Write out pending state changes in one pass. Drive from a heartbeat and
     * call before shutdown.
     *
     * @return how many were written
     */
    public int flushState() {
        awaitLoaded();
        List<String> ids;
        synchronized (mDirty) {
            if (mDirty.isEmpty()) {
                return 0;
            }
            ids = new ArrayList<>(mDirty);
            mDirty.clear();
        }
        int n = 0;
        for (String id : ids) {
            Entry e = mMessages.get(id);
            if (e != null) {
                mStore.put(C_MESSAGES, id, entryToJson(e));
                n++;
            }
        }
        mStore.flush();   // a write-behind store lands the batch now, not on its timer
        return n;
    }

    /** Flush and release the receipt pool. */
    public void close() {
        flushState();
        flushGroupReceipts();
        mReceiptFlusher.shutdown();
        mGroupPool.shutdown();
        mReceiptPool.shutdown();
    }

    private void persist(Group g) {
        mStore.put(C_GROUPS, g.id, groupToJson(g));
    }

    /** Drop the oldest messages in a conversation past the cap. */
    private synchronized void prune(String zConversation) {
        List<Entry> conv = conversation(zConversation);
        if (conv.size() <= mMaxPerConversation) {
            return;
        }
        // Prune by the LATER of send/arrival time so a spoofed far-future
        // ts can't make spam "newest" and evict genuine messages first.
        conv.sort((a, b) -> Long.compare(
                Math.max(a.time, a.arrived), Math.max(b.time, b.arrived)));
        int drop = conv.size() - mMaxPerConversation;
        for (int i = 0; i < drop; i++) {
            Entry old = conv.get(i);
            mMessages.remove(old.id);
            mStore.remove(C_MESSAGES, old.id);
            mSeenIds.add(old.id);   // remember it so a re-push isn't resurrected
        }
    }

    public void setListener(Listener zListener) {
        mListener = zListener;
    }

    public void setSendReadReceipts(boolean zSend) {
        mSendReadReceipts = zSend;
    }

    public List<Group> groups() {
        awaitLoaded();
        return new ArrayList<>(mGroups.values());
    }

    public Group group(String zId) {
        awaitLoaded();
        return mGroups.get(zId);
    }

    public List<Entry> conversation(String zPeerOrGroup) {
        awaitLoaded();
        List<Entry> out = new ArrayList<>();
        if (zPeerOrGroup == null || zPeerOrGroup.isEmpty()) {
            // Group entries carry peer == "", so an empty key would match every
            // group message across every group.
            return out;
        }
        for (Entry e : mMessages.values()) {
            if (zPeerOrGroup.equalsIgnoreCase(e.peer) || zPeerOrGroup.equals(e.groupId)) {
                out.add(e);
            }
        }
        return out;
    }

    public Entry message(String zId) {
        awaitLoaded();
        return mMessages.get(zId);
    }

    /** A snapshot of every stored message — a single pass for search/audit (read-only). */
    public java.util.Collection<Entry> allMessages() {
        awaitLoaded();
        return new ArrayList<>(mMessages.values());
    }

    /**
     * Forget a conversation's history on THIS device. Removes every message that
     * belongs to it (the same match {@link #conversation} uses) and its read
     * mark. It does not tell the other side, unsend anything, or dissolve a
     * group - the roster survives so the thread can simply start fresh. Returns
     * how many messages were removed.
     */
    public int clearConversation(String zPeerOrGroup) {
        awaitLoaded();
        if (zPeerOrGroup == null || zPeerOrGroup.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Entry e : new ArrayList<>(mMessages.values())) {
            if (zPeerOrGroup.equalsIgnoreCase(e.peer) || zPeerOrGroup.equals(e.groupId)) {
                mMessages.remove(e.id);
                mStore.remove(C_MESSAGES, e.id);
                n++;
            }
        }
        String k = key(zPeerOrGroup);
        mLastRead.remove(k);
        mStore.remove(C_READ, k);
        // Free the conversation-cap slot so clearing a chat actually makes room.
        mConvKeys.remove(zPeerOrGroup);
        return n;
    }

    /**
     * How many inbound messages in this conversation arrived after we last read
     * it. Counted from the message list rather than kept as a counter, so it
     * cannot drift out of step with what is actually on screen.
     */
    public int unread(String zPeerOrGroup) {
        awaitLoaded();
        long since = lastRead(zPeerOrGroup);
        int n = 0;
        for (Entry e : conversation(zPeerOrGroup)) {
            if (!e.mine && e.time > since) {
                n++;
            }
        }
        return n;
    }

    /** One pass. Polled by the main screen, so it must not scan per conversation. */
    public int totalUnread() {
        awaitLoaded();
        int n = 0;
        for (Entry e : mMessages.values()) {
            if (e.mine) {
                continue;
            }
            String k = e.isGroup() ? e.groupId : e.peer;
            if (k != null && !k.isEmpty() && e.time > lastRead(k)) {
                n++;
            }
        }
        return n;
    }

    /** One conversation, as the list screen needs it. */
    public static final class Summary {
        public final String conversation;
        public final String lastBody;
        public final String lastSender;
        public final boolean lastMine;
        public final long lastTime;
        public final int unread;

        Summary(String zConv, String zBody, String zSender, boolean zMine,
                long zTime, int zUnread) {
            conversation = zConv;
            lastBody = zBody;
            lastSender = zSender;
            lastMine = zMine;
            lastTime = zTime;
            unread = zUnread;
        }
    }

    /**
     * Every conversation with its newest line and unread count, newest first,
     * in ONE pass over the messages.
     *
     * The list screen used to call conversation() and unread() per conversation
     * and each of those scans everything, so drawing K conversations over M
     * messages cost O(K*M) - on the main thread, on every receipt. With a few
     * hundred messages and a burst of ticks that is a visible stall.
     */
    public List<Summary> summaries() {
        awaitLoaded();
        Map<String, Entry> newest = new LinkedHashMap<>();
        Map<String, Integer> unread = new LinkedHashMap<>();
        for (Entry e : mMessages.values()) {
            String k = e.isGroup() ? e.groupId : e.peer;
            if (k == null || k.isEmpty()) {
                continue;
            }
            Entry cur = newest.get(k);
            if (cur == null || e.time > cur.time) {
                newest.put(k, e);
            }
            if (!e.mine && e.time > lastRead(k)) {
                unread.merge(k, 1, Integer::sum);
            }
        }
        List<Summary> out = new ArrayList<>();
        for (Map.Entry<String, Entry> en : newest.entrySet()) {
            Entry e = en.getValue();
            out.add(new Summary(en.getKey(), e.body, e.sender, e.mine, e.time,
                    unread.getOrDefault(en.getKey(), 0)));
        }
        out.sort((a, b) -> Long.compare(b.lastTime, a.lastTime));
        return out;
    }

    public long lastRead(String zPeerOrGroup) {
        awaitLoaded();
        Long v = mLastRead.get(key(zPeerOrGroup));
        return v == null ? 0L : v;
    }

    private static String key(String zPeerOrGroup) {
        return zPeerOrGroup == null ? "" : zPeerOrGroup;
    }

    // ---------------------------------------------------------------
    // sending
    // ---------------------------------------------------------------

    private static String newId() {
        return new MiniData(MaximaCrypto.randomBytes(12)).to0xString();
    }

    /** Send a 1:1 message. Returns the local entry immediately, state QUEUED. */
    /** The media layer, so a chat message can carry a self-hosted photo/video. */
    private volatile com.eurobuddha.maxima.core.media.MediaService mMedia;

    public void setMediaService(com.eurobuddha.maxima.core.media.MediaService zMedia) {
        mMedia = zMedia;
    }

    /**
     * Send a photo/video/audio: publish it to the self-hosted media mesh, then
     * send a normal chat message whose body carries the manifest ref. Blocking
     * on the publish (encrypt + replicate) — call off the main thread.
     */
    public Entry sendMedia(Contact zTo, byte[] zBytes, String zMime, String zCaption)
            throws Exception {
        if (mMedia == null) {
            throw new IllegalStateException("media service not set");
        }
        String body = ChatMedia.wrap(zMime, publishRef(zBytes, zMime), zCaption);
        return send(zTo, body);
    }

    public Entry sendGroupMedia(String zGroupId, byte[] zBytes, String zMime, String zCaption)
            throws Exception {
        if (mMedia == null) {
            throw new IllegalStateException("media service not set");
        }
        String body = ChatMedia.wrap(zMime, publishRef(zBytes, zMime), zCaption);
        return sendGroup(zGroupId, body);
    }

    /**
     * Turn a MaxSolo inline image (data-URL filedata) into one of OUR media
     * bodies by republishing the bytes locally. Null when it cannot (no media
     * service, malformed data-URL) - the caller falls back to a text marker.
     */
    private String ingestClassicImage(String zFiledata, String zCaption) {
        if (mMedia == null || zFiledata == null || !zFiledata.startsWith("data:")) {
            return null;
        }
        try {
            int comma = zFiledata.indexOf(',');
            if (comma < 6) {
                return null;
            }
            String meta = zFiledata.substring(5, comma);   // e.g. image/png;base64
            String mime = meta.split(";")[0].trim();
            if (mime.isEmpty()) {
                mime = "image/jpeg";
            }
            byte[] bytes = java.util.Base64.getMimeDecoder()
                    .decode(zFiledata.substring(comma + 1).trim());
            if (bytes.length == 0) {
                return null;
            }
            return ChatMedia.wrap(mime, publishRef(bytes, mime), zCaption);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build the MaxSolo inline-image wire for one of OUR media bodies, or null
     * when it cannot bridge (no media service, blob missing, or over the
     * {@link ClassicChat#MAX_INLINE_IMAGE_BYTES} inline cap).
     */
    private String classicImageWire(String zMediaBody) {
        if (mMedia == null) {
            return null;
        }
        try {
            String ref = ChatMedia.ref(zMediaBody);
            if (ref != null && ref.startsWith("data:")) {
                // Already embedded - pass straight through if it fits the wire.
                int comma = ref.indexOf(',');
                if (comma < 0 || (ref.length() - comma) * 3L / 4
                        > ClassicChat.MAX_INLINE_IMAGE_BYTES) {
                    return null;
                }
                return ClassicChat.buildImage(mNode.name(),
                        ChatMedia.caption(zMediaBody), ref);
            }
            if (ref == null || !ref.startsWith("mx1:")) {
                return null;
            }
            String json = new String(java.util.Base64.getUrlDecoder()
                    .decode(ref.substring(4)), StandardCharsets.UTF_8);
            com.eurobuddha.maxima.core.media.MediaManifest mf =
                    com.eurobuddha.maxima.core.media.MediaManifest.decode(json);
            byte[] plain = mMedia.fetch(mf);
            if (plain == null || plain.length == 0
                    || plain.length > ClassicChat.MAX_INLINE_IMAGE_BYTES) {
                return null;
            }
            String mime = ChatMedia.mime(zMediaBody);
            if (mime == null || mime.isEmpty()) {
                mime = "image/jpeg";
            }
            String dataUrl = "data:" + mime + ";base64,"
                    + java.util.Base64.getEncoder().encodeToString(plain);
            return ClassicChat.buildImage(mNode.name(),
                    ChatMedia.caption(zMediaBody), dataUrl);
        } catch (Exception e) {
            return null;
        }
    }

    /** Publish bytes and return an mx1: ref (RFC4648 url-safe base64 manifest,
     *  matching android.util.Base64 URL_SAFE that the app UI decodes with). */
    private String publishRef(byte[] zBytes, String zMime) throws Exception {
        // Local-only media (the jar engine): there is no shelf a receiver could
        // fetch from, so the image itself RIDES IN the message as a data-URL.
        // Receivers decode it directly; nothing to fetch, nothing to ghost.
        if (mMedia.isLocalOnly()) {
            return "data:" + zMime + ";base64,"
                    + java.util.Base64.getEncoder().encodeToString(zBytes);
        }
        com.eurobuddha.maxima.core.media.MediaManifest mf = mMedia.publish(zBytes, zMime);
        return "mx1:" + java.util.Base64.getUrlEncoder()
                .encodeToString(mf.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public Entry send(Contact zTo, String zBody) {
        awaitLoaded();
        String id = newId();
        Entry e = new Entry(id, Keys.norm(zTo.publicKey), "",
                Keys.norm(mNode.publicKeyHex()), zBody,
                System.currentTimeMillis(), true, Receipt.QUEUED);
        record(e);

        ChatMessage cm = ChatMessage.text(id, zBody, e.time);
        boolean ok = deliver(zTo, cm);
        setState(e, ok ? Receipt.SENT : Receipt.FAILED);
        return e;
    }

    /** Re-deliver our own messages that a delivery receipt has not confirmed.
     *
     * Chat delivery is otherwise fire-and-forget: a message sent into a
     * half-open relay socket is silently lost, and a payment (reflected only
     * after its tx mines) lands when the socket is coldest. The two-tick receipt
     * is the ONLY proof of delivery, so anything still short of DELIVERED after
     * a settle window is re-sent on the heartbeat until the receipt arrives.
     * The receiver dedups by id, so a resend is harmless.
     *
     * Bounded: only messages older than {@code RESEND_SETTLE_MS} (so we do not
     * race the first attempt) and younger than {@code RESEND_MAX_AGE_MS} (so we
     * do not resend history forever) are retried. Returns how many were re-sent.
     */
    public int resendUndelivered() {
        awaitLoaded();
        long now = System.currentTimeMillis();
        int n = 0;
        int tried = 0;
        for (Entry e : new ArrayList<>(mMessages.values())) {
            if (!e.mine) {
                continue;
            }
            if (Receipt.DELIVERED.equals(e.state) || Receipt.READ.equals(e.state)) {
                continue;
            }
            long age = now - e.time;
            if (age < RESEND_SETTLE_MS || age > RESEND_MAX_AGE_MS) {
                continue;
            }
            // A classic peer never sends receipts, so "undelivered" is this
            // message's PERMANENT state - resending would spam them every 60s
            // forever. One tick (handed to the network) is the honest final
            // word for a classic contact.
            if (!e.isGroup()) {
                Contact cc = mNode.contact(e.peer);
                if (cc != null && cc.isClassic()) {
                    continue;
                }
            }
            tried++;
            if (redeliver(e)) {
                n++;
                if (Receipt.FAILED.equals(e.state)) {
                    setState(e, Receipt.SENT);   // it went somewhere this time
                }
            }
        }
        // ALWAYS report when something needed retrying. "0/4 delivered" every
        // beat is the outbound-path-dead alarm that used to be pure silence.
        if (tried > 0) {
            mNode.log("resend: " + n + "/" + tried + " delivered");
        }
        return n;
    }

    private static final long RESEND_SETTLE_MS = 45_000L;
    private static final long RESEND_MAX_AGE_MS = 24L * 60 * 60 * 1000;

    /** Rebuild an entry's wire message and send it again, to the members who
     *  have not receipted it (group) or the peer (1:1). */
    private boolean redeliver(Entry e) {
        if (e.isGroup()) {
            Group g = mGroups.get(e.groupId);
            if (g == null) {
                return false;
            }
            ChatMessage cm = ChatMessage.groupText(e.id, e.groupId, e.body, e.time);
            long now = System.currentTimeMillis();
            java.util.List<String> due = new ArrayList<>();
            for (String m : g.others(mNode.publicKeyHex())) {
                String k = Keys.norm(m);
                if (e.deliveredBy().contains(k)) {
                    continue;   // this member already confirmed
                }
                Contact c = mNode.contact(m);
                if (c == null) {
                    continue;
                }
                // A classic member never receipts, so it would be re-sent to
                // forever - one delivery is the honest final word (mirrors the
                // 1:1 classic guard in resendUndelivered). Track that in a
                // SEPARATE set: deliveredBy must hold only real confirmations,
                // or a reader (e.g. desktop ticks) shows a false delivered.
                if (c.isClassic() && e.classicSent().contains(k)) {
                    continue;
                }
                // Bounded: GROUP_RESEND_MAX attempts per member with backoff, then that
                // member is undelivered (grey tick) and costs nothing more - one dead member
                // must not keep the whole group's full body in the ladder for 24 h.
                int attempts = e.resendAttempts().getOrDefault(k, 0);
                if (attempts >= GROUP_RESEND_MAX) {
                    if (e.undeliveredByMut().add(k)) {
                        fireState(e);
                    }
                    continue;
                }
                if (now < e.resendNotBefore().getOrDefault(k, 0L)) {
                    continue;
                }
                e.resendAttemptsMut().put(k, attempts + 1);
                e.resendNotBeforeMut().put(k, now + GROUP_RESEND_BACKOFF_MS[Math.min(attempts, GROUP_RESEND_BACKOFF_MS.length - 1)]);
                due.add(m);
            }
            if (due.isEmpty()) {
                return false;
            }
            int n = fanOutGroup(due, cm, ok -> {
                Contact c = mNode.contact(ok);
                if (c != null && c.isClassic()) {
                    e.classicSentMut().add(Keys.norm(ok));
                }
            });
            return n > 0;
        }
        Contact c = mNode.contact(e.peer);
        if (c == null) {
            return false;
        }
        // Media rides inside a text body, so text() covers text and media; a
        // payment keeps its own type. Same id → the receiver dedups.
        ChatMessage cm = ChatPay.isPayment(e.body)
                ? ChatMessage.payment(e.id, ChatPay.amount(e.body), "",
                        ChatPay.tokenName(e.body), ChatPay.memo(e.body), ChatPay.txid(e.body), e.time)
                : ChatMessage.text(e.id, e.body, e.time);
        return deliver(c, cm);
    }

    /** The wallet receive address a contact shared, or "" if we have none. */
    public String walletAddress(String zPeer) {
        awaitLoaded();
        String a = mWalletAddr.getOrDefault(Keys.norm(zPeer), "");
        if (!a.isEmpty()) {
            return a;
        }
        // A CLASSIC contact never sends our TYPE_ADDRESS record - but classic's
        // own contact handshake carries their wallet address (minimaaddress),
        // which the contact store already holds. "Mx00" is classic's own
        // none-placeholder.
        Contact c = mNode.contact(zPeer);
        if (c != null && c.isClassic()
                && c.minimaAddress != null && !c.minimaAddress.isEmpty()
                && !"Mx00".equals(c.minimaAddress)) {
            return c.minimaAddress;
        }
        return "";
    }

    /** Tell a contact our wallet receive address so they can pay us. Silent -
     *  no message, no receipt; just deliver the control record. */
    public void shareWalletAddress(Contact zTo, String zAddress) {
        if (zTo == null || zAddress == null || zAddress.isEmpty()) {
            return;
        }
        deliver(zTo, ChatMessage.address(zAddress));
    }

    /**
     * Record an OUTGOING payment locally the instant it is signed (txid known),
     * as a pending bubble - so the sender sees it immediately instead of waiting
     * for the ~3-min broadcast round-trip. It is NOT sent to the peer yet: call
     * {@link #completePayment} once the broadcast is accepted, or
     * {@link #failPayment} if the broadcast fails (so the peer is never told of a
     * payment that did not go out).
     */
    public Entry beginPayment(Contact zTo, String zAmount, String zTokenName,
                              String zMemo, String zTxid) {
        String id = newId();
        String body = ChatPay.wrap(zAmount, zTokenName, zTxid, zMemo);
        Entry e = new Entry(id, Keys.norm(zTo.publicKey), "",
                Keys.norm(mNode.publicKeyHex()), body,
                System.currentTimeMillis(), true, Receipt.QUEUED);
        record(e);
        return e;
    }

    /** The broadcast was accepted: tell the peer, so the payment bubble appears
     *  on their side too, and let the receipt/resend machinery confirm it. */
    public boolean completePayment(Contact zTo, Entry zEntry) {
        ChatMessage cm = ChatMessage.payment(zEntry.id, ChatPay.amount(zEntry.body), "",
                ChatPay.tokenName(zEntry.body), ChatPay.memo(zEntry.body),
                ChatPay.txid(zEntry.body), zEntry.time);
        boolean ok = deliver(zTo, cm);
        setState(zEntry, ok ? Receipt.SENT : Receipt.FAILED);
        return ok;
    }

    /** The broadcast failed: mark the local bubble failed; the peer was never
     *  told, so nothing to retract. */
    public void failPayment(Entry zEntry) {
        setState(zEntry, Receipt.FAILED);
    }

    /**
     * Record a payment WE made to a contact and tell them about it in one step.
     * Kept for callers that already know the broadcast succeeded; the compose
     * flow uses {@link #beginPayment}/{@link #completePayment} for instant local
     * feedback.
     */
    public Entry sendPayment(Contact zTo, String zAmount, String zTokenId,
                             String zTokenName, String zMemo, String zTxid) {
        String id = newId();
        String body = ChatPay.wrap(zAmount, zTokenName, zTxid, zMemo);
        Entry e = new Entry(id, Keys.norm(zTo.publicKey), "",
                Keys.norm(mNode.publicKeyHex()), body,
                System.currentTimeMillis(), true, Receipt.QUEUED);
        record(e);
        ChatMessage cm = ChatMessage.payment(id, zAmount, zTokenId, zTokenName, zMemo, zTxid, e.time);
        boolean ok = deliver(zTo, cm);
        setState(e, ok ? Receipt.SENT : Receipt.FAILED);
        return e;
    }

    /**
     * Send to a group by fanning out a separate sealed copy to every member.
     *
     * No shared group key, so removing someone actually removes them - there is
     * no key they still hold.
     */
    public Entry sendGroup(String zGroupId, String zBody) {
        awaitLoaded();
        Group g = mGroups.get(zGroupId);
        if (g == null) {
            throw new IllegalArgumentException("unknown group " + zGroupId);
        }
        String me = Keys.norm(mNode.publicKeyHex());
        String id = newId();
        Entry e = new Entry(id, "", zGroupId, me, zBody,
                System.currentTimeMillis(), true, Receipt.QUEUED);
        record(e);

        ChatMessage cm = ChatMessage.groupText(id, zGroupId, zBody, e.time);
        int sent = fanOutGroup(g.others(me), cm, null);
        setState(e, sent > 0 ? Receipt.SENT : Receipt.FAILED);
        return e;
    }

    /**
     * Deliver one message to a set of members in parallel (bounded pool), first accepting relay
     * per member. Returns how many members took it; {@code zOk}, if given, receives each member
     * that did. Waits for every attempt (a member on a dead relay costs one leash, overlapped).
     */
    private int fanOutGroup(java.util.List<String> zMembers, ChatMessage zMsg,
                            java.util.function.Consumer<String> zOk) {
        final java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(zMembers.size());
        for (final String member : zMembers) {
            final Contact c = mNode.contact(member);
            if (c == null) {
                done.countDown();
                continue;
            }
            Runnable one = () -> {
                try {
                    if (deliver(c, zMsg, true)) {
                        sent.incrementAndGet();
                        if (zOk != null) zOk.accept(member);
                    }
                } finally {
                    done.countDown();
                }
            };
            try {
                mGroupPool.execute(one);
            } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
                one.run();
            }
        }
        try {
            done.await(3, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return sent.get();
    }

    /** Create a group and push the roster to every member. */
    public Group createGroup(String zName, List<String> zMemberKeys) {
        awaitLoaded();
        String me = mNode.publicKeyHex();
        Group g = new Group(newId());
        g.name = zName;
        g.addAdmin(me);
        for (String k : zMemberKeys) {
            g.addMember(k);
        }
        if (g.size() > Group.MAX_MEMBERS) {
            throw new IllegalArgumentException("a group holds at most " + Group.MAX_MEMBERS
                    + " members (" + g.size() + " asked)");
        }
        mGroups.put(g.id, g);
        persist(g);
        pushRoster(g);
        fireGroup(g);
        return g;
    }

    /** Change membership. Only an admin may, and only an admin is obeyed. */
    public void updateGroup(Group zGroup) {
        awaitLoaded();
        String me = mNode.publicKeyHex();
        if (!zGroup.isAdmin(me)) {
            throw new IllegalStateException("only an admin can change the roster");
        }
        if (zGroup.size() > Group.MAX_MEMBERS) {
            throw new IllegalArgumentException("a group holds at most " + Group.MAX_MEMBERS
                    + " members (" + zGroup.size() + " asked)");
        }
        mGroups.put(zGroup.id, zGroup);
        persist(zGroup);
        pushRoster(zGroup);
        fireGroup(zGroup);
    }

    private void pushRoster(Group zGroup) {
        String me = mNode.publicKeyHex();
        ChatMessage roster = ChatMessage.roster(zGroup.id, zGroup.name,
                new ArrayList<>(zGroup.members()), zGroup.adminsCsv());
        // Pre-flight: a roster carries every member key; past the wire ceiling it could never
        // be sent and used to fail silently. Trivially true under MAX_MEMBERS - kept as the guard.
        int bytes = roster.encode().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > com.eurobuddha.maxima.core.rpc.ServiceRegistry.MAX_REPLY_BYTES) {
            mNode.log("roster for group " + zGroup.id + " NOT sent: " + bytes
                    + " bytes exceeds the wire ceiling (" + zGroup.size() + " members)");
            return;
        }
        int n = fanOutGroup(zGroup.others(me), roster, null);
        if (n < zGroup.others(me).size()) {
            mNode.log("roster for group " + zGroup.id + ": " + n + "/" + zGroup.others(me).size()
                    + " members reached (the rest get it on the next resend)");
        }
    }

    private boolean deliver(Contact zTo, ChatMessage zMsg) {
        return deliver(zTo, zMsg, false);
    }

    /** {@code zGroup}: first accepting relay wins (see MaximaNode.sendToContact); 1:1 keeps
     *  the every-mailbox redundancy. */
    private boolean deliver(Contact zTo, ChatMessage zMsg, boolean zGroup) {
        try {
            // THE BRIDGE: a classic peer (MaxSolo on a stock node) speaks the
            // maxsolo wire, not ours. Only human-visible content crosses -
            // receipts/roster/address are our protocol alone and would be
            // gibberish there, so they no-op successfully.
            if (zTo.isClassic()) {
                if (zMsg.type != ChatMessage.TYPE_TEXT
                        && zMsg.type != ChatMessage.TYPE_PAYMENT) {
                    return true;
                }
                String body;
                if (zMsg.type == ChatMessage.TYPE_PAYMENT) {
                    body = "Payment: " + zMsg.amount + " " + zMsg.tokenName
                            + (zMsg.memo.isEmpty() ? "" : " - " + zMsg.memo)
                            + " (txid " + zMsg.txid + ")";
                } else if (ChatMedia.isMedia(zMsg.body)) {
                    // Try to send the photo INLINE, MaxSolo-style (type:"image",
                    // base64 data-URL) - classic <-> classic can do images, so
                    // core <-> classic must too. Oversize falls back to caption.
                    String wire = classicImageWire(zMsg.body);
                    if (wire != null) {
                        MaximaSender.Result ir = mNode.sendToContact(zTo,
                                ClassicChat.APPLICATION,
                                wire.getBytes(StandardCharsets.UTF_8));
                        return ir.isOk();
                    }
                    String cap = ChatMedia.caption(zMsg.body);
                    body = cap.isEmpty() ? "[photo]" : cap + " [photo]";
                } else {
                    body = zMsg.body;
                }
                MaximaSender.Result cr = mNode.sendToContact(zTo, ClassicChat.APPLICATION,
                        ClassicChat.build(mNode.name(), body).getBytes(StandardCharsets.UTF_8));
                return cr.isOk();
            }
            MaximaSender.Result r = mNode.sendToContact(zTo, ChatMessage.APPLICATION,
                    zMsg.encode().getBytes(StandardCharsets.UTF_8), !zGroup);
            return r.isOk();
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------
    // receiving
    // ---------------------------------------------------------------

    /**
     * Feed every inbound chat message here. The transport has already verified
     * the signature, so `from` is authentic - what remains is AUTHORISATION.
     *
     * @return true if it was ours
     */
    /** Call signaling tap - offer/answer/ice/bye arrive here, nowhere else. */
    public interface CallSignals {
        void onCallSignal(String zFromKey, ChatMessage zMsg);
    }

    private volatile CallSignals mCallSignals;

    public void setCallListener(CallSignals zListener) {
        mCallSignals = zListener;
    }

    /**
     * Fire-and-forget call signal - no resend ladder: stale signaling is worse
     * than lost signaling (the caller keeps ringing / retries instead).
     */
    public void sendCallSignal(Contact zTo, ChatMessage zSignal) throws Exception {
        mNode.sendToContact(zTo, ChatMessage.APPLICATION,
                zSignal.encode().getBytes(StandardCharsets.UTF_8));
    }

    public boolean onInbound(MaximaMessage zMsg) {
        return onInbound(zMsg, "");
    }

    /**
     * As {@link #onInbound(MaximaMessage)} but with the transport message id,
     * which gives CLASSIC (MaxSolo-format) messages a stable dedup id - vital
     * because classic relays re-push held mail on every re-attach.
     */
    public boolean onInbound(MaximaMessage zMsg, String zMsgid) {
        awaitLoaded();
        String app = zMsg.mApplication.toString();
        if (ClassicChat.APPLICATION.equals(app)) {
            // NB: receiving a classic-wire message is NOT proof the peer is on
            // the classic engine - a FULL peer sends images over this wire too
            // (see deliver(): images are inlined MaxSolo-style for everyone). So
            // do not touch capabilities here.
            handleClassic(zMsg, zMsgid);
            return true;
        }
        if (!ChatMessage.APPLICATION.equals(app)) {
            return false;
        }
        String from = Keys.norm(zMsg.mFrom.to0xString());
        // Speaking maxima_chat_v1 IS the capability proof - learn it even when
        // the handshake couldn't carry flags (classic contact-ctrl drops them).
        try {
            mNode.noteCapable(from);
        } catch (Exception ignored) {
        }
        ChatMessage cm;
        try {
            cm = ChatMessage.decode(
                    new String(zMsg.mData.getBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return true;
        }

        switch (cm.type) {
            case ChatMessage.TYPE_CALL:
                // Signaling only: no history entry, no receipt, no notification.
                CallSignals cs = mCallSignals;
                if (cs != null) {
                    try {
                        cs.onCallSignal(from, cm);
                    } catch (Exception ignored) {
                    }
                }
                return true;
            case ChatMessage.TYPE_TEXT:
                handleText(from, cm);
                return true;
            case ChatMessage.TYPE_GROUP_TEXT:
                handleGroupText(from, cm);
                return true;
            case ChatMessage.TYPE_ROSTER:
                handleRoster(from, cm);
                return true;
            case ChatMessage.TYPE_RECEIPT:
                handleReceipt(from, cm);
                return true;
            case ChatMessage.TYPE_ADDRESS:
                handleAddress(from, cm);
                return true;
            case ChatMessage.TYPE_PAYMENT:
                handlePayment(from, cm);
                return true;
            default:
                return true;
        }
    }

    /** A contact told us their wallet receive address. Store it silently - it
     *  is not a message, it is how we know where to pay them. */
    private void handleAddress(String zFrom, ChatMessage zMsg) {
        if (zMsg.address == null || zMsg.address.isEmpty()) {
            return;
        }
        String k = Keys.norm(zFrom);
        if (!zMsg.address.equals(mWalletAddr.get(k))) {
            mWalletAddr.put(k, zMsg.address);
            mStore.put(C_WALLET, k, zMsg.address);
        }
    }

    /** A contact paid us. Show it in-thread as a payment bubble (carried in the
     *  body exactly like media), and receipt it like any message. */
    private void handlePayment(String zFrom, ChatMessage zMsg) {
        String body = ChatPay.wrap(zMsg.amount, zMsg.tokenName, zMsg.txid, zMsg.memo);
        Entry e = new Entry(zMsg.id, zFrom, "", zFrom, body,
                inboundTime(zMsg), false, Receipt.DELIVERED);
        e.arrived = System.currentTimeMillis();
        if (record(e)) {
            fire(e);
            sendReceipt(zFrom, zMsg.id, Receipt.DELIVERED);
        } else {
            sendReceipt(zFrom, zMsg.id, Receipt.DELIVERED);   // re-ack (see handleText)
        }
    }

    /**
     * The time to stamp an INBOUND message with: the sender's send-time if it
     * carried one (0.5.15+), else our arrival time (older sender). Sorting by
     * this is what drops a late-arriving message back into its true place in the
     * thread instead of the bottom.
     */
    private static long inboundTime(ChatMessage zMsg) {
        long now = System.currentTimeMillis();
        // A sender-controlled time must never exceed now (+ small skew): an
        // unclamped future ts poisons the read-watermark (nothing ever unread
        // again) and prune ordering (real history evicted first).
        if (zMsg.time > 0 && zMsg.time <= now + 300_000L) {
            return zMsg.time;
        }
        return now;
    }

    /**
     * A CLASSIC (MaxSolo-format) chat message from a stock-node peer. Recorded
     * exactly like one of ours: thread keyed by the sender's identity key, time
     * from the transport's send-stamp, dedup by the transport msgid. No receipt
     * goes back - classic has no receipt protocol, so the sender's single tick
     * is all the truth there is.
     */
    private void handleClassic(MaximaMessage zMsg, String zMsgid) {
        String from = Keys.norm(zMsg.mFrom.to0xString());
        java.util.Map<String, String> m;
        try {
            m = ClassicChat.parse(new String(zMsg.mData.getBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        String type = m.getOrDefault("type", "text");
        String body = m.getOrDefault("message", "");
        if ("image".equals(type)) {
            // A MaxSolo inline image: decode the data-URL, republish the bytes
            // into OUR blob store, and the body becomes a standard media body -
            // the photo then renders natively like any Parlons photo.
            String filedata = m.getOrDefault("filedata", "");
            String mediaBody = ingestClassicImage(filedata, body);
            if (mediaBody != null) {
                body = mediaBody;
            } else {
                body = body.isEmpty() ? "[image]" : body + " [image]";
            }
        } else if (body.isEmpty()) {
            if ("text".equals(type)) {
                return;
            }
            body = "[" + type + "]";   // a file type we cannot bridge
        }
        long time = zMsg.mTimeMilli.getAsLong();
        String id = (zMsgid == null || zMsgid.isEmpty())
                // Include a content hash so two distinct messages from the same
                // sender in the same millisecond don't collide to one id (the
                // second was silently dropped by record()).
                ? "classic-" + from.hashCode() + "-" + time + "-"
                        + Integer.toHexString(body.hashCode())
                : zMsgid;
        Entry e = new Entry(id, from, "", from, body,
                time > 0 ? time : System.currentTimeMillis(), false, Receipt.DELIVERED);
        e.arrived = System.currentTimeMillis();
        if (record(e)) {
            fire(e);
        }
    }

    /** A TEXT body that WOULD parse as media/payment was sent as plain text -
     *  neutralize the marker so it can't be reinterpreted (in-band forgery).
     *  Narrow: only bodies isMedia/isPayment would match, so a legit message
     *  starting with a newline/tab is untouched. */
    // Package-private (not private) so ChatMediaTest can pin the exact property
    // the 0.6.16 regression violated: a media body must survive deMarker as media.
    static String deMarker(String zBody) {
        // ONLY payment markers are a forgery vector: a real payment arrives as
        // TYPE_PAYMENT (never as text), so a payment marker inside a TYPE_TEXT
        // body is fabricated. MEDIA legitimately rides text bodies (sendMedia
        // -> ChatMessage.text with a media-marker body), so it must NOT be
        // neutralized - doing so (0.6.16) rendered every inbound photo/voice
        // note as a wall of base64 text.
        if (zBody != null && ChatPay.isPayment(zBody) && !ChatMedia.isMedia(zBody)) {
            return "\uFFFD" + zBody;
        }
        return zBody;
    }

    private void handleText(String zFrom, ChatMessage zMsg) {
        zMsg.body = deMarker(zMsg.body);
        Entry e = new Entry(zMsg.id, zFrom, "", zFrom, zMsg.body,
                inboundTime(zMsg), false, Receipt.DELIVERED);
        e.arrived = System.currentTimeMillis();
        if (record(e)) {
            fire(e);
            sendReceipt(zFrom, zMsg.id, Receipt.DELIVERED);
        } else {
            // A duplicate IS proof the sender never got our receipt (they only
            // resend while un-receipted) - re-ack, or the red-cross/resend loop
            // never ends. This turns a lost receipt into a self-healing one.
            sendReceipt(zFrom, zMsg.id, Receipt.DELIVERED);
        }
    }

    private void handleGroupText(String zFrom, ChatMessage zMsg) {
        zMsg.body = deMarker(zMsg.body);
        Group g = mGroups.get(zMsg.groupId);
        if (g == null) {
            // A group we know nothing about. Ignore rather than auto-join:
            // otherwise anyone could drop you into a group unasked.
            return;
        }
        // FreezePeach's rule - only current members may post.
        if (!g.isMember(zFrom)) {
            return;
        }
        Entry e = new Entry(zMsg.id, "", zMsg.groupId, zFrom, zMsg.body,
                inboundTime(zMsg), false, Receipt.DELIVERED);
        e.arrived = System.currentTimeMillis();
        if (record(e)) {
            fire(e);
        }
        // Coalesced: one receipt per sender per window, covering this and everything older
        // (a duplicate re-queues it, so a lost receipt still self-heals - see handleText).
        queueGroupReceipt(zFrom, e);
    }

    /** Remember the newest group message received from a sender; flush ONE upto-receipt per
     *  sender after the window. N messages in a burst = one receipt, not N full sends. */
    private void queueGroupReceipt(String zFrom, Entry zNewest) {
        String k = Keys.norm(zFrom);
        mPendingGroupReceipts.merge(k, zNewest, (old, cur) -> cur.time >= old.time ? cur : old);
        if (mReceiptFlushArmed.compareAndSet(false, true)) {
            try {
                mReceiptFlusher.schedule(this::flushGroupReceipts, GROUP_RECEIPT_WINDOW_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
                mReceiptFlushArmed.set(false);
                flushGroupReceipts();
            }
        }
    }

    private void flushGroupReceipts() {
        mReceiptFlushArmed.set(false);
        java.util.List<java.util.Map.Entry<String, Entry>> batch =
                new ArrayList<>(mPendingGroupReceipts.entrySet());
        mPendingGroupReceipts.clear();
        for (java.util.Map.Entry<String, Entry> pe : batch) {
            Contact c = mNode.contact(pe.getKey());
            if (c == null) {
                continue;
            }
            final ChatMessage r = ChatMessage.receiptUpto(pe.getValue().id, Receipt.DELIVERED);
            try {
                mReceiptPool.submit(() -> deliver(c, r, true));
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // shutting down; a missed receipt costs a tick, not a message
            }
        }
    }

    private void handleRoster(String zFrom, ChatMessage zMsg) {
        Group existing = mGroups.get(zMsg.groupId);

        if (existing == null) {
            if (mGroups.size() >= 500) {
                return;   // bound group count against roster spam
            }
            // First time we hear of it: the sender must name themselves as an
            // admin, which is what an invitation looks like.
            Group g = new Group(zMsg.groupId);
            g.name = zMsg.groupName;
            g.setAdmins(Group.parseCsv(zMsg.admin));
            g.setMembers(zMsg.members);
            // An admin is implicitly a member.
            for (String a : Group.parseCsv(zMsg.admin)) {
                g.addMember(a);
            }
            if (!g.isAdmin(zFrom)) {
                return;
            }
            if (g.size() > Group.MAX_MEMBERS) {
                mNode.log("roster for group " + g.id + " refused: " + g.size() + " members > "
                        + Group.MAX_MEMBERS + " (from " + zFrom + ")");
                return;
            }
            mGroups.put(g.id, g);
            persist(g);
            fireGroup(g);
            return;
        }

        // Otherwise only an EXISTING admin may rewrite it. Without this any
        // member could re-add themselves after being removed.
        if (!existing.isAdmin(zFrom)) {
            return;
        }
        Group edit = new Group(existing.id);
        edit.setMembers(zMsg.members);
        edit.setAdmins(Group.parseCsv(zMsg.admin));
        for (String a : Group.parseCsv(zMsg.admin)) {
            edit.addMember(a);
        }
        if (edit.size() > Group.MAX_MEMBERS) {
            mNode.log("roster update for group " + existing.id + " refused: " + edit.size()
                    + " members > " + Group.MAX_MEMBERS + " (from " + zFrom + ")");
            return;
        }
        existing.name = zMsg.groupName.isEmpty() ? existing.name : zMsg.groupName;
        existing.setMembers(edit.members());
        existing.setAdmins(edit.admins());
        persist(existing);
        fireGroup(existing);
    }

    private void handleReceipt(String zFrom, ChatMessage zMsg) {
        Entry e = mMessages.get(zMsg.ref);
        if (e == null || !e.mine) {
            return;
        }
        if (e.isGroup()) {
            Group g = mGroups.get(e.groupId);
            if (g == null || !g.isMember(zFrom)) {
                return;
            }
            e.deliveredByMut().add(Keys.norm(zFrom));
            e.undeliveredByMut().remove(Keys.norm(zFrom));
            if (zMsg.upto) {
                // Coalesced receipt: this member has everything of ours in this group up to
                // and including the referenced message (ids are random; ORDER is by time).
                for (Entry older : new ArrayList<>(mMessages.values())) {
                    if (older != e && older.mine && older.groupId.equals(e.groupId)
                            && older.time <= e.time
                            && older.deliveredByMut().add(Keys.norm(zFrom))) {
                        older.undeliveredByMut().remove(Keys.norm(zFrom));
                        settleGroupState(older, g);
                    }
                }
            }

            // Count only confirmations from members who are STILL in the group.
            // Comparing sizes alone let a stale confirmation from a removed
            // member stand in for a current one who never received it - which
            // would make the second tick a lie, and the second tick is only
            // worth having because it is not.
            // Classic (stock-node) members have no receipt protocol and can
            // never confirm - exclude them from the denominator, or a group
            // with any classic member never reaches DELIVERED and redeliver
            // re-sends the full body to everyone every beat for 24h.
            settleGroupState(e, g);
            return;
        }
        if (zFrom.equalsIgnoreCase(e.peer)) {
            // A recipient may assert DELIVERED/READ, never FAILED - FAILED is a
            // local send-outcome, and accepting it would let a peer flip our
            // delivered tick to a red cross and re-arm the resend ladder.
            if (!Receipt.FAILED.equals(zMsg.state)) {
                setState(e, zMsg.state);
            }
        }
    }

    /** DELIVERED once every CURRENT non-classic member has confirmed; else persist + notify. */
    private void settleGroupState(Entry e, Group g) {
        List<String> current = new java.util.ArrayList<>();
        for (String m : g.others(mNode.publicKeyHex())) {
            Contact mc = mNode.contact(m);
            if (mc == null || !mc.isClassic()) {
                current.add(m);
            }
        }
        int confirmed = 0;
        for (String m : current) {
            if (e.deliveredBy().contains(Keys.norm(m))) {
                confirmed++;
            }
        }
        if (!current.isEmpty() && confirmed >= current.size()) {
            setState(e, Receipt.DELIVERED);
        } else {
            persistLater(e);
            fireState(e);
        }
    }

    /** Tell the sender we got it. This is the second tick. */
    private void sendReceipt(String zTo, String zRef, String zState) {
        Contact c = mNode.contact(zTo);
        if (c == null) {
            return;
        }
        final ChatMessage r = ChatMessage.receipt(zRef, zState);
        // Never block inbound handling on an outbound socket, and never spawn
        // an unbounded number of threads to avoid doing so.
        try {
            mReceiptPool.submit(() -> deliver(c, r));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Shutting down; a missed receipt costs a tick, not a message.
        }
    }

    /**
     * Mark a conversation read, and tell them only if the user allows it.
     *
     * Sends ONE receipt for the newest inbound message per sender, not one per
     * message: read state is monotonic, so the latest implies the rest. The
     * previous per-message loop could fire hundreds of sends from a single tap.
     */
    public void markRead(String zPeerOrGroup) {
        awaitLoaded();
        // The local mark is set whether or not we TELL them. Read receipts are
        // a privacy choice about the other person; the unread badge is ours.
        //
        // The mark is the timestamp of the newest message we have actually
        // seen, NOT the wall clock. Using the clock loses a message that
        // arrives in the same millisecond as the tap - it is stamped at or
        // before the mark, so it is born already read and never notified. That
        // is not theoretical: opening a thread while a message is in flight is
        // exactly when it happens.
        String k = key(zPeerOrGroup);
        if (!k.isEmpty()) {
            long mark = mLastRead.getOrDefault(k, 0L);
            for (Entry e : conversation(k)) {
                if (!e.mine && e.time > mark) {
                    mark = e.time;
                }
            }
            mLastRead.put(k, mark);
            mStore.put(C_READ, k, Long.toString(mark));
        }
        if (!mSendReadReceipts) {
            return;
        }
        if (mGroups.containsKey(zPeerOrGroup)) {
            // No READ receipts in groups: one tap would fan a full send to every sender in the
            // thread (up to N-1 x their addresses). Read state stays local in groups.
            return;
        }
        Map<String, Entry> newestBySender = new LinkedHashMap<>();
        for (Entry e : conversation(zPeerOrGroup)) {
            if (e.mine) {
                continue;
            }
            Entry cur = newestBySender.get(e.sender);
            if (cur == null || e.time > cur.time) {
                newestBySender.put(e.sender, e);
            }
        }
        for (Entry e : newestBySender.values()) {
            sendReceipt(e.sender, e.id, Receipt.READ);
        }
    }

    // ---------------------------------------------------------------

    /** Bounded ring of message ids dropped by prune/clear, so a relay
     *  mailbox re-push of an old message is recognised as a duplicate rather
     *  than resurrected as a new bubble (or a duplicate money bubble). */
    private final java.util.Set<String> mSeenIds =
            java.util.Collections.newSetFromMap(
                new java.util.LinkedHashMap<String, Boolean>(2048, 0.75f, false) {
                    @Override
                    protected boolean removeEldestEntry(
                            java.util.Map.Entry<String, Boolean> e) {
                        return size() > 4000;
                    }
                });

    private static final int MAX_CONVERSATIONS = 1000;
    private final java.util.Set<String> mConvKeys =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private synchronized boolean record(Entry zEntry) {
        if (mMessages.containsKey(zEntry.id) || mSeenIds.contains(zEntry.id)) {
            // Duplicate delivery - retained, or pruned/cleared but remembered.
            return false;
        }
        // Bound the number of distinct conversations a peer can spin up: a
        // brand-new conversation beyond the cap (spam to a fresh key each time)
        // is dropped; conversations we already have always accept.
        String conv = zEntry.isGroup() ? zEntry.groupId : zEntry.peer;
        if (!mConvKeys.contains(conv) && mConvKeys.size() >= MAX_CONVERSATIONS) {
            return false;
        }
        mConvKeys.add(conv);
        mMessages.put(zEntry.id, zEntry);
        persist(zEntry);
        prune(zEntry.isGroup() ? zEntry.groupId : zEntry.peer);
        return true;
    }

    private void setState(Entry zEntry, String zState) {
        String merged = Receipt.merge(zEntry.state, zState);
        if (!merged.equals(zEntry.state)) {
            zEntry.state = merged;
            persistLater(zEntry);
            fireState(zEntry);
        }
    }

    private void fire(Entry e) {
        Listener l = mListener;
        if (l != null) {
            try {
                l.onMessage(e);
            } catch (Exception ignored) {
            }
        }
    }

    private void fireState(Entry e) {
        Listener l = mListener;
        if (l != null) {
            try {
                l.onStateChanged(e);
            } catch (Exception ignored) {
            }
        }
    }

    private void fireGroup(Group g) {
        Listener l = mListener;
        if (l != null) {
            try {
                l.onGroupChanged(g);
            } catch (Exception ignored) {
            }
        }
    }

    /** Restore a group directly, e.g. from a test. */
    public void loadGroup(Group zGroup) {
        mGroups.put(zGroup.id, zGroup);
        persist(zGroup);
    }

    /** Conversations with activity, newest first. */
    public synchronized List<String> conversations() {
        awaitLoaded();
        Map<String, Long> latest = new LinkedHashMap<>();
        for (Entry e : mMessages.values()) {
            String key = e.isGroup() ? e.groupId : e.peer;
            Long cur = latest.get(key);
            if (cur == null || e.time > cur) {
                latest.put(key, e.time);
            }
        }
        List<String> keys = new ArrayList<>(latest.keySet());
        keys.sort((a, b) -> Long.compare(latest.get(b), latest.get(a)));
        return keys;
    }
}
