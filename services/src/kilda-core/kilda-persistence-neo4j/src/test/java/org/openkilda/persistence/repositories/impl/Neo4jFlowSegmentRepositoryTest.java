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

import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.Neo4jBasedTest;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.SwitchRepository;

import org.junit.Before;
import org.junit.BeforeClass;

public class Neo4jFlowSegmentRepositoryTest extends Neo4jBasedTest {
    static final String TEST_FLOW_ID = "test_flow";
    static final SwitchId TEST_SWITCH_A_ID = new SwitchId(1);
    static final SwitchId TEST_SWITCH_B_ID = new SwitchId(2);

    static FlowPathRepository flowSegmentRepository;
    static SwitchRepository switchRepository;

    private Switch switchA;
    private Switch switchB;

    @BeforeClass
    public static void setUp() {
        flowSegmentRepository = new Neo4jFlowPathRepository(neo4jSessionFactory, txManager);
        switchRepository = new Neo4jSwitchRepository(neo4jSessionFactory, txManager);
    }

    @Before
    public void createSwitches() {
        switchA = Switch.builder().switchId(TEST_SWITCH_A_ID).build();
        switchRepository.createOrUpdate(switchA);

        switchB = Switch.builder().switchId(TEST_SWITCH_B_ID).build();
        switchRepository.createOrUpdate(switchB);
    }
    /*
    @Test
    public void shouldCreateFlowSegment() {
        FlowSegment segment = FlowSegment.builder()
                .srcSwitch(switchA)
                .destSwitch(switchB)
                .flowId(TEST_FLOW_ID)
                .build();
        flowSegmentRepository.createOrUpdate(segment);

        Collection<FlowSegment> allSegments = flowSegmentRepository.findAll();
        FlowSegment foundSegment = allSegments.iterator().next();

        assertEquals(switchA.getSwitchId(), foundSegment.getSrcSwitch().getSwitchId());
        assertEquals(switchB.getSwitchId(), foundSegment.getDestSwitch().getSwitchId());
    }

    @Test
    public void shouldCreateSwitchAlongWithFlowSegment() {
        FlowSegment segment = FlowSegment.builder()
                .srcSwitch(switchA)
                .destSwitch(switchB)
                .flowId(TEST_FLOW_ID)
                .build();
        flowSegmentRepository.createOrUpdate(segment);

        assertEquals(2, switchRepository.findAll().size());
    }

    @Test
    public void shouldDeleteFlowSegment() {
        FlowSegment segment = FlowSegment.builder()
                .srcSwitch(switchA)
                .destSwitch(switchB)
                .flowId(TEST_FLOW_ID)
                .build();
        flowSegmentRepository.createOrUpdate(segment);

        flowSegmentRepository.delete(segment);

        assertEquals(0, flowSegmentRepository.findAll().size());
    }

    @Test
    public void shouldNotDeleteSwitchOnFlowSegmentDelete() {
        FlowSegment segment = FlowSegment.builder()
                .srcSwitch(switchA)
                .destSwitch(switchB)
                .flowId(TEST_FLOW_ID)
                .build();
        flowSegmentRepository.createOrUpdate(segment);

        flowSegmentRepository.delete(segment);

        assertEquals(2, switchRepository.findAll().size());
    }

    @Test
    public void shouldDeleteFoundFlowSegment() {
        FlowSegment segment = FlowSegment.builder()
                .srcSwitch(switchA)
                .destSwitch(switchB)
                .flowId(TEST_FLOW_ID)
                .build();
        flowSegmentRepository.createOrUpdate(segment);

        Collection<FlowSegment> allSegments = flowSegmentRepository.findAll();
        FlowSegment foundSegment = allSegments.iterator().next();
        flowSegmentRepository.delete(foundSegment);

        assertEquals(0, flowSegmentRepository.findAll().size());
    }

    @Test
    public void shouldFindSegmentByFlowIdAndCookie() {
        FlowSegment segment = FlowSegment.builder()
                .srcSwitch(switchA)
                .destSwitch(switchB)
                .cookie(1)
                .build();
        segment.setFlowId(TEST_FLOW_ID);

        flowSegmentRepository.createOrUpdate(segment);

        List<FlowSegment> foundSegment = Lists.newArrayList(
                flowSegmentRepository.findByFlowIdAndCookie(TEST_FLOW_ID, 1));
        assertThat(foundSegment, Matchers.hasSize(1));
    }
    */
}
