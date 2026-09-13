package com.eurobuddha.maxima.core.net;

import java.io.*;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Optional private transfer handoff on the already reachable Maxima port.
 * No handler is installed unless its owner explicitly starts a transfer. A 256-bit
 * capability is delivered through sealed, authenticated RPC, never a public directory.
 * The existing listener retains socket ownership, connection caps and shutdown cleanup. */
public final class PrivateStreams {
    public static final int TYPE = 203;
    public interface Handler { void serve(Socket socket, DataInputStream in, DataOutputStream out) throws Exception; }
    private static final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    public static void register(String token, Handler h) {
        if(!token.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid stream token");
        handlers.put(token,h);
    }
    public static void remove(String token) { handlers.remove(token); }
    public static void serve(byte[] frame, Socket socket, DataInputStream in, DataOutputStream out) throws Exception {
        if(frame.length!=65) return;
        String token=new String(frame,1,64,java.nio.charset.StandardCharsets.US_ASCII);
        Handler h=handlers.get(token);
        if(h!=null)h.serve(socket,in,out);
    }
    private PrivateStreams() { }
}
