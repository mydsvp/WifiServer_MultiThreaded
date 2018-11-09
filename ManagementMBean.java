package com.wifiserver.commserver;

public interface ManagementMBean {

	public String getName();
	
	public void setName(String name);
	
	public long getActiveMeterConnections ();
}
