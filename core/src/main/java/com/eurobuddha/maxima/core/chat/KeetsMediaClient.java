package com.eurobuddha.maxima.core.chat;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

/** Bounded, authenticated loopback media read, adapted from Salon's KeetsLink.Session.
 * The size bound is enforced by the sidecar before allocation and again by the client.
 * Never follows redirects or sends the node capability to a remote host. */
public final class KeetsMediaClient {
    private final int port;
    private final String token;
    public KeetsMediaClient(int port, String token) throws IOException {
        if (port < 1024 || port > 65535 || token == null || !token.matches("[a-f0-9]{64}"))
            throw new IOException("Keets is starting; try again shortly");
        this.port = port; this.token = token;
    }
    public static KeetsMediaClient local(int port, String dataDirectory) throws IOException {
        Path file = Paths.get(dataDirectory, "keets", "apitoken");
        if (!file.isAbsolute() || Files.size(file) > 128) throw new IOException("Invalid Keets node directory");
        return new KeetsMediaClient(port, new String(Files.readAllBytes(file), StandardCharsets.US_ASCII).trim());
    }
    public byte[] fetch(String url, int max) throws Exception {
        if (!KeetsTransport.validRef(url) || max < 1 || max > 8 * 1024 * 1024)
            throw new IOException("Invalid Keets media request");
        String route = "/media?url=" + URLEncoder.encode(url, "UTF-8") + "&maxbytes=" + max;
        return request("GET",route,null,max);
    }
    /** Existing photo/voice bridge can also avoid oversized Android broadcasts. */
    public String publish(String path,byte[] bytes) throws Exception {
        if(path==null || !path.matches("/parlons/[A-Za-z0-9._-]+") || path.contains("..")
                || bytes==null || bytes.length<1 || bytes.length>8*1024*1024)
            throw new IOException("Invalid Keets media upload");
        byte[] response=request("POST","/media"+path,bytes,4096);
        String url=com.eurobuddha.maxima.core.util.Json.parse(new String(response,StandardCharsets.UTF_8)).get("url");
        String hash=com.eurobuddha.maxima.files.FileCrypto.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        if(url==null || !url.matches("keets://[a-f0-9]{64}"+java.util.regex.Pattern.quote(path+"?hash="+hash)))
            throw new IOException("Keets did not confirm the media upload");
        return url;
    }
    public void sharing(ChatFile file,boolean enabled)throws Exception {
        com.eurobuddha.maxima.files.KeetsFiles.validate(file);
        byte[] body=new com.eurobuddha.maxima.core.util.Json.Writer().put("url",file.torrent).put("sharing",Boolean.toString(enabled)).done().getBytes(StandardCharsets.UTF_8);
        java.util.Map<String,String> r=com.eurobuddha.maxima.core.util.Json.parse(new String(request("POST","/private-sharing",body,4096),StandardCharsets.UTF_8));
        if(!Boolean.toString(enabled).equals(r.get("sharing")))throw new IOException("Node did not confirm file sharing state");
    }
    public void forget(ChatFile file)throws Exception {
        com.eurobuddha.maxima.files.KeetsFiles.validate(file);
        byte[] body=new com.eurobuddha.maxima.core.util.Json.Writer().put("url",file.torrent).done().getBytes(StandardCharsets.UTF_8);
        java.util.Map<String,String> r=com.eurobuddha.maxima.core.util.Json.parse(new String(request("POST","/private-forget",body,4096),StandardCharsets.UTF_8));
        if(!"true".equals(r.get("forgotten")))throw new IOException("Node did not confirm file removal");
    }
    private byte[] request(String method,String route,byte[] body,int max) throws Exception {
        HttpURLConnection con = (HttpURLConnection)new URL("http", "127.0.0.1", port, route).openConnection(Proxy.NO_PROXY);
        Timer watchdog = new Timer("KeetsMediaDeadline", true);
        watchdog.schedule(new TimerTask() { public void run() { con.disconnect(); } }, 50_000);
        try {
            con.setInstanceFollowRedirects(false);con.setConnectTimeout(5000);con.setReadTimeout(40_000);
            con.setRequestMethod(method);
            con.setRequestProperty("x-keets-token", token);con.setRequestProperty("Accept-Encoding", "identity");
            if(body!=null){
                con.setDoOutput(true);con.setFixedLengthStreamingMode(body.length);
                try(OutputStream out=con.getOutputStream()){out.write(body);}
            }
            int code = con.getResponseCode();
            if (code == 401) throw new IOException("Keets restarted; tap Resume");
            if (code != 200) throw new IOException(sidecarError(con, "File block unavailable; keep both nodes online and tap Resume"));
            if (con.getContentLengthLong() > max) throw new IOException("Keets media exceeds size limit");
            try (InputStream in = con.getInputStream()) { return read(in, max); }
        } finally { watchdog.cancel();con.disconnect(); }
    }
    /** The sidecar's own reason ({"error":"..."}, at most 4 KB), so the file sheet says why a
     *  block failed instead of one text for every cause; the generic text when there is none. */
    private static String sidecarError(HttpURLConnection con, String fallback) {
        try (InputStream err = con.getErrorStream()) {
            if (err == null) return fallback;
            String msg = com.eurobuddha.maxima.core.util.Json.parse(new String(read(err, 4096), StandardCharsets.UTF_8)).get("error");
            return msg == null || msg.isEmpty() ? fallback : msg;
        } catch (Exception e) { return fallback; }
    }
    public static byte[] read(InputStream in, int max) throws IOException {
        if (in == null) throw new IOException("File unavailable");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new InterruptedIOException("Transfer paused");
            if (n > max - out.size()) throw new IOException("Keets media exceeds size limit");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
