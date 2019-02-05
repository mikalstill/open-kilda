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

package org.openkilda.wfm.topology.discovery.storm;

public enum ComponentId {
    MONOTONIC_TICK("monotonic.tick"),

    INPUT("input"),
    INPUT_DECODER("input.decoder"),

    WATCH_LIST("watch-list-handler"),

    FL_MONITOR("fl-monitor"),
    NETWORK_PRELOADER("network-preloader"),
    SWITCH_HANDLER("switch-handler"),
    PORT_HANDLER("port-handler"),
    BFD_PORT_HANDLER("bfd-port-handler"),
    UNI_ISL_HANDLER("uni-isl-handler"),
    ISL_HANDLER("isl-handler"),

    SPEAKER_ENCODER("speaker.encoder"),
    SPEAKER_OUTPUT("speaker.output");

    private final String value;

    ComponentId(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }
}
