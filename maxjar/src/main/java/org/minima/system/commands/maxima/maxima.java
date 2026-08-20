package org.minima.system.commands.maxima;

import org.minima.objects.Address;
import org.minima.objects.base.MiniData;
import org.minima.system.Main;
import org.minima.system.network.maxima.MaximaManager;
import org.minima.system.network.maxima.message.MaximaMessage;
import org.minima.utils.Crypto;
import org.minima.utils.messages.Message;

/**
 * maxjar CUT-DOWN of classic's maxima CLI command: the managers call back into
 * exactly one static here - createSendMessage - copied VERBATIM from
 * commands/maxima/maxima.java:465-507. The other 470 lines are RPC plumbing
 * and stay behind.
 */
public class maxima {

	public static Message createSendMessage(String zFullTo, String zApplication, MiniData zData) {

		MaximaManager max = Main.getInstance().getMaxima();

		//Send a message..
		String fullto 	= zFullTo;
		int indexp 		= fullto.indexOf("@");
		int index 		= fullto.indexOf(":");

		//Get the Public Key
		String publickey = fullto.substring(0,indexp);
		if(publickey.startsWith("Mx")) {
			publickey = Address.convertMinimaAddress(publickey).to0xString();
		}

		//get the host and port..
		String tohost 	= fullto.substring(indexp+1,index);
		int toport		= Integer.parseInt(fullto.substring(index+1));

		//Get the complete details..
		MaximaMessage maxmessage = max.createMaximaMessage(publickey, zApplication, zData);

		//Get the MinData version
		MiniData maxdata = MiniData.getMiniDataVersion(maxmessage);

		//Hash it..
		MiniData hash 	= Crypto.getInstance().hashObject(maxdata);

		//Send to Maxima..
		Message sender = new Message(MaximaManager.MAXIMA_SENDMESSAGE);
		sender.addObject("maxima", maxmessage);

		sender.addObject("mypublickey", max.getPublicKey());
		sender.addObject("myprivatekey", max.getPrivateKey());

		sender.addString("publickey", publickey);
		sender.addString("tohost", tohost);
		sender.addInteger("toport", toport);

		sender.addString("msgid", hash.to0xString());

		return sender;
	}
}
