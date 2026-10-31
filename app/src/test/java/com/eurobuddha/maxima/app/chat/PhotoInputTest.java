package com.eurobuddha.maxima.app.chat;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.Arrays;

public class PhotoInputTest {
    static final class Source extends InputStream {
        final long size;long read;
        Source(long size){this.size=size;}
        public int read(){if(read==size)return -1;read++;return 7;}
        public int read(byte[] b,int offset,int length){if(read==size)return -1;int n=(int)Math.min(size-read,length);Arrays.fill(b,offset,offset+n,(byte)7);read+=n;return n;}
    }
    @Test public void oversizedStreamStopsAtLimitPlusOneByte()throws Exception {
        Source stream=new Source(Long.MAX_VALUE);
        try{PhotoInput.read(stream);fail("Unbounded image read");}catch(IOException expected){}
        assertEquals(PhotoInput.MAX_BYTES+1L,stream.read);
    }
    @Test public void exactBoundaryAndNormalPhotoArePreserved()throws Exception {
        assertEquals(PhotoInput.MAX_BYTES,PhotoInput.read(new Source(PhotoInput.MAX_BYTES)).length);
        byte[] bytes={1,2,3};assertArrayEquals(bytes,PhotoInput.read(new ByteArrayInputStream(bytes)));
    }
}
