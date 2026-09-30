package com.eurobuddha.maxima.cloud;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Owner management of the existing Tenants folder protocol. Never installed on a tenant. */
public final class HostedAccounts {
    public static final String STATUS_FILE = ".host-status.json";
    public static final String STOP_FILE = ".stop";
    public static final int DEFAULT_LIMIT = 10;
    private final Path root;
    private final int limit;

    public HostedAccounts(Path root, int limit) throws Exception {
        this.root = root.toRealPath();
        if (!Files.isDirectory(this.root) || limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Invalid hosting configuration");
        this.limit = limit;
    }

    public static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    }

    public static List<Path> directories(Path root) throws Exception {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path p : dirs) if (validName(p.getFileName().toString())
                    && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) result.add(p);
        }
        result.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return result;
    }

    private Path directory(String name, boolean mustExist) throws Exception {
        if (!validName(name)) throw new IllegalArgumentException("Use 1–64 letters, numbers, dots, underscores or hyphens; start with a letter or number");
        Path dir = root.resolve(name);
        if (Files.isSymbolicLink(dir) || (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)))
            throw new IllegalArgumentException("Account folder is unavailable");
        if (mustExist && !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Hosted account not found");
        return dir;
    }

    /** Bounded, no-follow reads: these files never contain seeds, passwords or chat content. */
    private static String read(Path file) throws Exception {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return "";
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 65536)
            throw new IllegalStateException("Hosting metadata is unavailable");
        return Files.readString(file, StandardCharsets.UTF_8).trim();
    }

    private JSONObject hostStatus() {
        try {
            JSONObject state = (JSONObject) new JSONParser().parse(read(root.resolve(STATUS_FILE)));
            long age = System.currentTimeMillis() - Long.parseLong(String.valueOf(state.get("updated")));
            if (age >= 0 && age < 90_000) return state;
        } catch (Exception ignored) { }
        return null;
    }

    public static void publishStatus(Path root, Collection<String> running, Collection<String> failed) throws Exception {
        JSONObject out = new JSONObject();
        out.put("updated", System.currentTimeMillis());
        JSONArray active = new JSONArray(); active.addAll(running); out.put("running", active);
        JSONArray errors = new JSONArray(); errors.addAll(failed); out.put("failed", errors);
        AccountFiles.writePrivate(root.resolve(STATUS_FILE), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean contains(JSONObject status, String field, String name) {
        return status != null && status.get(field) instanceof JSONArray
                && ((JSONArray) status.get(field)).contains(name);
    }

    public synchronized JSONObject handle(JSONObject in) throws Exception {
        String action = String.valueOf(in.getOrDefault("action", "list"));
        String name = String.valueOf(in.getOrDefault("name", ""));
        JSONObject host = hostStatus();
        if (!"list".equals(action) && host == null)
            throw new IllegalStateException("Hosted-account service is offline. Ask the server operator to start it.");
        if ("create".equals(action)) {
            Path dir = directory(name, false);
            if (!Files.exists(dir)) {
                if (directories(root).size() >= limit) throw new IllegalStateException("Hosting limit reached (" + limit + " accounts)");
                try {
                    Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } catch (UnsupportedOperationException e) { Files.createDirectory(dir); }
            } // Transport retries are idempotent; never replace an existing identity.
        } else if ("pause".equals(action)) {
            AccountFiles.writePrivate(directory(name, true).resolve(STOP_FILE), new byte[0]);
        } else if ("resume".equals(action)) {
            Files.deleteIfExists(directory(name, true).resolve(STOP_FILE));
        } else if ("invite".equals(action)) {
            Path dir = directory(name, true);
            if (Files.exists(dir.resolve(STOP_FILE), LinkOption.NOFOLLOW_LINKS)
                    || !contains(host, "running", name)) throw new IllegalStateException("Wait until this account is running before creating an invite");
            String address = read(dir.resolve(AccountFiles.ACCOUNT_FILE));
            if (!address.startsWith("MAX#") || !address.contains("@"))
                throw new IllegalStateException("Account is connecting. Refresh in a few seconds.");
            // Reuse an outstanding code so a lost RPC response cannot invalidate a shown QR.
            String code = read(dir.resolve(AccountFiles.CODE_FILE));
            if (code.isEmpty()) code = new DevicePairing(dir).newBootstrapCode();
            if (!code.matches("[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){2}"))
                throw new IllegalStateException("Pairing code is not ready");
            JSONObject out = new JSONObject(); out.put("ok", true); out.put("name", name);
            out.put("invite", AccountFiles.invite(address, code)); out.put("address", address);
            out.put("code", code); return out;
        } else if (!"list".equals(action)) throw new IllegalArgumentException("Unknown hosted-account action");

        JSONObject out = new JSONObject(); out.put("ok", true); out.put("online", host != null);
        out.put("limit", limit); JSONArray accounts = new JSONArray();
        for (Path dir : directories(root)) {
            String n = dir.getFileName().toString();
            boolean paused = Files.exists(dir.resolve(STOP_FILE), LinkOption.NOFOLLOW_LINKS);
            boolean running = contains(host, "running", n);
            JSONObject row = new JSONObject(); row.put("name", n); row.put("paused", paused);
            row.put("state", host == null ? "offline" : paused ? (running ? "pausing" : "paused")
                    : running ? "running" : contains(host, "failed", n) ? "retrying" : "starting");
            accounts.add(row);
        }
        out.put("accounts", accounts); return out;
    }
}
