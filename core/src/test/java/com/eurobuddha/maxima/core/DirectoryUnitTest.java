package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.contacts.ContactCtrl;
import com.eurobuddha.maxima.core.directory.MlsStore;
import com.eurobuddha.maxima.core.rpc.Capabilities;
import com.eurobuddha.maxima.core.util.Json;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Contacts, the MLS directory, and the JSON codec underneath them.
 *
 * The contact-ctrl JSON is on the wire (classic peers read it), so its shape is
 * a compatibility surface; the directory's authorisation and TTL are our own
 * additions and are where a hostile lookup would try to walk the social graph.
 */
public class DirectoryUnitTest {

    static int pass = 0, fail = 0;
    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    public static void main(String[] args) throws Exception {
        System.out.println("=== CONTACTS + MLS + JSON ===\n");

        // ---- Json primitives ----
        String esc = Json.escape("a\"b\\c\nd\te");
        if (esc.equals("a\\\"b\\\\c\\nd\\te")) {
            ok("Json.escape escapes quote, backslash, newline and tab");
        } else {
            bad("Json.escape: " + esc);
        }
        String j = new Json.Writer()
                .put("name", "alice \"the\" dev")
                .put("intro", true)
                .put("n", "42")
                .done();
        Map<String, String> parsed = Json.parse(j);
        if (parsed.get("name").equals("alice \"the\" dev")
                && parsed.get("intro").equals("true")
                && parsed.get("n").equals("42")) {
            ok("Json.Writer + parse round-trips strings (incl. embedded quotes) and booleans");
        } else {
            bad("Json round trip: " + parsed);
        }

        // ---- ContactCtrl build/parse round trip ----
        String myKey = "0x" + "AB".repeat(81); // 162-byte-ish hex stand-in
        List<String> myAddrs = Arrays.asList(
                "Mxprimary@1.2.3.4:9501", "Mxsecond@5.6.7.8:9501", "Mxthird@9.9.9.9:9501");
        String json = ContactCtrl.build(myKey, myAddrs, "Alice", "0x00",
                "", "Mxmls@1.2.3.4:9501", Capabilities.phoneDefaults(), true);
        ContactCtrl.Parsed p = ContactCtrl.parse(json, myKey);
        if (p != null && !p.delete && p.intro) {
            ok("a built contact-ctrl parses back as a non-delete intro");
        } else {
            bad("contact parse flags");
        }
        if (p.contact.name.equals("Alice")) {
            ok("the contact name survives the round trip");
        } else {
            bad("contact name: " + p.contact.name);
        }
        // multi-homing: all three published addresses must come back
        List<String> addrs = ContactCtrl.allAddresses(p.contact);
        if (addrs.size() == 3 && addrs.contains("Mxsecond@5.6.7.8:9501")) {
            ok("all three multi-homed addresses survive (primary + mxaddrs extras)");
        } else {
            bad("multi-home addresses: " + addrs);
        }
        // capability discovery: phone defaults are non-classic and carry the mailbox cap
        if (!p.contact.isClassic() && p.contact.capabilities.has(Capabilities.MAILBOX)) {
            ok("advertised capabilities are read back (peer is non-classic, has mailbox)");
        } else {
            bad("capabilities not read back");
        }
        // a classic contact (no caps blob) reads as classic
        String classicJson = ContactCtrl.build(myKey,
                Collections.singletonList("Mxprimary@1.2.3.4:9501"),
                "Bob", "0x00", "", "", Capabilities.none(), false);
        Contact classic = ContactCtrl.parse(classicJson, myKey).contact;
        if (classic.isClassic()) {
            ok("a contact with no capability blob reads as classic (automatic downgrade)");
        } else {
            bad("classic detection");
        }
        // delete variant
        ContactCtrl.Parsed del = ContactCtrl.parse(ContactCtrl.buildDelete(myKey), myKey);
        if (del.delete) {
            ok("the delete variant parses as a delete");
        } else {
            bad("delete variant");
        }

        // ---- MlsStore authorisation + TTL ----
        String target = "0x" + "11".repeat(81);
        String reader = "0x" + "22".repeat(81);
        String stranger = "0x" + "33".repeat(81);
        MlsStore store = new MlsStore();
        store.put(target, Collections.singletonList("Mxtarget@1.2.3.4:9501"),
                Collections.singletonList(reader));
        if (store.get(target, reader) != null) {
            ok("an authorised reader can resolve the entry");
        } else {
            bad("authorised reader denied");
        }
        if (store.get(target, stranger) == null) {
            ok("a stranger NOT on the allow-list cannot resolve it (no graph-walking)");
        } else {
            bad("stranger resolved a private entry");
        }
        if (store.get("0x" + "99".repeat(81), reader) == null) {
            ok("an unknown target resolves to null, not an exception");
        } else {
            bad("unknown target");
        }
        // TTL expiry: a zero-lifetime store expires immediately
        MlsStore ttl = new MlsStore(0L);
        ttl.put(target, Collections.singletonList("Mxx@1.2.3.4:9501"),
                Collections.singletonList(reader));
        Thread.sleep(2);
        if (ttl.get(target, reader) == null) {
            ok("an entry past its TTL is not returned (stale directory data expires)");
        } else {
            bad("expired entry still returned");
        }
        // address cap
        MlsStore cap = new MlsStore();
        String[] many = new String[20];
        for (int i = 0; i < 20; i++) many[i] = "Mxaddr" + i + "@1.2.3.4:9501";
        cap.put(target, Arrays.asList(many), Collections.singletonList(reader));
        MlsStore.Entry e = cap.get(target, reader);
        if (e.addresses.size() <= MlsStore.MAX_ADDRESSES) {
            ok("a SET is capped at MAX_ADDRESSES (" + e.addresses.size()
                    + " <= " + MlsStore.MAX_ADDRESSES + "), not an unbounded blob");
        } else {
            bad("address cap: " + e.addresses.size());
        }
        // permanent (our own well-known relays) bypass the allow-list
        MlsStore perm = new MlsStore();
        perm.addPermanent(target);
        perm.put(target, Collections.singletonList("Mxpub@1.2.3.4:9501"),
                Collections.<String>emptyList());
        if (perm.get(target, stranger) != null) {
            ok("a permanent (public) entry is resolvable by anyone");
        } else {
            bad("permanent entry not public");
        }

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("Contacts + MLS + JSON hold.");
    }
}
