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

package org.openkilda.persistence.repositories.impl;

import static java.lang.String.format;
import static java.util.Collections.singleton;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceException;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.converters.FlowStatusConverter;
import org.openkilda.persistence.repositories.FlowRepository;

import com.google.common.collect.ImmutableMap;
import org.neo4j.ogm.cypher.ComparisonOperator;
import org.neo4j.ogm.cypher.Filter;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Neo4J OGM implementation of {@link FlowRepository}.
 */
public class Neo4jFlowRepository extends Neo4jGenericRepository<Flow> implements FlowRepository {
    private static final String FLOW_ID_PROPERTY_NAME = "flowid";
    private static final String PERIODIC_PINGS_PROPERTY_NAME = "periodic_pings";

    private final FlowStatusConverter flowStatusConverter = new FlowStatusConverter();

    public Neo4jFlowRepository(Neo4jSessionFactory sessionFactory, TransactionManager transactionManager) {
        super(sessionFactory, transactionManager);
    }

    @Override
    public boolean exists(String flowId) {
        Filter flowIdFilter = new Filter(FLOW_ID_PROPERTY_NAME, ComparisonOperator.EQUALS, flowId);

        return getSession().count(getEntityType(), singleton(flowIdFilter)) > 0;
    }

    @Override
    public Optional<Flow> findById(String flowId) {
        Filter flowIdFilter = new Filter(FLOW_ID_PROPERTY_NAME, ComparisonOperator.EQUALS, flowId);

        Collection<Flow> flows = getSession().loadAll(getEntityType(), flowIdFilter, DEPTH_LOAD_ENTITY);
        if (flows.size() > 1) {
            throw new PersistenceException(format("Found more that 1 Flow entity by %s as flowId", flowId));
        }
        return flows.isEmpty() ? Optional.empty() : Optional.of(flows.iterator().next());
    }

    @Override
    public Collection<Flow> findWithPeriodicPingsEnabled() {
        Filter periodicPingsFilter = new Filter(PERIODIC_PINGS_PROPERTY_NAME, ComparisonOperator.EQUALS, true);

        return getSession().loadAll(getEntityType(), periodicPingsFilter, DEPTH_LOAD_ENTITY);
    }


    @Override
    public Collection<Flow> findByEndpoint(SwitchId switchId, int port) {
        Map<String, Object> parameters = ImmutableMap.of(
                "switch_id", switchId.toString(),
                "port", port);

        Set<Flow> flows = new HashSet<>();
        getSession().query(Flow.class, "MATCH (src:switch)-[f:flow]->(dst:switch) "
                + "WHERE src.name=$switch_id AND f.src_port=$port "
                + " OR dst.name=$switch_id AND f.dst_port=$port "
                + "RETURN src,f, dst", parameters).forEach(flows::add);
        return flows;
    }

    @Override
    public Collection<String> findActiveFlowIdsWithPortInPath(SwitchId switchId, int port) {
        Map<String, Object> parameters = ImmutableMap.of(
                "switch_id", switchId.toString(),
                "port", port,
                "flow_status", flowStatusConverter.toGraphProperty(FlowStatus.UP));

        Set<String> flowIds = new HashSet<>();
        // Treat empty status as UP to support old storage schema.
        getSession().query(String.class, "MATCH (src:switch)-[f:flow]->(dst:switch) "
                + "WHERE (src.name=$switch_id AND f.src_port=$port "
                + " OR dst.name=$switch_id AND f.dst_port=$port) "
                + " AND (f.status=$flow_status OR f.status IS NULL)"
                + "RETURN f.flowid", parameters).forEach(flowIds::add);

        getSession().query(String.class, "MATCH (src:switch)-[fs:flow_segment]->(dst:switch) "
                + "WHERE (src.name=$switch_id AND fs.src_port=$port "
                + " OR dst.name=$switch_id AND fs.dst_port=$port) "
                + "WITH fs "
                + "MATCH ()-[f:flow]->() "
                + "WHERE fs.flowid = f.flowid AND (f.status=$flow_status OR f.status IS NULL)"
                + "RETURN f.flowid", parameters).forEach(flowIds::add);
        return flowIds;
    }

    @Override
    public Collection<String> findDownFlowIds() {
        Map<String, Object> parameters = ImmutableMap.of(
                "flow_status", flowStatusConverter.toGraphProperty(FlowStatus.DOWN));

        Set<String> flowIds = new HashSet<>();
        getSession().query(String.class,
                "MATCH ()-[f:flow{status: {flow_status}}]->() RETURN f.flowid", parameters).forEach(flowIds::add);
        return flowIds;
    }

    @Override
    public Collection<Flow> findBySrcSwitchId(SwitchId switchId) {
        Filter srcSwitchFilter = createSrcSwitchFilter(switchId);
        return getSession().loadAll(getEntityType(), srcSwitchFilter, DEPTH_LOAD_ENTITY);
    }

    @Override
    public void createOrUpdate(Flow flow) {
        transactionManager.doInTransaction(() -> {
            lockSwitches(requireManagedEntity(flow.getSrcSwitch()), requireManagedEntity(flow.getDestSwitch()));
            super.createOrUpdate(flow);
        });
    }

    @Override
    public Collection<Flow> findWithPathSegment(SwitchId srcSwitchId, int srcPort,
                                                SwitchId dstSwitchId, int dstPort) {
        Map<String, Object> parameters = ImmutableMap.of(
                "src_switch", srcSwitchId,
                "src_port", srcPort,
                "dst_switch", dstSwitchId,
                "dst_port", dstPort);

        Set<Flow> flows = new HashSet<>();
        getSession().query(Flow.class, "MATCH (:switch)-[fc:flow_segment]->(:switch) "
                + "WHERE fc.src_switch=$src_switch "
                + "AND fc.src_port=$src_port  "
                + "AND fc.dst_switch=$dst_switch "
                + "AND fc.dst_port=$dst_port  "
                + "MATCH (src:switch)-[f:flow]->(dst:switch) "
                + "WHERE fc.flowid=f.flowid  "
                + "RETURN src, f, dst", parameters).forEach(flows::add);
        return flows;
    }

    @Override
    Class<Flow> getEntityType() {
        return Flow.class;
    }
}
