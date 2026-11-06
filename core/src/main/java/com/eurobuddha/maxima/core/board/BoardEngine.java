package com.eurobuddha.maxima.core.board;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.codec.Codec;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.MaximaCrypto;
import com.eurobuddha.maxima.core.identity.Keys;
import com.eurobuddha.maxima.core.msg.CryptoPackage;
import com.eurobuddha.maxima.core.store.Store;
import com.eurobuddha.maxima.core.util.Json;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The member side of boards: the local copy of every board this identity is on, publishing to
 * and pulling from pool relays, and the membership rules the relays deliberately do not know.
 *
 * A board is a message board, not a chat: anyone on it posts, anyone replies, nothing is sent to
 * anybody. A post is ONE signed envelope published to a few relays ({@link BoardWire#put}); every
 * member pulls deltas on the host's heartbeat ({@link #tick}). That is what lifts the 12-member
 * group cap to thousands - the cost of a post no longer depends on how many read it.
 *
 * What travels inside a post's blob (flat JSON, {@link Json}), by {@code k}:
 * <pre>
 *   text     b=body                                  a post, or a reply when the envelope has re
 *   hello    n=name a=MAX# address                   "I am here" - names a pseudonym, lets members DM
 *   roster   n=board name a=admin DERs m=member fps  admin-signed membership (invite-only boards)
 *   rekey    e=epoch x=fp:sealed,fp:sealed,...       admin-signed next key, sealed per member
 *   knock    n=name a=MAX# address                   epoch 0, sealed to the admin: a join request
 *   welcome  e=epoch k=key hex n=board name          epoch 0, sealed to the knocker: the key
 * </pre>
 * Everything a relay holds is ciphertext signed by pseudonyms. Members keep full local copies and
 * re-seed a relay that lost posts, so no relay is ever the record.
 */
public final class BoardEngine {

    public interface Listener {
        void onBoardChanged(Board zBoard);
    }

    /** Local history per board; relays keep up to 20 000, members re-fetch on demand later. */
    public static final int MAX_LOCAL_POSTS = 5000;
    public static final int MAX_BOARDS = 200;
    /** Relays a post is published to, and a board remembers. */
    public static final int RELAYS_PER_BOARD = 3;
    /** Relays pulled on each tick. */
    public static final int READ_RELAYS = 2;
    /** Pages pulled per relay per tick before giving the rest to the next tick. */
    private static final int PAGES_PER_TICK = 4;
    /** Posts re-seeded onto a relay that reset us, per tick. */
    private static final int RESEED_PER_TICK = 50;
    /** Sealed keys per rekey envelope (~200 B each, under the 64K post). */
    private static final int REKEY_CHUNK_CHARS = 40_000;

    static final String C_BOARDS = "boards";
    private static final String LOG_PREFIX = "boardlog_";

    public static final class Board {
        public final String id;
        public final byte[] idBytes;
        public String name = "";
        public boolean open;
        public long created;
        public long lastActivity;
        /** Local read marker: ts of the newest post seen on screen. */
        public long lastRead;
        /** Admin pseudonyms, DER hex. The creator's is in every link. */
        public final List<String> admins = new ArrayList<>();
        public int epoch;
        public final Map<Integer, byte[]> keys = new HashMap<>();
        public final List<String> relays = new ArrayList<>();
        public final Map<String, Long> cursors = new HashMap<>();
        /** Our posts no relay has accepted yet. */
        public final Set<String> pending = new LinkedHashSet<>();
        /** Knocked on an invite-only board, no welcome yet. */
        public boolean joining;
        /** An admin's roster dropped us (invite-only): read-only until an admin lets us back in. */
        public boolean removed;
        /** Admin clock of the newest roster applied: an older roster arriving later is ignored. */
        public long rosterTs;
        /** When the current epoch began: older-epoch text after this is from someone put out. */
        public long rekeyTs;
        /** Newest readable text post (list previews, without a sort of the whole log). */
        public String lastText = "";

        // membership state: derived from control posts, PERSISTED (control posts are kept out of the
        // prune too, but a 2,000-member roster must not depend on replaying a log)
        final Set<String> roster = new LinkedHashSet<>();
        final Map<String, String> names = new HashMap<>();
        final Map<String, String> ders = new HashMap<>();
        final Map<String, String> cards = new HashMap<>();
        /** Members an admin here put out (so their old knock is not listed as a fresh request). */
        final Set<String> removedFps = new HashSet<>();
        /** Multi-part rosters in flight: rs -> part index -> members. */
        final Map<Long, Map<Integer, List<String>>> rosterParts = new HashMap<>();
        final List<Entry> entries = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        KeyPair pseudonym;
        String myHex = "";
        String myFp = "";

        Board(byte[] zId) {
            idBytes = zId;
            id = new MiniData(zId).to0xString();
        }

        public boolean iAmAdmin() {
            return admins.contains(myHex);
        }

        public boolean canPost() {
            return !joining && !removed && keys.containsKey(epoch);
        }

        public String myFingerprint() {
            return myFp;
        }

        public int memberCount() {
            Set<String> all = new HashSet<>(roster);
            all.addAll(names.keySet());
            for (String a : admins) {
                all.add(Keys.fingerprint(a));
            }
            return all.size();
        }

        public String nameOf(String zFp) {
            String n = names.get(zFp);
            return n == null || n.isEmpty() ? zFp : n;
        }

        public String cardOf(String zFp) {
            String c = cards.get(zFp);
            return c == null ? "" : c;
        }

        public Set<String> rosterView() {
            return Collections.unmodifiableSet(roster);
        }

        /** True when this signer may post text here: open boards take anyone who said hello. */
        boolean isMember(String zFp) {
            if (roster.contains(zFp)) {
                return true;
            }
            for (String a : admins) {
                if (Keys.fingerprint(a).equals(zFp)) {
                    return true;
                }
            }
            return open && names.containsKey(zFp);
        }
    }

    public static final class Entry {
        public final String id;
        public final String from;
        public final String re;
        public final long ts;
        public final int epoch;
        /** The kind, or "" when the blob could not be opened (no key for its epoch yet). */
        public final String kind;
        public final Map<String, String> fields;
        public final byte[] raw;
        public final boolean mine;

        Entry(BoardPost.Signed zS, String zKind, Map<String, String> zFields, boolean zMine) {
            id = zS.post.idHex();
            from = zS.signerFp;
            re = zS.post.isReply() ? zS.post.mRe.to0xString() : "";
            ts = zS.post.mTs.getAsLong();
            epoch = zS.post.mEpoch.getAsInt();
            kind = zKind;
            fields = zFields;
            raw = zS.raw;
            mine = zMine;
        }

        public String body() {
            String b = fields == null ? null : fields.get("b");
            return b == null ? "" : b;
        }

        public boolean isText() {
            return "text".equals(kind);
        }
    }

    /** A join request waiting for an admin. */
    public static final class Knock {
        public final String fp;
        public final String name;
        public final String address;
        public final long ts;

        Knock(String zFp, String zName, String zAddress, long zTs) {
            fp = zFp;
            name = zName;
            address = zAddress;
            ts = zTs;
        }
    }

    private final MaximaNode mNode;
    private final Map<String, Board> mBoards = new LinkedHashMap<>();
    private volatile Store mStore = Store.MEMORY_ONLY;
    private volatile Listener mListener;
    /** Network work (publish, pull) off the caller's thread, one lane. */
    private final ExecutorService mExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "parlons-boards");
        t.setDaemon(true);
        return t;
    });
    /** What our hello says; the host may override the name. */
    private volatile String mCardName = "";
    private volatile String mCardAddress = "";
    private volatile boolean mLoaded;

    public BoardEngine(MaximaNode zNode) {
        mNode = zNode;
    }

    public void setListener(Listener zListener) {
        mListener = zListener;
    }

    /** The name and MAX# address our hello carries (defaults: the node's own). */
    public void setCard(String zName, String zAddress) {
        mCardName = zName == null ? "" : zName;
        mCardAddress = zAddress == null ? "" : zAddress;
    }

    public synchronized void setStore(Store zStore) {
        mStore = zStore == null ? Store.MEMORY_ONLY : zStore;
        load();
    }

    public void flush() {
        mStore.flush();
    }

    // ---- persistence ----

    private void load() {
        mBoards.clear();
        for (Map.Entry<String, String> e : mStore.all(C_BOARDS).entrySet()) {
            try {
                Board b = fromJson(e.getKey(), e.getValue());
                derive(b);
                for (String line : mStore.read(logName(b))) {
                    try {
                        BoardPost.Signed s = BoardPost.verify(new MiniData(line.trim()).getBytes());
                        apply(b, s, false);
                    } catch (Exception bad) {
                        // a damaged line: skip it, the relays or other members still have it
                    }
                }
                mBoards.put(b.id, b);
            } catch (Exception ex) {
                System.err.println("[boards] bad record " + e.getKey() + ": " + ex);
            }
        }
        mLoaded = true;
    }

    private static String logName(Board zB) {
        return LOG_PREFIX + zB.id.substring(2).toLowerCase();
    }

    private String toJson(Board b) {
        StringBuilder keys = new StringBuilder();
        for (Map.Entry<Integer, byte[]> k : b.keys.entrySet()) {
            if (keys.length() > 0) {
                keys.append(',');
            }
            keys.append(k.getKey()).append(':').append(new MiniData(k.getValue()).to0xString());
        }
        StringBuilder cursors = new StringBuilder();
        for (Map.Entry<String, Long> c : b.cursors.entrySet()) {
            if (cursors.length() > 0) {
                cursors.append(',');
            }
            cursors.append(c.getKey()).append('=').append(c.getValue());
        }
        return new Json.Writer()
                .put("name", b.name)
                .put("open", b.open ? "1" : "0")
                .put("created", Long.toString(b.created))
                .put("lastActivity", Long.toString(b.lastActivity))
                .put("lastRead", Long.toString(b.lastRead))
                .put("admins", String.join(",", b.admins))
                .put("epoch", Integer.toString(b.epoch))
                .put("keys", keys.toString())
                .put("relays", String.join(",", b.relays))
                .put("cursors", cursors.toString())
                .put("pending", String.join(",", b.pending))
                .put("joining", b.joining ? "1" : "0")
                .put("removed", b.removed ? "1" : "0")
                .put("rosterTs", Long.toString(b.rosterTs))
                .put("rekeyTs", Long.toString(b.rekeyTs))
                .put("lastText", b.lastText)
                .put("roster", String.join(",", b.roster))
                .put("removedFps", String.join(",", b.removedFps))
                .put("names", pairs(b.names))
                .put("ders", pairs(b.ders))
                .put("cards", pairs(b.cards))
                .done();
    }

    /** fp=value pairs, values hex/URL-safe-encoded so a name with a comma or '=' round-trips. */
    private static String pairs(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(new MiniData(e.getValue().getBytes(StandardCharsets.UTF_8)).to0xString());
        }
        return sb.toString();
    }

    private static void unpairs(String s, Map<String, String> into) {
        for (String p : csv(s)) {
            int eq = p.indexOf('=');
            if (eq > 0) {
                try {
                    into.put(p.substring(0, eq), new String(new MiniData(p.substring(eq + 1)).getBytes(), StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static Board fromJson(String zId, String zJson) {
        Map<String, String> m = Json.parse(zJson);
        Board b = new Board(new MiniData(zId).getBytes());
        b.name = val(m, "name");
        b.open = "1".equals(val(m, "open"));
        b.created = num(m, "created");
        b.lastActivity = num(m, "lastActivity");
        b.lastRead = num(m, "lastRead");
        for (String a : csv(val(m, "admins"))) {
            b.admins.add(a);
        }
        b.epoch = (int) num(m, "epoch");
        for (String k : csv(val(m, "keys"))) {
            int c = k.indexOf(':');
            if (c > 0) {
                b.keys.put(Integer.parseInt(k.substring(0, c)), new MiniData(k.substring(c + 1)).getBytes());
            }
        }
        b.relays.addAll(csv(val(m, "relays")));
        for (String c : csv(val(m, "cursors"))) {
            int eq = c.lastIndexOf('=');
            if (eq > 0) {
                try {
                    b.cursors.put(c.substring(0, eq), Long.parseLong(c.substring(eq + 1)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        b.pending.addAll(csv(val(m, "pending")));
        b.joining = "1".equals(val(m, "joining"));
        b.removed = "1".equals(val(m, "removed"));
        b.rosterTs = num(m, "rosterTs");
        b.rekeyTs = num(m, "rekeyTs");
        b.lastText = val(m, "lastText");
        b.roster.addAll(csv(val(m, "roster")));
        b.removedFps.addAll(csv(val(m, "removedFps")));
        unpairs(val(m, "names"), b.names);
        unpairs(val(m, "ders"), b.ders);
        unpairs(val(m, "cards"), b.cards);
        return b;
    }

    private static String val(Map<String, String> m, String k) {
        String v = m.get(k);
        return v == null ? "" : v;
    }

    private static long num(Map<String, String> m, String k) {
        try {
            return Long.parseLong(val(m, k));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) {
            if (!p.trim().isEmpty()) {
                out.add(p.trim());
            }
        }
        return out;
    }

    private void persist(Board b) {
        mStore.put(C_BOARDS, b.id, toJson(b));
    }

    /** Test hook: prune threshold (text posts kept per board). */
    public static int sPruneAt = MAX_LOCAL_POSTS;

    private void appendRaw(Board b, byte[] zRaw) {
        mStore.append(logName(b), new MiniData(zRaw).to0xString());
        if (b.entries.size() > sPruneAt + 100) {
            prune(b);
        }
    }

    /** True for a post the prune may drop: text and opaque posts, never the control posts
     *  (hello / roster / rekey / knock / welcome) the membership is rebuilt from. */
    private static boolean prunable(Entry e) {
        return e.isText() || e.kind.isEmpty();
    }

    /** Oldest text first, never a post still waiting to be published, never a control post. */
    private void prune(Board b) {
        b.entries.sort(Comparator.comparingLong(e -> e.ts));
        int texts = 0;
        for (Entry e : b.entries) {
            if (prunable(e)) {
                texts++;
            }
        }
        while (texts > sPruneAt) {
            Entry victim = null;
            for (Entry e : b.entries) {
                if (prunable(e) && !b.pending.contains(e.id)) {
                    victim = e;
                    break;
                }
            }
            texts--;
            if (victim == null) {
                break;
            }
            b.entries.remove(victim);
            b.ids.remove(victim.id);
        }
        List<String> lines = new ArrayList<>(b.entries.size());
        for (Entry e : b.entries) {
            lines.add(new MiniData(e.raw).to0xString());
        }
        mStore.rewrite(logName(b), lines);
    }

    private void derive(Board b) {
        if (b.pseudonym == null) {
            b.pseudonym = BoardKeys.pseudonym(mNode.identity(), b.idBytes);
            b.myHex = new MiniData(b.pseudonym.getPublic().getEncoded()).to0xString();
            b.myFp = Keys.fingerprint(b.myHex);
        }
    }

    // ---- boards ----

    public synchronized List<Board> boards() {
        List<Board> out = new ArrayList<>(mBoards.values());
        out.sort((a, b) -> Long.compare(b.lastActivity, a.lastActivity));
        return out;
    }

    public synchronized Board board(String zId) {
        return mBoards.get(norm(zId));
    }

    private static String norm(String zId) {
        return zId == null ? "" : new MiniData(zId.startsWith("0x") || zId.startsWith("0X") ? zId : "0x" + zId).to0xString();
    }

    /** Unread text posts since the local read marker. */
    public synchronized int unread(Board b) {
        int n = 0;
        for (Entry e : b.entries) {
            if (e.isText() && !e.mine && e.ts > b.lastRead && b.isMember(e.from)) {
                n++;
            }
        }
        return n;
    }

    public synchronized void markRead(String zId) {
        Board b = mBoards.get(norm(zId));
        if (b != null) {
            b.lastRead = Math.max(b.lastRead, b.lastActivity);
            persist(b);
        }
    }

    /** Make a board. Open = the link carries the key and anyone holding it may post. */
    public synchronized Board create(String zName, boolean zOpen) {
        if (mBoards.size() >= MAX_BOARDS) {
            throw new IllegalStateException("too many boards");
        }
        Board b = new Board(BoardKeys.newBoardId());
        b.name = zName == null ? "" : zName.trim();
        b.open = zOpen;
        b.created = System.currentTimeMillis();
        b.lastActivity = b.created;
        derive(b);
        b.admins.add(b.myHex);
        b.epoch = 1;
        b.keys.put(1, BoardKeys.newBoardKey());
        b.relays.addAll(pickRelays(Collections.emptyList()));
        mBoards.put(b.id, b);
        persist(b);
        postRoster(b);
        postHello(b);
        return b;
    }

    /** The share text / QR for a board. Open boards carry the key; invite-only ones never do. */
    public synchronized String link(String zId) {
        Board b = mBoards.get(norm(zId));
        if (b == null) {
            throw new IllegalArgumentException("no such board");
        }
        String admin = b.admins.isEmpty() ? b.myHex : b.admins.get(0);
        byte[] key = b.open ? b.keys.get(b.epoch) : null;
        return new BoardLink(b.idBytes, b.name, admin, b.open ? b.epoch : 0, key, b.relays).format();
    }

    /** Join from a link: hello straight away with a key, knock without one. */
    public synchronized Board join(String zLink) {
        BoardLink l = BoardLink.parse(zLink);
        String id = new MiniData(l.boardId).to0xString();
        Board b = mBoards.get(id);
        if (b != null) {
            return b;
        }
        if (mBoards.size() >= MAX_BOARDS) {
            throw new IllegalStateException("too many boards");
        }
        try {   // a malformed admin key would otherwise leave a half-joined board that never knocks
            java.security.KeyFactory.getInstance("RSA").generatePublic(
                    new java.security.spec.X509EncodedKeySpec(new MiniData(l.adminHex).getBytes()));
        } catch (Exception e) {
            throw new IllegalArgumentException("bad admin key in link");
        }
        b = new Board(l.boardId);
        b.name = l.name;
        b.open = l.hasKey();
        b.created = System.currentTimeMillis();
        b.lastActivity = b.created;
        b.admins.add(l.adminHex);
        derive(b);
        for (String r : l.relays) {
            if (b.relays.size() < RELAYS_PER_BOARD) {
                b.relays.add(r);
            }
        }
        if (l.hasKey()) {
            b.epoch = l.epoch;
            b.keys.put(l.epoch, l.key);
        } else {
            b.joining = true;
        }
        mBoards.put(id, b);
        persist(b);
        if (l.hasKey()) {
            postHello(b);
        } else {
            postKnock(b);
        }
        final Board fb = b;
        mExec.execute(() -> pull(fb));
        return b;
    }

    public synchronized void leave(String zId) {
        Board b = mBoards.remove(norm(zId));
        if (b != null) {
            mStore.remove(C_BOARDS, b.id);
            mStore.rewrite(logName(b), new ArrayList<>());
        }
    }

    // ---- posting ----

    /** Post text, or a reply when zRe names a post. */
    public synchronized Entry post(String zId, String zBody, String zRe) {
        Board b = mBoards.get(norm(zId));
        if (b == null) {
            throw new IllegalArgumentException("no such board");
        }
        if (!b.canPost()) {
            throw new IllegalStateException(b.joining ? "waiting for an admin to let you in" : "no board key");
        }
        // A media body starts with a control-character marker: trimming would strip it.
        String body = zBody == null ? "" : com.eurobuddha.maxima.core.chat.ChatMedia.isMedia(zBody) ? zBody : zBody.trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("empty post");
        }
        byte[] re = null;
        if (zRe != null && !zRe.isEmpty()) {
            re = new MiniData(zRe.startsWith("0x") || zRe.startsWith("0X") ? zRe : "0x" + zRe).getBytes();
            if (re.length != BoardPost.ID_BYTES) {
                throw new IllegalArgumentException("bad reply ref");
            }
        }
        return publishSealed(b, new Json.Writer().put("k", "text").put("b", body).done(), re);
    }

    /** The media mesh this engine publishes images through (null = no image posts here). */
    public void setMediaService(com.eurobuddha.maxima.core.media.MediaService zMedia) {
        mMedia = zMedia;
    }

    private volatile com.eurobuddha.maxima.core.media.MediaService mMedia;

    /**
     * Post an image (or any media): ONE upload to the media mesh, then a normal post whose body
     * carries the manifest ref, sealed under the board key like any text. Blocks on the publish
     * (encrypt + replicate, up to ~55 s): call off the UI thread. The caller shrinks the image
     * first; the mesh ceiling is 16 MB but a board should stay lean.
     */
    public Entry postMedia(String zId, byte[] zBytes, String zMime, String zCaption, String zRe) throws Exception {
        com.eurobuddha.maxima.core.media.MediaService media = mMedia;
        if (media == null) {
            throw new IllegalStateException("media service not set");
        }
        Board b = board(zId);
        if (b == null) {
            throw new IllegalArgumentException("no such board");
        }
        if (!b.canPost()) {
            throw new IllegalStateException(b.joining ? "waiting for an admin to let you in" : "no board key");
        }
        String ref = com.eurobuddha.maxima.core.chat.ChatEngine.publishRef(media, zBytes, zMime);
        return post(zId, com.eurobuddha.maxima.core.chat.ChatMedia.wrap(zMime, ref, zCaption == null ? "" : zCaption), zRe);
    }

    private void postHello(Board b) {
        String name = mCardName.isEmpty() ? safe(mNode.name()) : mCardName;
        String addr = mCardAddress.isEmpty() ? safe(mNode.permanentAddress()) : mCardAddress;
        publishSealed(b, new Json.Writer().put("k", "hello").put("n", name).put("a", addr).done(), null);
    }

    /** Members per roster part: 65 chars each, so a part stays well under the 64 KB envelope. */
    static final int ROSTER_PER_POST = 600;

    /** The roster in as many parts as it takes, all stamped with one admin clock {@code rs}:
     *  readers apply a roster only when its rs is newer than the one they hold, and a multi-part
     *  roster only once every part is in. */
    private void postRoster(Board b) {
        long rs = Math.max(System.currentTimeMillis(), b.rosterTs + 1);
        List<String> members = new ArrayList<>(b.roster);
        int parts = Math.max(1, (members.size() + ROSTER_PER_POST - 1) / ROSTER_PER_POST);
        for (int p = 0; p < parts; p++) {
            List<String> part = members.subList(p * ROSTER_PER_POST, Math.min(members.size(), (p + 1) * ROSTER_PER_POST));
            publishSealed(b, new Json.Writer().put("k", "roster").put("n", b.name)
                    .put("a", String.join(",", b.admins)).put("m", String.join(",", part))
                    .put("rs", Long.toString(rs)).put("p", Integer.toString(p)).put("of", Integer.toString(parts)).done(), null);
        }
        b.rosterTs = rs;
        persist(b);
    }

    /** One knock per admin (sealed to each), so any admin can let us in. */
    private void postKnock(Board b) {
        String name = mCardName.isEmpty() ? safe(mNode.name()) : mCardName;
        String addr = mCardAddress.isEmpty() ? safe(mNode.permanentAddress()) : mCardAddress;
        String plain = new Json.Writer().put("k", "knock").put("n", name).put("a", addr).done();
        for (String admin : b.admins) {
            byte[] adminDer = new MiniData(admin).getBytes();
            publishTo(b, Codec.serialise(MaximaCrypto.encrypt(plain.getBytes(StandardCharsets.UTF_8), adminDer)), 0, null);
        }
    }

    /** Seal under the current epoch key and publish. */
    private Entry publishSealed(Board b, String zPlain, byte[] zRe) {
        byte[] key = b.keys.get(b.epoch);
        if (key == null) {
            throw new IllegalStateException("no board key");
        }
        return publishTo(b, BoardKeys.seal(key, zPlain.getBytes(StandardCharsets.UTF_8)), b.epoch, zRe);
    }

    private Entry publishTo(Board b, byte[] zBlob, int zEpoch, byte[] zRe) {
        BoardPost p = new BoardPost(b.idBytes, BoardKeys.newPostId(), System.currentTimeMillis(), zEpoch, zRe, zBlob);
        BoardPost.Signed s = p.sign(b.pseudonym);
        Entry e = apply(b, s, true);
        appendRaw(b, s.raw);
        b.pending.add(s.post.idHex());
        persist(b);
        final byte[] raw = s.raw;
        final String id = s.post.idHex();
        mExec.execute(() -> publish(b, raw, id));
        notifyChanged(b);
        return e;
    }

    /** Off the engine lock: one put per relay, success is any relay taking it. */
    private void publish(Board b, byte[] zRaw, String zId) {
        List<String> relays;
        KeyPair pseudonym;
        synchronized (this) {
            relays = relaysFor(b);
            pseudonym = b.pseudonym;
        }
        int ok = 0;
        for (String r : relays) {
            if (BoardWire.put(mNode, r, pseudonym, zRaw)) {
                ok++;
                remember(b, r);
            }
        }
        if (ok > 0) {
            synchronized (this) {
                b.pending.remove(zId);
                persist(b);
            }
        }
    }

    private synchronized void remember(Board b, String zRelay) {
        if (!b.relays.contains(zRelay)) {
            if (b.relays.size() >= RELAYS_PER_BOARD) {
                b.relays.remove(0);
            }
            b.relays.add(zRelay);
        }
    }

    /** The board's own relays first, then our pool relays to fill up. Caller holds the lock. */
    private List<String> relaysFor(Board b) {
        return pickRelays(b.relays);
    }

    private List<String> pickRelays(List<String> zFirst) {
        List<String> out = new ArrayList<>(zFirst);
        for (String r : mNode.poolMlsAddresses()) {
            if (out.size() >= RELAYS_PER_BOARD) {
                break;
            }
            if (!out.contains(r)) {
                out.add(r);
            }
        }
        return out;
    }

    // ---- admin ----

    /** Join requests on an invite-only board, newest first. Only an admin can read them. */
    public synchronized List<Knock> knocks(String zId) {
        Board b = mBoards.get(norm(zId));
        List<Knock> out = new ArrayList<>();
        if (b == null || !b.iAmAdmin()) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (int i = b.entries.size() - 1; i >= 0; i--) {
            Entry e = b.entries.get(i);
            if ("knock".equals(e.kind) && !b.roster.contains(e.from) && !b.removedFps.contains(e.from) && seen.add(e.from)) {
                out.add(new Knock(e.from, val(e.fields, "n"), val(e.fields, "a"), e.ts));
            }
        }
        return out;
    }

    /** Let a knocker in: the key sealed to their pseudonym, then the roster with them on it. */
    public synchronized void approve(String zId, String zFp) {
        Board b = requireAdmin(zId);
        String der = b.ders.get(zFp);
        if (der == null) {
            throw new IllegalArgumentException("unknown member");
        }
        String plain = new Json.Writer().put("k", "welcome").put("e", Integer.toString(b.epoch))
                .put("key", new MiniData(b.keys.get(b.epoch)).to0xString()).put("n", b.name).done();
        publishTo(b, Codec.serialise(MaximaCrypto.encrypt(plain.getBytes(StandardCharsets.UTF_8),
                new MiniData(der).getBytes())), 0, null);
        b.roster.add(zFp);
        b.removedFps.remove(zFp);
        postRoster(b);
    }

    /**
     * Put a member out: the roster without them under the key everyone (including them) still
     * holds, then a new key only the rest can open, then the roster again under it so a later
     * joiner - who only ever gets the current key - can read the membership too.
     */
    public synchronized void remove(String zId, String zFp) {
        Board b = requireAdmin(zId);
        b.roster.remove(zFp);
        b.names.remove(zFp);
        b.removedFps.add(zFp);
        postRoster(b);
        rekey(b);
        postRoster(b);
    }

    public synchronized void rename(String zId, String zName) {
        Board b = requireAdmin(zId);
        b.name = zName == null ? "" : zName.trim();
        persist(b);
        postRoster(b);
    }

    private Board requireAdmin(String zId) {
        Board b = mBoards.get(norm(zId));
        if (b == null) {
            throw new IllegalArgumentException("no such board");
        }
        if (!b.iAmAdmin()) {
            throw new SecurityException("admins only");
        }
        return b;
    }

    /**
     * Next epoch. The new key goes out sealed to every remaining member whose pseudonym we have
     * seen sign something (hello / knock / any post); a member who never posted gets it on their
     * next knock. Sent under the OLD key so the rest can read it; the removed member cannot open
     * their missing slot and cannot read anything sealed under the new one.
     */
    private void rekey(Board b) {
        int next = b.epoch + 1;
        byte[] key = BoardKeys.newBoardKey();
        Set<String> recipients = new LinkedHashSet<>(b.roster);
        for (String a : b.admins) {
            recipients.add(Keys.fingerprint(a));
        }
        if (b.open) {
            recipients.addAll(b.names.keySet());
        }
        List<String> slots = new ArrayList<>();
        for (String fp : recipients) {
            String der = fp.equals(b.myFp) ? b.myHex : b.ders.get(fp);
            if (der == null) {
                continue;
            }
            byte[] sealed = Codec.serialise(MaximaCrypto.encrypt(key, new MiniData(der).getBytes()));
            slots.add(fp + ":" + new MiniData(sealed).to0xString());
        }
        // Chunk by BYTES: a slot is ~480 chars (fp + sealed key), the envelope ceiling is 64 KB.
        List<String> chunk = new ArrayList<>();
        int chars = 0;
        for (String slot : slots) {
            if (!chunk.isEmpty() && chars + slot.length() + 1 > REKEY_CHUNK_CHARS) {
                publishSealed(b, new Json.Writer().put("k", "rekey").put("e", Integer.toString(next))
                        .put("x", String.join(",", chunk)).done(), null);
                chunk.clear();
                chars = 0;
            }
            chunk.add(slot);
            chars += slot.length() + 1;
        }
        if (!chunk.isEmpty()) {
            publishSealed(b, new Json.Writer().put("k", "rekey").put("e", Integer.toString(next))
                    .put("x", String.join(",", chunk)).done(), null);
        }
        b.keys.put(next, key);
        b.epoch = next;
        b.rekeyTs = System.currentTimeMillis();
        persist(b);
    }

    // ---- reading ----

    /** Text posts and replies a member may see, oldest first. */
    public synchronized List<Entry> posts(String zId, int zLimit) {
        Board b = mBoards.get(norm(zId));
        List<Entry> out = new ArrayList<>();
        if (b == null) {
            return out;
        }
        for (Entry e : b.entries) {
            if (e.isText() && b.isMember(e.from) && !(e.epoch < b.epoch && e.ts > b.rekeyTs && b.rekeyTs > 0)) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparingLong(e -> e.ts));
        if (zLimit > 0 && out.size() > zLimit) {
            out = new ArrayList<>(out.subList(out.size() - zLimit, out.size()));
        }
        return out;
    }

    /** Apply one verified envelope to a board. Null when already known. */
    private Entry apply(Board b, BoardPost.Signed s, boolean zMine) {
        if (!s.post.boardHex().equals(b.id) || b.ids.contains(s.post.idHex())) {
            return null;
        }
        b.ders.put(s.signerFp, s.signerHex);
        boolean mine = zMine || s.signerFp.equals(b.myFp);
        String kind = "";
        Map<String, String> fields = null;
        int epoch = s.post.mEpoch.getAsInt();
        byte[] plain = null;
        if (epoch == 0) {
            // Sealed to ONE pseudonym: a knock (to an admin) or a welcome (to a knocker). Ours
            // opens; everyone else's stays opaque. Signed-then-sealed, so verify came first.
            // Only an admin (knocks) or a joiner (welcome) pays the RSA decrypt: for anyone else
            // these are noise, and 2,000 members must not each decrypt every knock.
            try {
                if (!b.iAmAdmin() && !b.joining) {
                    throw new IllegalStateException("not for us");
                }
                plain = MaximaCrypto.decrypt(CryptoPackage.fromBytes(s.post.mBlob.getBytes()),
                        b.pseudonym.getPrivate());
            } catch (Exception notForUs) {
                plain = null;
            }
        } else {
            plain = BoardKeys.open(b.keys.get(epoch), s.post.mBlob.getBytes());
        }
        if (plain != null) {
            try {
                fields = Json.parse(new String(plain, StandardCharsets.UTF_8));
                kind = val(fields, "k");
            } catch (Exception e) {
                fields = null;
            }
        }
        Entry e = new Entry(s, kind, fields, mine);
        b.entries.add(e);
        b.ids.add(e.id);
        if (e.ts > b.lastActivity && (e.isText() || "hello".equals(kind))) {
            b.lastActivity = e.ts;
        }
        if (e.isText() && b.isMember(e.from)) {
            b.lastText = e.body();
        }
        boolean isAdmin = b.admins.contains(s.signerHex);
        switch (kind) {
            case "hello":
                b.names.put(e.from, val(fields, "n"));
                b.cards.put(e.from, val(fields, "a"));
                break;
            case "roster":
                if (isAdmin) {
                    long rs = num(fields, "rs");
                    if (rs == 0) {
                        rs = e.ts;   // a roster from before parts existed
                    }
                    if (rs < b.rosterTs) {
                        break;   // older than what we hold: relays, reseeds and reloads deliver out of order
                    }
                    int of = Math.max(1, (int) num(fields, "of"));
                    int part = (int) num(fields, "p");
                    List<String> members;
                    if (of == 1) {
                        members = csv(val(fields, "m"));
                    } else {
                        Map<Integer, List<String>> parts = b.rosterParts.computeIfAbsent(rs, x -> new HashMap<>());
                        parts.put(part, csv(val(fields, "m")));
                        if (parts.size() < of) {
                            break;   // wait for the rest of this roster
                        }
                        members = new ArrayList<>();
                        for (int i = 0; i < of; i++) {
                            members.addAll(parts.getOrDefault(i, Collections.emptyList()));
                        }
                        final long done = rs;
                        b.rosterParts.keySet().removeIf(k -> k <= done);
                    }
                    String n = val(fields, "n");
                    if (!n.isEmpty()) {
                        b.name = n;
                    }
                    List<String> admins = csv(val(fields, "a"));
                    if (!admins.isEmpty()) {
                        b.admins.clear();
                        b.admins.addAll(admins);
                    }
                    boolean wasIn = b.roster.contains(b.myFp) || b.iAmAdmin() || !b.open && !b.joining && b.rosterTs > 0;
                    b.roster.clear();
                    b.roster.addAll(members);
                    b.rosterTs = rs;
                    if (!b.open && !b.iAmAdmin() && !mine) {
                        boolean in = b.roster.contains(b.myFp);
                        if (in) {
                            b.removed = false;
                        } else if (wasIn && !b.joining) {
                            b.removed = true;   // put out: read what we can, post nothing
                        }
                    }
                }
                break;
            case "rekey":
                if (isAdmin && !mine) {
                    int next = (int) num(fields, "e");
                    for (String slot : csv(val(fields, "x"))) {
                        int c = slot.indexOf(':');
                        if (c > 0 && slot.substring(0, c).equals(b.myFp)) {
                            try {
                                byte[] key = MaximaCrypto.decrypt(
                                        CryptoPackage.fromBytes(new MiniData(slot.substring(c + 1)).getBytes()),
                                        b.pseudonym.getPrivate());
                                if (key.length == BoardKeys.KEY_BYTES && next > 0 && next <= b.epoch + 1000) {
                                    b.keys.put(next, key);
                                    if (next > b.epoch) {
                                        b.epoch = next;
                                        b.rekeyTs = e.ts;
                                    }
                                    reopen(b);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                break;
            case "welcome":
                // Only an admin can let us in: anyone with the link could otherwise seal a bogus
                // key to a knocker and strand them with joining=false under garbage.
                if (isAdmin && !mine && b.joining) {
                    int ep = (int) num(fields, "e");
                    String k = val(fields, "key");
                    if (ep > 0 && ep <= b.epoch + 1000 && k.length() == 2 + BoardKeys.KEY_BYTES * 2) {
                        b.keys.put(ep, new MiniData(k).getBytes());
                        b.epoch = Math.max(b.epoch, ep);
                        b.joining = false;
                        String n = val(fields, "n");
                        if (!n.isEmpty()) {
                            b.name = n;
                        }
                        reopen(b);
                        postHello(b);
                    }
                }
                break;
            default:
                break;
        }
        return e;
    }

    /** A new key arrived: posts that were opaque under it can be read now. */
    private void reopen(Board b) {
        List<Entry> opaque = new ArrayList<>();
        for (Entry e : b.entries) {
            if (e.kind.isEmpty() && e.epoch > 0 && b.keys.containsKey(e.epoch)) {
                opaque.add(e);
            }
        }
        for (Entry e : opaque) {
            b.entries.remove(e);
            b.ids.remove(e.id);
            try {
                apply(b, BoardPost.verify(e.raw), e.mine);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    // ---- sync ----

    /** Drive from the host's heartbeat: pull every board from a couple of relays, publish
     *  anything pending, re-seed a relay that forgot. Network work runs on the engine's lane. */
    private final java.util.concurrent.atomic.AtomicBoolean mTickQueued = new java.util.concurrent.atomic.AtomicBoolean();

    public void tick() {
        if (!mLoaded || !mTickQueued.compareAndSet(false, true)) {
            return;   // one pass in flight or queued is enough: a slow relay must not stack them up
        }
        mExec.execute(() -> {
            try {
                pullAll();
            } finally {
                mTickQueued.set(false);
            }
        });
    }

    private void pullAll() {
        List<Board> boards;
        synchronized (this) {
            boards = new ArrayList<>(mBoards.values());
        }
        if (boards.isEmpty()) {
            return;
        }
        // Relays to read: each board's own, then the pool, a couple per board.
        Map<String, List<Board>> byRelay = new LinkedHashMap<>();
        synchronized (this) {
            for (Board b : boards) {
                int n = 0;
                for (String r : relaysFor(b)) {
                    if (n++ >= READ_RELAYS) {
                        break;
                    }
                    byRelay.computeIfAbsent(r, x -> new ArrayList<>()).add(b);
                }
            }
        }
        for (Map.Entry<String, List<Board>> e : byRelay.entrySet()) {
            pullFrom(e.getKey(), e.getValue());
        }
        for (Board b : boards) {
            List<String> pending;
            synchronized (this) {
                pending = new ArrayList<>(b.pending);
            }
            for (String id : pending) {
                byte[] raw = rawOf(b, id);
                if (raw != null) {
                    publish(b, raw, id);
                }
            }
        }
    }

    /** One reader pseudonym per device for pulls: a relay sees a key that reads these boards,
     *  never the MAX# identity (puts use the per-board pseudonym). */
    private KeyPair mReader;

    private synchronized KeyPair reader() {
        if (mReader == null) {
            mReader = BoardKeys.reader(mNode.identity());
        }
        return mReader;
    }

    private void pull(Board b) {
        List<String> relays;
        synchronized (this) {
            relays = relaysFor(b);
        }
        for (String r : relays) {
            pullFrom(r, Collections.singletonList(b));
        }
    }

    private void pullFrom(String zRelay, List<Board> zBoards) {
        for (int page = 0; page < PAGES_PER_TICK; page++) {
            BoardWire.SinceRequest req = new BoardWire.SinceRequest();
            synchronized (this) {
                for (Board b : zBoards) {
                    if (req.size() >= BoardWire.MAX_BOARDS_PER_CALL) {
                        break;
                    }
                    Long c = b.cursors.get(zRelay);
                    req.add(b.idBytes, c == null ? 0 : c);
                }
            }
            BoardWire.SinceReply reply = BoardWire.since(mNode, zRelay, reader(), req);
            if (reply == null) {
                return;
            }
            boolean more = false;
            for (BoardWire.Slice s : reply.slices) {
                Board b = null;
                for (Board x : zBoards) {
                    if (x.id.equals(s.board.to0xString())) {
                        b = x;
                    }
                }
                if (b == null) {
                    continue;
                }
                Set<String> seen = new HashSet<>();
                boolean changed = false;
                synchronized (this) {
                    for (byte[] raw : s.raws) {
                        try {
                            BoardPost.Signed sg = BoardPost.verify(raw);
                            seen.add(sg.post.idHex());
                            if (apply(b, sg, false) != null) {
                                appendRaw(b, raw);
                                changed = true;
                            }
                        } catch (IllegalArgumentException bad) {
                            // a relay handed us junk: ignore it, keep the rest
                        }
                    }
                    b.cursors.put(zRelay, s.cursor);
                    if (!s.raws.isEmpty()) {
                        more = true;
                    }
                    remember(b, zRelay);
                    persist(b);
                }
                if (changed) {
                    notifyChanged(b);
                }
                if (s.reset && s.raws.size() < 50) {
                    reseed(b, zRelay, seen);
                }
            }
            if (!more) {
                return;
            }
        }
    }

    private final Map<String, Long> mReseedAt = new HashMap<>();
    private static final long RESEED_BACKOFF_MS = 10 * 60 * 1000L;

    /** The relay has less than we do: give it back OUR OWN posts it lacks (idempotent puts),
     *  a bounded batch, not more than once per ten minutes per board and relay - so a relay
     *  that pruned does not get 2,000 members re-seeding everything on every heartbeat. */
    private void reseed(Board b, String zRelay, Set<String> zRelayHas) {
        List<byte[]> raws = new ArrayList<>();
        KeyPair pseudonym;
        synchronized (this) {
            String key = b.id + "|" + zRelay;
            long now = System.currentTimeMillis();
            Long last = mReseedAt.get(key);
            if (last != null && now - last < RESEED_BACKOFF_MS) {
                return;
            }
            mReseedAt.put(key, now);
            pseudonym = b.pseudonym;
            for (Entry e : b.entries) {
                if (e.mine && !zRelayHas.contains(e.id)) {
                    raws.add(e.raw);
                    if (raws.size() >= RESEED_PER_TICK) {
                        break;
                    }
                }
            }
        }
        for (byte[] raw : raws) {
            if (!BoardWire.put(mNode, zRelay, pseudonym, raw)) {
                return;   // the relay is refusing: try again next tick
            }
        }
    }

    private synchronized byte[] rawOf(Board b, String zId) {
        for (Entry e : b.entries) {
            if (e.id.equals(zId)) {
                return e.raw;
            }
        }
        b.pending.remove(zId);   // pruned away: nothing left to publish
        return null;
    }

    private void notifyChanged(Board b) {
        Listener l = mListener;
        if (l != null) {
            try {
                l.onBoardChanged(b);
            } catch (Exception ignored) {
            }
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    public void stop() {
        mExec.shutdownNow();
    }
}
