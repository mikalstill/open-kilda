/*
 * Copyright 2019 Telstra Open Source
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

package org.openkilda.wfm.topology.discovery.storm.bolt.isl;

import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.AbstractOutputAdapter;
import org.openkilda.wfm.error.AbstractException;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.topology.discovery.storm.bolt.uniisl.UniIslHandler;
import org.openkilda.wfm.topology.discovery.storm.bolt.bfdport.command.BfdPortBiIslUpCommand;
import org.openkilda.wfm.topology.discovery.storm.bolt.bfdport.command.BfdPortCommand;
import org.openkilda.wfm.topology.discovery.model.DiscoveryOptions;
import org.openkilda.wfm.topology.discovery.model.Endpoint;
import org.openkilda.wfm.topology.discovery.storm.bolt.isl.command.IslCommand;
import org.openkilda.wfm.topology.discovery.storm.bolt.isl.command.IslReference;
import org.openkilda.wfm.topology.discovery.service.DiscoveryIslService;
import org.openkilda.wfm.topology.discovery.service.IIslReply;
import org.openkilda.wfm.topology.discovery.storm.ComponentId;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class IslHandler extends AbstractBolt {
    public static final String BOLT_ID = ComponentId.ISL_HANDLER.toString();

    public static final String FIELD_ID_DATAPATH = "datapath";
    public static final String FIELD_ID_COMMAND = UniIslHandler.FIELD_ID_COMMAND;

    public static final String STREAM_BFD_PORT_ID = "bfd-port";
    public static final Fields STREAM_BFD_PORT_FIELDS = new Fields(FIELD_ID_DATAPATH,
                                                                   FIELD_ID_COMMAND, FIELD_ID_CONTEXT);

    private final DiscoveryOptions options;
    private final PersistenceManager persistenceManager;

    private transient DiscoveryIslService service;

    public IslHandler(DiscoveryOptions options, PersistenceManager persistenceManager) {
        this.options = options;
        this.persistenceManager = persistenceManager;
    }

    @Override
    protected void handleInput(Tuple input) throws AbstractException {
        String source = input.getSourceComponent();
        if (UniIslHandler.BOLT_ID.equals(source)) {
            handleUniIslCommand(input);
        } else {
            unhandledInput(input);
        }
    }

    private void handleUniIslCommand(Tuple input) throws PipelineException {
        IslCommand command = pullValue(input, UniIslHandler.FIELD_ID_COMMAND, IslCommand.class);
        command.apply(service, new OutputAdapter(this, input));
    }

    @Override
    protected void init() {
        service = new DiscoveryIslService(options, persistenceManager);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declareStream(STREAM_BFD_PORT_ID, STREAM_BFD_PORT_FIELDS);
    }

    private static class OutputAdapter extends AbstractOutputAdapter implements IIslReply {
        public OutputAdapter(AbstractBolt owner, Tuple tuple) {
            super(owner, tuple);
        }

        @Override
        public void notifyBiIslUp(Endpoint endpoint, IslReference reference) {
            emit(STREAM_BFD_PORT_ID, makeBfdPortTuple(new BfdPortBiIslUpCommand(endpoint, reference)));
        }

        private Values makeBfdPortTuple(BfdPortCommand command) {
            Endpoint endpoint = command.getEndpoint();
            return new Values(endpoint.getDatapath(), command, getContext());
        }
    }
}
