/*
 *  An example LMTP server created by extending com.challengeandresponse.lmtp.LMTP and then 
 * customizing...
 * 
 * @author Jim
 */
package com.challengeandresponse.lmtp;

import java.util.Vector;

public class MyLMTP extends LMTP {

    /**
     * Note, this subclass must explicitly call the super() constructor with arguments... there is no default empty super() constructor
     * @param approvedHosts
     * @param domainsServed
     */      
    public MyLMTP(Vector <String> approvedHosts, Vector <String> domainsServed) {
        super(approvedHosts, domainsServed);
    }        
    
    
    /**
     * The deliver() method is called by the service threads, to actually do something with a received message.
     * Here we just display it
     */
    protected String deliver(String mailFrom, String mailTo, String message) {
        System.out.println("from: "+mailFrom);
        System.out.println("to: "+mailTo);
        System.out.println("message: "+message);
        
        return "250 message accepted for delivery";
    }
    
    

    /**
     * Here is a main class to actually make the server go go go.
     * In this example, the default socket is overridden to 9999, and network lookups are turned off (All domain names will be accepted,etc)
     * @param args
     */
    public static void main (String[] args)
    {
        // these settings change the defaults
        SOCKET_NUM = 12000;
        SOFT_MAX_THREADS = 2;
        HARD_MAX_THREADS = 20;
        BUSY_NEW_THREAD_DELAY = 200;
        MY_HOST = "electricdays.com";
        INACTIVITY_TIMEOUT_MSEC = 30000;
        MAX_ERRORS_BEFORE_DISCONNECT = 3;
        MAX_NOOPS_BEFORE_DISCONNECT = 5;
        MAX_RSETS_BEFORE_DISCONNECT = 3;
        LIVE_NET_LOOKUPS = false; // don't go to the net for domain names and stuff
        
        // setup the domains served
        Vector <String> domainsServed = new Vector <String> ();
        	domainsServed.add("electricdays.com");
        	domainsServed.add("agentzero.com");
        // and the hosts that can connect to this server
        Vector <String> approvedHosts = new Vector <String> ();
        	approvedHosts.add("127.0.0.1");
        	approvedHosts.add("agentzero.com");
        	approvedHosts.add("0:0:0:0:0:0:0:1");
   
        	new MyLMTP(approvedHosts,domainsServed);    
    }

    
}
