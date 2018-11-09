package com.wifiserver.commserver;

import java.util.concurrent.atomic.AtomicLong;

import javax.management.AttributeChangeNotification;
import javax.management.MBeanNotificationInfo;
import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;

import com.adamtaft.eb.EventBusService;
import com.adamtaft.eb.EventHandler;

import com.wifiserver.commserver.Events.MeterConnected;
import com.wifiserver.commserver.Events.MeterDisconnected;

public class Management extends NotificationBroadcasterSupport implements ManagementMBean
{
	@Override
	public String getName()
	{
		return mName;
	}

	@Override
	public synchronized void setName(String name)
	{
		String oldName = this.mName;
		
		this.mName = name;

		/* Construct a notification that describes the change. The
		   "source" of a notification is the ObjectName of the MBean
		   that emitted it. But an MBean can put a reference to
		   itself ("this") in the source, and the MBean server will
		   replace this with the ObjectName before sending the
		   notification on to its clients.

		   For good measure, we maintain a sequence number for each
		   notification emitted by this MBean. */
		Notification n =
		    new AttributeChangeNotification(this,
						    mSequenceNumber++,
						    System.currentTimeMillis(),
						    "Name changed",
						    "Name",
						    "String",
						    oldName,
						    this.mName);

		/* Now send the notification using the sendNotification method
		   inherited from the parent class NotificationBroadcasterSupport. */
		sendNotification(n);

	}

    @Override
    public MBeanNotificationInfo[] getNotificationInfo()
    {
		String[] types = new String[] {
		    AttributeChangeNotification.ATTRIBUTE_CHANGE
		};
		String name = AttributeChangeNotification.class.getName();
		String description = "An attribute of this MBean has changed";
		MBeanNotificationInfo info =
		    new MBeanNotificationInfo(types, name, description);
		return new MBeanNotificationInfo[] {info};
    }

    @Override
	public long getActiveMeterConnections ()
	{
		return(mActiveMeterConnections.get());
	}

    /* Implement event handlers for values that are changing inside the CommServer */
    @EventHandler
    public void handle(MeterConnected event)
    {
    	long connectionCount = mActiveMeterConnections.getAndIncrement();

		Notification n = new AttributeChangeNotification(this,
										mSequenceNumber++,
										System.currentTimeMillis(),
										"Name changed",
										"Name",
										"Long",
										connectionCount,
										connectionCount + 1);

		/* Now send the notification using the sendNotification method
		   inherited from the parent class NotificationBroadcasterSupport. */
		sendNotification(n);
    }


    /* Implement event handlers for values that are changing inside the CommServer */
    @EventHandler
    public void handle(MeterDisconnected event)
    {
    	long connectionCount = mActiveMeterConnections.getAndDecrement();

		Notification n = new AttributeChangeNotification(this,
										mSequenceNumber++,
										System.currentTimeMillis(),
										"Meter connections changed",
										"ActiveMeterConnections",
										"Long",
										connectionCount,
										connectionCount - 1);

		/* Now send the notification using the sendNotification method
		   inherited from the parent class NotificationBroadcasterSupport. */
		sendNotification(n);
    }

    Management() 
    {
    	super();
    	
    	EventBusService.subscribe(this);
    }

	public String mName;
	private static AtomicLong mActiveMeterConnections = new AtomicLong();

	private long mSequenceNumber = 1;
}
