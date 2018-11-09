package com.wifiserver.commserver;

import java.util.Set;
import java.util.Comparator;
import java.util.concurrent.ConcurrentSkipListSet;

import java.net.InetSocketAddress;

import java.rmi.RemoteException;
import java.sql.Timestamp;
import java.util.Properties;

import javax.ejb.CreateException;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.ObjectMessage;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.rmi.PortableRemoteObject;

import com.wifiserver.core.event.SSPModel;
import com.wifiserver.core.services.messages.HoldingQueue;
import com.wifiserver.core.services.messages.HoldingQueueHome;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.Member;
import com.hazelcast.core.MembershipEvent;
import com.hazelcast.core.MembershipListener;

import com.wifiserver.commserver.util.KetamaHash;

public class ObjectListener implements MessageListener {


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

	private static Set<Member> clusterMembers = new ConcurrentSkipListSet<Member>(new Comparator<Member>() 
	{
		private Integer getIp(InetSocketAddress addr) {
		    byte[] a = addr.getAddress().getAddress();
		    return ((a[0] & 0xff) << 24) | ((a[1] & 0xff) << 16) | ((a[2] & 0xff) << 8) | (a[3] & 0xff);
		}

		public int compare(Member memberOne, Member memberTwo)
		{
		    //TODO deal with nulls
		    if (memberOne == memberTwo) {
		        return 0;
		    } else if(memberOne.getInetSocketAddress().isUnresolved() || memberTwo.getInetSocketAddress().isUnresolved()){
		        return memberOne.toString().compareTo(memberTwo.toString());
		    } else {
		        int compare = getIp(memberOne.getInetSocketAddress()).compareTo(getIp(memberTwo.getInetSocketAddress()));
		        if (compare == 0) {
		            compare = Integer.valueOf(memberOne.getPort()).compareTo(memberTwo.getPort());
		        }
		        return compare;
		    }
		}});
	private static KetamaHash mHasher = new KetamaHash(clusterMembers);

	static class MessageAssigner implements MembershipListener
	{
		MessageAssigner () 
		{
			Hazelcast.getCluster().addMembershipListener(this);
			
			clusterMembers.addAll(Hazelcast.getCluster().getMembers());
			
			mHasher = new KetamaHash(clusterMembers);
		}
		
		public Member getAssignment(final String key)
		{
			return(mHasher.getPrimary(key));
		}

		@Override
		public void memberAdded(MembershipEvent membershipEvent)
		{
			clusterMembers.add(membershipEvent.getMember());
			mHasher = new KetamaHash(clusterMembers);			
		}

		@Override
		public void memberRemoved(MembershipEvent membershipEvent)
		{
			clusterMembers.remove(membershipEvent.getMember());
			mHasher = new KetamaHash(clusterMembers);
			
		}
	}

	@SuppressWarnings("unused")
	static private MessageAssigner assigner = new ObjectListener.MessageAssigner();


    public void onMessage(Message message) 
    {
        if (!InitializeMsgQueue()) {
        	CommServer.log4j.error("Could not initialize the Holding Queue. JMS message dropped.");
        	return;
        }

        try {
            if (message instanceof ObjectMessage) {               
                // Cast to an ObjectMessage
                ObjectMessage objMsg = (ObjectMessage)message; 
                
                if (null != objMsg.getStringProperty("commserverCommand")) {
	                switch (objMsg.getStringProperty("commserverCommand").toLowerCase()) {
	                	case "reloadkeys" : {
	                		CommServer.log4j.debug("reloadkeys");

	                		CommServer.loadEncryptionKeys();
	                		return;
	                   	}
	                }
                }

                SSPModel model = (SSPModel)objMsg.getObject();

                if (model.messageExpiration.before(new Timestamp(System.currentTimeMillis()))) {
                    CommServer.log4j.info("[" + model.destinationAddress + "] Stale message discard " + model.messageExpiration.toString());
                	return;
                }
                model.numRetries = -1;

                // Push the message into the holding queue and make it immediately available, parameter 3
                sHoldingQueueEJB.putMessage(model.destinationAddress, model, 0);

                CommServer.log4j.debug("[" + model.destinationAddress + "] Message sent to Holding Queue");

                Member nodeAssigned = mHasher.getPrimary(model.destinationAddress);
                CommServer.log4j.debug("[" + model.destinationAddress + "] would be assigned to " + nodeAssigned);
            }
        } catch (JMSException e) {
        	// Be sure to invalidate the input JMS queue reference if it fails
        	// to force reconnection later
        	sHoldingQueueEJB = null;
			CommServer.log4j.error(e);
        } catch (Throwable t) {
        	// Be sure to invalidate the input JMS queue reference if it fails
        	// to force reconnection later
        	sHoldingQueueEJB = null;
			CommServer.log4j.error(t);
        }
    }
}

