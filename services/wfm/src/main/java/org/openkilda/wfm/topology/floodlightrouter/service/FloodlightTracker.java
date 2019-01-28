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

import org.openkilda.model.SwitchId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FloodlightTracker {
    private Map<SwitchId, String> switchRegionMap = new HashMap<>();
    private Map<String, FloodlightInstance> floodlightStatus = new HashMap<>();
    private long aliveTimeout;

    public FloodlightTracker(Set<String> floodlights, long aliveTimeout) {
        this.aliveTimeout = aliveTimeout;
        for (String region : floodlights) {
            FloodlightInstance fl = new FloodlightInstance(region);

            floodlightStatus.put(region, fl);
        }
    }


    public void updateSwitchRegion(SwitchId switchId, String region) {
        switchRegionMap.put(switchId, region);
    }

    public String lookupRegion(SwitchId switchId) {
        return switchRegionMap.get(switchId);
    }

    /** Return active avaible regions.
     *
     * @return set of active regions
     */
    public Set<String> getActiveRegions() {
        Set<String> activeRegions = new HashSet<>();
        for (Map.Entry<String, FloodlightInstance> entry: floodlightStatus.entrySet()) {
            if (entry.getValue().isAlive()) {
                activeRegions.add(entry.getKey());
            }
        }
        return activeRegions;
    }

    /** Handles alive response.
     *
     * @return flag whether discovery needed or not
     */
    public boolean handleAliveResponse(String region, long timestamp) {
        FloodlightInstance instance = floodlightStatus.get(region);
        instance.setLastAliveResponse(timestamp);
        boolean needDiscovery = false;
        if (timestamp + aliveTimeout * 1000 > System.currentTimeMillis()) {
            if (!instance.isAlive()) {
                needDiscovery = true;
            }
            instance.setAlive(true);
        } else {
            instance.setAlive(false);
        }
        return needDiscovery;
    }
}
