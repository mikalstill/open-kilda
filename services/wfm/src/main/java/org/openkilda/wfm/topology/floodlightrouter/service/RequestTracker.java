/* Copyright 2018 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.wfm.topology.floodlightrouter.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RequestTracker {
    private static final long TIMEOUT_INTERVAL = 30L;
    private ConcurrentMap<String, TrackedMessage> trackedRequests = new ConcurrentHashMap<>();
    private ConcurrentMap<String, TrackedMessage> blackList = new ConcurrentHashMap<>();
    private long requestTimeout;
    private long blacklistTimeout;

    public RequestTracker(long requestTimeout, long blacklistTimeout) {
        this.requestTimeout = requestTimeout;
        this.blacklistTimeout = blacklistTimeout;
    }

    /**
     * Track new message for timeout handling.
     */
    public void trackMessage(String correlationId, boolean expectedChunked) {
        long now = System.currentTimeMillis();
        TrackedMessage message = new TrackedMessage(correlationId, expectedChunked, now, now);
        trackedRequests.put(correlationId, message);
    }

    /**
     * Cleans up expired tracked messages.
     * @return collection of messages to be timeouted.
     */
    public Collection<TrackedMessage> cleanupOldMessages() {
        Map<String, TrackedMessage> toBlackList = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, TrackedMessage> entry: trackedRequests.entrySet()) {
            if (entry.getValue().isExpired(now, requestTimeout * 1000)) {
                toBlackList.put(entry.getKey(), entry.getValue());
            }
        }
        for (String correlationId: toBlackList.keySet()) {
            trackedRequests.remove(correlationId);
        }
        blackList.putAll(toBlackList);
        return toBlackList.values();
    }

    /**
     * Check new message for blacklist.
     * @return whether is message ok or not.
     */
    public boolean checkReplyMessage(String correlationId, boolean last) {
        long now = System.currentTimeMillis();
        if (blackList.containsKey(correlationId)) {
            return false;
        }
        TrackedMessage message = trackedRequests.get(correlationId);
        if (message == null) {
            return false;
        }

        boolean expired = message.isExpired(now, TIMEOUT_INTERVAL * 1000);

        if (expired) {
            toBlackList(message);
            return false;
        }
        if (last) {
            trackedRequests.remove(correlationId);
            return true;
        }
        if (message.isChunked()) {
            message.updateLastReplyTime(now);
        }
        return true;
    }

    private void toBlackList(TrackedMessage message) {
        blackList.put(message.getCorrelationId(), message);
        trackedRequests.remove(message.getCorrelationId());
    }
}
