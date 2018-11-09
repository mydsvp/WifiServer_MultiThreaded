package com.wifiserver.commserver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.bind.DatatypeConverter;

public class Chunk 
{ 
	private int chunkType = -1;

	private byte[] chunkDataBytes = null;

	//Chunk Types
	public static final String FRAME_ACK = "00000110";
	public static final String NEG_FRAME_ACK = "00010101";
	public static final String REQUEST_GMT_TIME = "00100000";
	public static final String GMT_TIME = "00100001";
	public static final String SELF_ID = "00100010";
	public static final String REDIRECT_CHUNK = "00100011";
	public static final String SET_IDLE_TIMEOUT = "00100100";
	public static final String TMI_PACKET = "00100101";
	public static final String FIRMWARE_DOWNLOAD = "00100110";
	
	public static final int CT_FRAME_ACK = 6;
	public static final int CT_NEG_FRAME_ACK = 21;
	public static final int CT_REQUEST_GMT_TIME = 32;
	public static final int CT_GMT_TIME = 33;
	public static final int CT_SELF_ID = 34;
	public static final int CT_REDIRECT_CHUNK = 35;
	public static final int CT_SET_IDLE_TIMEOUT = 36;
	public static final int CT_TMI_PACKET = 37;
	public static final int CT_FIRMWARE_DOWNLOAD = 38;
    public static final int CT_NETWORK_STATS = 39;

    /** Helper method to unpack a large buffer of multiple chunks **/
	public static List<Chunk> unpackChunks(byte [] buffer)
	{
		List<Chunk> chunks = new ArrayList<Chunk>();
		int offset = 0;
		
		try {
			do {
				// Get the chunk type and length 
				int chunkType = buffer[offset++];
				int length = buffer[offset++] & 0xFF;
				length += (buffer[offset++] << 8) & 0xFF00;

				// Copy bytes into a new chunk
				byte [] chunkData = Arrays.copyOfRange(buffer, offset, offset + length);
				offset += length;

				chunks.add(new Chunk(chunkType, chunkData));
			} while(offset < buffer.length);
		}
		catch (Exception exception)
		{
			CommServer.log4j.warn(exception);
			// In the event of a problem clear out any pending chunks as there was
			// corruption and there is no way to acknowledge portions of
			// TMI traffic inside the framing protocol.
			chunks.clear();
		}

		return (chunks);
	}
	
	public Chunk ()
	{
	}

	public Chunk (int chunkType, String chunkData)
	{
		this.chunkType = chunkType;
		chunkDataBytes = DatatypeConverter.parseHexBinary(chunkData);
	}

	public Chunk (int chunkType, byte[] chunkDataBytes)
	{
		this.chunkType = chunkType;
		this.chunkDataBytes = chunkDataBytes;
	}
	
	public void setChunkType(int data)
	{
		chunkType = data;
	}

	public byte[] getChunkDataBytes()
	{
		return chunkDataBytes;
	}
	
	public int getChunkPayloadLength()
	{
		return(chunkDataBytes.length + 3);
	}

	public int getChunkType()
	{
		return chunkType;
	}
	
	public byte [] getChunkPayload()
	{
		byte [] payload = new byte [1 + 2 + chunkDataBytes.length];
		payload[0] = (byte) (chunkType & 0xFF);
		payload[1] = (byte) (chunkDataBytes.length & 0xFF);
		payload[2] = (byte) ((chunkDataBytes.length >> 8) & 0xFF);
		System.arraycopy(chunkDataBytes, 0, payload, 3, chunkDataBytes.length);

		return(payload);
	}

	public String toString()
	{
		StringBuffer retString = new StringBuffer("Chunk Type = " + this.chunkType + " ");
		retString.append("Data = " + DatatypeConverter.printHexBinary(chunkDataBytes).toUpperCase() + "\n");
		return retString.toString();
	}
	
/*
	public Chunk (String chunkType, String payload)
	{
		this.chunkType = chunkType;
		this.chunkData = convertPayloadToChunk(payload);
	}
	
	public String convertPayloadToChunk(String payloadString)
	{
		if (payloadString != null && payloadString.trim().length() > 0)
		{
			//payload comes in as hex - convert to binary (do i need to strip anything off)
			String convertedString = ConversionUtility.convertHexStrToBitStr(payloadString, payloadString.length()/2);
			return convertedString;
		} else {
			return null;
		}
	}
*/		
}
