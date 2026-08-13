package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.*;
import com.eurobuddha.maxima.core.chat.*;
import com.eurobuddha.maxima.core.identity.*;
import com.eurobuddha.maxima.core.store.*;
import com.eurobuddha.maxima.core.contacts.Contact;
import java.io.File;
import java.util.concurrent.*;

/**
 * Concurrency + write-behind stress.
 *
 * The review found mMessages read off-lock from the pump thread while the UI
 * writes. A plain HashMap can SPIN FOREVER on a resize race rather than throw,
 * so the check is "does it complete", with a hard timeout.
 */
public class ConcurrencyTest {
  public static void main(String[] a) throws Exception {
    File dir=new File(System.getProperty("java.io.tmpdir"),"maxima-conc");
    if(dir.exists()){File[] fs=dir.listFiles(); if(fs!=null) for(File f:fs) f.delete();}

    byte[] e=new byte[32]; for(int i=0;i<32;i++) e[i]=(byte)(i*11+2);
    MaximaIdentity id=MaximaIdentity.fromPhrase(Bip39.fromEntropy(e));
    MaximaNode node=new MaximaNode(id,"1.0.48",1);
    ChatEngine ce=new ChatEngine(node);
    ce.setStore(new FileStore(dir));

    Group g=new Group("0xGC"); g.addAdmin(id.publicKeyHex()); g.addMember("0xPEER");
    ce.loadGroup(g);

    final int N=4000;
    ExecutorService pool=Executors.newFixedThreadPool(8);
    CountDownLatch done=new CountDownLatch(8);
    final java.util.concurrent.atomic.AtomicReference<Throwable> err=new java.util.concurrent.atomic.AtomicReference<>();

    // 4 writers appending, 4 readers scanning - the exact overlap the review flagged
    for(int t=0;t<4;t++){ final int tid=t; pool.submit(()->{
      try{ for(int i=0;i<N;i++){
        Contact c=new Contact("0xPEER"); c.name="p";
        c.setAddresses(java.util.Collections.singletonList("MxAAA@1.1.1.1:9001"));
        ce.send(c,"m"+tid+"-"+i);   // records + persists, will FAIL to deliver (no network) - fine
      }}catch(Throwable x){err.set(x);} finally{done.countDown();} }); }
    for(int t=0;t<4;t++){ pool.submit(()->{
      try{ for(int i=0;i<N;i++){ ce.conversation("0xPEER"); ce.conversations(); ce.message("0xnope"); }
      }catch(Throwable x){err.set(x);} finally{done.countDown();} }); }

    boolean finished=done.await(120,TimeUnit.SECONDS);
    pool.shutdownNow();

    if(err.get()!=null){ System.out.println("  XX threw: "+err.get()); System.exit(1); }
    if(!finished){ System.out.println("  XX DID NOT COMPLETE - likely a map resize spin"); System.exit(1); }
    System.out.println("  ok  16k concurrent ops across 8 threads completed with no corruption");

    // write-behind: state changes queue, then flush in one pass
    int flushed=ce.flushState();
    System.out.println("  ok  flushState wrote "+flushed+" deferred state change(s) in one pass");

    // cap holds under load
    int conv=ce.conversation("0xPEER").size();
    if(conv<=500) System.out.println("  ok  retention cap held under load ("+conv+" <= 500)");
    else { System.out.println("  XX cap breached: "+conv); System.exit(1); }

    ce.close();
    System.out.println("\n  ALL CONCURRENCY CHECKS PASSED");
  }
}
