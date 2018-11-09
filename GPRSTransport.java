package com.wifiserver.commserver;

import java.rmi.RemoteException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

import javax.ejb.CreateException;
import javax.mail.Message;
import javax.mail.SendFailedException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.rmi.PortableRemoteObject;
import javax.xml.bind.DatatypeConverter;

import com.wifiserver.commserver.handlers.NIOMeterHandler;
import com.wifiserver.commserver.util.CRC16;
import com.wifiserver.core.event.SSPModel;
import com.wifiserver.core.services.messages.HoldingQueue;
import com.wifiserver.core.services.messages.HoldingQueueHome;

import com.wifiserver.core.util.ConversionUtility;
import com.wifiserver.util.ThayerEncryption;

import com.wifiserver.verticals.utility.devices.translation.ReverseHexString;


public abstract class GPRSTransport
{
	public static final String TMI_PACKET = "01";
	public static final String INQUIRY = "02";

    private static HoldingQueue sHoldingQueueEJB = null;

    private static boolean InitializeMsgQueue()
    {
    	if (null == sHoldingQueueEJB)
    	{
	        Properties props = new Properties();
	        props.put(Context.INITIAL_CONTEXT_FACTORY, "weblogic.jndi.WLInitialContextFactory");
	        props.put(Context.PROVIDER_URL, CommServer.serverInterface);
	
			try {
				InitialContext initCtx = new InitialContext(props);
	
				HoldingQueueHome holdingQueueHome = (HoldingQueueHome)PortableRemoteObject.narrow(initCtx.lookup("HoldingQueue"), HoldingQueueHome.class);
	
				sHoldingQueueEJB = holdingQueueHome.create();

				return (true);
	
			} catch (NamingException exception) {
				CommServer.log4j.warn(exception);
			}
			catch (RemoteException exception) {
				CommServer.log4j.warn(exception);
	
			}
			catch (CreateException exception) {
				CommServer.log4j.warn(exception);
	
			}

			return (false);
    	}
		return (true);
    }

	public String buildSMSPacket(String messageType, SSPModel sspModel) throws Exception
	{
		StringBuffer unencrypted = new StringBuffer(messageType);
		String encrypted = null;
        String utilityKey = null;
		StringBuffer completeString = null;

		try {
            if (CommServer.isConfigServer != null && CommServer.isConfigServer.equalsIgnoreCase("true")) {
                CommServer.log4j.debug("*** Using default key since this is a config server ***");
                utilityKey = CommServer.defaultKey;
            } else {
                utilityKey = (String)CommServer.utilityKeyMap.get(sspModel.customerNumber);
                CommServer.log4j.debug("*** Using utility key for customer number " + 
                					   sspModel.customerNumber + ", so encryption key = " + utilityKey);
            }

			String devicePin = sspModel.destinationAddress;
			CommServer.log4j.debug("messageId = " + sspModel.messageId + " ... " + devicePin); 
			unencrypted.append(sspModel.messageId);
			CommServer.log4j.debug("messageId ... " + unencrypted.toString() + " ... " + devicePin);
		
			if (messageType.equalsIgnoreCase(TMI_PACKET))
			{
				CommServer.log4j.debug("messageType = TMI_PACKET ... " + devicePin);
				unencrypted.append(sspModel.payload);
			} 
			else if (messageType.equalsIgnoreCase(INQUIRY))
			{
				CommServer.log4j.debug("messageType = INQUIRY ... " + devicePin);
				unencrypted.append(formatRedirectAddress(sspModel.senderAddress));
			}
			else 
			{
				CommServer.log4j.debug("no/invalid messageType ... " + devicePin);
				throw new Exception("MessageType is incorrect.  Cannot build SMS packet [" + devicePin +"]");
			}
			CommServer.log4j.debug("payload appended ... " + unencrypted.toString() + " ... " + devicePin);
	
			// Calculate crc on the unencrypted payload
			int crc = CRC16.calculateCRC16(GPRSUtility.getBytesFromHexValue(unencrypted.toString()));
			
			//encrypt with utility key seed
			CommServer.log4j.debug("utilityKey = " + utilityKey + "unencrypted = " + unencrypted.toString() + " ... " + devicePin);
			encrypted = ThayerEncryption.crypt(unencrypted.toString(), utilityKey);
			CommServer.log4j.debug("payload encrypted = " + encrypted + " ... " + devicePin);
			
			// Append the string-ified CRC to the out-bound packet
			CommServer.log4j.debug("appending crc ... crc = " + crc + " ... " + devicePin);
			completeString = new StringBuffer(encrypted);
			String crcString = ConversionUtility.intToHex(crc, 4);
			completeString.append(crcString);
			CommServer.log4j.debug("crc appended ... " + completeString.toString() + " ... " + devicePin);

			return completeString.toString();
		}
		catch (Exception exception) {
			CommServer.log4j.error("failed to construct outbound packet", exception);			
		}
		return("");
	}
	

	public String formatSMSMessage(String payload) throws Exception
	{
		// this is where the message will be base 64 encoded
		// because some SMS services cannot support binary payloads.

		try {
			byte ba [] = DatatypeConverter.parseHexBinary(payload);

			String smsMessage = ":" + DatatypeConverter.printBase64Binary(ba);
			CommServer.log4j.debug("Base 64 encoded message = " + smsMessage);

			return(smsMessage);
		} catch (Exception exception) {
		    CommServer.log4j.error("Unable to encode message.", exception);
			throw new Exception("Unable to encode message.", exception);
		}
	}

	public String formatRedirectAddress(String address)
	{
		//address should be in format 127.0.0.1:7001
		String redirectAddress = new String();
		CommServer.log4j.debug("address = " + address);
		
		//Octet 1
		int index = address.indexOf(".");
		String octet = address.substring(0, index);
		CommServer.log4j.debug("octet 1 = " + octet);
		int octInt = Integer.parseInt(octet);
		redirectAddress = ConversionUtility.intToHex(octInt, 2);
		CommServer.log4j.debug("after octet 1");

		//Octet 2
		int index2 = address.indexOf(".", index+1);
		octet = address.substring(index+1, index2);
		CommServer.log4j.debug("octet 2 = " + octet);
		octInt = Integer.parseInt(octet);
		redirectAddress += ConversionUtility.intToHex(octInt, 2);
		CommServer.log4j.debug("after octet 2");
		//Octet 3
		index = address.indexOf(".", index2+1);
		octet = address.substring(index2+1, index);
		CommServer.log4j.debug("octet 3 = " + octet);
		octInt = Integer.parseInt(octet);
		redirectAddress += ConversionUtility.intToHex(octInt, 2);
		CommServer.log4j.debug("after octet 3");
		//Octet 4
		CommServer.log4j.debug("getting octet 4 ... 1");
		index2 = address.indexOf(":", index+1);
		CommServer.log4j.debug("getting octet 4 ... 2");
		octet = address.substring(index+1, index2);
		CommServer.log4j.debug("octet 4 = " + octet);
		octInt = Integer.parseInt(octet);
		redirectAddress += ConversionUtility.intToHex(octInt, 2);
		CommServer.log4j.debug("after octet 4");
		//Port
		octet = address.substring(index2+1);
		CommServer.log4j.debug("port = " + octet);
		octInt = Integer.parseInt(octet);
		//redirectAddress += ConversionUtility.intToHex(octInt, 4);
		redirectAddress += ReverseHexString.perform(ConversionUtility.intToHex(octInt, 4));
		CommServer.log4j.debug("after port");
		return redirectAddress;
	}
	
	public void sendSMTPMessage(String toAddress, String fromAddress, String subject, String payload) 
		throws SendFailedException, Exception
    {
		//toAddress will be the device pin plus the transport information to form an email address

		try {
			Properties props = new Properties();
			props.put("mail.smtp.host", CommServer.emailServer);
			
			Session session = Session.getDefaultInstance(props);
	
	        // create a message
	        Message msg = new MimeMessage(session);
	            
	        try {
	            // set the from and to address
	            msg.setFrom(new InternetAddress(fromAddress));
	            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(toAddress));
	
	            // Setting the Subject and Content Type
	            msg.setSubject(subject);
	            msg.setContent(payload, "text/plain");
				
	            Transport.send(msg);
	        }
	        catch (SendFailedException sfe) 
	        {
	            CommServer.log4j.error(sfe);
				throw sfe;
	        }
		}
		catch (Exception exception)
        {
			// Typical exceptions might consist of
			//
			// javax.mail.internet.AddressException
			// javax.mail.MessagingException

		    CommServer.log4j.error("Incorrect Address or Message.", exception);
			throw (exception);
        }
    }

	public void putMsgInHoldingQ(SSPModel sspModel) throws Exception
	{
		try {
			if (InitializeMsgQueue()) {
	
			    CommServer.log4j.info( "[" + sspModel.destinationAddress + "] Pushing message back into holding queue.");

				sHoldingQueueEJB.putMessage(sspModel.destinationAddress, sspModel, sspModel.retryInterval);
			}
			else {
			    CommServer.log4j.warn( "[" + sspModel.destinationAddress + "] Msg discarded. Holding queue not available.");
			}
		} catch (Exception e) {
		    CommServer.log4j.error( "[" + sspModel.destinationAddress + "] Msg discarded. Holding queue not available.", e);
		    
		    sHoldingQueueEJB = null;

			throw new Exception("Unable to put message in Holding Queue", e);
		}
	    
	}	

	protected abstract void sendSMS(SSPModel model, String emailPacket) throws Exception;

	public void processMessage(SSPModel sspModel) throws Exception
	{
		boolean processMsg = false;

		// Max Length for an SMS Message on AT&T is 110 Characters
		// Minus the SMS Header leaves 104 Bytes
		// Base 64 Encode it leaves around 80 characters at the max
		int maxPayloadLength = Integer.parseInt(CommServer.props.getProperty("commserver.transport.Simplewire.maxPacketSize"));

		if (CommServer.log4j.isDebugEnabled()) 
		{
			CommServer.log4j.debug("CommServer.openSockets.containsKey(" + sspModel.destinationAddress + ") = " + CommServer.getOpenSocketsEjb().containsKey(sspModel.destinationAddress));
			CommServer.log4j.debug("CommServer.pingSent.containsKey(" + sspModel.destinationAddress + ") = " + CommServer.getPingSentEjb().containsKey(sspModel.destinationAddress));
			CommServer.log4j.debug("CommServer.processingMsg.containsKey(sspModel.destinationAddress) = " + CommServer.getProcessingMsgEjb().containsKey(sspModel.destinationAddress));		
		}

		if (!CommServer.getProcessingMsgEjb().containsKey(sspModel.destinationAddress) &&
			!CommServer.getPingSentEjb().containsKey(sspModel.destinationAddress) &&
		    !CommServer.getOpenSocketsEjb().containsKey(sspModel.destinationAddress))
		{
		    //add device to processingMsg
		    CommServer.log4j.debug("New connection to " + sspModel.destinationAddress);
		    CommServer.getProcessingMsgEjb().put(sspModel.destinationAddress, new Timestamp(System.currentTimeMillis()));
		    processMsg = true;
		} 
		else {

		    CommServer.log4j.debug("Connection to " + sspModel.destinationAddress + " already exists");

		    if (!CommServer.getProcessingMsgEjb().containsKey(sspModel.destinationAddress) &&
		        !CommServer.getOpenSocketsEjb().containsKey(sspModel.destinationAddress) &&
		         CommServer.getPingSentEjb().containsKey(sspModel.destinationAddress))
		    {
		        Timestamp pingStamp = (Timestamp)CommServer.getPingSentEjb().get(sspModel.destinationAddress);
		        
		        // If a request to wake up a meter has occurred at somepoint in the past too long ago we
		        // reset the previous attempt state and allow new message to be attempted, at the same time
		        // as sending a message back to the TMS system warning it of the failure of the previous
		        // message
		        if (pingStamp.before(new Timestamp(System.currentTimeMillis() - CommServer.auditorTimeOut)))
		        {
		            //remove from pingSent map
		            CommServer.log4j.debug("Removing " + sspModel.destinationAddress + " from Ping Sent Map");
		            CommServer.getPingSentEjb().remove(sspModel.destinationAddress);
		            //send message back to tms
		            GPRSUtility.enqueueMessage("No response from SMS sent to device " + sspModel.destinationAddress, sspModel.destinationAddress, CommServer.gprsInboundQueue);
		            //send the new message
		            processMsg = true;
		        }
		    }
		}

		// If it appears that we are actually connected at the present moment push the message for the connected
		// meter into the holding queue so that it can be retrieved by the object dealing with that meter.
		//
		// This contains a fairly large concurrency hole but will do for now until we implement
		// a shared nothing architecture without a central authority, the true enemy of scaling.
		if (!processMsg || CommServer.getOpenSocketsEjb().containsKey(sspModel.destinationAddress) || 
						   CommServer.getPingSentEjb().containsKey(sspModel.destinationAddress)) 
		{
			try {
			    putMsgInHoldingQ(sspModel);				
			} catch (Exception e) {
			    CommServer.log4j.error("Unable to put message in Holding Queue.", e);
				throw new Exception("Unable to put message in Holding Queue.", e);
			}
			return;
		}

		// TODO RUSTI - try to connect to the device here before sending SMS
        try {
            if (sspModel.commType == SSPModel.MT_AND_SMS_COMM_TYPE || 
            	sspModel.commType == SSPModel.MT_COMM_TYPE)
            {
            	if (sspModel.mtIpAddress != null && !sspModel.mtIpAddress.isEmpty() && 
                    sspModel.mtIpAddress.contains(":")) 
            	{ 
                    // Extract ip address & port from its proprietary representation in the SSPModel

                    String fullAddress = sspModel.mtIpAddress;
                    fullAddress = fullAddress.replace("[", "");
                    fullAddress = fullAddress.replace("]", "");

                    int index = fullAddress.lastIndexOf(":");

                    String ipAddress = fullAddress.substring(0,index);
                    int port = Integer.parseInt(fullAddress.substring(index + 1));

                    // After processing the IP we build the packet that will be sent by
                    // concatenating TMI chinks and wrapping the GPRS framing around it.

                    ArrayList<SSPModel> sspColl = new ArrayList<SSPModel>();
                    sspColl.add(sspModel);
                    Collection<Chunk> queuedChunksForDevice = GPRSUtility.readChunksFromSSPModels(sspColl);
                    
                    ArrayList<Chunk> outboundChunks = new ArrayList<Chunk>();
                    outboundChunks.addAll(queuedChunksForDevice);

                    byte[] gprsPacket = GPRSUtility.buildGPRSPacket(outboundChunks, 0, FrameHeader.STANDARD_FRAME, false,
                    	                                            sspModel.destinationAddress);

                    try {
	                    // Make a connection attempt using TCP/IP
	                    if (null != new NIOMeterHandler().handle(ipAddress, port, gprsPacket, sspColl, sspModel.destinationAddress)) {
	                    	// Success the connection was made so we simply return and allow the client to deal with subsequent 
	                    	// traffic
	                    	return;
	                    }
                    }
                    catch (Throwable exception) {
                        CommServer.log4j.debug("[" + sspModel.destinationAddress + "] is being removed from the processingMsg hashmap.");
            			CommServer.getProcessingMsgEjb().remove(sspModel.destinationAddress);
                    }

                    // If the connection was not made then there could be a host of reasons.  It wont always
                    // be clear exactly under what circumstances an SMS is warranted because of the large
                    // number of possibilities.  If the message being sent has the opportunity to be re processed
                    // additional times by the auditor through being put back into the holding queue then do that.
                    // We give ourselves a leyway of 1 second, 1000 milliseconds to prevent premature losses.
                    //
                    // However if the message would expire as a result of being in the holding queue now might be
                    // a good time to try sending an SMS to see if the connection can be established, this is the
                    // drop-through case.
                    
                    Timestamp expectedNextTry = new Timestamp(System.currentTimeMillis() + (sspModel.retryInterval) - 1000);
                    if (sspModel.messageExpiration.after(expectedNextTry))
                    {
                    	CommServer.log4j.debug("[" + sspModel.destinationAddress + "] " + sspModel.messageExpiration.toString() + " is after " + expectedNextTry.toString());
        			    putMsgInHoldingQ(sspModel);
        			    return;
                    }

                    // In the event that the request is only permitted to use TCP then we push the message
                    // back into the holding queue and do not exploit other communications paths to the meter
                    if (sspModel.commType == SSPModel.MT_COMM_TYPE) {
        			    putMsgInHoldingQ(sspModel);
        			    return;                    	
                    }
                    
                    // Drop-Through to try the SMS
            	}
            	else {
                    CommServer.log4j.info("[" + sspModel.destinationAddress + "] Has an unuseable IP Address. SMS is mandatory.");
                    // This wont ever correct itself so assume this meter does not have a static IP and MUST be
                    // woken up using an SMS, drop through.
            	}
            }
        } catch (Exception e) {
            CommServer.log4j.warn("[" + sspModel.destinationAddress + "] Socket connection " + sspModel.mtIpAddress + " failed.", e);

            CommServer.log4j.debug("[" + sspModel.destinationAddress + "] is being removed from the processingMsg hashmap.");
			CommServer.getProcessingMsgEjb().remove(sspModel.destinationAddress);
			
        	//TODO RUSTI - should the message be put in the holding queue so it can be picked up by the auditor?
        	//putMsgInHoldingQ(sspModel);

        	throw new Exception("Aborting attempt to connect to the socket.", e);
        }	    

        // At this point the attempts to make use of a TCP/IP socket have not meet with success so we now
        // can send an SMS to try and wake-up the meter, as long as the communications type permits the
        // SMS connection attempt.
		if (sspModel.commType == SSPModel.MT_AND_SMS_COMM_TYPE || sspModel.commType == SSPModel.SMS_COMM_TYPE) 
		{
			String messageType = TMI_PACKET;

			// If we discover that the message cannot be sent using SMS because of its size we simply
			// put the message into the holding queue, and set the payload of the SMS to be a
			// request that the meter send an enquiry message to extract the original message
			// intended for it.
			if(sspModel.payload.length() > maxPayloadLength)
			{
				messageType = INQUIRY;
				CommServer.log4j.info("[" + sspModel.destinationAddress + "] Payload to large for SMS. Setting message type to INQUIRY");
			}

			// Now build the SMS message intended for the meter by wrapping the TMI message sent by TMS 
			try {
				// Payloads come back from buildGPRSPacket encrypted, with a crc appended
				String emailPacket = buildSMSPacket(messageType, sspModel);

				// Payload now comes back from formatSMSMessage ready to be sent out on the ATT network
				emailPacket = formatSMSMessage(emailPacket);
				
				sendSMS(sspModel, emailPacket);
			} 
			catch (Exception e) {
				CommServer.log4j.debug(sspModel.destinationAddress + " is being removed from the processingMsg hashmap.");
				CommServer.getProcessingMsgEjb().remove(sspModel.destinationAddress);

				//RUSTI - do not put message in holding queue - it will be put in the holding queue after attempting all transports

				throw new Exception("Aborting send of SMS Message.", e);
			}

			// This is controlled by the check above for the payload SMS size being too large 
			if (messageType.equalsIgnoreCase(INQUIRY)) {
				try {
					// Place the original message back into the outbound message queue
					// so that it can be retrieved when requested by the meter.  However
					// because the message was pushed using an inquiry SMS we should not
					// retry to resend the original message but simply leave it in the
					// queue without retries until it expires, or it retrieved by the meter.
					//
					// To do this we set the rety interval to the time when the message will
					// have expired.

					sspModel.retryInterval = (int)(sspModel.messageExpiration.getTime() - System.currentTimeMillis());

				    putMsgInHoldingQ(sspModel);
					
				} catch (Exception e) {
				    CommServer.log4j.error("Unable to put message in Holding Queue. ",e);
					throw new Exception("Unable to put message in Holding Queue. ", e);
				}
			}
			
			if (!CommServer.getPingSentEjb().containsKey(sspModel.destinationAddress)) 
			{
			    CommServer.log4j.debug(sspModel.destinationAddress + " is being added to the pingSent hashmap.");
			    CommServer.getPingSentEjb().put(sspModel.destinationAddress, new Timestamp(System.currentTimeMillis()));
			}
		}

		CommServer.log4j.debug(sspModel.destinationAddress + " is being removed from the processingMsg hashmap.");
		CommServer.getProcessingMsgEjb().remove(sspModel.destinationAddress);
		
		// Sending an SMS is currently a fire-and-forget operation, when the SOAP/REST based
		// SMS transports are being used it will be fly-by-wire.  So we simply return here and wait for
		// a response from the meter to pull its messages from the Holding Queue..
	}

}	
