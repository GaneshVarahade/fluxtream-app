package com.fluxtream.services.impl;

import java.util.Date;
import com.fluxtream.Configuration;
import com.fluxtream.aspects.FlxLogger;
import com.fluxtream.connectors.Connector;
import com.fluxtream.connectors.updaters.AbstractUpdater;
import com.fluxtream.connectors.updaters.UpdateInfo;
import com.fluxtream.connectors.updaters.UpdateResult;
import com.fluxtream.domain.ApiKey;
import com.fluxtream.domain.UpdateWorkerTask;
import com.fluxtream.domain.UpdateWorkerTask.Status;
import com.fluxtream.services.ApiDataService;
import com.fluxtream.services.ConnectorUpdateService;
import com.fluxtream.services.GuestService;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static com.fluxtream.utils.Utils.stackTrace;

@Component
@Scope("prototype")
class UpdateWorker implements Runnable {

	FlxLogger logger = FlxLogger.getLogger(UpdateWorker.class);

    @Qualifier("connectorUpdateServiceImpl")
    @Autowired
	ConnectorUpdateService connectorUpdateService;

    @Qualifier("apiDataServiceImpl")
    @Autowired
	ApiDataService apiDataService;

	@Autowired
	GuestService guestService;

	@Autowired
	Configuration env;

	UpdateWorkerTask task;

	public UpdateWorker() {
	}

    @Trace(dispatcher=true)
	@Override
	public void run() {
        logNR();
        StringBuilder sb = new StringBuilder("module=updateQueue component=worker action=start")
                .append(" guestId=").append(task.getGuestId())
                .append(" connector=").append(task.connectorName)
                .append(" objectType=").append(task.objectTypes)
                .append(" apiKeyId=").append(task.apiKeyId);
		logger.info(sb.toString());

		Connector connector = Connector.getConnector(task.connectorName);
		ApiKey apiKey = guestService.getApiKey(task.apiKeyId);
		AbstractUpdater updater = connectorUpdateService.getUpdater(connector);

		switch (task.updateType) {
		case INITIAL_HISTORY_UPDATE:
			updateDataHistory(connector, apiKey, updater);
			break;
		case PUSH_TRIGGERED_UPDATE:
			pushTriggeredUpdate(connector, apiKey, updater);
			break;
        case INCREMENTAL_UPDATE:
            updateData(connector, apiKey, updater);
            break;
        default:
            logger.warn("module=updateQueue component=worker message=\"UpdateType was not handled (" + task.updateType + ")\"");
            connectorUpdateService.setUpdateWorkerTaskStatus(task.getId(), Status.FAILED);
        }
	}

    private void logNR() {
        try {
            StringBuilder taskName = new StringBuilder("Background_Update_");
            taskName.append(task.connectorName);
            taskName.append("_").append(task.objectTypes);
            NewRelic.setTransactionName(null, taskName.toString());
            NewRelic.addCustomParameter("connector", task.connectorName);
            NewRelic.addCustomParameter("objectType", task.objectTypes);
            NewRelic.addCustomParameter("guestId", task.getGuestId());
        } catch (Throwable t) {
            logger.warn("Could not set NR info..." + task.connectorName);
        }
    }

    private void pushTriggeredUpdate(Connector connector, ApiKey apiKey,
			AbstractUpdater updater) {
		try {
            logger.info("module=updateQueue component=worker action=pushTriggeredUpdate " +
                        "connector=" + connector.getName() + " guestId=" + apiKey.getGuestId());
			UpdateInfo updateInfo = UpdateInfo.pushTriggeredUpdateInfo(apiKey,
					task.objectTypes, task.jsonParams);
			UpdateResult updateResult = updater.updateData(updateInfo);
			handleUpdateResult(connector, updateResult);
		} catch (Throwable e) {
			String stackTrace = stackTrace(e);
			logger.warn("module=updateQueue component=worker action=pushTriggeredUpdate " +
                        "message=\"Unexpected Exception\" " +
                        "guestId=" + task.guestId + " objectType=" + task.objectTypes +
                        " stackTrace=<![CDATA[" + stackTrace + "]]>");
			retry(connector, new UpdateWorkerTask.AuditTrailEntry(new Date(), "unexpected exception", "retry", stackTrace));
		}
	}

	private void updateDataHistory(Connector connector, ApiKey apiKey,
			AbstractUpdater updater) {
        try {
            logger.info("module=updateQueue component=worker action=updateDataHistory " +
                        "connector=" + connector.getName() + " guestId=" + apiKey.getGuestId());
            UpdateInfo updateInfo = UpdateInfo.initialHistoryUpdateInfo(apiKey,
                    task.objectTypes);
            UpdateResult updateResult = updater.updateDataHistory(updateInfo);
            handleUpdateResult(connector, updateResult);
        } catch (Exception e) {
            String stackTrace = stackTrace(e);
            logger.warn("module=updateQueue component=worker action=updateDataHistory" +
                        " guestId=" + task.guestId + " action=updateDataHistory" +
                        " message=\"Unexpected Exception\" connector=" +
                        task.connectorName + " objectType=" + task.objectTypes +
                        " stackTrace=<![CDATA[" + stackTrace + "]]>");
            retry(connector, new UpdateWorkerTask.AuditTrailEntry(new Date(), "unexpected exception", "retry", stackTrace));
        }
	}

    private void updateData(final Connector connector, final ApiKey apiKey, final AbstractUpdater updater) {
        try
        {
            logger.info("module=updateQueue component=worker action=\"updateData (incremental update)\"" +
                        " connector=" + connector.getName() + " guestId=" + apiKey.getGuestId());
            UpdateInfo updateInfo = UpdateInfo.IncrementalUpdateInfo(apiKey, task.objectTypes);
            UpdateResult result = updater.updateData(updateInfo);
            handleUpdateResult(connector, result);
        } catch (Exception e){
            String stackTrace = stackTrace(e);
            logger.warn("module=updateQueue component=worker action=\"updateData (incremental update)\"" +
                        " guestId=" + task.guestId
                        + " message=\"Unexpected Exception\" connector="
                        + task.connectorName + " objectType=" + task.objectTypes +
                        " stackTrace=<![CDATA[" + stackTrace + "]]>");
            retry(connector, new UpdateWorkerTask.AuditTrailEntry(new Date(), "unexpected exception", "retry", stackTrace));
        }
    }

	private void handleUpdateResult(Connector connector,
			UpdateResult updateResult) {
		switch (updateResult.type) {
		case DUPLICATE_UPDATE:
			duplicateUpdate();
			break;
		case HAS_REACHED_RATE_LIMIT:
			longReschedule(connector, new UpdateWorkerTask.AuditTrailEntry(new Date(), updateResult.type.toString(), "long reschedule"));
			break;
		case UPDATE_SUCCEEDED:
			success();
			break;
		case UPDATE_FAILED:
            final UpdateWorkerTask.AuditTrailEntry failed = new UpdateWorkerTask.AuditTrailEntry(new Date(), updateResult.type.toString(), "failed");
            failed.stackTrace = updateResult.stackTrace;
            connectorUpdateService.addAuditTrail(task.getId(), failed);
            final UpdateWorkerTask.AuditTrailEntry retry = new UpdateWorkerTask.AuditTrailEntry(new Date(), updateResult.type.toString(), "retry");
            retry(connector, retry);
			break;
		case NO_RESULT:
			abort();
			break;
		}
	}

	private void duplicateUpdate() {
		logger.warn("module=updateQueue component=worker action=duplicateUpdate guestId=" + task.getGuestId() +
				" connector=" + task.connectorName + " objectType="
                + task.objectTypes);
	}

	private void success() {
		StringBuilder stringBuilder = new StringBuilder("module=updateQueue component=worker action=success")
                .append(" guestId=").append(task.getGuestId())
                .append(" connector=").append(task.objectTypes);
		logger.info(stringBuilder.toString());
		connectorUpdateService.setUpdateWorkerTaskStatus(task.getId(), Status.DONE);
	}

	private void abort() {
		StringBuilder stringBuilder = new StringBuilder("module=updateQueue component=worker action=abort")
                .append(" guestId=").append(task.getGuestId())
                .append(" connector=").append(task.connectorName)
                .append(" objectType=").append(task.objectTypes);
		logger.info(stringBuilder.toString());
		connectorUpdateService.setUpdateWorkerTaskStatus(task.getId(), Status.FAILED);
	}

	private void retry(Connector connector, UpdateWorkerTask.AuditTrailEntry auditTrailEntry) {
		StringBuilder stringBuilder = new StringBuilder("module=updateQueue component=worker action=retry")
                .append(" guestId=").append(task.getGuestId())
                .append(" connector=").append(task.connectorName)
                .append(" objectType=").append(task.objectTypes);
        if (auditTrailEntry.stackTrace!=null) {
            stringBuilder.append(" stackTrace=<![CDATA[")
                    .append(auditTrailEntry.stackTrace)
                    .append("]]>");
        }
		logger.info(stringBuilder.toString());
		int maxRetries = 0;
		try {
			maxRetries = getMaxRetries(connector);
		} catch (Throwable t) {
			t.printStackTrace();
		}
		if (task.retries < maxRetries) {
            auditTrailEntry.nextAction = "short reschedule";
            shortReschedule(connector, auditTrailEntry);
		} else {
            auditTrailEntry.nextAction = "long reschedule";
			longReschedule(connector, auditTrailEntry);
		}
	}

	private void longReschedule(Connector connector, UpdateWorkerTask.AuditTrailEntry auditTrailEntry) {
		StringBuilder stringBuilder = new StringBuilder("module=updateQueue component=worker action=longReschedule")
                .append(" guestId=").append(task.getGuestId())
                .append(" connector=").append(task.connectorName)
                .append(" objectType=").append(task.objectTypes);
		logger.info(stringBuilder.toString());
		// re-schedule when we are below rate limit again
		connectorUpdateService.reScheduleUpdateTask(task.getId(), System.currentTimeMillis() + getLongRetryDelay(connector),
                                                    false, auditTrailEntry);
	}

	private void shortReschedule(Connector connector, UpdateWorkerTask.AuditTrailEntry auditTrailEntry) {
		StringBuilder sb = new StringBuilder("module=updateQueue component=worker action=shortReschedule")
                .append(" guestId=").append(task.getGuestId())
                .append(" connector=").append(task.connectorName)
                .append(" objectType=").append(task.objectTypes)
                .append(" retries=").append(String.valueOf(task.retries));
		logger.info(sb.toString());
		// schedule 1 minute later, typically
		connectorUpdateService.reScheduleUpdateTask(task.getId(), System.currentTimeMillis() + getShortRetryDelay(connector),
                                                    true, auditTrailEntry);
	}

	private int getMaxRetries(Connector connector) {
		String key = connector.getName() + ".maxRetries";
        if (env.connectors.containsKey(key)) {
            return Integer.valueOf((String)env.connectors.getProperty(key));
        }
        else {
            return Integer.valueOf((String)env.connectors.getProperty("maxRetries"));
        }
	}

	private int getShortRetryDelay(Connector connector) {
		String key = connector.getName() + ".shortRetryDelay";
        if (env.connectors.containsKey(key)) {
            return Integer.valueOf((String)env.connectors.getProperty(key));
        }
        else {
            return Integer.valueOf((String)env.connectors.getProperty("shortRetryDelay"));
        }
	}

	private int getLongRetryDelay(Connector connector) {
		String key = connector.getName() + ".longRetryDelay";
        if (env.connectors.containsKey(key)) {
            return Integer.valueOf((String)env.connectors.getProperty(key));
        }
        else {
            return Integer.valueOf((String)env.connectors.getProperty("longRetryDelay"));
        }
	}

}
