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

package org.openkilda.wfm.topology.discovery.service.discovery;

import org.openkilda.model.SwitchId;

import lombok.Value;

import java.util.HashSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

public class WatcherService {
    private final IWatcherServiceCarrier carrier;
    private final long awaitTime;
    private long packetNo = 0;
    private Set<Packet> confirmations = new HashSet<>();
    private SortedMap<Long, Set<Packet>> timeouts = new TreeMap<>();

    public WatcherService(IWatcherServiceCarrier carrier, long awaitTime) {
        this.carrier = carrier;
        this.awaitTime = awaitTime;
    }

    public void addWatch(SwitchId switchId, int portNo, long currentTime) {
        Packet packet = Packet.of(switchId, portNo, packetNo);
        timeouts.computeIfAbsent(currentTime + awaitTime, mappingFunction -> new HashSet<>())
                .add(packet);
        carrier.sendDiscovery(switchId, portNo, packetNo, currentTime);
        packetNo += 1;
    }


    public void removeWatch(SwitchId switchId, int portNo) {

    }

    public void tick(long tickTime) {
        // TODO: move to stream processing
        SortedMap<Long, Set<Packet>> range = timeouts.subMap(0L, tickTime + 1);
        if (!range.isEmpty()) {
            for (Set<Packet> e : range.values()) {
                for (Packet ee : e) {
                    if (confirmations.remove(ee)) {
                        carrier.failed(ee.switchId, ee.port, tickTime);
                    }
                }
            }
            range.clear();
        }
    }

    public void confirmation(SwitchId switchId, int portNo, long packetNo) {
        Packet packet = Packet.of(switchId, portNo, packetNo);
        confirmations.add(packet);
    }

    public void discovery(SwitchId switchId, int portNo, SwitchId endSwitchId, int endPortNo, long packetNo, long currentTime) {
        Packet packet = Packet.of(switchId, portNo, packetNo);
        confirmations.remove(packet);
        carrier.discovered(switchId, portNo, endSwitchId, endPortNo, currentTime);
    }

    public Set<Packet> getConfirmations() {
        return confirmations;
    }

    public SortedMap<Long, Set<Packet>> getTimeouts() {
        return timeouts;
    }

    public static @Value(staticConstructor = "of")
    class Packet {
        private final SwitchId switchId;
        private final int port;
        private final long packetNo;
    }
}
