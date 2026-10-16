package com.eurobuddha.maxima.cloud;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.Collections;
import java.util.concurrent.*;
import org.minima.utils.json.JSONObject;

/** Opt-in chat-only hosting inside a desktop or standalone owner account. */
public final class ManagedHosting implements AutoCloseable {
    private final Path root;
    private final Path enabledFile;
    private final HostedAccounts accounts;
    private final ParlonsCore.Config config;
    private final AdminPairingPassword password;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final ScheduledExecutorService worker;
    private volatile Tenants.Host host;
    private boolean closed;

    public ManagedHosting(Path ownerData, ParlonsCore.Config ownerConfig, AdminPairingPassword password) throws Exception {
        root = ownerData.resolve("hosted-chat");
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IllegalStateException("Hosting folder must not be a symbolic link");
        enabledFile = root.resolve(".enabled");
        this.password = password;
        accounts = new HostedAccounts(root, HostedAccounts.DEFAULT_LIMIT);
        config = new ParlonsCore.Config();
        config.version = ownerConfig.version;
        config.relayPort = 0; config.directPort = 0; config.panelPort = 0;
        config.extraRelays.addAll(ownerConfig.extraRelays);
        config.builtInRelays = ownerConfig.builtInRelays;
        if (ownerConfig.ownRelay != null && !ownerConfig.ownRelay.isEmpty()) config.extraRelays.add(ownerConfig.ownRelay);
        lockChannel = FileChannel.open(root.resolve(".host-lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (Exception e) { lockChannel.close(); throw e; }
        if (acquired == null) { lockChannel.close(); throw new IllegalStateException("This hosting folder is already running"); }
        lock = acquired;
        try { Files.deleteIfExists(root.resolve(HostedAccounts.STATUS_FILE)); } // discard a crashed host's heartbeat
        catch (Exception e) { lock.release(); lockChannel.close(); throw e; }
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "parlons-hosted-chat"); t.setDaemon(true); return t;
        });
        worker.scheduleWithFixedDelay(this::tick, 0, 5, TimeUnit.SECONDS);
    }

    static ParlonsCore chatAccount(com.eurobuddha.maxima.core.identity.MaximaIdentity identity,
            Path data, ParlonsCore.Config config, String phrase) {
        AccountBackup.Source backup = new AccountBackup.Source() {
            public String phrase() { return phrase; }
            public java.util.Map<String, Integer> keyUses() { return Collections.emptyMap(); }
        };
        // No wallet, node console, owner password or hosting manager is installed on a tenant.
        return new ParlonsCore(identity, data, config, null, backup);
    }

    private void tick() {
        Tenants.Host current;
        boolean enabled;
        synchronized (this) {
            if (closed) return;
            enabled = Files.isRegularFile(enabledFile, LinkOption.NOFOLLOW_LINKS);
            if (enabled && host == null) host = new Tenants.Host(root, config, null, ManagedHosting::chatAccount);
            current = host;
        }
        if (current == null) return;
        // Connection attempts stay on this worker, never under the owner RPC handler's monitor.
        if (enabled) { current.poll(); current.publish(); }
        else {
            current.shutdownAll();
            synchronized (this) { if (host == current) host = null; }
        }
    }

    public synchronized JSONObject handle(JSONObject input) throws Exception {
        if (closed) throw new IllegalStateException("Hosting is stopping");
        String action = String.valueOf(input.getOrDefault("action", "list"));
        if ("enable".equals(action)) {
            if (!password.configured()) throw new IllegalStateException("Set your admin password before enabling hosting");
            AccountFiles.writePrivate(enabledFile, new byte[]{1});
        } else if ("disable".equals(action)) {
            Files.deleteIfExists(enabledFile);
        }
        boolean enabled = Files.isRegularFile(enabledFile, LinkOption.NOFOLLOW_LINKS);
        if (!enabled && !("list".equals(action) || "disable".equals(action)))
            throw new IllegalStateException("Enable hosting before managing guest accounts");
        JSONObject request = input;
        if ("enable".equals(action) || "disable".equals(action)) {
            request = new JSONObject(); request.put("action", "list");
        }
        JSONObject out = accounts.handle(request);
        if (!enabled) out.put("online", false);
        out.put("managed", true);
        out.put("enabled", enabled);
        out.put("access", "chat-only");
        return out;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true; worker.shutdownNow();
        if (host != null) host.shutdownAll();
        try { lock.release(); } catch (Exception ignored) { }
        try { lockChannel.close(); } catch (Exception ignored) { }
    }
}
