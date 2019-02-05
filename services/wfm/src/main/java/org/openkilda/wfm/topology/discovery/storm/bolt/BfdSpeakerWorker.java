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

package org.openkilda.wfm.topology.discovery.storm.bolt;

import org.openkilda.wfm.share.hubandspoke.WorkerBolt;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;

public class BfdSpeakerWorker extends WorkerBolt {
    public static final String BOLD_ID = WorkerBolt.ID + ".bfd.speaker";

    public static final String STREAM_HUB_ID = "hub";

    public static final Fields STREAM_FIELDS = new Fields();

    public BfdSpeakerWorker(Config config) {
        super(config);
    }

    @Override
    protected void onHubRequest(Tuple input) {
        // TODO
    }

    @Override
    protected void onAsyncResponse(Tuple input) {
        // TODO
    }

    @Override
    public void onTimeout(String key) {
        // TODO
    }

    public void declareOutputFields(OutputFieldsDeclarer streamManager) {
        super.declareOutputFields(streamManager); // if will define HUB stream
        streamManager.declare(STREAM_FIELDS);
    }
}
