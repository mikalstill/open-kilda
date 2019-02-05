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

package org.openkilda.floodlight.feature;

import org.openkilda.messaging.model.Switch;
import org.openkilda.messaging.model.Switch.Feature;

import io.netty.channel.local.LocalChannel;
import io.netty.util.HashedWheelTimer;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.SwitchDescription;
import net.floodlightcontroller.core.internal.OFConnection;
import net.floodlightcontroller.core.internal.OFSwitch;
import net.floodlightcontroller.core.internal.OFSwitchManager;
import net.floodlightcontroller.debugcounter.MockDebugCounterService;
import org.junit.Assert;
import org.junit.Test;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.ver13.OFFactoryVer13;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFAuxId;

import java.util.Optional;

public class BfdFeatureTest {
    private static final BfdFeature bfdFeature = new BfdFeature();

    @Test
    public void testDiscoverOfSwitchWithBfdSupport() {
        assertWithBfdSupport("NoviFlow Inc OF_13 NW400.4.0");
        assertWithBfdSupport("NoviFlow Inc OF_13 NW400.3.5");
        assertWithBfdSupport("NoviFlow Inc OF_13 NW450.5.0");
        assertWithBfdSupport("NoviFlow Inc OF_13 NW450.50.10");
        assertWithBfdSupport("E OF_13 NW400.6.4");
        assertWithBfdSupport("E OF_13 NW450.6.4");
        assertWithBfdSupport("E OF_13 NW450.60.40");
    }

    @Test
    public void testDiscoverOfSwitchWithoutBfdSupport() {
        Assert.assertFalse(bfdFeature.discover(createSwitchWithDescription(null)).isPresent());

        assertWithoutBfdSupport("2004-2014 Centec Networks Inc OF_13 2.8.16.21");
        assertWithoutBfdSupport("2004-2015 Centec Networks Inc OF_13 2.8.16.15");
        assertWithoutBfdSupport("Sonus Networks Inc, 4 Technology Park Dr, Westford, MA 01886, USA OF_12 8.1.0.14");
    }

    private static void assertWithBfdSupport(String description) {
        Optional<Feature> feature = bfdFeature.discover(createSwitchWithSoftwareDescription(description));
        Assert.assertTrue(feature.isPresent());
        Assert.assertEquals(Switch.Feature.BFD, feature.get());
    }

    private static void assertWithoutBfdSupport(String description) {
        Optional<Feature> feature = bfdFeature.discover(createSwitchWithSoftwareDescription(description));
        Assert.assertFalse(feature.isPresent());
    }

    private static IOFSwitch createSwitchWithSoftwareDescription(String softwareDescription) {
        return createSwitchWithDescription(new SwitchDescription("", "", softwareDescription, "", ""));
    }

    private static IOFSwitch createSwitchWithDescription(SwitchDescription description) {
        OFFactory factory = new OFFactoryVer13();
        DatapathId dpid = DatapathId.of("1");
        OFConnection connection = new OFConnection(dpid, factory, new LocalChannel(), OFAuxId.MAIN,
                new MockDebugCounterService(), new HashedWheelTimer());

        OFSwitch sw = new OFSwitch(connection, factory, new OFSwitchManager(), dpid);
        sw.setSwitchProperties(description);
        return sw;
    }
}
