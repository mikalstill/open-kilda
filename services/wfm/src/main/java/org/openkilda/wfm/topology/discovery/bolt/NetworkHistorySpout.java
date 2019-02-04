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

import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.topology.discovery.model.DiscoveryOptions;
import org.openkilda.wfm.topology.discovery.model.SwitchHistory;
import org.openkilda.wfm.topology.discovery.model.SwitchHistoryCommand;
import org.openkilda.wfm.topology.discovery.service.DiscoveryHistoryService;
import org.openkilda.wfm.topology.discovery.service.ISwitchPrepopulateReply;

import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichSpout;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;

import java.util.Map;

public class NetworkHistorySpout extends BaseRichSpout {
    public static final String SPOUT_ID = ComponentId.NETWORK_PRELOADER.toString();

    public static final String FIELD_ID_DATAPATH = SpeakerMonitor.FIELD_ID_DATAPATH;
    public static final String FIELD_ID_PAYLOAD = "switch-init";

    public static final Fields STREAM_FIELDS = new Fields(FIELD_ID_DATAPATH, FIELD_ID_PAYLOAD);

    private final DiscoveryOptions options;
    private final PersistenceManager persistenceManager;

    private transient DiscoveryHistoryService service;
    private transient SpoutOutputCollector output;

    private boolean workDone = false;

    public NetworkHistorySpout(DiscoveryOptions options, PersistenceManager persistenceManager) {
        this.options = options;
        this.persistenceManager = persistenceManager;
    }

    @Override
    public void nextTuple() {
        if (workDone) {
            org.apache.storm.utils.Utils.sleep(1L);
            return;
        }
        workDone = true;

        service.applyHistory(new OutputAdapter(output));
    }

    @Override
    public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
        output = collector;
        service = new DiscoveryHistoryService(options, persistenceManager);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        streamManager.declare(STREAM_FIELDS);
    }

    private static class OutputAdapter implements ISwitchPrepopulateReply {
        private final SpoutOutputCollector output;
        private final CommandContext rootContext;

        OutputAdapter(SpoutOutputCollector output) {
            this.output = output;
            this.rootContext = new CommandContext();
        }

        public void switchAddWithHistory(SwitchHistory switchHistory) {
            SwitchHistoryCommand command = new SwitchHistoryCommand(switchHistory);
            SwitchId switchId = command.getDatapath();
            CommandContext context = rootContext.makeNested(switchId.toOtsdFormat());
            output.emit(new Values(switchId, command, context));
        }
    }
}
