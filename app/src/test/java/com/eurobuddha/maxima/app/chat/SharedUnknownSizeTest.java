package com.eurobuddha.maxima.app.chat;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.Arrays;
import com.eurobuddha.maxima.core.chat.ChatFile;

public class SharedUnknownSizeTest {
    @Test public void pipeContentIsPreservedExactly() throws Exception {
        byte[] input=new byte[90001];for(int i=0;i<input.length;i++)input[i]=(byte)i;
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        assertEquals(input.length,PrivateFileUi.copyUnknown(new ByteArrayInputStream(input),output,()->false));
        assertArrayEquals(input,output.toByteArray());
    }
    @Test public void hundredMegabyteBoundaryAndCancellation() throws Exception {
        OutputStream discard=new OutputStream(){public void write(int b){}public void write(byte[] b,int o,int n){}};
        assertEquals(ChatFile.TRANSFER_MAX_BYTES,PrivateFileUi.copyUnknown(zeroes(ChatFile.TRANSFER_MAX_BYTES),discard,()->false));
        try{PrivateFileUi.copyUnknown(zeroes(ChatFile.TRANSFER_MAX_BYTES+1),discard,()->false);fail("Size limit bypassed");}catch(IOException expected){}
        try{PrivateFileUi.copyUnknown(zeroes(2),discard,()->true);fail("Cancelled import continued");}catch(InterruptedIOException expected){}
    }
    private InputStream zeroes(long size){return new InputStream(){long left=size;
        public int read(){if(left==0)return -1;left--;return 0;}
        public int read(byte[] b,int offset,int length){if(left==0)return -1;int count=(int)Math.min(left,length);Arrays.fill(b,offset,offset+count,(byte)0);left-=count;return count;}
    };}
}
