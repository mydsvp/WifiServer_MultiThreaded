package com.wifiserver.commserver;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.net.URISyntaxException;

import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.Executors;

import javax.ejb.EJBHome;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.apache.log4j.Logger;
import org.apache.log4j.extras.DOMConfigurator;

import com.google.common.io.Files;
import com.hazelcast.core.Hazelcast;
import com.wifiserver.commserver.handlers.NIOMeterHandler;
import com.wifiserver.core.services.messages.commserver.OpenSocketsMap;
import com.wifiserver.core.services.messages.commserver.OpenSocketsMapHome;
import com.wifiserver.core.services.messages.commserver.PingSentMap;
import com.wifiserver.core.services.messages.commserver.PingSentMapHome;
import com.wifiserver.core.services.messages.commserver.ProcessingMsgMap;
import com.wifiserver.core.services.messages.commserver.ProcessingMsgMapHome;
import com.wifiserver.core.transport.Transport;
import com.wifiserver.core.transport.TransportHome;
import com.wifiserver.core.transport.TransportModel;
import com.wifiserver.util.ThayerEncryption;
import com.wifiserver.verticals.common.customer.Customer;
import com.wifiserver.verticals.common.customer.CustomerHome;
import com.wifiserver.verticals.common.customer.CustomerModel;
import com.wifiserver.verticals.utility.ansi.ANSIKeyGenerator;

public class CommServer  {

	/* static class data/methods */
	public static Logger log4j = null;
	public static Logger unsentLog4j = null;

	/* our server's configuration information 
	 *                              is stored
	 * in these properties
	 */
	public static Properties props = new Properties();

	/* timeout on client connections */
	private static int port = 25472;

	public static ConcurrentHashMap<String, TransportModel> transports = new ConcurrentHashMap<String, TransportModel>();
	public static ConcurrentHashMap<String, String> utilityKeyMap = new ConcurrentHashMap<String, String>();

	public static String serverInterface = "";
	public static ArrayList<String> serverInterfaceList = new ArrayList<String>();
	public static String gprsQueueName = "";
	public static String emailServer = "";
	// public static String holdingQueue = "";
	public static String gprsInboundQueue = "";
	public static String maxPayloadLength = "";
	public static String commServerPort = "";
	public static String commServerIPAddress = "";
	public static String firmwarePath = "";
	public static String retryTime = "";
	public static String smsthreads = "";
	public static String isConfigServer = "";
	public static String defaultKey = "1A16264D88A3B8332CFA9208838F356A";
	public static long lowMemoryThreshold = 0;


	public static int auditorTimeOut = -1;
	public static int auditorSleepTime = -1;

	private static String companyKeyDecryptor = "E66AF8B3A2D113011A5E92F873E63724";

	private Thread shutdownThread;
	//private static boolean runningInCleanMode;
	private boolean readyToExit = false;
	
	// This object initializes a listener for watching the JMS outgoing messages queue
	// that holds message intended for meters
	private static GPRSQueueListener mOutgoingQListener;
	private static HoldingQueueAuditor auditor;

	private static OpenSocketsMapHome mOpenSocketsMapHome = null;
	private static PingSentMapHome mPingSentMapHome = null;
	private static ProcessingMsgMapHome mProcessingMsgMapHome = null;

	private static synchronized boolean InitNaming() 
	{
        Properties props = new Properties();
        props.put(Context.INITIAL_CONTEXT_FACTORY, "weblogic.jndi.WLInitialContextFactory");
        props.put(Context.PROVIDER_URL, CommServer.serverInterface);
        try {
        	InitialContext initCtx = new InitialContext(props);

            try {
            	if (null == mOpenSocketsMapHome) {
            		mOpenSocketsMapHome = (OpenSocketsMapHome) initCtx.lookup("OpenSocketsMap");
            	}
            	if (null == mPingSentMapHome) {
            		mPingSentMapHome = (PingSentMapHome) initCtx.lookup("PingSentMap");
            	}
            	if (null == mProcessingMsgMapHome) {
            		mProcessingMsgMapHome = (ProcessingMsgMapHome) initCtx.lookup("ProcessingMsgMap");
            	}
    		} catch (NamingException e) {
    			// TODO Auto-generated catch block
    			e.printStackTrace();
    			return(false);
    		}

		} catch (NamingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return(false);
		}
		return (true);
	}

	private static volatile boolean mIsConnected = false;

	public static void main( String[] args)
	{
		if (null == args[0]) {
			System.out.println("A properties file name must be specified as the first argument. Stopping CommServer.");

			System.exit(-1);
		}

		try {
			loadProps(args[0]);
		} catch (Exception e) {
			//Unable to load properties from commserver.properties
			//Need to log and stop the commserver
			System.out.println("Unable to load properties from " + args[0] + ". Stopping CommServer.");
			e.printStackTrace();
			System.exit(-1);
		}

		// Initialize the logging system
		String log4jPropsFile = props.getProperty("commserver.logPropertiesFile");

		if (!new File(log4jPropsFile).exists()) 
		{
			System.out.println("Log Properties File DOES NOT Exist.  Stopping CommServer.");
			System.exit(-1);
		}

		DOMConfigurator.configureAndWatch(log4jPropsFile, 2000);
		log4j = Logger.getLogger("System");
		unsentLog4j = Logger.getLogger("Unsent");

		try {
			String digest = String.format("%1$032X", new BigInteger(1, Files.getDigest(new File(args[0]), MessageDigest.getInstance("MD5"))));
			log4j.info("Properties file " + args[0] + " loaded [" + digest + "]");
		}
		catch (Exception e) {
			log4j.info("Properties file " + args[0] + " loaded");
		}

		CommServer.log4j.info(props.toString());

		/** Loads the Hazelcast clustering facility using the default and
		 * change some of the default items */

		System.setProperty("hazelcast.version.check.enabled", "false");
		System.setProperty("hazelcast.wait.seconds.before.join", "5");

		Hazelcast.init(null);

		CommServer executionEntity = new CommServer();
		
        try {
            File jarFile = new File
            				(executionEntity.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        	Date modifiedData = new Date(jarFile.lastModified());
            System.out.println("CommServerJAR modification date " + modifiedData);
            CommServer.log4j.info("CommServer.JAR modification date " + modifiedData);
        } catch (URISyntaxException e1) {
        }

		executionEntity.startCommServer();

		// Load JMX as a management extension.  Errors from this operation can be safely ignored
		String agentName = executionEntity.getClass().getName();
		MBeanServer managementServer = ManagementFactory.getPlatformMBeanServer();
		try {
			String objectName = agentName.substring(0, agentName.lastIndexOf(".")) + ":type=Management";
			CommServer.log4j.debug(objectName);
			ObjectName managementAgent = new ObjectName(objectName);
			Management manageBean = new Management();
			managementServer.registerMBean(manageBean, managementAgent);
		} catch (Exception exception) {
			// Ignore errors from this code as JMX is used but not supported by
			// the comm server
			exception.printStackTrace();
		}

		try {
			Thread.sleep(10 * 1000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public CommServer()
	{
		registerShutdownHook();
	}

	/**
	 * Registers a shutdown hook to pause shutdown after Control-C long enough
	 * to finish processing the current record.
	 */
	private void registerShutdownHook() 
	{
		log4j.debug("Registering shutdown hook");

		this.shutdownThread = new Thread("shutdownhook") {

			public void run() {
				synchronized(this) {
					if (!readyToExit) {
						log4j.debug("Shutdown hook: Waiting to exit");
						//try {
						// Wait up to 1.5 secs for a record to be processed.
						//wait(1500);
						CommServer.cleanUpOnShutdown();
						//} catch (InterruptedException ignore) {
						//}
						if (!readyToExit) {
							log4j.info("Exiting CommServer.");
						}
					}
				} // End of synchronize scope on this
			}    // End of run() method
		};

		Runtime.getRuntime().addShutdownHook(this.shutdownThread);
	}

	/**
	 * Gets the next server Interface in the ArrayList and puts it at the back of the list.
	 * 
	 * @return String containing URL of next server interface in the ArrayList.
	 */
	@SuppressWarnings("unused")
	private static void getNextServerInterface() 
	{
		if (serverInterfaceList != null && serverInterfaceList.size() > 0) {
			serverInterface = (String) serverInterfaceList.get(0);
			// Move the URL used to the back of the queue.
			serverInterfaceList.add(serverInterfaceList.remove(0));
		}
		else {
			log4j.error("serverInterfaceList is empty. Please check the tms.interface property in commserver.properties.");
		}
	}

/**
 * Connects the commserver to TMS.  This method also initializes the thread pool.
 * If we connect to TMS, the this method will enter a loop that doesn't exit until
 * TMS disconnects.  
 * 
 * @return should always return false.
 **/
private static boolean connectTMS()
{
	// Locate the CommServer control structures running inside the WebLogic server
	do {
		if (!mIsConnected) {
			try {
				Thread.sleep(500);
			}
			catch (Exception exception) {}
			mIsConnected = InitNaming();
		}
	} while (!mIsConnected);

    // Load the transport classes used to implement message exchange sequences with meters/devices
	try {
		clearHashMapsIfNecessary();
		loadEJBs();
	} catch (Exception e) {
		log4j.error("Unable to load Transport EJBs from TMS. Please ensure that TMS running on " + serverInterface);
		return false;
	}

	// Initializes the queue listener that uses JMS to retrieve work from the TMS
	mOutgoingQListener = new GPRSQueueListener();
	try {
		mOutgoingQListener.start();
	} catch (Exception e) {
		log4j.error("Unable to start the TMS work queue. Please ensure that TMS is running on " + serverInterface);
		return false;
	}

	auditor = new HoldingQueueAuditor();
	log4j.debug("Starting HoldingQueueAuditor");
	try {
		auditor.start();
	} catch (Exception e) {
		log4j.error("Unable to start HoldingQueueAuditor. Please ensure that TMS at " + serverInterface + " is configured with a HoldingQueue.");

		return false;
	}
  
	log4j.info("Connected to all needed resources on TMS.");

	return true;
}


/**
 * Connects the commserver to TMS.  This method also initializes the thread pool.
 * If we connect to TMS, the this method will enter a loop that doesn't exit until
 * TMS disconnects.  
 * 
 * @return should always return false.
 */
private boolean connectCommServerNIO()
{
	log4j.debug("Entering connectCommServerNIO");

	try {
		loadEncryptionKeys();
	} catch (Exception e) {
		log4j.error("Unable to load EJBs from TMS. Please ensure that TMS is available for connection.");
		e.printStackTrace();
		return false;
	}

	//port is configurable through commserver.properties 
	if (commServerPort != null && commServerPort.length() > 0)
	{
		port = Integer.parseInt(commServerPort);
	}
	  
	log4j.info("TMS Server URL = " + serverInterface + " CommServer port " + port);

	try {
	    Integer threadCount = Integer.valueOf(CommServer.props.getProperty("commserver.threadsInPool"));

	  	AsynchronousChannelGroup threadGroup = 
	  			AsynchronousChannelGroup.withFixedThreadPool(threadCount.intValue(),
	  														 Executors.defaultThreadFactory());
	  	
	  	log4j.debug("Created " + threadCount + " pooled threads.");
		
	  	final AsynchronousServerSocketChannel server =
	  	        AsynchronousServerSocketChannel.open(threadGroup).bind(new InetSocketAddress(port));

	  	// TODO Add shutdown handler code into this loop
	  	// TODO Add limits on just how many sockets can be open
  		server.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
			@Override
  			public void completed(AsynchronousSocketChannel channel, Void attachment)
  			{
				// Accept the next connection
				server.accept(null, this);

				try {

					channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
					//Integer.parseInt((String)props.get("commserver.socketTimeOut"));

					new NIOMeterHandler().handle(channel);

				}
				catch (Exception e) { }
  			} // End of public void completed(...)

			@Override
			public void failed(Throwable exc, Void attachment) { }

  		}// End of anonymous class definition
  		);// Terminating brace of accept call
	}
	catch (Exception exception) {
		exception.printStackTrace();
		return(false);
	}

	return true;
}


/**
 *  Check the heap for low memory. If the free memory is too low (configurable via commserver.properties),
 *  perform emergency garbage collection. This should help prevent the NIO memory leak from getting out of hand.
 */	
        
static void checkHeapForLowMemory() {
    CommServer.log4j.debug("Checking Memory Free - " + Runtime.getRuntime().freeMemory());
    if (Runtime.getRuntime().freeMemory() < lowMemoryThreshold) {
        CommServer.log4j.warn("Performing Garbage Collection | Free Memory Before Garbage Collection = " + Runtime.getRuntime().freeMemory());
        System.gc();
        CommServer.log4j.warn("Garbage Collection Complete | Memory Free After Garbage Collection = " + Runtime.getRuntime().freeMemory());
        System.runFinalization();
    }
}
  
private void startCommServer() 
{

	long timeInMs = 0; // sleep for 100 ms
	if ( retryTime != null && retryTime.length() > 0)
	{
		timeInMs = Integer.parseInt(retryTime);
	}

	if (timeInMs < 5000)
		timeInMs = 5000;

	while (!connectTMS()) {
		log4j.warn("startCommServer ... Going into Wait and Retry.  JMS queues are not available");

		try 
		{
			Thread.sleep(timeInMs);
		} catch (InterruptedException ex) { }
	}

	while (!connectCommServerNIO())
	{
		log4j.warn("startCommServer ... Going into Wait and Retry.  TCP server not running");

		try 
		{
			Thread.sleep(timeInMs);
		} catch (InterruptedException ex) { }
	}

}

    static public int GetPort() {
    	return(port);
    }

static void loadProps(String filePath) throws IOException, Exception
{
	File propertiesFile = new File(filePath);

	if (propertiesFile.exists()) 
	{
		try {
			String digest = String.format("%1$032X", new BigInteger(1, Files.getDigest(propertiesFile , MessageDigest.getInstance("MD5"))));
			System.out.println("Properties file " + propertiesFile.toString() + " loaded [" + digest + "]");
		}
		catch (Exception e) {
			System.out.println("Properties file " + propertiesFile.toString() + " loaded");
		}

		InputStream is = new BufferedInputStream(new FileInputStream(propertiesFile));
		props.load(is);
		is.close();

		props.setProperty("commserver.propertiesFile", filePath);
		gprsQueueName = props.getProperty("tms.gprsQueueName");
		serverInterface = props.getProperty("tms.interface");

		emailServer = props.getProperty("commserver.emailServer");
		//		 holdingQueue = props.getProperty("commserver.holdingQueue");
		gprsInboundQueue = props.getProperty("tms.gprsInboundQueue");
		maxPayloadLength = props.getProperty("commserver.maxPayloadLength");
		commServerPort = props.getProperty("commserver.port");
		commServerIPAddress = props.getProperty("commserver.ipaddress");
		firmwarePath = props.getProperty("commserver.firmwarePath");
		retryTime = props.getProperty("commserver.retryTime");
		smsthreads = props.getProperty("commserver.smsthreads");
		isConfigServer = props.getProperty("tms.isConfigServer");
		lowMemoryThreshold = Long.parseLong(props.getProperty("commserver.lowMemoryThreshold"));

		StringTokenizer tokenizer = new StringTokenizer(props.getProperty("tms.interface"),",");
		serverInterfaceList.clear();
		while (tokenizer.hasMoreTokens()) {
			serverInterfaceList.add(tokenizer.nextToken());
		}

		auditorTimeOut = Integer.parseInt(props.getProperty("commserver.auditorTimeOut"));
		auditorSleepTime = Integer.parseInt(props.getProperty("commserver.auditorSleepTime"));
	}

}
  
  public static void loadEJBs() throws Exception
  {
	 InitialContext initCtx = null;
	 try {
		 //load transports here
		 EJBHome home = null;
		 Properties props = new Properties();
		 props.put(Context.INITIAL_CONTEXT_FACTORY, "weblogic.jndi.WLInitialContextFactory");
		 props.put(Context.PROVIDER_URL, serverInterface);
		 initCtx = new InitialContext(props);
		 
		 Object objref = initCtx.lookup("Transport");
		 
		 home = (EJBHome)objref;
		 TransportHome transportHome = (TransportHome) home;
		 Collection<?> transportColl = transportHome.findByAllTransports();
		 Iterator<?> transportIter = transportColl.iterator();
		 TransportModel transModel = null;
		 Transport transport = null;
		 while (transportIter.hasNext())
		 {
			 transport = (Transport)transportIter.next();
			 transModel = new TransportModel(transport.getTransportId(), transport.getTransportType(), transport.getTransportName(), transport.getTransportQueue(), transport.getTransportAgentName(), transport.getConnectionParameters(),
								transport.getTransportPermissions(), transport.getServerIPAddress(), transport.getServerPort(), transport.getRetryCount(), transport.getMessageLatency(), transport.getPollingFrequency(),
								transport.getNetworkCostType(), transport.getSynchType(), transport.getConnectionType(), transport.getTOURestrictions(), transport.getTransportOffset(), transport.getTransportToleranceTime(),
								1, transport.getModifiedDate(), transport.getModifiedBy(), transport.getCreatedDate(), transport.getCreatedBy());
			 transports.put(String.valueOf(transModel.getTransportId()), transModel);
		 }
		 
	 } catch (Exception e) {
		 log4j.error("Could not load transport information from TMS using " + serverInterface + ".", e);
		 throw new Exception("Could not load transport information from TMS using " + serverInterface + ".", e);
	 } finally {
		 initCtx.close();
	 }
  }

    public static void loadEncryptionKeys() throws Exception{
        synchronized(utilityKeyMap){            

            String url = serverInterface;
            InitialContext initCtx = null;
            try{
                Properties props = new Properties();
                props.put(Context.INITIAL_CONTEXT_FACTORY, "weblogic.jndi.WLInitialContextFactory");
                props.put(Context.PROVIDER_URL, url);
                initCtx = new InitialContext(props);
                Object objref2 = initCtx.lookup("Customer");
                EJBHome home2 = (EJBHome)objref2;
                CustomerHome customerHome = (CustomerHome) home2;
                Collection<?> customerColl = customerHome.findByAllCustomers();
                Iterator<?> customerIter = customerColl.iterator();
                CustomerModel custModel = null;
                //Customer customer = null;

                 while (customerIter.hasNext()){
                    custModel = ((Customer)customerIter.next()).getDetails();
                    String encryptedKeySeed = custModel.keySeed;
                    log4j.debug("Customer number = " + custModel.customerNumber + " EncryptedKeySeed = " + custModel.keySeed);
                    String companyKey = ThayerEncryption.crypt(encryptedKeySeed, companyKeyDecryptor);
                    String utilitySeed = ANSIKeyGenerator.generateUtilityKey(companyKey);
                    log4j.debug("UtilitySeed = " + utilitySeed);
                    utilityKeyMap.put(custModel.getCustomerNumber(), utilitySeed);
                 }
             } catch (Exception e) {
                 log4j.error("Could not load customer information from TMS.", e);
                 throw new Exception("Could not load customer information from TMS.", e);
             } finally {
                     initCtx.close();
             }
        }
  }
  
    static void printProps() 
    {
    	log4j.debug("serverInterface = " + serverInterface);
    	log4j.debug("gprsQueueName = " + gprsQueueName);
    	log4j.debug("emailServer = " + emailServer);
    	//	 log4j.debug("holdingQueue = " + holdingQueue);

    	for (String customerNumber : utilityKeyMap.keySet())
    	{
    		log4j.debug("utilitySeed:  " + customerNumber + ": " + utilityKeyMap.get(customerNumber));             
    	}
    }

    public static void cleanUp(boolean restart)
    {
    	try {
    		log4j.debug("Closing Listeners");

    		if(mOutgoingQListener != null) {
    			mOutgoingQListener.close();
    		}

    		clearHashMapsIfNecessary();

    	} catch (IOException ioe) {
    		log4j.error("Error Closing Socket.", ioe);
    	} catch (Exception e) {
    		log4j.error(e);
    	}

		log4j.debug("Control structure references cleared");

    	mOpenSocketsMapHome = null;
    	mPingSentMapHome = null;
    	mProcessingMsgMapHome = null;
    	
    	if (restart) {
    		log4j.info("Restarting TMS connections");

    		Thread startupThread = new Thread("startup") {

    			public void run() {
    				long timeInMs = 0; // sleep for 100 ms
    				if ( retryTime != null && retryTime.length() > 0)
    				{
    					timeInMs = Integer.parseInt(retryTime);
    				}

    				if (timeInMs < 5000)
    					timeInMs = 5000;

    				while (!connectTMS()) {
    					log4j.warn("JMS is not available. Retrying in " + timeInMs / 1000 + " second(s).");

    					try 
    					{
    						Thread.sleep(timeInMs);
    					} catch (InterruptedException ex) { }
    				}
    			}    // End of run() method
    		};
    		startupThread.start();
    	}
    }

    public static void cleanUpOnShutdown()
    {
		log4j.debug("Stopping Holding Queue Auditor");

		if(auditor != null) {
			auditor.interrupt();
		}

		cleanUp(false);

		Hazelcast.shutdownAll();
    }

    private static void clearHashMapsIfNecessary() throws Exception 
    {
    	log4j.debug("Clearing HashMaps");

    	try {
    		// Only clear the Singleton HashMaps if this is the only CommServer running.
    		if (isOnlyCommServerRunning()) {
    			getOpenSocketsEjb().clear();
    			getPingSentEjb().clear();
    			getProcessingMsgEjb().clear();
    		}
    	} catch (Exception e) {
    		log4j.debug("Unable to access TMS Singleton HashMaps.");
    		throw e;
    	}
    }

    /**
     *  Determines if this commserver is the only commserver running in a cluster.
     *  This is primarily used to determine when to clear out the singleton HashMaps.
     *  
     *  Note: This method requires the optional "clusterMembers" property to be set in commserver.properties
     */
    private static boolean isOnlyCommServerRunning() {

    	String ipAddressesString = props.getProperty("commserver.clusterMembers").toString();

    	// if commservers (ips/ports) are not specified in the properties file (not required)
    	// then we have no way to determine if this is the only commserver running
    	if(ipAddressesString==null || ipAddressesString.trim().length() == 0) {
    		return false;
    	}

    	StringTokenizer tokenizer = new StringTokenizer(ipAddressesString,"|");
    	while (tokenizer.hasMoreTokens()) {
    		try {
    			String token = tokenizer.nextToken();
    			// Split the token into seperate strings for IP address and port.
    			String ip = token.substring(0, token.indexOf(":"));
    			String portString = token.substring(token.indexOf(":") + 1);
    			// Attempt to connect to the IP address and port.
    			Socket socket = new Socket(ip, Integer.parseInt(portString));
    			// If we got this far without an exception, another server is running. Close the socket and return false.
    			socket.close();
    			return false;
    		} catch (Exception e) {
    			// If we get here, it means the server wasn't running. Continue iterating through servers and attempting connections.
    		}
    	}
    	// If we make it out of the loop of tokens, no other servers are running, so return true.
    	return true;
    }

  	public static OpenSocketsMap getOpenSocketsEjb()
    {
        try {
            return(mOpenSocketsMapHome.create());
        } catch (Exception e) {

        	// Force a recovery attempt if the connection fails
        	mOpenSocketsMapHome = null;

            if (InitNaming()) {
                try {
					return(mOpenSocketsMapHome.create());
				} catch (Exception e1) {
		            CommServer.log4j.error("Unable to get OpenSocketsEjb from TMS on 2nd attempt: ", e);
				}
            }
            CommServer.log4j.info("Unable to get OpenSocketsEjb from TMS ", e);
        }
        return null;
    }

    public static PingSentMap getPingSentEjb()
    {
        try {                    
            return(mPingSentMapHome.create());
        } catch (Exception e) {

        	// Force a recovery attempt if the connection fails
        	mPingSentMapHome = null;

            if (InitNaming()) {
                try {
					return(mPingSentMapHome.create());
				} catch (Exception e1) {
		            CommServer.log4j.error("Unable to get PingSentjb from TMS on 2nd attempt: ", e);
				}
            }
            CommServer.log4j.error("Unable to get PingSentjb from TMS ", e);
        } 
        return null;
    }

    public static ProcessingMsgMap getProcessingMsgEjb() 
    {
        try {                    
            return(mProcessingMsgMapHome.create());
        } catch (Exception e) {

        	// Force a recovery attempt if the connection fails
        	mProcessingMsgMapHome = null;

            if (InitNaming()) {
                try {
					return(mProcessingMsgMapHome.create());
				} catch (Exception e1) {
		            CommServer.log4j.error("Unable to get ProcessingMsgEjb from TMS on 2nd attempt: ", e);
				}
            }
            CommServer.log4j.error("Unable to get ProcessingMsgEjb from TMS ", e);
        } 
        return null;
    }    
  
 }
  

 