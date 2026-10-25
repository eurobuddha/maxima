package com.eurobuddha.maxima.app.portal;
import com.eurobuddha.maxima.cloud.ParlonsRemote;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.rpc.RpcPeer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.minima.utils.json.JSONObject;
import org.mockito.MockedConstruction;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class DirectFileRoutingTest {
    @Test public void directFailureNeverPutsUploadOrDownloadBlocksIntoRpc()throws Exception {
        RpcPeer rpc=mock(RpcPeer.class);AtomicInteger calls=new AtomicInteger();
        doAnswer(invocation->{
            assertEquals("parlons.files.direct",invocation.getArgument(1));
            assertEquals("{}",new String((byte[])invocation.getArgument(2),StandardCharsets.UTF_8));
            calls.incrementAndGet();((RpcPeer.ResponseHandler)invocation.getArgument(3)).onError("Synthetic unreachable account");return "id";
        }).when(rpc).call(anyString(),anyString(),any(byte[].class),any(RpcPeer.ResponseHandler.class),anyLong());
        try(MockedConstruction<MaximaNode> nodes=mockConstruction(MaximaNode.class,(node,context)->when(node.rpc()).thenReturn(rpc))){
            ParlonsRemote remote=new ParlonsRemote(mock(MaximaIdentity.class));
            try{
                JSONObject upload=new JSONObject();upload.put("action","append");upload.put("data","PRIVATE_FILE_BYTES");
                try{remote.rpc("parlons.files",upload);fail();}catch(Exception expected){}
                JSONObject download=new JSONObject();download.put("action","read");
                try{remote.rpc("parlons.files",download,1000);fail();}catch(Exception expected){}
                assertEquals(4,calls.get()); // two ticket attempts each, never the file command
            }finally{remote.close();}
        }
    }
}
