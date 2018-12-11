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

package org.openkilda.wfm.topology.discovery;

import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.discovery.bolt.ComponentId;
import org.openkilda.wfm.topology.discovery.bolt.SpeakerMonitor;
import org.openkilda.wfm.topology.discovery.bolt.InputDecoder;
import org.openkilda.wfm.topology.discovery.bolt.MonotonicTick;
import org.openkilda.wfm.topology.discovery.bolt.SpeakerEncoder;

import org.apache.storm.generated.StormTopology;
import org.apache.storm.kafka.bolt.KafkaBolt;
import org.apache.storm.kafka.spout.KafkaSpout;
import org.apache.storm.topology.TopologyBuilder;

public class DiscoveryTopology extends AbstractTopology<DiscoveryTopologyConfig> {
    public DiscoveryTopology(LaunchEnvironment env) {
        super(env, DiscoveryTopologyConfig.class);
    }

    /**
     * Discovery topology factory.
     */
    @Override
    public StormTopology createTopology() {
        int scaleFactor = topologyConfig.getScaleFactor();

        TopologyBuilder topology = new TopologyBuilder();

        monotonicTick(topology);
        input(topology, scaleFactor);

        speakerMonitor(topology);
        switchHandler(topology, scaleFactor);
        portHandler(topology, scaleFactor);
        islHandler(topology, scaleFactor);

        output(topology, scaleFactor);

        return topology.createTopology();
    }

    private void monotonicTick(TopologyBuilder topology) {
        topology.setBolt(MonotonicTick.BOLT_ID, new MonotonicTick(topologyConfig.getDiscoveryInterval()));
    }

    private void input(TopologyBuilder topology, int scaleFactor) {
        KafkaSpout<String, String> spout = createKafkaSpout(
                topologyConfig.getKafkaSpeakerDiscoTopic(), ComponentId.INPUT.toString());
        topology.setSpout(ComponentId.INPUT.toString(), spout, scaleFactor);

        InputDecoder bolt = new InputDecoder();
        topology.setBolt(InputDecoder.BOLT_ID, bolt, scaleFactor)
                .shuffleGrouping(ComponentId.INPUT.toString());
    }

    private void speakerMonitor(TopologyBuilder topology) {
        SpeakerMonitor bolt = new SpeakerMonitor(topologyConfig.getSpeakerFailureTimeoutSeconds(),
                                                 topologyConfig.getDumpRequestTimeoutSeconds());
        topology.setBolt(SpeakerMonitor.BOLT_ID, bolt, 1)
                .allGrouping(MonotonicTick.BOLT_ID)
                .allGrouping(InputDecoder.BOLT_ID);
    }

    private void output(TopologyBuilder topology, int scaleFactor) {
        SpeakerEncoder bolt = new SpeakerEncoder();
        topology.setBolt(SpeakerEncoder.BOLT_ID, bolt, scaleFactor)
                .shuffleGrouping(SpeakerMonitor.BOLT_ID, SpeakerMonitor.STREAM_SPEAKER_ID);
                // TODO(surabujin): subscribe

        KafkaBolt output = createKafkaBolt(topologyConfig.getKafkaSpeakerTopic());
        topology.setBolt(ComponentId.SPEAKER_OUTPUT.toString(), output, scaleFactor)
                .shuffleGrouping(SpeakerEncoder.BOLT_ID);
    }

    /**
     * Discovery topology uploader.
     */
    public static void main(String[] args) {
        try {
            LaunchEnvironment env = new LaunchEnvironment(args);
            (new DiscoveryTopology(env)).setup();
        } catch (Exception e) {
            System.exit(handleLaunchException(e));
        }
    }
}
