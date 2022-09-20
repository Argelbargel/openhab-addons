/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.io.hueemulation.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * The configuration for {@link HueEmulationService}.
 *
 * @author David Graeff - Initial Contribution
 */
@NonNullByDefault
public class HueEmulationConfig {
    public boolean pairingEnabled = false;
    public static final String CONFIG_PAIRING_ENABLED = "pairingEnabled";

    /**
     * The Amazon echos have no means to recreate a new api key and they don't care about the 403-forbidden http status
     * code. If the addon has pruned its api-key list, echos will not be able to discover new devices. Set this option
     * to just create a new user on the fly.
     */
    public boolean createNewUserOnEveryEndpoint = false;
    public static final String CONFIG_CREATE_NEW_USER_ON_THE_FLY = "createNewUserOnEveryEndpoint";
    public boolean temporarilyEmulateV1bridge = false;
    public static final String CONFIG_EMULATE_V1 = "temporarilyEmulateV1bridge";
    public boolean permanentV1bridge = false;

    /** Pairing timeout in seconds */
    public int pairingTimeout = 60;
    /**
     * The field discoveryIps was named discoveryIp in the frontend for some time and thus user probably
     * have it in their local config saved under the non plural version.
     */
    public @Nullable String discoveryIp;
    public int discoveryHttpPort = 0;
    public boolean determineItemsHeuristically = true;
    public boolean exposeGroupsAsDevices = true;

    public static final String DEFAULT_SWITCHES_TAG = "Huemu_Switch";
    public static final String DEFAULT_COLOR_LIGHTS_TAG = "Huemu_ColorLight";
    public static final String DEFAULT_WHITE_LIGHTS_TAG = "Huemu_Light";
    public static final String DEFAULT_SENSORS_TAG = "Huemu_Sensor";
    public static final String DEFAULT_IGNORED_TAG = "Huemu_Ignored";

    /** Comma separated list of tags */
    public String restrictToTagsSwitches = DEFAULT_SWITCHES_TAG;
    /** Comma separated list of tags */
    public String restrictToTagsColorLights = DEFAULT_COLOR_LIGHTS_TAG;
    /** Comma separated list of tags */
    public String restrictToTagsWhiteLights = DEFAULT_WHITE_LIGHTS_TAG;
    /** Comma separated list of tags */
    public String restrictToTagsSensors = DEFAULT_SENSORS_TAG;
    /** Comma separated list of tags */
    public String ignoreItemsWithTags = DEFAULT_IGNORED_TAG;

    public static final String CONFIG_UUID = "uuid";
    public String uuid = "";
    public String devicename = "openHAB";
}
