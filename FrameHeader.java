package com.smartsynch.commserver;

import java.util.Formatter;

public class FrameHeader 
{
	//String Representation of Frame Start Code
	public static final String SHORT_TMI_PACKET = "00001";
	public static final String SHORT_ACK = "00110";
	public static final String SHORT_NACK = "10101";
	public static final String CLOSE_SESSION = "00100";
	public static final String STANDARD_FRAME = "00010";
	public static final String ENQUIRE = "00101";

	//Integer Representation of Frame Start Code
	public static final int FT_SHORT_TMI_PACKET = 1;
	public static final int FT_SHORT_ACK = 6;
	public static final int FT_SHORT_NACK = 21;
	public static final int FT_CLOSE_SESSION = 4;
	public static final int FT_STANDARD_FRAME = 2;
	public static final int FT_ENQUIRE = 5;
	
	private int mStartCode = 0;
	private long sequenceNumber = 0;
	private long frameSize = 0;
	private long checkSum = 0;

	private boolean mIsValid = true;

	public FrameHeader(String frameType, 
					   boolean closeSocket, 
					   boolean moreFrames, 
					   int sequenceNumber,
					   int frameSize)
	{
		mStartCode = Integer.parseInt(frameType, 2) & 0x1F;
		if (closeSocket) {
			mStartCode |= 1 << 7;
		}
		if (frameSize > 255 ) {
			mStartCode |= 1 << 6;
		}
		if (!moreFrames) {
			mStartCode |= 1 << 5;
		}

		this.sequenceNumber = sequenceNumber;
		this.frameSize = frameSize;
		
		byte[] payload = new byte [getFrameHeaderSize()];
		payload[0] = (byte) ((short) mStartCode & 0xFF);
		payload[1] = (byte) ((short)sequenceNumber & 0xFF);
		
		if (isLong()) {
			payload[2] = (byte)((short)frameSize & 0xFF);
			payload[3] = (byte)((int)(frameSize >> 8) & 0xFF);
		}
		else {
			payload[2] = (byte)((short)frameSize & 0xFF);
		}

		checkSum = GPRSUtility.createANSICheckSum(payload, getFrameHeaderSize()-1);

		CommServer.log4j.debug("[parseFrameHeader] start = " + mStartCode + 
												   " seq = " + sequenceNumber +
												   " size = " + frameSize + 
												   " sum = " + checkSum);
	}

    public FrameHeader(byte data) {
		   mStartCode = data;
    }

	public FrameHeader(byte[] gprsPacketBytes) 
	{
		mIsValid = false;

		//First byte of Frame Header (frameStartCode) will tell you how long the frame header is
		mStartCode = (short)gprsPacketBytes[0];

		if (getFrameHeaderSize() > gprsPacketBytes.length) {
			CommServer.log4j.warn("Supplied a Byte array to small for a packet header");
			return;
		}

		sequenceNumber = ((short)gprsPacketBytes[1]) & 0xFF;

		frameSize = ((short)gprsPacketBytes[2]) & 0xFF;

		if (isLong()) {
			frameSize += ((short)gprsPacketBytes[3] & 0xFF) << 8;
			checkSum = (short)gprsPacketBytes[4] & 0xFF;
		}
		else {
			checkSum = (short)gprsPacketBytes[3] & 0xFF;
		}

		long calcCheckSum = GPRSUtility.createANSICheckSum(gprsPacketBytes, getFrameHeaderSize() - 1);

		try {
			CommServer.log4j.debug("[FrameHeader] parseFrameHeader start = " + mStartCode + " seq = " + 
									sequenceNumber + " size = " + frameSize + " sum = " + checkSum);
		}
		catch (Exception ignored){}
		
		if (!(mIsValid = (calcCheckSum == checkSum)))
		{
			CommServer.log4j.warn("Calculated CheckSum = " + calcCheckSum + " disagreed with packet value " + checkSum);

			CommServer.log4j.warn(gprsPacketBytes.toString());
		}
	}


	public byte [] getBytes()
	{
		byte[] payload = new byte [getFrameHeaderSize()];
		payload[0] = (byte) ((short) mStartCode & 0xFF);
		payload[1] = (byte) ((short)sequenceNumber & 0xFF);
		
		if (isLong()) {
			payload[2] = (byte)((short)frameSize & 0xFF);
			payload[3] = (byte)((int)(frameSize >> 8) & 0xFF);
			payload[4] = (byte) (checkSum & 0xFF);
		}
		else {
			payload[2] = (byte)((short)frameSize & 0xFF);
			payload[3] = (byte) (checkSum & 0xFF);
		}

		return(payload);
	}

	public boolean isValid() {
		return(mIsValid);
	}

	public int getStartCode() {
		return mStartCode;
	}

	public String getDebugStringFrameType() {
		int frameType = mStartCode & 31;
		switch (frameType) {
			case FT_SHORT_TMI_PACKET : {
				return("FT_SHORT_TMI_PACKET");
			}
			case FT_SHORT_ACK : {
				return("FT_SHORT_ACK");
			}
			case FT_SHORT_NACK : {
				return("FT_SHORT_NACK");
			}
			case FT_CLOSE_SESSION : {
				return("FT_CLOSE_SESSION");
			}
			case FT_STANDARD_FRAME : {
				return("FT_STANDARD_FRAME");
			}
			case FT_ENQUIRE : {
				return("FT_ENQUIRE");
			}
			default : {
				return("UNKNOWN");
			}
		}
	}
	
	public boolean isLastFrame()
	{
		return(1 == (mStartCode & (1 << 5)));
	}

	public boolean isCloseSocket() {
		return (mStartCode >> 7 != 0);
	}

	public boolean isLong() {
		return(((mStartCode & (1 << 6)) != 0));
	}

	public int getFrameType() {
		return mStartCode & 0x1F;
	}

	public int getSequenceNumber() {
		return (int)(sequenceNumber & 0xFF);
	}	   

	public int getFrameSize() {
		return (int)(frameSize & 0xFFFF);
	}

	public int getFrameHeaderSize() {
		return (isLong() ? 5 : 4);
	}

	public String reconstructFrameHeaderAsString() 
	{
		StringBuilder reFrame = new StringBuilder();
		Formatter byteFormatter = new Formatter(reFrame);
		byteFormatter.format("%02X%02X", mStartCode & 0xFF, 
										 sequenceNumber & 0xFF);
		if (isLong())
		{
			byteFormatter.format("%02X%02X", (frameSize & 0xFF), 
					 			 			 (frameSize >> 8 & 0xFF));
		} else {
			byteFormatter.format("%02X", (frameSize & 0xFF));
		}

		byteFormatter.format("%02X", (checkSum & 0xFF));

		return reFrame.toString();
	}

	public String toString()
	{
		return(reconstructFrameHeaderAsString());
	}
}
