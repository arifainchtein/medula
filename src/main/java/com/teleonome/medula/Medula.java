package com.teleonome.medula;



import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Vector;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.teleonome.framework.TeleonomeConstants;
import com.teleonome.framework.denome.DenomeUtils;
import com.teleonome.framework.denome.Identity;
import com.teleonome.framework.exception.InvalidDenomeException;
import com.teleonome.framework.utils.Utils;

import javolution.text.CharSet;

public class Medula {
	//public final static String BUILD_NUMBER="17";
	private static String buildNumber="11/05/2018 10:23";
	Logger logger;
	String teleonomeName="";
	int currentPulseInMilliSeconds=0;

	boolean inPulse=false;
	long pulseLastFinishedMillis=0;
	long pulseLastStartedMillis=0;
	boolean performPulseAnalysis=false;
	long lastPulseTime=0;
	int numberOfPulsesBeforeItsLate=1;
	//Process paceMakerProcess=null;
	int currentPulseFrequency=0;
	int currentPulseGenerationDuration=0;
	long timeSinceLastPulse=0;
	int numberOfPulsesBeforeIsLate =0;
	SimpleDateFormat simpleFormatter = new SimpleDateFormat("dd/MM/yy HH:mm");
	private int heartPid, hypothalamusPid, tomcatPid;
	private double MAXIMUM_HEART_DB_SIZE=30*1024*1024;
	//
	// jSerialComm has a known, unresolved native (off-heap) memory leak on some serial
	// read patterns (see github.com/Fazecast/jSerialComm/issues/596) that Java's own
	// -Xmx/-XX:MaxDirectMemorySize can't see or bound, since it's memory the native
	// library allocates directly. Rather than chase that upstream bug, cap the blast
	// radius: if Hypothalamus's RSS crosses this ceiling, restart it the same clean way
	// we already do for a late pulse, before it grows large enough to trigger a
	// system-wide OOM-kill (which previously picked arbitrary victims and triggered
	// power-draw spikes/undervoltage during recovery -- see conversation 2026-07-15).
	// 300MB is well above Hypothalamus's normal operating range (~110-145MB observed)
	// but far below the point where it starts starving the rest of the Pi.
	private static final long HYPOTHALAMUS_MAX_RSS_KB = 300000;
	
	public Medula(){
		String fileName =  Utils.getLocalDirectory() + "lib/Log4J.properties";
		PropertyConfigurator.configure(fileName);
		logger = Logger.getLogger(getClass());
		logger.info("Starting Medula at " + new Date() + " buildNumber=" + buildNumber);
		try {
			FileUtils.writeStringToFile(new File("MedulaBuild.info"), buildNumber);
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e1));
		}
	}

	public void monitor() {

		String denomeFileInString = null;
		JSONObject denomeJSONObject=null;
		Calendar cal = Calendar.getInstance();//TimeZone.getTimeZone("GMT+10:00"));
		Date faultDate = cal.getTime();

		try {

			try {
				hypothalamusPid = Integer.parseInt(FileUtils.readFileToString(new File("PaceMakerProcess.info")).split("@")[0]);
			} catch (NumberFormatException | IOException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));
			}
			
			File denomeFile = new File(Utils.getLocalDirectory() + "Teleonome.denome");
			if(!denomeFile.isFile() || denomeFile.length()==0) {
				File previousTeleonomeFile = new File(Utils.getLocalDirectory() + "Teleonome.previous_pulse");
				if(previousTeleonomeFile.isFile() && previousTeleonomeFile.length()>0) {
					logger.info("Teleonome.denome was not found, copying from previous_pulse" );
					copyFileAtomically(previousTeleonomeFile, new File(Utils.getLocalDirectory() + "Teleonome.denome"));
					addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_MISSING_DENOME_FILE,"");
					denomeFile = new File(Utils.getLocalDirectory() + "Teleonome.denome");
				}else {
					File originalTeleonome = new File(Utils.getLocalDirectory() + "Teleonome.original");
					logger.info("Teleonome.denome was not found, copying from previous_pulse" );
					copyFileAtomically(originalTeleonome, new File(Utils.getLocalDirectory() + "Teleonome.denome"));
					addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_MISSING_DENOME_FILE,"");
					denomeFile = new File(Utils.getLocalDirectory() + "Teleonome.denome");
				}
				

			}
			// now check if the denome is a valid json
			//
			
			denomeFileInString = FileUtils.readFileToString(denomeFile, Charset.defaultCharset());
			boolean validJSONFormat=false;
			logger.info("line 115 checking the Teleonome.denome first, length=" + denomeFileInString.length() );
			try{
					denomeJSONObject = new JSONObject(denomeFileInString);
					validJSONFormat=true;
			}catch(Exception e) {
				logger.warn(Utils.getStringException(e));
			}finally{
				if(!validJSONFormat) {
					File originalTeleonomeFile = new File(Utils.getLocalDirectory() + "Teleonome.original");
					logger.info("Teleonome.previous was not bad, copying from original" );
					copyFileAtomically(originalTeleonomeFile, new File(Utils.getLocalDirectory() + "Teleonome.denome"));
				}
			}
			
			//
			// check the services
			//
			boolean networkok = checkNetwork();
			boolean avahiok = checkService("avahi-daemon");
			if(!avahiok) restartService("avahi-daemon");
		    boolean postgresok = checkService("postgresql");
			if(!postgresok) restartService("postgresql");
			checkPowerThrottling(faultDate);
		        
			
			//
			// check the heart status
			//
			File heartProcessInfo=new File("/home/pi/Teleonome/heart/HeartProcess.info");
			
			try {
				heartPid = Integer.parseInt(FileUtils.readFileToString(heartProcessInfo).split("@")[0]);
			} catch (NumberFormatException | IOException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));
			}
			logger.info("checking the heart, heartPid=" +heartPid );

			// first check to see if the heart has received a pulse lately, read file heart/HeartTeleonome.denome
			// 
			String heartDenomeFileInString="";
			try {
				heartDenomeFileInString = FileUtils.readFileToString(new File(Utils.getLocalDirectory() + "heart/HeartTeleonome.denome"));
			} catch (IOException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));
			}
			JSONObject heartDenomeJSONObject =null;
			try {
				heartDenomeJSONObject = new JSONObject(heartDenomeFileInString);
			}catch(Exception e) {
				logger.warn(Utils.getStringException(e));
			}
			boolean late;
			boolean heartPulseLate = false;
			String heartLastPulseDate = "";
			if(heartDenomeJSONObject!=null) {
				heartDenomeJSONObject.getString(TeleonomeConstants.PULSE_TIMESTAMP);
			}



			try {
				boolean denomePulseLate;
				if(heartDenomeJSONObject!=null) {
					denomePulseLate= isPulseLate( heartDenomeJSONObject);
				}else {
					denomePulseLate=true;
				}
				//
				// isPulseLate alone can stay false even when the heart is half-dead:
				// Moquette pins each client to one of a fixed set of session event
				// loop threads, and a thread that dies from an uncaught exception is
				// never restarted (Moquette limitation, not ours to fix) -- every
				// client on that shard goes silently unresponsive forever, but if the
				// client that reports HeartTeleonome.denome happens to be on a
				// surviving shard, the pulse keeps looking fine. Heart's own ping
				// thread is independent of Moquette entirely and polls each session
				// loop's actual thread-liveness, so check that too.
				//
				boolean sessionLoopDead = false;
				//
				// HeartTeleonome.denome is only ever rewritten by PublisherListener
				// when Hypothalamus successfully publishes a Status message to Heart --
				// so a late denome pulse alone does not prove Heart is broken, only
				// that nothing has arrived from Hypothalamus lately. HeartPing.info,
				// in contrast, is written every ~60s by Heart's own PingThread
				// regardless of whether anything is being published to it -- a
				// fresh ping there proves Heart itself is alive and responsive even
				// if the denome pulse looks stale. Observed in production
				// 2026-07-17: Hypothalamus's MQTT client to Heart got permanently
				// stuck disconnected (a Hypothalamus-side bug), Heart's denome
				// pulse froze, and Medula killed a perfectly healthy Heart process
				// on a ~10-15 minute loop for hours because it had no way to tell
				// the two situations apart.
				//
				boolean heartPingFresh = false;
				long heartPingAgeSeconds = -1;
				try {
					String heartPingInfoString = FileUtils.readFileToString(new File(Utils.getLocalDirectory() + "heart/HeartPing.info"));
					JSONObject heartPingInfo = new JSONObject(heartPingInfoString);
					int deadSessionEventLoopCount = heartPingInfo.optInt("deadSessionEventLoopCount", 0);
					if(deadSessionEventLoopCount > 0) {
						sessionLoopDead = true;
						logger.warn("heart has " + deadSessionEventLoopCount + " dead session event loop(s) out of " + heartPingInfo.optInt("sessionEventLoopCount", -1) + " -- clients pinned to those shards are permanently unresponsive");
						addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_HEART_SESSION_LOOP_DEAD, "deadSessionEventLoopCount=" + deadSessionEventLoopCount);
					}
					long heartPingTimestampMillis = heartPingInfo.optLong(TeleonomeConstants.DATATYPE_TIMESTAMP_MILLISECONDS, -1);
					if(heartPingTimestampMillis > 0) {
						heartPingAgeSeconds = (System.currentTimeMillis() - heartPingTimestampMillis)/1000;
						// PingThread writes every 60s; allow a few missed cycles before treating it as stale
						heartPingFresh = heartPingAgeSeconds < 180;
					}
				} catch (Exception e) {
					logger.warn(Utils.getStringException(e));
				}
				boolean hypothalamusNotPublishingToHeart = denomePulseLate && !sessionLoopDead && heartPingFresh;
				late = denomePulseLate || sessionLoopDead;
				heartPulseLate = late;
				if(hypothalamusNotPublishingToHeart) {
					logger.warn("Heart's own ping is fresh (age=" + heartPingAgeSeconds + "s) but its pulse denome is late -- Heart is alive, Hypothalamus is not publishing to it. Not restarting Heart; this is a Hypothalamus problem.");
					addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_HYPOTHALAMUS_NOT_PUBLISHING_TO_HEART, "heartPingAgeSeconds=" + heartPingAgeSeconds);
				} else if(late ){
					logger.info("the heart  is late, seconds since currentPulseFrequency=" + currentPulseFrequency + " numberOfPulsesBeforeIsLate=" + numberOfPulsesBeforeIsLate + " last pulse=" + timeSinceLastPulse/1000 + " maximum number of seconds =" + (numberOfPulsesBeforeIsLate*(currentPulseFrequency + currentPulseGenerationDuration))/1000);
					//
					// if we are late, check to see if the pacemaker is running,
					// get the processid

					ArrayList results = Utils.executeCommand("ps -p " + heartPid);
					String data =  " Heart Last Pulse at " + heartLastPulseDate + String.join(", ", results);
					//				 if the heart is running it will return two lines like:
					//				PID TTY          TIME CMD
					//				1872 pts/0    00:02:45 java
					//				if it only returns one line then the process is not running
					logger.info("line 146, results=" + results);
					if(results.size()<2){
						logger.info("heart is not running");
						addPathologyDene(faultDate,TeleonomeConstants.PATHOLOGY_HEART_DIED, "data=" + data);
					}else{
						logger.info("heart is  running but still late, killing it... data=" + data);
						addPathologyDene(faultDate,TeleonomeConstants.PATHOLOGY_HEART_PULSE_LATE,data);
						logger.warn( "heart is running about to kill process " + heartPid);
						Utils.executeCommand("sudo kill -9  " + heartPid);
						Thread.sleep(10000);
						
						
						data = "killing the heart command response="  +String.join(", ", results);
						logger.warn( data);
						copyLogFiles(faultDate);
					}
					//
					// heartPid is a single cached pid read from HeartProcess.info, which can be
					// stale or racing with Heart's own rewrite of that file. Killing only that pid
					// has repeatedly left orphaned Heart.jar instances running alongside a freshly
					// started one (observed piling up to 5 concurrent instances on Ra). Sweep by
					// process name so every stray instance is gone before we start a new one.
					//
					logger.warn("sweeping for any remaining Heart.jar processes before restart");
					Utils.executeCommand("sudo pkill -9 -f Heart.jar");
					Thread.sleep(2000);
					logger.warn( "deleting files");
					Utils.executeCommand("sudo rm /home/pi/Teleonome/heart/heart.mapdb*");
					Utils.executeCommand("sudo rm /home/pi/Teleonome/heart/moquette_store.h2*");
					Utils.executeCommand("sudo rm /home/pi/Teleonome/heart/*.page*");
					FileUtils.deleteQuietly(heartProcessInfo);
					try {
						Thread.sleep(5000);
					}catch(InterruptedException e) {
						logger.debug("line 277 sleep interrupted");
					}
					logger.info(" about to restart the heart"  );
					results = Utils.executeCommand("sudo sh /home/pi/Teleonome/heart/StartHeartBG.sh");
					data = "restarted the heart command response="  +String.join(", ", results);
					int counter=0;
					logger.info("line 306 After restarting the heart, data=" + data)  ;
					Thread.sleep(10000);
					do {
						heartPid=-1;
						heartProcessInfo=new File("/home/pi/Teleonome/heart/HeartProcess.info");
						logger.info("After restarting, HeartProcess.info is a file=" + heartProcessInfo.isFile()  );
						try {
							heartPid = Integer.parseInt(FileUtils.readFileToString(heartProcessInfo).split("@")[0]);
							logger.info("After restarting, heartPid=" + heartPid  );
						} catch (NumberFormatException | IOException e3) {
							// TODO Auto-generated catch block
							logger.warn(Utils.getStringException(e3));
						}
						counter++;
						Thread.sleep(10000);
					}while(!heartProcessInfo.isFile() && counter<4 );

					//
					// now check the heart status
					//
					
					

				}

			} catch (IOException e1) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e1));
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));
			} catch (InvalidDenomeException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));
			}
			
			//
			// check the hippocampus
			//
			File hippocampusStatusFile = new File("/home/pi/Teleonome/HippocampusStatus.json");
			int hippocampusPid = -1;
			boolean hippocampusNeedsRestart = false;

			try {
				if (!hippocampusStatusFile.isFile()) {
					//
					// HippocampusStatus.json is only written once Hippocampus finishes its
					// preload phase, which can legitimately take longer than one Medula cycle.
					// Only restart if there isn't already a Hippocampus.jar process running -
					// otherwise this piles up duplicate instances every cycle, each holding its
					// own orphaned MQTT connection to Heart, until Heart runs out of memory.
					//
					// The [H] bracket avoids pgrep matching its own "sh -c pgrep -f ..."
					// invocation, which otherwise always self-matches and reports a
					// false positive on every single cycle, permanently masking a dead
					// Hippocampus.
					ArrayList runningHippocampusResults = Utils.executeCommand("pgrep -f [H]ippocampus.jar");
					boolean hippocampusAlreadyRunning = runningHippocampusResults.size() >= 1;
					if (hippocampusAlreadyRunning) {
						logger.warn("HippocampusStatus.json not found, but hippocampus process already running (still preloading?), skipping restart this cycle: " + runningHippocampusResults);
					} else {
						logger.warn("HippocampusStatus.json not found, hippocampus needs restart");
						hippocampusNeedsRestart = true;
					}
				} else {
					String hippocampusFileString = FileUtils.readFileToString(hippocampusStatusFile, Charset.defaultCharset());
					JSONObject hippocampusStatus = new JSONObject(hippocampusFileString);
					long lastUpdate = hippocampusStatus.getLong(TeleonomeConstants.DATATYPE_TIMESTAMP_MILLISECONDS);
					hippocampusPid = hippocampusStatus.getInt("hippocampusPid");

					long now = System.currentTimeMillis();
					long timeSinceLastUpdate = now - lastUpdate;
					boolean statusStale = timeSinceLastUpdate > (2 * 60 * 1000);

					ArrayList pidResults = Utils.executeCommand("ps -p " + hippocampusPid);
					boolean isRunning = pidResults.size() >= 2;
					logger.info("hippocampus check: pid=" + hippocampusPid + " isRunning=" + isRunning + " statusStale=" + statusStale + " timeSinceLastUpdate=" + timeSinceLastUpdate / 1000 + "s");

					if (!isRunning) {
						logger.warn("hippocampus is not running (pid=" + hippocampusPid + ")");
						addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_HIPPOCAMPUS_DIED, "pid=" + hippocampusPid);
						hippocampusNeedsRestart = true;
					} else if (statusStale) {
						logger.warn("hippocampus status is stale (" + timeSinceLastUpdate / 1000 + "s), killing pid=" + hippocampusPid);
						addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_HIPPOCAMPUS_LATE, "timeSinceLastUpdate=" + timeSinceLastUpdate / 1000 + "s");
						Utils.executeCommand("sudo kill -9 " + hippocampusPid);
						copyLogFiles(faultDate);
						Thread.sleep(5000);
						hippocampusNeedsRestart = true;
					}
				}

				if (hippocampusNeedsRestart) {
					FileUtils.deleteQuietly(hippocampusStatusFile);
					try {
						Thread.sleep(5000);
					} catch (InterruptedException e) {
						logger.debug("hippocampus restart sleep interrupted");
					}
					logger.info("about to restart the hippocampus");
					ArrayList results = Utils.executeCommand("sudo sh /home/pi/Teleonome/StartHippocampusBG.sh");
					logger.info("restarted hippocampus, response=" + String.join(", ", results));
				}

			} catch (Exception e) {
				logger.warn("Exception verifying hippocampus: " + Utils.getStringException(e));
			}


			//
			// check the cerebellum
			//
			File cerebellumStatusFile = new File("/home/pi/Teleonome/CerebellumStatus.json");
			int cerebellumPid = -1;
			boolean cerebellumNeedsRestart = false;

			try {
				if (!cerebellumStatusFile.isFile()) {
					logger.warn("CerebellumStatus.json not found, cerebellum needs restart");
					cerebellumNeedsRestart = true;
				} else {
					String cerebellumFileString = FileUtils.readFileToString(cerebellumStatusFile, Charset.defaultCharset());
					JSONObject cerebellumStatus = new JSONObject(cerebellumFileString);
					long lastUpdate = cerebellumStatus.getLong(TeleonomeConstants.DATATYPE_TIMESTAMP_MILLISECONDS);
					cerebellumPid = cerebellumStatus.getInt("cerebellumPid");

					long now = System.currentTimeMillis();
					long timeSinceLastUpdate = now - lastUpdate;
					boolean statusStale = timeSinceLastUpdate > (2 * 60 * 1000);

					ArrayList pidResults = Utils.executeCommand("ps -p " + cerebellumPid);
					boolean isRunning = pidResults.size() >= 2;
					logger.info("cerebellum check: pid=" + cerebellumPid + " isRunning=" + isRunning + " statusStale=" + statusStale + " timeSinceLastUpdate=" + timeSinceLastUpdate / 1000 + "s");

					if (!isRunning) {
						logger.warn("cerebellum is not running (pid=" + cerebellumPid + ")");
						addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_CEREBELLUM_DIED, "pid=" + cerebellumPid);
						cerebellumNeedsRestart = true;
					} else if (statusStale) {
						logger.warn("cerebellum status is stale (" + timeSinceLastUpdate / 1000 + "s), killing pid=" + cerebellumPid);
						addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_CEREBELLUM_LATE, "timeSinceLastUpdate=" + timeSinceLastUpdate / 1000 + "s");
						Utils.executeCommand("sudo kill -9 " + cerebellumPid);
						copyLogFiles(faultDate);
						Thread.sleep(5000);
						cerebellumNeedsRestart = true;
					}
				}

				if (cerebellumNeedsRestart) {
					FileUtils.deleteQuietly(cerebellumStatusFile);
					try {
						Thread.sleep(5000);
					} catch (InterruptedException e) {
						logger.debug("cerebellum restart sleep interrupted");
					}
					logger.info("about to restart the cerebellum");
					ArrayList results = Utils.executeCommand("sudo sh /home/pi/Teleonome/StartCerebellumBG.sh");
					logger.info("restarted cerebellum, response=" + String.join(", ", results));
				}

			} catch (Exception e) {
				logger.warn("Exception verifying cerebellum: " + Utils.getStringException(e));
			}


			//
			// hypothalamus
			//

			//
			// Hypothalamus rewrites Teleonome.denome by deleting it and writing a fresh
			// copy rather than write-then-rename, so there's a real (if short) window on
			// every pulse where the file plain doesn't exist. The isFile()/length()==0
			// check above only catches that at the very start of monitor() -- by the time
			// execution reaches here (after the heart and cerebellum checks), Hypothalamus
			// could easily have deleted-and-not-yet-recreated it, throwing
			// NoSuchFileException (seen on Ra 2026-07-16 04:10-04:30). That used to fall
			// through to the outer IOException catch with teleonomeName left at its "",
			// which went on to break the Tomcat health check below (see the guard there).
			// Retry through the window instead of giving up on the first read.
			//
			int denomeReadAttempts=0;
			while(true) {
				try {
					denomeFileInString = FileUtils.readFileToString(denomeFile, Charset.defaultCharset());
					break;
				} catch(IOException e) {
					denomeReadAttempts++;
					if(denomeReadAttempts>=5) throw e;
					logger.warn("Teleonome.denome not readable (attempt " + denomeReadAttempts + "), likely mid-rewrite by Hypothalamus, retrying: " + Utils.getStringException(e));
					try {
						Thread.sleep(1000);
					} catch(InterruptedException ie) {
						logger.debug("denome read retry sleep interrupted");
					}
				}
			}
			 validJSONFormat=true;
			logger.info("checking the Teleonome.denome first, length=" + denomeFileInString.length() );
			boolean restartHypothalamus=false;
			//
			// Late-pulse and crash restarts already imply Hypothalamus is stuck or dead,
			// not actively writing. A memory-ceiling restart is different: Hypothalamus can
			// be perfectly alive and mid-write to Teleonome.denome at the moment we decide to
			// kill -9 it (see conversation 2026-07-15), which can truncate/corrupt the file.
			// This flag marks that specific case so the kill step below can snapshot the file
			// first and verify/repair it after, instead of applying that overhead to every
			// restart path.
			boolean memoryCeilingRestart=false;
			try{
				denomeJSONObject = new JSONObject(denomeFileInString);
				JSONObject denomeObject = denomeJSONObject.getJSONObject("Denome");
				teleonomeName = denomeObject.getString("Name");
				 logger.warn("Teleonome Name=" + teleonomeName);
				//
				// ok the teleonome is a valid file, now check if its late
				//
				// Deliberately decoupled from heartPulseLate: Hypothalamus owns the
				// system pulse, and a Heart problem is Heart's problem to restart on
				// its own (see the heart-check block above). Folding heartPulseLate in
				// here used to force a Hypothalamus restart any time Heart looked late,
				// even when Hypothalamus's own pulse was fine and Heart's staleness
				// turned out to be a Heart-side bug unrelated to the Hypothalamus<->Heart
				// connection -- that just added unnecessary churn to the pulse itself.
				//
				 late= isPulseLate( denomeJSONObject);
				String lastPulseDate = denomeJSONObject.getString(TeleonomeConstants.PULSE_TIMESTAMP);
				if(late){
					logger.info("PULSE LATE, heartPulseLate=" + heartPulseLate + " seconds since currentPulseFrequency=" + currentPulseFrequency + " numberOfPulsesBeforeIsLate=" + numberOfPulsesBeforeIsLate + " last pulse=" + timeSinceLastPulse/1000 + " maximum number of seconds =" + (numberOfPulsesBeforeIsLate*(currentPulseFrequency + currentPulseGenerationDuration))/1000);
					validJSONFormat=true;
					restartHypothalamus=true;

					ArrayList results;
					try {
						results = Utils.executeCommand("ps -p " + hypothalamusPid);
						// if the pacemaker is running it will return two lines like:
						//PID TTY          TIME CMD
						//1872 pts/0    00:02:45 java
						//if it only returns one line then the process is not running
						String data = "Last Pulse at " + lastPulseDate + String.join(", ", results);
						logger.info("here is data:" +data);

						addPathologyDene(faultDate,TeleonomeConstants.PATHOLOGY_PULSE_LATE,data);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						logger.warn(Utils.getStringException(e));
					}
					//
				}

				if(!restartHypothalamus && hypothalamusPid>0) {
					try {
						long hypothalamusRssKb = getProcessResidentMemoryKb(hypothalamusPid);
						if(hypothalamusRssKb > HYPOTHALAMUS_MAX_RSS_KB) {
							logger.warn("Hypothalamus RSS=" + hypothalamusRssKb + "Kb exceeds ceiling=" + HYPOTHALAMUS_MAX_RSS_KB + "Kb, forcing restart");
							restartHypothalamus=true;
							memoryCeilingRestart=true;
							addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_HYPOTHALAMUS_MEMORY_CEILING,
									"RSS=" + hypothalamusRssKb + "Kb ceiling=" + HYPOTHALAMUS_MAX_RSS_KB + "Kb");
						}
					} catch (InterruptedException e) {
						logger.warn(Utils.getStringException(e));
					}

				}
			}catch (JSONException e) {
				//
				// if we are here is
				logger.info("Teleonome.denome is not valid" );
				logger.warn(Utils.getStringException(e));
				validJSONFormat=false;
				restartHypothalamus=true;
			}


			if(!validJSONFormat){


				File previousPulseFile = new File(Utils.getLocalDirectory() + "Teleonome.previous_pulse");
				logger.info("Teleonome.denome has zero length, checking previous pulse" );
				String previousPulseDenomeFileInString = FileUtils.readFileToString(previousPulseFile, Charset.defaultCharset());
				if(previousPulseDenomeFileInString.length()>0){
					try{
						JSONObject previousDenomeJSONObject = new JSONObject(previousPulseDenomeFileInString);
						copyFileAtomically(previousPulseFile, new File(Utils.getLocalDirectory() + "Teleonome.denome"));
						logger.info("Teleonome.previous_pulse denome is valid json, copying to Teleonome.denome "  );
						validJSONFormat=true;
						restartHypothalamus=true;
						addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_CORRUPT_PULSE_FILE,"");
					}catch (JSONException e) {
						//
						// if we are here is
						logger.info("Teleonome_.previous_pulse does not convert to a valid json object" );
						logger.warn(Utils.getStringException(e));
						validJSONFormat=false;
					}
				}
			}
			//
			if(!validJSONFormat) {
				// if we are here is because the previous pulse was corrupt so copy the original
				try{
					File originalPulseFile = new File(Utils.getLocalDirectory() + "Teleonome.original");
					logger.info("Copying Teleonome.original to Teleonome.denome" );
					String originalDenomeFileInString = FileUtils.readFileToString(originalPulseFile, Charset.defaultCharset());
					JSONObject previousDenomeJSONObject = new JSONObject(originalDenomeFileInString);
					copyFileAtomically(originalPulseFile, new File(Utils.getLocalDirectory() + "Teleonome.denome"));

					logger.info("Teleonome.previous_pulse denome is not valid json, copying Teleonome.original to Teleonome.denome "  );
					validJSONFormat=true;
					restartHypothalamus=true;
					addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_CORRUPT_PULSE_FILE,"");
				}catch (JSONException e) {
					//
					// if we are here is
					logger.info("Teleonome_.previous_pulse does not convert to a valid json object" );
					logger.warn(Utils.getStringException(e));
					validJSONFormat=false;
				}
			}

			if(restartHypothalamus) {
				try {
					//
					// Only the memory-ceiling path can be killing an otherwise-healthy,
					// actively-writing Hypothalamus (see the memoryCeilingRestart comment
					// above) -- snapshot Teleonome.denome first so a kill -9 landing mid-write
					// can be detected and repaired before we hand the file back to a freshly
					// restarted Hypothalamus.
					File liveDenomeFile = new File(Utils.getLocalDirectory() + "Teleonome.denome");
					File preKillSnapshotFile = new File(Utils.getLocalDirectory() + "Teleonome.denome.prekill_snapshot");
					boolean tookSnapshot=false;
					if(memoryCeilingRestart) {
						try {
							copyFileAtomically(liveDenomeFile, preKillSnapshotFile);
							tookSnapshot=true;
						} catch(IOException e) {
							logger.warn("could not snapshot Teleonome.denome before memory-ceiling kill: " + Utils.getStringException(e));
						}
					}

					Utils.executeCommand("sudo kill -9  " + hypothalamusPid);
					logger.warn("killing teleonomehypothalamus process");

					if(tookSnapshot) {
						try {
							if(FileUtils.contentEquals(preKillSnapshotFile, liveDenomeFile)) {
								logger.info("Teleonome.denome unchanged by the memory-ceiling kill, no repair needed");
							} else {
								long preKillLength = preKillSnapshotFile.length();
								long postKillLength = liveDenomeFile.length();
								//
								// Restore from the pre-kill snapshot, not Teleonome.original --
								// the snapshot is a complete, valid denome with all the accumulated
								// Mnemosyne history intact (just missing whatever partial write was
								// in flight at kill time), while Teleonome.original is a blank
								// template that would wipe all of that history out.
								logger.warn("Teleonome.denome changed while killing Hypothalamus for the memory ceiling (preKillLength=" + preKillLength + " postKillLength=" + postKillLength + "), likely a kill mid-write -- restoring the pre-kill snapshot before restart");
								copyFileAtomically(preKillSnapshotFile, liveDenomeFile);
								addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_CORRUPT_PULSE_FILE,
										"Restored Teleonome.denome from pre-kill snapshot after memory-ceiling kill mid-write, preKillLength=" + preKillLength + " postKillLength=" + postKillLength);
							}
						} catch(IOException e) {
							logger.warn("could not verify/reseed Teleonome.denome after memory-ceiling kill: " + Utils.getStringException(e));
						} finally {
							FileUtils.deleteQuietly(preKillSnapshotFile);
						}
					}

					copyLogFiles(faultDate);
					try {
						Thread.sleep(5000);
					}catch(InterruptedException e) {
						logger.debug("line 195 sleep interrupted");
					}
					ArrayList<String> results = Utils.executeCommand("sudo sh /home/pi/Teleonome/StartHypothalamusBG.sh");
					String data =  " Restarted Hypothalamus " + new Date() + String.join(", ", results);
					logger.warn("restarting TeleonomeHypothalamus process command execution result:" + data);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					logger.warn(Utils.getStringException(e));
				}
			}



		} catch (IOException e) {
			// TODO Auto-generated catch block
			logger.info(Utils.getStringException(e));
		}catch (InvalidDenomeException e) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e));
		} 

		
		try {
			FileUtils.writeStringToFile(new File("Medula.info"), buildNumber);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e));
		}


		//
		// now check the tomcat ping
		//
		logger.warn("About to check tomcat");
		//
		// check the site
		 boolean webappok=false;
		 if(teleonomeName==null || teleonomeName.trim().isEmpty()) {
			 //
			 // teleonomeName only ends up empty here if this cycle's Teleonome.denome
			 // read/parse never got as far as the "Name" field (see the retry loop
			 // above) -- in that case "http://"+teleonomeName+".local" collapses to
			 // "http://.local", which can never resolve. That used to read as the
			 // webapp being down and kill a Tomcat that was actually healthy, then
			 // fail to verify the restart for the same reason and give up (observed
			 // on Ra 2026-07-16 04:10-04:30, needing a manual restart). Skip the check
			 // entirely rather than act on a hostname we know is bogus.
			 //
			 logger.warn("teleonomeName is empty this cycle, skipping Tomcat health check rather than risk killing a healthy webapp over a malformed hostname");
			 webappok=true;
		 } else {
         try {
        	 String website = "http://"+teleonomeName+".local";
        	 logger.warn("Connecting to  " + website);
    		 URL url = new URL(website);
             HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			  connection.setConnectTimeout(5000); // Set timeout to 5 seconds
		         connection.connect();
		         int responseCode = connection.getResponseCode();
		         logger.warn("responseCode=  " + responseCode);
		         if (responseCode == 200) webappok=true;
		} catch (ProtocolException e) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e));
		}
		 }
       int webAppPid=-1;
        if(!webappok) {
        	File webAppProcessInfo=new File("/home/pi/Teleonome/WebServerProcess.info");
        	logger.info("webapp is not responsing killing it... " );
        	try {
        		//
        		// webAppPid can easily be stale or missing (e.g. WebServerProcess.info
        		// is empty because the last restart died before Tomcat got far enough
        		// to write its own PID) -- that must not stop us from still attempting
        		// the restart below, so the kill is best-effort and doesn't gate it.
        		//
    			try {
    				webAppPid = Integer.parseInt(FileUtils.readFileToString(webAppProcessInfo).split("@")[0]);
    			} catch (NumberFormatException | IOException e) {
    				logger.warn("could not read webAppPid, will still attempt restart: " + Utils.getStringException(e));
    			}
    			long lastTomcatPingMillis=0;
            	String webserverPingInfoS = FileUtils.readFileToString(new File("WebServerPing.info"));
    			if(webserverPingInfoS!=null) {
    				JSONObject webserverPingInfo = new JSONObject(webserverPingInfoS);
    				 lastTomcatPingMillis = webserverPingInfo.getLong(TeleonomeConstants.DATATYPE_TIMESTAMP_MILLISECONDS);
    				long now = System.currentTimeMillis();
    			}
            	addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_TOMCAT_PING_LATE,"Last Tomcat Ping at at " + simpleFormatter.format(new Timestamp(lastTomcatPingMillis)));
            	if(webAppPid>0) {
            		logger.warn( "webapp  is not running about to kill process " + webAppPid);
            		Utils.executeCommand("sudo kill -9  " + webAppPid);
            	}
    			//
    			// webAppPid is a single cached pid read from WebServerProcess.info,
    			// which gets deleted below on every attempt and only rewritten once
    			// Tomcat fully finishes starting. If a restart attempt fails, the next
    			// cycle finds no pid file, skips the kill above entirely, and launches
    			// another catalina.sh on top of whatever's still there -- repeated
    			// failures pile up multiple half-started/hung Tomcat instances instead
    			// of ever being cleaned up (observed on Ra and Tlaloc: Medula gave up
    			// after 4 failed attempts each, needing a manual sweep to recover).
    			// Sweep by process name unconditionally, same fix pattern as Heart's
    			// restart, so every stray instance is gone before we start a new one.
    			//
    			logger.warn("sweeping for any remaining Tomcat processes before restart");
    			Utils.executeCommand("sudo pkill -9 -f org.apache.catalina.startup.Bootstrap");
    			try {
    				Thread.sleep(5000);
    			}catch(InterruptedException e) {
    				logger.warn(Utils.getStringException(e));
    			}
    			logger.warn( "restarting webapp ");
    			FileUtils.deleteQuietly(webAppProcessInfo);
    			ArrayList results = Utils.executeCommand("sudo sh /home/pi/Teleonome/StartWebserverBG.sh");
    			String data = "restarted the webapp command response="  +String.join(", ", results);
    			logger.warn( data);

    			//
    			// StartWebserverBG.sh backgrounds catalina.sh and returns almost
    			// instantly -- "response=done" only proves the launch command was
    			// issued, not that Tomcat actually came up. Poll the same HTTP check
    			// used above until it responds or we give up, same pattern as the
    			// heart restart verification.
    			//
    			boolean webappRestartedOk = false;
    			int webappCheckCounter = 0;
    			do {
    				try {
    					Thread.sleep(10000);
    				}catch(InterruptedException e) {
    					logger.warn(Utils.getStringException(e));
    				}
    				try {
    					URL verifyUrl = new URL("http://"+teleonomeName+".local");
    					HttpURLConnection verifyConnection = (HttpURLConnection) verifyUrl.openConnection();
    					verifyConnection.setRequestMethod("GET");
    					verifyConnection.setConnectTimeout(5000);
    					verifyConnection.connect();
    					webappRestartedOk = (verifyConnection.getResponseCode() == 200);
    				} catch (IOException e2) {
    					logger.warn(Utils.getStringException(e2));
    				}
    				webappCheckCounter++;
    				logger.info("After restarting webapp, responding=" + webappRestartedOk + " attempt=" + webappCheckCounter);
    			} while(!webappRestartedOk && webappCheckCounter<4);

    			if(!webappRestartedOk) {
    				logger.warn("webapp did not come back up after restart");
    				addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_TOMCAT_PING_LATE, "webapp did not come back up after restart");
    			}
    		} catch (NumberFormatException | IOException e) {
    			// TODO Auto-generated catch block
    			logger.warn(Utils.getStringException(e));
    		} catch (InvalidDenomeException e) {
				// TODO Auto-generated catch block
    			logger.warn(Utils.getStringException(e));
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));
			} catch (InterruptedException e1) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e1));
			}



        }
         
       
         
		//
		// if we are late, check to see if the pacemaker is running, 
		// get the processid
		try {
			String webserverPingInfoS = FileUtils.readFileToString(new File("WebServerPing.info"));
			if(webserverPingInfoS!=null) {
				JSONObject webserverPingInfo = new JSONObject(webserverPingInfoS);
				long lastTomcatPingMillis = webserverPingInfo.getLong(TeleonomeConstants.DATATYPE_TIMESTAMP_MILLISECONDS);
				long now = System.currentTimeMillis();

				if(now>(lastTomcatPingMillis*60*3)){
					logger.info("tomcat ping late, now=" + now + " lastTomcatPingMillis=" + lastTomcatPingMillis);
					addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_TOMCAT_PING_LATE,"Last Tomcat Ping at at " + simpleFormatter.format(new Timestamp(lastTomcatPingMillis)));
					copyLogFiles(faultDate);
					//ArrayList results = Utils.executeCommand("File");
					//String data = "Reboot command response="  +String.join(", ", results);
					//logger.warn("Medula is rebooting from tomcat problem, reboot data=" + data);

				}
			}



		} catch (IOException e) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e));
		} catch (InvalidDenomeException e) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e));
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e));
		}

		//
		// check the size of the heartdb
		/*
		File file = new File(Utils.getLocalDirectory() + "heart" + File.separator +  "heart.mapdb.p");
		int heartDBOriginalSize = (int)file.length()/(1024*1024);
		logger.debug("heartDB size=" + heartDBOriginalSize + " maximum=" +MAXIMUM_HEART_DB_SIZE/(1024*1024) );
		if( file.length() > MAXIMUM_HEART_DB_SIZE) {
			//
			// the database is getting big, kill ht heart erase the db and start again
			logger.warn("Medula about to restart the heart because the database was big=" + file.length()/1024000);
			boolean restartOk=false;
			try {
				ArrayList results = Utils.executeCommand("sudo kill -9  " + heartPid);
				String resultsString = String.join(", ", results);
				logger.warn("After killing heart, results=" + resultsString);

				//Utils.executeCommand("sudo rm /home/pi/Teleonome/heart/heart.mapdb*");

				file.delete();
				File file2 = new File(Utils.getLocalDirectory() + "heart" + File.separator +  "heart.mapdb.t");
				file2.delete();
				File file3 = new File(Utils.getLocalDirectory() + "heart" + File.separator +  "heart.mapdb");
				file3.delete();

				logger.warn("After erasing three files, results=" + resultsString);

				Utils.executeCommand("cd /home/pi/Teleonome/heart && sudo sh NoHupStartHeart.sh");

				int heartDBNewSize = (int)(file.length()/(1024*1024));

				logger.warn("Restarted the heart succesfully, heartDBNewSize=" + heartDBNewSize);
				restartOk=true;
			} catch (IOException | InterruptedException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));
			}finally {
				if(!restartOk) {
					//
					// there was a problem with the restart of the heart
					// add a pathology and rebootaddPathologyDene(TeleonomeConstants.PATHOLOGY_HEART_PULSE_LATE,data);
					try {
						Utils.executeCommand("sudo kill -9  " + heartPid);
						Utils.executeCommand("sudo rm /home/pi/Teleonome/heart/heart.mapdb*");

					} catch (IOException | InterruptedException e) {
						// TODO Auto-generated catch block
						logger.warn(Utils.getStringException(e));
					}

					try {

						Utils.executeCommand("sudo kill -9  " + hypothalamusPid);
					} catch (IOException | InterruptedException e) {
						// TODO Auto-generated catch block
						logger.warn(Utils.getStringException(e));
					}


					copyLogFiles(faultDate);

					ArrayList results;
					//						try {
					//							//results = Utils.executeCommand("sudo reboot");
					//							//String data = "Reboot command response="  +String.join(", ", results);
					//							logger.warn("after executing rebootfrom heart problem command and while still in medule data=" + data);
					//						} catch (IOException | InterruptedException e) {
					//							// TODO Auto-generated catch block
					//							logger.warn(Utils.getStringException(e));
					//						}


				}
			}
		}

*/


		logger.info("Ending Medula at " + new Date());
		System.exit(0);
	}


	private  boolean checkService(String serviceName) {
        String command = "systemctl is-active --quiet " + serviceName;
        boolean toReturn=false;
        try {
            Process process = Runtime.getRuntime().exec(command);
            int exitCode = process.waitFor();
            if (exitCode == 0) {
            	logger.debug(serviceName + ": true");
                toReturn=true;
            } else {
            	logger.debug(serviceName + ": false");
            }
        } catch (IOException | InterruptedException e) {
        	logger.warn(Utils.getStringException(e));
        }
        return toReturn;
    }

	 //
	 // Resident memory of a running process, in Kb, via "ps -o rss=" (no header line,
	 // so a single trimmed numeric line back). Returns -1 if the process isn't running
	 // or the output can't be parsed, which the caller treats as "can't tell, don't act".
	 //
	 //
	 // FileUtils.copyFile() writes straight into the destination (truncate then
	 // stream), so anything reading Teleonome.denome while one of these recovery
	 // copies is in flight can see it empty or partial -- the same class of race
	 // that caused the Tomcat health check to misfire on Ra (2026-07-16). Copy to
	 // a sibling .tmp file in the same directory, then swap it into place with an
	 // atomic rename so readers only ever see the old complete file or the new
	 // complete file.
	 //
	 private void copyFileAtomically(File source, File dest) throws IOException {
		 File tempFile = new File(dest.getParentFile(), dest.getName() + ".tmp");
		 FileUtils.copyFile(source, tempFile);
		 Files.move(tempFile.toPath(), dest.toPath(),
				 StandardCopyOption.REPLACE_EXISTING,
				 StandardCopyOption.ATOMIC_MOVE);
	 }

	 //
	 // Streams a JSONObject node-by-node instead of building a full toString(4)
	 // String first (that alone was enough to OOM Medula's small heap once the
	 // denome passed a few MB -- see addPathologyDene, conversation 2026-07-16),
	 // and lands it atomically via the same temp-file-then-rename pattern as
	 // copyFileAtomically above.
	 //
	 private void writeDenomeJsonAtomically(JSONObject json, File targetFile) throws IOException {
		 File tempFile = new File(targetFile.getParentFile(), targetFile.getName() + ".tmp");
		 try (BufferedWriter bw = new BufferedWriter(new FileWriter(tempFile))) {
			 json.write(bw);
		 }
		 Files.move(tempFile.toPath(), targetFile.toPath(),
				 StandardCopyOption.REPLACE_EXISTING,
				 StandardCopyOption.ATOMIC_MOVE);
	 }

	 private long getProcessResidentMemoryKb(int pid) throws InterruptedException {
		 try {
			 ArrayList results = Utils.executeCommand("ps -p " + pid + " -o rss=");
			 if(results.isEmpty()) return -1;
			 String rssString = ((String)results.get(0)).trim();
			 if(rssString.isEmpty()) return -1;
			 return Long.parseLong(rssString);
		 } catch (IOException | NumberFormatException e) {
			 logger.warn(Utils.getStringException(e));
			 return -1;
		 }
	 }

	 private  boolean restartService(String servicename) {
	        String command = "sudo service " + servicename + " restart";
	        boolean toReturn=false;
	        try {
	            Process process = Runtime.getRuntime().exec(command);
	            int exitCode = process.waitFor();
	            if (exitCode == 0) {
	                logger.debug("Restarting " + servicename + " ok");
	                toReturn=true;
	            } else {
		                logger.debug("Restarting " + servicename + " Failed");
	            }
	        } catch (IOException | InterruptedException e) {
	        	logger.warn(Utils.getStringException(e));
	        }
	        return toReturn;
	    }

	 
    private  boolean checkNetwork() {
        String command = "ping -c 1 8.8.8.8";
        boolean toReturn=false;
        try {
            Process process = Runtime.getRuntime().exec(command);
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                logger.debug("Network: connected");
                toReturn=true;
            } else {
            	 logger.debug("Network: disconnected, restarting");
                restartNetworkInterface();
            }
        } catch (IOException | InterruptedException e) {
        	logger.warn(Utils.getStringException(e));
        }
        return toReturn;
    }

    private  void restartNetworkInterface() {
        try {
        	logger.debug("Restarting wlan1...");
            Process ifdown = Runtime.getRuntime().exec("sudo ifdown wlan1");
            ifdown.waitFor();
            Process ifup = Runtime.getRuntime().exec("sudo ifup wlan1");
            ifup.waitFor();
        } catch (IOException | InterruptedException e) {
        	logger.warn(Utils.getStringException(e));
        }
    }

    //
    // vcgencmd get_throttled returns a bitmask, e.g. "throttled=0x50005".
    // Bits 0/2 mean under-voltage/throttling is happening right now; bits
    // 16/18 just mean it happened at some point since boot (sticky, not
    // urgent). We only want to raise a pathology for the currently-active
    // condition, since a pile of simultaneous JVM restarts drawing a current
    // spike is exactly the kind of thing that causes Heart/Tomcat to fail to
    // come back up without any Java-level exception to explain why.
    //
    private void checkPowerThrottling(Date faultDate) {
    	try {
    		ArrayList results = Utils.executeCommand("vcgencmd get_throttled");
    		if(results!=null && !results.isEmpty()) {
    			String line = String.join(" ", results).trim();
    			int hexStart = line.indexOf("0x");
    			if(hexStart>=0) {
    				long throttled = Long.parseLong(line.substring(hexStart+2).trim(), 16);
    				boolean underVoltageNow = (throttled & 0x1) != 0;
    				boolean throttledNow = (throttled & 0x4) != 0;
    				if(underVoltageNow || throttledNow) {
    					logger.warn("power throttling detected: " + line + " underVoltageNow=" + underVoltageNow + " throttledNow=" + throttledNow);
    					addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_POWER_UNDERVOLTAGE, line + " underVoltageNow=" + underVoltageNow + " throttledNow=" + throttledNow);
    				}
    			}
    		}
    	} catch (Exception e) {
    		logger.warn(Utils.getStringException(e));
    	}
    }

	private void copyLogFiles(Date faultDate) {

		String srcFolderName="/home/pi/Teleonome/logs/" ;
		String destFolderName="/home/pi/Teleonome/logs/" + faultDate.getTime() + "/";
		String destFolderWebRootName="/home/pi/Teleonome/tomcat/webapps/ROOT/logs/" + faultDate.getTime() + "/";
		logger.debug("copying files of faultdate" + faultDate.getTime() );;
		File destFolder = new File(destFolderName);
		destFolder.mkdirs();
		File destFolderWebRoot = new File(destFolderWebRootName);
		destFolderWebRoot.mkdirs();

		File srcFile = new File(srcFolderName + "TeleonomeHypothalamus.txt");
		File destFile =  new File(destFolderName + "TeleonomeHypothalamus.txt");

		File destFileWeb =  new File(destFolderWebRootName + "TeleonomeHypothalamus.txt");
		if(srcFile.isFile()) {
			logger.debug("copying file" + srcFile.getAbsolutePath() + " to " + destFile.getAbsolutePath());;
			try {
				FileUtils.copyFile(srcFile, destFile);
				FileUtils.copyFile(srcFile, destFileWeb);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));
			}
		}
		if(srcFile.isFile()) {
			logger.debug("copying file" + srcFile.getAbsolutePath() + " to " + destFile.getAbsolutePath());;
			srcFile = new File(srcFolderName + "PulseThread.txt");
			destFile =  new File(destFolderName + "PulseThread.txt");
			destFileWeb =  new File(destFolderWebRootName + "PulseThread.txt");
			try {
				FileUtils.copyFile(srcFile, destFile);
				FileUtils.copyFile(srcFile, destFileWeb);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));

			}
		}

		if(srcFile.isFile()) {
			logger.debug("copying file" + srcFile.getAbsolutePath() + " to " + destFile.getAbsolutePath());;
			srcFile = new File(srcFolderName + "DenomeManager.txt");

			destFile =  new File(destFolderName + "DenomeManager.txt");
			destFileWeb =  new File(destFolderWebRootName + "DenomeManager.txt");
			try {
				FileUtils.copyFile(srcFile, destFile);
				FileUtils.copyFile(srcFile, destFileWeb);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));

			}
		}
		if(srcFile.isFile()) {
			logger.debug("copying file" + srcFile.getAbsolutePath() + " to " + destFile.getAbsolutePath());;
			srcFile = new File(srcFolderName + "Medula.txt");
			destFile =  new File(destFolderName + "Medula.txt");
			destFileWeb =  new File(destFolderWebRootName + "Medula.txt");
			try {
				FileUtils.copyFile(srcFile, destFile);
				FileUtils.copyFile(srcFile, destFileWeb);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));
			}
		}
		if(srcFile.isFile()) {
			logger.debug("copying file" + srcFile.getAbsolutePath() + " to " + destFile.getAbsolutePath());;
			srcFile = new File(srcFolderName + "MnemosyneManager.txt");
			destFile =  new File(destFolderName + "MnemosyneManager.txt");
			destFileWeb =  new File(destFolderWebRootName + "MnemosyneManager.txt");
			try {
				FileUtils.copyFile(srcFile, destFile);
				FileUtils.copyFile(srcFile, destFileWeb);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));
			}
		}
		srcFile = new File(srcFolderName + "AsyncCycle.txt");
		destFile =  new File(destFolderName + "AsyncCycle.txt");
		destFileWeb =  new File(destFolderWebRootName + "AsyncCycle.txt");

		if(srcFile.isFile()) {
			logger.debug("copying file" + srcFile.getAbsolutePath() + " to " + destFile.getAbsolutePath());;
			try {
				FileUtils.copyFile(srcFile, destFile);
				FileUtils.copyFile(srcFile, destFileWeb);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));
			}
		}

		srcFile = new File(srcFolderName + "SFTPPublisherWriter.txt");
		destFile =  new File(destFolderName + "SFTPPublisherWriter.txt");
		destFileWeb =  new File(destFolderWebRootName + "SFTPPublisherWriter.txt");

		if(srcFile.isFile()) {
			logger.debug("copying file" + srcFile.getAbsolutePath() + " to " + destFile.getAbsolutePath());;
			try {
				FileUtils.copyFile(srcFile, destFile);
				FileUtils.copyFile(srcFile, destFileWeb);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));
			}
		}

		if(srcFile.isFile()) {
			logger.debug("copying file" + srcFile.getAbsolutePath() + " to " + destFile.getAbsolutePath());;
			srcFile = new File(srcFolderName + "gc.log");
			destFile =  new File(destFolderName + "gc.log");
			destFileWeb =  new File(destFolderWebRootName + "gc.log");

			try {
				FileUtils.copyFile(srcFile, destFile);
				FileUtils.copyFile(srcFile, destFileWeb);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));
			}
		}
		//
		// heart
		//
		srcFolderName="/home/pi/Teleonome/heart/logs/" ;
		if(srcFile.isFile()) {
			logger.debug("copying file" + srcFile.getAbsolutePath() + " to " + destFile.getAbsolutePath());;
			srcFile = new File(srcFolderName + "PublisherListener.txt");
			destFile =  new File(destFolderName + "PublisherListener.txt");
			destFileWeb =  new File(destFolderWebRootName + "PublisherListener.txt");
			try {
				FileUtils.copyFile(srcFile, destFile);
				FileUtils.copyFile(srcFile, destFileWeb);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));

			}
		}
		if(srcFile.isFile()) {
			logger.debug("copying file" + srcFile.getAbsolutePath() + " to " + destFile.getAbsolutePath());;
			srcFile = new File(srcFolderName + "Heart.txt");
			destFile =  new File(destFolderName + "Heart.txt");
			destFileWeb =  new File(destFolderWebRootName + "Heart.txt");
			try {
				FileUtils.copyFile(srcFile, destFile);
				FileUtils.copyFile(srcFile, destFileWeb);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));

			}
		}
		srcFolderName="/home/pi/Teleonome/heart/" ;
		if(srcFile.isFile()) {
			logger.debug("copying file" + srcFile.getAbsolutePath() + " to " + destFile.getAbsolutePath());;
			srcFile = new File(srcFolderName + "gc.log");
			destFile =  new File(destFolderName + "heartgc.log");
			destFileWeb =  new File(destFolderWebRootName + "gc.log");
			try {
				FileUtils.copyFile(srcFile, destFile);
				FileUtils.copyFile(srcFile, destFileWeb);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));

			}
		}
	}

	private boolean isPulseLate(JSONObject denomeJSONObject) throws InvalidDenomeException {
		long lastPulseMillis = denomeJSONObject.getLong(TeleonomeConstants.PULSE_TIMESTAMP_MILLISECONDS);
		String lastPulseDate = denomeJSONObject.getString(TeleonomeConstants.PULSE_TIMESTAMP);
		logger.info("lastPulseDate=" + lastPulseDate + " lastPulseMillis=" + lastPulseMillis);
		JSONObject denomeObject = denomeJSONObject.getJSONObject("Denome");
		String tN = denomeObject.getString("Name");


		Identity identity = new Identity(tN, TeleonomeConstants.NUCLEI_INTERNAL, TeleonomeConstants.DENECHAIN_DESCRIPTIVE, TeleonomeConstants.DENE_VITAL, TeleonomeConstants.DENEWORD_TYPE_NUMBER_PULSES_BEFORE_LATE);
		numberOfPulsesBeforeIsLate =  (Integer) DenomeUtils.getDeneWordByIdentity(denomeJSONObject, identity, TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);

		identity = new Identity(tN, TeleonomeConstants.NUCLEI_PURPOSE, TeleonomeConstants.DENECHAIN_OPERATIONAL_DATA, TeleonomeConstants.DENE_VITAL, TeleonomeConstants.DENEWORD_TYPE_CURRENT_PULSE_FREQUENCY);
		currentPulseFrequency = (Integer)DenomeUtils.getDeneWordByIdentity(denomeJSONObject, identity, TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);

		identity = new Identity(tN, TeleonomeConstants.NUCLEI_PURPOSE, TeleonomeConstants.DENECHAIN_OPERATIONAL_DATA, TeleonomeConstants.DENE_VITAL, TeleonomeConstants.DENEWORD_TYPE_CURRENT_PULSE_GENERATION_DURATION);
		currentPulseGenerationDuration = (Integer)DenomeUtils.getDeneWordByIdentity(denomeJSONObject, identity, TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);

		long now = System.currentTimeMillis();
		timeSinceLastPulse =  now - lastPulseMillis;

		//
		// There are two cases, depending whether:
		//
		// currentPulseFrequency > currentPulseGenerationDuration in this case we are in a teleonome that 
		// executes a pulse every minute but the creation of the pulse is less than one minute
		//
		//currentPulseFrequency < currentPulseGenerationDuration in this case we are in a teleonome that 
		// takes a long time to complete a pulse cycle.  For example a teleonome that is processing analyticons
		// or mnemosycons might take 20 minutes to complete the pulse and wait one minute before starting again

		boolean late=(numberOfPulsesBeforeIsLate*(currentPulseFrequency  + currentPulseGenerationDuration))< timeSinceLastPulse;
		//boolean late=(numberOfPulsesBeforeIsLate*currentPulseFrequency )< timeSinceLastPulse;
		logger.info("late=" + late + " timeSinceLastPulse=" + timeSinceLastPulse + " currentPulseGenerationDuration=" + currentPulseGenerationDuration + " currentPulseFrequency=" + currentPulseFrequency + " numberOfPulsesBeforeIsLate=" + numberOfPulsesBeforeIsLate);


		//		boolean late=false;
		//		if(currentPulseFrequency > currentPulseGenerationDuration) {
		//			if((numberOfPulsesBeforeIsLate*(currentPulseFrequency  + currentPulseGenerationDuration))< timeSinceLastPulse)late=true;
		//		}else {
		//			if((numberOfPulsesBeforeIsLate*currentPulseGenerationDuration)< timeSinceLastPulse)late=true;
		//		}
		return late;
	}


	public void addPathologyDene(Date faultTime, String pathologyCause, String data) throws InvalidDenomeException, JSONException{

		File selectedFile = new File(Utils.getLocalDirectory() + "Teleonome.denome");
		String initialIdentityState="";
		try {
			String fileInString = FileUtils.readFileToString(selectedFile);
			JSONObject denomeJSONObject = new JSONObject(fileInString);

			JSONObject denome = denomeJSONObject.getJSONObject("Denome");
			String tN = denome.getString("Name");





			//
			// add a pathology dene to the denome
			// fiurst get the location, it will be a pointer to a denechain inside of the mnemosyne
			//
			Identity id2 = new Identity(tN, TeleonomeConstants.NUCLEI_INTERNAL, TeleonomeConstants.DENECHAIN_MEDULA, TeleonomeConstants.DENE_PATHOLOGY,TeleonomeConstants.MEDULA_PATHOLOGY_MNEMOSYNE_LOCATION);
			String medulaPathologyLocationPointer  = (String) DenomeUtils.getDeneWordByIdentity(denomeJSONObject, id2, TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);
			JSONObject selectedPathologyDeneChain = DenomeUtils.getDeneChainByIdentity(denomeJSONObject, new Identity(medulaPathologyLocationPointer));


			String pathologyName = pathologyCause;
			String pathologyLocation = TeleonomeConstants.PATHOLOGY_LOCATION_MEDULA;
			Vector extraDeneWords = new Vector();
			JSONObject pathologyDeneDeneWord = Utils.createDeneWordJSONObject(TeleonomeConstants.PATHOLOGY_EVENT_MILLISECONDS, "" + faultTime.getTime() ,null,"long",true);
			extraDeneWords.addElement(pathologyDeneDeneWord);
			pathologyDeneDeneWord = Utils.createDeneWordJSONObject(TeleonomeConstants.PATHOLOGY_EVENT_TIMESTAMP, simpleFormatter.format(faultTime) ,null,"long",true);
			extraDeneWords.addElement(pathologyDeneDeneWord);

			pathologyDeneDeneWord = Utils.createDeneWordJSONObject(TeleonomeConstants.PATHOLOGY_DETAILS_LABEL, data ,null,"String",true);
			extraDeneWords.addElement(pathologyDeneDeneWord);
			//JSONObject pathologyDeneChain = DenomeUtils.getDeneChainByName(denomeJSONObject, TeleonomeConstants.NUCLEI_MNEMOSYNE,TeleonomeConstants.DENECHAIN_MNEMOSYNE_PATHOLOGY);


			JSONArray pathologyDenes=null, pathologyDeneDeneWords;
			JSONObject pathologyDene;
			try {
				pathologyDenes = selectedPathologyDeneChain.getJSONArray("Denes");

			} catch (JSONException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));
			}

			//
			// now create the pathology dene
			//
			pathologyDene = new JSONObject();
			pathologyDenes.put(pathologyDene);

			pathologyDene.put("Name", pathologyName);
			pathologyDeneDeneWords = new JSONArray();
			pathologyDene.put(TeleonomeConstants.DENE_DENE_TYPE_ATTRIBUTE, TeleonomeConstants.DENE_PATHOLOGY);

			LocalDateTime currentTime = LocalDateTime.now();
			DateTimeFormatter formatter = DateTimeFormatter.ofPattern(TeleonomeConstants.MNEMOSYNE_TIME_FORMAT);
			String formatedCurrentTime = currentTime.format(formatter);
			pathologyDene.put(TeleonomeConstants.DATATYPE_TIMESTAMP, formatedCurrentTime);

			pathologyDene.put(TeleonomeConstants.DATATYPE_TIMESTAMP_MILLISECONDS, System.currentTimeMillis());
			pathologyDene.put("DeneWords", pathologyDeneDeneWords);
			//
			// create the Cause deneword
			//
			pathologyDeneDeneWord = Utils.createDeneWordJSONObject(TeleonomeConstants.PATHOLOGY_CAUSE, pathologyCause ,null,"String",true);
			pathologyDeneDeneWords.put(pathologyDeneDeneWord);
			//
			// create the location deneword
			pathologyDeneDeneWord = Utils.createDeneWordJSONObject(TeleonomeConstants.PATHOLOGY_LOCATION, pathologyLocation ,null,TeleonomeConstants.DATATYPE_DENE_POINTER,true);
			pathologyDeneDeneWords.put(pathologyDeneDeneWord);
			//
			// to make it easier to display the pathology dene, add the current value as well
			// as the thresholds

			for(int i=0;i<extraDeneWords.size();i++){
				pathologyDeneDeneWord=(JSONObject)extraDeneWords.elementAt(i);
				pathologyDeneDeneWords.put(pathologyDeneDeneWord);
			}


			try {
				//
				// denomeJSONObject.toString(4) materializes the ENTIRE denome as one
				// pretty-printed String (called twice, back to back, one per target
				// file) -- with Medula's small -Xmx64m heap and the denome having grown
				// past 4MB, this alone was enough to OOM Medula on every single cron
				// cycle that needed to record a pathology (see conversation
				// 2026-07-16: Medula crashed every 5 minutes from here, never reaching
				// the kill/restart calls that follow, leaving Heart and Hypothalamus
				// both stuck down with nothing left to bring them back). Stream
				// node-by-node via JSONObject.write() instead, same pattern already
				// used in DenomeManager/MnemosyneManager, and land each file atomically
				// via a temp-file rename so a reader never sees a partial write either.
				//
				writeDenomeJsonAtomically(denomeJSONObject, new File(Utils.getLocalDirectory() + "Teleonome.denome"));
				writeDenomeJsonAtomically(denomeJSONObject, new File(Utils.getLocalDirectory() + "tomcat/webapps/ROOT/Teleonome.denome"));

			} catch (IOException | JSONException e) {
				// TODO Auto-generated catch block
				logger.debug(Utils.getStringException(e));
			}



		} catch (IOException e) {
			// TODO Auto-generated catch block
			logger.debug(Utils.getStringException(e));
		}


	}





	public static void startHeart(){
		String commandToExecute = "cd /home/pi/Teleonome/heart;sudo java -jar Heart.jar &";
		try {
			Process paceMakerProcess = Runtime.getRuntime().exec(commandToExecute);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			System.out.println(Utils.getStringException(e));
		}

	}


	public static void startHypothalamus(){
		String commandToExecute = "sudo sh StartPaceMakerBG.sh";
		try {
			Process paceMakerProcess = Runtime.getRuntime().exec(commandToExecute);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			System.out.println(Utils.getStringException(e));
		}

	}

	public static void startWebServer(){
		String commandToExecute = "sudo sh StartWebserverBG.sh";
		try {
			Process paceMakerProcess = Runtime.getRuntime().exec(commandToExecute);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			System.out.println(Utils.getStringException(e));
		}

	}

	/*

	public void destroyPaceMakerProcess(int processId){
		String commandToExecute = "sudo kill -9  " + processId;
		try {
			Process paceMakerProcess = Runtime.getRuntime().exec(commandToExecute);
			logger.debug("destroyed pacemaker process ok");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			logger.debug(Utils.getStringException(e));
		}

	}


	public int getPaceMakerProcessNumber(){
		int paceMakerProcessNumber=-1;
		File paceMakerProcessNumberFile = new File(Utils.getLocalDirectory() + "PaceMakerProcess.info");
		if(paceMakerProcessNumberFile.isFile()){
			try {
				paceMakerProcessNumber = Integer.parseInt(FileUtils.readFileToString(paceMakerProcessNumberFile).split("@")[0]);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));
			} catch (NumberFormatException e) {
				// TODO Auto-generated catch block
				logger.warn(Utils.getStringException(e));
			}
		}
		return paceMakerProcessNumber;
	}
	public void readDenome(){
		//
		// read the denome
		//
		File denomeFile = new File(Utils.getLocalDirectory() + "Teleonome.denome");
		if(!denomeFile.isFile()){
			logger.debug("Denome not found, terminating");
			System.exit(-1);
		}
		String denomeFileInString="";
		try {
			denomeFileInString = FileUtils.readFileToString(denomeFile);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e));
		}
		if(denomeFileInString.equals("")){
			logger.debug("Invalid denome terminating");
			System.exit(-1);
		}
		JSONObject pulse=null;
		try {
			pulse = new JSONObject(denomeFileInString);
			JSONObject denomeObject = pulse.getJSONObject("Denome");
			teleonomeName = denomeObject.getString("Name");
			lastPulseTime = pulse.getLong("Pulse Timestamp in Milliseconds");
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			String error = Utils.getStringException(e);
			logger.debug(error);
			System.exit(-1);
		}
		//
		// first read the pulse and get the teleonome name and the current pulse
		//
		Integer I;
		Identity identity;

		//
		// get the number of pulses befores
		try {
			identity = new Identity("@" + teleonomeName + ":" + TeleonomeConstants.NUCLEI_INTERNAL + ":" +  TeleonomeConstants.DENECHAIN_DESCRIPTIVE + ":" + TeleonomeConstants.DENE_TYPE_VITAL +":" + TeleonomeConstants.DENEWORD_TYPE_NUMBER_PULSES_BEFORE_LATE);
			I =  (Integer) DenomeUtils.getDeneWordByIdentity(pulse, identity, TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);
			if(I!=null){
				numberOfPulsesBeforeItsLate=I.intValue();
			}
		} catch (InvalidDenomeException e) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e));
		}





		identity = new Identity("@" + teleonomeName + ":" + TeleonomeConstants.NUCLEI_PURPOSE + ":" +  TeleonomeConstants.DENECHAIN_OPERATIONAL_DATA + ":" + TeleonomeConstants.DENE_TYPE_VITAL +":" + TeleonomeConstants.DENEWORD_TYPE_CURRENT_PULSE);
		try {
			I = (Integer) DenomeUtils.getDeneWordByIdentity(pulse, identity, TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);
			if(I==null){
				//
				// try the Base Pulse Frequency
				//
				identity = new Identity("@" + teleonomeName + ":" + TeleonomeConstants.NUCLEI_INTERNAL + ":" +  TeleonomeConstants.DENECHAIN_DESCRIPTIVE + ":" + TeleonomeConstants.DENE_TYPE_VITAL +":" + TeleonomeConstants.DENEWORD_TYPE_BASE_PULSE_FREQUENCY);
				I =  (Integer) DenomeUtils.getDeneWordByIdentity(pulse, identity, TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);
				if(I!=null){
					currentPulseInMilliSeconds=I.intValue();
				}else{
					currentPulseInMilliSeconds=60000;
				}
			}else {
				currentPulseInMilliSeconds=I.intValue();
			}
		} catch (InvalidDenomeException e) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e));
		}
		//
		// now sta
	}

	 */

	public static void main(String[] args) {
		if(args.length>0 && args[0].equals("-v")) {
			System.out.println("Medula Build " + buildNumber);
			return;
		}

		//
		// StartMedula.sh has no concurrency guard and cron fires it every 5 minutes
		// regardless of whether the previous run finished -- under Pi power throttling
		// a run can take much longer than usual, so without a lock several Medula
		// processes end up running at once, each independently killing/restarting
		// Heart and racing each other, leaving multiple orphaned Heart instances behind.
		// tryLock() is non-blocking: if another instance already holds it, exit
		// immediately instead of piling up. The OS releases the lock automatically
		// even if this process is killed, so there's no stale-lock cleanup needed.
		//
		FileLock lock = acquireSingleInstanceLock();
		if(lock == null) {
			System.out.println("Another Medula instance is already running, exiting");
			return;
		}
		try {
			new Medula().monitor();
		} finally {
			try {
				lock.release();
				lock.channel().close();
			} catch (IOException e) {
				// nothing to do, process is exiting anyway
			}
		}
	}

	private static FileLock acquireSingleInstanceLock() {
		try {
			File lockFile = new File(Utils.getLocalDirectory() + "Medula.lock");
			RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
			FileChannel channel = raf.getChannel();
			return channel.tryLock();
		} catch (IOException | OverlappingFileLockException e) {
			return null;
		}
	}

}
