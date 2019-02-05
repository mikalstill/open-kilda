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

package org.openkilda.wfm.topology.discovery.controller;

import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.discovery.model.Endpoint;
import org.openkilda.wfm.topology.discovery.model.facts.PortFacts;

import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;
import org.squirrelframework.foundation.fsm.impl.AbstractStateMachine;

public final class BfdPortFsm extends
        AbstractStateMachine<BfdPortFsm, BfdPortFsmState, BfdPortFsmEvent, BfdPortFsmContext> {
    private final Endpoint physicalEndpoint;
    private final PortFacts logicalPort;
    private Integer discriminator = null;
    private boolean upStatus = false;

    private static final StateMachineBuilder<BfdPortFsm, BfdPortFsmState, BfdPortFsmEvent, BfdPortFsmContext> builder;

    static {
        builder = StateMachineBuilderFactory.create(
                BfdPortFsm.class, BfdPortFsmState.class, BfdPortFsmEvent.class, BfdPortFsmContext.class,
                // extra parameters
                Endpoint.class, Endpoint.class);

        // INIT
        builder.transition()
                .from(BfdPortFsmState.INIT).to(BfdPortFsmState.INIT_CHOICE).on(BfdPortFsmEvent.HISTORY)
                .callMethod("consumeHistory");

        // INIT_CHOICE
        builder.transition()
                .from(BfdPortFsmState.INIT_CHOICE).to(BfdPortFsmState.IDLE).on(BfdPortFsmEvent._INIT_CHOICE_CLEAN);
        builder.transition()
                .from(BfdPortFsmState.INIT_CHOICE).to(BfdPortFsmState.CLEANING).on(BfdPortFsmEvent._INIT_CHOICE_DIRTY);
        builder.onEntry(BfdPortFsmState.INIT_CHOICE)
                .callMethod("handleInitChoice");

        // IDLE
        builder.transition()
                .from(BfdPortFsmState.INIT).to(BfdPortFsmState.INSTALLING).on(BfdPortFsmEvent.BI_ISL_UP);
        builder.transition()
                .from(BfdPortFsmState.INIT).to(BfdPortFsmState.FAIL).on(BfdPortFsmEvent.PORT_UP);

        // INSTALLING
        builder.transition()
                .from(BfdPortFsmState.INSTALLING).to(BfdPortFsmState.UP).on(BfdPortFsmEvent.PORT_UP);
        builder.transition()
                .from(BfdPortFsmState.INSTALLING).to(BfdPortFsmState.CLEANING).on(BfdPortFsmEvent.SPEAKER_FAIL);
        builder.transition()
                .from(BfdPortFsmState.INSTALLING).to(BfdPortFsmState.CLEANING).on(BfdPortFsmEvent.SPEAKER_TIMEOUT);
        builder.onEntry(BfdPortFsmState.INSTALLING)
                .callMethod("installingEnter");

        // CLEANING
        builder.transition()
                .from(BfdPortFsmState.CLEANING).to(BfdPortFsmState.CLEANING_CHOICE).on(BfdPortFsmEvent.SPEAKER_SUCCESS)
                .callMethod("releaseResources");
        builder.transition()
                .from(BfdPortFsmState.CLEANING).to(BfdPortFsmState.FAIL).on(BfdPortFsmEvent.SPEAKER_FAIL);
        builder.transition()
                .from(BfdPortFsmState.CLEANING).to(BfdPortFsmState.FAIL).on(BfdPortFsmEvent.SPEAKER_TIMEOUT);
        builder.internalTransition().within(BfdPortFsmState.CLEANING).on(BfdPortFsmEvent.PORT_UP)
                .callMethod("cleaningUpdateUpStatus");
        builder.internalTransition().within(BfdPortFsmState.CLEANING).on(BfdPortFsmEvent.PORT_DOWN)
                .callMethod("cleaningUpdateUpStatus");
        builder.onEntry(BfdPortFsmState.CLEANING)
                .callMethod("handleCleaning");

        // CLEANING_CHOICE
        builder.transition()
                .from(BfdPortFsmState.CLEANING_CHOICE).to(BfdPortFsmState.IDLE)
                .on(BfdPortFsmEvent._CLEANING_CHOICE_READY);
        builder.transition()
                .from(BfdPortFsmState.CLEANING_CHOICE).to(BfdPortFsmState.WAIT_RELEASE)
                .on(BfdPortFsmEvent._CLEANING_CHOICE_HOLD);
        builder.onEntry(BfdPortFsmState.CLEANING_CHOICE)
                .callMethod("handleCleaningChoice");

        // WAIT_RELEASE
        builder.transition()
                .from(BfdPortFsmState.WAIT_RELEASE).to(BfdPortFsmState.IDLE).on(BfdPortFsmEvent.PORT_DOWN);
        builder.onExit(BfdPortFsmState.WAIT_RELEASE)
                .callMethod("waitReleaseExit");

        // UP
        builder.transition()
                .from(BfdPortFsmState.UP).to(BfdPortFsmState.DOWN).on(BfdPortFsmEvent.PORT_DOWN);
        builder.transition()
                .from(BfdPortFsmState.UP).to(BfdPortFsmState.CLEANING).on(BfdPortFsmEvent.BI_ISL_MOVE);
        builder.onEntry(BfdPortFsmState.UP)
                .callMethod("upEnter");

        // DOWN
        builder.transition()
                .from(BfdPortFsmState.DOWN).to(BfdPortFsmState.UP).on(BfdPortFsmEvent.PORT_UP);
        builder.transition()
                .from(BfdPortFsmState.DOWN).to(BfdPortFsmState.CLEANING).on(BfdPortFsmEvent.BI_ISL_MOVE);
        builder.onEntry(BfdPortFsmState.DOWN)
                .callMethod("downEnter");

        // FAIL
        builder.transition()
                .from(BfdPortFsmState.FAIL).to(BfdPortFsmState.IDLE).on(BfdPortFsmEvent.PORT_DOWN);
    }

    public static FsmExecutor<BfdPortFsm, BfdPortFsmState, BfdPortFsmEvent, BfdPortFsmContext> makeExecutor() {
        return new FsmExecutor<>(BfdPortFsmEvent.NEXT);
    }

    public static BfdPortFsm create(Endpoint physicalEndpoint, PortFacts logicalPort) {
        return builder.newStateMachine(BfdPortFsmState.INIT, physicalEndpoint, logicalPort);
    }

    private BfdPortFsm(Endpoint physicalEndpoint, PortFacts logicalPort) {
        this.physicalEndpoint = physicalEndpoint;
        this.logicalPort = logicalPort;
    }

    // -- FSM actions --

    private void consumeHistory(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                BfdPortFsmContext context) {
        this.discriminator = context.getDiscriminator();
    }

    private void handleInitChoice(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                  BfdPortFsmContext context) {
        if (discriminator == null) {
            fire(BfdPortFsmEvent._INIT_CHOICE_CLEAN, context);
        } else {
            fire(BfdPortFsmEvent._INIT_CHOICE_DIRTY, context);
        }
    }

    private void installingEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                 BfdPortFsmContext context) {
        // TODO allocate BFD-discriminator
        // TODO emit FL-BFD-producer setup
    }

    private void releaseResources(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                  BfdPortFsmContext context) {
        // TODO release BFD-discriminator
    }

    private void handleCleaning(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                BfdPortFsmContext context) {
        // TODO emit FL-BFD-producer remove
    }

    private void cleaningUpdateUpStatus(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                        BfdPortFsmContext context) {
        switch (event) {
            case PORT_UP:
                upStatus = true;
                break;
            case PORT_DOWN:
                upStatus = false;
                break;
            default:
                throw new IllegalStateException(String.format("Unable to handle event %s into %s",
                                                              event, getCurrentState()));
        }
    }

    private void handleCleaningChoice(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event,
                                      BfdPortFsmContext context) {
        if (upStatus) {
            fire(BfdPortFsmEvent._CLEANING_CHOICE_HOLD, context);
        } else {
            fire(BfdPortFsmEvent._CLEANING_CHOICE_READY, context);
        }
    }

    private void waitReleaseExit(BfdPortFsmState from,  BfdPortFsmState to, BfdPortFsmEvent event,
                                 BfdPortFsmContext context) {
        upStatus = false;
    }

    private void upEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event, BfdPortFsmContext context) {
        // TODO emit bfd-up
        upStatus = true;
    }

    private void downEnter(BfdPortFsmState from, BfdPortFsmState to, BfdPortFsmEvent event, BfdPortFsmContext context) {
        // TODO emit bfd-down
        upStatus = false;
    }

    // -- private/service methods --
}
