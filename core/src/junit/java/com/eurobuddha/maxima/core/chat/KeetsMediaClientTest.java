package com.eurobuddha.maxima.core.chat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class KeetsMediaClientTest {
    private static final String TOKEN="a".repeat(64),URL="keets://"+"b".repeat(64)+"/private/test?hash="+"c".repeat(64);
    @Test public void sendsBoundToSidecarAndEnforcesStreamingLimit()throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger hits=new AtomicInteger();
        server.createContext("/media",exchange->{
            assertEquals(TOKEN,exchange.getRequestHeaders().getFirst("x-keets-token"));
            assertTrue(exchange.getRequestURI().getRawQuery().contains("maxbytes=4"));
            hits.incrementAndGet();exchange.sendResponseHeaders(200,0);
            try(OutputStream out=exchange.getResponseBody()){out.write(new byte[5]);}
        });server.start();
        try {
            KeetsMediaClient client=new KeetsMediaClient(server.getAddress().getPort(),TOKEN);
            try{client.fetch(URL,4);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("size limit"));}
            assertEquals(1,hits.get());
        }finally{server.stop(0);}
    }
    @Test public void redirectsNeverReceiveNodeCapability()throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger redirected=new AtomicInteger();
        server.createContext("/media",exchange->{exchange.getResponseHeaders().set("Location","/secret");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/secret",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        try{
            try{new KeetsMediaClient(server.getAddress().getPort(),TOKEN).fetch(URL,4);fail();}catch(IOException expected){}
            assertEquals(0,redirected.get());
        }finally{server.stop(0);}
    }
    @Test public void mediaAboveBroadcastLimitUsesAuthenticatedBoundedUpload()throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] payload=new byte[200000];java.util.Arrays.fill(payload,(byte)17);
        String hash=com.eurobuddha.maxima.files.FileCrypto.hex(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
        String url="keets://"+"b".repeat(64)+"/parlons/img-test.jpg?hash="+hash;
        server.createContext("/media/parlons/img-test.jpg",exchange->{
            assertEquals("POST",exchange.getRequestMethod());assertEquals(TOKEN,exchange.getRequestHeaders().getFirst("x-keets-token"));
            assertArrayEquals(payload,exchange.getRequestBody().readAllBytes());
            byte[] response=("{\"url\":\""+url+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);try(OutputStream out=exchange.getResponseBody()){out.write(response);}
        });server.start();
        try{assertEquals(url,new KeetsMediaClient(server.getAddress().getPort(),TOKEN).publish("/parlons/img-test.jpg",payload));}
        finally{server.stop(0);}
    }

}
