package com.wifiserver.commserver;

import java.util.Properties;

import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueReceiver;
import javax.jms.QueueSession;
import javax.naming.Context;
import javax.naming.InitialContext;

import weblogic.jms.common.JMSConstants;


public class GPRSQueueListener {

	String queueName = "";
	QueueConnectionFactory  connectionFactory = null;
	QueueConnection         connection = null;
	QueueSession            session = null;
	Queue        			dest = null;
	QueueReceiver    		consumer = null;
	ObjectListener			listener = null;

	public void start () {
        /* 
         * Create a JNDI API InitialContext object if none exists
         * yet.
         */
		CommServer.log4j.debug("Server url = " + CommServer.serverInterface);
		try {
			// Get an InitialContextProperties 
			Properties h = new Properties();
			h.put(Context.INITIAL_CONTEXT_FACTORY, "weblogic.jndi.WLInitialContextFactory");
			h.put(Context.PROVIDER_URL, CommServer.serverInterface);
		    Context jndiContext = new InitialContext(h);  

        /* 
         * Look up connection factory and destination.  If either
         * does not exist, exit.  If you look up a 
         * TopicConnectionFactory instead of a 
         * QueueConnectionFactory, program behavior is the same.
         */
			CommServer.log4j.debug("looking up QueueConnectionFactory");
            connectionFactory = (QueueConnectionFactory) jndiContext.lookup("QueueConnectionFactory");
			CommServer.log4j.debug("looking up " + CommServer.gprsQueueName);
			dest = (Queue) jndiContext.lookup(CommServer.gprsQueueName);
			jndiContext.close();

        /*
         * Create connection.
         * Create session from connection; false means session is
         * not transacted.
         * Create consumer.
         * Register message listener (TextListener).
         * Receive text messages from destination.
         * When all messages have been received, type Q to quit.
         * Close connection.
         */

			CommServer.log4j.debug("create Q connection");
			connection = connectionFactory.createQueueConnection();
			CommServer.log4j.debug("create Q session");
			session = connection.createQueueSession(false, QueueSession.AUTO_ACKNOWLEDGE);
			((weblogic.jms.extensions.WLConnection)connection).setReconnectPolicy(JMSConstants.RECONNECT_POLICY_ALL);
			CommServer.log4j.debug("create Q receiver");
			consumer = session.createReceiver(dest);
			listener = new ObjectListener();
			CommServer.log4j.debug("create listener = " + listener.getClass().getName());
            consumer.setMessageListener(listener);
			
			CommServer.log4j.debug("start Q");
            connection.start();
			
			CommServerExceptionListener exceptList = new CommServerExceptionListener();
			connection.setExceptionListener(exceptList);
			CommServer.log4j.info("TMS Queue connection started");
			
        } catch (Exception exception) {
			CommServer.log4j.error("Cannot Connect to " + CommServer.gprsQueueName + " at url " +  CommServer.serverInterface, exception);
		}
    }

	public void close() 
	{
		try {
			if (connection != null)
				connection.close();
		} catch (Exception e) {
			CommServer.log4j.debug("Exception thrown while closing connection");
		}
		try {
			if (session != null)
				session.close();
		} catch (Exception e) {
			CommServer.log4j.debug("Exception thrown while closing session");
		}
		try {
			if (consumer != null)
				consumer.close();
		} catch (Exception e) {
			CommServer.log4j.debug("Exception thrown while closing consumer");
		}
	}
	
}
