package com.fluxtream.connectors.sms_backup;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import javax.mail.FetchProfile;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Store;
import javax.mail.search.SentDateTerm;

import com.fluxtream.utils.JPAUtils;
import com.fluxtream.utils.Utils;
import oauth.signpost.OAuthConsumer;
import org.joda.time.DateTime;
import org.springframework.stereotype.Component;

import com.fluxtream.connectors.Connector;
import com.fluxtream.connectors.ObjectType;
import com.fluxtream.connectors.annotations.JsonFacetCollection;
import com.fluxtream.connectors.annotations.Updater;
import com.fluxtream.connectors.updaters.AbstractUpdater;
import com.fluxtream.connectors.updaters.RateLimitReachedException;
import com.fluxtream.connectors.updaters.UpdateInfo;
import com.fluxtream.domain.AbstractFacet;
import com.fluxtream.domain.ApiKey;
import com.fluxtream.domain.ApiUpdate;
import com.fluxtream.services.GuestService;
import com.fluxtream.utils.MailUtils;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;

@Component
@Updater(prettyName = "SMS Backup", value = 6, objectTypes = {
		CallLogEntryFacet.class, SmsEntryFacet.class })
@JsonFacetCollection(SmsBackupFacetVOCollection.class)
public class SmsBackupUpdater extends AbstractUpdater {

	// basic cache for email connections
	static ConcurrentMap<String, Store> stores;

	public SmsBackupUpdater() {
		super();
	}

	@Override
	protected void updateConnectorDataHistory(UpdateInfo updateInfo)
			throws RateLimitReachedException, Exception {

        updateConnectorData(updateInfo);
	}

	@Override
	public void updateConnectorData(UpdateInfo updateInfo) throws Exception {
		List<ObjectType> objectTypes = updateInfo.objectTypes();
		String email = updateInfo.apiKey.getAttributeValue("username", env);
		String password = updateInfo.apiKey.getAttributeValue("password", env);

        ObjectType callLogObjectType = ObjectType.getObjectType(connector(),
                                                                "call_log");
        if (objectTypes.contains(callLogObjectType)) {
            Date since = getStartDate(updateInfo, callLogObjectType);
            retrieveCallLogSinceDate(updateInfo, email, password, since);
        }

        ObjectType smsObjectType = ObjectType.getObjectType(connector(), "sms");
        if (objectTypes.contains(smsObjectType)) {
            Date since = getStartDate(updateInfo, smsObjectType);
            retrieveSmsEntriesSince(updateInfo, email, password, since);
        }





	}


    public Date getStartDate(UpdateInfo updateInfo, ObjectType ot) {
        ApiKey apiKey = updateInfo.apiKey;

        String updateKeyName = "SMSBackup." + ot.getName() + ".updateStartDate";
        String updateStartDate = guestService.getApiKeyAttribute(apiKey, updateKeyName);

        if(updateStartDate == null) {
            updateStartDate = "0";

            guestService.setApiKeyAttribute(updateInfo.apiKey, updateKeyName, updateStartDate);
        }
        return new Date(Long.parseLong(updateStartDate));
    }

    private void updateStartDate(UpdateInfo updateInfo, ObjectType ot, long updateProgressTime){

        // Calculate the name of the key in the ApiAttributes table
        // where the next start of update for this object type is
        // stored and retrieve the stored value.  This stored value
        // may potentially be null if something happened to the attributes table
        String updateKeyName = "SMSBackup." + ot.getName() + ".updateStartDate";
        long lastUpdateStart = getStartDate(updateInfo,ot).getTime();

        if (updateProgressTime <= lastUpdateStart) return;


        guestService.setApiKeyAttribute(updateInfo.apiKey, updateKeyName, "" + updateProgressTime);
    }

    private void updateStartDate(UpdateInfo updateInfo, ObjectType ot, Date updateProgressTime){
        updateStartDate(updateInfo,ot,updateProgressTime.getTime());
    }


	private void flushEntries(UpdateInfo updateInfo,
			List<? extends AbstractFacet> entries) throws Exception {
		for (AbstractFacet entry : entries) {
			if (!isDuplicate(updateInfo.getGuestId(), entry))
				apiDataService.cacheApiDataObject(updateInfo, -1, -1, entry);
		}

		entries.clear();
	}

	private boolean isDuplicate(long guestId, AbstractFacet entry) {
		if (entry instanceof SmsEntryFacet)
			return isDuplicateSmsEntry(guestId, (SmsEntryFacet) entry);
		else
			return isDuplicateCallLogEntry(guestId, (CallLogEntryFacet) entry);
	}

	private boolean isDuplicateCallLogEntry(long guestId,
			CallLogEntryFacet entry) {
		CallLogEntryFacet found = jpaDaoService.findOne(
				"sms_backup.call_log.byEmailId", CallLogEntryFacet.class,
				entry.apiKeyId, entry.emailId);
        return found != null;
	}

	private boolean isDuplicateSmsEntry(long guestId, SmsEntryFacet entry) {
        SmsEntryFacet found = jpaDaoService.findOne(
				"sms_backup.sms.byEmailId", SmsEntryFacet.class, entry.apiKeyId,
				entry.emailId);
        return found != null;
	}

	static boolean checkAuthorization(GuestService guestService, Long guestId) {
		ApiKey apiKey = guestService.getApiKey(guestId,
				Connector.getConnector("sms_backup"));
		return apiKey != null;
	}

	boolean testConnection(String email, String password) {
		try {
			MailUtils.getGmailImapStore(email, password);
		} catch (MessagingException e) {
			return false;
		}
		return true;
	}

	private Store getStore(String email, String password)
			throws MessagingException {
		if (stores == null)
			stores = new ConcurrentLinkedHashMap.Builder<String, Store>()
					.maximumWeightedCapacity(100).build();
		Store store = null;
		if (stores.get(email) != null) {
			store = stores.get(email);
			if (!store.isConnected())
				store.connect();
			boolean stillAlive = true;
			try {
				store.getDefaultFolder();
			} catch (Exception e) {
				stillAlive = false;
			}
			if (stillAlive)
				return store;
		}
		store = MailUtils.getGmailImapStore(email, password);
		stores.put(email, store);
		return store;
	}

	List<SmsEntryFacet> retrieveSmsEntriesSince(UpdateInfo updateInfo,
			String email, String password, Date date) throws Exception {
		long then = System.currentTimeMillis();
		String query = "(incremental sms log retrieval)";
		ObjectType smsObjectType = ObjectType.getObjectType(connector(), "sms");
		String smsFolderName = guestService.getApiKeyAttribute(updateInfo.apiKey, "smsFolderName");
		try {
			Store store = getStore(email, password);
			Folder folder = store.getDefaultFolder();
			if (folder == null)
				throw new Exception("No default folder");
			folder = folder.getFolder(smsFolderName);
			if (folder == null)
				throw new Exception("No Sms Log");
			Message[] msgs = getMessagesInFolderSinceDate(folder, date);
			List<SmsEntryFacet> smsLog = new ArrayList<SmsEntryFacet>();
			for (Message message : msgs) {
                date = message.getReceivedDate();
				SmsEntryFacet entry = new SmsEntryFacet(message, email, updateInfo.apiKey.getId());
				smsLog.add(entry);
				if (smsLog.size() == 20){
					flushEntries(updateInfo, smsLog);
                    updateStartDate(updateInfo,smsObjectType,date);
                }
			}
			flushEntries(updateInfo, smsLog);
            updateStartDate(updateInfo,smsObjectType,date);
			countSuccessfulApiCall(updateInfo.apiKey,
					smsObjectType.value(), then, query);
			return smsLog;
		} catch (Exception ex) {
            ex.printStackTrace();
			countFailedApiCall(updateInfo.apiKey, smsObjectType.value(),
					then, query, Utils.stackTrace(ex));
			throw ex;
		}
	}

	List<CallLogEntryFacet> retrieveCallLogSinceDate(UpdateInfo updateInfo,
			String email, String password, Date date) throws Exception {
		long then = System.currentTimeMillis();
		String query = "(incremental call log retrieval)";
		ObjectType callLogObjectType = ObjectType.getObjectType(connector(),
				"call_log");
		String callLogFolderName = guestService.getApiKeyAttribute(updateInfo.apiKey, "callLogFolderName");
		try {
			Store store = getStore(email, password);
			Folder folder = store.getDefaultFolder();
			if (folder == null)
				throw new Exception("No default folder");
			folder = folder.getFolder(callLogFolderName);
			if (folder == null)
				throw new Exception("No Call Log");
			Message[] msgs = getMessagesInFolderSinceDate(folder, date);
			List<CallLogEntryFacet> callLog = new ArrayList<CallLogEntryFacet>();
			for (Message message : msgs) {
                date = message.getReceivedDate();
				CallLogEntryFacet entry = new CallLogEntryFacet(message, updateInfo.apiKey.getId());
				callLog.add(entry);
				if (callLog.size() == 20){
					flushEntries(updateInfo, callLog);
                    updateStartDate(updateInfo,callLogObjectType,date);
                }
			}
			flushEntries(updateInfo, callLog);
            updateStartDate(updateInfo,callLogObjectType,date);
			countSuccessfulApiCall(updateInfo.apiKey,
					callLogObjectType.value(), then, query);
			return callLog;
		} catch (Exception ex) {
            ex.printStackTrace();
			countFailedApiCall(updateInfo.apiKey,
					callLogObjectType.value(), then, query, Utils.stackTrace(ex));
			throw ex;
		}
	}

	private Message[] getMessagesInFolder(Folder folder) throws Exception {
		if (!folder.isOpen())
			folder.open(Folder.READ_ONLY);
		Message[] msgs = folder.getMessages();

		FetchProfile fp = new FetchProfile();
		fp.add(FetchProfile.Item.ENVELOPE);
		fp.add(FetchProfile.Item.CONTENT_INFO);
		fp.add("Content");

		folder.fetch(msgs, fp);
		return msgs;
	}

	private Message[] getMessagesInFolderSinceDate(Folder folder, Date date)
			throws Exception {
		if (!folder.isOpen())
			folder.open(Folder.READ_ONLY);
		SentDateTerm term = new SentDateTerm(SentDateTerm.GT, date);
		Message[] msgs = folder.search(term);

		FetchProfile fp = new FetchProfile();
		fp.add(FetchProfile.Item.ENVELOPE);
		fp.add(FetchProfile.Item.CONTENT_INFO);
		fp.add("Content");

		folder.fetch(msgs, fp);
		return msgs;
	}

}
