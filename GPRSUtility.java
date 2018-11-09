package com.wifiserver.commserver;

import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.jms.ObjectMessage;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.xml.bind.DatatypeConverter;

import com.wifiserver.commserver.util.CRC16;
import com.wifiserver.core.event.SSPModel;
import com.wifiserver.core.queue.JmsEjbConstants;
import com.wifiserver.core.transport.TransportMessage;

import com.fasterxml.uuid.Generators;

public final class GPRSUtility
{
	public static byte[] buildGPRSPacket(ArrayList<Chunk> chunks, int sequenceNumber, 
										 String frameType, boolean moreFrames, String logDevicePin )
	{
		int frameSize = 0;

		if (frameType.equalsIgnoreCase(FrameHeader.ENQUIRE) || 
			frameType.equalsIgnoreCase(FrameHeader.SHORT_TMI_PACKET) || 
			frameType.equalsIgnoreCase(FrameHeader.STANDARD_FRAME))
		{
			for (Chunk aChunk : chunks) {
				frameSize += aChunk.getChunkPayloadLength();
			}

			frameSize += 2;
		}

		//create frameheader
		FrameHeader fh = new FrameHeader(frameType, false, moreFrames, sequenceNumber, frameSize);

		CommServer.log4j.info("[" + logDevicePin + "] -> " + fh.getDebugStringFrameType());

		byte [] payload = new byte [fh.getFrameHeaderSize() + fh.getFrameSize()];

		System.arraycopy(fh.getBytes(), 0, payload, 0, fh.getFrameHeaderSize());
		try {
			if (frameSize != 0) {
	
				int destinationPosition = fh.getFrameHeaderSize();
	
				for (Chunk aChunk : chunks) {
					byte [] chunkFragment = aChunk.getChunkPayload();
					System.arraycopy(chunkFragment, 0, payload, destinationPosition, chunkFragment.length);
					destinationPosition += chunkFragment.length;
				}
				int crc = CRC16.calculateCRC16(payload, 0, payload.length - 2);
				payload[payload.length-2] = (byte) (crc & 0xFF);
				payload[payload.length-1] = (byte) (((crc & 0xFF00) >> 8) & 0xFF);
			}
		}
		catch (Exception exception)
		{
			CommServer.log4j.warn("When composing -" + exception);
		}
		return(payload);
	}


    public static String getHexValue(byte[] array)
    {
        final char [] symbols = "0123456789ABCDEF".toCharArray();
        char [] hexValue = new char[array.length * 2];
 
        for(int i=0 ; i < array.length ; ++i)
        {
			//determine the Hex symbol for the last 4 bits
	        hexValue[i*2+1] = symbols[array[i] & 0x0f];
			//determine the Hex symbol for the first 4 bits
			hexValue[i*2] = symbols[(array[i] & 0xFF) >> 4];
        }
        return new String(hexValue);
    }

    public static byte[] getBytesFromHexValue(String s)
    {
        byte[] data = new byte[s.length() / 2];
        for (int i = 0; i < s.length() ; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

	public static int createANSICheckSum(byte[] data, int length)
    {
	    int sum = 0;
		int count = 0;
		
		while (count <= length-1)
		{
			sum = (sum & 0xFF) + (short)(data[count] & 0xFF);
			count++;
		}

		return ((~sum & 0xFF) + 0x01);
    }

	public static Collection<Chunk> readChunksFromSSPModels(Collection<SSPModel> sspColl)
	{		
		if (sspColl != null && !sspColl.isEmpty())
		{		    
			Collection<Chunk> chunkColl = new ArrayList<Chunk>();
			for (SSPModel sspModel : sspColl)
			{
				Chunk thisChunk = new Chunk(Chunk.CT_TMI_PACKET, sspModel.payload);
				CommServer.log4j.debug(thisChunk.toString());
				chunkColl.add(thisChunk);
				
			}
			return chunkColl;
		}

	    return null;
	}

    private static String convertDate(String datePassed, String iFormat , String oFormat )
    {
        try
        {
        	SimpleDateFormat sdfInput = new SimpleDateFormat(iFormat);
        	SimpleDateFormat sdfOutput = new SimpleDateFormat(oFormat);

        	return(sdfOutput.format(sdfInput.parse(datePassed)));
        }
        catch(ParseException pe)
        {
        }

        return null;

    } // end of the convertDate() method

	public static void enqueueMessage(ConcurrentLinkedQueue<byte []> tmiPacketList, 
									  String devicePin, String queueName, 
									  byte[] networkStats)
	{
		Timestamp submitTimestamp = new Timestamp(System.currentTimeMillis());
		
		try {
			//Place the date delivered in the standard format mm/dd/yyyy hh:mm:ss
			String fDate = convertDate(submitTimestamp.toString(),"yyyy-MM-dd HH:mm:ss.SSS","MM/dd/yyyy HH:mm:ss");

			// Make use of an unsynchronized collection as there is no use of multi-threading
			ArrayList<TransportMessage> transMsgList = new ArrayList<TransportMessage>();

			byte [] tmiPacket = null;

			String msgId = Generators.timeBasedGenerator().generate().toString();

			while (null != (tmiPacket = tmiPacketList.poll())) {
				// Pack multiple TMI messages together and then present the
				// entire collection to TMS
				TransportMessage message = new TransportMessage(tmiPacket, 
																devicePin,
																msgId,
																fDate,
																"",
																CommServer.commServerIPAddress + ":" + CommServer.commServerPort,
																"",
																"2",
																"",
																"",
																4, networkStats);

				transMsgList.add(message);
                networkStats = null; // we only want to send the network stats chunk once, so null it out after first loop.
	        }

			// The code to deal with serialization issues in older style JMS/JEE is place here
			// rather than polluting the new style array list java code above.  This way
			// when a newer JEE is used then this code is in one place.
			TransportMessage[] transMsgsJMS = new TransportMessage[transMsgList.size()];
			int itemCount = 0;
			for (TransportMessage aMessage : transMsgList) {
				transMsgsJMS[itemCount++] = aMessage;
			}
			//Put TMI Packet into queue TMS
			RemoteQueueSender qSender = new RemoteQueueSender(queueName, CommServer.serverInterface);
			ObjectMessage objMsg = qSender.createObjectMessage(transMsgsJMS);
			objMsg.setJMSType(JmsEjbConstants.JMSEJB_MESSAGE);
			qSender.sendMessageToQueue(objMsg);
			qSender.closeConnection();

			CommServer.log4j.debug("[" + devicePin + "] queued a message '" + msgId +"'");

		} catch (Exception exception) {
			CommServer.log4j.error("[" + devicePin + "] Unable to enqueue message in " + queueName, exception);

			for (byte [] tmiPacket : tmiPacketList) {
	            //GPRSUtility.enqueueMessage(tmiPacket, devicePin, gprsInboundQueue);
	            String message = "[" + devicePin + "] " + DatatypeConverter.printHexBinary(tmiPacket);
	            //Print to separate log file
	            CommServer.unsentLog4j.info(message);
	        }
		}
	}
	
	public static void enqueueMessage(String logString, String devicePin, String queueName)
	{
		try {
			RemoteQueueSender qSender = new RemoteQueueSender(queueName, CommServer.serverInterface);
			ObjectMessage objMsg = qSender.createObjectMessage(logString);
			objMsg.setJMSType(JmsEjbConstants.JMSEJB_MESSAGE);
			qSender.sendMessageToQueue(objMsg);
			qSender.closeConnection();

			CommServer.log4j.debug("[" + devicePin + "] queued a message");

		} catch (Exception exception) {
			CommServer.log4j.error("[" + devicePin + "] Unable to enqueue message in " + queueName, exception);
		}
	}
	
	public static void enqueueMessage(SSPModel sspModel, String devicePin, String queueName)
	{
		try {
			RemoteQueueSender qSender = new RemoteQueueSender(queueName, CommServer.serverInterface);
			ObjectMessage objMsg = qSender.createObjectMessage(sspModel);
			objMsg.setJMSType(JmsEjbConstants.JMSEJB_MESSAGE);
			qSender.sendMessageToQueue(objMsg);
			qSender.closeConnection();

			CommServer.log4j.debug("[" + devicePin + "] queued a message " + "'" + sspModel.messageId + "'");

		} catch (Exception exception) {
			CommServer.log4j.error("[" + devicePin + "] Unable to enqueue message in " + queueName, exception);
		}
		
	}	
	
	  static public Context getInitialContext(String url) throws Exception {
		    Properties p = new Properties();
		    p.put(Context.INITIAL_CONTEXT_FACTORY, "weblogic.jndi.WLInitialContextFactory");
		    p.put(Context.PROVIDER_URL, url);
		    return new InitialContext(p);
	  }
	  
}

