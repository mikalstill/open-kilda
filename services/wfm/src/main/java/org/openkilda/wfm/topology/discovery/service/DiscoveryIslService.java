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

import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.discovery.controller.IslFsm;
import org.openkilda.wfm.topology.discovery.controller.IslFsmContext;
import org.openkilda.wfm.topology.discovery.controller.IslFsmEvent;
import org.openkilda.wfm.topology.discovery.controller.IslFsmState;
import org.openkilda.wfm.topology.discovery.model.DiscoveryFacts;
import org.openkilda.wfm.topology.discovery.model.DiscoveryOptions;
import org.openkilda.wfm.topology.discovery.model.Endpoint;
import org.openkilda.wfm.topology.discovery.model.IslReference;

import java.util.HashMap;
import java.util.Map;

public class DiscoveryIslService extends AbstractDiscoveryService {
    private final Map<IslReference, IslFsm> controller = new HashMap<>();

    private final FsmExecutor<IslFsm, IslFsmState, IslFsmEvent, IslFsmContext> controllerExecutor
            = IslFsm.makeExecutor();

    // FIXME(surabujin): start usage
    private final PersistenceManager persistenceManager;

    public DiscoveryIslService(DiscoveryOptions options, PersistenceManager persistenceManager) {
        super(options);
        this.persistenceManager = persistenceManager;
    }

    /**
     * .
     */
    public void islUp(Endpoint endpoint, DiscoveryFacts discoveryFacts, IIslReply outputAdapter) {
        IslFsm islFsm = locateControllerCreateIfAbsent(discoveryFacts.getReference());
        IslFsmContext context = new IslFsmContext(outputAdapter, endpoint);
        context.setDiscoveryFacts(discoveryFacts);
        controllerExecutor.fire(islFsm, IslFsmEvent.ISL_UP, context);
    }

    public void islDown(Endpoint endpoint, IslReference reference, IIslReply outputAdapter) {
        IslFsm islFsm = locateControllerCreateIfAbsent(reference);
        controllerExecutor.fire(islFsm, IslFsmEvent.ISL_DOWN, new IslFsmContext(outputAdapter, endpoint));
    }

    public void islMove(Endpoint endpoint, IslReference reference, IIslReply outputAdapter) {
        IslFsm islFsm = locateControllerCreateIfAbsent(reference);
        controllerExecutor.fire(islFsm, IslFsmEvent.ISL_MOVE, new IslFsmContext(outputAdapter, endpoint));
    }

    // -- private --

    private IslFsm locateControllerCreateIfAbsent(IslReference reference) {
        return controller.computeIfAbsent(reference, key -> IslFsm.create(reference));
    }
}
