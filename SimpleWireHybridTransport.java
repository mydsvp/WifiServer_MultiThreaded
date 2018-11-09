package com.wifiserver.commserver;

import com.simplewire.sms.SMS;
import com.wifiserver.core.event.SSPModel;

public class SimpleWireHybridTransport  extends GPRSTransport 
{   
	@Override
	protected void sendSMS(SSPModel model, String emailPacket) throws Exception
	{
	    // need to get pin from sspModel
		String toAddress = model.destinationAddress;
		
        if (toAddress.length() > 10 && toAddress.substring(0,1).equals("1")){
            CommServer.log4j.debug("Stripping off leading one from number " + toAddress);
            toAddress = toAddress.substring(1);
        }

        CommServer.log4j.debug("Sending toAddress = +1" + toAddress);
		
		CommServer.log4j.debug("About to create SMS Object");
		SMS sms = new SMS();
		CommServer.log4j.debug("Created SMS Object");

		// Subscriber Settings
		CommServer.log4j.debug("Subscriber Settings");
		//sms.setSubscriberID("230-337-760-82291");
		sms.setSubscriberID(CommServer.props.getProperty("commserver.transport.Simplewire.subscriberId"));
		//sms.setSubscriberPassword("0BF77319");
		sms.setSubscriberPassword(CommServer.props.getProperty("commserver.transport.Simplewire.subscriberPassword"));
		

		//Because AT&T uses a proprietary area code (500) we need to manually set 
		//the carrier code for messages sent to AT&T devices.
		//This is not necessary for Rogers devices.  So its configurable, if one 
		//exists in the configuration we use it, otherwise we ignore this line.
	
		//AT&T - 7
		//Rogers - 389 & 75
		String cId = "";
		int carrier = 0;
		if (CommServer.props.getProperty("commserver.transport." + String.valueOf(model.transportId) + ".Simplewire.carrierCode") != null)
		{
		    CommServer.log4j.debug("Setting Carrier Code to " + CommServer.props.getProperty("commserver.transport." + String.valueOf(model.transportId)+ ".Simplewire.carrierCode"));
		    //sms.setMsgCarrierID(CommServer.props.getProperty("commserver.transport." + String.valueOf(model.transportId) + ".Simplewire.carrierCode"));
		    cId = CommServer.props.getProperty("commserver.transport." + String.valueOf(model.transportId) + ".Simplewire.carrierCode");
		    try {
		    	carrier = Integer.parseInt(cId);
		    }
		    catch (Exception e) {
		    	CommServer.log4j.error("Carrier Id is being reset to 0.  Please ensure that the Carrier Id is a valid integer.");
		    	carrier = 0 ;
		    }
		    CommServer.log4j.debug("Setting Carrier Code to int value of " + carrier);
		}
		
		//set the short code if it is applicable
		if (CommServer.props.getProperty("commserver.transport." + String.valueOf(model.transportId) + ".Simplewire.shortCode") != null)
		{
		    CommServer.log4j.debug("Setting short Code to " + CommServer.props.getProperty("commserver.transport." + String.valueOf(model.transportId)+ ".Simplewire.shortCode"));
		    sms.setSourceAddr(SMS.ADDR_TYPE_NETWORK,CommServer.props.getProperty("commserver.transport." + String.valueOf(model.transportId) + ".Simplewire.shortCode")); 
		}
		
		//set program id if applicable
		if (CommServer.props.getProperty("commserver.transport." + String.valueOf(model.transportId) + ".Simplewire.programID") != null)
		{
		    CommServer.log4j.debug("Setting program ID to " + CommServer.props.getProperty("commserver.transport." + String.valueOf(model.transportId)+ ".Simplewire.programID"));
		    sms.setProgramID(CommServer.props.getProperty("commserver.transport." + String.valueOf(model.transportId) + ".Simplewire.programID")); 
		}
		
		if (carrier != 0)
			sms.setDestinationAddr(carrier,SMS.ADDR_TYPE_UNKNOWN,"+1"+toAddress);
		else 
			sms.setDestinationAddr(SMS.ADDR_TYPE_UNKNOWN,"+1"+toAddress);

        // Set the destination of the message to the appropriate URL.
        sms.setRemoteHost(CommServer.props.getProperty("commserver.transport.Simplewire.RemoteHostURL"));
                
		sms.setMsgText(emailPacket);

		CommServer.log4j.info("[" + toAddress + "] -> " + emailPacket);

		// Send Message
		sms.msgSend();

		// Check For Errors
		if(sms.isSuccess())
		{
			CommServer.log4j.debug("Message was sent to " + toAddress + "!");
		}
		else
		{
			CommServer.log4j.error("Message was not sent to " + toAddress + "!");
			CommServer.log4j.error("Error Code: " + sms.getErrorCode());
			CommServer.log4j.error("Error Description: " + sms.getErrorDesc());
			CommServer.log4j.error("Error Resolution: " + sms.getErrorResolution() + "\n");
			throw new Exception();
		}
		
	}
	
}
