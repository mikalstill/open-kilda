package org.bitbucket.openkilda.wfm.topology.flow;

import static org.bitbucket.openkilda.messaging.Utils.TRANSACTION_ID;

import org.bitbucket.openkilda.messaging.ServiceType;
import org.bitbucket.openkilda.messaging.Topic;
import org.bitbucket.openkilda.messaging.Utils;
import org.bitbucket.openkilda.pce.provider.NeoDriver;
import org.bitbucket.openkilda.pce.provider.PathComputer;
import org.bitbucket.openkilda.wfm.topology.AbstractTopology;
import org.bitbucket.openkilda.wfm.topology.flow.bolts.CrudBolt;
import org.bitbucket.openkilda.wfm.topology.flow.bolts.ErrorBolt;
import org.bitbucket.openkilda.wfm.topology.flow.bolts.NorthboundReplyBolt;
import org.bitbucket.openkilda.wfm.topology.flow.bolts.NorthboundRequestBolt;
import org.bitbucket.openkilda.wfm.topology.flow.bolts.SpeakerBolt;
import org.bitbucket.openkilda.wfm.topology.flow.bolts.StatusBolt;
import org.bitbucket.openkilda.wfm.topology.flow.bolts.TopologyEngineBolt;
import org.bitbucket.openkilda.wfm.topology.flow.bolts.TransactionBolt;
import org.bitbucket.openkilda.wfm.topology.utils.HealthCheckBolt;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.storm.Config;
import org.apache.storm.LocalCluster;
import org.apache.storm.StormSubmitter;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.kafka.KafkaSpout;
import org.apache.storm.kafka.bolt.KafkaBolt;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;

/**
 * Flow topology.
 */
public class FlowTopology extends AbstractTopology {
    public static final String FLOW_ID_FIELD = Utils.FLOW_ID;
    public static final String SWITCH_ID_FIELD = "switch-id";
    public static final String STATUS_FIELD = "status";
    public static final String ERROR_TYPE_FIELD = "error-type";
    public static final Fields fieldFlowId = new Fields(FLOW_ID_FIELD);
    public static final Fields fieldSwitchId = new Fields(SWITCH_ID_FIELD);
    public static final Fields fieldsFlowIdStatus = new Fields(FLOW_ID_FIELD, STATUS_FIELD);
    public static final Fields fieldsMessageFlowId = new Fields(MESSAGE_FIELD, FLOW_ID_FIELD);
    public static final Fields fieldsMessageErrorType = new Fields(MESSAGE_FIELD, ERROR_TYPE_FIELD);
    public static final Fields fieldsMessageSwitchIdFlowIdTransactionId =
            new Fields(MESSAGE_FIELD, SWITCH_ID_FIELD, FLOW_ID_FIELD, TRANSACTION_ID);
    private static final Logger logger = LogManager.getLogger(FlowTopology.class);
    private static final String TOPIC = "kilda-test";

    /**
     * Path computation instance.
     */
    private final PathComputer pathComputer;

    public FlowTopology(PathComputer pathComputer) {
        this.pathComputer = pathComputer;

        checkAndCreateTopic(Topic.HEALTH_CHECK.getId());

        logger.debug("Topology built {}: zookeeper={}, kafka={}, parallelism={}, workers={}",
                topologyName, zookeeperHosts, kafkaHosts, parallelism, workers);
    }

    /**
     * Loads topology.
     *
     * @param args topology args
     * @throws Exception if topology submitting fails
     */
    public static void main(String[] args) throws Exception {
        final FlowTopology flowTopology = new FlowTopology(new NeoDriver());
        StormTopology stormTopology = flowTopology.createTopology();
        final Config config = new Config();
        config.setNumWorkers(flowTopology.workers);

        if (args != null && args.length > 0) {
            logger.info("Start Topology: {}", flowTopology.getTopologyName());

            config.setDebug(false);

            StormSubmitter.submitTopology(args[0], config, stormTopology);
        } else {
            logger.info("Start Topology Locally: {}", flowTopology.topologyName);

            config.setDebug(true);
            config.setMaxTaskParallelism(flowTopology.parallelism);

            LocalCluster cluster = new LocalCluster();
            cluster.submitTopology(flowTopology.topologyName, config, stormTopology);

            logger.info("Sleep", flowTopology.topologyName);
            Thread.sleep(60000);
            cluster.shutdown();
        }
    }

    @Override
    public StormTopology createTopology() {
        logger.info("Creating Topology: {}", topologyName);

        TopologyBuilder builder = new TopologyBuilder();

        /*
         * Spout receives all Northbound requests.
         */
        KafkaSpout northboundKafkaSpout = createKafkaSpout(TOPIC);
        builder.setSpout(ComponentType.NORTHBOUND_KAFKA_SPOUT.toString(), northboundKafkaSpout, parallelism);

        /*
         * Bolt splits Northbound requests on streams.
         * It groups requests by flow-id.
         */
        NorthboundRequestBolt northboundRequestBolt = new NorthboundRequestBolt();
        builder.setBolt(ComponentType.NORTHBOUND_REQUEST_BOLT.toString(), northboundRequestBolt, parallelism)
                .shuffleGrouping(ComponentType.NORTHBOUND_KAFKA_SPOUT.toString());

        /*
         * Bolt tracks flow status and receives transactions and errors.
         * It uses flow-id for grouping.
         */
        StatusBolt statusBolt = new StatusBolt();
        builder.setBolt(ComponentType.STATUS_BOLT.toString(), statusBolt, parallelism)
                .fieldsGrouping(ComponentType.NORTHBOUND_REQUEST_BOLT.toString(), StreamType.CREATE.toString(), fieldFlowId)
                .fieldsGrouping(ComponentType.NORTHBOUND_REQUEST_BOLT.toString(), StreamType.READ.toString(), fieldFlowId)
                .fieldsGrouping(ComponentType.NORTHBOUND_REQUEST_BOLT.toString(), StreamType.UPDATE.toString(), fieldFlowId)
                .fieldsGrouping(ComponentType.NORTHBOUND_REQUEST_BOLT.toString(), StreamType.DELETE.toString(), fieldFlowId)
                .fieldsGrouping(ComponentType.NORTHBOUND_REQUEST_BOLT.toString(), StreamType.PATH.toString(), fieldFlowId)
                .fieldsGrouping(ComponentType.NORTHBOUND_REQUEST_BOLT.toString(), StreamType.STATUS.toString(), fieldFlowId)
                .fieldsGrouping(ComponentType.TRANSACTION_BOLT.toString(), StreamType.STATUS.toString(), fieldFlowId)
                .fieldsGrouping(ComponentType.SPEAKER_BOLT.toString(), StreamType.STATUS.toString(), fieldFlowId)
                .fieldsGrouping(ComponentType.TOPOLOGY_ENGINE_BOLT.toString(), StreamType.STATUS.toString(), fieldFlowId);

        CrudBolt crudBolt = new CrudBolt(pathComputer);
        builder.setBolt(ComponentType.CRUD_BOLT.toString(), crudBolt, parallelism)
                .fieldsGrouping(ComponentType.STATUS_BOLT.toString(), StreamType.CREATE.toString(), fieldFlowId)
                .fieldsGrouping(ComponentType.STATUS_BOLT.toString(), StreamType.READ.toString(), fieldFlowId)
                .fieldsGrouping(ComponentType.STATUS_BOLT.toString(), StreamType.UPDATE.toString(), fieldFlowId)
                .fieldsGrouping(ComponentType.STATUS_BOLT.toString(), StreamType.DELETE.toString(), fieldFlowId)
                .fieldsGrouping(ComponentType.STATUS_BOLT.toString(), StreamType.PATH.toString(), fieldFlowId);

        /*
         * Bolt sends Topology Engine requests
         */
        KafkaBolt topologyKafkaBolt = createKafkaBolt(TOPIC);
        builder.setBolt(ComponentType.TOPOLOGY_ENGINE_KAFKA_BOLT.toString(), topologyKafkaBolt, parallelism)
                .shuffleGrouping(ComponentType.CRUD_BOLT.toString(), StreamType.CREATE.toString())
                .shuffleGrouping(ComponentType.CRUD_BOLT.toString(), StreamType.UPDATE.toString())
                .shuffleGrouping(ComponentType.CRUD_BOLT.toString(), StreamType.DELETE.toString());

        /*
         * Spout receives Topology Engine response
         */
        KafkaSpout topologyKafkaSpout = createKafkaSpout(TOPIC);
        builder.setSpout(ComponentType.TOPOLOGY_ENGINE_KAFKA_SPOUT.toString(), topologyKafkaSpout, parallelism);

        /*
         * Bolt processes Topology Engine responses, groups by flow-id field
         */
        TopologyEngineBolt topologyEngineBolt = new TopologyEngineBolt();
        builder.setBolt(ComponentType.TOPOLOGY_ENGINE_BOLT.toString(), topologyEngineBolt, parallelism)
                .shuffleGrouping(ComponentType.TOPOLOGY_ENGINE_KAFKA_SPOUT.toString());

        /*
         * Bolt sends Speaker requests
         */
        KafkaBolt speakerKafkaBolt = createKafkaBolt(TOPIC);
        builder.setBolt(ComponentType.SPEAKER_KAFKA_BOLT.toString(), speakerKafkaBolt, parallelism)
                .shuffleGrouping(ComponentType.TRANSACTION_BOLT.toString(), StreamType.CREATE.toString())
                .shuffleGrouping(ComponentType.TRANSACTION_BOLT.toString(), StreamType.DELETE.toString());

        /*
         * Spout receives Speaker responses
         */
        KafkaSpout speakerKafkaSpout = createKafkaSpout(TOPIC);
        builder.setSpout(ComponentType.SPEAKER_KAFKA_SPOUT.toString(), speakerKafkaSpout, parallelism);

        /*
         * Bolt processes Speaker responses, groups by flow-id field
         */
        SpeakerBolt speakerBolt = new SpeakerBolt();
        builder.setBolt(ComponentType.SPEAKER_BOLT.toString(), speakerBolt, parallelism)
                .shuffleGrouping(ComponentType.SPEAKER_KAFKA_SPOUT.toString());

        /*
         * Transaction bolt.
         */
        TransactionBolt transactionBolt = new TransactionBolt();
        builder.setBolt(ComponentType.TRANSACTION_BOLT.toString(), transactionBolt, parallelism)
                .fieldsGrouping(ComponentType.TOPOLOGY_ENGINE_BOLT.toString(), StreamType.CREATE.toString(), fieldSwitchId)
                .fieldsGrouping(ComponentType.TOPOLOGY_ENGINE_BOLT.toString(), StreamType.DELETE.toString(), fieldSwitchId)
                .fieldsGrouping(ComponentType.SPEAKER_BOLT.toString(), StreamType.CREATE.toString(), fieldSwitchId)
                .fieldsGrouping(ComponentType.SPEAKER_BOLT.toString(), StreamType.DELETE.toString(), fieldSwitchId);

        /*
         * Error processing bolt
         */
        ErrorBolt errorProcessingBolt = new ErrorBolt();
        builder.setBolt(ComponentType.ERROR_BOLT.toString(), errorProcessingBolt, parallelism)
                .shuffleGrouping(ComponentType.NORTHBOUND_REQUEST_BOLT.toString(), StreamType.ERROR.toString())
                .shuffleGrouping(ComponentType.STATUS_BOLT.toString(), StreamType.ERROR.toString())
                .shuffleGrouping(ComponentType.STATUS_BOLT.toString(), StreamType.ERROR.toString());

        /*
         * Bolt forms Northbound responses
         */
        NorthboundReplyBolt northboundReplyBolt = new NorthboundReplyBolt();
        builder.setBolt(ComponentType.NORTHBOUND_REPLY_BOLT.toString(), northboundReplyBolt, parallelism)
                .shuffleGrouping(ComponentType.TOPOLOGY_ENGINE_BOLT.toString(), StreamType.RESPONSE.toString())
                .shuffleGrouping(ComponentType.STATUS_BOLT.toString(), StreamType.RESPONSE.toString())
                .shuffleGrouping(ComponentType.CRUD_BOLT.toString(), StreamType.RESPONSE.toString())
                .shuffleGrouping(ComponentType.ERROR_BOLT.toString(), StreamType.RESPONSE.toString());

        /*
         * Bolt sends Northbound responses
         */
        KafkaBolt northboundKafkaBolt = createKafkaBolt(TOPIC);
        builder.setBolt(ComponentType.NORTHBOUND_KAFKA_BOLT.toString(), northboundKafkaBolt, parallelism)
                .shuffleGrouping(ComponentType.NORTHBOUND_REPLY_BOLT.toString(), StreamType.RESPONSE.toString());

        String prefix = ServiceType.FLOW_TOPOLOGY.getId();
        KafkaSpout healthCheckKafkaSpout = createKafkaSpout(Topic.HEALTH_CHECK.getId());
        builder.setSpout(prefix + "HealthCheckKafkaSpout", healthCheckKafkaSpout, 1);
        HealthCheckBolt healthCheckBolt = new HealthCheckBolt(ServiceType.FLOW_TOPOLOGY);
        builder.setBolt(prefix + "HealthCheckBolt", healthCheckBolt, 1)
                .shuffleGrouping(prefix + "HealthCheckKafkaSpout");
        KafkaBolt healthCheckKafkaBolt = createKafkaBolt(Topic.HEALTH_CHECK.getId());
        builder.setBolt(prefix + "HealthCheckKafkaBolt", healthCheckKafkaBolt, 1)
                .shuffleGrouping(prefix + "HealthCheckBolt", Topic.HEALTH_CHECK.getId());

        return builder.createTopology();
    }
}
