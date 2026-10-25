package com.eurobuddha.maxima.files;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/** Platform transport for an already authorised, explicitly accepted private swarm.
 * Only signalling goes through Maxima. The local gateway checks capability and infohash.
 * Implementations must bound buffering and must never use TURN or a payload relay. */
public interface PeerDataTransport extends AutoCloseable {
    void connect(String id, String peer, boolean offerer, int gateway, String localToken,
                 String remoteToken, BiConsumer<String,String> signal, IntConsumer ready);
    void signal(String id, String peer, Map<String,String> signal);
    void disconnect(String id, String peer);
    void stop(String id);
    void close();
}
