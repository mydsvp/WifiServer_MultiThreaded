package com.wifiserver.commserver;

import java.rmi.RemoteException;
import java.util.Properties;

import javax.ejb.CreateException;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.rmi.PortableRemoteObject;

import com.wifiserver.core.event.SSPModel;
import com.wifiserver.core.services.messages.HoldingQueue;
import com.wifiserver.core.services.messages.HoldingQueueHome;
import com.wifiserver.util.thread.ThreadPool;

public class InitialMessageSender extends ThreadPool
{
    private class InitialMessageSendWorker implements Runnable
    {
        public InitialMessageSendWorker(SSPModel model)
        {
            mModel = model;
        }

        public void run()
        {
            boolean messageSent = false;
            boolean exceptionCaught = false;
            int transportNum = 1;
            int transportId = mModel.transportId;
            String transportName = "";
			//get transport class
			//TransportModel transportModel = (TransportModel) CommServer.transports.get(String.valueOf(model.transportId));
            while (!messageSent)
            {
                exceptionCaught = false;
                CommServer.log4j.debug("transportNum = " + transportNum);
                CommServer.log4j.debug("transportId = " + mModel.transportId);
                
                //commserver.transport.4.1.transportType
                
                if (CommServer.props.getProperty("commserver.transport." + String.valueOf(transportId) + "." + String.valueOf(transportNum) + ".transportType") != null)
                    transportName = CommServer.props.getProperty("commserver.transport." + String.valueOf(transportId) + "." + String.valueOf(transportNum) + ".transportType");
                else { 
                	CommServer.log4j.debug("No transport property found for commserver.transport." + String.valueOf(transportId) + "." + String.valueOf(transportNum) + ".transportType");
                	break;
                }
                CommServer.log4j.debug("transportName = " + transportName);
				try {
					GPRSTransport transport = GPRSFactory.getInitialMessageClass(transportName);
					//call processMessage on transport class
					transport.processMessage(mModel);
				} catch (Exception e) {	
				    CommServer.log4j.debug("Exception Caught");
				    exceptionCaught = true;
				    transportNum++;
					CommServer.log4j.error(e);
				}
				if (!exceptionCaught)
				    messageSent = true;
            }
			
            if (!messageSent) {
            	//message not sent - put in holding queue it will be picked up by the auditor 
        		try {
        			putMsgInHoldingQ(mModel);
        		} catch (Exception e) {
        			//no need to log twice
        			//CommServer.log4j.error("Unable to put message in " + queueName, e);
        			CommServer.log4j.error("Message could not be assigned to a transport... " + mModel.payload );
        		}
            	
            }
            
        }
        
        private SSPModel mModel = null;
    }
	
    public InitialMessageSender()
    {
        super(mThreadCount);
        
		try {
		} catch (Exception e) {
			e.printStackTrace();
		}

        CommServer.log4j.debug("InitialMessageSender Threadpool started with " + mThreadCount + " worker threads.");
    }
    
    private static int mThreadCount;
    private static InitialMessageSender theInstance = null;
    
    public static synchronized void sendInitialMessage(SSPModel model)
    { 
		CommServer.log4j.debug("sendInitialMessage......begin");
		if (theInstance == null)
			CommServer.log4j.debug("sendInitialMessage......theInstance is null");
		theInstance.run(theInstance.new InitialMessageSendWorker(model));
		CommServer.log4j.debug("sendInitialMessage......end");
    }
    

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
    
    static
    {
		String numThreads = CommServer.smsthreads;
        if ((numThreads == null || numThreads.trim().length() < 1))
            numThreads = "5";
            
        mThreadCount = Integer.parseInt(numThreads);

        InitializeMsgQueue();

		main(null);

    }
    
    public static void main(String args[])
    {
        theInstance = new InitialMessageSender();
        (new Thread(theInstance)).start();        
    }
    
	private void putMsgInHoldingQ(SSPModel sspModel) throws Exception
	{
		try {
			if (InitializeMsgQueue()) {
	
				CommServer.log4j.debug("[InitialMessageSender] putMsgInHoldingQ ... about to send message to holding queue ... " + sspModel.destinationAddress);
				try {
					sHoldingQueueEJB.putMessage(sspModel.destinationAddress, sspModel, sspModel.retryInterval);
				} catch (Exception e) {
					CommServer.log4j.error("Critical Error while registering HoldingQueue instance.", e);
				}
	
				CommServer.log4j.debug("[" + sspModel.destinationAddress + "] Message sent to queue");
				return;
			}
		} catch (Exception e) {
			throw new Exception("Unable to put message in Holding Queue", e);
		}

		CommServer.log4j.warn("[" + sspModel.destinationAddress + "] dropped a message as TMS was down.");
	}
    
}

