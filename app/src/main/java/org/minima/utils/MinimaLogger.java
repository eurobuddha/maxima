/**
 * 
 */
package org.minima.utils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// M0 STUB: org.minima.system.Main import removed — the node runtime is not bundled in the wallet.
// The notify branch that posted to Main.getInstance() is stubbed to a plain println; the wallet has
// no node event bus. Logging semantics are otherwise unchanged.
import org.minima.utils.json.JSONObject;

/**
 * @author Spartacus Rex
 *
 */
public class MinimaLogger {
	
	public static final String MINIMA_LOG = "MINIMALOG";
	
	public static final SimpleDateFormat DATEFORMAT = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH );
	
	public static void log(String zLog){
		log(zLog, true);
	}
	
	public static void log(String zLog, boolean zNotify){
		long mem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
		String full_log = "Minima @ "+DATEFORMAT.format(new Date())+" ["+MiniFormat.formatSize(mem)+"] : "+zLog;
		System.out.println(full_log);
		
		//M0 STUB: no node event bus in the wallet build — the notify listener post is dropped.
		//(Original posted full_log to Main.getInstance().PostNotifyEvent(MINIMA_LOG, ...).)
	}
	
	public static void log(Exception zException){
		log(zException,true);
	}
	
	public static void log(Exception zException, boolean zNotify){
		//First the Full Exception
		MinimaLogger.log(zException.toString(), zNotify);
		
		//Now the Stack Trace
		for(StackTraceElement stack : zException.getStackTrace()) {
			//Print it..
			MinimaLogger.log("     "+stack.toString(), zNotify);
		}
	}
	
	public static void logUncaught(Throwable zThrow, boolean zNotify){
		//First the Full Exception
		MinimaLogger.log("[!] UNCAUGHT EXCEPTION : "+zThrow.toString(), zNotify);
		
		//Now the Stack Trace
		for(StackTraceElement stack : zThrow.getStackTrace()) {
			//Print it..
			MinimaLogger.log("     "+stack.toString(), zNotify);
		}
	}
}
