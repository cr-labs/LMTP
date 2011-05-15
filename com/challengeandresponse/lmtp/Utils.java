package com.challengeandresponse.lmtp;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * Created on Aug 1, 2005
 *
 * (c) 2005 Challenge/Response, LLC
 * @author Jim
 * Some helpful utilities for the LMTP class
 */

public class Utils {
    
  
    /**
     * Turn a string address or host name (IPV4, IPV6, host.domain.tld) into an InetAddress
     * @param _addr
     * @return the internet address IPV4 of the IP address, or host name in _addr
     */
    public static InetAddress parseInetAddress(String _addr)
    throws UnknownHostException, NumberFormatException {
        byte[] octets = null;
        Inet4Address addr4 = null; // a composed address to add to the access list stored in result and returned to the caller
        Inet6Address addr6 = null;
        
        // if it contains a colon, this is an IPV6 host address
        if (_addr.indexOf(":") > -1) {
            StringTokenizer st = new StringTokenizer(_addr,":");
            octets = new byte[16];
            int j = 0;
            String s = null;
            while (st.hasMoreTokens()) {
                s = st.nextToken();
                short val = Short.parseShort(s,16);
                octets[j] = (byte) ( (val >> 8) & 0x00FF);
                octets[j+1] = (byte) ( val & 0x00FF);
                j+=2;
            }
            addr6 = (Inet6Address) InetAddress.getByAddress(octets);
            return (InetAddress) addr6;
        }
        // otherwise if it ends in a digit, this is an IPV4 HOST ADDRESS
        else if ( Character.isDigit(_addr.charAt(_addr.length()-1))) {
            StringTokenizer st = new StringTokenizer(_addr,".");
            octets = new byte[4];
            int j = 0;
            while (st.hasMoreTokens()) {
                String s = st.nextToken();
                octets[j] = Byte.parseByte(s,10);
                j++;
            }
            addr4 = (Inet4Address) InetAddress.getByAddress(octets);
            return (InetAddress) addr4;
        }
        // otherwise this is a HOST NAME
        else {
            addr4 = (Inet4Address) InetAddress.getByName(_addr);
            return (InetAddress) addr4;
        }
    }
     
    
    /**
     * This expects a <> wrapped email address. It will return the parts of the address, parsed, if there was one or null if an email address could not be recognized 
     * @param _fullEmailAddress an email address in the form "<user+command@host.tld>" including the &lt; and &gt; brackets around it. the "+command" part is optional of course
     * @return a String[] array containing in order: username+command, the username, the command, the host name, the full address less <> brackets and lowercased. [2] the commandw ill be NULL if there was no command (there is not usually a command)
     */
    public static String[] parseEmailAddress(String _fullEmailAddress) {
        String[] result = new String[5];
        
        _fullEmailAddress=_fullEmailAddress.trim().toLowerCase();
        
        // parses <username+command@host> into username+command, host
        final Pattern EMAIL_ADDRESS_PATTERN = Pattern.compile(".*?<(.*?)@(.*?)>.*");
        // parses username+command into username, command
        final Pattern EMAIL_COMMAND_PATTERN = Pattern.compile("(.*?)\\+(.*?)");
        
        final Pattern EMAIL_AROUND_ATSIGN = Pattern.compile(".*?(<|\\s)([a-zA-Z_0-9\\+\\-\\.]*?@[a-zA-Z_0-9\\.]*?)(\\s|>)");

        Matcher m = EMAIL_ADDRESS_PATTERN.matcher(_fullEmailAddress);
        if (m.matches()) {
            result[0] = m.group(1);
            result[3] = m.group(2);

            Matcher m2 = EMAIL_COMMAND_PATTERN.matcher(result[0]);
            if (m2.matches()) {
                result[1] = m2.group(1);
                result[2] = m2.group(2);
            }
            else { // no plus sign, so just copy over what's needed and null out the command as there was none
                result[1] = result[0];
                result[2] = null;
            }
            
            // finally set the "whole" address less brackets, if any
            Matcher mm = EMAIL_AROUND_ATSIGN.matcher(_fullEmailAddress);
            if (mm.find()) {
                result[4] = mm.group(2);
            }
            else
                result = null;
        }
        else result = null;
        
        
        
        return result;
    }
    
    
    public static void main(String[] x) {
    
        String[] s= Utils.parseEmailAddress("cvhaos <jim+electric@agentzero.com>");
        for (int i = 0; i < s.length; i++) 
            System.out.println(i+" "+s[i]);
             
    }
    
    

}
