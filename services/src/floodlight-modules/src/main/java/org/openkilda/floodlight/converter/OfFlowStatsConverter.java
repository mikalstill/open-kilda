/* Copyright 2017 Telstra Open Source
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

package org.openkilda.floodlight.converter;

import org.openkilda.messaging.info.rule.FlowApplyActions;
import org.openkilda.messaging.info.rule.FlowEntry;
import org.openkilda.messaging.info.rule.FlowInstructions;
import org.openkilda.messaging.info.rule.FlowMatchField;
import org.openkilda.messaging.info.rule.FlowSetFieldAction;

import org.projectfloodlight.openflow.protocol.OFActionType;
import org.projectfloodlight.openflow.protocol.OFFlowModFlags;
import org.projectfloodlight.openflow.protocol.OFFlowStatsEntry;
import org.projectfloodlight.openflow.protocol.OFInstructionType;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionMeter;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActionPushVlan;
import org.projectfloodlight.openflow.protocol.action.OFActionSetField;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionApplyActions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionMeter;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxm;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Utility class that converts OFlowStats from the switch to kilda known format for further processing.
 */
public final class OfFlowStatsConverter {

    /**
     * OF specification added 13 bit that defines existence of vlan tag.
     */
    private static final int VLAN_MASK = 0xFFF;

    /**
     * Convert {@link OFFlowStatsEntry} to format that kilda supports.
     * @param entry flow stats to be converted.
     * @return result of transformation.
     */
    public static FlowEntry toFlowEntry(final OFFlowStatsEntry entry) {
        return FlowEntry.builder()
                .version(entry.getVersion().toString())
                .durationSeconds(entry.getDurationSec())
                .durationNanoSeconds(entry.getDurationNsec())
                .hardTimeout(entry.getHardTimeout())
                .idleTimeout(entry.getIdleTimeout())
                .priority(entry.getPriority())
                .byteCount(entry.getByteCount().getValue())
                .packetCount(entry.getPacketCount().getValue())
                .flags(entry.getFlags().stream()
                        .map(OFFlowModFlags::name)
                        .toArray(String[]::new))
                .cookie(entry.getCookie().getValue())
                .tableId(entry.getTableId().getValue())
                .match(buildFlowMatch(entry.getMatch()))
                .instructions(buildFlowInstructions(entry.getInstructions()))
                .build();
    }

    private static FlowMatchField buildFlowMatch(final Match match) {
        return FlowMatchField.builder()
                .vlanVid(Optional.ofNullable(match.get(MatchField.VLAN_VID))
                        .map(value -> String.valueOf(value.getVlan() & VLAN_MASK))
                        .orElse(null))
                .ethType(Optional.ofNullable(match.get(MatchField.ETH_TYPE))
                        .map(Objects::toString).orElse(null))
                .ethSrc(Optional.ofNullable(match.get(MatchField.ETH_SRC))
                        .map(Objects::toString).orElse(null))
                .ethDst(Optional.ofNullable(match.get(MatchField.ETH_DST))
                        .map(Objects::toString).orElse(null))
                .inPort(Optional.ofNullable(match.get(MatchField.IN_PORT))
                        .map(Objects::toString).orElse(null))
                .ipProto(Optional.ofNullable(match.get(MatchField.IP_PROTO))
                        .map(Objects::toString).orElse(null))
                .udpDst(Optional.ofNullable(match.get(MatchField.UDP_DST))
                        .map(Objects::toString).orElse(null))
                .udpSrc(Optional.ofNullable(match.get(MatchField.UDP_SRC))
                        .map(Objects::toString).orElse(null))
                .build();
    }

    private static FlowInstructions buildFlowInstructions(final List<OFInstruction> instructions) {
        Map<OFInstructionType, OFInstruction> instructionMap = instructions
                .stream()
                .collect(Collectors.toMap(OFInstruction::getType, instruction -> instruction));

        FlowApplyActions applyActions = Optional.ofNullable(instructionMap.get(OFInstructionType.APPLY_ACTIONS))
                .map(OfFlowStatsConverter::buildApplyActions)
                .orElse(null);

        Long meter = Optional.ofNullable(instructionMap.get(OFInstructionType.METER))
                .map(instruction -> ((OFInstructionMeter) instruction).getMeterId())
                .orElse(null);

        return FlowInstructions.builder()
                .applyActions(applyActions)
                .goToMeter(meter)
                .build();
    }

    private static FlowApplyActions buildApplyActions(OFInstruction instruction) {
        Map<OFActionType, OFAction> actions = ((OFInstructionApplyActions) instruction).getActions()
                .stream()
                .collect(Collectors.toMap(OFAction::getType, action -> action));
        return FlowApplyActions.builder()
                .meter(Optional.ofNullable(actions.get(OFActionType.METER))
                        .map(action -> String.valueOf(((OFActionMeter) action).getMeterId()))
                        .orElse(null))
                .pushVlan(Optional.ofNullable(actions.get(OFActionType.PUSH_VLAN))
                        .map(action ->
                                String.valueOf(((OFActionPushVlan) action).getEthertype().toString()))
                        .orElse(null))
                .flowOutput(Optional.ofNullable(actions.get(OFActionType.OUTPUT))
                        .map(action -> String.valueOf(((OFActionOutput) action).getPort().toString()))
                        .orElse(null))
                .fieldAction(Optional.ofNullable(actions.get(OFActionType.SET_FIELD))
                        .map(OfFlowStatsConverter::buildSetField)
                        .orElse(null))
                .build();
    }

    private static FlowSetFieldAction buildSetField(OFAction action) {
        OFOxm<?> setFieldAction = ((OFActionSetField) action).getField();
        String value = setFieldAction.getValue().toString();

        if (MatchField.VLAN_VID.getName().equals(setFieldAction.getMatchField().getName())) {
            value = String.valueOf(Long.decode(value) & VLAN_MASK);
        }
        return FlowSetFieldAction.builder()
                .fieldName(setFieldAction.getMatchField().getName())
                .fieldValue(value)
                .build();
    }

    private OfFlowStatsConverter() {
    }
}
