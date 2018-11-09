package com.wifiserver.commserver;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

import javax.xml.bind.DatatypeConverter;

import com.wifiserver.core.event.SSPModel;

public class WiFiTransport  extends GPRSTransport 
{
	public static void main(String[] args) {
	}
        
	protected void sendSMS(SSPModel model, String emailPacket) throws Exception
	{
		//Create the Socket Connection
                
		String destinationIP = "";
                /*
                String tempIP = model.destinationAddress.substring(0,3);
                tempIP = stripLeadingZerosFromOctet(tempIP);
                destinationIP = destinationIP + tempIP + ".";
                tempIP = model.destinationAddress.substring(3,6);
                tempIP = stripLeadingZerosFromOctet(tempIP);
                destinationIP = destinationIP + tempIP+ ".";
                tempIP = model.destinationAddress.substring(6,9);
                tempIP = stripLeadingZerosFromOctet(tempIP);
                destinationIP = destinationIP + tempIP+ ".";
                tempIP = model.destinationAddress.substring(9,12);
                tempIP = stripLeadingZerosFromOctet(tempIP);
                destinationIP = destinationIP + tempIP;

                */
                destinationIP = model.destinationAddress;
                CommServer.log4j.debug("WiFiTransport about to create socket to send outbound message, " + destinationIP + ":"+CommServer.GetPort());
                Socket tempSocket = new Socket(destinationIP, CommServer.GetPort());
                CommServer.log4j.debug("WiFiTransport socket created.");
		DataOutputStream os = new DataOutputStream( new BufferedOutputStream(tempSocket.getOutputStream()));
		//DataInputStream is = new DataInputStream( new BufferedInputStream(tempSocket.getInputStream()));
		CommServer.log4j.debug("WiFiTransport about to sendFrame to the output stream.");
		boolean sc = sendFrame(os, emailPacket.getBytes());
		if (sc)
			CommServer.log4j.info("(" + model.destinationAddress + "):TX=" + emailPacket);
			
		//read
		//byte[] bytes = new byte[64];
		//int rc= is.read(bytes);
			
		os.close();
		tempSocket.close();

	}
	
	private boolean sendFrame(DataOutputStream os, byte[] returnFrame) throws IOException 
	{
		boolean sent = false;
		CommServer.log4j.debug("SendFrame returnFrame = " + DatatypeConverter.printHexBinary(returnFrame).toUpperCase());
		try {
			os.write(returnFrame, 0, returnFrame.length);
			sent = true;
			CommServer.log4j.debug("SendFrame ... after write ... " );
			os.flush();
			CommServer.log4j.debug("SendFrame ... flushing ... " );
		} catch (IOException ioe) {
			CommServer.log4j.error(ioe);
			throw ioe;
		}
		return sent;
		
	}
	
}
