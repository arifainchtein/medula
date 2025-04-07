package com.teleonome.medula;



import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.charset.Charset;
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
			
			//
			// check the services
			//
			boolean networkok = checkNetwork();
			boolean avahiok = checkService("avahi-daemon");
			if(!avahiok) restartService("avahi-daemon");
		    boolean postgresok = checkService("postgresql");
			if(!postgresok) restartService("postgresql");
		        
			
			
			
			
			File denomeFile = new File(Utils.getLocalDirectory() + "Teleonome.denome");
			if(!denomeFile.isFile()) {
				logger.info("Teleonome.denome was not found, copying from previous_pulse" );
				FileUtils.copyFile(new File(Utils.getLocalDirectory() + "Teleonome.previous_pulse"), new File(Utils.getLocalDirectory() + "Teleonome.denome"));
				addPathologyDene(faultDate, TeleonomeConstants.PATHOLOGY_MISSING_DENOME_FILE,"");
				denomeFile = new File(Utils.getLocalDirectory() + "Teleonome.denome");

			}
			denomeFileInString = FileUtils.readFileToString(denomeFile, Charset.defaultCharset());
			boolean validJSONFormat=true;
			logger.info("checking the Teleonome.denome first, length=" + denomeFileInString.length() );
			boolean restartHypothalamus=false;
			try{
				denomeJSONObject = new JSONObject(denomeFileInString);

				//
				// ok the teleonome is a valid file, now check if its late
				//
				boolean late= isPulseLate( denomeJSONObject);
				String lastPulseDate = denomeJSONObject.getString(TeleonomeConstants.PULSE_TIMESTAMP);
				if(late){
					logger.info("PULSE LATE, seconds since currentPulseFrequency=" + currentPulseFrequency + " numberOfPulsesBeforeIsLate=" + numberOfPulsesBeforeIsLate + " last pulse=" + timeSinceLastPulse/1000 + " maximum number of seconds =" + (numberOfPulsesBeforeIsLate*currentPulseFrequency)/1000);
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
						FileUtils.deleteQuietly(new File(Utils.getLocalDirectory() + "Teleonome.denome"));
						FileUtils.copyFile(previousPulseFile, new File(Utils.getLocalDirectory() + "Teleonome.denome"));
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
					FileUtils.deleteQuietly(new File(Utils.getLocalDirectory() + "Teleonome.denome"));
					FileUtils.copyFile(originalPulseFile, new File(Utils.getLocalDirectory() + "Teleonome.denome"));

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
					Utils.executeCommand("sudo kill -9  " + hypothalamusPid);
					logger.warn("killing teleonomehypothalamus process");

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

		//
		// now check the heart status
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
		JSONObject heartDenomeJSONObject = new JSONObject(heartDenomeFileInString);
		boolean late;
		String heartLastPulseDate = heartDenomeJSONObject.getString(TeleonomeConstants.PULSE_TIMESTAMP);



		try {
			late= isPulseLate( heartDenomeJSONObject);
			if(late ){
				logger.info("the heart  is late, seconds since currentPulseFrequency=" + currentPulseFrequency + " numberOfPulsesBeforeIsLate=" + numberOfPulsesBeforeIsLate + " last pulse=" + timeSinceLastPulse/1000 + " maximum number of seconds =" + (numberOfPulsesBeforeIsLate*currentPulseFrequency)/1000);
				//
				// if we are late, check to see if the pacemaker is running, 
				// get the processid

				ArrayList results = Utils.executeCommand("ps -p " + heartPid);
				String data =  " Heart Last Pulse at " + heartLastPulseDate + String.join(", ", results);


				//				 if the heart is running it will return two lines like:
				//				PID TTY          TIME CMD
				//				1872 pts/0    00:02:45 java
				//				if it only returns one line then the process is not running
				if(results.size()<2){
					logger.info("heart is not running");
					addPathologyDene(faultDate,TeleonomeConstants.PATHOLOGY_HEART_DIED, "data=" + data);
				}else{
					logger.info("heart is  running but still late, killing it... data=" + data);
					addPathologyDene(faultDate,TeleonomeConstants.PATHOLOGY_HEART_PULSE_LATE,data);
					logger.warn( "heart is running about to kill process " + heartPid);
					Utils.executeCommand("sudo kill -9  " + heartPid);
					Utils.executeCommand("sudo rm /home/pi/Teleonome/heart/heart.mapdb*");
					data = "killing the heart command response="  +String.join(", ", results);
					logger.warn( data);
					copyLogFiles(faultDate);
				}
				
				FileUtils.deleteQuietly(heartProcessInfo);
				try {
					Thread.sleep(5000);
				}catch(InterruptedException e) {
					logger.debug("line 277 sleep interrupted");
				}
				logger.info(" about to restart the heart"  );
				results = Utils.executeCommand("sudo sh /home/pi/Teleonome/heart/StartHeartBG.sh");
				data = "restarted the heart command response="  +String.join(", ", results);

				Thread.sleep(10000);
				logger.warn( data);
				//
				// now check the heart status
				//
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

		try {
			//
			// if the subscriberthread bug reappears
			//
			File dir = new File(Utils.getLocalDirectory() );
			FileFilter fileFilter = new WildcardFileFilter("*.hprof");
			File[] files = dir.listFiles(fileFilter);
			if(files.length>0) {
				StringBuffer data1=new StringBuffer();;
				//				logger.info("foound hprof file, renaming them to hprog" );
				//				File destFile;
				for(int i=0;i<files.length;i++) {
					data1.append(files[i].getAbsolutePath() );
				}

				//
				//				// remove 
				//				//
				Arrays.stream(dir.listFiles((f, p) -> p.endsWith("hprof"))).forEach(File::delete);

				//
				// add a pathology dene to the pulse
				//
				logger.warn("adding pathology dene");
				addPathologyDene(faultDate,TeleonomeConstants.PATHOLOGY_HYPOTHALAMUS_DIED,data1.toString());
				//Utils.executeCommand("sudo kill -9  " + hypothalamusPid);
				logger.warn("killing teleonomehypothalamus process");
				Utils.executeCommand("sudo kill -9  " + hypothalamusPid);
				copyLogFiles(faultDate);


				//	ArrayList results = Utils.executeCommand("sudo reboot");
				//logger.warn("restarting TeleonomeHypothalamus process");

				ArrayList results = Utils.executeCommand("sudo sh /home/pi/Teleonome/StartHypothalamusBG.sh");
				String data = "restarted the TeleonomeHypothalamus command response="  +String.join(", ", results);
				logger.warn("after restarting TeleonomeHypothalamus while still in medule data=" + data);
			}
		}catch(IOException e) {
			logger.warn(Utils.getStringException(e));

		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e));
		} catch (InvalidDenomeException e) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e));
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			logger.warn(Utils.getStringException(e));
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
				logger.warn("killing heart process heartPid=" + heartPid);
				ArrayList results = Utils.executeCommand("sudo kill -9  " + heartPid);
				String data = "kill heart, response="  +String.join(", ", results);
				logger.warn("delete mapdb files");
				Utils.executeCommand("sudo rm /home/pi/Teleonome/heart/heart.mapdb*");
				copyLogFiles(faultDate);
				//	 results = Utils.executeCommand("sudo reboot");
				logger.warn("restarting heart process");
				
				results = Utils.executeCommand("sudo sh /home/pi/Teleonome/heart/StartHeartBG.sh");
				try {
					Thread.sleep(5000);
				}catch(InterruptedException e) {
					logger.debug("line 412 sleep interrupted");
				}
				data = "restarted the heart command response="  +String.join(", ", results);
				
				logger.warn("after restarting heart while still in medule data=" + data);
			}
		}catch(IOException e) {
			logger.warn(Utils.getStringException(e));

		} catch (InterruptedException e) {
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
		// read the denome file and see when was the last pulse
		// as it is in the denome


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
		}else {
			new Medula().monitor();
		}



	}

}
