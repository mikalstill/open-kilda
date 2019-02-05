/*
 * Copyright 2019 Telstra Open Source
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

package org.openkilda.wfm.topology.discovery.service.discovery;

import lombok.Value;

import java.util.HashMap;

public class DecisionMakerService {

    private final IDecisionMakerCarrier carrier;
    private final int failTimeout;
    private final int awaitTime;
    private HashMap<Endpoint, Long> lastDiscovery = new HashMap<>();

    public DecisionMakerService(IDecisionMakerCarrier carrier, int failTimeout, int awaitTime) {
        this.carrier = carrier;
        this.failTimeout = failTimeout;
        this.awaitTime = awaitTime;
    }

    void discovered(SwitchId switchId, int portNo, SwitchId endSwitchId, int endPortNo, long currentTime) {
        carrier.discovered(switchId, portNo, endSwitchId, endPortNo, currentTime);
        lastDiscovery.put(Endpoint.of(switchId, portNo), currentTime);
    }

    void failed(SwitchId switchId, int portNo, long currentTime) {
        Endpoint endpoint = Endpoint.of(switchId, portNo);
        if (!lastDiscovery.containsKey(endpoint)) {
            lastDiscovery.put(endpoint, currentTime - awaitTime);
        }

        long timeWindow = lastDiscovery.get(endpoint) + failTimeout;

        if (currentTime >= timeWindow) {
            carrier.failed(switchId, portNo, currentTime);
        }
    }

    public HashMap<Endpoint, Long> getLastDiscovery() {
        return lastDiscovery;
    }

    public static @Value(staticConstructor = "of")
    class Endpoint {
        private final SwitchId switchId;
        private final int port;
    }
}
