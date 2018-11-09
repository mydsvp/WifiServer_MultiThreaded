package com.wifiserver.commserver;

import javax.jms.ExceptionListener;
import javax.jms.JMSException;

public class CommServerExceptionListener implements ExceptionListener {

    /**
     * Starts a new thread for each message to be formatted and sent out
     *
     * @param message     the incoming message
     */
    public void onException(JMSException exception) {
        
		CommServer.log4j.debug("onException");
            
			CommServer.log4j.debug("*********************************************************************");
			CommServer.log4j.debug("*********************************************************************");
			CommServer.log4j.debug("*********************************************************************");
			CommServer.log4j.debug("*********************************************************************");

			//exception.printStackTrace();
			CommServer.log4j.error(exception);

			CommServer.log4j.debug("*********************************************************************");
			CommServer.log4j.debug("*********************************************************************");
			CommServer.log4j.debug("*********************************************************************");
			CommServer.log4j.debug("*********************************************************************");
			
			// If we see an exception related to JMS then we invoke
			// the clean up with the option to restart any services
			// that are affected
			CommServer.cleanUp(true);
    }
}
