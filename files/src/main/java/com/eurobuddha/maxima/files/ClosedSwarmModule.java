package com.eurobuddha.maxima.files;

import bt.net.BitfieldConnectionHandler;
import bt.net.ConnectionHandlerFactory;
import bt.net.IConnectionHandlerFactory;
import bt.protocol.IHandshakeFactory;
import bt.runtime.Config;
import bt.torrent.TorrentRegistry;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import java.util.Collections;

/** Reuses ProtocolModule's connection factory with its bitfield handler only.
 * The upstream default always emits a BEP-10 handshake (including a loopback port),
 * even when standard extensions and the remote extension bits are disabled. */
final class ClosedSwarmModule extends AbstractModule {
    private final java.util.function.Consumer<bt.metainfo.TorrentId> stopped;
    ClosedSwarmModule(java.util.function.Consumer<bt.metainfo.TorrentId> stopped) { this.stopped=stopped; }
    @Override protected void configure() { }
    @Provides @Singleton
    bt.event.EventBus events() {
        return new bt.event.EventBus() {
            @Override public void fireTorrentStopped(bt.metainfo.TorrentId id) {
                // EventBus removes per-torrent listeners AFTER notifying them. A restart
                // must wait until that cleanup has returned or its listeners can be lost.
                super.fireTorrentStopped(id);
                if(id!=null)stopped.accept(id);
            }
        };
    }
    @Provides @Singleton
    IConnectionHandlerFactory connections(IHandshakeFactory handshake, TorrentRegistry torrents, Config config) {
        return new ConnectionHandlerFactory(handshake, torrents,
                Collections.singletonList(new BitfieldConnectionHandler(torrents)), config.getPeerHandshakeTimeout());
    }
}
