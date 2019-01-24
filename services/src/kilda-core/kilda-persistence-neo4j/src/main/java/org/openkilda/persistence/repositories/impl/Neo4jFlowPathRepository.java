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

import org.openkilda.model.Cookie;
import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceException;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.FlowPathRepository;

import com.google.common.collect.ImmutableMap;
import org.neo4j.ogm.cypher.ComparisonOperator;
import org.neo4j.ogm.cypher.Filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Neo4J OGM implementation of {@link FlowPathRepository}.
 */
public class Neo4jFlowPathRepository extends Neo4jGenericRepository<FlowPath> implements FlowPathRepository {
    static final String PATH_ID_PROPERTY_NAME = "path_id";
    static final String FLOW_ID_PROPERTY_NAME = "flowid";
    static final String COOKIE_PROPERTY_NAME = "cookie";

    public Neo4jFlowPathRepository(Neo4jSessionFactory sessionFactory, TransactionManager transactionManager) {
        super(sessionFactory, transactionManager);
    }

    @Override
    public Optional<FlowPath> findById(PathId pathId) {
        Filter pathIdFilter = new Filter(PATH_ID_PROPERTY_NAME, ComparisonOperator.EQUALS, pathId);

        Collection<FlowPath> flowPaths = getSession().loadAll(getEntityType(), pathIdFilter,
                DEPTH_LOAD_ENTITY);
        if (flowPaths.size() > 1) {
            throw new PersistenceException(format("Found more that 1 FlowPath entity by (%s)", pathId));
        } else if (flowPaths.isEmpty()) {
            return Optional.empty();
        }

        FlowPath flowPath = flowPaths.iterator().next();
        flowPath.setSegments(findPathSegmentsByPathId(flowPath.getPathId()));
        return Optional.of(flowPath);
    }

    @Override
    public Optional<FlowPath> findByFlowIdAndCookie(String flowId, Cookie cookie) {
        Filter flowIdFilter = new Filter(FLOW_ID_PROPERTY_NAME, ComparisonOperator.EQUALS, flowId);
        Filter cookieFilter = new Filter(COOKIE_PROPERTY_NAME, ComparisonOperator.EQUALS, cookie);

        Collection<FlowPath> flowPaths = getSession().loadAll(getEntityType(), flowIdFilter.and(cookieFilter),
                DEPTH_LOAD_ENTITY);
        if (flowPaths.size() > 1) {
            throw new PersistenceException(format("Found more that 1 FlowPath entity by (%s, %s)", flowId, cookie));
        } else if (flowPaths.isEmpty()) {
            return Optional.empty();
        }

        FlowPath flowPath = flowPaths.iterator().next();
        flowPath.setSegments(findPathSegmentsByPathId(flowPath.getPathId()));
        return Optional.of(flowPath);
    }

    private List<PathSegment> findPathSegmentsByPathId(PathId pathId) {
        Filter pathIdFilter = new Filter(PATH_ID_PROPERTY_NAME, ComparisonOperator.EQUALS, pathId);
        return new ArrayList<>(getSession().loadAll(PathSegment.class, pathIdFilter, DEPTH_LOAD_ENTITY));
    }

    @Override
    public Collection<FlowPath> findByFlowId(String flowId) {
        Filter flowIdFilter = new Filter(FLOW_ID_PROPERTY_NAME, ComparisonOperator.EQUALS, flowId);

        return getSession().loadAll(getEntityType(), flowIdFilter,
                DEPTH_LOAD_ENTITY);
    }

    @Override
    public Collection<PathSegment> findPathSegmentsByDestSwitchId(SwitchId switchId) {
        Filter destSwitchIdFilter = createDstSwitchFilter(switchId);
        return getSession().loadAll(PathSegment.class, destSwitchIdFilter, DEPTH_LOAD_ENTITY);
    }

    @Override
    public void createOrUpdate(FlowPath entity) {
        createOrUpdate(entity.getPathId(), entity.getSegments());

        super.createOrUpdate(entity);
    }

    private void createOrUpdate(PathId pathId, Collection<PathSegment> segments) {
        /*TODO:
        transactionManager.doInTransaction(() -> {
            lockSwitches(requireManagedEntity(segment.getSrcSwitch()), requireManagedEntity(segment.getDestSwitch()));

            getSession().save(segment, DEPTH_CREATE_UPDATE_ENTITY);
        });*/
    }

    @Override
    public void createOrUpdate(PathSegment segment) {
        transactionManager.doInTransaction(() -> {
            lockSwitches(requireManagedEntity(segment.getSrcSwitch()), requireManagedEntity(segment.getDestSwitch()));

            getSession().save(segment, DEPTH_CREATE_UPDATE_ENTITY);
        });
    }

    @Override
    public long getUsedBandwidthBetweenEndpoints(SwitchId srcSwitchId, int srcPort, SwitchId dstSwitchId, int dstPort) {
        Map<String, Object> parameters = ImmutableMap.of(
                "src_switch", srcSwitchId.toString(),
                "src_port", srcPort,
                "dst_switch", dstSwitchId.toString(),
                "dst_port", dstPort);

        String query = "MATCH (src:switch {name: $src_switch}), (dst:switch {name: $dst_switch}) "
                + "WITH src,dst "
                + "OPTIONAL MATCH (src) - [fs:flow_segment { "
                + " src_port: $src_port, "
                + " dst_port: $dst_port, "
                + " ignore_bandwidth: false "
                + "}] -> (dst) "
                + "WITH sum(fs.bandwidth) AS used_bandwidth RETURN used_bandwidth";

        return Optional.ofNullable(getSession().queryForObject(Long.class, query, parameters))
                .orElse(0L);
    }

    @Override
    Class<FlowPath> getEntityType() {
        return FlowPath.class;
    }
}
