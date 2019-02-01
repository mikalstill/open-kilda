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
import org.openkilda.wfm.topology.discovery.model.DiscoveryFacts;
import org.openkilda.wfm.topology.discovery.model.Endpoint;
import org.openkilda.wfm.topology.discovery.model.IslReference;

import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;
import org.squirrelframework.foundation.fsm.impl.AbstractStateMachine;

public final class IslFsm extends AbstractStateMachine<IslFsm, IslFsmState, IslFsmEvent, IslFsmContext> {
    private boolean sourceUp = false;
    private boolean destUp = false;

    private final DiscoveryFacts discoveryFacts;

    private static final StateMachineBuilder<IslFsm, IslFsmState, IslFsmEvent, IslFsmContext> builder;

    static {
        builder = StateMachineBuilderFactory.create(
                IslFsm.class, IslFsmState.class, IslFsmEvent.class, IslFsmContext.class,
                // extra parameters
                IslReference.class);

        // DOWN
        builder.transition()
                .from(IslFsmState.DOWN).to(IslFsmState.UP_ATTEMPT).on(IslFsmEvent.ISL_UP)
                .callMethod("handleSourceDestUpState");
        builder.transition()
                .from(IslFsmState.DOWN).to(IslFsmState.MOVED).on(IslFsmEvent.ISL_MOVE);
        builder.internalTransition().within(IslFsmState.DOWN).on(IslFsmEvent.ISL_DOWN)
                .callMethod("handleSourceDestUpState");
        builder.onEntry(IslFsmState.DOWN)
                .callMethod("downEnter");

        // UP_ATTEMPT
        builder.transition()
                .from(IslFsmState.UP_ATTEMPT).to(IslFsmState.DOWN).on(IslFsmEvent._UP_ATTEMPT_FAIL);
        builder.transition()
                .from(IslFsmState.UP_ATTEMPT).to(IslFsmState.UP).on(IslFsmEvent._UP_ATTEMPT_SUCCESS);
        builder.onEntry(IslFsmState.UP_ATTEMPT)
                .callMethod("handleUpAttempt");

        // UP
        builder.transition()
                .from(IslFsmState.UP).to(IslFsmState.DOWN).on(IslFsmEvent.ISL_DOWN)
                .callMethod("handleSourceDestUpState");
        builder.transition()
                .from(IslFsmState.UP).to(IslFsmState.MOVED).on(IslFsmEvent.ISL_MOVE);
        builder.onEntry(IslFsmState.UP)
                .callMethod("upEnter");

        // MOVED
        builder.transition()
                .from(IslFsmState.MOVED).to(IslFsmState.UP_ATTEMPT).on(IslFsmEvent.ISL_UP)
                .callMethod("handleSourceDestUpState");
        builder.internalTransition()
                .within(IslFsmState.MOVED).on(IslFsmEvent.ISL_DOWN)
                .callMethod("handleSourceDestUpState");
        builder.onEntry(IslFsmState.MOVED)
                .callMethod("movedEnter");
    }

    public static FsmExecutor<IslFsm, IslFsmState, IslFsmEvent, IslFsmContext> makeExecutor() {
        return new FsmExecutor<>(IslFsmEvent.NEXT);
    }

    public static IslFsm create(IslReference reference) {
        return builder.newStateMachine(IslFsmState.DOWN, reference);
    }

    private IslFsm(IslReference reference) {
        this.discoveryFacts = new DiscoveryFacts(reference);
    }

    // -- FSM actions --
    private void handleSourceDestUpState(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        boolean status;
        switch (event) {
            case ISL_UP:
                status = true;
                break;
            case ISL_DOWN:
                status = false;
                break;
            default:
                throw new IllegalStateException(String.format("Unexpeted event %s for %s.handleSourceDestUpState",
                                                              event, getClass().getName()));
        }
        updateSourceDestUpStatus(context.getEndpoint(), status);
    }

    private void downEnter(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        updatePersistedStatus(context);
    }

    private void handleUpAttempt(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        IslFsmEvent route;
        if (sourceUp && destUp) {
            route = IslFsmEvent._UP_ATTEMPT_SUCCESS;
        } else {
            route = IslFsmEvent._UP_ATTEMPT_FAIL;
        }
        fire(route, context);
    }

    private void upEnter(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        updatePersistedStatus(context);

        IslReference reference = discoveryFacts.getReference();
        context.getOutput().notifyBiIslUp(reference.getSource(), reference);
        context.getOutput().notifyBiIslUp(reference.getDest(), reference);
    }

    private void movedEnter(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        updatePersistedStatus(context);
        // emit isl-move
        updateSourceDestUpStatus(context.getEndpoint(), false);
    }

    // -- private/service methods --

    private void updateSourceDestUpStatus(Endpoint endpoint, boolean status) {
        IslReference reference = discoveryFacts.getReference();
        if (reference.getSource().equals(endpoint)) {
            sourceUp = status;
        } else if (reference.getDest().equals(endpoint)) {
            destUp = status;
        } else {
            throw new IllegalArgumentException(String.format("Endpoint %s is not part of ISL %s", endpoint, reference));
        }
    }

    private void updatePersistedStatus(IslFsmContext context) {
        // TODO
    }
}
