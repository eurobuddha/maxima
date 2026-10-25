package com.eurobuddha.maxima.core.chat;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Native adaptation of panel/private-files.js's tested idempotent upload flow.
 * A failed call never cancels accepted bytes. Reuse this object and reopen the same
 * source to resume; only explicit cancellation should call cancelUpload. */
public final class FileUpload {
    public static final int BLOCK = 48 * 1024;
    public interface Backend { Map<String,String> call(Map<String,String> parameters) throws Exception; }
    private final Backend backend;
    private final Map<String,String> begin;
    private final Map<Long,byte[]> chunks = new HashMap<>();
    public final String id, name;
    public final long size;

    public FileUpload(Backend backend,String id,String name,String mime,long size,String peer,boolean group) throws IOException {
        if(size<0 || size>ChatFile.TRANSFER_MAX_BYTES)throw new IOException("Choose a local file up to 100 MB");
        this.backend=backend;this.id=id;this.name=name;this.size=size;
        begin=params("begin","name",name,"mime",mime,"size",Long.toString(size),"peer",peer,"group",Boolean.toString(group));
    }
    public void run(InputStream source,BooleanSupplier cancelled,LongConsumer progress) throws Exception {
        try(InputStream in=source) {
            if(in==null)throw new IOException("Cannot open file");
            long offset=offset(retry(begin,cancelled));
            if(offset<0 || offset>size)throw new IOException("Invalid upload offset");
            // Reopened Android documents can change. Check the retained chunk hashes
            // before resuming so an old prefix cannot be joined to a different file.
            byte[] buffer=new byte[BLOCK];long skipped=0;
            while(skipped<offset) {
                check(cancelled);int n=(int)Math.min(buffer.length,offset-skipped);
                new DataInputStream(in).readFully(buffer,0,n);
                byte[] expected=chunks.get(skipped);
                if(expected==null || !java.security.MessageDigest.isEqual(expected,hash(buffer,n)))
                    throw new IOException("File changed while preparing it");
                skipped+=n;
            }
            progress.accept(offset);
            while(offset<size) {
                check(cancelled);int n=(int)Math.min(buffer.length,size-offset);
                new DataInputStream(in).readFully(buffer,0,n);
                byte[] digest=hash(buffer,n), previous=chunks.putIfAbsent(offset,digest);
                if(previous!=null && !java.security.MessageDigest.isEqual(previous,digest))
                    throw new IOException("File changed while preparing it");
                Map<String,String> response=retry(params("append","offset",Long.toString(offset),
                        "data",Base64.getEncoder().encodeToString(Arrays.copyOf(buffer,n))),cancelled);
                long next=offset(response);
                if(next!=offset+n)throw new IOException("Invalid upload offset");
                offset=next;progress.accept(offset);
            }
            if(in.read()!=-1)throw new IOException("File changed while preparing it");
            retry(params("finish"),cancelled);
        }
    }
    public void cancel() throws Exception { call(params("cancelUpload")); }
    private static byte[] hash(byte[] bytes,int count)throws Exception {
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        digest.update(bytes,0,count);return digest.digest();
    }
    private Map<String,String> retry(Map<String,String> request,BooleanSupplier cancelled) throws Exception {
        for(int attempt=0;;attempt++) {
            check(cancelled);
            try{return call(request);}
            catch(Exception e) {
                check(cancelled);
                if(attempt==2)throw e;
                Thread.sleep(250L*(attempt+1));
            }
        }
    }
    private Map<String,String> call(Map<String,String> request)throws Exception {
        Map<String,String> response=backend.call(request);
        if(response==null)throw new IOException("Empty upload response");
        if("false".equals(response.get("ok")))throw new IOException(response.getOrDefault("error","Transfer failed"));
        return response;
    }
    private static long offset(Map<String,String> response)throws IOException {
        try{return Long.parseLong(response.get("offset"));}
        catch(Exception e){throw new IOException("Invalid upload offset");}
    }
    private Map<String,String> params(String action,String... args) {
        Map<String,String> p=new LinkedHashMap<>();p.put("action",action);p.put("id",id);
        for(int i=0;i<args.length;i+=2)p.put(args[i],args[i+1]);return p;
    }
    private static void check(BooleanSupplier cancelled)throws InterruptedIOException {
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new InterruptedIOException("Upload paused");
    }
}
