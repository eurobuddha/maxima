package com.eurobuddha.maxima.files;

import com.eurobuddha.maxima.core.chat.ChatFile;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, idempotent owner-device upload/download commands, shared by every frontend.
 * The host MUST authenticate the caller before dispatch. Never exposes filesystem paths. */
public final class FileCommands implements AutoCloseable {
    public static final String METHOD="parlons.files";
    private final PrivateFiles files;
    private final Path staging;
    private final Map<String,Upload> uploads=new ConcurrentHashMap<>();
    private final ExecutorService work=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),
            r->{Thread t=new Thread(r,"private-file-import");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    private static final class Upload {
        final Path path;final long size;final String name,mime,peer;final boolean group;
        long offset;volatile String fileId="",error="";volatile boolean finishing;volatile long touched=System.currentTimeMillis();
        Upload(Path p,Map<String,String> m){path=p;size=Long.parseLong(m.get("size"));name=m.get("name");mime=m.getOrDefault("mime","application/octet-stream");peer=m.get("peer");group=Boolean.parseBoolean(m.get("group"));}
    }
    public FileCommands(PrivateFiles files,Path staging)throws IOException {this.files=files;this.staging=staging;Files.createDirectories(staging);
        try(java.util.stream.Stream<Path> leftovers=Files.list(staging)) {
            for(Path p:(Iterable<Path>)leftovers::iterator)if(p.getFileName().toString().matches("[a-f0-9]{32}") && Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))Files.delete(p);
        }
    }
    private static Map<String,String> result(String... values){Map<String,String> m=new LinkedHashMap<>();m.put("ok","true");for(int i=0;i<values.length;i+=2)m.put(values[i],values[i+1]);return m;}
    public Map<String,String> call(Map<String,String> in)throws Exception {
        if(closed)throw new IOException("File sharing stopped");
        String action=in.getOrDefault("action",""),id=in.getOrDefault("id","");
        switch(action){
            case "begin":{
                synchronized(uploads){
                    sweep();
                    if(!id.matches("[a-f0-9]{32}"))throw new IOException("Invalid upload id");
                    Upload old=uploads.get(id);if(old!=null){
                        if(!Long.toString(old.size).equals(in.get("size")) || !Objects.equals(old.name,in.get("name"))
                                || !Objects.equals(old.peer,in.get("peer")) || old.group!=Boolean.parseBoolean(in.get("group"))
                                || !Objects.equals(old.mime,in.getOrDefault("mime","application/octet-stream")))throw new IOException("Conflicting upload retry");
                        return result("id",id,"offset",Long.toString(old.offset));
                    }
                    Upload u=new Upload(staging.resolve(id),in);
                    String zero="0000000000000000000000000000000000000000000000000000000000000000";
                    new ChatFile(id,u.name,u.mime,u.size,zero,"0000000000000000",zero,"");
                    if(u.size<0 || u.size>ChatFile.MAX_BYTES || u.name==null || u.name.isEmpty() || u.name.length()>240 || u.peer==null)throw new IOException("Invalid file (512 MB maximum)");
                    long pending=uploads.values().stream().filter(x->x.fileId.isEmpty()).mapToLong(x->x.size).sum();
                    if(uploads.size()>=8 || pending+u.size>ChatFile.MAX_BYTES || staging.toFile().getUsableSpace()<u.size*3+32*1024*1024)
                        throw new IOException("Finish or cancel another upload first");
                    Files.createFile(u.path);try{Files.setPosixFilePermissions(u.path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
                    uploads.put(id,u);return result("id",id,"offset","0");
                }
            }
            case "append":{
                Upload u=upload(id);String encoded=in.getOrDefault("data","");if(encoded.length()>PrivateFiles.BLOCK*4/3+4)throw new IOException("Upload block too large");
                byte[] data=Base64.getDecoder().decode(encoded);long offset=Long.parseLong(in.get("offset"));
                synchronized(u){u.touched=System.currentTimeMillis();if(u.finishing)throw new IOException("Upload already submitted");
                    if(data.length==0 || offset<0 || offset>u.offset || offset+data.length>u.size)throw new IOException("Invalid upload offset");
                    try(RandomAccessFile file=new RandomAccessFile(u.path.toFile(),"rw")){
                        file.seek(offset);
                        if(offset<u.offset){byte[] prior=new byte[data.length];if(offset+data.length>u.offset)throw new IOException("Partial duplicate upload");file.readFully(prior);
                            if(!Arrays.equals(prior,data))throw new IOException("Conflicting upload retry");}
                        else{file.write(data);u.offset+=data.length;}
                    }return result("id",id,"offset",Long.toString(u.offset));
                }
            }
            case "finish":{
                Upload u=upload(id);synchronized(u){if(u.offset!=u.size)throw new IOException("Upload is incomplete");
                    if(!u.finishing){u.finishing=true;
                        try{work.execute(()->{try{u.fileId=files.send(Files.newInputStream(u.path),u.size,u.name,u.mime,u.peer,u.group);}
                            catch(Exception e){u.error=e.getMessage()==null?"Could not prepare file":e.getMessage();}
                            finally{u.touched=System.currentTimeMillis();try{Files.deleteIfExists(u.path);}catch(Exception ignored){}}});}
                        catch(RejectedExecutionException e){u.finishing=false;throw new IOException("Busy; retry shortly");}
                    }return result("id",id,"status","Preparing");}
            }
            case "status":{
                Upload u=uploads.get(id);if(u!=null){if(!u.error.isEmpty())return result("id",id,"status","Failed","error",u.error);
                    if(u.fileId.isEmpty())return result("id",id,"status",u.finishing?"Preparing":"Uploading","done",Long.toString(u.offset),"size",Long.toString(u.size),"ready","false");
                    Map<String,String> m=files.status(u.fileId);m.put("fileId",u.fileId);m.put("ok","true");return m;}
                Map<String,String> m=files.status(id);m.put("ok","true");return m;
            }
            case "download":return result("id",files.receive(in.get("ref"),in.get("peer"),Boolean.parseBoolean(in.get("group"))));
            case "pause":files.pause(id);return result();
            case "resume":files.resume(id);return result();
            case "remove":{Upload u=uploads.get(id);if(u!=null && !u.error.isEmpty()){uploads.remove(id);return result();}files.remove(id);return result();}
            case "cancelUpload":{Upload u=upload(id);synchronized(u){if(u.finishing)throw new IOException("Preparation already started");uploads.remove(id);Files.deleteIfExists(u.path);}return result();}
            case "read":{
                long offset=Long.parseLong(in.getOrDefault("offset","0"));
                long size=Long.parseLong(files.status(id).get("size"));if(offset<0||offset>size)throw new IOException("Invalid download offset");
                byte[] data=new byte[(int)Math.min(PrivateFiles.BLOCK,size-offset)];
                try(InputStream stream=files.open(id)){long skipped=0;while(skipped<offset){long n=stream.skip(offset-skipped);if(n<=0)throw new EOFException();skipped+=n;}
                    new DataInputStream(stream).readFully(data);}
                return result("data",Base64.getEncoder().encodeToString(data),"next",Long.toString(offset+data.length),"size",Long.toString(size),"name",files.name(id));
            }
            case "list":{
                StringBuilder json=new StringBuilder("[");for(Map<String,String> m:files.list()){
                    if(json.length()>1)json.append(',');com.eurobuddha.maxima.core.util.Json.Writer w=new com.eurobuddha.maxima.core.util.Json.Writer();m.forEach(w::put);json.append(w.done());}
                return result("transfers",json.append(']').toString());
            }
            default:throw new IOException("Unknown file action");
        }
    }
    private Upload upload(String id)throws IOException{Upload u=uploads.get(id);if(u==null)throw new IOException("Upload expired; choose the file again");return u;}
    private void sweep(){long now=System.currentTimeMillis();uploads.entrySet().removeIf(e->{Upload u=e.getValue();if(!u.finishing && now-u.touched>30*60*1000L){try{Files.deleteIfExists(u.path);}catch(Exception ignored){}return true;}
        return (!u.fileId.isEmpty() || !u.error.isEmpty()) && now-u.touched>30*60*1000L;});}
    @Override public void close(){closed=true;work.shutdownNow();for(Upload u:uploads.values())try{Files.deleteIfExists(u.path);}catch(Exception ignored){}uploads.clear();}
}
