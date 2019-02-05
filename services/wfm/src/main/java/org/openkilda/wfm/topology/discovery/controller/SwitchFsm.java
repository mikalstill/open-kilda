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

package org.openkilda.wfm.topology.discovery.controller;

import org.openkilda.messaging.model.SpeakerSwitchPortView;
import org.openkilda.messaging.model.SpeakerSwitchView;
import org.openkilda.model.Isl;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.discovery.model.DiscoveryOptions;
import org.openkilda.wfm.topology.discovery.model.Endpoint;
import org.openkilda.wfm.topology.discovery.model.facts.PortFacts;
import org.openkilda.wfm.topology.discovery.model.facts.PortFacts.LinkStatus;
import org.openkilda.wfm.topology.discovery.model.facts.HistoryFacts;
import org.openkilda.wfm.topology.discovery.service.ISwitchReply;

import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;
import org.squirrelframework.foundation.fsm.impl.AbstractStateMachine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public final class SwitchFsm extends AbstractStateMachine<SwitchFsm, SwitchFsmState, SwitchFsmEvent, SwitchFsmContext> {
    private final SwitchId switchId;
    private final DiscoveryOptions options;

    private final Map<Integer, PortFacts> portByNumber = new HashMap<>();

    private static final StateMachineBuilder<SwitchFsm, SwitchFsmState, SwitchFsmEvent, SwitchFsmContext> builder;

    static {
        builder = StateMachineBuilderFactory.create(
                SwitchFsm.class, SwitchFsmState.class, SwitchFsmEvent.class, SwitchFsmContext.class,
                // extra parameters
                SwitchId.class, Integer.class);

        // INIT
        builder.transition()
                .from(SwitchFsmState.INIT).to(SwitchFsmState.OFFLINE).on(SwitchFsmEvent.HISTORY)
                .callMethod("applyHistory");
        builder.transition()
                .from(SwitchFsmState.INIT).to(SwitchFsmState.SETUP).on(SwitchFsmEvent.ONLINE);

        // SETUP
        builder.transition()
                .from(SwitchFsmState.SETUP).to(SwitchFsmState.ONLINE).on(SwitchFsmEvent.NEXT);
        builder.onEntry(SwitchFsmState.SETUP)
                .callMethod("setupEnter");

        // ONLINE
        builder.transition().from(SwitchFsmState.ONLINE).to(SwitchFsmState.OFFLINE).on(SwitchFsmEvent.OFFLINE);
        builder.internalTransition().within(SwitchFsmState.ONLINE).on(SwitchFsmEvent.PORT_ADD)
                .callMethod("handlePortAdd");
        builder.internalTransition().within(SwitchFsmState.ONLINE).on(SwitchFsmEvent.PORT_DEL)
                .callMethod("handlePortDel");
        builder.internalTransition().within(SwitchFsmState.ONLINE).on(SwitchFsmEvent.PORT_UP)
                .callMethod("handlePortLinkStateChange");
        builder.internalTransition().within(SwitchFsmState.ONLINE).on(SwitchFsmEvent.PORT_DOWN)
                .callMethod("handlePortLinkStateChange");
        builder.onEntry(SwitchFsmState.ONLINE)
                .callMethod("setupServiceRules");

        // OFFLINE
        builder.transition().from(SwitchFsmState.OFFLINE).to(SwitchFsmState.SETUP).on(SwitchFsmEvent.ONLINE);
        builder.onEntry(SwitchFsmState.OFFLINE)
                .callMethod("offlineEnter");
    }

    public static FsmExecutor<SwitchFsm, SwitchFsmState, SwitchFsmEvent, SwitchFsmContext> makeExecutor() {
        return new FsmExecutor<>(SwitchFsmEvent.NEXT);
    }

    public static SwitchFsm create(SwitchId switchId, DiscoveryOptions options) {
        return builder.newStateMachine(SwitchFsmState.INIT, switchId, options);
    }

    private SwitchFsm(SwitchId switchId, DiscoveryOptions options) {
        this.switchId = switchId;
        this.options = options;
    }

    // -- FSM actions --

    private void applyHistory(SwitchFsmState from, SwitchFsmState to, SwitchFsmEvent event, SwitchFsmContext context) {
        HistoryFacts historyFacts = context.getHistory();
        for (Isl outgoingLink : historyFacts.getOutgoingLinks()) {
            portAdd(context, outgoingLink);
        }
    }

    private void setupEnter(SwitchFsmState from, SwitchFsmState to, SwitchFsmEvent event, SwitchFsmContext context) {
        SpeakerSwitchView speakerData = context.getSpeakerData();

        Set<Integer> removedPorts = new HashSet<>(portByNumber.keySet());
        List<PortFacts> becomeUpPorts = new ArrayList<>();
        List<PortFacts> becomeDownPorts = new ArrayList<>();
        for (SpeakerSwitchPortView port : speakerData.getPorts()) {
            removedPorts.remove(port.getNumber());

            PortFacts actualPort = new PortFacts(switchId, port);
            PortFacts storedPort = portByNumber.get(port.getNumber());
            if (storedPort == null) {
                // port added
                portAdd(context, actualPort);
                continue;
            }

            if (storedPort.getLinkStatus() == actualPort.getLinkStatus()) {
                // ports state have not been changed
                continue;
            }

            switch (actualPort.getLinkStatus()) {
                case UP:
                    becomeUpPorts.add(actualPort);
                    break;
                case DOWN:
                    becomeDownPorts.add(actualPort);
                    break;
                default:
                    throw new IllegalArgumentException(String.format(
                            "Unsupported port admin state value %s (%s)",
                            actualPort.getLinkStatus(), actualPort.getLinkStatus().getClass().getName()));
            }
        }

        for (Integer portNumber : removedPorts) {
            PortFacts port = portByNumber.get(portNumber);
            portDel(context, port);
        }

        // emit "online = true" for all ports
        ISwitchReply output = context.getOutput();
        for (PortFacts port : portByNumber.values()) {
            output.sethOnlineMode(port.getEndpoint(), true);
        }

        for (PortFacts port : becomeDownPorts) {
            port.setLinkStatus(LinkStatus.DOWN);
            output.setPortLinkMode(port);
        }

        for (PortFacts port : becomeUpPorts) {
            port.setLinkStatus(LinkStatus.UP);
            output.setPortLinkMode(port);
        }
    }

    private void setupServiceRules(SwitchFsmState from, SwitchFsmState to, SwitchFsmEvent event,
                                   SwitchFsmContext context) {
        // FIXME(surabujin): move initial switch setup here (from FL)
    }

    private void offlineEnter(SwitchFsmState from, SwitchFsmState to, SwitchFsmEvent event, SwitchFsmContext context) {
        ISwitchReply output = context.getOutput();
        for (PortFacts port : portByNumber.values()) {
            output.sethOnlineMode(port.getEndpoint(), false);
        }
    }

    private void handlePortAdd(SwitchFsmState from, SwitchFsmState to, SwitchFsmEvent event, SwitchFsmContext context) {
        PortFacts port = new PortFacts(new Endpoint(switchId, context.getPortNumber()));
        portAdd(context, port);
    }

    private void handlePortDel(SwitchFsmState from, SwitchFsmState to, SwitchFsmEvent event, SwitchFsmContext context) {
        PortFacts port = new PortFacts(new Endpoint(switchId, context.getPortNumber()));
        portDel(context, port);
    }

    private void handlePortLinkStateChange(SwitchFsmState from, SwitchFsmState to, SwitchFsmEvent event,
                                           SwitchFsmContext context) {
        PortFacts port = portByNumber.get(context.getPortNumber());
        if (port == null) {
            log.error("Port {} is not listed into {}", context.getPortNumber(), switchId);
            return;
        }

        switch (event) {
            case PORT_UP:
                port.setLinkStatus(LinkStatus.UP);
                break;
            case PORT_DOWN:
                port.setLinkStatus(LinkStatus.DOWN);
                break;
            default:
                throw new IllegalArgumentException(String.format("Unexpected event %s received in state %s (%s)",
                                                                 event, to, getClass().getName()));
        }


        context.getOutput().setPortLinkMode(port);
    }

    // -- private/service methods --

    private void portAdd(SwitchFsmContext context, PortFacts portFacts) {
        portByNumber.put(portFacts.getPortNumber(), portFacts);
        context.getOutput().setupPortHandler(portFacts);
    }

    private void portAdd(SwitchFsmContext context, Isl history) {
        Endpoint endpoint = new Endpoint(switchId, history.getSrcPort());
        PortFacts portFacts = new PortFacts(endpoint);

        portByNumber.put(portFacts.getPortNumber(), portFacts);
        context.getOutput().setupPortHandler(portFacts, history);
    }

    private void portDel(SwitchFsmContext context, PortFacts portFacts) {
        portByNumber.remove(portFacts.getPortNumber());
        context.getOutput().removePortHandler(portFacts);
    }
}
