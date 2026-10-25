package com.eurobuddha.maxima.files;

import com.eurobuddha.maxima.core.chat.*;
import com.eurobuddha.maxima.core.net.DirectEndpoint;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DirectFileChannelTest {
    private static Map<String,String> map(String... pairs){Map<String,String> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put(pairs[i],pairs[i+1]);return m;}
    @Test(timeout=90000) public void hundredMiBUploadAndDownloadUseOneDirectSocket()throws Exception {
        try(PrivateFilesTest.Fixture f=new PrivateFilesTest.Fixture();DirectFileChannel host=new DirectFileChannel()){
            DirectEndpoint endpoint=new DirectEndpoint(f.node.identity(),"1.0.48",ignored->{});
            int port=endpoint.start(0);assertTrue(port>0);
            try{
                Map<String,String> ticket=host.issue("device",Collections.singletonList("127.0.0.1:"+port),()->true,f.commands::call);
                try(DirectFileChannel.Client client=DirectFileChannel.Client.connect(ticket,true)){
                    long size=ChatFile.TRANSFER_MAX_BYTES;
                    String id=FileCrypto.randomHex(16);
                    InputStream input=new InputStream(){long n;public int read(){return n==size?-1:(int)((n++*7)&255);}
                        public int read(byte[] b,int off,int len){if(n==size)return -1;int count=(int)Math.min(len,size-n);for(int i=0;i<count;i++)b[off+i]=(byte)((n++*7)&255);return count;}};
                    FileUpload upload=new FileUpload(client::call,id,"100MiB.bin","application/octet-stream",size,f.peer.publicKeyHex(),false);
                    upload.run(input,()->false,n->{});
                    Map<String,String> status;long deadline=System.currentTimeMillis()+30000;
                    do{status=client.call(map("action","status","id",id));if("true".equals(status.get("ready")))break;Thread.sleep(50);}while(System.currentTimeMillis()<deadline);
                    assertEquals(status.toString(),"true",status.get("ready"));
                    String file=status.get("fileId");long offset=0;
                    while(offset<size){
                        Map<String,String> reply=client.call(map("action","read","id",file,"offset",Long.toString(offset)));
                        byte[] bytes=Base64.getDecoder().decode(reply.get("data"));assertTrue(bytes.length>0);
                        for(byte b:bytes)assertEquals((byte)((offset++*7)&255),b);
                        assertEquals(offset,Long.parseLong(reply.get("next")));
                    }
                    assertEquals(size,offset);
                }
            }finally{endpoint.stop();}
        }
    }
    @Test(timeout=15000) public void revokedAndReplayedTicketsCannotExecuteCommands()throws Exception {
        try(PrivateFilesTest.Fixture f=new PrivateFilesTest.Fixture();DirectFileChannel host=new DirectFileChannel()){
            DirectEndpoint endpoint=new DirectEndpoint(f.node.identity(),"1.0.48",ignored->{});int port=endpoint.start(0);
            AtomicBoolean allowed=new AtomicBoolean(true);AtomicInteger calls=new AtomicInteger();
            try{
                Map<String,String> ticket=host.issue("device",Collections.singletonList("127.0.0.1:"+port),allowed::get,in->{calls.incrementAndGet();return map("ok","true");});
                try(DirectFileChannel.Client client=DirectFileChannel.Client.connect(ticket,true)){
                    assertEquals("true",client.call(map("action","list")).get("ok"));
                    try(DirectFileChannel.Client replay=DirectFileChannel.Client.connect(ticket,true)){fail("Ticket reused");}catch(IOException expected){}
                    allowed.set(false);
                    try{client.call(map("action","list"));fail("Revoked device used channel");}catch(IOException expected){}
                    assertEquals(1,calls.get());
                }
            }finally{endpoint.stop();}
        }
    }
    private byte[] record(byte[] key)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DirectFileChannel.Records r=new DirectFileChannel.Records(new DataInputStream(new ByteArrayInputStream(new byte[0])),new DataOutputStream(bytes),key,key);
        r.write(new byte[]{1,2,3});return bytes.toByteArray();
    }
    private DirectFileChannel.Records reader(byte[] bytes,byte[] key){return new DirectFileChannel.Records(new DataInputStream(new ByteArrayInputStream(bytes)),new DataOutputStream(new ByteArrayOutputStream()),key,key);}
    @Test public void recordsRejectTamperingReplayWrongKeyAndOversizedAllocation()throws Exception {
        byte[] key=new byte[32];new Random(7).nextBytes(key);byte[] bytes=record(key);
        assertArrayEquals(new byte[]{1,2,3},reader(bytes,key).read());
        byte[] changed=bytes.clone();changed[changed.length-1]^=1;
        try{reader(changed,key).read();fail();}catch(IOException expected){}
        try{reader(bytes,new byte[32]).read();fail();}catch(IOException expected){}
        byte[] twice=new byte[bytes.length*2];System.arraycopy(bytes,0,twice,0,bytes.length);System.arraycopy(bytes,0,twice,bytes.length,bytes.length);
        DirectFileChannel.Records replay=reader(twice,key);replay.read();try{replay.read();fail();}catch(IOException expected){}
        try{reader(new byte[]{127,-1,-1,-1},key).read();fail();}catch(IOException expected){}
        try{reader(bytes,key).write(new byte[DirectFileChannel.MAX_RECORD+1]);fail();}catch(IOException expected){}
    }
    @Test public void ticketsRequireAuthorityReachabilityAndBoundedAdmission()throws Exception {
        try(DirectFileChannel host=new DirectFileChannel()){
            try{host.issue("x",Collections.singletonList("192.0.2.1:9501"),()->false,in->in);fail();}catch(IOException expected){}
            try{host.issue("x",Collections.emptyList(),()->true,in->in);fail();}catch(IOException expected){}
            for(int i=0;i<3;i++)host.issue("x",Collections.singletonList("192.0.2.1:9501"),()->true,in->in);
            try{host.issue("x",Collections.singletonList("192.0.2.1:9501"),()->true,in->in);fail();}catch(IOException expected){}
        }
    }
}
