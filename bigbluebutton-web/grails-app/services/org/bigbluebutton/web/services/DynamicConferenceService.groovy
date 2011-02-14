/* BigBlueButton - http://www.bigbluebutton.org
 * 
 * 
 * Copyright (c) 2008-2009 by respective authors (see below). All rights reserved.
 * 
 * BigBlueButton is free software; you can redistribute it and/or modify it under the 
 * terms of the GNU Lesser General Public License as published by the Free Software 
 * Foundation; either version 3 of the License, or (at your option) any later 
 * version. 
 * 
 * BigBlueButton is distributed in the hope that it will be useful, but WITHOUT ANY 
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A 
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License along 
 * with BigBlueButton; if not, If not, see <http://www.gnu.org/licenses/>.
 *
 * @author Jeremy Thomerson <jthomerson@genericconf.com>
 * @version $Id: $
 */
package org.bigbluebutton.web.services

import org.bigbluebutton.conference.Room
import java.util.concurrent.ConcurrentHashMap
import org.apache.commons.collections.bidimap.DualHashBidiMap
import java.util.*;
import java.util.concurrent.*;
import org.bigbluebutton.api.domain.DynamicConference;
 
public class DynamicConferenceService implements IDynamicConferenceService {	
	static transactional = false
	def serviceEnabled = false
	def apiVersion;
	def securitySalt
	int minutesElapsedBeforeMeetingExpiration = 60
	def defaultWelcomeMessage
	def defaultDialAccessNumber
	def testVoiceBridge
	def testConferenceMock
	def recordingDir
	def recordingFile
	
	// TODO: need to remove use of DynamicConference and make it use "Room.groovy" instead
	//				so that both apps and web are using common domain objects and we don't map between them
	private final Map<String, Room> roomsByToken
	private final Map<String, DynamicConference> confsByMtgID
	private final Map<String, String> tokenMap
	
	public DynamicConferenceService() {
		confsByMtgID = new ConcurrentHashMap<String, DynamicConference>()
		tokenMap = new DualHashBidiMap<String, String>()
		roomsByToken = new ConcurrentHashMap<String, Room>()
		
		// wait one minute to run, and run every five minutes:
		TimerTask task = new DynamicConferenceServiceCleanupTimerTask(this);
		new Timer("api-cleanup", true).scheduleAtFixedRate(task, 60000, 300000);
		// PS - <rant> I hate Groovy - no inline (anonymous or inner) class support (until 1.7)?  Come on!  Closures aren't the be-all-end-all </rant>
	}
	
	void cleanupOldConferences() {
		println("Cleaning out old conferences");
		for (DynamicConference conf : confsByMtgID.values()) {
			boolean remove = false;
			if (conf.isRunning()) {
				println "Meeting [" + conf.getMeetingID() + "] is running - not cleaning it out"
				// won't remove one that's running
				continue;
			}
			
			long millisSinceStored = conf.getStoredTime() == null ? -1 : (System.currentTimeMillis() - conf.getStoredTime().getTime());
			long millisSinceEnd = conf.getEndTime() == null ? -1 : (System.currentTimeMillis() - conf.getEndTime().getTime());
			if (conf.getStartTime() != null && millisSinceEnd > (minutesElapsedBeforeMeetingExpiration * 60000)) {
				println("Removing meeting because it started, ended, and is past the max expiration");
				remove = true;
			} else if (conf.getEndTime() == null && millisSinceStored > (minutesElapsedBeforeMeetingExpiration * 60000)) {
				println("Removing meeting because it was stored, but never started [stored " + millisSinceStored + " millis ago]");
				remove = true;
			}
			
			if (remove) {
				println "Removing meeting [" + conf.getMeetingToken() + "]"
				confsByMtgID.remove(conf.getMeetingID());
				roomsByToken.remove(conf.getMeetingToken());
				tokenMap.remove(conf.getMeetingToken());
			} else {
				println "Not removing meeting [" + conf.getMeetingID() + "]"
			}
		}
	}
	
	public Collection<DynamicConference> getAllConferences() {
		return confsByMtgID.isEmpty() ? Collections.emptySet() : Collections.unmodifiableCollection(confsByMtgID.values());
	}
	
	public void storeConference(DynamicConference conf) {
		conf.setStoredTime(new Date());
		confsByMtgID.put(conf.getMeetingID(), conf);
		tokenMap.put(conf.getMeetingToken(), conf.getMeetingID());
		if(conf.record)
			createConferenceRecord(conf);
	}

	public void createConferenceRecord(DynamicConference conf) {
		String dirpath=recordingDir+File.separatorChar+conf.meetingToken+File.separatorChar+conf.meetingToken+File.separatorChar
		String filename=dirpath+recordingFile
		if(!new File(dirpath).exists()){
			boolean success = (new File(dirpath)).mkdirs()
		}

		def mb = new groovy.xml.StreamingMarkupBuilder()
		mb.encoding = "UTF-8"
		new OutputStreamWriter(new FileOutputStream(filename),'utf-8') << mb.bind {
			mkp.xmlDeclaration()
			events(token:conf.meetingToken,name:conf.name,date:conf.storedTime){
				seq{
				}
			}
		}

	}
	
	public Room getRoomByMeetingID(String meetingID) {
		if (meetingID == null) {
			return null;
		}
		String token = tokenMap.getKey(meetingID);
		if (token == null) {
			System.out.println("Cannot find token for meetingId " + meetingID)
			return null;
		}
		return roomsByToken.get(token);
	}
	
	public DynamicConference getConferenceByMeetingID(String meetingID) {
		if (meetingID == null) {
			return null;
		}
		return confsByMtgID.get(meetingID);
	}
	
	private DynamicConference getConferenceByToken(String token) {
		if (token == null) {
			return null;
		}
		String mtgID = tokenMap.get(token);
		if (mtgID == null) {
			return null;
		}
		return confsByMtgID.get(mtgID);
	}
	
	public boolean isMeetingWithVoiceBridgeExist(String voiceBridge) {
		Collection<DynamicConference> confs = confsByMtgID.values()
		for (DynamicConference c : confs) {
	        if (voiceBridge == c.voiceBridge) {
	        	log.debug "Found voice bridge $voiceBridge"
	        	return true
	        }
		}
		log.debug "could not find voice bridge $voiceBridge"
		return false
	}
		
	// these methods called by spring integration:
	public void conferenceStarted(Room room) {
		log.debug "conference started: " + room.getName();
		participantsUpdated(room);
		DynamicConference conf = getConferenceByToken(room.getName());
		if (conf != null) {
			conf.setStartTime(new Date());
			conf.setEndTime(null);
			log.debug "found conference and set start date"
		}
	}
	
	public void conferenceEnded(Room room) {
		log.debug "conference ended: " + room.getName();
		DynamicConference conf = getConferenceByToken(room.getName());
		if (conf != null) {
			conf.setEndTime(new Date());
			log.debug "found conference and set end date"
		}
	}
	
	public void participantsUpdated(Room room) {
		log.debug "participants updated: " + room.getName();
		System.out.println("participants updated: " + room.getName())
		roomsByToken.put(room.getName(), room);
	}
	// end of spring integration-called methods
	
	public void processRecording(String meetingId) {
		System.out.println("enter processRecording " + meetingId)
		Room room = roomsByToken.get(meetingId)
		if (room != null) {
			System.out.println("Number of participants in room " + room.getNumberOfParticipants())
			if (room.getNumberOfParticipants() == 0) {
				System.out.println("starting processRecording " + meetingId)
				// Run conversion on another thread.
				new Timer().runAfter(1000) {
					startIngestAndProcessing(meetingId)
				}		
			} else {
				System.out.println("Someone still in the room...not processRecording " + meetingId)
			}
		} else {
			System.out.println("Could not find room " + meetingId + " ... Not processing recording")
		}

	}
	
	private void startIngestAndProcessing(meetingId) {				
		String COMMAND = "python /home/firstuser/python/src/ingestandproc.py -m" + meetingId + " -a /var/freeswitch/meetings -p /var/bigbluebutton -r /var/bigbluebutton/archive -e 192.168.0.166 -o 6379 -i /var/bigbluebutton/ingest -b /var/bigbluebutton/recordings"; 
		
		try {
			Process p = Runtime.getRuntime().exec(COMMAND);            
        	
			BufferedReader stdInput = new BufferedReader(new InputStreamReader(p.getInputStream()));
			BufferedReader stdError = new BufferedReader(new InputStreamReader(p.getErrorStream()));
			String info;

			while ((info = stdInput.readLine()) != null) {
				System.out.println(info)
			}
			while ((info = stdError.readLine()) != null) {
				log.error(info);
			}
			stdInput.close();
			stdError.close();

			// Wait for the process to finish.
        	int exitValue = p.waitFor();
        	if (exitValue != 0) {
		    	log.warn("Exit Value != 0 while for " + COMMAND);
		    }
		} catch (IOException e) {
			log.error("IOException while processing " + COMMAND);
		} catch (InterruptedException e) {
			log.error("InterruptedException while processing " + COMMAND);
		}
	}
	
	
}
