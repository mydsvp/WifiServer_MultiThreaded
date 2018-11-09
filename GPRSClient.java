package com.wifiserver.commserver;

import java.io.BufferedInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Formatter;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import javax.ejb.CreateException;
import javax.ejb.FinderException;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.rmi.PortableRemoteObject;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

import com.wifiserver.commserver.util.CRC16;
import com.wifiserver.core.event.SSPModel;
import com.wifiserver.core.services.messages.HoldingQueue;
import com.wifiserver.core.services.messages.HoldingQueueHome;
import com.wifiserver.core.util.ConversionUtility;
import com.wifiserver.verticals.utility.devices.Device;
import com.wifiserver.verticals.utility.devices.DeviceHome;

public class GPRSClient {

	static RemovalListener<File, Object> mRemovalListener = new RemovalListener<File, Object>() 
					{
						public void onRemoval(RemovalNotification<File, Object> removal) 
						{
							CommServer.log4j.info("Evicted " + removal.getKey().toString() + " because " + removal.getCause().toString());
						}
					};
	
	/*!< This data structure represents a cache of the contents of firmware files */
	static final LoadingCache<File, byte []> mFirmwareCache = CacheBuilder.newBuilder()
    .maximumSize(128)
    .expireAfterWrite(12, TimeUnit.HOURS)
    .removalListener(mRemovalListener)
    .build(
        new CacheLoader<File, byte []>() {
            public byte [] load(File key) {
            	try {
            		InputStream is = new BufferedInputStream(new FileInputStream(key));
            		int length = Integer.parseInt(String.valueOf(key.length()));

            		byte[] tempData = new byte[length+2];

					// the chunkData is the contents of the file

					// The first two bytes of information from the file are not sent to the client.
					// These bytes are defined as follows which has been extracted from a header file
					// fwdlprep.h
					//
					//#define DL_CHUNK_TYPE_INFO      (1)
					//#define DL_CHUNK_TYPE_DATA      (2)
					//#define DL_CHUNK_TYPE_LAST_DATA (3)
					//
					//
					//struct dlchunk_info
					//{
					//  unsigned short chunk_type;         // Chunk Type code
					//  unsigned short chunk_number;       // Chunk Number, 0 to ...
					//  unsigned long chunk_length;        // Length of Data to Follow
					//                                     // - Excluding Checksum
					//  unsigned char bcd_fw_ver[BCD_VERSION_LEN];
					//  unsigned long key;                 // Key
					//  unsigned char fw_image_type;       // Firmware Image Type code
					//  unsigned char copy_dsi_db;         // Copy DSI Database code
					//  unsigned char reserved[2];
					//  unsigned long total_num_chunks;    // Total Number of Chunks 
					//  unsigned long fw_image_size;       // Actual Firmware Image Size
					//  unsigned long fw_image_cksum;      // Actual Image Checksum
					//  unsigned long fw_image_size_orig;  // Un-Compressed Image Size
					//  unsigned long fw_image_cksum_orig; // Un-Compressed Image Checksum
					//  unsigned char enc_key[16];         // Encrypted Encryption Key
					//  unsigned long cksum;               // Chunk Checksum
					//}__attribute__((packed));
					//
					//struct dlchunk_data
					//{
					//  unsigned short chunk_type;         // Chunk Type code
					//  unsigned short chunk_number;       // Chunk Number, 0 to ...
					//  unsigned long flash_offset;        // Offset into Flash
					//  unsigned long key;                 // Key
					//  unsigned long chunk_length;        // Length of Data to Follow
					//                                     // - Excluding Checksum
					//  unsigned char data[0];             // Data...
					//                                     // Chunk Checksum Follows !!!
					//}__attribute__((packed));
					//

            		is.read(tempData, 2, length);
            		is.close();

            		// These two bytes define the payload as being a Firmware Download PDU
            		// We can afford to overwrite these bytes as they represent the bytes 
            		// that are not sent to the client
            		tempData[0] = 2;
            		tempData[1] = 0;

            		CommServer.log4j.info("Cache updated for " + key.getName() + " " + mFirmwareCache.size());
            		return(tempData);
            	}
            	catch (NullPointerException exception) {
            		return(null);
            	}
            	catch (Exception exception) {
            		CommServer.log4j.debug("Cache failed to retrieve " + key.getAbsolutePath());
            		return(null);
            	}
            }
        });



	//need to keep track of last sent packet.
	private byte[] mLastFrame = null;

	//need to keep track of device information.
	private String mDevicePin = "";
	
	//keep track of sequence number
	private int sequenceNumber = 0;
	private int frameAcked = -1;
	
	//keep track of when the no more data bit is sent.
	private boolean moreFrames = true;
	
	private String gprsInboundQueue = CommServer.gprsInboundQueue;
//	String holdingQueue = CommServer.holdingQueue;
	/*String serverAddress = CommServer.serverAddress;
	String serverPort = CommServer.serverPort;
    String serverProtocol = CommServer.serverProtocol;
        */

    private Collection<SSPModel> queuedMessagesForDevice = null;
    private Collection<SSPModel> lastMessagesSent = null;
	private int lastFrameSequence = -1;

    private ConcurrentLinkedQueue<byte []> queuedMessagesForServer = new ConcurrentLinkedQueue<byte []>();
    private byte[] networkStats = null;

    private boolean mHangUpAfterResponding = false;

    private static HoldingQueue sHoldingQueueEJB = null;
    private static DeviceHome sDeviceHome = null;

    private static boolean InitializeMsgQueue()
    {
    	if ((null == sHoldingQueueEJB) || (null == sDeviceHome))
    	{
	        Properties props = new Properties();
	        props.put(Context.INITIAL_CONTEXT_FACTORY, "weblogic.jndi.WLInitialContextFactory");
	        props.put(Context.PROVIDER_URL, CommServer.serverInterface);
	
			try {
				InitialContext initCtx = new InitialContext(props);
	
				HoldingQueueHome holdingQueueHome = (HoldingQueueHome)PortableRemoteObject.narrow(initCtx.lookup("HoldingQueue"), HoldingQueueHome.class);
				sDeviceHome = (DeviceHome)PortableRemoteObject.narrow(initCtx.lookup("Device"), DeviceHome.class);
	
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


	public GPRSClient() 
	{
		InitializeMsgQueue();
	}

	public boolean isHangupAfterResponding()
	{
		return(mHangUpAfterResponding);
	}

	/**
	 *  Uses a method to allow the unchecked to apply only to the casting that is needed inside this class
	 *  for messages coming from JMS.
	 **/
	@SuppressWarnings("unchecked")
	private static <T> Collection<T> cast(ArrayList<?> type) 
    {
        return (ArrayList<T>)(type);
    }

	public void checkQueues() {

        sendMessagesToServer();  

        if (mDevicePin.isEmpty()) {
        	return;
        }

        // If we pulled messages from the holding queue but were not able to send them, put them back in the holding queue.
        if (queuedMessagesForDevice != null && queuedMessagesForDevice.size() > 0){
            CommServer.log4j.info(getLogPrepender() + "Post unsent msgs to holding queue.");
            putMessagesBackInHoldingQueue(queuedMessagesForDevice);                            
        }

        queuedMessagesForDevice = null;
        lastFrameSequence = -1;

        if (lastMessagesSent != null && !lastMessagesSent.isEmpty()) {
            CommServer.log4j.info(getLogPrepender() + "Post unack'd message to holding queue.");
            putMessagesBackInHoldingQueue(lastMessagesSent);
            lastMessagesSent = null;
            return; // If we do find a problem with the connection do not immediately
            // spin around and retry as this will thrash on the transmit attempt
            // and will still repeatedly open a socket without going through 
            // the full retry logic path, the result being a very tight loop of 
            // successful opens and failed re-transmission that never successfully 
            // completes thrashing
        }

		//Query the holding Q.  If there are any messages for this device, send an SMS message.				
		try {
            queuedMessagesForDevice = cast(sHoldingQueueEJB.getMessage(mDevicePin.toString()));

            // queuedMessagesForDevice = GPRSUtility.readSSPModelsFromQueue(CommServer.holdingQueue, 1, messageSelector);

			if (queuedMessagesForDevice != null && queuedMessagesForDevice.size() > 0)
			{
			    CommServer.log4j.warn(getLogPrepender() + "Messages are left after disconnecting from socket.");
				//If Messages, send an sms message or ping
				Iterator<SSPModel> qIter = queuedMessagesForDevice.iterator();
				//Message msg = (Message) qIter.next();
				SSPModel model = qIter.next();																																							
				InitialMessageSender.sendInitialMessage(model);
			} 
			else {
				CommServer.log4j.debug(getLogPrepender() + "No messages waiting");
			}
		} 
		catch (Exception e) 
		{
		    CommServer.log4j.error(e);
		    
		    // If a failure occurs of this type then disconnect our JNDI
		    // objects.  The next attempt to send a message will result
		    // in the constructor re-establishing communications
		    sHoldingQueueEJB = null;
		    sDeviceHome = null;
		}
	}
    
    public boolean sendMessagesToServer()
    {
        try {
            if (!queuedMessagesForServer.isEmpty())
            {
            	/**
                 * Changing this to send an array of messages to combat the Skytel Packet Counter Errors Logged
                 * problem.  This happens when a large packet comes in multiple chunks on the commserver.  The commserver
                 * breaks these up and puts them into the queue on tms.  Due to threading, an error can occur that is caused
                 * by two packets attempting to be stored at the same time.  This should cause the packets to be processed
                 * together sequentially.  See ticket - tms00006676.
                 */
            	GPRSUtility.enqueueMessage(queuedMessagesForServer, mDevicePin, gprsInboundQueue, networkStats);
            }
        } catch(Exception e) {
            CommServer.log4j.error(getLogPrepender() + "Exception when sending messages to server. ", e);
        }

        networkStats = null;
		try {
			return (sHoldingQueueEJB.messagesWaiting(mDevicePin));
		}
		catch (RemoteException e) {
			return(false);
		}
    }

    private void putMessagesBackInHoldingQueue(Collection<SSPModel> passedCollection)
    {
    	try {
    		for (SSPModel sspModel : passedCollection) {
    			CommServer.log4j.debug(getLogPrepender() + "Will be retried in " + sspModel.retryInterval + " second(s)");
                sHoldingQueueEJB.putMessage(mDevicePin, sspModel, sspModel.retryInterval);
            }
        } catch (Exception e) {
            CommServer.log4j.error(getLogPrepender() + e);

		    // If a failure occurs of this type then disconnect our JNDI
		    // objects.  The next attempt to send a message will result
		    // in the constructor re-establishing communications
		    sHoldingQueueEJB = null;
		    sDeviceHome = null;
        }
    }

    /** 
     * This method takes in a frame and its TMI payload and unpacks it so that it can be sent
     * into TMS using a JMS queue.  This method after sending the TMI data to TMS will then
     * check for any outbound messages without waiting or blocking and will return to the caller
     * the first pending outbound message.
     * 
     * This method will not block and instead relies upon the meter handling classes to 
     * spin into a read mode waiting for messages from meters asking the Comm Server
     * to check for TMI messages that are typically responses to earlier messages they have 
     * sent.
     * */
	public byte[] processFrame(byte[] gprsPacketBytes)
	{
		FrameHeader fh = new FrameHeader(gprsPacketBytes);

		if (fh.isLastFrame()) {
			CommServer.log4j.warn(getLogPrepender() + "## Last Frame indication set");
		}

		ArrayList<Chunk> outboundChunks = new ArrayList<Chunk>();

		boolean queryHoldingQueue = false;
		boolean prependACK = true;
        mHangUpAfterResponding = false;

        int byteIndex = fh.getFrameHeaderSize();

        frameAcked = -1;

        // Handle the CRC of potential chunks right up front.  It is always the case that if there is any
        // payload following the header that it will be at least three bytes and that it will have as its
        // last two bytes a CRC16 
		if (fh.getFrameSize() != 0) {

			int packetCRC = 0;
			int newCRC = 0;

			try {
				packetCRC = ((gprsPacketBytes[gprsPacketBytes.length-1] & 0xFF) << 8) | (gprsPacketBytes[gprsPacketBytes.length-2] & 0xFF);
				newCRC = CRC16.calculateCRC16(gprsPacketBytes, 0, gprsPacketBytes.length-2);

				//Check CRC
				if (packetCRC != newCRC) {
					CommServer.log4j.warn(getLogPrepender() + "FT_UNKNOWN_FRAME calculatedCRC = " + Integer.toHexString(newCRC) + " " + Integer.toHexString(packetCRC));

					return createShortNack();
				}
			}
			catch (Exception exception)
			{
				CommServer.log4j.warn(getLogPrepender() + "FT_UNKNOWN_FRAME Exception while calculating CRC  " + Integer.toHexString(newCRC) + " " + Integer.toHexString(packetCRC));
				CommServer.log4j.warn(getLogPrepender() + exception);

				// When short NAK's are used they will not be accompanied by
				// any other payloads in this version of the CommServer.  This is
				// a common practice you will see throughout this method
				return createShortNack();					
			}
        }

		switch (fh.getFrameType()) {
			case FrameHeader.FT_ENQUIRE:
				//RUSTI - the device pin might need to be passed in or the gprsclient needs to be a member of the handler class
				CommServer.log4j.info(getLogPrepender() + "FT_ENQUIRE");

				//Enquire Packets can only be sent from the SmartMeter
				//Enquire means 
					//1.  the device was pinged
					//2.  YO checking for packets b/c meter doesnt have anything else to send

				// If there was no device identification with this packet then we continue checking.
				// This because it may only be sent with the first exchange and then not again until 
				// it has changed in the client.
				if (fh.getFrameSize() != 0)
				{
					byte [] chunks = ConversionUtility.copyByteArray(gprsPacketBytes, byteIndex, byteIndex + fh.getFrameSize() - 1);
					//Parse Device Information
					setDeviceIdentification(chunks[0], chunks[1], chunks, 2);
				}

				queryHoldingQueue = true;
				prependACK = false;
				moreFrames = true;

				break; //FrameHeader.FT_ENQUIRE
				
			case FrameHeader.FT_SHORT_TMI_PACKET:
			{
				CommServer.log4j.info(getLogPrepender() + "FT_SHORT_TMI_PACKET ... ");
				//Short TMI Packets can only be sent from the SmartMeter

				if (fh.getFrameSize() == 0)
				{
					CommServer.log4j.warn(" FT_SHORT_TMI_PACKET frame ... Failed as there was no TMI payload" + mDevicePin);

					return(createShortNack());
				}
				
				byte [] chunks = ConversionUtility.copyByteArray(gprsPacketBytes, byteIndex, byteIndex + fh.getFrameSize() - 1);

				//Parse Device Information
				int offset = setDeviceIdentification(chunks[0], chunks[1], chunks, 2);

				//Get TMI Packet
				//TMI Packet is going to be chunks from 2+deviceInfoLength to chunks length - 3 (crc is 2 bytes and array is 0 based)

				//need this in a byte array instead of a String
				byte[] tmiPacket = ConversionUtility.copyByteArray(chunks, offset, chunks.length - 3);

			    // Add messages to a list that will be sent to TMS after we ACK the device.
                addMessageForServer(tmiPacket);
                //GPRSUtility.enqueueMessage(tmiPacket, devicePin, gprsInboundQueue);
				queryHoldingQueue = true;
				moreFrames = true;

				break; //FrameHeader.FT_SHORT_TMI_PACKET
			}
				
			case FrameHeader.FT_SHORT_ACK:
				CommServer.log4j.info(getLogPrepender() + "FT_SHORT_ACK");
				//consists of frameHeader only
                moreFrames = true;
				queryHoldingQueue = true;
				frameAcked = fh.getSequenceNumber();
				
				if (frameAcked == lastFrameSequence) {
					//YIPPEE - clear lastMessagesSent and last FrameSequence
                	lastMessagesSent = null;
                	lastFrameSequence = -1;
				} else {
					//UHOH
				}
				
				break; //FrameHeader.FT_SHORT_ACK
				
			case FrameHeader.FT_SHORT_NACK:
				CommServer.log4j.info(getLogPrepender() + "FT_SHORT_NACK");
				//consists of frameHeader only
				//resend last frame
				return mLastFrame;
				//break; //FrameHeader.FT_SHORT_NACK
				
			case FrameHeader.FT_CLOSE_SESSION:
				CommServer.log4j.info(getLogPrepender() + "FT_CLOSE_SESSION");
				//we arent doing this in this release
				break; //FrameHeader.FT_CLOSE_SESSION
				
			case FrameHeader.FT_STANDARD_FRAME: {
				if (fh.getFrameSize() == 0)
				{
					CommServer.log4j.warn(getLogPrepender() + " FT_STANDARD_FRAME frame ... Failed as there were no TMI payloads");

					return(createShortNack());
				}

				List<Chunk> chunks;

				try {
					byte [] chunkBuffer = Arrays.copyOfRange(gprsPacketBytes, byteIndex, byteIndex + fh.getFrameSize() - 2);
					chunks = Chunk.unpackChunks(chunkBuffer);
				}
				catch (Exception exception) 
				{
					CommServer.log4j.warn(getLogPrepender() + " FT_STANDARD_FRAME Exception While processing chunks " + exception);
					return createShortNack();					
				}

				if (chunks.isEmpty()) {
					CommServer.log4j.warn(getLogPrepender() + " FT_STANDARD_FRAME No chunks could be found");
				}
				else {
					CommServer.log4j.info(getLogPrepender() + "FT_STANDARD_FRAME " + chunks.size() + " Chunks ");
				}
				
				// We track which chunks we have seen to try and determine what state the connection should
				// be left in after the message has been received.  This can be used to to drop connections
				// if we believe there will be no more traffic exchanged.

				boolean selfIDChunkSeen = false;
				boolean networkStatsChunkSeen = false;
				boolean tmiChunkSeen = false;

				for (Chunk thisChunk : chunks) {
					switch (thisChunk.getChunkType()) {
						case Chunk.CT_FIRMWARE_DOWNLOAD: {

							byte [] chunkData = thisChunk.getChunkDataBytes();

							// Skip the download code that is reserved for future use / not used, it is a UNIT16 so start at index 2
							String fileName = new String(Arrays.copyOfRange(chunkData, 2, chunkData.length));

							File filePath = new File(CommServer.firmwarePath + "/" + fileName.substring(0, fileName.lastIndexOf("_")) + "/" + fileName);
							CommServer.log4j.info (getLogPrepender() + " CT_FIRMWARE_DOWNLOAD fileName = " + fileName);

							try {
								byte [] tempData = mFirmwareCache.get(filePath);
								if (null != tempData) {
									outboundChunks.add(new Chunk(Chunk.CT_FIRMWARE_DOWNLOAD, tempData));
								} else
								{
								    CommServer.log4j.info(getLogPrepender() + " CT_FIRMWARE_DOWNLOAD ... Cache reported that the File does NOT exist");
									outboundChunks.add(new Chunk(Chunk.CT_FIRMWARE_DOWNLOAD, "0300"));
								}
							}
							catch (Exception exception) {
							    CommServer.log4j.info(getLogPrepender() + " CT_FIRMWARE_DOWNLOAD ... File does NOT exist", exception);
								outboundChunks.add(new Chunk(Chunk.CT_FIRMWARE_DOWNLOAD, "0300"));
							}

							moreFrames = true;
							queryHoldingQueue = true; // Must send messages in case a Cancel has been queued for the meter by TMS

							break; //Chunk.CT_FIRMWARE_DOWNLOAD
						}
						case Chunk.CT_FRAME_ACK: {
							CommServer.log4j.info(getLogPrepender() + " CT_FRAME_ACK chunk");
							moreFrames = true;
							queryHoldingQueue = true;

							byte [] chunkBytes = thisChunk.getChunkDataBytes();

							if (1 == chunkBytes.length) {
								try {
		                            frameAcked = (chunkBytes[0] & 0xFF);
		                            
		                            if (frameAcked == lastFrameSequence) {
		                            	lastMessagesSent = null;
		                            	lastFrameSequence = -1;
		            				} else {
		    							CommServer.log4j.warn(getLogPrepender() + " CT_FRAME_ACK chunk ... Unknown Frame " + frameAcked + " " + lastFrameSequence);
		            				}
								}
								catch (Exception exception) {
									if ((null == chunkBytes) || (0 == chunkBytes.length)) {
		    							CommServer.log4j.warn(getLogPrepender() + " CT_FRAME_ACK chunk ... Invalid payload No data " + exception);
									}
									else {
		    							CommServer.log4j.warn(getLogPrepender() + " CT_FRAME_ACK chunk ... Invalid payload expected 1 byte got " + chunkBytes.length + " " + GPRSUtility.getHexValue(chunkBytes) + " " + exception);
									}
								}
							}
							else {
    							CommServer.log4j.warn(getLogPrepender() + " CT_FRAME_ACK chunk ... Invalid payload expected 1 byte got " + chunkBytes.length + " " + GPRSUtility.getHexValue(chunkBytes));
							}
							break; //Chunk.CT_FRAME_ACK
						}
						case Chunk.CT_GMT_TIME:
							//this cannot be sent from device?
							CommServer.log4j.warn(getLogPrepender() + " CT_GMT_TIME request from meter was unexpected");
							break; //Chunk.CT_GMT_TIME
							
						case Chunk.CT_NEG_FRAME_ACK:
							CommServer.log4j.warn(getLogPrepender() + " CT_NEG_FRAME_ACK chunk ... Unsupported ");
							break; //Chunk.CT_NEG_FRAME_ACK
							
						case Chunk.CT_REDIRECT_CHUNK:
							CommServer.log4j.debug(getLogPrepender() + " CT_REDIRECT_CHUNK chunk ... Unsupported");
							break; //Chunk.CT_REDIRECT_CHUNK
							
						case Chunk.CT_REQUEST_GMT_TIME: {
							CommServer.log4j.info(getLogPrepender() + " CT_REQUEST_GMT_TIME chunk");
	
							//get the current gmt time
							// TODO RUSTI - might need to do more calculation here to get gmt time
							Calendar tempCal = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
							CommServer.log4j.debug(getLogPrepender() + " Time Synch-Calendar = " + tempCal.toString());
							CommServer.log4j.debug(getLogPrepender() + " Time Synch-Time being sent= " + tempCal.getTime());
	
							//Format data string in hex here
							int month = 0;
							month = tempCal.get(Calendar.MONTH) + 1;

							StringBuilder output = new StringBuilder();
							Formatter formatter = new Formatter(output);
							formatter.format("%02X", tempCal.get(Calendar.YEAR)-2000);
							formatter.format("%02X", month);
							formatter.format("%02X", tempCal.get(Calendar.DATE));
							formatter.format("%02X", tempCal.get(Calendar.HOUR_OF_DAY));
							formatter.format("%02X", tempCal.get(Calendar.MINUTE));
							formatter.format("%02X", tempCal.get(Calendar.SECOND));
							CommServer.log4j.debug(getLogPrepender() + " Time Synch-TS payload= " + output.toString());

							//format a chunk with a GMT_TIME type
							Chunk tempChunk = new Chunk(Chunk.CT_GMT_TIME, output.toString());
	
							outboundChunks.add(tempChunk);
							moreFrames = true;		
							queryHoldingQueue = true;
							CommServer.log4j.debug(getLogPrepender() + " Chunk Type = GMT setting hangUpAfterResponding = true");
							break; //Chunk.CT_REQUEST_GMT_TIME
						}
						case Chunk.CT_SELF_ID: {
							CommServer.log4j.info(getLogPrepender() + " CT_SELF_ID chunk");
							byte [] chunkDataBytes = thisChunk.getChunkDataBytes();
							//parse device identification from this chunk
							setDeviceIdentification(chunkDataBytes[0], chunkDataBytes.length-1, 
												    chunkDataBytes, 1);
							selfIDChunkSeen = true;
							moreFrames = true;
                            queryHoldingQueue = true;
							break; //Chunk.CT_SELF_ID
						}
						case Chunk.CT_SET_IDLE_TIMEOUT: {
							CommServer.log4j.info(getLogPrepender() + " CT_SET_IDLE_TIMEOUT chunk ... Ignoring");
							moreFrames = true;
                            queryHoldingQueue = true;
							break; //Chunk.CT_SET_IDLE_TIMEOUT
						}
						case Chunk.CT_TMI_PACKET: {
							byte [] chunkData = thisChunk.getChunkDataBytes();
							if (CommServer.log4j.isDebugEnabled()) {
								CommServer.log4j.debug(getLogPrepender() + " CT_TMI_PACKET chunk ... " + GPRSUtility.getHexValue(chunkData));
							}
							else {
								CommServer.log4j.info(getLogPrepender() + " CT_TMI_PACKET");
							}
							//Put TMI Packet into gprsInboundQueue in TMS
							// Add messages to a list that will be sent to TMS after we ACK the device.
                            addMessageForServer(chunkData);

                            tmiChunkSeen = true;
							moreFrames = true;
                            queryHoldingQueue = true;
							break; //Chunk.CT_TMI_PACKET
						}
	                    case Chunk.CT_NETWORK_STATS: {
                    		CommServer.log4j.info(getLogPrepender() + " CT_NETWORK_STATS");
                            networkStats = thisChunk.getChunkDataBytes();
                            networkStatsChunkSeen = true;
                            moreFrames = true;
                            queryHoldingQueue = true;
	                        break;
	                    }
	                    default : {
	                    	CommServer.log4j.warn(getLogPrepender() + "An unknown Chunk type was ignored 0x" + Integer.toHexString(thisChunk.getChunkType()));
	                    	break;
	                    }
					} //switch (thisChunk.getChunkType())

				} // for (aChunk : chunks)

				// Now we characterize the traffic that we have seen and determine from it if the connection
				// should be maintained
				switch (chunks.size()) {
					case 3 : {
						// If the very first message we see from the meter that has contacted us
						// contains the following three chunks and those only it is very likely
						// we have a scheduled billing read to we assume we can hangup the connection
						mHangUpAfterResponding = selfIDChunkSeen && networkStatsChunkSeen && tmiChunkSeen && (0 == sequenceNumber);
						break;
					}
				}
				break; //FrameHeader.FT_STANDARD_FRAME
			}
		} //switch (fh.getStartCode())

		//Check for Messages from Holding Queue
		Collection<Chunk> queuedChunksForDevice = null;

		try {
			if (queryHoldingQueue && moreFrames)
			{
				CommServer.log4j.debug(getLogPrepender() + " queryHoldingQueue");

				queuedMessagesForDevice = cast(sHoldingQueueEJB.getMessages(mDevicePin.toString()));  

	            lastMessagesSent = queuedMessagesForDevice;
	            
	            queuedChunksForDevice = GPRSUtility.readChunksFromSSPModels(queuedMessagesForDevice); 
			}
		}
		catch (Exception exception)
		{
			exception.printStackTrace();
			CommServer.log4j.error(getLogPrepender() + " queryHoldingQueue failed " + exception.getMessage());

		    // If a failure occurs of this type then disconnect our JNDI
		    // objects.  The next attempt to send a message will result
		    // in the constructor re-establishing communications
		    sHoldingQueueEJB = null;
		    sDeviceHome = null;
		}

		if (queuedChunksForDevice != null && queuedChunksForDevice.size() > 0)
		{
			//If Messages, get a collection of chunks and add to outboundChunks
			moreFrames = false;
			outboundChunks.addAll(queuedChunksForDevice);
			CommServer.log4j.debug(getLogPrepender() + " queuedChunks found");
		} 

		//Do not want to send the outbound chunks to the device if the CommServer has disconnected from TMS.
		//No costs have been incurred at this point - TMS will retry if necessary.
		
		if (!outboundChunks.isEmpty())
		{
			CommServer.log4j.debug(getLogPrepender() + "Processing outbound traffic");
			//create a frame with the collection of chunks
			if (prependACK)
			{
				//create an ACK  and prepend it to outboundChunks
				Chunk tempChunk = new Chunk(Chunk.CT_FRAME_ACK, ConversionUtility.intToHex(fh.getSequenceNumber(),2));
				//increment sequence number
				outboundChunks.add(0, tempChunk);
			}

			if (++sequenceNumber == 256) {
				sequenceNumber = 1;
			}

			if (mHangUpAfterResponding) {
	            moreFrames = false;
	        }

			lastFrameSequence = sequenceNumber;

			return GPRSUtility.buildGPRSPacket(outboundChunks, sequenceNumber, FrameHeader.STANDARD_FRAME, moreFrames, getCommentaryDevicePin());
		}

		if (fh.getFrameType() == FrameHeader.FT_SHORT_ACK)
		{
			CommServer.log4j.info(getLogPrepender() + " SHORT ACK Standalone");
	        mHangUpAfterResponding = true;

	        return null;
		}

		return GPRSUtility.buildGPRSPacket(null, fh.getSequenceNumber(), FrameHeader.SHORT_ACK, false, getCommentaryDevicePin());
	}

    private void addMessageForServer(byte[] sendBytes)
    {
        queuedMessagesForServer.offer(sendBytes);
    }


	private int setDeviceIdentification(int type, int deviceInfoLength, byte[] data, int beginOffset)
	{
        //This is from an enquire or a short tmi packet
        //this should only happen once

        // If length is 14, it is an MEID. process as Hex.
        if (deviceInfoLength == 7) {
            mDevicePin = ConversionUtility.getHexStringFromByteArray(data, beginOffset, beginOffset+deviceInfoLength-1);
        } else {
            mDevicePin = ConversionUtility.convertBCDByteArrayToString(data, beginOffset, beginOffset+deviceInfoLength-1);
        }

        // If this is a config server, we will be getting ICCID instead of PIN. This will be indicated by a deviceType of 3.
        if (type == 3){
            // Look up this ICCID in TMS, get the PIN for that meter. Basically, we are just converting the ICCID to a PIN.
            try {
            	@SuppressWarnings("unchecked")
            	ArrayList<Device> devices = (ArrayList<Device>)sDeviceHome.findByDeviceParam_import("ICCID", mDevicePin);

            	String realPin = devices.remove(0).getDevicePin_import();
            	CommServer.log4j.debug(getLogPrepender() + "PIN " + realPin);

            	mDevicePin = realPin;
            }
            catch (ClassCastException exception) {
            	CommServer.log4j.error(getLogPrepender() + exception);
            	throw exception;
            } catch (FinderException exception) {
            	CommServer.log4j.error(getLogPrepender() + exception);
			} catch (RemoteException exception) {
				// TODO Auto-generated catch block
            	CommServer.log4j.error(getLogPrepender() + exception);
			}
            
        } else {
            //convert the Wifi meter IP to chars from HEX
            if (mDevicePin.indexOf("2E")>0){
                mDevicePin = ConversionUtility.parseHexStringToChars(mDevicePin);
            }
            
            if (mDevicePin.startsWith("1") && mDevicePin.length() > 10 && mDevicePin.indexOf(".") == -1) {
                mDevicePin = mDevicePin.substring(1, mDevicePin.length()-1);
            }
        }
        
        try {
        	if (!CommServer.getOpenSocketsEjb().containsKey(mDevicePin)) {
        		CommServer.getOpenSocketsEjb().put(mDevicePin, mDevicePin);
        	}

        	CommServer.log4j.debug(getLogPrepender() +" setDeviceIdentification checking for device in pingSent");
        	if (CommServer.getPingSentEjb().containsKey(mDevicePin)) {
        		CommServer.log4j.debug(getLogPrepender() +" setDeviceIdentification removing device from pingSent");
        		CommServer.getPingSentEjb().remove(mDevicePin);
        		// dont allow hanging up on this session if TMS initiated the communication (ping sent to the device).
        		mHangUpAfterResponding = false;
        	}
        }
        catch (Exception exception) {
        	CommServer.log4j.error(getLogPrepender() +exception);
        }

        //returns the new offset which is beginOffset + deviceInfoLength
        return beginOffset + deviceInfoLength;
    }

	private byte[] createShortNack()
	{
		CommServer.log4j.debug(getLogPrepender() +"    Sending a NAK");
		return (GPRSUtility.buildGPRSPacket(null, 0, FrameHeader.SHORT_NACK, false, getCommentaryDevicePin()));
	}

	/**
	 * @return the devicePin
	 */
	public String getDevicePin() {
		return mDevicePin;
	}

	/**
	 * @param devicePin the devicePin to set
	 */
	public void setDevicePin(String devicePin) {
		this.mDevicePin = devicePin;
	}

	/**
	 * @return the frameAcked
	 */
	public synchronized int getFrameAcked() {
		return frameAcked;
	}

	/**
	 * @param frameAcked the frameAcked to set
	 */
	public synchronized void setFrameAcked(int frameAcked) {
		this.frameAcked = frameAcked;
	}

	
	//these 4 methods are for the first message only because it does not go through the gprs client
	/**
	 * @return the lastMessagesSent
	 */
	public synchronized Collection<SSPModel> getLastMessagesSent() {
		return lastMessagesSent;
	}

	/**
	 * @return the lastFrameSequence
	 */
	public synchronized int getLastFrameSequence() {
		return lastFrameSequence;
	}

	/**
	 * @param lastFrameSequence the lastFrameSequence to set
	 */
	public synchronized void setLast(int lastFrameSequence, Collection<SSPModel> lastMessagesSent) {
		this.lastFrameSequence = lastFrameSequence;
		this.lastMessagesSent = lastMessagesSent;
	}

	private String getCommentaryDevicePin()
	{
		if (getDevicePin() != null && getDevicePin().length() > 0) {
			return(getDevicePin());
		}
		else {
			return("----------");
		}
	}

	private String getLogPrepender() 
	{
		if (getDevicePin() != null && getDevicePin().length() > 0) {
			return("[" + getDevicePin() +"] <- ");
		}
		else {
			return("[----------] <- ");
		}
	}
	

}
