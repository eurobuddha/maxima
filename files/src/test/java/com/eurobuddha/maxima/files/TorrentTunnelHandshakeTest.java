package com.eurobuddha.maxima.files;

import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.msg.MaximaCTRLMessage;
import com.eurobuddha.maxima.core.net.Frame;
import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class TorrentTunnelHandshakeTest {
    private static byte[] offer() {
        return Frame.body(Frame.MSG_MAXIMA_CTRL, MaximaCTRLMessage.mls("MxSynthetic"));
    }
    private static DataInputStream reply(byte[] frame, int... tail)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        if(frame!=null)Frame.write(out,frame);
        for(int b:tail)out.write(b);
        return new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }
    @Test public void phoneAcceptanceLeavesTorrentHandshakeUnread()throws Exception {
        DataInputStream in=reply(null,1,19);
        TorrentTunnel.awaitReady(in);assertEquals(19,in.read());
    }
    @Test public void nodeOfferAndAcceptanceLeaveTorrentHandshakeUnread()throws Exception {
        DataInputStream in=reply(offer(),1,19);
        TorrentTunnel.awaitReady(in);assertEquals(19,in.read());
    }
    @Test public void framedOfferWithoutAcceptanceCannotOpenTunnel()throws Exception {
        for(DataInputStream in:new DataInputStream[]{reply(offer()),reply(offer(),0),reply(null,2),reply(null)}) {
            try{TorrentTunnel.awaitReady(in);fail("Missing/rejected acceptance admitted");}catch(IOException expected){}
        }
    }
    @Test public void oversizedTruncatedAndUnexpectedControlFramesAreRejected()throws Exception {
        for(DataInputStream in:new DataInputStream[]{
                reply(null,0,1,0,0), // oversized length, with no body: refuse before reading it
                reply(null,0,0,0,0),
                reply(null,0,0,0,10,9),
                reply(new byte[]{(byte)Frame.MSG_GREETING,0},1),
                reply(Frame.body(Frame.MSG_MAXIMA_CTRL,MaximaCTRLMessage.id(new MiniData(new byte[]{1}))),1),
                reply(offer(),0,0,0,2,9,1,1)}) {
            try{TorrentTunnel.awaitReady(in);fail("Invalid control frame admitted");}catch(IOException expected){}
        }
    }
}
