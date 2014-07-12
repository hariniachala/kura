/**
 * Copyright (c) 2011, 2014 Eurotech and/or its affiliates
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Eurotech
 */
package org.eclipse.kura.core.cloud.call;

import java.io.IOException;

import org.eclipse.kura.KuraConnectException;
import org.eclipse.kura.KuraErrorCode;
import org.eclipse.kura.KuraException;
import org.eclipse.kura.KuraInvalidMessageException;
import org.eclipse.kura.KuraStoreException;
import org.eclipse.kura.KuraTimeoutException;
import org.eclipse.kura.cloud.CloudCallService;
import org.eclipse.kura.cloud.app.RequestIdGenerator;
import org.eclipse.kura.core.cloud.CloudPayloadProtoBufDecoderImpl;
import org.eclipse.kura.core.cloud.CloudPayloadProtoBufEncoderImpl;
import org.eclipse.kura.data.DataService;
import org.eclipse.kura.data.DataServiceListener;
import org.eclipse.kura.message.KuraPayload;
import org.eclipse.kura.message.KuraRequestPayload;
import org.eclipse.kura.message.KuraResponsePayload;
import org.eclipse.kura.message.KuraTopic;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudCallServiceImpl implements CloudCallService, DataServiceListener 
{
	private static final Logger s_logger = LoggerFactory
			.getLogger(CloudCallServiceImpl.class);

	private static RequestIdGenerator s_generator = RequestIdGenerator
			.getInstance();

	private static final int DFLT_PUB_QOS = 0;
	private static final boolean DFLT_RETAIN = false;
	private static final int DFLT_PRIORITY = 1;

	private static final String ACCOUNT_NAME_VAR_NAME = "#account-name";
	private static final String CLIENT_ID_VAR_NAME = "#client-id";

	private DataService m_dataService;

	private Object m_lock;
	private String m_respTopic;
	private KuraResponsePayload m_resp;

	// ----------------------------------------------------------------
	//
	// Dependencies
	//
	// ----------------------------------------------------------------

	public void setDataService(DataService dataService) {
		m_dataService = dataService;
	}

	public void unsetDataService(DataService dataService) {
		m_dataService = null;
	}

	// ----------------------------------------------------------------
	//
	// Activation APIs
	//
	// ----------------------------------------------------------------

	protected void activate(ComponentContext componentContext) {
		s_logger.info("Activating...");
		m_lock = new Object();
	}

	protected void deactivate(ComponentContext componentContext) {
		s_logger.info("Deactivating...");
		synchronized (m_lock) {
			m_lock.notifyAll();
		}
	}

	@Override
	public synchronized KuraResponsePayload call(String appId, String appTopic,
			KuraPayload appPayload, int timeout) throws KuraConnectException,
			KuraTimeoutException, KuraStoreException, KuraException {
		return call(CLIENT_ID_VAR_NAME, appId, appTopic, appPayload, timeout);
	}

	@Override
	public synchronized KuraResponsePayload call(String deviceId, String appId,
			String appTopic, KuraPayload appPayload, int timeout)
			throws KuraConnectException, KuraTimeoutException, KuraStoreException,
			KuraException {
		// Generate the request ID
		String requestId = s_generator.next();

		StringBuilder sbReqTopic = new StringBuilder("$EDC").append("/")
				.append(ACCOUNT_NAME_VAR_NAME).append("/").append(deviceId)
				.append("/").append(appId).append("/").append(appTopic);

		StringBuilder sbRespTopic = new StringBuilder("$EDC").append("/")
				.append(ACCOUNT_NAME_VAR_NAME).append("/")
				.append(CLIENT_ID_VAR_NAME).append("/").append(appId)
				.append("/").append("REPLY").append("/").append(requestId);

		KuraRequestPayload req = null;
		if (appPayload != null) {
			// Construct a request payload
			req = new KuraRequestPayload(appPayload);
		} else {
			req = new KuraRequestPayload();
		}

		req.setRequestId(requestId);
		req.setRequesterClientId(CLIENT_ID_VAR_NAME);

		CloudPayloadProtoBufEncoderImpl encoder = new CloudPayloadProtoBufEncoderImpl(req);
		byte[] rawPayload;
		try {
			rawPayload = encoder.getBytes();
		} catch (IOException e) {
			throw new KuraException(KuraErrorCode.INTERNAL_ERROR, e,
					"Cannot encode request");
		}

		m_respTopic = sbRespTopic.toString();
		m_resp = null;

		m_dataService.subscribe(m_respTopic, 0);

		synchronized (m_lock) {
			try {
				m_dataService.publish(sbReqTopic.toString(), rawPayload,
						DFLT_PUB_QOS, DFLT_RETAIN, DFLT_PRIORITY);
				m_lock.wait(timeout);
			} catch (KuraStoreException e) {
				throw e;
			} catch (InterruptedException e) {
				// Avoid re-throwing this exception which should not normally happen
				s_logger.warn("Interrupted while waiting for the response");
				Thread.interrupted();
			} finally {
				try {
					m_dataService.unsubscribe(m_respTopic);
				} catch (KuraException e) {
					s_logger.error("Cannot unsubscribe");
				}
				m_respTopic = null;
			}
		}

		if (m_resp == null) {
			throw new KuraTimeoutException(
					"Timed out while waiting for the response");
		}

		return m_resp;
	}
	
	public void cancel() {
		synchronized (m_lock) {
			notifyAll();
		}
	}
	
	@Override
	public void onConnectionEstablished() {
		// Ignore
	}

	@Override
	public void onDisconnecting() {
		// Ignore
	}

	@Override
	public void onDisconnected() {
		// Ignore
	}

	@Override
	public void onConnectionLost(Throwable cause) {
		// Ignore
	}

	@Override
	public void onMessageArrived(String topic, byte[] payload, int qos,
			boolean retained) {
		
		s_logger.debug("Message arrived on topic: '{}'", topic);
		
		if (m_respTopic != null) {
			// Filter on application ID and topic
			KuraTopic kuraTopic = new KuraTopic(topic);
			KuraTopic kuraRespTopic = new KuraTopic(m_respTopic);
			
			if (kuraTopic.getApplicationId().equals(kuraRespTopic.getApplicationId()) &&
					kuraTopic.getApplicationTopic().equals(kuraRespTopic.getApplicationTopic())) {
				
				s_logger.debug("Got response");
				
				CloudPayloadProtoBufDecoderImpl decoder = new CloudPayloadProtoBufDecoderImpl(
						payload);

				KuraResponsePayload resp = null;
				try {
					KuraPayload kuraPayload = decoder.buildFromByteArray();
					resp = new KuraResponsePayload(kuraPayload);
				} catch (KuraInvalidMessageException e) {
					s_logger.error("Cannot decode protobuf", e);
				} catch (IOException e) {
					s_logger.error("Cannot decode protobuf", e);
				}

				synchronized (m_lock) {
					m_resp = resp; // Can be null
					m_lock.notifyAll();
				}
			}
		}
	}

	@Override
	public void onMessagePublished(int messageId, String topic) {
		// Ignore
	}

	@Override
	public void onMessageConfirmed(int messageId, String topic) {
		// Ignore
	}
	
	@Override
	public boolean isConnected() {
		return m_dataService.isConnected();
	}
}
