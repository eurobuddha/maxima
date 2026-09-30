package com.eurobuddha.maxima.cloud;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.JSONArray;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Operator metadata only, synthetic accounts, no live identity or network. */
public class HostedAccountsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path root;
    private HostedAccounts manager;
    @Before public void setUp() throws Exception {
        root = temp.getRoot().toPath(); manager = new HostedAccounts(root, 2);
        HostedAccounts.publishStatus(root, Collections.emptyList(), Collections.emptyList());
    }
    private JSONObject call(String action, String name) throws Exception {
        JSONObject in = new JSONObject(); in.put("action", action); in.put("name", name);
        return manager.handle(in);
    }
    @Test public void createRetryPreservesExistingAccountAndEnforcesCapacity() throws Exception {
        call("create", "alice"); Files.writeString(root.resolve("alice/seed.txt"), "synthetic existing identity");
        call("create", "alice"); assertEquals("synthetic existing identity", Files.readString(root.resolve("alice/seed.txt")));
        call("create", "bob");
        assertThrows(IllegalStateException.class, () -> call("create", "carol"));
        assertFalse(Files.exists(root.resolve("carol")));
        String list = call("list", "").toString();
        assertFalse(list.contains("synthetic")); assertFalse(list.contains("seed.txt"));
    }
    @Test public void invalidNamesAndSymlinksCannotReachOutsideHostingRoot() throws Exception {
        for (String name : Arrays.asList("../owner", "/root", ".hidden", "alice/bob", "x\nY", "a".repeat(65)))
            assertThrows(IllegalArgumentException.class, () -> call("create", name));
        Path outside = temp.newFolder("outside").toPath();
        Files.createSymbolicLink(root.resolve("linked"), outside);
        assertThrows(IllegalArgumentException.class, () -> call("pause", "linked"));
        assertFalse(Files.exists(outside.resolve(".stop")));
        assertFalse(call("list", "").toString().contains("linked"));
    }
    @Test public void offlineAndStaleHostCannotCreateOrIssueInvitations() throws Exception {
        Files.delete(root.resolve(HostedAccounts.STATUS_FILE));
        assertEquals(false, call("list", "").get("online"));
        assertThrows(IllegalStateException.class, () -> call("create", "alice"));
        Files.writeString(root.resolve(HostedAccounts.STATUS_FILE), "{\"updated\":1,\"running\":[\"alice\"]}");
        assertEquals(false, call("list", "").get("online"));
        assertThrows(IllegalStateException.class, () -> call("invite", "alice"));
    }
    @Test public void invitationsUseCurrentFilesAndReuseCodeOnTransportRetry() throws Exception {
        call("create", "alice"); Path dir = root.resolve("alice");
        assertThrows(IllegalStateException.class, () -> call("invite", "alice"));
        HostedAccounts.publishStatus(root, Arrays.asList("alice"), Collections.emptyList());
        Files.writeString(dir.resolve("account.txt"), "MAX#synthetic#MxTEST@192.0.2.1:8001");
        Files.writeString(dir.resolve("invite.txt"), "stale invite");
        JSONObject first = call("invite", "alice"), retry = call("invite", "alice");
        assertEquals(first.get("invite"), retry.get("invite"));
        assertFalse(first.toString().contains("stale"));
        assertFalse(call("list", "").toString().contains((String)first.get("code")));
        Files.delete(dir.resolve("pair-code.txt"));
        assertNotEquals(first.get("code"), call("invite", "alice").get("code"));
    }
    @Test public void pauseAndResumeKeepIdentityAndRefusePausedInvites() throws Exception {
        call("create", "alice"); Path dir = root.resolve("alice");
        Files.writeString(dir.resolve("seed.txt"), "synthetic");
        HostedAccounts.publishStatus(root, Arrays.asList("alice"), Collections.emptyList());
        JSONObject paused = call("pause", "alice");
        assertEquals("pausing", ((JSONObject)((JSONArray)paused.get("accounts")).get(0)).get("state"));
        assertThrows(IllegalStateException.class, () -> call("invite", "alice"));
        HostedAccounts.publishStatus(root, Collections.emptyList(), Collections.emptyList());
        assertEquals("paused", ((JSONObject)((JSONArray)call("list", "").get("accounts")).get(0)).get("state"));
        call("resume", "alice");
        assertFalse(Files.exists(dir.resolve(".stop"))); assertEquals("synthetic", Files.readString(dir.resolve("seed.txt")));
    }
}
