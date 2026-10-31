package com.eurobuddha.maxima.app.chat;

import java.io.*;

/** Bound bytes from untrusted photo providers before decoding, regardless of claimed size. */
public final class PhotoInput {
    public static final int MAX_BYTES=32*1024*1024;
    private PhotoInput(){}
    public static byte[] read(InputStream in)throws IOException {
        if(in==null)throw new IOException("Cannot open file");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buffer=new byte[8192];int count;
        while((count=in.read(buffer,0,Math.min(buffer.length,MAX_BYTES-out.size()+1)))!=-1){
            if(count==0){int one=in.read();if(one==-1)break;if(out.size()==MAX_BYTES)throw new IOException("Photo exceeds preview limit. Send it as a file.");out.write(one);continue;}
            if(count>MAX_BYTES-out.size())throw new IOException("Photo exceeds preview limit. Send it as a file.");
            out.write(buffer,0,count);
        }
        return out.toByteArray();
    }
}
