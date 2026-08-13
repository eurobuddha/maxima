package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.rpc.Capabilities;
import com.eurobuddha.maxima.core.store.FileStore;
import com.eurobuddha.maxima.core.store.Store;

import java.io.File;
import java.util.Arrays;

/**
 * PERSISTENCE GATE.
 *
 * Until this passed, a relay lost every held message when systemd restarted it
 * and a phone forgot its contacts on reboot. Both are silent failures - nothing
 * errors, the data is simply gone - so they need an explicit test.
 */
public class StoreTest {

    static int pass = 0, fail = 0;

    static void ok(String m) { pass++; System.out.println("  ok  " + m); }
    static void bad(String m) { fail++; System.out.println("  XX  " + m); }

    static void wipe(File d) {
        if (d.exists()) {
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) f.delete();
        }
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "maxima-storetest");
        wipe(dir);

        // The classic hand-rolled-line-format bug: a tab or newline in a value
        // corrupts every record after it.
        Store s = new FileStore(dir);
        String nasty = "line1\nline2\twith tab\\and backslash\r end";
        s.put("t", "k", nasty);
        if (nasty.equals(new FileStore(dir).get("t", "k"))) {
            ok("tabs, newlines and backslashes survive a round trip");
        } else {
            bad("escaping broken");
        }

        s.put("c", "a", "alpha");
        s.put("c", "b", "beta");
        s.remove("c", "a");
        Store re = new FileStore(dir);
        if ("beta".equals(re.get("c", "b")) && re.get("c", "a") == null) {
            ok("keyed records persist and removals stick");
        } else {
            bad("keyed persistence broken");
        }

        s.append("l", "one");
        s.append("l", "two");
        if (new FileStore(dir).read("l").equals(Arrays.asList("one", "two"))) {
            ok("append log persists in order");
        } else {
            bad("append log broken");
        }

        // A collection name must never escape the data directory.
        s.put("../../evil", "k", "v");
        File escaped = new File(dir.getParentFile(), "evil.tsv");
        if (!escaped.exists()) {
            ok("collection names cannot escape the data directory");
        } else {
            bad("PATH TRAVERSAL - wrote outside the data dir");
            escaped.delete();
        }

        // ---- the real question: does a NODE survive a restart? ----
        File nd = new File(System.getProperty("java.io.tmpdir"), "maxima-nodetest");
        wipe(nd);

        byte[] e = new byte[32];
        for (int i = 0; i < 32; i++) e[i] = (byte) (i + 3);
        MaximaIdentity id = MaximaIdentity.fromPhrase(Bip39.fromEntropy(e));

        MaximaNode n1 = new MaximaNode(id, "1.0.48", 1);
        n1.setStore(new FileStore(nd));
        n1.setName("before-restart");
        n1.setStaticMls("MxABC@1.2.3.4:9001");
        n1.setIcon("0xFF");
        n1.setAllowAllContacts(false);

        Contact c = new Contact("0x30819FDEADBEEF");
        c.name = "alice";
        c.setAddresses(Arrays.asList("MxAAA@1.1.1.1:9001", "MxBBB@2.2.2.2:9001"));
        c.capabilities = Capabilities.phoneDefaults();
        n1.storeContact(c);
        n1.stop();

        MaximaNode n2 = new MaximaNode(id, "1.0.48", 1);
        n2.setStore(new FileStore(nd));

        Contact back = n2.contact("0x30819FDEADBEEF");
        if (back != null && "alice".equals(back.name)
                && back.addresses.size() == 2 && !back.capabilities.isClassic()) {
            ok("contacts survive a restart with all addresses and capabilities");
        } else {
            bad("contact did not survive: " + back);
        }
        if (n2.isStaticMls()) ok("pinned MLS survives a restart");
        else bad("static MLS lost");
        if ("0xFF".equals(n2.icon())) ok("icon survives a restart");
        else bad("icon lost");
        if (!n2.allowAllContacts()) ok("allow-all-contacts setting survives a restart");
        else bad("allowall lost");

        System.out.println();
        System.out.println("=====================================");
        System.out.println("  PASSED: " + pass + "   FAILED: " + fail);
        System.out.println("=====================================");
        if (fail > 0) System.exit(1);
        System.out.println("Nothing is lost across a restart.");
    }
}
