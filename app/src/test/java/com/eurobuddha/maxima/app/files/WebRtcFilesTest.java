package com.eurobuddha.maxima.app.files;
import org.junit.Test;
import static org.junit.Assert.*;
public class WebRtcFilesTest {
    @Test public void selectedPairMustExistAndBothCandidatesMustBeDirect(){
        java.util.Map<String,org.webrtc.RTCStats> stats=new java.util.HashMap<>();
        stats.put("t",new org.webrtc.RTCStats(0,"transport","t",java.util.Map.of("selectedCandidatePairId","p")));
        stats.put("p",new org.webrtc.RTCStats(0,"candidate-pair","p",java.util.Map.of("localCandidateId","l","remoteCandidateId","r")));
        stats.put("l",new org.webrtc.RTCStats(0,"local-candidate","l",java.util.Map.of("candidateType","host")));
        stats.put("r",new org.webrtc.RTCStats(0,"remote-candidate","r",java.util.Map.of("candidateType","srflx")));
        assertTrue(WebRtcFiles.directPair(stats));
        stats.put("r",new org.webrtc.RTCStats(0,"remote-candidate","r",java.util.Map.of("candidateType","relay")));
        assertFalse(WebRtcFiles.directPair(stats));
        stats.remove("r");assertFalse(WebRtcFiles.directPair(stats));
        assertFalse(WebRtcFiles.directPair(java.util.Collections.emptyMap()));
    }
    @Test public void rejectRelayAndMediaNegotiation(){
        String sdp="v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=candidate:1 1 udp 1 192.0.2.1 4000 typ host\r\n";
        assertTrue(WebRtcFiles.directSdp(sdp));
        assertFalse(WebRtcFiles.directSdp(sdp.replace("typ host","typ relay")));
        assertFalse(WebRtcFiles.directSdp(sdp+"m=audio 9 RTP/AVP 0\r\n"));
        assertFalse(WebRtcFiles.directSdp(sdp+"m=video 9 RTP/AVP 0\r\n"));
        assertFalse(WebRtcFiles.directSdp("v=0"));
        assertTrue(WebRtcFiles.directCandidate("candidate:1 1 udp 1 192.0.2.1 4000 typ srflx raddr 10.0.0.1"));
    }
}
