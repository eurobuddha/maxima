package com.eurobuddha.maxima.desktop;

import com.eurobuddha.maxima.desktop.ui.DesktopNode;
import com.eurobuddha.maxima.cloud.*;
import com.eurobuddha.maxima.core.*;
import com.eurobuddha.maxima.core.identity.*;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.desktoplinks.MinimaDocsLink;
import org.json.JSONObject;
import java.nio.file.*;
import java.util.concurrent.*;

/** Disposable real host adapters, local encrypted transport, no wallet or public relay. */
public final class DocsHostsFixture {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); Files.createDirectories(root);
        System.setProperty("java.util.prefs.userRoot", root.resolve("prefs").toString());
        Path a = Files.createDirectories(root.resolve("desktop")), b = Files.createDirectories(root.resolve("core"));
        Files.writeString(a.resolve("relays-builtin.txt"), "off");
        DesktopNode desktop = new DesktopNode(MaximaIdentity.fromPhrase(Bip39.fromEntropy(new byte[32])), a, "Desktop test account");
        byte[] entropy = new byte[32]; entropy[0] = 1;
        ParlonsCore.Config cfg = new ParlonsCore.Config(); cfg.builtInRelays = false; cfg.relayPort = 0; cfg.directPort = 0; cfg.displayName = "Core test account";
        ParlonsCore core = new ParlonsCore(MaximaIdentity.fromPhrase(Bip39.fromEntropy(entropy)), b, cfg, null, null);
        MaximaNode left = desktop.node(), right = core.node();
        left.startDirect(0); right.startDirect(0);
        Contact lc = new Contact(right.publicKeyHex()); lc.name = "Same contact name"; lc.addresses.add(right.identity().mxIdentity() + "@127.0.0.1:" + right.directPort()); left.storeContact(lc);
        Contact rc = new Contact(left.publicKeyHex()); rc.name = "Same contact name"; rc.addresses.add(left.identity().mxIdentity() + "@127.0.0.1:" + left.directPort()); right.storeContact(rc);
        desktop.docsLink().start(); core.docsLink().start();
        ScheduledExecutorService pump = Executors.newSingleThreadScheduledExecutor();
        pump.scheduleWithFixedDelay(() -> { left.flushOutbox(); right.flushOutbox(); }, 0, 100, TimeUnit.MILLISECONDS);
        byte[] panelKey = new byte[32]; panelKey[0] = 7;
        core.pairing().authorizeLocal(panelKey, "Test panel", true);
        ParlonsLocal panel = new ParlonsLocal(core.node().services(), panelKey, b, 0, () -> "", core.pairing(), null, line -> {});
        panel.setDocsLink(core.docsLink()); panel.start();
        try (java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
            System.out.println("DOCS_FIXTURE " + new JSONObject().put("desktop", desktop.docsLink().approve()).put("core", core.docsLink().approve()).put("panel", panel.fileTicketUrl()));
            String command;
            while ((command = input.readLine()) != null && !"quit".equals(command)) {
                if ("revoke desktop".equals(command)) desktop.docsLink().revoke();
                if ("revoke core".equals(command)) core.docsLink().revoke();
                System.out.println("DOCS_DONE");
            }
        } finally { panel.stop(); pump.shutdownNow(); desktop.shutdown(); core.shutdown(); }
    }
}
