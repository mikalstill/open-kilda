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

import org.openkilda.model.Isl;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.topology.discovery.model.DiscoveryOptions;
import org.openkilda.wfm.topology.discovery.model.SwitchHistory;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.HashMap;

@Slf4j
public class DiscoveryHistoryService extends AbstractDiscoveryService {
    private final PersistenceManager persistenceManager;

    public DiscoveryHistoryService(DiscoveryOptions options,
                                   PersistenceManager persistenceManager) {
        super(options);
        this.persistenceManager = persistenceManager;
    }

    /**
     * .
     */
    public void applyHistory(ISwitchPrepopulateReply reply) {
        for (SwitchHistory history : loadNetworkHistory()) {
            reply.switchAddWithHistory(history);
        }
    }

    // -- private --

    private Collection<SwitchHistory> loadNetworkHistory() {
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        SwitchRepository switchRepository = repositoryFactory.createSwitchRepository();

        HashMap<SwitchId, SwitchHistory> switchById = new HashMap<>();
        for (Switch switchEntry : switchRepository.findAll()) {
            SwitchId switchId = switchEntry.getSwitchId();
            switchById.put(switchId, new SwitchHistory(switchId));
        }

        IslRepository islRepository = repositoryFactory.createIslRepository();
        for (Isl islEntry : islRepository.findAll()) {
            SwitchHistory history = switchById.get(islEntry.getSrcSwitch().getSwitchId());
            if (history == null) {
                log.error("Orphaned ISL relation - {}-{} (read race condition?)",
                          islEntry.getSrcSwitch().getSwitchId(), islEntry.getSrcPort());
                continue;
            }

            history.addLink(islEntry);
        }

        return switchById.values();
    }
}
