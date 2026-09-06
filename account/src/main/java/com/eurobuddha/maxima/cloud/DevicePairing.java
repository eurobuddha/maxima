package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.codec.MiniData;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The authorized-device set for a Parlons Cloud account — the login model.
 *
 * The cloud node holds the ONE account identity; a device NEVER holds it. Instead each
 * device has its own keypair, and pairing records that device's public key as authorized
 * to drive this account. Auth on every owner command is then simply: is the caller's
 * (signature-verified) Maxima identity in this set?
 *
 * Two ways in, no passwords over the wire:
 *  - **Bootstrap code:** a one-time code the operator reads over ssh from {@code pair-code.txt}
 *    (0600, auto-generated at first run while no device is paired). A device presenting it is
 *    authorized and the code is CONSUMED (deleted).
 *  - **Approval by an existing device:** a device with no code lands in {@code pending}; an
 *    already-paired device approves it. Losing a phone → {@link #revoke} it; the identity is
 *    untouched, so no rotation.
 *
 * Persisted to {@code <data>/devices.json}. Keys are the DER public key as 0x-hex.
 */
public final class DevicePairing {

    private static final SecureRandom RAND = new SecureRandom();

    public static final class Device {
        public final String key;      // 0x-hex of the DER public key
        public final String label;
        public final long pairedAt;
        /** iOS wake record: the APNs device token (hex), "prod"|"sandbox", and the wake proxy
         *  the DEVICE chose - "" = none registered, "off" = never wake, else a URL. The proxy
         *  only ever receives the token and the word "wake"; the message travels E2E after. */
        public volatile String apnsToken = "";
        public volatile String apnsEnv = "";
        public volatile String wakeProxy = "";
        public volatile long apnsUpdated;
        Device(String key, String label, long pairedAt) {
            this.key = key;
            this.label = label;
            this.pairedAt = pairedAt;
        }
        public boolean canWake() {
            return !apnsToken.isEmpty() && !wakeProxy.isEmpty() && !"off".equalsIgnoreCase(wakeProxy);
        }
    }

    /** Record (or clear, with an empty token) a device's APNs wake registration. */
    public synchronized boolean setApns(String zKeyHex, String zToken, String zEnv, String zProxy) {
        Device d = mAuthorized.get(normalizeHex(zKeyHex));
        if (d == null) {
            return false;
        }
        d.apnsToken = zToken == null ? "" : zToken.trim();
        d.apnsEnv = zEnv == null ? "" : zEnv.trim();
        d.wakeProxy = zProxy == null ? "" : zProxy.trim();
        d.apnsUpdated = System.currentTimeMillis();
        save();
        return true;
    }

    /** The device record for a key, or null. */
    public synchronized Device device(String zKeyHex) {
        return mAuthorized.get(normalizeHex(zKeyHex));
    }

    private final Path mFile;          // devices.json
    private final Path mCodeFile;      // pair-code.txt
    private final Map<String, Device> mAuthorized = new LinkedHashMap<>();
    private final Map<String, String> mPending = new LinkedHashMap<>();   // key -> label

    public DevicePairing(Path zDataDir) {
        mFile = zDataDir.resolve("devices.json");
        mCodeFile = zDataDir.resolve("pair-code.txt");
        load();
    }

    // ---- auth ----

    /** True when this DER public key is an authorized device of this account. */
    public synchronized boolean isAuthorized(byte[] zFromPublicKeyDer) {
        if (zFromPublicKeyDer == null) {
            return false;
        }
        return mAuthorized.containsKey(hex(zFromPublicKeyDer));
    }

    public synchronized int authorizedCount() {
        return mAuthorized.size();
    }

    public synchronized List<Device> authorized() {
        return new ArrayList<>(mAuthorized.values());
    }

    public synchronized List<String> pendingKeys() {
        return new ArrayList<>(mPending.keySet());
    }

    // ---- pairing ----

    public enum Result { AUTHORIZED, PENDING, ALREADY }

    /**
     * A device asks to pair. With a valid bootstrap code it is authorized immediately (and the
     * code is consumed); otherwise it goes to pending for an existing device to approve.
     */
    public synchronized Result requestPair(byte[] zDeviceKeyDer, String zLabel, String zCode) {
        String key = hex(zDeviceKeyDer);
        if (mAuthorized.containsKey(key)) {
            return Result.ALREADY;
        }
        String label = (zLabel == null || zLabel.isEmpty()) ? "device" : zLabel;
        String code = currentCode();
        if (zCode != null && !zCode.isEmpty() && code != null
                && normalize(zCode).equals(normalize(code))) {
            authorize(key, label);
            consumeCode();
            return Result.AUTHORIZED;
        }
        mPending.put(key, label);
        save();
        return Result.PENDING;
    }

    /** An already-authorized device approves a pending device. */
    public synchronized boolean approve(byte[] zApproverKeyDer, String zPendingKeyHex) {
        if (!isAuthorized(zApproverKeyDer)) {
            return false;
        }
        String key = normalizeHex(zPendingKeyHex);
        String label = mPending.remove(key);
        if (label == null) {
            return false;
        }
        authorize(key, label);
        return true;
    }

    /** An authorized device revokes a device (authorized or pending). Identity is untouched. */
    public synchronized boolean revoke(byte[] zRevokerKeyDer, String zTargetKeyHex) {
        if (!isAuthorized(zRevokerKeyDer)) {
            return false;
        }
        String key = normalizeHex(zTargetKeyHex);
        boolean removed = mAuthorized.remove(key) != null;
        removed |= mPending.remove(key) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    /** Generate a fresh single-use bootstrap code (for adding a device without an existing one
     *  to approve it). Written 0600; the operator reads it over ssh. */
    public synchronized String newBootstrapCode() {
        String code = randomCode();
        writeCode(code);
        return code;
    }

    /** Ensure a bootstrap code exists while no device is paired (first-run onboarding). */
    public synchronized void ensureBootstrapCode() {
        if (mAuthorized.isEmpty() && currentCode() == null) {
            writeCode(randomCode());
        }
    }

    public synchronized boolean hasBootstrapCode() {
        return currentCode() != null;
    }

    public Path codeFile() {
        return mCodeFile;
    }

    // ---- internals ----

    private void authorize(String key, String label) {
        mAuthorized.put(key, new Device(key, label, System.currentTimeMillis()));
        mPending.remove(key);
        save();
    }

    private String currentCode() {
        try {
            if (Files.exists(mCodeFile)) {
                String s = new String(Files.readAllBytes(mCodeFile), StandardCharsets.UTF_8).trim();
                return s.isEmpty() ? null : s;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void consumeCode() {
        try {
            Files.deleteIfExists(mCodeFile);
        } catch (Exception ignored) {
        }
    }

    private void writeCode(String code) {
        try {
            Files.write(mCodeFile, code.getBytes(StandardCharsets.UTF_8));
            try {
                Files.setPosixFilePermissions(mCodeFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
            }
        } catch (Exception ignored) {
        }
    }

    private static String randomCode() {
        // Human-typeable: 3 groups of 4 from an unambiguous alphabet (no 0/O/1/I).
        final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < 3; g++) {
            if (g > 0) {
                sb.append('-');
            }
            for (int i = 0; i < 4; i++) {
                sb.append(ALPHABET.charAt(RAND.nextInt(ALPHABET.length())));
            }
        }
        return sb.toString();
    }

    private static String normalize(String code) {
        return code == null ? "" : code.replace("-", "").trim().toUpperCase();
    }

    private static String hex(byte[] der) {
        return new MiniData(der).to0xString();
    }

    /** Canonical key form, matching {@link MiniData#to0xString()}: a lowercase {@code 0x}
     *  prefix followed by UPPERCASE hex. Everything (store, isAuthorized, approve, revoke,
     *  load) must use this one form or keys silently fail to match. */
    private static String normalizeHex(String h) {
        if (h == null) {
            return "";
        }
        String s = h.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        return "0x" + s.toUpperCase();
    }

    private void load() {
        try {
            if (!Files.exists(mFile)) {
                return;
            }
            JSONObject o = (JSONObject) new JSONParser().parse(
                    new String(Files.readAllBytes(mFile), StandardCharsets.UTF_8));
            Object auth = o.get("authorized");
            if (auth instanceof JSONArray) {
                for (Object e : (JSONArray) auth) {
                    JSONObject d = (JSONObject) e;
                    String key = normalizeHex(String.valueOf(d.get("key")));
                    String label = String.valueOf(d.getOrDefault("label", "device"));
                    long at = d.get("pairedAt") == null ? 0L
                            : Long.parseLong(String.valueOf(d.get("pairedAt")));
                    Device dev = new Device(key, label, at);
                    Object apns = d.get("apns");
                    if (apns instanceof JSONObject) {
                        JSONObject a = (JSONObject) apns;
                        dev.apnsToken = String.valueOf(a.getOrDefault("token", ""));
                        dev.apnsEnv = String.valueOf(a.getOrDefault("env", ""));
                        dev.wakeProxy = String.valueOf(a.getOrDefault("proxy", ""));
                        try { dev.apnsUpdated = Long.parseLong(String.valueOf(a.getOrDefault("updated", "0"))); }
                        catch (Exception ignored) { }
                    }
                    mAuthorized.put(key, dev);
                }
            }
            Object pend = o.get("pending");
            if (pend instanceof JSONArray) {
                for (Object e : (JSONArray) pend) {
                    JSONObject d = (JSONObject) e;
                    mPending.put(normalizeHex(String.valueOf(d.get("key"))),
                            String.valueOf(d.getOrDefault("label", "device")));
                }
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private void save() {
        try {
            JSONArray auth = new JSONArray();
            for (Device d : mAuthorized.values()) {
                JSONObject o = new JSONObject();
                o.put("key", d.key);
                o.put("label", d.label);
                o.put("pairedAt", d.pairedAt);
                if (!d.apnsToken.isEmpty() || !d.wakeProxy.isEmpty()) {
                    JSONObject a = new JSONObject();
                    a.put("token", d.apnsToken);
                    a.put("env", d.apnsEnv);
                    a.put("proxy", d.wakeProxy);
                    a.put("updated", d.apnsUpdated);
                    o.put("apns", a);
                }
                auth.add(o);
            }
            JSONArray pend = new JSONArray();
            for (Map.Entry<String, String> e : mPending.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("key", e.getKey());
                o.put("label", e.getValue());
                pend.add(o);
            }
            JSONObject root = new JSONObject();
            root.put("authorized", auth);
            root.put("pending", pend);
            Files.write(mFile, root.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.setPosixFilePermissions(mFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
            }
        } catch (Exception ignored) {
        }
    }
}
