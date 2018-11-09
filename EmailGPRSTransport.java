package com.wifiserver.commserver;

import java.sql.Timestamp;

import com.wifiserver.core.event.SSPModel;
import com.wifiserver.core.transport.TransportModel;

public class EmailGPRSTransport extends GPRSTransport 
{
	@Override
	public void processMessage(SSPModel sspModel) throws Exception 
	{
		CommServer.log4j.debug("Entering processMessage.");
		String emailPacket = null;
		String messageType = TMI_PACKET;
		boolean processMsg = false;
		int maxPayloadLength = -1;
//		String queueName = CommServer.holdingQueue;
		//String serverInterface = CommServer.serverInterface;
		Timestamp expireTime = new Timestamp(System.currentTimeMillis()
				- CommServer.auditorTimeOut);
		CommServer.log4j.debug("=============================================");
		CommServer.log4j.debug("====================START====================");
		CommServer.log4j.debug("expireTime = " + expireTime);

		// Max Length for an SMS Message on AT&T is 110 Characters
		// Minus the SMS Header leaves 104 Bytes
		// Base 64 Encode it leaves around 80 characters at the max
		maxPayloadLength = Integer.parseInt(CommServer.props
				.getProperty("commserver.transport.Email.maxPacketSize"));

		CommServer.log4j.debug("maxPayloadLength = " + maxPayloadLength);

                CommServer.log4j
                                .debug("CommServer.openSockets.containsKey(sspModel.destinationAddress) = "
                                                + CommServer.getOpenSocketsEjb()
                                                                .containsKey(sspModel.destinationAddress));
                CommServer.log4j
                                .debug("CommServer.pingSent.containsKey(sspModel.destinationAddress) = "
                                                + CommServer.getPingSentEjb()
                                                                .containsKey(sspModel.destinationAddress));
                CommServer.log4j
                                .debug("CommServer.processingMsg.containsKey(sspModel.destinationAddress) = "
                                                + CommServer.getProcessingMsgEjb()
                                                                .containsKey(sspModel.destinationAddress));
                    
		if (!CommServer.getProcessingMsgEjb().containsKey(sspModel.destinationAddress)
				&& !CommServer.getPingSentEjb()
						.containsKey(sspModel.destinationAddress)
				&& !CommServer.getOpenSocketsEjb()
						.containsKey(sspModel.destinationAddress)) {
			// add device to processingMsg
			CommServer.log4j
					.debug("This is the first message being processed for "
							+ sspModel.destinationAddress);
			CommServer.getProcessingMsgEjb().put(sspModel.destinationAddress,
					new Timestamp(System.currentTimeMillis()));
			processMsg = true;
		} else {
			CommServer.log4j.debug("****A message for "
					+ sspModel.destinationAddress
					+ " is already being processed****");
			if (!CommServer.getProcessingMsgEjb()
					.containsKey(sspModel.destinationAddress)
					&& !CommServer.getOpenSocketsEjb()
							.containsKey(sspModel.destinationAddress)
					&& CommServer.getPingSentEjb()
							.containsKey(sspModel.destinationAddress)) {
				Timestamp pingStamp = (Timestamp) CommServer.getPingSentEjb()
						.get(sspModel.destinationAddress);
				if (pingStamp.before(expireTime)) {
					// remove from pingSent map
					CommServer.log4j.debug("Removing "
							+ sspModel.destinationAddress
							+ " from Ping Sent Map");
					CommServer.getPingSentEjb().remove(sspModel.destinationAddress);
					// send message back to tms
					GPRSUtility.enqueueMessage(
							"No response from SMS sent to device "
									+ sspModel.destinationAddress,
							sspModel.destinationAddress,
							CommServer.gprsInboundQueue);
					// send the new message
					processMsg = true;
				}
			}
		}

		if (!processMsg
				|| CommServer.getOpenSocketsEjb()
						.containsKey(sspModel.destinationAddress)
				|| CommServer.getPingSentEjb().containsKey(sspModel.destinationAddress))
		/*
		 * && (((Timestamp)
		 * CommServer.pingSent.get(sspModel.destinationAddress)).before(expireTime))))
		 */
		{
			// CommServer.log4j.debug("Some type of action has already taken
			// place for " + sspModel.destinationAddress + ". Putting message in
			// the holding queue.");
			CommServer.log4j
					.info(sspModel.destinationAddress
							+ " has already been sent a message or ping. Putting this message in the holding queue. "
							+ sspModel.payload);
			// put message in holding queue
			try {
				putMsgInHoldingQ(sspModel);

			} catch (Exception e) {
				CommServer.log4j.error("Unable to put message in Holding Queue.", e);
				throw new Exception("Unable to put message in Holding Queue.", e);
			}
		} else {
			Timestamp timeSent = null;
			CommServer.log4j.debug("processMessage before length "
					+ sspModel.destinationAddress + " ... "
					+ sspModel.payload.length());
			if (sspModel.payload.length() > maxPayloadLength) {
				CommServer.log4j.debug("processMessage "
						+ sspModel.destinationAddress + "... message length = "
						+ sspModel.payload.length());
				CommServer.log4j.debug("processMessage "
						+ sspModel.destinationAddress
						+ "... maxPayloadLength = " + maxPayloadLength);

				try {
					putMsgInHoldingQ(sspModel);

				} catch (Exception e) {
					CommServer.log4j.error("Unable to put message in Holding Queue.", e);
					throw new Exception(
							"Unable to put message in Holding Queue.", e);
				}

				messageType = INQUIRY;
				CommServer.log4j.debug("[EmailGPRSTransport] processMessage "
						+ sspModel.destinationAddress
						+ " ... setting message type to INQUIRY");
			}

			try {
				// payload should come back from buildGPRSPacket encrypted with
				// a valid crc
				CommServer.log4j.debug("[EmailGPRSTransport] processMessage "
						+ sspModel.destinationAddress
						+ " ... before calling buildSMSPacket");
				emailPacket = buildSMSPacket(messageType, sspModel);
				CommServer.log4j.debug("[EmailGPRSTransport] processMessage "
						+ sspModel.destinationAddress
						+ " ... after calling buildSMSPacket");

				// payload should come back from formatSMSMessage ready to be
				// sent out on the ATT network
				CommServer.log4j.debug("[EmailGPRSTransport] processMessage "
						+ sspModel.destinationAddress
						+ " ... before calling formatSMSMessage");
				emailPacket = formatSMSMessage(emailPacket);
				CommServer.log4j.debug("[EmailGPRSTransport] processMessage "
						+ sspModel.destinationAddress
						+ " ... after calling formatSMSMessage");

				timeSent = new Timestamp(System.currentTimeMillis());
				CommServer.log4j.info("[" + sspModel.destinationAddress + "] -> " + emailPacket + " Email");
				sendSMS(sspModel, emailPacket);
			} catch (Exception e) {
				CommServer.log4j.error("Aborting send of SMS Message.", e);
				if (!messageType.equalsIgnoreCase(INQUIRY))
					putMsgInHoldingQ(sspModel);
				CommServer.getProcessingMsgEjb().remove(sspModel.destinationAddress);
				throw new Exception("Aborting send of SMS Message.", e);
			}

			/*
			 * if (CommServer.pingSent.containsKey(sspModel.destinationAddress) &&
			 * ((Timestamp)
			 * CommServer.pingSent.get(sspModel.destinationAddress)).after(expireTime)) {
			 * CommServer.log4j.debug(sspModel.destinationAddress + " is being
			 * removed from the pingSent hashmap and added with a new timestamp. " +
			 * new Timestamp(System.currentTimeMillis()));
			 * CommServer.pingSent.remove(sspModel.destinationAddress);
			 * CommServer.pingSent.put(sspModel.destinationAddress, new
			 * Timestamp(System.currentTimeMillis())); } else
			 */
			if (!CommServer.getPingSentEjb().containsKey(sspModel.destinationAddress)) {
				CommServer.log4j.debug(sspModel.destinationAddress
						+ " is being added to the pingSent hashmap. "
						+ new Timestamp(System.currentTimeMillis()));
				CommServer.getPingSentEjb().put(sspModel.destinationAddress, timeSent);
			}
			CommServer.log4j.debug(sspModel.destinationAddress
					+ " is being removed from the processingMsg hashmap.");
			CommServer.getProcessingMsgEjb().remove(sspModel.destinationAddress);

		}
		CommServer.log4j.debug("===================FINISH====================");
		CommServer.log4j.debug("=============================================");
	}

	@Override
	protected void sendSMS(SSPModel model, String emailPacket) throws Exception 
	{

		TransportModel transportModel = (TransportModel) CommServer.transports.get(String
			.valueOf(model.transportId));

		String emailExtension = transportModel.connectionParameters;

		// need to get email address from sspModel and transport
		String toAddress = model.destinationAddress;

		if (toAddress.length() > 10 && toAddress.substring(0, 1).equals("1")) {
			CommServer.log4j.debug("Stripping off leading one from number "
					+ toAddress);
			toAddress = toAddress.substring(1);
		}

		CommServer.log4j.debug("toAddress = " + toAddress);
		String fromAddress = model.senderAddress;
		CommServer.log4j.debug("fromAddress = " + fromAddress);
		String subject = "";
		toAddress += "@" + emailExtension;
		CommServer.log4j.debug("toAddress2 = " + toAddress);

		sendSMTPMessage(toAddress, toAddress, subject, emailPacket);

		CommServer.log4j.debug("SMS Message sent to " + toAddress
				+ " ... packet = " + emailPacket);

	}
}
