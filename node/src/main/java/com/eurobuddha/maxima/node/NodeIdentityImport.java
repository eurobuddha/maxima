package com.eurobuddha.maxima.node;

import com.eurobuddha.maxima.cloud.*;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.codec.MiniString;
import com.eurobuddha.maxima.core.crypto.Hashes;
import com.eurobuddha.maxima.core.identity.*;
import org.minima.utils.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Prepare off the node pump, confirm over owner RPC, apply OFFLINE before the next node boot. */
public final class NodeIdentityImport implements ParlonsControl.IdentityImport {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final long TTL = 15 * 60_000L;
    private static final String PENDING = ".identity-import";
    // Never include the Minima vault/chain, NFT files or gateway credentials.
    static final List<String> ACCOUNT_FILES = Arrays.asList("node", "chat", "media", "private-files", "devices.json",
            "cloud-settings.properties", "pair-code.txt", "account.txt", "invite.txt", "panel.txt",
            "panel-ticket.txt", "identity-anyphrase.txt", "identity.txt");
    private final Path root;
    private final Supplier<List<String>> directories;
    private final String currentKey;
    private final DevicePairing pairing;
    private final Runnable restart;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "parlons-identity-import"); t.setDaemon(true); return t;
    });
    private String owner = "", id = "", state = "", error = "", address = "", name = "";
    private int total, received;
    private long touched;
    private boolean closed;

    public NodeIdentityImport(Path root, String currentKey, DevicePairing pairing,
                              Supplier<List<String>> directories, Runnable restart) {
        this.root = root; this.currentKey = currentKey; this.pairing = pairing;
        this.directories = directories; this.restart = restart;
    }

    @Override public synchronized JSONObject handle(byte[] caller, JSONObject in) throws Exception {
        if (closed || !pairing.isAuthorized(caller)) throw new IllegalStateException("not authorized");
        String who = new MiniData(caller).to0xString();
        String action = str(in, "action"), ticket = str(in, "id");
        if (!ticket.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid import request");
        if ("begin".equals(action)) {
            if (ticket.equals(id) && who.equals(owner)) return status(); // retried reply
            if ("committed".equals(state) || "preparing".equals(state)
                    || (!id.isEmpty() && System.currentTimeMillis() - touched < TTL)) {
                throw new IllegalStateException("An import is already in progress. Finish or cancel it first.");
            }
            Path pending = root.resolve(PENDING);
            if (Files.exists(pending.resolve("commit"))) throw new IllegalStateException("An import is awaiting restart");
            removeTree(pending);
            privateDir(pending);
            owner = who; id = ticket; state = "uploading"; error = ""; address = ""; name = "";
            total = number(in, "total"); received = 0; touched = System.currentTimeMillis();
            if (total < 0 || total > MAX_BYTES) { state = "failed"; throw new IllegalArgumentException("Backup exceeds 16 MB"); }
            return status();
        }
        if ("cancel".equals(action) && id.isEmpty() && !Files.exists(root.resolve(PENDING).resolve("commit"))) {
            JSONObject out = new JSONObject(); out.put("ok", true); out.put("state", "cancelled"); return out;
        }
        if (!ticket.equals(id) || !who.equals(owner)) throw new IllegalStateException("Import not found for this device");
        if (!"cancel".equals(action) && !"committed".equals(state) && System.currentTimeMillis() - touched > TTL)
            throw new IllegalStateException("Import expired; start again");
        touched = System.currentTimeMillis();
        Path pending = root.resolve(PENDING);
        if ("cancel".equals(action)) {
            if ("preparing".equals(state) || "committed".equals(state)) throw new IllegalStateException("Import is already being processed");
            removeTree(pending); state = "cancelled"; id = ""; return status();
        }
        if ("upload".equals(action)) {
            if (!"uploading".equals(state)) return status();
            String encoded = str(in, "chunk");
            if (encoded.length() > 120_000) throw new IllegalArgumentException("Import chunk too large");
            byte[] chunk = Base64.getDecoder().decode(encoded);
            int offset = number(in, "offset");
            Path file = pending.resolve("backup.pbk");
            if (offset < received) {
                // Only accept exact replay; transport retries must not append twice.
                if (offset < 0 || offset + chunk.length > received) throw new IllegalArgumentException("Incorrect upload offset");
                try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(file.toFile(), "r")) {
                    f.seek(offset); byte[] old = new byte[chunk.length]; f.readFully(old);
                    if (!Arrays.equals(old, chunk)) throw new IllegalArgumentException("Upload replay differs");
                }
            } else {
                if (offset != received || chunk.length == 0 || received + chunk.length > total)
                    throw new IllegalArgumentException("Incorrect upload size or offset");
                Files.write(file, chunk, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                received += chunk.length;
            }
            return status();
        }
        if ("prepare".equals(action)) {
            if (!"uploading".equals(state)) return status();
            if (total > 0 && received != total) throw new IllegalStateException("Backup upload is incomplete");
            String phrase = str(in, "phrase");
            boolean custom = Boolean.TRUE.equals(in.get("anyPhrase"));
            char[] password = str(in, "password").toCharArray();
            if (phrase.length() > 16_384) throw new IllegalArgumentException("Phrase is too long");
            state = "preparing";
            worker.execute(() -> prepare(caller.clone(), phrase, custom, password));
        } else if ("commit".equals(action)) {
            if ("committed".equals(state)) return status();
            if (!"ready".equals(state)) throw new IllegalStateException("Import is not ready");
            if (!Boolean.TRUE.equals(in.get("confirm")) || !Boolean.TRUE.equals(in.get("oldHostStopped")))
                throw new IllegalArgumentException("Confirm the import and stop the original account first");
            writePrivate(pending.resolve("commit"), id);
            state = "committed";
            // Give the encrypted reply time to arrive. Startup performs the swap only after
            // shutdown hooks have flushed all old stores, never against a running account.
            worker.schedule(restart, 3, TimeUnit.SECONDS);
        } else if (!"status".equals(action)) {
            throw new IllegalArgumentException("Unknown import action");
        }
        return status();
    }

    private void prepare(byte[] caller, String phrase, boolean custom, char[] password) {
        try {
            Path pending = root.resolve(PENDING), next = pending.resolve("next");
            BackupBundle bundle;
            if (total > 0) {
                bundle = AccountBackup.read(Files.readAllBytes(pending.resolve("backup.pbk")), password);
            } else {
                if (phrase.trim().isEmpty()) throw new IllegalArgumentException("Enter your phrase");
                bundle = new BackupBundle(); bundle.anyPhrase = custom;
                bundle.phrase = custom ? phrase : Bip39.cleanSeedPhrase(phrase);
            }
            if (bundle.accountFormat > BackupBundle.ACCOUNT_FORMAT) throw new IllegalArgumentException("Update the server for this backup format");
            MaximaIdentity identity = bundle.anyPhrase
                    ? MaximaIdentity.fromSeed(new MiniData(Hashes.sha3(new MiniString(bundle.phrase).getData())))
                    : total == 0 ? MaximaIdentity.fromPhrase(bundle.phrase) : MaximaIdentity.fromNodeSecret(bundle.phrase);
            if (identity.publicKeyHex().equalsIgnoreCase(currentKey)) throw new ImportProblem("This server already hosts that identity. No import is needed.");
            String directory = null;
            for (String candidate : directories.get()) {
                if (!MxAddress.isValidContactAddress(candidate)) continue;
                // The Pi's cape changes identity too. Use an independent directory for reconnect.
                String routing = MxAddress.convert(candidate.substring(0, candidate.indexOf('@'))).to0xString();
                if (!routing.equalsIgnoreCase(currentKey)) { directory = candidate; break; }
            }
            if (directory == null) throw new ImportProblem("Connect the node to another relay before importing, so this phone can reconnect after the identity changes.");
            privateDir(next);
            AccountBackup.applyRestore(next, bundle, null, "identity.txt");
            // Preserve source-device pairings from a full bundle and add only this target client.
            // Other clients of the account being replaced never inherit access to the new one.
            DevicePairing restored = new DevicePairing(next);
            if (!restored.isAuthorized(caller)) {
                if (restored.requestPair(caller, "Device that imported this account", restored.newBootstrapCode())
                        != DevicePairing.Result.AUTHORIZED) throw new IllegalStateException("Could not retain this device's pairing");
            }
            Files.deleteIfExists(next.resolve("pair-code.txt"));
            // Host connectivity belongs to this server. Imported addresses/settings may refer
            // to a retired server or disable the target's only route back to its client.
            Files.deleteIfExists(next.resolve("cloud-settings.properties"));
            Path settings = root.resolve("cloud-settings.properties");
            if (Files.isRegularFile(settings)) Files.copy(settings, next.resolve("cloud-settings.properties"));
            synchronized (this) {
                if (closed || !pairing.isAuthorized(caller)) throw new IllegalStateException("The importing device is no longer authorized");
                address = "MAX#" + identity.publicKeyHex() + "#" + directory;
                name = bundle.displayName == null ? "" : bundle.displayName.substring(0, Math.min(160, bundle.displayName.length()));
                state = "ready"; touched = System.currentTimeMillis();
            }
        } catch (Exception e) {
            synchronized (this) {
                state = "failed";
                // Crypto/parse exceptions can include input. Never echo secret backup content.
                error = e instanceof ImportProblem ? e.getMessage() : e instanceof javax.crypto.AEADBadTagException ? "Incorrect backup password or damaged backup"
                        : "Could not prepare the identity. Check the phrase mode or backup, and the node's relay connections.";
            }
        } finally { Arrays.fill(password, '\0'); }
    }

    private static final class ImportProblem extends Exception { ImportProblem(String text) { super(text); } }

    private JSONObject status() {
        JSONObject out = new JSONObject(); out.put("ok", true); out.put("state", state);
        out.put("id", id); out.put("received", received); out.put("address", address);
        out.put("name", name); if (!error.isEmpty()) out.put("error", error); return out;
    }

    /** Resumable rename transaction. Must run before constructing any node/account/store. */
    public static void applyPending(Path root) throws Exception {
        Path pending = root.resolve(PENDING);
        if (!Files.isRegularFile(pending.resolve("commit"))) return;
        String id = Files.readString(pending.resolve("commit")).trim();
        if (!id.matches("[a-f0-9]{32}")) throw new IllegalStateException("Invalid pending account import");
        Path next = pending.resolve("next"), previous = pending.resolve("previous");
        if (!Files.exists(pending.resolve("old-moved")) && !Files.isRegularFile(next.resolve("identity.txt")))
            throw new IllegalStateException("Staged identity is missing; live account was not changed");
        privateDir(previous);
        if (!Files.exists(pending.resolve("old-moved"))) {
            for (String name : ACCOUNT_FILES) {
                Path live = root.resolve(name), old = previous.resolve(name);
                if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.exists(old, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Import recovery conflict: " + name);
                    Files.move(live, old, StandardCopyOption.ATOMIC_MOVE);
                }
            }
            writePrivate(pending.resolve("old-moved"), id);
        }
        for (String name : ACCOUNT_FILES) {
            Path staged = next.resolve(name), live = root.resolve(name);
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) Files.move(staged, live, StandardCopyOption.ATOMIC_MOVE);
        }
        if (!Files.isRegularFile(root.resolve("identity.txt"))) throw new IllegalStateException("Imported identity is missing");
        Path history = root.resolve("account-history"); privateDir(history);
        Files.deleteIfExists(pending.resolve("backup.pbk"));
        Files.move(pending, history.resolve(id), StandardCopyOption.ATOMIC_MOVE);
    }

    private static void privateDir(Path path) throws Exception {
        Files.createDirectories(path);
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) { }
    }
    private static void writePrivate(Path target, String text) throws Exception {
        AccountFiles.writePrivate(target, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void removeTree(Path root) throws Exception {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
        }
    }
    private static String str(JSONObject in, String key) { return String.valueOf(in.getOrDefault(key, "")); }
    private static int number(JSONObject in, String key) { return Integer.parseInt(str(in, key)); }
    @Override public synchronized void close() { closed = true; worker.shutdownNow(); }
}
