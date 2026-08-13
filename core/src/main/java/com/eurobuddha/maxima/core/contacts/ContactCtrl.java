package com.eurobuddha.maxima.core.contacts;

import com.eurobuddha.maxima.core.rpc.Capabilities;
import com.eurobuddha.maxima.core.util.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The {@code **maxima_contact_ctrl**} payload - a flat JSON object carried as
 * the UTF-8 bytes of a Maxima message.
 *
 * Classic keys, reproduced exactly (a classic receiver reads these and ignores
 * anything else):
 * <pre>
 *   delete, intro, publickey, address, name, icon,
 *   minimaaddress, topblock, checkblock, checkhash, mls
 * </pre>
 *
 * We add two OPTIONAL keys. A classic node drops unknown keys when it builds
 * its extradata, so these are invisible to classic peers and cost nothing:
 * <pre>
 *   mxcaps    capability list  (see Capabilities)
 *   mxaddrs   comma-separated ADDITIONAL addresses, for multi-homing
 * </pre>
 * `address` always carries our best single address so a classic peer still has
 * somewhere to send. That ordering matters: never rely on `mxaddrs` alone.
 *
 * The receiver's hard rule, which we also enforce: the {@code publickey} inside
 * the JSON must equal the {@code from} of the carrying message. Without that,
 * anyone could introduce themselves as anyone.
 */
public final class ContactCtrl {

    public static final String APPLICATION = "**maxima_contact_ctrl**";

    public static final String KEY_ADDRS = "mxaddrs";

    private ContactCtrl() {
    }

    /**
     * Build an introduction / update.
     *
     * @param zIntro true asks the peer to reciprocate
     */
    public static String build(String zPublicKeyHex,
                               List<String> zMyAddresses,
                               String zName,
                               String zIcon,
                               String zMinimaAddress,
                               String zMls,
                               Capabilities zCaps,
                               boolean zIntro) {

        String primary = zMyAddresses.isEmpty() ? "" : zMyAddresses.get(0);
        String extra = "";
        if (zMyAddresses.size() > 1) {
            extra = String.join(",", zMyAddresses.subList(1, zMyAddresses.size()));
        }

        Json.Writer w = new Json.Writer()
                .put("delete", false)
                .put("intro", zIntro)
                .put("publickey", zPublicKeyHex)
                .put("address", primary)
                .put("name", sanitise(zName))
                .put("icon", sanitise(zIcon))
                .put("minimaaddress", zMinimaAddress == null ? "" : zMinimaAddress)
                .put("topblock", "0")
                .put("checkblock", "0")
                .put("checkhash", "0x00")
                .put("mls", zMls == null ? "" : zMls);

        if (!zCaps.isClassic()) {
            w.put(Capabilities.KEY, zCaps.encode());
        }
        if (!extra.isEmpty()) {
            w.put(KEY_ADDRS, extra);
        }
        return w.done();
    }

    /**
     * The delete variant. The reference zeroes every field rather than omitting
     * them, so we do the same.
     */
    public static String buildDelete(String zPublicKeyHex) {
        return new Json.Writer()
                .put("delete", true)
                .put("intro", false)
                .put("publickey", zPublicKeyHex)
                .put("address", "")
                .put("name", "")
                .put("icon", "")
                .put("minimaaddress", "Mx00")
                .put("topblock", "0")
                .put("checkblock", "0")
                .put("checkhash", "0x00")
                .put("mls", "")
                .done();
    }

    /** The result of reading an inbound contact-ctrl message. */
    public static final class Parsed {
        public final Contact contact;
        public final boolean delete;
        public final boolean intro;

        Parsed(Contact zContact, boolean zDelete, boolean zIntro) {
            contact = zContact;
            delete = zDelete;
            intro = zIntro;
        }
    }

    /**
     * @param zFromHex the carrying message's {@code from}, uppercase 0x hex
     * @throws IllegalArgumentException if the declared key does not match the
     *                                  signer - the anti-impersonation check
     */
    public static Parsed parse(String zJson, String zFromHex) {
        Map<String, String> m = Json.parse(zJson);

        String declared = m.get("publickey");
        if (declared == null) {
            throw new IllegalArgumentException("contact ctrl has no publickey");
        }
        if (!declared.equalsIgnoreCase(zFromHex)) {
            throw new IllegalArgumentException(
                    "contact publickey does not match the message signer");
        }

        boolean delete = "true".equalsIgnoreCase(m.getOrDefault("delete", "false"));
        boolean intro = "true".equalsIgnoreCase(m.getOrDefault("intro", "false"));

        Contact c = new Contact(declared.toUpperCase().replace("0X", "0x"));

        List<String> addrs = new ArrayList<>();
        String primary = m.get("address");
        if (primary != null && !primary.isEmpty()) {
            addrs.add(primary);
        }
        String extra = m.get(KEY_ADDRS);
        if (extra != null && !extra.trim().isEmpty()) {
            for (String a : extra.split(",")) {
                String t = a.trim();
                if (!t.isEmpty() && !addrs.contains(t)) {
                    addrs.add(t);
                }
            }
        }
        c.setAddresses(addrs);

        c.name = m.getOrDefault("name", "noname");
        c.icon = m.getOrDefault("icon", "0x00");
        c.minimaAddress = m.getOrDefault("minimaaddress", "");
        c.mls = m.getOrDefault("mls", "");
        c.topBlock = m.getOrDefault("topblock", "0");
        c.checkBlock = m.getOrDefault("checkblock", "0");
        c.checkHash = m.getOrDefault("checkhash", "0x00");
        c.capabilities = Capabilities.decode(m.get(Capabilities.KEY));
        c.lastSeen = System.currentTimeMillis();

        return new Parsed(c, delete, intro);
    }

    /** The reference strips these from name and icon; matching it avoids surprises. */
    private static String sanitise(String zValue) {
        if (zValue == null) {
            return "";
        }
        return zValue.replace("\"", "").replace("'", "").replace(";", "");
    }

    public static List<String> allAddresses(Contact zContact) {
        return new ArrayList<>(zContact.addresses);
    }

    public static List<String> splitCsv(String zCsv) {
        if (zCsv == null || zCsv.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(zCsv.split(",")));
    }
}
