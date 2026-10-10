package com.eurobuddha.maxima.desktop;

import com.eurobuddha.maxima.desktop.jar.DesktopJarEngine;
import com.eurobuddha.maxima.desktoplinks.MinimaDocsLink;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.identity.*;
import com.eurobuddha.maxima.core.contacts.Contact;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.util.*;
import static org.junit.Assert.*;

/** Real classic decrypt/callback and classic outbound wire, with disposable local peers. */
public class ClassicDocsIntegrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void classicAndBuiltInExchangeDocumentInvitations() throws Exception {
        org.minima.system.params.GeneralParams.ALLOW_ALL_IP = true;
        DesktopJarEngine jar = null; MaximaNode peer = null; MinimaDocsLink classic = null, other = null;
        try {
            jar = new DesktopJarEngine(temp.newFolder("classic"), "Classic fixture", Collections.emptyList());
            classic = new MinimaDocsLink(jar, temp.newFolder("links").toPath(), "desktop"); classic.start();
            MinimaDocsLink sink = classic; jar.setInbound((m,id) -> sink.receive(m)); jar.startLan();
            peer = new MaximaNode(MaximaIdentity.fromPhrase(Bip39.generate(24)), "test", 0); peer.startDirect(0);
            other = new MinimaDocsLink(peer, temp.newFolder("peer").toPath(), "core"); other.start();
            MinimaDocsLink receiver = other; peer.setMessageListener((m,id) -> receiver.receive(m));
            org.minima.database.maxima.MaximaContact c = new org.minima.database.maxima.MaximaContact(peer.publicKeyHex());
            c.setName("Built-in fixture"); c.setCurrentAddress(peer.identity().mxIdentity() + "@127.0.0.1:" + peer.directPort());
            org.minima.database.MinimaDB.getDB().getMaximaDB().newContact(c);
            String line = "MN2.Classic|Mx12345678@host:9001|AQ==|Ag==";
            String classicToken = connect(classic), otherToken = connect(other);
            assertEquals("sent", call(classic,"invite",classicToken,new JSONObject().put("to",peer.publicKeyHex()).put("line",line).put("requestId",UUID.randomUUID().toString())).getString("state"));
            awaitInbox(other, otherToken, line);
            assertTrue(peer.sendRaw(MxAddress.make(new com.eurobuddha.maxima.core.codec.MiniData(jar.publicKeyHex())) + "@127.0.0.1:" + jar.lanPort(), MinimaDocsLink.APPLICATION, line.getBytes(StandardCharsets.UTF_8)).isOk());
            awaitInbox(classic, classicToken, line);
        } finally {
            if(classic!=null)classic.close(); if(other!=null)other.close(); if(peer!=null)peer.stop(); if(jar!=null)jar.shutdown();
            org.minima.system.params.GeneralParams.ALLOW_ALL_IP = false;
        }
    }
    private static String connect(MinimaDocsLink link)throws Exception {String url=link.approve();return call(link,"connect",null,new JSONObject().put("code",url.substring(url.indexOf("&code=")+6))).getString("token");}
    private static JSONObject call(MinimaDocsLink link,String op,String token,JSONObject body)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+link.port()+"/minimadocs/v1/"+op).openConnection();c.setReadTimeout(20000);c.setRequestMethod("POST");c.setRequestProperty("Content-Type","application/json");if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);c.setDoOutput(true);c.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));assertEquals(200,c.getResponseCode());return new JSONObject(new String(c.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
    }
    private static void awaitInbox(MinimaDocsLink link,String token,String line)throws Exception {long end=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<end){org.json.JSONArray rows=call(link,"invitations",token,new JSONObject()).getJSONArray("invitations");if(rows.length()>0){assertEquals(line,rows.getJSONObject(0).getString("line"));return;}Thread.sleep(50);}fail("Invitation never reached real host inbox");}
}
