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

package org.openkilda.floodlight.feature;

import org.openkilda.messaging.model.Switch;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.SwitchDescription;

import java.util.Optional;

public class BfdFeature extends AbstractFeature {
    @Override
    public Optional<Switch.Feature> discover(IOFSwitch sw) {
        Optional<Switch.Feature> empty = Optional.empty();

        SwitchDescription description = sw.getSwitchDescription();
        if (description == null) {
            return empty;
        }
        if (!MANUFACTURER_NOVIFLOW.equals(description.getManufacturerDescription())) {
            return empty;
        }

        return Optional.of(Switch.Feature.BFD);
    }
}
