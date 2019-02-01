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

package org.openkilda.wfm.topology.discovery.model;

import org.openkilda.model.Isl;
import org.openkilda.wfm.topology.discovery.service.DiscoveryService;
import org.openkilda.wfm.topology.discovery.service.IPortReply;
import org.openkilda.wfm.topology.discovery.service.IUniIslReply;

public class UniIslSetupCommand extends UniIslCommand {
    private final Isl history;

    public UniIslSetupCommand(Endpoint endpoint, Isl history) {
        super(endpoint);
        this.history = history;
    }

    @Override
    public void apply(DiscoveryService service, IUniIslReply output) {
        service.uniIslSetup(getEndpoint(), history, output);
    }
}
