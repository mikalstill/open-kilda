package org.openkilda.functionaltests.spec.northbound.flows

import org.openkilda.functionaltests.BaseSpecification
import org.openkilda.functionaltests.helpers.FlowHelper
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.payload.flow.FlowPayload
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.testing.model.topology.TopologyDefinition
import org.openkilda.testing.model.topology.TopologyDefinition.Switch
import org.openkilda.testing.service.database.Database
import org.openkilda.testing.service.northbound.NorthboundService
import org.openkilda.testing.service.topology.TopologyEngineService
import org.openkilda.testing.tools.IslUtils

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value

import java.util.concurrent.TimeUnit

class MultiRerouteSpec extends BaseSpecification {

    @Value('${reroute.delay}')
    int rerouteDelay
    @Value('${discovery.interval}')
    int discoveryInterval

    @Autowired
    TopologyDefinition topology
    @Autowired
    TopologyEngineService topologyEngineService
    @Autowired
    FlowHelper flowHelper
    @Autowired
    NorthboundService northboundService
    @Autowired
    IslUtils islUtils
    @Autowired
    PathHelper pathHelper
    @Autowired
    Database db

    def "Simultaneous reroute of multiple flows should not oversubscribe any ISLs"() {
        given: "2 flows on the same path, with alt paths available"
        def switches = topology.activeSwitches
        List<List<PathNode>> allPaths = []
        def (Switch srcSwitch, Switch dstSwitch) = [switches, switches].combinations()
                .findAll {src, dst -> src != dst}.unique {it.sort()}.find {Switch src, Switch dst ->
            allPaths = topologyEngineService.getPaths(src.dpId, dst.dpId)*.path
            allPaths.size() > 2
        }
        def currentPath = allPaths.min { pathHelper.getCost(it) }
        List<FlowPayload> flows = []
        2.times {
            def flow = flowHelper.randomFlow(srcSwitch, dstSwitch)
            flow.maximumBandwidth = 10000
            northboundService.addFlow(flow)
            flows << flow
        }
        Wrappers.wait(5) { flows.every { northboundService.getFlowStatus(it.id).status == FlowState.UP } }
        assert flows.every { pathHelper.convert(northboundService.getFlowPath(it.id)) == currentPath }

        when: "Make another path more preferable"
        def newPath = allPaths.find { it != currentPath }
        allPaths.findAll { it != newPath }.each { pathHelper.makePathMorePreferable(newPath, it) }

        and: "Make preferable path's ISL to have not enough bandwidth to handle 2 flows together, but enough for 1 flow"
        def currentIsls = pathHelper.getInvolvedIsls(currentPath)
        def newIsls = pathHelper.getInvolvedIsls(newPath)
        def thinIsl = newIsls.find { !currentIsls.contains(it) }
        def newBw = flows.sum { it.maximumBandwidth } - 1
        db.updateLinkProperty(thinIsl, "max_bandwidth", newBw)
        db.updateLinkProperty(islUtils.reverseIsl(thinIsl), "max_bandwidth", newBw)
        db.updateLinkProperty(thinIsl, "available_bandwidth", newBw)
        db.updateLinkProperty(islUtils.reverseIsl(thinIsl), "available_bandwidth", newBw)

        and: "Init simultaneous reroute of both flows by bringing current path's ISL down"
        def islToBreak = currentIsls.find { !newIsls.contains(it) }
        northboundService.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        TimeUnit.SECONDS.sleep(rerouteDelay - 1)

        then: "Both flows change their paths"
        Wrappers.wait(5) {
            flows.every { pathHelper.convert(northboundService.getFlowPath(it.id)) != currentPath }
        }

        and: "'Thin' ISL is not oversubscribed"
        islUtils.getIslInfo(thinIsl).get().availableBandwidth == newBw - flows.first().maximumBandwidth

        and: "Only one flow goes through a preferred path"
        flows.count { pathHelper.convert(northboundService.getFlowPath(it.id)) == newPath } == 1

        and: "cleanup"
        northboundService.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        db.revertIslBandwidth(thinIsl)
        flows.every { northboundService.deleteFlow(it.id) }
        northboundService.deleteLinkProps(northboundService.getAllLinkProps())
        db.resetCosts()
        Wrappers.wait(discoveryInterval + 1) { islUtils.getIslInfo(islToBreak).get().state == IslChangeType.DISCOVERED }
    }
}
