package com.wifiserver.commserver;

import java.util.Properties;

import javax.jms.JMSException;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueSender;
import javax.jms.QueueSession;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import com.wifiserver.core.event.SSPModel;
import com.wifiserver.core.transport.TransportMessage;

public class RemoteQueueSender {

	static QueueConnectionFactory  connectionFactory = null;
	static Context jndiContext = null;

	Queue dest = null;

	QueueConnection connection = null;
	QueueSession session = null;
	QueueSender qSender = null;

	static {
		
		try {
			// Get an InitialContextProperties 
			Properties h = new Properties();
			h.put(Context.INITIAL_CONTEXT_FACTORY, "weblogic.jndi.WLInitialContextFactory");
			h.put(Context.PROVIDER_URL, CommServer.serverInterface);
			
			jndiContext = new InitialContext(h);
			connectionFactory = (QueueConnectionFactory) jndiContext.lookup("QueueConnectionFactory");
		}
		catch (NamingException ne) {
			CommServer.log4j.debug("We were unable to get a connection to the WebLogic Server at " + CommServer.serverInterface);
			CommServer.log4j.debug("Please make sure that the server is running.");
            CommServer.log4j.debug("Could not create JNDI API " + "context: " + ne.toString());
			CommServer.log4j.error(ne);
		}		
	}


	public RemoteQueueSender(String queueName, String serverInterface) throws Exception
	{
		try {
	        /* 
	         * Look up connection factory and destination.  If either
	         * does not exist, exit.  If you look up a 
	         * TopicConnectionFactory instead of a 
	         * QueueConnectionFactory, program behavior is the same.
	         */
	        dest = (Queue) jndiContext.lookup(queueName);

	        /*
	         * Create connection.
	         */
	        try {
				connection = connectionFactory.createQueueConnection();			
	        } catch (JMSException e) {
				CommServer.log4j.error(e);
				throw e;
	        }

	        session = this.connection.createQueueSession(false, QueueSession.AUTO_ACKNOWLEDGE);				
            qSender = session.createSender(dest);
		} catch (Exception e) {
		    CommServer.log4j.error(e);
			throw e;
		}
	}

	public ObjectMessage createObjectMessage(SSPModel msg) throws Exception
	{
		// JMS ObjectMessage to contain a Message
		ObjectMessage msgJMS = null;      

		try
        {
            // Create a JMS message capable of handling an object
            msgJMS = session.createObjectMessage();
                    
            // Set JMS Message type
            msgJMS.setJMSType( "JmsEjbMessage" );
                    
            // Insert the message into the JMS object
            msgJMS.setObject( msg );
		}
        catch(Exception e)
        {
			CommServer.log4j.error(e);
			throw e;
        }
		return msgJMS;
	}

	public ObjectMessage createObjectMessage(String msg) throws Exception
	{
		// JMS ObjectMessage to contain a Message
		ObjectMessage msgJMS = null;      

		try
        {
			
            // Create a JMS message capable of handling an object
            msgJMS = session.createObjectMessage();
                    
            // Set JMS Message type
            msgJMS.setJMSType( "JmsEjbMessage" );
                    
            // Insert the message into the JMS object
            msgJMS.setObject( msg );
		}
        catch(Exception e)
        {
			CommServer.log4j.error(e);
			throw e;
        }
		return msgJMS;
	}
	
	public ObjectMessage createObjectMessage(TransportMessage msg) throws Exception
	{
		// JMS ObjectMessage to contain a Message
		ObjectMessage msgJMS = null;      

		try
        {
			
            // Create a JMS message capable of handling an object
            msgJMS = session.createObjectMessage();
                    
            // Set JMS Message type
            //msgJMS.setJMSType( "JmsEjbMessage" );
                    
            // Insert the message into the JMS object
            msgJMS.setObject( msg );
		}
        catch(Exception e)
        {
			CommServer.log4j.error(e);
			throw e;
        }
		return msgJMS;
	}
	
	public ObjectMessage createObjectMessage(TransportMessage[] msg) throws Exception
	{
		// JMS ObjectMessage to contain a Message
		ObjectMessage msgJMS = null;      

		try
        {
			
            // Create a JMS message capable of handling an object
            msgJMS = session.createObjectMessage();
                    
            // Set JMS Message type
            //msgJMS.setJMSType( "JmsEjbMessage" );
                    
            // Insert the message into the JMS object
            msgJMS.setObject( msg );
		}
        catch(Exception e)
        {
			CommServer.log4j.error(e);
			throw e;
        }
		return msgJMS;
	}
	
	public ObjectMessage setStringProperty(ObjectMessage msgJMS, String propertyName, String propertyValue)  throws Exception
	{
		try {
			msgJMS.setStringProperty(propertyName, propertyValue);
		} catch (Exception e) {
			CommServer.log4j.error(e);
			throw e;
		}
		return msgJMS;
	}
	
	public ObjectMessage setObjectProperty(ObjectMessage msgJMS, String propertyName, Object propertyValue)  throws Exception
	{
		try {
			msgJMS.setObjectProperty(propertyName, propertyValue);
		} catch (Exception e) {
			CommServer.log4j.error(e);
			throw e;
		}
		return msgJMS;
	}
	
	public void sendMessageToQueue(ObjectMessage queueMessage) throws Exception
	{
		try {
			qSender.send(queueMessage);
        } catch (JMSException e) {
			CommServer.log4j.error(e);
			throw e;
        }

	}
	
	public void closeConnection()  throws Exception
	{
		try {
			if (session != null)
				session.close();
			if (qSender != null)
				qSender.close();
			if (this.connection != null)
				this.connection.close();
		} catch (Exception e) {
			CommServer.log4j.error(e);
			throw e;
		}
		
	}
	
	
    
}
