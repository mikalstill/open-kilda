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

package org.openkilda.wfm.topology.discovery.bolt;

import org.openkilda.messaging.model.SpeakerSwitchView;
import org.openkilda.model.Isl;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.AbstractOutputAdapter;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.topology.discovery.model.Endpoint;
import org.openkilda.wfm.topology.discovery.model.PortCommand;
import org.openkilda.wfm.topology.discovery.model.PortFacts;
import org.openkilda.wfm.topology.discovery.model.PortLinkStatusCommand;
import org.openkilda.wfm.topology.discovery.model.PortOnlineModeCommand;
import org.openkilda.wfm.topology.discovery.model.PortRemoveCommand;
import org.openkilda.wfm.topology.discovery.model.PortSetupCommand;
import org.openkilda.wfm.topology.discovery.model.SpeakerSharedSync;
import org.openkilda.wfm.topology.discovery.model.SwitchCommand;
import org.openkilda.wfm.topology.discovery.service.DiscoveryServiceFactory;
import org.openkilda.wfm.topology.discovery.service.ISwitchReply;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.apache.storm.utils.Utils;

public class SwitchHandler extends DiscoveryAbstractBolt {
    public static final String BOLT_ID = ComponentId.SWITCH_HANDLER.toString();

    public static final String FIELD_ID_DATAPATH = SpeakerMonitor.FIELD_ID_DATAPATH;
    public static final String FIELD_ID_PORT_NUMBER = "port-number";
    public static final String FIELD_ID_COMMAND = "command";

    public static final String STREAM_PORT_ID = "port";
    public static final Fields STREAM_PORT_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_PORT_NUMBER,
                                                               FIELD_ID_COMMAND, FIELD_ID_CONTEXT);

    public static final String STREAM_BFD_PORT_ID = "bfd-port";
    public static final Fields STREAM_BFD_PORT_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_COMMAND,
                                                                   FIELD_ID_CONTEXT);

    public SwitchHandler(DiscoveryServiceFactory serviceFactory) {
        super(serviceFactory);
    }

    @Override
    protected void handleInput(Tuple input) throws PipelineException {
        String source = input.getSourceComponent();

        if (SpeakerMonitor.BOLT_ID.equals(source)) {
            handleSpeakerInput(input);
        } else if (NetworkPreloader.SPOUT_ID.equals(source)) {
            handlePreloaderInput(input);
        } else {
            unhandledInput(input);
        }
    }

    private void handleSpeakerInput(Tuple input) throws PipelineException {
        String stream = input.getSourceStreamId();

        if (Utils.DEFAULT_STREAM_ID.equals(stream)) {
            handleSpeakerMainStream(input);
        } else if (SpeakerMonitor.STREAM_REFRESH_ID.equals(stream)) {
            handleSpeakerRefreshStream(input);
        } else if (SpeakerMonitor.STREAM_SYNC_ID.equals(stream)) {
            handleSpeakerSyncStream(input);
        } else {
            unhandledInput(input);
        }
    }

    private void handlePreloaderInput(Tuple input) throws PipelineException {
        SwitchCommand command = pullValue(input, NetworkPreloader.FIELD_ID_PAYLOAD, SwitchCommand.class);
        command.apply(discoveryService, new OutputAdapter(this, input));
    }

    private void handleSpeakerMainStream(Tuple input) throws PipelineException {
        SwitchCommand command = pullValue(input, SpeakerMonitor.FIELD_ID_INPUT, SwitchCommand.class);
        command.apply(discoveryService, new OutputAdapter(this, input));
    }

    private void handleSpeakerRefreshStream(Tuple input) throws PipelineException {
        SpeakerSwitchView switchView = pullValue(input, SpeakerMonitor.FIELD_ID_REFRESH, SpeakerSwitchView.class);
        discoveryService.switchRestoreManagement(switchView, new OutputAdapter(this, input));
    }

    private void handleSpeakerSyncStream(Tuple input) throws PipelineException {
        SpeakerSharedSync sharedSync = pullValue(input, SpeakerMonitor.FIELD_ID_SYNC, SpeakerSharedSync.class);
        discoveryService.switchSharedSync(sharedSync, new OutputAdapter(this, input));
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declareStream(STREAM_PORT_ID, STREAM_PORT_FIELDS);
        streamManager.declareStream(STREAM_BFD_PORT_ID, STREAM_BFD_PORT_FIELDS);
    }

    private static class OutputAdapter extends AbstractOutputAdapter implements ISwitchReply {
        OutputAdapter(AbstractBolt owner, Tuple tuple) {
            super(owner, tuple);
        }

        @Override
        public void setupPortHandler(PortFacts portFacts) {
            emit(STREAM_PORT_ID, makePortTuple(new PortSetupCommand(portFacts)));
        }

        @Override
        public void setupPortHandler(PortFacts portFacts, Isl history) {
            emit(STREAM_PORT_ID, makePortTuple(new PortSetupCommand(portFacts, history)));
        }

        @Override
        public void removePortHandler(PortFacts portFacts) {
            emit(STREAM_PORT_ID, makePortTuple(new PortRemoveCommand(portFacts)));
        }

        @Override
        public void sethOnlineMode(Endpoint endpoint, boolean mode) {
            emit(STREAM_PORT_ID, makePortTuple(new PortOnlineModeCommand(endpoint, mode)));
        }

        @Override
        public void setPortLinkMode(PortFacts port) {
            emit(STREAM_PORT_ID, makePortTuple(new PortLinkStatusCommand(port)));
        }

        private Values makePortTuple(PortCommand command) {
            Endpoint endpoint = command.getEndpoint();
            return new Values(endpoint.getDatapath(), endpoint.getPortNumber(), command, getContext());
        }
    }
}
