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
package org.openhab.io.hueemulation.internal.rest;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

import java.io.IOException;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.core.events.Event;
import org.openhab.core.items.GroupItem;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemCommandEvent;
import org.openhab.core.library.items.ColorItem;
import org.openhab.core.library.items.SwitchItem;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.io.hueemulation.internal.ConfigStore;
import org.openhab.io.hueemulation.internal.DeviceType;
import org.openhab.io.hueemulation.internal.HueEmulationConfig;
import org.openhab.io.hueemulation.internal.dto.HueGroupEntry;
import org.openhab.io.hueemulation.internal.dto.HueLightEntry;
import org.openhab.io.hueemulation.internal.dto.HueStateColorBulb;
import org.openhab.io.hueemulation.internal.dto.HueStatePlug;
import org.openhab.io.hueemulation.internal.rest.mocks.DummyItemRegistry;

/**
 * Tests for {@link LightsAndGroups}.
 *
 * @author David Graeff - Initial contribution
 */
@NonNullByDefault
public class LightsAndGroupsTests {
    protected @NonNullByDefault({}) CommonSetup commonSetup;
    protected @NonNullByDefault({}) ItemRegistry itemRegistry;
    protected @NonNullByDefault({}) ConfigStore cs;

    LightsAndGroups subject = new LightsAndGroups();

    @BeforeEach
    public void setUp() throws IOException {
        commonSetup = new CommonSetup(false);
        itemRegistry = new DummyItemRegistry();

        this.cs = commonSetup.cs;

        subject.cs = cs;
        subject.eventPublisher = commonSetup.eventPublisher;
        subject.userManagement = commonSetup.userManagement;
        subject.itemRegistry = itemRegistry;
        subject.activate();

        // Add simulated lights
        cs.ds.lights.put("1", new HueLightEntry(new SwitchItem("switch"), "switch", DeviceType.SwitchType));
        cs.ds.lights.put("2", new HueLightEntry(new ColorItem("color"), "color", DeviceType.ColorType));
        cs.ds.lights.put("3", new HueLightEntry(new ColorItem("white"), "white", DeviceType.WhiteTemperatureType));

        // Add group item
        cs.ds.groups.put("10",
                new HueGroupEntry("name", new GroupItem("white", new SwitchItem("switch")), DeviceType.SwitchType));

        commonSetup.start(new ResourceConfig().registerInstances(subject));
    }

    @AfterEach
    public void tearDown() throws Exception {
        commonSetup.dispose();
    }

    @Test
    public void addSwitchableByCategory() {
        SwitchItem item = new SwitchItem("switch1");
        item.setCategory("Light");
        itemRegistry.add(item);
        HueLightEntry device = cs.ds.lights.get(cs.mapItemUIDtoHueID(item));
        assertThat(device.item, is(item));
        assertThat(device.state, is(instanceOf(HueStatePlug.class)));
    }

    @Test
    public void addSwitchableByTag() {
        SwitchItem item = new SwitchItem("switch1");
        item.addTag(HueEmulationConfig.DEFAULT_SWITCHES_TAG);
        itemRegistry.add(item);
        HueLightEntry device = cs.ds.lights.get(cs.mapItemUIDtoHueID(item));
        assertThat(device.item, is(item));
        assertThat(device.state, is(instanceOf(HueStatePlug.class)));
    }

    @Test
    public void ignoreByTag() {
        SwitchItem item = new SwitchItem("switch1");
        item.addTags(HueEmulationConfig.DEFAULT_SWITCHES_TAG, HueEmulationConfig.DEFAULT_IGNORED_TAG); // The ignore tag
                                                                                                       // will win
        itemRegistry.add(item);
        HueLightEntry device = cs.ds.lights.get(cs.mapItemUIDtoHueID(item));
        assertThat(device, is(nullValue()));
    }

    @Test
    public void addGroupSwitchableByTag() {
        cs.exposeGroupsAsDevices = false;
        GroupItem item = new GroupItem("group1", new SwitchItem("switch1"));
        item.addTag(HueEmulationConfig.DEFAULT_SWITCHES_TAG);
        itemRegistry.add(item);
        HueGroupEntry device = cs.ds.groups.get(cs.mapItemUIDtoHueID(item));
        assertThat(device.groupItem, is(item));
        assertThat(device.action, is(instanceOf(HueStatePlug.class)));
    }

    @Test
    public void addDeviceAsGroupSwitchableByTag() {
        cs.exposeGroupsAsDevices = true;
        GroupItem item = new GroupItem("group1", new SwitchItem("switch1"));
        item.addTag(HueEmulationConfig.DEFAULT_SWITCHES_TAG);
        itemRegistry.add(item);
        HueLightEntry device = cs.ds.lights.get(cs.mapItemUIDtoHueID(item));
        assertThat(device.item, is(item));
        assertThat(device.state, is(instanceOf(HueStatePlug.class)));
    }

    @Test
    public void addGroupWithoutTypeByTag() {
        cs.exposeGroupsAsDevices = false;
        GroupItem item = new GroupItem("group1", null);
        item.addTag(HueEmulationConfig.DEFAULT_SWITCHES_TAG);

        itemRegistry.add(item);

        HueGroupEntry device = cs.ds.groups.get(cs.mapItemUIDtoHueID(item));
        assertThat(device.groupItem, is(item));
        assertThat(device.action, is(instanceOf(HueStatePlug.class)));
        assertThat(cs.ds.groups.get(cs.mapItemUIDtoHueID(item)).groupItem, is(item));
    }

    @Test
    public void removeGroupWithoutTypeAndTag() {
        cs.exposeGroupsAsDevices = false;

        String groupName = "group1";
        GroupItem item = new GroupItem(groupName, null);
        item.addTag(HueEmulationConfig.DEFAULT_SWITCHES_TAG);
        itemRegistry.add(item);

        String hueID = cs.mapItemUIDtoHueID(item);
        assertThat(cs.ds.groups.get(hueID), notNullValue());

        subject.updated(item, new GroupItem(groupName, null));

        assertThat(cs.ds.groups.get(hueID), nullValue());
    }

    @Test
    public void updateSwitchable() {
        commonSetup.enableHeuristics(false);
        SwitchItem item = new SwitchItem("switch1");
        item.setLabel("labelOld");
        item.addTag(HueEmulationConfig.DEFAULT_SWITCHES_TAG);
        itemRegistry.add(item);
        String hueID = cs.mapItemUIDtoHueID(item);
        HueLightEntry device = cs.ds.lights.get(hueID);
        assertThat(device.item, is(item));
        assertThat(device.state, is(instanceOf(HueStatePlug.class)));
        assertThat(device.name, is("labelOld"));

        SwitchItem newitem = new SwitchItem("switch1");
        newitem.setLabel("labelNew");
        newitem.addTag(HueEmulationConfig.DEFAULT_SWITCHES_TAG);
        subject.updated(item, newitem);
        device = cs.ds.lights.get(hueID);
        assertThat(device.item, is(newitem));
        assertThat(device.state, is(instanceOf(HueStatePlug.class)));
        assertThat(device.name, is("labelNew"));

        // Update with an item that has no tags anymore -> should be removed
        SwitchItem newitemWithoutTag = new SwitchItem("switch1");
        newitemWithoutTag.setLabel("labelNew2");
        subject.updated(newitem, newitemWithoutTag);

        device = cs.ds.lights.get(hueID);
        assertThat(device, nullValue());
    }

    @Test
    public void changeSwitchState() {
        assertThat(((HueStatePlug) cs.ds.lights.get("1").state).on, is(false));

        String body = "{'on':true}";
        Response response = commonSetup.client.target(commonSetup.basePath + "/testuser/lights/1/state").request()
                .put(Entity.json(body));
        assertEquals(200, response.getStatus());
        assertThat(response.readEntity(String.class), containsString("success"));
        assertThat(((HueStatePlug) cs.ds.lights.get("1").state).on, is(true));
        verify(commonSetup.eventPublisher).post(argThat((Event t) -> {
            assertThat(t.getPayload(), is("{\"type\":\"OnOff\",\"value\":\"ON\"}"));
            return true;
        }));
    }

    @Test
    public void changeGroupItemSwitchState() {
        assertThat(((HueStatePlug) cs.ds.groups.get("10").action).on, is(false));

        String body = "{'on':true}";
        Response response = commonSetup.client.target(commonSetup.basePath + "/testuser/groups/10/action").request()
                .put(Entity.json(body));
        assertEquals(200, response.getStatus());
        assertThat(response.readEntity(String.class), containsString("success"));
        assertThat(((HueStatePlug) cs.ds.groups.get("10").action).on, is(true));
        verify(commonSetup.eventPublisher).post(argThat((Event t) -> {
            assertThat(t.getPayload(), is("{\"type\":\"OnOff\",\"value\":\"ON\"}"));
            return true;
        }));
    }

    @Test
    public void changeOnValue() {
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).on, is(false));

        String body = "{'on':true}";
        Response response = commonSetup.client.target(commonSetup.basePath + "/testuser/lights/2/state").request()
                .put(Entity.json(body));
        assertEquals(200, response.getStatus());
        String entity = response.readEntity(String.class);
        assertThat(entity, is("[{\"success\":{\"/lights/2/state/on\":true}}]"));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).on, is(true));
    }

    @Test
    public void changeOnAndBriValues() {
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).on, is(false));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).bri, is(1));

        String body = "{'on':true,'bri':200}";
        Response response = commonSetup.client.target(commonSetup.basePath + "/testuser/lights/2/state").request()
                .put(Entity.json(body));
        assertEquals(200, response.getStatus());
        assertThat(response.readEntity(String.class), containsString("success"));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).on, is(true));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).bri, is(200));
    }

    @Test
    public void changeHueSatValues() {
        HueLightEntry hueDevice = cs.ds.lights.get("2");
        hueDevice.item.setState(OnOffType.ON);
        hueDevice.state.as(HueStateColorBulb.class).on = true;

        String body = "{'hue':1000,'sat':50}";
        Response response = commonSetup.client.target(commonSetup.basePath + "/testuser/lights/2/state").request()
                .put(Entity.json(body));
        assertEquals(200, response.getStatus());
        assertThat(response.readEntity(String.class), containsString("success"));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).on, is(true));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).hue, is(1000));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).sat, is(50));

        verify(commonSetup.eventPublisher).post(argThat(ce -> assertHueValue((ItemCommandEvent) ce, 1000)));
    }

    /**
     * Amazon echos are setting ct only, if commanded to turn a light white.
     */
    @Test
    public void changeCtValue() {
        HueLightEntry hueDevice = cs.ds.lights.get("2");
        hueDevice.item.setState(OnOffType.ON);
        hueDevice.state.as(HueStateColorBulb.class).on = true;

        String body = "{'ct':500}";
        Response response = commonSetup.client.target(commonSetup.basePath + "/testuser/lights/2/state").request()
                .put(Entity.json(body));
        assertEquals(200, response.getStatus());
        body = response.readEntity(String.class);
        assertThat(body, containsString("success"));
        assertThat(body, containsString("ct"));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).on, is(true));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).ct, is(500));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).sat, is(0));

        // Saturation is expected to be 0 -> white light
        verify(commonSetup.eventPublisher).post(argThat(ce -> assertSatValue((ItemCommandEvent) ce, 0)));
    }

    @Test
    public void switchOnWithXY() {
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).on, is(false));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).bri, is(1));

        String body = "{'on':true,'bri':200,'xy':[0.5119,0.4147]}";
        Response response = commonSetup.client.target(commonSetup.basePath + "/testuser/lights/2/state").request()
                .put(Entity.json(body));
        assertEquals(200, response.getStatus());
        assertThat(response.readEntity(String.class), containsString("success"));
        assertThat(response.readEntity(String.class), containsString("xy"));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).on, is(true));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).bri, is(200));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).xy[0], is(0.5119));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).xy[1], is(0.4147));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).colormode, is(HueStateColorBulb.ColorMode.xy));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).toHSBType().getHue().intValue(),
                is((int) 27.47722590981918));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).toHSBType().getSaturation().intValue(), is(88));
        assertThat(((HueStateColorBulb) cs.ds.lights.get("2").state).toHSBType().getBrightness().intValue(), is(78));
    }

    @Test
    public void allLightsAndSingleLight() {
        Response response = commonSetup.client.target(commonSetup.basePath + "/testuser/lights").request().get();
        assertEquals(200, response.getStatus());

        String body = response.readEntity(String.class);

        assertThat(body, containsString("switch"));
        assertThat(body, containsString("color"));
        assertThat(body, containsString("white"));

        // Single light access test
        response = commonSetup.client.target(commonSetup.basePath + "/testuser/lights/2").request().get();
        assertEquals(200, response.getStatus());
        body = response.readEntity(String.class);
        assertThat(body, containsString("color"));
    }

    private boolean assertHueValue(ItemCommandEvent ce, int hueValue) {
        assertThat(((HSBType) ce.getItemCommand()).getHue().intValue(), is(hueValue * 360 / HueStateColorBulb.MAX_HUE));
        return true;
    }

    private boolean assertSatValue(ItemCommandEvent ce, int satValue) {
        assertThat(((HSBType) ce.getItemCommand()).getSaturation().intValue(),
                is(satValue * 100 / HueStateColorBulb.MAX_SAT));
        return true;
    }
}
