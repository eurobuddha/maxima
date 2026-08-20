package org.minima.objects;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.minima.objects.base.MiniData;
import org.minima.utils.BaseConverter;
import org.minima.utils.Crypto;

/**
 * maxjar CUT-DOWN of classic org.minima.objects.Address: only the two static
 * Mx-address converters Maxima uses. The full class drags in the MMR coin
 * machinery, which is chain code and stays behind. The two method bodies below
 * are copied VERBATIM from classic (Address.java:133-176 and 178-225).
 */
public class Address {

	/**
	 * Convert an address into a Minima Checksum Base32 address - MAX 32k
	 */
	public static String makeMinimaAddress(MiniData zAddress){

		//The Original data
		byte[] data = zAddress.getBytes();
		int datalen = data.length;

		//First hash it to for checksum digits..
		byte[] hash 		= Crypto.getInstance().hashData(data);
		byte[] checksum		= new byte[4];
		for(int i=0;i<4;i++) {
			checksum[i] = hash[i];
		}

		//Now write this info to stream
		ByteArrayOutputStream bos 	= new ByteArrayOutputStream();
	    DataOutputStream dos 		= new DataOutputStream(bos);

	    try {
	    	//MUST write 1 non 0 byte first to ensure no truncation in base 32 conversion
	    	dos.write(1);

	    	//the length
			dos.writeShort(datalen);

		    //the data itself..
			dos.write(data);

			//4 bytes of the hash
			dos.write(checksum);

			//Close the Streams
			dos.close();
			bos.close();

	    } catch (IOException e) {
	    	throw new IllegalArgumentException("Invalid MxAddress - "+e.toString());
		}

		//Get the bytes
		byte[] origdata = bos.toByteArray();

		//Now convert the whole thing to Base 32
		return BaseConverter.encode32(origdata);
	}

	public static MiniData convertMinimaAddress(String zMinimAddress) throws IllegalArgumentException {

		//First convert the whole thing back..
		byte[] decode 	= BaseConverter.decode32(zMinimAddress);

		//Now read in the data..
		ByteArrayInputStream bais 	= new ByteArrayInputStream(decode);
		DataInputStream dis 		= new DataInputStream(bais);

		byte[] data;
		byte[] checksum = new byte[4];

		try {
			//Read the first byte
			int one = dis.read();
			if(one!=1) {
				throw new IllegalArgumentException("Invalid MxAddress - should start with 1 "+zMinimAddress);
			}

	    	//First the data length
			int datalen = dis.readShort();

		    //the data itself..
			data = new byte[datalen];
			dis.readFully(data);

			//And the checksum
			dis.readFully(checksum);

			//Close the Streams
			dis.close();
			bais.close();

	    } catch (IOException e) {
	    	throw new IllegalArgumentException("Invalid MxAddress : "+zMinimAddress+" "+e.toString());
		}

		//Now check the hash
		byte[] hash = Crypto.getInstance().hashData(data);

		//Check the first 4 bytes..
		for(int i=0;i<4;i++) {
			if(hash[i] != checksum[i]) {
				throw new IllegalArgumentException("Invalid MxAddress - checksum wrong for "+zMinimAddress);
			}
		}

		return new MiniData(data);
	}
}
