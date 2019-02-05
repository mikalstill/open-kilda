/* Copyright 2019 Telstra Open Source
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

package org.openkilda.wfm.topology.discovery.service;

import org.openkilda.wfm.topology.discovery.model.DiscoveryOptions;
import org.openkilda.wfm.topology.discovery.model.Endpoint;

import java.util.HashSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

public class DiscoveryWatchListService extends AbstractDiscoveryService {
    private Set<Endpoint> endpoints = new HashSet<>();
    private SortedMap<Long, Set<Endpoint>> timeouts = new TreeMap<>();

    private long lastTickTimeMs = 0;

    public DiscoveryWatchListService(DiscoveryOptions options) {
        super(options);
    }

    public Set<Endpoint> getEndpoints() {
        return endpoints;
    }

    public SortedMap<Long, Set<Endpoint>> getTimeouts() {
        return timeouts;
    }

    public void addWatch(IWatchListServiceCarrier carrier, Endpoint endpoint, long currentTime) {
        if (endpoints.add(endpoint)) {
            carrier.discoveryRequest(endpoint, currentTime);
            long key = currentTime + options.getDiscoveryIntervalMs();
            timeouts.computeIfAbsent(key, mappingFunction -> new HashSet<>())
                    .add(endpoint);
        }
    }

    public void addWatch(IWatchListServiceCarrier carrier, Endpoint endpoint) {
        addWatch(carrier, endpoint, lastTickTimeMs);
    }

    public void removeWatch(IWatchListServiceCarrier carrier, Endpoint endpoint) {
        carrier.watchRemoved(endpoint);
        endpoints.remove(endpoint);
    }

    public void tick(IWatchListServiceCarrier carrier, long tickTime) {
        lastTickTimeMs = tickTime;

        SortedMap<Long, Set<Endpoint>> range = timeouts.subMap(0L, tickTime + 1);
        if (!range.isEmpty()) {
            HashSet<Endpoint> renew = new HashSet<>();
            for (Set<Endpoint> e : range.values()) {
                for (Endpoint ee : e) {
                    if (endpoints.contains(ee)) {
                        carrier.discoveryRequest(ee, tickTime);
                        renew.add(ee);
                    }
                }
            }
            range.clear();
            if (!renew.isEmpty()) {
                long key = tickTime + options.getDiscoveryIntervalMs();
                timeouts.computeIfAbsent(key, mappingFunction -> new HashSet<>())
                        .addAll(renew);
            }
        }
    }
}
