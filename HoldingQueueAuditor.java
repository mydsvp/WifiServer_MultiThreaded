package com.wifiserver.commserver;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.rmi.RemoteException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Properties;

import javax.jms.JMSException;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import com.wifiserver.core.event.SSPModel;
import com.wifiserver.core.services.messages.HoldingQueue;
import com.wifiserver.core.services.messages.HoldingQueueHome;

public class HoldingQueueAuditor extends Thread 
{
	public static String getStackTrace(Exception e)
	{
	    StringWriter sWriter = new StringWriter();
	    PrintWriter pWriter = new PrintWriter(sWriter);
	    e.printStackTrace(pWriter);
	    return sWriter.toString();
	}
	
	public void run () {
		while (true)
		{
		    try {
		        Thread.sleep(CommServer.auditorSleepTime);
		        checkQueueForStuckMsgs();
		    } catch (NamingException exception) {
		    	CommServer.log4j.info("When checking queue for stuck messages \n" + getStackTrace(exception));
		    	//This occurs when TMS is shutdown
		    	sHoldingQueueEJB = null;
		    	continue;
		    } catch (JMSException exception) {
		    	CommServer.log4j.info("When checking queue for stuck messages \n" + getStackTrace(exception));
		    	//This is when TMS is shutdown
		    	sHoldingQueueEJB = null;
		    	continue;
		    } catch (InterruptedException e) {
		    	continue;
			} catch (RemoteException exception) {
		    	CommServer.log4j.info("When checking queue for stuck messages \n" + getStackTrace(exception));
		    	
		    	sHoldingQueueEJB = null;
				continue;
			}
		    catch (NoSuchMethodError exception) {
		    	CommServer.log4j.info("Server implementation of HoldingQueue has an incorrect version");

		    	sHoldingQueueEJB = null;
				continue;
		    	
		    }
		}
	}

    static volatile HoldingQueue sHoldingQueueEJB = null;

	private static void checkQueueForStuckMsgs() throws NamingException, JMSException, RemoteException, NoSuchMethodError
    {
		if (null == sHoldingQueueEJB) {

	        Properties props = new Properties();
	        props.put(Context.INITIAL_CONTEXT_FACTORY, "weblogic.jndi.WLInitialContextFactory");
	        props.put(Context.PROVIDER_URL, CommServer.serverInterface);

	        try {
	        	HoldingQueueHome queueHome = (HoldingQueueHome)(new InitialContext(props).lookup("HoldingQueue"));
	        	if (null != queueHome) {
	        		sHoldingQueueEJB = queueHome.create();
	        	}
	        }
	        catch (Exception exception) {
	        	sHoldingQueueEJB = null;

	        	// If we have not yet initialized we continue retrying rather than bringing down the server
	        	return;
	        }
		}

		// Reload the permitted retries parameter from the properties file on each pass
		int allowedRetries = Integer.parseInt(CommServer.props.getProperty("commserver.retryCount"));

		// Save system time to check for expiry
		Long systemTime = System.currentTimeMillis();

		// Retrieve all messages in the holding queue that have sat around for too long 
		// and resubmit these if they have not exceeded the configured number of retries
		// permitted

		ArrayList<SSPModel> queuedMessages = sHoldingQueueEJB.purgeMessages();

		if (queuedMessages == null || queuedMessages.size() == 0)
		{
			return;
		}

		// We have messages that are ready to be checked for re-submission
		CommServer.log4j.debug(queuedMessages.size() + " Ready Message(s)");

		// If Messages, send an sms message or ping
		for (SSPModel model : queuedMessages)
		{
			if (CommServer.getPingSentEjb().containsKey(model.destinationAddress))
			{
			    CommServer.log4j.debug("Ping Sent Map contains " + model.destinationAddress + " with time sent of " + (Timestamp) CommServer.getPingSentEjb().get(model.destinationAddress));
			    if (((Timestamp) CommServer.getPingSentEjb().get(model.destinationAddress)).before(model.messageExpiration))
			    {
			        CommServer.log4j.debug("Removing " + model.destinationAddress + " from Ping Sent Map");
			        CommServer.getPingSentEjb().remove(model.destinationAddress);
			    }
			}

			// If the message is not yet considered stale and we have not violated the retry times counter
			// then resubmit.
			if ((systemTime <= model.messageExpiration.getTime()) && 
				((0 >= allowedRetries) || (model.numRetries < allowedRetries)))
			{
		        CommServer.log4j.debug("Retrying " + model.destinationAddress + " " + allowedRetries + " " + model.numRetries);

			    ++model.numRetries;
			    InitialMessageSender.sendInitialMessage(model);

			    continue;
			}

            CommServer.getPingSentEjb().remove(model.destinationAddress);
            CommServer.getOpenSocketsEjb().remove(model.destinationAddress);
            CommServer.getProcessingMsgEjb().remove(model.destinationAddress);

            ArrayList<SSPModel> expiredMessages = sHoldingQueueEJB.getMessages(model.destinationAddress);
		    //expiredMessages = GPRSUtility.readSSPModelsFromQueue(CommServer.holdingQueue, 0, messageSelector);

		    //send message back to tms
		    GPRSUtility.enqueueMessage(model, model.destinationAddress, CommServer.gprsInboundQueue);

		    CommServer.log4j.info( "[" + model.destinationAddress  + "] has been purged after failing retries.");

		    if (expiredMessages != null)
		    {
			    for (SSPModel expiredModel : expiredMessages)
			    {
			        //set the max retries on this model and send back to tms
			    	expiredModel.numRetries = allowedRetries;
			        GPRSUtility.enqueueMessage(expiredModel, expiredModel.destinationAddress, CommServer.gprsInboundQueue);
			    }
		    }
		}
    }
}
