package com.wifiserver.commserver;

import com.wifiserver.core.event.SSPModel;
import com.wifiserver.smsgateway.client.SMS;

public class SMSGatewayHybridTransport  extends GPRSTransport 
{    
	public static void main(String[] args) {
	}
	
	@Override
	protected void sendSMS(SSPModel model, String emailPacket) throws Exception
	{
		
	    CommServer.log4j.debug("Entering sendEmail ");

	    // need to get pin from sspModel
		String toAddress = model.destinationAddress;
		
                if (toAddress.length() > 10 && toAddress.substring(0,1).equals("1")){
                    CommServer.log4j.debug("Stripping off leading one from number " + toAddress);
                    toAddress = toAddress.substring(1);
                }
                
		CommServer.log4j.debug("SendEmail toAddress = +1" + toAddress);
		
		CommServer.log4j.debug("About to create SMS Object");
		//sms.setMsgPin("+1"+toAddress);
		SMS sms = new SMS(toAddress, emailPacket, CommServer.props.getProperty("commserver.transport." + String.valueOf(model.transportId) + ".SMSGateway.carrierCode"), false);
		CommServer.log4j.debug("Created SMS Object");

		// Subscriber Settings
		CommServer.log4j.debug("Subscriber Settings");
		//sms.setSubscriberID("230-337-760-82291");
		
		

		// Optional Message Settings
		// Specify source and destination ports that will appear
		// in the GSM User-Data-Header
		//sms.setSourcePort((short)0x0000);
		//sms.setDestPort((short)0x0000);
		//
		// Specify a network type "hint" - helps Simplewire
		// choose between a TDMA vs. GSM network for example.
		// Only useful for certain types of messages such as
		// WAP push or MIDP Java WMA messages and if the destination
		// operator runs both TDMA and GSM networks.
		// sms.setNetworkType(SMS.NETWORK_TYPE_GSM);

		// Message Settings

        CommServer.log4j.info("[" + toAddress + "] -> " + emailPacket + " SMS");
		
		// Send Message
		sms.send();

		// Check For Errors
		if(sms.isSuccess())
		{
			CommServer.log4j.debug("[" + toAddress + "] Sent SMS Message");
		}
		else
		{
			//try to send through something else!
			CommServer.log4j.error("Message was not sent to " + toAddress + "!");
			CommServer.log4j.error("Error Description: " + sms.getResponseErrorText());
			throw new Exception();
		}
		
		CommServer.log4j.debug("Exiting SendEmail");
    	
	}
	
}
