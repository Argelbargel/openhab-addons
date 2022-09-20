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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.IllegalFormatException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.config.core.ConfigurableService;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.items.GenericItem;
import org.openhab.core.items.GroupItem;
import org.openhab.core.items.Item;
import org.openhab.core.items.Metadata;
import org.openhab.core.items.MetadataKey;
import org.openhab.core.items.MetadataRegistry;
import org.openhab.core.library.CoreItemFactory;
import org.openhab.core.net.CidrAddress;
import org.openhab.core.net.NetUtil;
import org.openhab.core.net.NetworkAddressService;
import org.openhab.io.hueemulation.internal.dto.HueAuthorizedConfig;
import org.openhab.io.hueemulation.internal.dto.HueDataStore;
import org.openhab.io.hueemulation.internal.dto.HueGroupEntry;
import org.openhab.io.hueemulation.internal.dto.HueLightEntry;
import org.openhab.io.hueemulation.internal.dto.HueRuleEntry;
import org.openhab.io.hueemulation.internal.dto.HueSensorEntry;
import org.openhab.io.hueemulation.internal.dto.response.HueSuccessGeneric;
import org.openhab.io.hueemulation.internal.dto.response.HueSuccessResponseStateChanged;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * This component sets up the hue data store and gets the service configuration.
 * It also determines the address for the upnp service by the given configuration.
 * <p>
 * Also manages the pairing timeout. The service is restarted after a pairing timeout, due to the ConfigAdmin
 * configuration change.
 * <p>
 * This is a central component and required by all other components and may not
 * depend on anything in this bundle.
 *
 * @author David Graeff - Initial contribution
 */
@Component(immediate = false, service = ConfigStore.class, configurationPid = HueEmulationService.CONFIG_PID)
@ConfigurableService(category = "io", label = "Hue Emulation", description_uri = "io:hueemulation")
@NonNullByDefault
public class ConfigStore {

    public static final String METAKEY = "HUEEMU";
    public static final String EVENT_ADDRESS_CHANGED = "HUE_EMU_CONFIG_ADDR_CHANGED";

    private static final String ITEM_TYPE_GROUP = "Group";
    private static final Set<String> ALLOWED_SENSOR_ITEM_TYPES = Set.of(CoreItemFactory.COLOR, CoreItemFactory.DIMMER,
            CoreItemFactory.ROLLERSHUTTER, CoreItemFactory.SWITCH, CoreItemFactory.CONTACT, CoreItemFactory.NUMBER);
    private static final Set<String> ALLOWED_LIGHT_ITEM_TYPES = Set.of(CoreItemFactory.COLOR, CoreItemFactory.DIMMER,
            CoreItemFactory.ROLLERSHUTTER, CoreItemFactory.SWITCH, ITEM_TYPE_GROUP);

    private final Logger logger = LoggerFactory.getLogger(ConfigStore.class);

    public HueDataStore ds = new HueDataStore();

    protected @NonNullByDefault({}) ScheduledExecutorService scheduler;
    private @Nullable ScheduledFuture<?> pairingOffFuture;
    private @Nullable ScheduledFuture<?> writeUUIDFuture;

    /**
     * This is the main gson instance, to be obtained by all components that operate on the dto data fields
     */
    public final Gson gson = new GsonBuilder().registerTypeAdapter(HueLightEntry.class, new HueLightEntry.Serializer())
            .registerTypeAdapter(HueSensorEntry.class, new HueSensorEntry.Serializer())
            .registerTypeAdapter(HueRuleEntry.Condition.class, new HueRuleEntry.SerializerCondition())
            .registerTypeAdapter(HueAuthorizedConfig.class, new HueAuthorizedConfig.Serializer())
            .registerTypeAdapter(HueSuccessGeneric.class, new HueSuccessGeneric.Serializer())
            .registerTypeAdapter(HueSuccessResponseStateChanged.class, new HueSuccessResponseStateChanged.Serializer())
            .registerTypeAdapter(HueGroupEntry.class, new HueGroupEntry.Serializer(this)).create();

    @Reference
    protected @NonNullByDefault({}) ConfigurationAdmin configAdmin;

    @Reference
    protected @NonNullByDefault({}) NetworkAddressService networkAddressService;

    @Reference
    protected @NonNullByDefault({}) MetadataRegistry metadataRegistry;

    @Reference
    protected @NonNullByDefault({}) EventAdmin eventAdmin;

    //// objects, set within activate()
    private Set<InetAddress> discoveryIps = Collections.emptySet();
    protected volatile @NonNullByDefault({}) HueEmulationConfig config;

    private Set<String> switchTags = Collections.emptySet();
    private Set<String> colorLightTags = Collections.emptySet();
    private Set<String> whiteLightTags = Collections.emptySet();
    private Set<String> sensorTags = Collections.emptySet();
    private Set<String> ignoredTags = Collections.emptySet();

    public boolean determineItemsHeuristically = true;
    public boolean exposeGroupsAsDevices = true;

    private int highestAssignedHueID = 1;

    private String hueIDPrefix = "";

    public ConfigStore() {
        scheduler = ThreadPoolManager.getScheduledPool(ThreadPoolManager.THREAD_POOL_NAME_COMMON);
    }

    /**
     * For test dependency injection
     *
     * @param networkAddressService The network address service
     * @param configAdmin The configuration admin service
     * @param metadataRegistry The metadataRegistry service
     */
    public ConfigStore(NetworkAddressService networkAddressService, ConfigurationAdmin configAdmin,
            @Nullable MetadataRegistry metadataRegistry, ScheduledExecutorService scheduler) {
        this.networkAddressService = networkAddressService;
        this.configAdmin = configAdmin;
        this.metadataRegistry = metadataRegistry;
        this.scheduler = scheduler;
    }

    @Activate
    public void activate(Map<String, Object> properties) {
        this.config = new Configuration(properties).as(HueEmulationConfig.class);

        determineHighestAssignedHueID();

        if (config.uuid.isEmpty()) {
            config.uuid = UUID.randomUUID().toString();
            writeUUIDFuture = scheduler.schedule(() -> {
                logger.info("No unique ID assigned yet. Assigning {} and restarting...", config.uuid);
                WriteConfig.setUUID(configAdmin, config.uuid);
            }, 100, TimeUnit.MILLISECONDS);
            return;
        } else {
            modified(properties);
        }
    }

    private @Nullable InetAddress byName(@Nullable String address) {
        if (address == null) {
            return null;
        }
        try {
            return InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            logger.warn("Given IP address could not be resolved: {}", address, e);
            return null;
        }
    }

    @Modified
    public void modified(Map<String, Object> properties) {
        this.config = new Configuration(properties).as(HueEmulationConfig.class);

        setIgnoredTags(config.ignoreItemsWithTags);
        setSwitchTags(config.restrictToTagsSwitches);
        setColorLightTags(config.restrictToTagsColorLights);
        setWhiteLightTags(config.restrictToTagsWhiteLights);
        setSensorTags(config.restrictToTagsSensors);
        exposeGroupsAsDevices = config.exposeGroupsAsDevices;
        determineItemsHeuristically = config.determineItemsHeuristically;

        // Use either the user configured
        InetAddress configuredAddress = null;
        int networkPrefixLength = 24; // Default for most networks: 255.255.255.0

        if (config.discoveryIp != null) {
            discoveryIps = Collections.unmodifiableSet(Stream.of(config.discoveryIp.split(",")).map(String::trim)
                    .map(this::byName).filter(e -> e != null).collect(Collectors.toSet()));
        } else {
            discoveryIps = new LinkedHashSet<>();
            configuredAddress = byName(networkAddressService.getPrimaryIpv4HostAddress());
            if (configuredAddress != null) {
                discoveryIps.add(configuredAddress);
            }
            for (CidrAddress a : NetUtil.getAllInterfaceAddresses()) {
                if (a.getAddress().equals(configuredAddress)) {
                    networkPrefixLength = a.getPrefix();
                } else {
                    discoveryIps.add(a.getAddress());
                }
            }
        }

        if (discoveryIps.isEmpty()) {
            try {
                logger.info("No discovery ip specified. Trying to determine the host address");
                configuredAddress = InetAddress.getLocalHost();
            } catch (Exception e) {
                logger.info("Host address cannot be determined. Trying loopback address");
                configuredAddress = InetAddress.getLoopbackAddress();
            }
        } else {
            configuredAddress = discoveryIps.iterator().next();
        }

        logger.info("Using discovery ip {}", configuredAddress.getHostAddress());

        // Get and apply configurations
        ds.config.createNewUserOnEveryEndpoint = config.createNewUserOnEveryEndpoint;
        ds.config.networkopenduration = config.pairingTimeout;
        ds.config.devicename = config.devicename;

        ds.config.uuid = config.uuid;
        ds.config.bridgeid = config.uuid.replace("-", "").toUpperCase();
        if (ds.config.bridgeid.length() > 12) {
            ds.config.bridgeid = ds.config.bridgeid.substring(0, 12);
        }

        hueIDPrefix = getHueIDPrefixFromUUID(config.uuid);

        if (config.permanentV1bridge) {
            ds.config.makeV1bridge();
        }

        setLinkbutton(config.pairingEnabled, config.createNewUserOnEveryEndpoint, config.temporarilyEmulateV1bridge);
        ds.config.mac = NetworkUtils.getMAC(configuredAddress);
        ds.config.ipaddress = getConfiguredHostAddress(configuredAddress);
        ds.config.netmask = networkPrefixLength < 32 ? NetUtil.networkPrefixLengthToNetmask(networkPrefixLength)
                : "255.255.255.0";

        if (eventAdmin != null) {
            eventAdmin.postEvent(new Event(EVENT_ADDRESS_CHANGED, Collections.emptyMap()));
        }
    }

    public void setIgnoredTags(String... tags) {
        ignoredTags = parseTagSet(tags);
    }

    public void setSwitchTags(String... tags) {
        switchTags = parseTagSet(tags);
    }

    public void setWhiteLightTags(String... tags) {
        whiteLightTags = parseTagSet(tags);
    }

    public void setColorLightTags(String... tags) {
        colorLightTags = parseTagSet(tags);
    }

    public void setSensorTags(String... tags) {
        sensorTags = parseTagSet(tags);
    }

    private Set<String> parseTagSet(String... values) {
        return Stream.of(values).flatMap(v -> Stream.of(v.split(",")).map(String::trim).map(String::toLowerCase))
                .collect(Collectors.toSet());
    }

    private String getConfiguredHostAddress(InetAddress configuredAddress) {
        String hostAddress = configuredAddress.getHostAddress();
        int percentIndex = hostAddress.indexOf("%");
        if (percentIndex != -1) {
            return hostAddress.substring(0, percentIndex);
        } else {
            return hostAddress;
        }
    }

    /**
     * Get the prefix used to create a unique id
     *
     * @param uuid The uuid
     * @return The prefix in the format of AA:BB:CC:DD:EE:FF:00:11 if uuid is a valid UUID, otherwise uuid is returned.
     */
    private String getHueIDPrefixFromUUID(final String uuid) {
        // Hue API example of a unique id is AA:BB:CC:DD:EE:FF:00:11-XX
        // XX is generated from the item.
        String prefix = uuid;
        try {
            // Generate prefix if uuid is a randomly generated UUID
            if (UUID.fromString(uuid).version() == 4) {
                final StringBuilder sb = new StringBuilder(23);
                sb.append(uuid, 0, 2).append(":").append(uuid, 2, 4).append(":").append(uuid, 4, 6).append(":")
                        .append(uuid, 6, 8).append(":").append(uuid, 9, 11).append(":").append(uuid, 11, 13).append(":")
                        .append(uuid, 14, 16).append(":").append(uuid, 16, 18);
                prefix = sb.toString().toUpperCase();
            }
        } catch (final IllegalArgumentException e) {
            // uuid is not a valid UUID
        }

        return prefix;
    }

    @Deactivate
    public void deactive(int reason) {
        ScheduledFuture<?> future = pairingOffFuture;
        if (future != null) {
            future.cancel(false);
        }
        future = writeUUIDFuture;
        if (future != null) {
            future.cancel(false);
        }
    }

    protected void determineHighestAssignedHueID() {
        for (Metadata metadata : metadataRegistry.getAll()) {
            if (!metadata.getUID().getNamespace().equals(METAKEY)) {
                continue;
            }
            try {
                int hueId = Integer.parseInt(metadata.getValue());
                if (hueId > highestAssignedHueID) {
                    highestAssignedHueID = hueId;
                }
            } catch (NumberFormatException e) {
                logger.warn("A non numeric hue ID '{}' was assigned. Ignoring!", metadata.getValue());
            }
        }
    }

    public boolean containsSwitchTag(Set<String> tags) {
        return containsTag(switchTags, tags);
    }

    public boolean containsWhiteLightTag(Set<String> tags) {
        return containsTag(whiteLightTags, tags);
    }

    public boolean containsColorLightTag(Set<String> tags) {
        return containsTag(colorLightTags, tags);
    }

    private boolean containsSensorTag(Set<String> tags) {
        return containsTag(sensorTags, tags);
    }

    private boolean containsIgnoredTag(Set<String> tags) {
        return containsTag(ignoredTags, tags);
    }

    public boolean isSensor(Item item) {
        if (!isGenericItemOfType(item, ALLOWED_SENSOR_ITEM_TYPES)) {
            return false;
        }

        Set<String> tags = item.getTags();
        if (containsIgnoredTag(tags)) {
            return false;
        }

        return containsSensorTag(tags) || determineItemsHeuristically;
    }

    public boolean isGroup(Item item) {
        Set<String> tags = item.getTags();

        if (!(item instanceof GroupItem) || containsIgnoredTag(tags)) {
            return false;
        }

        if (!exposeGroupsAsDevices) {
            return true;
        }

        return !(containsSwitchTag(tags) || containsWhiteLightTag(tags) || containsColorLightTag(tags)
                || containsSensorTag(tags));
    }

    public boolean isLight(Item item) {
        return isGenericItemOfType(item, ALLOWED_LIGHT_ITEM_TYPES) && !containsIgnoredTag(item.getTags());
    }

    private boolean isGenericItemOfType(Item item, Set<String> types) {
        return item instanceof GenericItem && types.contains(item.getType());
    }

    private boolean containsTag(Set<String> filter, Set<String> tags) {
        return tags.stream().map(String::toLowerCase).anyMatch(filter::contains);
    }

    /**
     * Although hue IDs are strings, a lot of implementations out there assume them to be numbers. Therefore
     * we map each item to a number and store that in the meta data provider.
     *
     * @param item The item to map
     * @return A stringified integer number
     */
    public String mapItemUIDtoHueID(Item item) {
        MetadataKey key = new MetadataKey(METAKEY, item.getUID());
        Metadata metadata = metadataRegistry.get(key);
        int hueId = 0;
        if (metadata != null) {
            try {
                hueId = Integer.parseInt(metadata.getValue());
            } catch (NumberFormatException e) {
                logger.warn("A non numeric hue ID '{}' was assigned. Ignore and reassign a different id now!",
                        metadata.getValue());
            }
        }
        if (hueId == 0) {
            ++highestAssignedHueID;
            hueId = highestAssignedHueID;
            metadataRegistry.add(new Metadata(key, String.valueOf(hueId), null));
        }

        return String.valueOf(hueId);
    }

    /**
     * Get the unique id
     *
     * @param hueId The item hueID
     * @return The unique id
     */
    public String getHueUniqueId(final String hueId) {
        String unique = hueId;
        try {
            unique = String.format("%02X", Integer.valueOf(hueId));
        } catch (final NumberFormatException | IllegalFormatException e) {
            // Use the hueId as is
        }

        return hueIDPrefix + "-" + unique;
    }

    public boolean isReady() {
        return !discoveryIps.isEmpty();
    }

    public HueEmulationConfig getConfig() {
        return config;
    }

    public int getHighestAssignedHueID() {
        return highestAssignedHueID;
    }

    /**
     * Sets the link button state.
     *
     * Starts a pairing timeout thread if set to true.
     * Stops any already running timers.
     *
     * @param linkbutton New link button state
     */
    public void setLinkbutton(boolean linkbutton, boolean createUsersOnEveryEndpoint,
            boolean temporarilyEmulateV1bridge) {
        ds.config.linkbutton = linkbutton;
        config.createNewUserOnEveryEndpoint = createUsersOnEveryEndpoint;
        if (temporarilyEmulateV1bridge) {
            ds.config.makeV1bridge();
        } else if (!config.permanentV1bridge) {
            ds.config.makeV2bridge();
        }
        ScheduledFuture<?> future = pairingOffFuture;
        if (future != null) {
            future.cancel(false);
        }
        if (!linkbutton) {
            logger.info("Hue Emulation pairing disabled");
            return;
        }

        logger.info("Hue Emulation pairing enabled for {}s", ds.config.networkopenduration);
        pairingOffFuture = scheduler.schedule(() -> {
            logger.info("Hue Emulation disable pairing...");
            if (!config.permanentV1bridge) { // Restore bridge version
                ds.config.makeV2bridge();
            }
            config.createNewUserOnEveryEndpoint = false;
            config.temporarilyEmulateV1bridge = false;
            WriteConfig.unsetPairingMode(configAdmin);
        }, ds.config.networkopenduration * 1000, TimeUnit.MILLISECONDS);
    }

    public Set<InetAddress> getDiscoveryIps() {
        return discoveryIps;
    }
}
