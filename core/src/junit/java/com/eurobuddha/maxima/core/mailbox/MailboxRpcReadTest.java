package com.eurobuddha.maxima.core.mailbox;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.directory.MlsStore;
import com.eurobuddha.maxima.core.identity.*;
import com.eurobuddha.maxima.core.rpc.ServiceRegistry;
import com.eurobuddha.maxima.core.services.Tier1Services;
import com.eurobuddha.maxima.core.store.FileStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.Assert.*;

/** The RPC mailbox must preserve the same safe prefix as the relay's signed-ACK path. */
public class MailboxRpcReadTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    @Test public void unreadableFirstRecordBlocksLaterRpcRows() throws Exception { recover(1); }
    @Test public void unreadableMiddleRecordLeavesTheRpcPrefixUsable() throws Exception { recover(2); }

    @Test public void aDelayedRpcAcknowledgementCannotClearNewMail() throws Exception {
        MaximaIdentity owner = MaximaIdentity.fromPhrase(Bip39.generate(24));
        Mailbox mailbox = new Mailbox(); mailbox.setStore(new FileStore(tmp.newFolder()));
        ServiceRegistry registry = new ServiceRegistry();
        new Tier1Services(owner, mailbox, new MlsStore()).registerAll(registry);
        mailbox.store(owner.publicKeyHex(), new byte[]{1});
        String old = call(registry, owner, Tier1Services.MAILBOX_FETCH, "0").split("\\|", 2)[0];
        assertEquals("1", call(registry, owner, Tier1Services.MAILBOX_ACK, old));
        mailbox.store(owner.publicKeyHex(), new byte[]{2});
        assertEquals("0", call(registry, owner, Tier1Services.MAILBOX_ACK, old));
        String[] next = call(registry, owner, Tier1Services.MAILBOX_FETCH, old).split("\\|", 2);
        assertEquals(2, next.length); assertEquals("0x02", next[1]);
        assertTrue(Long.parseLong(next[0]) > Long.parseLong(old));
        assertEquals("1", call(registry, owner, Tier1Services.MAILBOX_ACK, next[0]));
    }

    private void recover(int blockedSeq) throws Exception {
        MaximaIdentity owner = MaximaIdentity.fromPhrase(Bip39.generate(24));
        String key = owner.publicKeyHex();
        Path dir = tmp.newFolder("mail").toPath(); FileStore store = new FileStore(dir.toFile());
        Mailbox mailbox = new Mailbox(); mailbox.setStore(store);
        for (int i = 1; i <= 3; i++) assertEquals(Mailbox.Result.STORED, mailbox.store(key, new byte[]{(byte) i}));
        ServiceRegistry registry = new ServiceRegistry();
        new Tier1Services(owner, mailbox, new MlsStore()).registerAll(registry);
        String record = store.listBytes("mailitems").keySet().stream()
                .filter(k -> k.split("\\|", 4)[1].equals(Integer.toString(blockedSeq))).findFirst().get();
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(record.getBytes(StandardCharsets.UTF_8));
        StringBuilder filename = new StringBuilder();
        for (byte b : hash) filename.append(String.format("%02x", b));
        Path target = dir.resolve("mailitems.d").resolve(filename.toString());
        Path saved = tmp.newFolder("saved").toPath().resolve("record");
        Files.move(target, saved); Files.createDirectory(target);
        String fetched = call(registry, owner, Tier1Services.MAILBOX_FETCH, "0");
        if (!fetched.isEmpty()) {
            String[] rows = fetched.split("\n");
            String sequence = rows[rows.length - 1].split("\\|", 2)[0];
            call(registry, owner, Tier1Services.MAILBOX_ACK, sequence);
        }
        assertEquals("a cumulative RPC ACK cannot remove the unread record", 4 - blockedSeq, mailbox.count(key));
        assertEquals(blockedSeq == 1 ? "" : "1|0x01", fetched);
        Files.delete(target); Files.move(saved, target);
        fetched = call(registry, owner, Tier1Services.MAILBOX_FETCH, "0");
        StringBuilder expected = new StringBuilder();
        for (int i = blockedSeq; i <= 3; i++) {
            if (expected.length() > 0) expected.append('\n');
            expected.append(i).append('|').append(new MiniData(new byte[]{(byte) i}).to0xString());
        }
        assertEquals(expected.toString(), fetched);
        call(registry, owner, Tier1Services.MAILBOX_ACK, "3");
        assertTrue(new FileStore(dir.toFile()).listBytes("mailitems").isEmpty());
    }

    private static String call(ServiceRegistry registry, MaximaIdentity owner, String method, String payload) throws Exception {
        // Request metadata is the already-verified sender, as in production RPC dispatch.
        return new String(registry.dispatchLocal(new ServiceRegistry.Request(method,
                payload.getBytes(StandardCharsets.UTF_8), owner.publicKey(), java.util.Collections.emptyList())), StandardCharsets.UTF_8);
    }
}
