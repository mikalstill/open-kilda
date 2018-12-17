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

package org.openkilda.wfm.topology.discovery.service;

import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.info.event.PortInfoData;
import org.openkilda.messaging.info.event.SwitchInfoData;
import org.openkilda.messaging.model.SpeakerSwitchView;
import org.openkilda.model.Isl;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.discovery.controller.IslFsm;
import org.openkilda.wfm.topology.discovery.controller.IslFsmContext;
import org.openkilda.wfm.topology.discovery.controller.IslFsmEvent;
import org.openkilda.wfm.topology.discovery.controller.IslFsmState;
import org.openkilda.wfm.topology.discovery.controller.PortFsm;
import org.openkilda.wfm.topology.discovery.controller.PortFsmContext;
import org.openkilda.wfm.topology.discovery.controller.PortFsmEvent;
import org.openkilda.wfm.topology.discovery.controller.PortFsmState;
import org.openkilda.wfm.topology.discovery.controller.SwitchFsm;
import org.openkilda.wfm.topology.discovery.controller.SwitchFsmContext;
import org.openkilda.wfm.topology.discovery.controller.SwitchFsmEvent;
import org.openkilda.wfm.topology.discovery.controller.SwitchFsmState;
import org.openkilda.wfm.topology.discovery.controller.UniIslFsm;
import org.openkilda.wfm.topology.discovery.controller.UniIslFsmContext;
import org.openkilda.wfm.topology.discovery.controller.UniIslFsmEvent;
import org.openkilda.wfm.topology.discovery.controller.UniIslFsmState;
import org.openkilda.wfm.topology.discovery.model.DiscoveryFacts;
import org.openkilda.wfm.topology.discovery.model.Endpoint;
import org.openkilda.wfm.topology.discovery.model.IslReference;
import org.openkilda.wfm.topology.discovery.model.OperationMode;
import org.openkilda.wfm.topology.discovery.model.PortFacts;
import org.openkilda.wfm.topology.discovery.model.SpeakerSharedSync;
import org.openkilda.wfm.topology.discovery.model.SwitchHistory;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
public class DiscoveryService {
    private final PersistenceManager persistenceManager;

    private final Map<SwitchId, SwitchFsm> switchController = new HashMap<>();
    private final Map<Endpoint, PortFsm> portController = new HashMap<>();
    private final Map<Endpoint, UniIslFsm> uniIslController = new HashMap<>();
    private final Map<IslReference, IslFsm> islController = new HashMap<>();

    private final FsmExecutor<SwitchFsm, SwitchFsmState, SwitchFsmEvent, SwitchFsmContext> switchControllerExecutor
            = SwitchFsm.makeExecutor();
    private final FsmExecutor<PortFsm, PortFsmState, PortFsmEvent, PortFsmContext> portControllerExecutor
            = PortFsm.makeExecutor();
    private final FsmExecutor<UniIslFsm, UniIslFsmState, UniIslFsmEvent, UniIslFsmContext> uniIslControllerExecutor
            = UniIslFsm.makeExecutor();
    private final FsmExecutor<IslFsm, IslFsmState, IslFsmEvent, IslFsmContext> islControllerExecutor
            = IslFsm.makeExecutor();

    public DiscoveryService(PersistenceManager persistenceManager) {
        this.persistenceManager = persistenceManager;
    }

    // -- NetworkPreloader --

    public void prepopulate(ISwitchPrepopulateReply reply) {
        for (SwitchHistory history : loadNetworkHistory()) {
            reply.switchAddWithHistory(history);
        }
    }

    // -- SwitchHandler --

    public void switchAddWithHistory(SwitchHistory history, ISwitchReply outputAdapter) {
        SwitchFsm switchFsm = SwitchFsm.create(history.getSwitchId());

        SwitchFsmContext fsmContext = new SwitchFsmContext(outputAdapter);
        fsmContext.setHistory(history);

        switchController.put(history.getSwitchId(), switchFsm);
        switchControllerExecutor.fire(switchFsm, SwitchFsmEvent.HISTORY, fsmContext);
    }

    public void switchRestoreManagement(SpeakerSwitchView switchView, ISwitchReply outputAdapter) {
        SwitchFsmContext fsmContext = new SwitchFsmContext(outputAdapter);
        fsmContext.setSpeakerData(switchView);

        SwitchFsm fsm = locateSwitchFsmCreateIfAbsent(switchView.getDatapath());
        switchControllerExecutor.fire(fsm, SwitchFsmEvent.ONLINE, fsmContext);
    }

    public void switchSharedSync(SpeakerSharedSync sharedSync, ISwitchReply outputAdapter) {
        // FIXME(surabujin): invalid in multi-FL environment
        switch (sharedSync.getMode()) {
            case MANAGED_MODE:
                // Still connected switches will be handled by {@link switchRestoreManagement}, disconnected switches
                // must be handled here.
                detectOfflineSwitches(sharedSync.getKnownSwitches());
                break;
            case UNMANAGED_MODE:
                setAllSwitchesUnmanaged(outputAdapter);
                break;
            default:
                throw new IllegalArgumentException(
                        String.format("Unsupported %s value %s", OperationMode.class.getName(), sharedSync.getMode()));
        }
    }

    public void switchEvent(SwitchInfoData payload, ISwitchReply outputAdapter) {
        SwitchFsmContext fsmContext = new SwitchFsmContext(outputAdapter);
        SwitchFsmEvent event = null;

        switch (payload.getState()) {
            case ACTIVATED:
                event = SwitchFsmEvent.ONLINE;
                fsmContext.setSpeakerData(payload.getSwitchView());
                break;
            case DEACTIVATED:
                event = SwitchFsmEvent.OFFLINE;
                break;

            default:
                log.info("Ignore switch event {} (no need to handle it)", payload.getSwitchId());
                break;
        }

        if (event != null) {
            SwitchFsm fsm = locateSwitchFsmCreateIfAbsent(payload.getSwitchId());
            switchControllerExecutor.fire(fsm, event, fsmContext);
        }
    }

    public void switchPortEvent(PortInfoData payload, ISwitchReply outputAdapter) {
        SwitchFsmContext fsmContext = new SwitchFsmContext(outputAdapter);
        fsmContext.setPortNumber(payload.getPortNo());
        SwitchFsmEvent event = null;
        switch (payload.getState()) {
            case ADD:
                event = SwitchFsmEvent.PORT_ADD;
                break;
            case DELETE:
                event = SwitchFsmEvent.PORT_DEL;
                break;
            case UP:
                event = SwitchFsmEvent.PORT_UP;
                break;
            case DOWN:
                event = SwitchFsmEvent.PORT_DOWN;
                break;

            case OTHER_UPDATE:
            case CACHED:
                log.error("Invalid port event {}_{} - incomplete or deprecated",
                          payload.getSwitchId(), payload.getPortNo());
                break;

            default:
                log.info("Ignore port event {}_{} (no need to handle it)", payload.getSwitchId(), payload.getPortNo());
        }

        if (event != null) {
            SwitchFsm switchFsm = locateSwitchFsm(payload.getSwitchId());
            switchControllerExecutor.fire(switchFsm, event, fsmContext);
        }
    }

    // -- PortHandler --

    public void portSetup(PortFacts portFacts, Isl history, IPortReply outputAdapter) {
        Endpoint endpoint = portFacts.getEndpoint();
        PortFsm portFsm = PortFsm.create(endpoint, history);
        portController.put(endpoint, portFsm);
    }

    public void portOnlineModeSwitch(Endpoint endpoint, boolean online, IPortReply outputAdapter) {
        PortFsm portFsm = locatePortFsm(endpoint);
        PortFsmEvent event;
        if (online) {
            event = PortFsmEvent.ONLINE;
        } else {
            event = PortFsmEvent.OFFLINE;
        }
        portControllerExecutor.fire(portFsm, event, new PortFsmContext(outputAdapter));
    }

    public void portLinkStatusSwitch(Endpoint endpoint, PortFacts.LinkStatus status, IPortReply outputAdapter) {
        PortFsm portFsm = locatePortFsm(endpoint);
        PortFsmEvent event;
        switch (status) {
            case UP:
                event = PortFsmEvent.PORT_UP;
                break;
            case DOWN:
                event = PortFsmEvent.PORT_DOWN;
                break;
            default:
                throw new IllegalArgumentException(
                        String.format("Unsupported %s value %s", PortFacts.LinkStatus.class.getName(), status));
        }
        portControllerExecutor.fire(portFsm, event, new PortFsmContext(outputAdapter));
    }

    public void portRemove(Endpoint endpoint, IPortReply outputAdapter) {
        PortFsm portFsm = portController.remove(endpoint);
        if (portFsm == null) {
            throw new IllegalStateException(String.format("Port FSM not found (%s).", endpoint));
        }

        PortFsmContext context = new PortFsmContext(outputAdapter);
        portControllerExecutor.fire(portFsm, PortFsmEvent.PORT_DOWN, context);
        portControllerExecutor.fire(portFsm, PortFsmEvent.OFFLINE, context);
    }

    // -- UniIslHandler --

    public void uniIslSetup(Endpoint endpoint, Isl history, IUniIslReply outputAdapter) {
        UniIslFsm uniIslFsm = UniIslFsm.create(endpoint, history);
        uniIslController.put(endpoint, uniIslFsm);
    }

    public void uniIslDiscovery(Endpoint endpoint, IslInfoData speakerDiscoveryEvent, IUniIslReply outputAdapter) {
        UniIslFsmContext context = new UniIslFsmContext(outputAdapter);
        context.setDiscoveryEvent(speakerDiscoveryEvent);
        uniIslControllerExecutor.fire(locateUniIslFsm(endpoint), UniIslFsmEvent.DISCOVERY, context);
    }

    public void uniIslFail(Endpoint endpoint, IUniIslReply outputAdapter) {
        UniIslFsmContext context = new UniIslFsmContext(outputAdapter);
        uniIslControllerExecutor.fire(locateUniIslFsm(endpoint), UniIslFsmEvent.FAIL, context);
    }

    public void uniIslPhysicalDown(Endpoint endpoint, IUniIslReply outputAdapter) {
        UniIslFsmContext context = new UniIslFsmContext(outputAdapter);
        uniIslControllerExecutor.fire(locateUniIslFsm(endpoint), UniIslFsmEvent.PHYSICAL_DOWN, context);
    }

    public void uniIslBfdUpDown(Endpoint endpoint, boolean isUp, IUniIslReply outputAdapter) {
        UniIslFsmContext context = new UniIslFsmContext(outputAdapter);
        UniIslFsmEvent event = isUp ? UniIslFsmEvent.BFD_UP : UniIslFsmEvent.BFD_DOWN;
        uniIslControllerExecutor.fire(locateUniIslFsm(endpoint), event, context);
    }

    // -- IslHandler --

    public void islUp(Endpoint endpoint, DiscoveryFacts discoveryFacts, IIslReply outputAdapter) {
        IslFsm islFsm = localteIslFsmCreateIfAbsent(discoveryFacts.getReference());
        IslFsmContext context = new IslFsmContext(outputAdapter, endpoint);
        context.setDiscoveryFacts(discoveryFacts);
        islControllerExecutor.fire(islFsm, IslFsmEvent.ISL_UP, context);
    }

    public void islDown(Endpoint endpoint, IslReference reference, IIslReply outputAdapter) {
        IslFsm islFsm = localteIslFsmCreateIfAbsent(reference);
        islControllerExecutor.fire(islFsm, IslFsmEvent.ISL_DOWN, new IslFsmContext(outputAdapter, endpoint));
    }

    public void islMove(Endpoint endpoint, IslReference reference, IIslReply outputAdapter) {
        IslFsm islFsm = localteIslFsmCreateIfAbsent(reference);
        islControllerExecutor.fire(islFsm, IslFsmEvent.ISL_MOVE, new IslFsmContext(outputAdapter, endpoint));
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

    private void detectOfflineSwitches(Set<SwitchId> knownSwitches) {
        Set<SwitchId> extraSwitches = new HashSet<>(switchController.keySet());
        extraSwitches.removeAll(knownSwitches);

        for (SwitchId entryId : extraSwitches) {
            switchController.remove(entryId);
        }
    }

    private void setAllSwitchesUnmanaged(ISwitchReply outputAdapter) {
        SwitchFsmContext fsmContext = new SwitchFsmContext(outputAdapter);
        for (SwitchFsm switchFsm : switchController.values()) {
            switchControllerExecutor.fire(switchFsm, SwitchFsmEvent.OFFLINE, fsmContext);
        }
    }

    private SwitchFsm locateSwitchFsm(SwitchId datapath) {
        SwitchFsm switchFsm = switchController.get(datapath);
        if (switchFsm == null) {
            throw new IllegalStateException(String.format("Switch FSM not found (%s).", datapath));
        }
        return switchFsm;
    }

    private SwitchFsm locateSwitchFsmCreateIfAbsent(SwitchId datapath) {
        return switchController.computeIfAbsent(datapath, key -> SwitchFsm.create(datapath));
    }

    private PortFsm locatePortFsm(Endpoint endpoint) {
        PortFsm portFsm = portController.get(endpoint);
        if (portFsm == null) {
            throw new IllegalStateException(String.format("Port FSM not found (%s).", endpoint));
        }
        return portFsm;
    }

    private UniIslFsm locateUniIslFsm(Endpoint endpoint) {
        UniIslFsm uniIslFsm = uniIslController.get(endpoint);
        if (uniIslFsm == null) {
            throw new IllegalStateException(String.format("Uni-ISL FSM not found (%s).", endpoint));
        }
        return uniIslFsm;
    }

    private IslFsm localteIslFsmCreateIfAbsent(IslReference reference) {
        return islController.computeIfAbsent(reference, key -> IslFsm.create(reference));
    }
}
