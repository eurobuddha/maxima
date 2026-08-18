package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.Group;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.media.MediaService;
import com.eurobuddha.maxima.core.session.Bootstrap;
import com.eurobuddha.maxima.core.session.RelayGossipClient;
import com.eurobuddha.maxima.core.store.BlobStore;
import com.eurobuddha.maxima.core.store.FileStore;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The desktop chat client's node — the same {@link MaximaNode} + {@link ChatEngine}
 * the phone runs in {@code MaximaService}, wired for a desktop process: persistent
 * stores under the data dir, a message pump, pool maintenance, relay discovery via
 * gossip, and a fan-out listener the Swing panels subscribe to.
 *
 * It shares the machine's Maxima identity (the seed under the data dir) so the
 * desktop is ONE identity whether it is relaying or chatting.
 */
public final class DesktopNode {

    private static final String PROTOCOL = "1.0.48";
    private static final int RELAY_TARGET = 4;

    private final MaximaNode mNode;
    private final ChatEngine mChat;
    private final MediaService mMedia;
    private final RelayGossipClient mGossip;
    private final MaximaIdentity mIdentity;

    private Thread mPump;
    private volatile boolean mRunning = true;
    private ScheduledExecutorService mMaint;

    private final List<Runnable> mChangeListeners = new CopyOnWriteArrayList<>();
    private final List<ChatEngine.Listener> mChatListeners = new CopyOnWriteArrayList<>();

    public DesktopNode(MaximaIdentity zId, Path zDataDir, String zDisplayName) {
        mIdentity = zId;
        mNode = new MaximaNode(zId, PROTOCOL, RELAY_TARGET);
        mGossip = new RelayGossipClient(zId, PROTOCOL, 8);

        File base = zDataDir.toFile();
        mNode.setStore(new FileStore(new File(base, "node")));
        mNode.setName(zDisplayName);
        mNode.setNodeKind("core");   // an always-on desktop is a core node

        BlobStore blobs = new BlobStore(new File(base, "media"), 512L * 1024 * 1024);
        mMedia = new MediaService(mNode, blobs);
        mNode.setLocalBlobs(blobs);

        mChat = new ChatEngine(mNode);
        mChat.setStore(new FileStore(new File(base, "chat")));
        mChat.setMediaService(mMedia);
        mChat.setListener(new ChatEngine.Listener() {
            public void onMessage(ChatEngine.Entry e) {
                for (ChatEngine.Listener l : mChatListeners) {
                    try { l.onMessage(e); } catch (Exception ignored) { }
                }
                fireChanged();
            }

            public void onStateChanged(ChatEngine.Entry e) {
                for (ChatEngine.Listener l : mChatListeners) {
                    try { l.onStateChanged(e); } catch (Exception ignored) { }
                }
                fireChanged();
            }

            public void onGroupChanged(Group g) {
                for (ChatEngine.Listener l : mChatListeners) {
                    try { l.onGroupChanged(g); } catch (Exception ignored) { }
                }
                fireChanged();
            }
        });

        // Route inbound chat traffic into the engine (the phone does the same).
        mNode.setMessageListener((msg, msgid) -> {
            try {
                mChat.onInbound(msg);
            } catch (Exception ignored) {
            }
        });
    }

    public MaximaNode node()       { return mNode; }
    public ChatEngine chat()       { return mChat; }
    public MediaService media()    { return mMedia; }
    public MaximaIdentity identity() { return mIdentity; }

    /** Subscribe to "something changed" — fired on any inbound/state/group event. */
    public void addChangeListener(Runnable r) { mChangeListeners.add(r); }
    public void removeChangeListener(Runnable r) { mChangeListeners.remove(r); }
    public void addChatListener(ChatEngine.Listener l) { mChatListeners.add(l); }

    private void fireChanged() {
        for (Runnable r : mChangeListeners) {
            try { r.run(); } catch (Exception ignored) { }
        }
    }

    /** Attach to the bootstrap relays and start pumping. Blocks briefly on attach. */
    public int start() {
        int attached = mNode.start(Bootstrap.RELAYS, 30_000);
        startPump();
        return attached;
    }

    private void startPump() {
        mPump = new Thread(() -> {
            while (mRunning) {
                boolean any = false;
                for (String hp : mNode.pool().activeHosts()) {
                    try {
                        any |= mNode.pump(hp, 1500);
                    } catch (Exception ignored) {
                    }
                }
                if (any) {
                    // Inbound was drained on the pool thread; nudge the UI.
                    fireChanged();
                } else {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "maxima-desktop-pump");
        mPump.setDaemon(true);
        mPump.start();

        mMaint = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "maxima-desktop-maint");
            t.setDaemon(true);
            return t;
        });
        mMaint.scheduleWithFixedDelay(() -> {
            try {
                mNode.maintain(20_000);
            } catch (Exception ignored) {
            }
            fireChanged();
        }, 20, 20, TimeUnit.SECONDS);
        mMaint.scheduleWithFixedDelay(() -> {
            try {
                mGossip.tick(mNode);
            } catch (Exception ignored) {
            }
        }, 15, 60, TimeUnit.SECONDS);
        // Resend anything that didn't get a delivery receipt.
        mMaint.scheduleWithFixedDelay(() -> {
            try {
                mChat.resendUndelivered();
            } catch (Exception ignored) {
            }
        }, 30, 45, TimeUnit.SECONDS);
    }

    public void setReadReceipts(boolean on) {
        mChat.setSendReadReceipts(on);
    }

    public void shutdown() {
        mRunning = false;
        if (mPump != null) {
            mPump.interrupt();
        }
        if (mMaint != null) {
            mMaint.shutdownNow();
        }
        try {
            mChat.close();
        } catch (Exception ignored) {
        }
        try {
            mNode.stop();
        } catch (Exception ignored) {
        }
    }
}
