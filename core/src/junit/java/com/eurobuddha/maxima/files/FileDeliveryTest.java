package com.eurobuddha.maxima.files;
import com.eurobuddha.maxima.core.chat.ChatFile;
import com.eurobuddha.maxima.core.util.Json;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class FileDeliveryTest {
    private ChatFile file(){return new ChatFile("a".repeat(32),"test.bin","application/octet-stream",0,"b".repeat(64),"c".repeat(16),"d".repeat(64),"");}
    private String control(String kind,String digest){return new Json.Writer().put("v","1").put("id",file().id).put("digest",digest).put("kind",kind).done();}
    @Test public void verifiedReceiptRetriesAfterRestartAndAcknowledgesDuplicates()throws Exception {
        ChatFile f=file();Path a=Files.createTempDirectory("receipt-sender"),b=Files.createTempDirectory("receipt-receiver");
        AtomicInteger stops=new AtomicInteger();List<String> sent=new ArrayList<>();
        FileDelivery sender=new FileDelivery((p,c)->sent.add(c),id->stops.incrementAndGet());sender.register(f,a,true,List.of("bob"));
        FileDelivery receiver=new FileDelivery((p,c)->{},id->{});receiver.register(f,b,false,List.of("alice"));
        receiver.tick();assertEquals("0",sender.status(f.id).get("downloaded"));
        receiver.verified(f.id);receiver.tick(); // lost receipt
        receiver=new FileDelivery((p,c)->sender.inbound("bob",c),id->{});receiver.register(f,b,false,List.of("alice"));receiver.tick();sender.tick();
        assertEquals(1,stops.get());assertEquals("true",sender.status(f.id).get("sharingStopped"));
        sender.inbound("bob",control("complete",f.digest));sender.tick();assertEquals(1,stops.get());
        assertTrue(sent.stream().anyMatch(x->x.contains("ack")));
        FileDelivery restored=new FileDelivery((p,c)->{},id->stops.incrementAndGet());restored.register(f,a,true,List.of("bob"));restored.tick();assertEquals(1,stops.get());
        assertEquals("1",restored.status(f.id).get("downloaded"));
    }
    @Test public void forgedReceiptsCannotStopAndGroupsWaitForEveryRecipient()throws Exception {
        ChatFile f=file();AtomicInteger stops=new AtomicInteger();FileDelivery d=new FileDelivery((p,c)->{},id->stops.incrementAndGet());
        d.register(f,Files.createTempDirectory("receipt-group"),true,List.of("bob","carol"));
        d.inbound("mallory",control("complete",f.digest));d.inbound("bob",control("complete","e".repeat(64)));d.tick();assertEquals(0,stops.get());
        d.inbound("bob",control("complete",f.digest));d.tick();assertEquals(0,stops.get());
        d.keep(f.id,true);d.inbound("carol",control("complete",f.digest));d.tick();assertEquals(0,stops.get());
        d.keep(f.id,false);d.tick();assertEquals(1,stops.get());
    }
    @Test public void resendsBackOffUntilThePeerAnswers()throws Exception {
        ChatFile f=file();List<String> sent=new ArrayList<>();FileDelivery d=new FileDelivery((p,c)->sent.add(c),id->{});
        d.register(f,Files.createTempDirectory("receipt-backoff"),true,List.of("bob"));
        long t=1_000_000L;d.tick(t);assertEquals(1,sent.size());          // first hello at once
        d.tick(t+14_000);assertEquals(1,sent.size());d.tick(t+15_000);assertEquals(2,sent.size());   // 15 s
        d.tick(t+15_000+29_000);assertEquals(2,sent.size());d.tick(t+15_000+30_000);assertEquals(3,sent.size()); // 30 s
        d.tick(t+45_000+59_000);assertEquals(3,sent.size());d.tick(t+45_000+60_000);assertEquals(4,sent.size()); // 60 s
        for(int i=0;i<10;i++)d.tick(t+105_000+i*600_000L);                 // 2, 4, 8 min, then every 10 min
        assertEquals(13,sent.size());
        d.inbound("bob",control("capable",f.digest));                       // the peer is back: fast again
        long u=t+105_000+10*600_000L;d.tick(u-300_000);assertEquals(13,sent.size()); // the current gap still runs
        d.tick(u);assertEquals(14,sent.size());d.tick(u+15_000);assertEquals(15,sent.size());
    }
    @Test public void aReceiverThatSendsNothingDoesNotBackOff()throws Exception {
        ChatFile f=file();List<String> sent=new ArrayList<>();FileDelivery d=new FileDelivery((p,c)->sent.add(c),id->{});
        d.register(f,Files.createTempDirectory("receipt-quiet"),false,List.of("alice"));
        long t=1_000_000L;for(int i=0;i<10;i++)d.tick(t+i*600_000L);assertEquals(0,sent.size()); // a long download: nothing to say
        d.verified(f.id);long v=t+10*600_000L;d.tick(v);assertEquals(1,sent.size());              // complete at once
        d.tick(v+14_000);assertEquals(1,sent.size());d.tick(v+15_000);assertEquals(2,sent.size()); // and again after 15 s, not 10 min
    }
    @Test public void failedStopIsNeverReportedAsStoppedAndWillRetry()throws Exception {
        ChatFile f=file();AtomicInteger stops=new AtomicInteger();FileDelivery d=new FileDelivery((p,c)->{},id->{if(stops.getAndIncrement()==0)throw new java.io.IOException("offline");});
        d.register(f,Files.createTempDirectory("receipt-stop"),true,List.of("bob"));d.inbound("bob",control("complete",f.digest));d.tick();
        assertEquals("false",d.status(f.id).get("sharingStopped"));assertTrue(d.status(f.id).containsKey("deliveryError"));d.tick();assertEquals("true",d.status(f.id).get("sharingStopped"));
    }
}
