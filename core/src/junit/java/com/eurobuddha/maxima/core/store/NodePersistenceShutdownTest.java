package com.eurobuddha.maxima.core.store;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import static org.junit.Assert.*;

public class NodePersistenceShutdownTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void failedFinalFlushStillClosesTheNodeWorkers() throws Exception {
        Path dir = tmp.newFolder("store").toPath();
        FileStore store = new FileStore(dir.toFile());
        store.setWriteBehind(true);
        MaximaNode node = new MaximaNode(MaximaIdentity.fromPhrase(Bip39.generate(24)), "1.0.48", 0);
        node.setStore(store); // no start, no network, fresh identity
        store.put("settings", "test", "pending");
        Path target = Files.createDirectory(dir.resolve("settings.tsv"));
        Files.write(target.resolve("blocker"), new byte[]{1});
        try {
            assertThrows(UncheckedIOException.class, node::stop);
            for (String name : new String[]{"mInboundExec", "mRpcExec", "mSideExec"}) {
                Field field = MaximaNode.class.getDeclaredField(name);
                field.setAccessible(true);
                assertTrue(name, ((ExecutorService) field.get(node)).isShutdown());
            }
        } finally {
            Files.delete(target.resolve("blocker")); Files.delete(target);
            store.flush();
            node.stop();
        }
        assertEquals("pending", new FileStore(dir.toFile()).get("settings", "test"));
    }
}
