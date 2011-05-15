package com.challengeandresponse.lmtp;

/**
 * @author Jim Youll
 * 
 * <p>To use this class, just extend it and provide a deliver() method that does what you want with the mail.
 * Note that the deliver() method MUST return a properly formatted SMTP status code and message that's sent 
 * back to the foreign server.</p>
 * 
 * <p><strong>Hey!</strong> You also have to provide a CONSTRUCTOR that is compliant with the constructor here.
 * This class does not have a no-arg constructor, so it's gonna look like this in your class:<br>
 *  public MyLMTP(Vector approvedHosts, Vector domainsServed) {
 *     super(approvedHosts, domainsServed);
 * }
 * </p> 
 * <p><strong>Hey again!</strong> The DELIVER method... don't forget. It's abstract here.  Your
 * class won't complile without one. Nyeh.<br>
 * String deliver(String _mailFrom, String _mailTo, String _message);<br>
 * 
 * <p>There are several configurable parameters, set here as static vars ... you can override them
 * with new values in the subclass.</p>
 * <p>Here is an example of the main() method for a subclass of LMTP:<br>
 * <pre>
 public static void main (String[] args)
    {
        // these settings change the defaults
        SOCKET_NUM = 9901;
        SOFT_MAX_THREADS = 150;
        HARD_MAX_THREADS = 200;
        BUSY_NEW_THREAD_DELAY = 100;
        MY_HOST = "megasuperhost.net";
        INACTIVITY_TIMEOUT_MSEC = 15000;
        MAX_ERRORS_BEFORE_DISCONNECT = 2;
        MAX_NOOPS_BEFORE_DISCONNECT = 10;
        MAX_RSETS_BEFORE_DISCONNECT = 0;
        LIVE_NET_LOOKUPS = false; // don't go to the net for domain names and stuff
        
        // setup the domains served
        Vector domainsServed = new Vector();
        	domainsServed.add("electricdays.com");
        	domainsServed.add("agentzero.com");
        // and the hosts that can connect to this server
        Vector approvedHosts = new Vector();
        	approvedHosts.add("127.0.0.1");
        	approvedHosts.add("agentzero.com");
        	approvedHosts.add("0:0:0:0:0:0:0:1");
   
        	new MyLMTP(approvedHosts,domainsServed);    
    }
 </pre>
 * 
 * This is a basic implementation of an LMTP server for Java... I've tried to follow the RFC (RFC 2821 + the LMTP RFC) but was not
 * working from a state transition diagram. Beware, at least a little. Note that LMTP is strongly modeled on SMTP
 * and as such, its RFC mostly discusses the differences against the SMTP RFC, rather than repeating or 
 * restating what's already there. Construction of an LMTP server requires integration of both documents.
 * 
 * TODO: So it doesn't actually check the domain name, but the ENTIRE chunk after the @ sign... which could be a host.domain.tld
 * or even host.host.domain.tld or worse. So, that's wrong. Redo this, parsing from the right to just get the DOMAIN.TLD part. so sorry!
 * 
 * TODO: it advertises both the PIPELINING and SIZE commands, but doesn't implement either of them
 * 
 * TODO: it goes into pipelining mode w/out being asked. this isn't necessarily a bad thing, but the state
 * transitions are not entirely correct. There is also a special case where it demands an LHLO as the first
 * message from a remote -- is that correct or not?
 */


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.StringTokenizer;
import java.util.Vector;



public abstract class LMTP {
    
    /// THESE ARE DEFAULTS FOR THE CONFIGURABLE PARAMETERS
    /** The socket number to listen on for connection requests. The IANA assigned port for local mail delivery is 2424, but we don't need a privileged port for this.<br>Default 2424*/
    public static int				SOCKET_NUM	= 2424;
    /** when this many threads are alive, begin slowing down between accepts. <br>Default 100 */
    public static int				SOFT_MAX_THREADS = 100;
    /** when this many threads are alive, don't accept any more connections. <br>Default 150*/
    public static int 				HARD_MAX_THREADS = 150;
    /** The delay in msec before re-attempting to listen for a server connection, if ?_MAX_THREADS threads are active right now. 
     * This is done to avoid soaking the server if a giant load should occur suddenly. 
     * <br>Default 200
     */
    public static int				BUSY_NEW_THREAD_DELAY = 200;
    /** The host this server is answering for. Default ""*/
    public static String 		MY_HOST = "";
    /** Inactivity timeout -- how long to wait with nothing received from the other side. Set to 0 to disable this check.<br>Default 30000*/
    public static long			INACTIVITY_TIMEOUT_MSEC = 30000;
    /** Maximum number of command errors to accept before disconnection. Set to 0 to disable this check.<br>Default 3 */
    public static int				MAX_ERRORS_BEFORE_DISCONNECT = 3;
    /** Maximum number of NOOP commands to accept before disconnecting. NOOPs counted include not just the NOOP command itself, but commands that are disabled in this server, like VRFY. Set to 0 to disable this check. <br>Default 5*/
    public static int				MAX_NOOPS_BEFORE_DISCONNECT = 5;
    /** Maximum number of RSET commands to accept before disconnecting. Set to 0 to disable this check.
     * NOTE: an implicit RSET is done at the start of every service conection, so the minimum number of RSETS
     * tallied for any connection is always 1. So, for example, to disable any RSET from a peer entirely, set this
     * value to 1. Set it to 2 to permit one RSET during the transaction, and so on.
     * <br>Default: 3
     */
    public static int				MAX_RSETS_BEFORE_DISCONNECT = 3;
    /**
     *  if true, perform live lookups of domain names and other things that require a network connection.
     * if false, no tests requiring outside network access are performed (e.g. domain name validity checks)
     * <br>Default: true
     */
    public static boolean		LIVE_NET_LOOKUPS = true;
    
    
    
    /** The capabilities that are advertised to the connecting server. separate capabilities with newlines (\n). Do not supply a trailing newline. The println() command will provide that */
    private String 	CAPABILITIES = "250-"+MY_HOST+"\n250-PIPELINING\n250 SIZE";
    
    private  HashSet <String> DOMAINS_SERVED =null;
    private  HashSet <InetAddress> APPROVED_HOSTS = null;
    
    
//// THESE ARE EVIL HARDCODED VALUES THAT ARE NOT CONFIGURABLE    
    /** Number of msec to sleep between loop iterations in the server... to avoid madness when nothing is happening */
    private static final int		LOOP_SLEEP_MSEC = 5;
    /** A name for the thread group containing all the active threads */
    private static final String 	THREAD_GROUP_NAME = "lmtp_threads";
    /** Number of milliseconds to wait for a read on a BufferedReader  */
    private static final long		READ_DEFER_TIME_MSEC = 250;
    
    
    
    /**
     * Convert a vector of domain names into an easily searchable HashSet of domains for which this server accepts mail (user@domain).
     * If LIVE_NET_LOOKUPS is true, the names are checked before they're added and invalid names are skipped.
     * @param _domains a Vector of the domain names to add to the list
     * @return a HashSet of the domains containing all names if LIVE_NET_LOOKUPS is false, or just the valid names, if LIVE_NET_LOOKUPS IS true
     */
    private static HashSet <String> buildDomainsServedSet(Vector <String> _domains) {
        HashSet <String> result = new HashSet <String> ();
        String oneDomain = null; // the domain being worked on 
        Iterator <String> i = _domains.iterator();
        while (i.hasNext()) {
            oneDomain = (i.next()).trim().toLowerCase();
            try {
                if (LIVE_NET_LOOKUPS) // only test net address of this domain if we're supposed to
                		// this will thrown an exception if the host lookup fails
                    Utils.parseInetAddress(oneDomain);
                result.add(oneDomain);
            }
            catch (UnknownHostException uhe) {
                System.out.println("Could not add domain: "+oneDomain+" to domains served list. Unknown host.");
            }
        }
        return result;
    }
    
    
    /**
     * Converts a Vector of IP addresses and host names into an easily searchable HashSet of approved host addresses 
     * in InetAddress objects. In the course of parsing the addresses, the names or addresses must be checked in the course of conversion
     * to InetAddress objects (this behavior can't be disabled). However these checks will mostly fail if the machine on which
     * the code is running can't reach the Internet.
     * 
     * @param _hostlist A Vector of host names or addresses (IPV4 or IPV6) to add to the list of hosts that are allowed to connect to this server.
     * @return a HashSet of the host addresses in InetAddress objects, containing all hosts from _hostlist if LIVE_NET_LOOKUPS is false, or just the valid hosts, if LIVE_NET_LOOKUPS IS true
     */
    private static HashSet <InetAddress> buildApprovedHostsSet(Vector <String> _hostlist)
    throws NumberFormatException {
        HashSet <InetAddress> result = new HashSet <InetAddress> ();
        InetAddress addr = null; // a composed address to add to the access list stored in result and returned to the caller
        String oneHost = null; // the host being processed
        
        Iterator <String> i = _hostlist.iterator();
        while (i.hasNext()) {
            oneHost = ((i.next())).trim();
            try {
                addr = Utils.parseInetAddress(oneHost);
                result.add(addr);
            }
            catch (NumberFormatException nfe) {
                // this error would occur on startup, try keep going if possible
                System.out.println("Could not add address or host: "+oneHost+" to approved hosts list. Invalid numeric format. Be sure to use digits only and IPV6 format nn:nn:nn:nn:nn:nn");
            }
            catch (UnknownHostException uhe) {
                // this error would occur on startup, try keep going if possible
                System.out.println("Could not add address or host: "+oneHost+" to approved hosts list. Unknown host.");
            }
        }
        return result;
    }
    
    

    // launch the server 
    ServerSocket m_ServerSocket;
    ThreadGroup threads;
    
    /**
     * The constructor for LMTP needs a list of hosts it is allowed to talk to, a list of
     * domains for which it will accept mail, and some options
     * @param approvedHosts a Vector of all the hosts that can connect to this server. Can be IPv4 address, IPv6 address or host name
     * @param domainsServed a Vector of the domain names for which this server will accept mail
     * @param options See the LMTP.OPTION_ for options
     */
    public LMTP(Vector <String> approvedHosts, Vector <String> domainsServed) {
        
        // collect the operating parameters for this server instance, from the constructor
        // build the table of hosts that can connect
        APPROVED_HOSTS = buildApprovedHostsSet(approvedHosts);
        // build the table of domains for which we accept mail
        DOMAINS_SERVED = buildDomainsServedSet(domainsServed);
        
        try {
            // Create the server socket for listening for connection attempts from clients
            m_ServerSocket = new ServerSocket(SOCKET_NUM);
        }
        catch(IOException ioe) {
            System.out.println("Could not create server socket at "+SOCKET_NUM+" . Quitting.");
            System.out.println(ioe.getMessage());
            System.exit(-1);
        }
        System.out.println("Server ready. Listening for clients on port "+SOCKET_NUM);
        // Successfully created Server Socket. Now wait for connections.
        threads = new ThreadGroup(THREAD_GROUP_NAME);
        int id = 0;
        while(true) 
        {                        
            // Accept incoming connections. Accept() blocks until a client connects to the server.
            try {
                // if the number of threads is over the soft limit, slow down acceptance rate (and the loop rate)
                if (threads.activeCount() > SOFT_MAX_THREADS) {
                    Thread.sleep(BUSY_NEW_THREAD_DELAY);
                }
                
                // only look for a connection request if we have threads available to service it
                if (threads.activeCount() < HARD_MAX_THREADS) {
                    Socket clientSocket = m_ServerSocket.accept();
                    InetAddress clientAddress = clientSocket.getInetAddress();
                    // if this connection is not from an approved host, drop it.
                    // otherwise dispatch a service thread
                    if (APPROVED_HOSTS.contains(clientAddress)) {
                        ClientServiceThread cliThread = new ClientServiceThread(clientSocket, id++);
                        cliThread.start();
                        System.out.println("Started service for host: "+clientAddress.toString());
                    }
                    else {
                        clientSocket.close();
                        System.out.println("Rejected unapproved host: "+clientAddress.toString());
                    }
                }
            }
            catch (InterruptedException ie) {
                // nothing happens on a Thread.sleep interrupt... (other than sleeping stops)
            }
            catch(IOException ioe) {
                System.out.println("Exception encountered on accept. Ignoring. Stack Trace :");
                ioe.printStackTrace();
            }
        }
    }
    
      
    class ClientServiceThread extends Thread
    {
        static final int 	STATE_START = 10;
        static final int	STATE_RSET = 20;
        static final int 	STATE_EXPECT_LHLO= 30;
        static final int 	STATE_CAPABILITIES = 40;
        static final int 	STATE_PIPELINING = 50;
        static final int 	STATE_DATA = 60;
        static final int	STATE_PROCESS = 70;
        
        static final int	STATE_STOP = 90;
        static final int	STATE_STOPPED = 99;
        
        Socket m_clientSocket;        
        int m_clientID = -1;
        boolean m_bRunThread = true;
        
        /**
         * This is just a convenience class to hold the results of a command-fetch
         * @author jim
         */
        class ClientCommandBlock {
            String fullLine;
            String command;
            String arguments;
            int nextState;
            
            public ClientCommandBlock() {
                fullLine = null;				command = null;
                arguments = null;			nextState = STATE_START;
            }
        }

        
        ClientServiceThread(Socket s, int clientID)
        {	super(threads,""+clientID);
            m_clientSocket = s;
            m_clientID = clientID;
       }

        
        /**
         * Convenience method to write a string to the output stream AND flush it. Gotta flush!
         * @param _pw the Printwriter that's ready for characters
         * @param _s The string to write
         * @throws IOException the caller should catch this... exception if the write failed
         */
        private void printNFlush(PrintWriter _pw, String _s)
        throws IOException {
            _pw.println(_s);
            _pw.flush();
        }
        
        /**
         * Looks for a command from the client in the next line of input. Returns it and some other info, if found,
         * in a nice ClientCommandBlock. If a command could not be found (or hte input had no data) the 
         * method returns null.<br>
         * If a commandBlock is returned, the command field is lowercased.
         * @return a ClientCommandBlock with the parsed-out command (the command is lowercased), or null if there was an error reading the client command
         */
        private ClientCommandBlock getClientCommand(BufferedReader _in)
        throws IOException {
            ClientCommandBlock ccb = new ClientCommandBlock();
            ccb.fullLine = nonBlockingPatientRead(_in,READ_DEFER_TIME_MSEC);
            if (ccb.fullLine == null) {
                return null;
            }
            
            StringTokenizer st = new StringTokenizer(ccb.fullLine," ");
            if (st.hasMoreTokens())
                ccb.command = st.nextToken().toLowerCase().trim();
            else {
    	        return null;
            }
            if (st.hasMoreTokens())
                ccb.arguments = st.nextToken().trim();
            
	        return ccb;
        }

         
        /**
         * Non-blocking readLine() -- returns null if no data are ready, or a String if data are ready.
         * With optional timer to allow a delay and retry in case some data comes
         * @todo this needs to be rewritten using a socket timeout... properly... this approach works but it's kinda incorrect as it's not interruptible on data, but rather sleeps for the whole sleep-time whenever the server is running ahead of the client
         * 
         * @param _in the BufferedReader to read from
         * @param _delayOnFail positive number of milliseconds to sleep before a single retry, if _in is NOT ready() on the first attempt. If this is &lt;= 1, no retry will be attempted and this method will return immedately, either with data or NULL
         */
        private String nonBlockingPatientRead(BufferedReader _in, long _delayOnFail) {
            String result = null;
            try {
                if (! _in.ready()) {
                    if ( _delayOnFail > 0)
                        Thread.sleep(_delayOnFail);
                }
                if (! _in.ready())
                    result = null;
                else
                    result = _in.readLine().trim();
            }
            catch (InterruptedException ie) { }
            catch (IOException ioe) { return null; }
            
            return result;
        }
        
        
        
        
        
        
        
        
        
        public void run()
        {            
            // Obtain the input stream and the output stream for the socket
            // A good practice is to encapsulate them with a BufferedReader
            // and a PrintWriter as shown below.
            BufferedReader in = null; 
            PrintWriter out = null;
            int state = STATE_START;
            int nextState = STATE_START;
            ClientCommandBlock ccb = null; // content of the last handled client command, if any
            String mailFrom = null;
            String mailTo = null;
            StringBuffer dataBlock = null; // all the data received from a DATA command
            String helloName = null; // set to the name received w/ the LHLO command, also signals that LHLO has been received
            
            long lastActivityTime; // last time something non-null was received from the other side 
            int errors; // number of errors that occurred, is reset on an RSET
            int noops; // number of noop ('for keepalive') received, is reset on an RSET
            int rsets; // number of rsets received -- is NOT reset on an RSET
            
            
            // Print out details of this connection
            System.out.println("Servicing client: " + m_clientID + " at "+m_clientSocket.getInetAddress().getHostName()+" ("+m_clientSocket.getInetAddress().toString()+")");
            System.out.println("threads running: "+threads.activeCount());
            
            try
            {                                
                in = new BufferedReader(new InputStreamReader(m_clientSocket.getInputStream()));
                out = new PrintWriter(new OutputStreamWriter(m_clientSocket.getOutputStream()));
                // these are set 0 on entry for the traps just below here, and are cleared on any RSET command
                lastActivityTime = System.currentTimeMillis();
                errors = 0;
                noops = 0;
                // this counter is NEVER cleared for a transaction. it enforces a limit on the number of RSETs permitted
                rsets = 0;
                
                while(m_bRunThread)
                {
                    // a very brief sleep, even when we are running full bore, to avoid grabbing too much resources
                    Thread.sleep(LOOP_SLEEP_MSEC);
                    
                    // test for timeouts and thresholds that were exceeded
                    // if so, return the error and change the state to STOP before going into the switch block, so we stop immediately
        	        if ( (INACTIVITY_TIMEOUT_MSEC > 0) && (lastActivityTime +INACTIVITY_TIMEOUT_MSEC) < System.currentTimeMillis()) {
        	            printNFlush(out, "500 Timeout");
        	            state = STATE_STOP;
        	        }
        	        if ((MAX_ERRORS_BEFORE_DISCONNECT > 0) && (errors > MAX_ERRORS_BEFORE_DISCONNECT) ) {
        	            printNFlush(out, "500 Too many errors");
        	            state = STATE_STOP;
        	        }
        	        if ( (MAX_NOOPS_BEFORE_DISCONNECT > 0) && (noops > MAX_NOOPS_BEFORE_DISCONNECT) ) {
        	            printNFlush(out, "500 Too many NOOPs and/or unimplemented commands");
        	            state = STATE_STOP;
        	        }
        	        if ( (MAX_RSETS_BEFORE_DISCONNECT > 0) && (rsets > MAX_RSETS_BEFORE_DISCONNECT) ) {
        	            printNFlush(out, "500 Too many RSETs");
        	            state = STATE_STOP;
        	        }
                    
                    switch (state) {
                    
                    	// if just starting for the first time, send greeting
                    	case STATE_START:
                    	    printNFlush(out,"220 "+MY_HOST+" microLMTP server ready");
                    	    nextState = STATE_RSET;
                    	    break;
                    	    
                        	// regardless, whenever the protocol starts or the connection is RSET, clear all the state variables then start pipelining
                    	    // per RFC 2821, all state variables are cleared by an RSET
                    	    // however we count and limit the number of rsets permitted, and that counter does not reset here :)
                    	case STATE_RSET:
                    	    ccb = null;
                    	    mailFrom = null;
                    	    mailTo = null;
                    	    dataBlock = new StringBuffer();
                    	    lastActivityTime = System.currentTimeMillis();
                    	    errors = 0;
                    	    noops = 0;
                    	    rsets++; 
                    	    nextState = STATE_PIPELINING;
                    	    break;
                    	    
                    	case STATE_CAPABILITIES:
                    	    printNFlush(out,CAPABILITIES);
                    	    nextState = STATE_PIPELINING;
                    	    break;
                    	    
                    	    // while pipelining, any commands may come in any order, until one of the terminal commands is received
                    	    // per RFC: The EHLO, DATA, VRFY, EXPN, TURN,QUIT, and NOOP commands can only appear as the last command
                    	    // and must produce a change of state
                    	case STATE_PIPELINING:
                    	    ccb = getClientCommand(in);
                    	    // refresh the timeout timer on receipt of a command, and definitely drop out if not pipelining if there was no command read
                    	    if (ccb != null)
                	            lastActivityTime = System.currentTimeMillis();
                    	    else {
                    	        // bail if null command block was returned
                    	        nextState = state;
                    	        break;
                    	    }
                    	    
                    	    // if command is not LHLO and LHLO has not been received, that's an error -- we want an LHLO first
                    	    if ( (! ccb.command.equals("lhlo")) && (helloName == null) ) {
                	            printNFlush(out,"503 Please say LHLO first");
                	            nextState = state;
                	            break;
                    	    }

                    	    // now process the received command
                    	    if (ccb.command.equals("quit"))
                    	        nextState = STATE_STOP;
                    	    else if (ccb.command.equals("rset")) {
                    	        printNFlush(out,"250 OK");
                    	        nextState = STATE_RSET;
                    	    }
                    	    else if (ccb.command.equals("lhlo")) {
                    	        helloName = ccb.arguments;
                    	        nextState = STATE_CAPABILITIES;
                    	    }
                    	    else if (ccb.command.equals("quit"))
                    	        nextState = STATE_STOP;
                    	    else if (ccb.command.equals("data"))  {
                    	        // fails with 503 per RFC if there was no RCPT successfully set
                    	        if (mailTo == null) {
                    	            errors++;
                    	            printNFlush(out,"503 need RCPT (recipient)");
                            	    nextState = STATE_PIPELINING;
                    	        }
                    	        else if (mailFrom == null) {
                    	            errors++;
                    	            printNFlush(out,"503 need MAIL FROM");
                            	    nextState = STATE_PIPELINING;
                    	        }
                    	        else {
                        	        printNFlush(out,"354 Start mail input; end with <CRLF>.<CRLF>");
                    	            nextState = STATE_DATA;
                        	    }
                    	    }
                    	    // and the TERMINAL commands we sinply NOOPS. They terminate PIPELINE mode.. but it doesn't mean much
                    	    else if  (ccb.command.equals("noop")) {
                    	        noops++;
                    	        nextState = STATE_PIPELINING;
                    	        printNFlush(out,"250 OK");
                    	    }
                    	    // unimplemented commands count against the NOOP counter
                    	    else if (
                    	            (ccb.command.equals("vrfy")) ||
                    	            (ccb.command.equals("expn")) ||
                    	            (ccb.command.equals("turn"))
                    	    ) {
                    	        noops++;
                    	        nextState = STATE_PIPELINING;
                    	        printNFlush(out,"502 Command not implemented");
                    	    }
                    	    
                    	    // the actual pipelined commands that we can accumulate
                    	    else if (ccb.fullLine.toLowerCase().startsWith("mail from:"))
                    	        if (mailFrom != null) {
                    	            errors++;
                    	            printNFlush(out,"503 Sender already specified");
                    	        }
                    	        else if (Utils.parseEmailAddress(ccb.arguments) == null) {
                    	            errors++;
                    	            printNFlush(out,"501 Invalid email address");
                    	        }
                    	        else {
                    	            // parse out the sender's address
                    	            String[] parsedEmail = Utils.parseEmailAddress(ccb.arguments);
                    	            // confirm that the domain is real in the world
                    	            try {
                    	                if (LIVE_NET_LOOKUPS) // only test net address if we're supposed to
                    	                    Utils.parseInetAddress(parsedEmail[3]);
                    	                mailFrom = parsedEmail[4];
                    	                printNFlush(out,"250 "+mailFrom+" Sender ok");
                    	            }
                    	            catch (UnknownHostException uhe) {
                    	                errors++;
                    	                printNFlush(out,"501 Invalid host name");
                    	            }
                    	        }
                    	    else if (ccb.fullLine.toLowerCase().startsWith("rcpt to:")) {
                    	        // cleanup the email address, parsing and such are very strict
                    	        ccb.arguments = ccb.arguments.trim().toLowerCase();
                    	        if (mailTo != null) {
                    	            errors++;
                    	            printNFlush(out,"503 Recipient already specified");
                    	        }
                    	        else if (Utils.parseEmailAddress(ccb.arguments) == null) {
                    	            errors++;
                    	            printNFlush(out,"501 Invalid email address");
                    	        }
                    	        else {
                    	        	// be sure this a domain we handle
                    	            String[] parsedEmail = Utils.parseEmailAddress(ccb.arguments);
                    	            if (! DOMAINS_SERVED.contains(parsedEmail[3])) {
                    	                errors++;
                    	                printNFlush(out,"551 We do not relay and we do not accept mail for "+parsedEmail[3]);
                    	            }
                    	            else {
                        	            mailTo = parsedEmail[4];
                    	                printNFlush(out,"250 "+mailTo+" Recipient ok");
                    	            }
                    	        }
                    	    }
                    	    // command totally unrecognized
                    	    else {
                    	        errors++;
                    	        nextState = state;
                    	        printNFlush(out,"500 Command unrecognized: "+ccb.command);
                    	    }

                    	    break;
                    	    
                    
                    	case STATE_DATA:
                    	    String line = nonBlockingPatientRead(in,READ_DEFER_TIME_MSEC);
                    	    // roll the inactivity timer forward -- we've received something
                    	    if (line != null) {
                    	        lastActivityTime = System.currentTimeMillis();
                    	        // just keep loading lines until EOD
                    	        if (line.startsWith(".") && (line.length() == 1 ) ) {
                    	            nextState= STATE_PROCESS;
                    	        }
                    	        else {
                    	            dataBlock.append(line+"\n");
                    	        }
                    	    }
                    	    break;
                    	    
                    	// act on the accumulated commands and crap
                    	case STATE_PROCESS:
                    	    printNFlush(out,deliver(mailFrom,mailTo,dataBlock.toString()));

                    	    // not entirely sure what the RFC wants here. SMTP stays hot for another message until 'quit' is received but we are not getting a quit from the LMTP in the test postfix server
                    	     nextState = STATE_RSET;
// now i think it's supposed to stay open. eventually the client shoudl QUIT if the server doesn't
                    	     //                    	    nextState = STATE_STOP;
                    	    break;

                    	    
                       case STATE_STOP:
                           m_bRunThread = false;   
                           printNFlush(out,"221 "+MY_HOST+" closing connection");
                           nextState = STATE_STOPPED;
                           break;
                    }
                    // OUT OF THE CASE BLOCK...
                    // set the nextState and respond if a reply is needed
                    if (state != nextState) 
                        System.out.println("state transition, "+state+" --> "+nextState);
                    state = nextState;
                }
         
               }
            catch (InterruptedException ie) {
            }
            catch(IOException ioe)
            {            
                try { printNFlush(out,"451 Server error"); } catch (IOException ioe2) { ioe2.printStackTrace(); }
                nextState = STATE_STOP;
                ioe.printStackTrace();
            }
            finally
            {
                // Clean up
                try  {                    
                    in.close();
                    out.close();
                    m_clientSocket.close();
                    System.out.println("Thread "+this.getName()+" stopped, socket closed");
                }
                catch(IOException ioe) {
                    ioe.printStackTrace();
                }
            }
        }
    }


    
    
   /**
    * DELIVER the message just received... implementers must provide a method that really does something useful.
    * <p>The method must return an SMTP response string suitable for sending back to the client.
    * Your deliver method must determine if the user name is one that it can deliver to, or not... the caller
    * will not check anything for feasibility of delivery. It's on you baby, since each server should stand on its own.<br>
    * The easiest strings to return are along these lines:<br>
    * 250 Message accepted for delivery<br>
    * 451 Error saving message<br>
    * 550 User not found<br>
    * For more information about SMTP response codes, see RFC 2821. Briefly, 4xx errors indicate temporary
    * failures, and the sending server will try to send the message again. 5xx messages are permanent failures (for
    * example, the addressee does not exist).
    * </p>
    * Note: the mailTo and mailFrom addresses are passed without the surrounding &lt; and &gt; marks. 
    * As well, the addresses will be lowercased and leading/trailing spaces will be trimmed.
    * So, the servers pass addresses like this: &lt;x(at)y.com&gt;, and the addressed handed to deliver() are: x(at)y.com
    * 
    * 
    * @param _mailFrom The FROM address - who sent this message?  (username@host.domain.tld)
    * @param _mailTo The TO address -- to whom was this addressed? (username@host.domain.tld)
    * @param _message The entire e-mail message
    */ 
   protected abstract String deliver(String _mailFrom, String _mailTo, String _message);
    


} // end of class LMTP
