package com.teleonome.medula;



import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
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
		JSONObject denomeJSONObject;
		Calendar cal = Calendar.getInstance();//TimeZone.getTimeZone("GMT+10:00"));
		Date faultDate = cal.getTime();

		try {
			denomeFileInString = FileUtils.readFileToString(new File(Utils.getLocalDirectory() + "Teleonome.denome"));
			boolean validJSONFormat=true;
			logger.info("checking the Teleonome.denome first, length=" + denomeFileInString.length() );
			

			if(denomeFileInString.length()==0){
				validJSONFormat=false;
				logger.info("Teleonome.denome has zero length" );

			}else {
				//
				// now try to create a jsonobject, if you get an exception cop \y the backup
				//
				try{
					denomeJSONObject = new JSONObject(denomeFileInString);
					logger.info("Teleonome.denome denome is valid json, "  );
				}catch (JSONException e) {
					//
					// if we are here is
					logger.info("Teleonome.denome does not convert to a valid json object" );
					logger.warn(Utils.getStringException(e));
					validJSONFormat=false;
				}
			}

			if(!validJSONFormat) {
				logger.info("removing Teleonome.denome  and copying from previous_pulse" );
				FileUtils.deleteQuietly(new File(Utils.getLocalDirectory() + "Teleonome.denome"));
				FileUtils.copyFile(new File(Utils.getLocalDirectory() + "Teleonome.previous_pulse"), new File(Utils.getLocalDirectory() + "Teleonome.denome"));
				addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_CORRUPT_PULSE_FILE,"");
			}

			denomeFileInString = FileUtils.readFileToString(new File(Utils.getLocalDirectory() + "Teleonome.denome"));
			denomeJSONObject = new JSONObject(denomeFileInString);
			logger.info("denome JSObject was created ok" );

		} catch (IOException e) {
			// TODO Auto-generated catch block
			logger.info(Utils.getStringException(e));
		}catch (InvalidDenomeException e) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e));
		} 

		//
		// now check the heart status
		//
		logger.info("checking the heart first, starting with checking the last pulse received by the heart" );
		try {
			heartPid = Integer.parseInt(FileUtils.readFileToString(new File("heart/HeartProcess.info")).split("@")[0]);
		} catch (NumberFormatException | IOException e3) {
			// TODO Auto-generated catch block
			e3.printStackTrace();
		}

		// first check to see if the heart has received a pulse lately, read file heart/HeartTeleonome.denome
		// 
		try {
			denomeFileInString = FileUtils.readFileToString(new File(Utils.getLocalDirectory() + "heart/HeartTeleonome.denome"));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e));
		}
		denomeJSONObject = new JSONObject(denomeFileInString);
		boolean late;
		String heartLastPulseDate = denomeJSONObject.getString(TeleonomeConstants.PULSE_TIMESTAMP);



		try {
			late= isPulseLate( denomeJSONObject);
			if(late ){
				logger.info("the heart  is late, seconds since currentPulseFrequency=" + currentPulseFrequency + " numberOfPulsesBeforeIsLate=" + numberOfPulsesBeforeIsLate + " last pulse=" + timeSinceLastPulse/1000 + " maximum number of seconds =" + (numberOfPulsesBeforeIsLate*currentPulseFrequency)/1000);
				//
				// if we are late, check to see if the pacemaker is running, 
				// get the processid

				ArrayList results = Utils.executeCommand("ps -p " + heartPid);
				String data =  " Heart Last Pulse at " + heartLastPulseDate + String.join(", ", results);

				//
				// if the pacemaker is running it will return two lines like:
				//PID TTY          TIME CMD
				//1872 pts/0    00:02:45 java
				//if it only returns one line then the process is not running
				if(results.size()<2){
					logger.info("heart is not running");
					addPathologyDene(faultDate,TeleonomeConstants.PATHOLOGY_HEART_DIED, "data=" + data);
				}else{
					logger.info("heart is  running but still late, killing it... data=" + data);

					//
					// add a pathology dene to the pulse
					//

					addPathologyDene(faultDate,TeleonomeConstants.PATHOLOGY_HEART_PULSE_LATE,data);

					Utils.executeCommand("sudo kill -9  " + heartPid);
					Utils.executeCommand("sudo rm /home/pi/Teleonome/heart/heart.mapdb*");
				}


				copyLogFiles(faultDate);

				results = Utils.executeCommand("/home/pi/Teleonome/heart/StartHeartBG.sh");
				data = "restarted the heart command response="  +String.join(", ", results);
				logger.warn("after restarting heart while still in medule data=" + data);

			}

		} catch (IOException e1) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e1));
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e));
		} catch (InvalidDenomeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		try {
			//
			// if the subscriberthread bug reappears
			//
			File dir = new File(Utils.getLocalDirectory() );
			FileFilter fileFilter = new WildcardFileFilter("*.hprof");
			File[] files = dir.listFiles(fileFilter);
			if(files.length>0) {
				//
				// remove previous hprog
				//
				Arrays.stream(dir.listFiles((f, p) -> p.endsWith("hprog"))).forEach(File::delete);    

				StringBuffer data1=new StringBuffer();;
				logger.info("foound hprof file, renaming them to hprog" );
				File destFile;
				for(int i=0;i<files.length;i++) {
					destFile = new File ("/home/pi/Teleonome/"+ FilenameUtils.getBaseName(files[i].getAbsolutePath()) + ".hprog");
					if(i>0) {
						data1.append(",");
					}
					data1.append(files[i].getAbsolutePath() + ".hprog");
					FileUtils.moveFile(files[i],destFile);
				}

				//
				// add a pathology dene to the pulse
				//
				logger.warn("adding pathology dene");
				addPathologyDene(faultDate,TeleonomeConstants.PATHOLOGY_HYPOTHALAMUS_DIED,data1.toString());
				//Utils.executeCommand("sudo kill -9  " + hypothalamusPid);
				logger.warn("killing teleonomehypothalamus process");
				Utils.executeCommand("sudo kill -9  " + heartPid);
				copyLogFiles(faultDate);
				//ArrayList results = Utils.executeCommand("sudo reboot");
				logger.warn("restarting TeleonomeHypothalamus process");

				ArrayList results = Utils.executeCommand("/home/pi/Teleonome/StartHypothalamusBG.sh");
				String data = "restarted the TeleonomeHypothalamus command response="  +String.join(", ", results);
				logger.warn("after restarting TeleonomeHypothalamus while still in medule data=" + data);
			}
		}catch(IOException e) {
			logger.warn(Utils.getStringException(e));

		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InvalidDenomeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}


		try {
			//
			// until they fixed mockette, check for an hprof file
			//
			File dir = new File(Utils.getLocalDirectory() + "heart");
			FileFilter fileFilter = new WildcardFileFilter("*.hprof");
			File[] files = dir.listFiles(fileFilter);
			if(files.length>0) {
				//
				// remove previous hprog
				//
				Arrays.stream(dir.listFiles((f, p) -> p.endsWith("hprog"))).forEach(File::delete);    

				StringBuffer data1=new StringBuffer();;
				logger.info("foound hprof file, renaming them to hprog" );
				File destFile;
				for(int i=0;i<files.length;i++) {
					destFile = new File ("/home/pi/Teleonome/heart/"+ FilenameUtils.getBaseName(files[i].getAbsolutePath()) + ".hprog");
					if(i>0) {
						data1.append(",");
					}
					data1.append(files[i].getAbsolutePath() + ".hprog");
					FileUtils.moveFile(files[i],destFile);
				}

				//
				// add a pathology dene to the pulse
				//
				logger.warn("adding pathology dene");
				addPathologyDene(faultDate,TeleonomeConstants.PATHOLOGY_HEART_CRASHED_HPROF,data1.toString());
				//Utils.executeCommand("sudo kill -9  " + hypothalamusPid);
				logger.warn("killing heart process");
				Utils.executeCommand("sudo kill -9  " + heartPid);
				logger.warn("delete mapdb files");
				Utils.executeCommand("sudo rm /home/pi/Teleonome/heart/heart.mapdb*");
				copyLogFiles(faultDate);
				//ArrayList results = Utils.executeCommand("sudo reboot");
				logger.warn("restarting heart process");

				ArrayList results = Utils.executeCommand("/home/pi/Teleonome/heart/StartHeartBG.sh");
				String data = "restarted the heart command response="  +String.join(", ", results);
				logger.warn("after restarting heart while still in medule data=" + data);
			}
		}catch(IOException e) {
			logger.warn(Utils.getStringException(e));

		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InvalidDenomeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		//
		// read the denome file and see when was the last pulse
		// as it is in the denome


		try {
			FileUtils.writeStringToFile(new File("Medula.info"), buildNumber);
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}



		try {
			hypothalamusPid = Integer.parseInt(FileUtils.readFileToString(new File("PaceMakerProcess.info")).split("@")[0]);
		} catch (NumberFormatException | IOException e3) {
			// TODO Auto-generated catch block
			e3.printStackTrace();
		}


		try{	

			late= isPulseLate( denomeJSONObject);
			String lastPulseDate = denomeJSONObject.getString(TeleonomeConstants.PULSE_TIMESTAMP);

			if(late){
				logger.info("we are late, seconds since currentPulseFrequency=" + currentPulseFrequency + " numberOfPulsesBeforeIsLate=" + numberOfPulsesBeforeIsLate + " last pulse=" + timeSinceLastPulse/1000 + " maximum number of seconds =" + (numberOfPulsesBeforeIsLate*currentPulseFrequency)/1000);
				//
				// if we are late, check to see if the pacemaker is running, 
				// get the processid
				try {

					ArrayList results = Utils.executeCommand("ps -p " + hypothalamusPid);
					//
					// if the pacemaker is running it will return two lines like:
					//PID TTY          TIME CMD
					//1872 pts/0    00:02:45 java
					//if it only returns one line then the process is not running
					String data = "Last Pulse at \r" + lastPulseDate + String.join(", ", results);
					if(results.size()<2){
						logger.info("pacemaker is not running");
						addPathologyDene(faultDate,TeleonomeConstants.PATHOLOGY_HYPOTHALAMUS_DIED,data);
					}else{
						logger.info("pacemaker is  running but still late, killing it...");
						Utils.executeCommand("sudo kill -9  " + hypothalamusPid);
						//Utils.executeCommand("sudo kill -9  " + heartPid);
						//Utils.executeCommand("sudo rm /home/pi/Teleonome/heart/heart.mapdb*");
						//
						// add a pathology dene to the pulse
						//
						addPathologyDene(faultDate,TeleonomeConstants.PATHOLOGY_PULSE_LATE,data);
					}
					logger.info("Medula is about t restart the hypothalamus ...");
					copyLogFiles(faultDate);
					//Process p = Runtime.getRuntime().exec("sudo reboot");
					 results = Utils.executeCommand("/home/pi/Teleonome/StartHypothalamusBG.sh");
					//System.exit(0);
					data = "StartHypothalamusBG command response="  +String.join(", ", results);
					logger.warn("after restarting hypothalamus and while still in medule data=" + data);

				} catch (IOException e1) {
					// TODO Auto-generated catch block
					logger.warn(Utils.getStringException(e1));
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					logger.warn(Utils.getStringException(e));
				}

			}






			//
			// now check the tomcat ping
			//

			//
			// if we are late, check to see if the pacemaker is running, 
			// get the processid
			try {
				long lastTomcatPingMillis = Long.parseLong(FileUtils.readFileToString(new File("WebServerPing.info")));
				long now = System.currentTimeMillis();

				if(now>(lastTomcatPingMillis*60*3)){
					logger.info("tomcat ping late, now=" + now + " lastTomcatPingMillis=" + lastTomcatPingMillis);
					addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_TOMCAT_PING_LATE,"Last Tomcat Ping at at " + simpleFormatter.format(new Timestamp(lastTomcatPingMillis)));
					copyLogFiles(faultDate);
					ArrayList results = Utils.executeCommand("sudo reboot");
					String data = "Reboot command response="  +String.join(", ", results);
					logger.warn("Medula is rebooting from tomcat problem, reboot data=" + data);

				}

			} catch (IOException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			//
			// check the size of the heartdb
			File file = new File(Utils.getLocalDirectory() + "heart" + File.separator +  "heart.mapdb.p");
			int heartDBOriginalSize = (int)file.length()/(1024*1024);
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
						try {
							results = Utils.executeCommand("sudo reboot");
							String data = "Reboot command response="  +String.join(", ", results);
							logger.warn("after executing rebootfrom heart problem command and while still in medule data=" + data);
						} catch (IOException | InterruptedException e) {
							// TODO Auto-generated catch block
							logger.warn(Utils.getStringException(e));
						}


					}
				}
			}

			//
			// if we are here it means we are ok
			//
			//			DecimalFormat df = new DecimalFormat(".##");
			//			logger.info("we are ok, seconds since last pulse=" + timeSinceLastPulse/1000 + " maximum time before is late " + (numberOfPulsesBeforeIsLate*currentPulseFrequency)/1000+ " heartdb size= "+ df.format(heartDBOriginalSize));			
			//			
			logger.info("we are ok, seconds since last pulse=" + timeSinceLastPulse/1000 + " maximum time before is late " + (numberOfPulsesBeforeIsLate*currentPulseFrequency)/1000+ " heartdb size="+ heartDBOriginalSize+"mb maximumheartdb size=" + ((int)MAXIMUM_HEART_DB_SIZE/(1024*1024)) +"mb");			



		}catch (JSONException e) {
			// TODO Auto-generated catch block
			logger.info(Utils.getStringException(e));
		}   catch (InvalidDenomeException e) {
			// TODO Auto-generated catch block
			logger.info(Utils.getStringException(e));
		}


		logger.info("Ending Medula at " + new Date());

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
		if(srcFile.isFile()) {
			logger.debug("copying file" + srcFile.getAbsolutePath() + " to " + destFile.getAbsolutePath());;
			srcFile = new File(srcFolderName + "ArduinoUno.txt");
			destFile =  new File(destFolderName + "ArduinoUno.txt");
			destFileWeb =  new File(destFolderWebRootName + "ArduinoUno.txt");
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
				e.printStackTrace();
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
		
		//boolean late=(numberOfPulsesBeforeIsLate*(currentPulseFrequency  + currentPulseGenerationDuration))< timeSinceLastPulse;
		boolean late=(numberOfPulsesBeforeIsLate*currentPulseFrequency )< timeSinceLastPulse;
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


			String pathologyName = TeleonomeConstants.PATHOLOGY_MEDULA_FORCED_REBOOT;
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

			} catch (JSONException e2) {
				// TODO Auto-generated catch block
				e2.printStackTrace();
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
				FileUtils.write(new File(Utils.getLocalDirectory() + "Teleonome.denome"), denomeJSONObject.toString(4));
				FileUtils.write(new File(Utils.getLocalDirectory() + "tomcat/webapps/ROOT/Teleonome.denome"), denomeJSONObject.toString(4));

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
				e.printStackTrace();
			} catch (NumberFormatException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
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
			e.printStackTrace();
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
			e.printStackTrace();
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
			e.printStackTrace();
		}
		//
		// now sta
	}

	 */

	public static void main(String[] args) {
		if(args.length>0 && args[0].equals("-v")) {
			System.out.println("Medula Build " + buildNumber);
		}else {
			new Medula().monitor();
		}



	}

}
