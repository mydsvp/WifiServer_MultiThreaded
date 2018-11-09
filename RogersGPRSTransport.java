package com.wifiserver.commserver;

import com.wifiserver.core.event.SSPModel;


public class RogersGPRSTransport extends GPRSTransport
{
	@Override
	protected void sendSMS(SSPModel model, String emailPacket) throws Exception
	{
		// need to get email address from sspModel and transport
		String toAddress = model.destinationAddress;
                
                if (toAddress.length() > 10 && toAddress.substring(0,1).equals("1")){
                    CommServer.log4j.debug("Stripping off leading one from number " + toAddress);
                    toAddress = toAddress.substring(1);
                }
                
		CommServer.log4j.debug("toAddress = " + toAddress);
		String fromAddress = model.senderAddress;
		CommServer.log4j.debug("fromAddress = " + fromAddress);
		String subject = "";
		//toAddress+="@mobile.att.net";
		toAddress+="@pcs.rogers.com";
		CommServer.log4j.debug("toAddress2 = " + toAddress);
		
		sendSMTPMessage(toAddress, toAddress, subject, emailPacket);

		CommServer.log4j.debug("SMS Message sent to " + toAddress + " ... packet = " + emailPacket);

	}
}
