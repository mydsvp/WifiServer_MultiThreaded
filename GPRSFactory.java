package com.wifiserver.commserver;


public class GPRSFactory
{
	public static GPRSTransport getTransportRuleClass(String transportName) throws Exception 
	{
		//Use transportName to derive class name here
		
        try {

			StringBuffer sb = new StringBuffer("com.wifiserver.commserver.");
			sb.append(transportName);
			sb.append("GPRSTransport");
			Class<?> c = Class.forName(sb.toString());
			return (GPRSTransport)c.newInstance();
		}  catch (Exception e)
		{
			CommServer.log4j.error("Unable to get transport: " + transportName, e);
			throw e;
		}
	}

	public static GPRSTransport getInitialMessageClass(String transportName) throws Exception 
	{
		//Use transportName to derive class name here
		
        try {
            String className = CommServer.props.getProperty("commserver.transport." + transportName + ".classname");
            CommServer.log4j.debug("className = " + className);
			Class<?> c = Class.forName(className);
			//RUSTI - maybe set info on the GPRSTransport here that comes from commserver.properties
			return (GPRSTransport)c.newInstance();
		}  catch (Exception e)
		{
			CommServer.log4j.error("Unable to get transport: " + transportName, e);
			throw e;
		}
	}
	
}
