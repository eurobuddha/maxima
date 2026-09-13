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
    @Override protected void configure() { }
    @Provides @Singleton
    IConnectionHandlerFactory connections(IHandshakeFactory handshake, TorrentRegistry torrents, Config config) {
        return new ConnectionHandlerFactory(handshake, torrents,
                Collections.singletonList(new BitfieldConnectionHandler(torrents)), config.getPeerHandshakeTimeout());
    }
}
