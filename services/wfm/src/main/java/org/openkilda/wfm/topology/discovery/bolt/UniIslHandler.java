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

package org.openkilda.wfm.topology.discovery.bolt;

import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.AbstractOutputAdapter;
import org.openkilda.wfm.error.AbstractException;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.topology.discovery.model.DiscoveryFacts;
import org.openkilda.wfm.topology.discovery.model.DiscoveryOptions;
import org.openkilda.wfm.topology.discovery.model.Endpoint;
import org.openkilda.wfm.topology.discovery.model.IslCommand;
import org.openkilda.wfm.topology.discovery.model.IslDownCommand;
import org.openkilda.wfm.topology.discovery.model.IslMoveCommand;
import org.openkilda.wfm.topology.discovery.model.IslReference;
import org.openkilda.wfm.topology.discovery.model.IslUpCommand;
import org.openkilda.wfm.topology.discovery.model.UniIslCommand;
import org.openkilda.wfm.topology.discovery.service.DiscoveryUniIslService;
import org.openkilda.wfm.topology.discovery.service.IUniIslReply;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class UniIslHandler extends AbstractBolt {
    public static final String BOLT_ID = ComponentId.UNI_ISL_HANDLER.toString();

    public static final String FIELD_ID_ISL_SOURCE = "isl-source";
    public static final String FIELD_ID_ISL_DEST = "isl-dest";
    public static final String FIELD_ID_COMMAND = "command";

    public static final Fields STREAM_FIELDS = new Fields(FIELD_ID_ISL_SOURCE, FIELD_ID_ISL_DEST,
                                                           FIELD_ID_COMMAND, FIELD_ID_CONTEXT);

    private final DiscoveryOptions options;

    private transient DiscoveryUniIslService service;

    public UniIslHandler(DiscoveryOptions options) {
        this.options = options;
    }

    @Override
    protected void handleInput(Tuple input) throws AbstractException {
        String source = input.getSourceComponent();
        if (PortHandler.BOLT_ID.equals(source)) {
            handlePortCommand(input);
        } else {
            // TODO add input from discovery poll system
            unhandledInput(input);
        }
    }

    private void handlePortCommand(Tuple input) throws PipelineException {
        UniIslCommand command = pullValue(input, PortHandler.FIELD_ID_COMMAND, UniIslCommand.class);
        command.apply(service, new OutputAdapter(this, input));
    }

    @Override
    protected void init() {
        service = new DiscoveryUniIslService(options);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declare(STREAM_FIELDS);
        // TODO
    }

    private static class OutputAdapter extends AbstractOutputAdapter implements IUniIslReply {
        OutputAdapter(AbstractBolt owner, Tuple tuple) {
            super(owner, tuple);
        }

        @Override
        public void notifyIslUp(Endpoint endpoint, DiscoveryFacts discoveryFacts) {
            emit(makeDefaultTuple(new IslUpCommand(endpoint, discoveryFacts)));
        }

        @Override
        public void notifyIslDown(Endpoint endpoint, IslReference reference) {
            emit(makeDefaultTuple(new IslDownCommand(endpoint, reference)));
        }

        @Override
        public void notifyIslMove(Endpoint endpoint, IslReference reference) {
            emit(makeDefaultTuple(new IslMoveCommand(endpoint, reference)));
        }

        private Values makeDefaultTuple(IslCommand command) {
            IslReference reference = command.getReference();
            return new Values(reference.getSource(), reference.getDest(), command, getContext());
        }
    }
}
