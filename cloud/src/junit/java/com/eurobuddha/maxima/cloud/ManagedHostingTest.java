package com.eurobuddha.maxima.cloud;

import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.rpc.ServiceRegistry;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Synthetic identities only; fleet relays, direct listeners and wallets are disabled. */
public class ManagedHostingTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private ParlonsCore.Config config() {
        ParlonsCore.Config c = new ParlonsCore.Config();
        c.builtInRelays = false; c.relayPort = 0; c.directPort = 0; c.panelPort = 0;
        return c;
    }
    private JSONObject request(String action, String name) {
        JSONObject r = new JSONObject(); r.put("action", action); r.put("name", name); return r;
    }
    @Test public void hostedFileTicketUsesOwnerListenerButOnlyTenantPairing() throws Exception {
        Path data = temp.newFolder("direct-tenant").toPath(); String phrase = Tenants.phraseFor(data, null);
        ParlonsCore.Config cfg = config();
        java.util.concurrent.atomic.AtomicReference<String> address = new java.util.concurrent.atomic.AtomicReference<>("192.0.2.1:9501");
        cfg.fileSources = () -> Collections.singletonList(address.get());
        ParlonsCore tenant = ManagedHosting.chatAccount(MaximaIdentity.fromNodeSecret(phrase), data, cfg, phrase);
        byte[] paired = {1,2,3}, stranger = {4,5,6};
        try {
            tenant.pairing().authorizeLocal(paired, "synthetic device", true);
            ServiceRegistry registry = tenant.node().services();
            String method = "parlons.files.direct";
            assertThrows(SecurityException.class, () -> registry.dispatchLocal(new ServiceRegistry.Request(method,new byte[0],stranger,Collections.emptyList())));
            String reply = new String(registry.dispatchLocal(new ServiceRegistry.Request(method,new byte[0],paired,Collections.emptyList())),StandardCharsets.UTF_8);
            assertEquals(address.get(),com.eurobuddha.maxima.core.util.Json.parse(reply).get("sources"));
            address.set("192.0.2.2:9501");
            assertEquals(Collections.singletonList(address.get()),tenant.directFileSources());
        } finally { tenant.shutdown(); }
    }
    @Test public void hostingIsOptInPasswordGatedAndPreservesAccountsWhenStopped() throws Exception {
        Path data = temp.getRoot().toPath(); AdminPairingPassword password = new AdminPairingPassword(data);
        try (ManagedHosting host = new ManagedHosting(data, config(), password)) {
            assertEquals(false, host.handle(request("list", "")).get("enabled"));
            assertThrows(IllegalStateException.class, () -> host.handle(request("enable", "")));
            assertThrows(IllegalStateException.class, () -> host.handle(request("create", "alice")));
            password.set("synthetic password".toCharArray());
            assertEquals(true, host.handle(request("enable", "")).get("enabled"));
            long until = System.currentTimeMillis() + 7000;
            while (!Boolean.TRUE.equals(host.handle(request("list", "")).get("online")) && System.currentTimeMillis() < until) Thread.sleep(20);
            host.handle(request("create", "alice"));
            Files.writeString(data.resolve("hosted-chat/alice/keep.txt"), "preserve");
            assertEquals(false, host.handle(request("disable", "")).get("online"));
            assertEquals("preserve", Files.readString(data.resolve("hosted-chat/alice/keep.txt")));
            assertThrows(IllegalStateException.class, () -> host.handle(request("invite", "alice")));
        }
        try (ManagedHosting again = new ManagedHosting(data, config(), password)) {
            assertEquals(false, again.handle(request("list", "")).get("enabled"));
            assertEquals("chat-only", again.handle(request("list", "")).get("access"));
        }
    }
    @Test public void anotherHostCannotStartOnTheSameDirectory() throws Exception {
        Path data = temp.getRoot().toPath(); AdminPairingPassword password = new AdminPairingPassword(data);
        try (ManagedHosting host = new ManagedHosting(data, config(), password)) {
            assertThrows(java.nio.channels.OverlappingFileLockException.class,
                    () -> new ManagedHosting(data, config(), password));
        }
        try (ManagedHosting again = new ManagedHosting(data, config(), password)) {
            assertNotNull(again.handle(request("list", "")));
        }
    }
    @Test public void unavailableHostingDoesNotDisableTheOwnerAccount() throws Exception {
        Path data = temp.getRoot().toPath(); String phrase = Tenants.phraseFor(data, null);
        AdminPairingPassword password = new AdminPairingPassword(data);
        ParlonsCore core = ManagedHosting.chatAccount(MaximaIdentity.fromNodeSecret(phrase), data, config(), phrase);
        byte[] device = {8, 7, 6}; core.pairing().authorizeLocal(device, "fixture", true);
        try (ManagedHosting busy = new ManagedHosting(data, config(), password)) {
            core.control().setAdminPairingPassword(password);
            core.enableManagedHosting(); // another process owns the hosting directory
            JSONObject status = (JSONObject)new JSONParser().parse(new String(core.node().services().dispatchLocal(
                    new ServiceRegistry.Request("parlons.node.status", "{}".getBytes(StandardCharsets.UTF_8), device, Collections.emptyList())), StandardCharsets.UTF_8));
            assertEquals(true, status.get("ok")); assertEquals(false, status.get("hostedAccounts"));
            assertFalse(String.valueOf(status.get("hostingError")).isEmpty());
        } finally { core.shutdown(); }
    }
    @Test public void tenantInvitePairsOnlyTenantAndCannotReachWalletOrOwnerControls() throws Exception {
        Path data = temp.newFolder("tenant").toPath(); String phrase = Tenants.phraseFor(data, null);
        ParlonsCore tenant = ManagedHosting.chatAccount(MaximaIdentity.fromNodeSecret(phrase), data, config(), phrase);
        DevicePairing owner = new DevicePairing(temp.newFolder("owner").toPath());
        byte[] device = {4, 3, 2, 1};
        try {
            String code = tenant.pairing().newBootstrapCode();
            assertEquals(DevicePairing.Result.AUTHORIZED, tenant.pairing().requestPair(device, "guest phone", code));
            assertFalse(owner.isAuthorized(device));
            ServiceRegistry registry = tenant.node().services();
            for (String method : Arrays.asList("parlons.wallet.address", "parlons.wallet.send", "parlons.chat.pay",
                    "parlons.node.cmd", "parlons.hosted.accounts", "parlons.nft.put", "parlons.identity.import")) {
                assertThrows(method, SecurityException.class, () -> registry.dispatchLocal(
                        new ServiceRegistry.Request(method, "{}".getBytes(StandardCharsets.UTF_8), device, Collections.emptyList())));
            }
            JSONObject status = (JSONObject)new JSONParser().parse(new String(registry.dispatchLocal(
                    new ServiceRegistry.Request("parlons.node.status", "{}".getBytes(StandardCharsets.UTF_8), device, Collections.emptyList())), StandardCharsets.UTF_8));
            assertEquals(true, status.get("chatOnly")); assertEquals(false, status.get("nodeConsole"));
            assertEquals(false, status.get("hostedAccounts")); assertEquals(false, status.get("adminPairingPassword"));
            registry.dispatchLocal(new ServiceRegistry.Request("parlons.chat.summaries", "{}".getBytes(StandardCharsets.UTF_8), device, Collections.emptyList()));
        } finally { tenant.shutdown(); }
        assertFalse(Files.exists(data.resolve("wallet")));
    }
    @Test public void sharedHostRestartsTheSameIdentityAndReusesOutstandingInvitation() throws Exception {
        Path root = temp.newFolder("guests").toPath(); Path alice = Files.createDirectory(root.resolve("alice"));
        Tenants.AccountFactory factory = (id, dir, cfg, phrase) -> {
            ParlonsCore core = ManagedHosting.chatAccount(id, dir, cfg, phrase);
            core.node().setStaticMls("MxTEST@127.0.0.1:1"); return core;
        };
        Tenants.Host host = new Tenants.Host(root, config(), null, factory);
        String first;
        try {
            host.poll(); host.poll();
            HostedAccounts manager = new HostedAccounts(root, 2);
            first = (String)manager.handle(request("invite", "alice")).get("invite");
            assertEquals(first, manager.handle(request("invite", "alice")).get("invite"));
            manager.handle(request("pause", "alice")); host.poll(); host.publish();
            assertTrue(host.cores.isEmpty());
            manager.handle(request("resume", "alice")); host.poll(); host.poll();
            assertEquals(first, manager.handle(request("invite", "alice")).get("invite"));
        } finally { host.shutdownAll(); }
        String phrase = Files.readString(alice.resolve("seed.txt"));
        Tenants.Host restarted = new Tenants.Host(root, config(), null, factory);
        try {
            restarted.poll(); restarted.poll();
            assertEquals(phrase, Files.readString(alice.resolve("seed.txt")));
            assertEquals(first, new HostedAccounts(root, 2).handle(request("invite", "alice")).get("invite"));
        } finally { restarted.shutdownAll(); }
        assertFalse(Files.exists(root.resolve(HostedAccounts.STATUS_FILE)));
    }
}
