/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.telecom.tests;

import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.os.Parcel;
import android.telecom.Log;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.os.SomeArgs;
import com.android.server.telecom.BluetoothHeadsetProxy;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.Timeouts;
import com.android.server.telecom.bluetooth.BluetoothDeviceManager;
import com.android.server.telecom.bluetooth.BluetoothRouteManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(JUnit4.class)
public class BluetoothRouteManagerTest extends TelecomTestCase {
    private static final int TEST_TIMEOUT = 1000;
    static final BluetoothDevice DEVICE1 = makeBluetoothDevice("00:00:00:00:00:01");
    static final BluetoothDevice DEVICE2 = makeBluetoothDevice("00:00:00:00:00:02");
    static final BluetoothDevice DEVICE3 = makeBluetoothDevice("00:00:00:00:00:03");

    @Mock private BluetoothDeviceManager mDeviceManager;
    @Mock private BluetoothHeadsetProxy mHeadsetProxy;
    @Mock private Timeouts.Adapter mTimeoutsAdapter;
    @Mock private BluetoothRouteManager.BluetoothStateListener mListener;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @SmallTest
    @Test
    public void testConnectHfpRetryWhileNotConnected() {
        BluetoothRouteManager sm = setupStateMachine(
                BluetoothRouteManager.AUDIO_OFF_STATE_NAME, null);
        setupConnectedDevices(new BluetoothDevice[]{DEVICE1}, null);
        when(mTimeoutsAdapter.getRetryBluetoothConnectAudioBackoffMillis(
                nullable(ContentResolver.class))).thenReturn(0L);
        when(mHeadsetProxy.connectAudio(nullable(String.class))).thenReturn(false);
        executeRoutingAction(sm, BluetoothRouteManager.CONNECT_HFP, null);
        // Wait 3 times: for the first connection attempt, the retry attempt,
        // the second retry, and once more to make sure there are only three attempts.
        waitForHandlerAction(sm.getHandler(), TEST_TIMEOUT);
        waitForHandlerAction(sm.getHandler(), TEST_TIMEOUT);
        waitForHandlerAction(sm.getHandler(), TEST_TIMEOUT);
        waitForHandlerAction(sm.getHandler(), TEST_TIMEOUT);
        verify(mHeadsetProxy, times(3)).connectAudio(DEVICE1.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_OFF_STATE_NAME, sm.getCurrentState().getName());
        sm.getHandler().removeMessages(BluetoothRouteManager.CONNECTION_TIMEOUT);
        sm.quitNow();
    }

    @SmallTest
    @Test
    public void testConnectHfpRetryWhileConnectedToAnotherDevice() {
        BluetoothRouteManager sm = setupStateMachine(
                BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX, DEVICE1);
        setupConnectedDevices(new BluetoothDevice[]{DEVICE1, DEVICE2}, null);
        when(mTimeoutsAdapter.getRetryBluetoothConnectAudioBackoffMillis(
                nullable(ContentResolver.class))).thenReturn(0L);
        when(mHeadsetProxy.connectAudio(nullable(String.class))).thenReturn(false);
        executeRoutingAction(sm, BluetoothRouteManager.CONNECT_HFP, DEVICE2.getAddress());
        // Wait 3 times: the first connection attempt is accounted for in executeRoutingAction,
        // so wait twice for the retry attempt, again to make sure there are only three attempts,
        // and once more for good luck.
        waitForHandlerAction(sm.getHandler(), TEST_TIMEOUT);
        waitForHandlerAction(sm.getHandler(), TEST_TIMEOUT);
        waitForHandlerAction(sm.getHandler(), TEST_TIMEOUT);
        waitForHandlerAction(sm.getHandler(), TEST_TIMEOUT);
        verify(mHeadsetProxy, times(3)).connectAudio(DEVICE2.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX
                        + ":" + DEVICE1.getAddress(),
                sm.getCurrentState().getName());
        sm.getHandler().removeMessages(BluetoothRouteManager.CONNECTION_TIMEOUT);
        sm.quitNow();
    }

    @SmallTest
    @Test
    public void testProperFallbackOrder1() {
        // Device 1, 2, 3 are connected in that order. Device 1 is activated, then device 2.
        // Disconnect device 2, verify fallback to device 1. Disconnect device 1, fallback to
        // device 3.
        BluetoothRouteManager sm = setupStateMachine(
                BluetoothRouteManager.AUDIO_OFF_STATE_NAME, null);
        setupConnectedDevices(new BluetoothDevice[]{DEVICE3, DEVICE2, DEVICE1}, null);
        executeRoutingAction(sm, BluetoothRouteManager.CONNECT_HFP, DEVICE1.getAddress());
        verify(mHeadsetProxy, times(1)).connectAudio(DEVICE1.getAddress());

        setupConnectedDevices(new BluetoothDevice[]{DEVICE3, DEVICE2, DEVICE1}, DEVICE1);
        executeRoutingAction(sm, BluetoothRouteManager.HFP_IS_ON, DEVICE1.getAddress());

        executeRoutingAction(sm, BluetoothRouteManager.CONNECT_HFP, DEVICE2.getAddress());
        verify(mHeadsetProxy, times(1)).connectAudio(DEVICE2.getAddress());

        setupConnectedDevices(new BluetoothDevice[]{DEVICE3, DEVICE2, DEVICE1}, DEVICE2);
        executeRoutingAction(sm, BluetoothRouteManager.HFP_IS_ON, DEVICE2.getAddress());
        // Disconnect device 2
        setupConnectedDevices(new BluetoothDevice[]{DEVICE3, DEVICE1}, null);
        executeRoutingAction(sm, BluetoothRouteManager.LOST_DEVICE, DEVICE2.getAddress());
        // Verify that we've fallen back to device 1
        verify(mHeadsetProxy, times(2)).connectAudio(DEVICE1.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX
                        + ":" + DEVICE1.getAddress(),
                sm.getCurrentState().getName());
        setupConnectedDevices(new BluetoothDevice[]{DEVICE3, DEVICE1}, DEVICE1);
        executeRoutingAction(sm, BluetoothRouteManager.HFP_IS_ON, DEVICE1.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX
                        + ":" + DEVICE1.getAddress(),
                sm.getCurrentState().getName());

        // Disconnect device 1
        setupConnectedDevices(new BluetoothDevice[]{DEVICE3}, null);
        executeRoutingAction(sm, BluetoothRouteManager.LOST_DEVICE, DEVICE1.getAddress());
        // Verify that we've fallen back to device 3
        verify(mHeadsetProxy, times(1)).connectAudio(DEVICE3.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX
                        + ":" + DEVICE3.getAddress(),
                sm.getCurrentState().getName());
        setupConnectedDevices(new BluetoothDevice[]{DEVICE3}, DEVICE3);
        executeRoutingAction(sm, BluetoothRouteManager.HFP_IS_ON, DEVICE3.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX
                        + ":" + DEVICE3.getAddress(),
                sm.getCurrentState().getName());

        sm.getHandler().removeMessages(BluetoothRouteManager.CONNECTION_TIMEOUT);
        sm.quitNow();
    }

    @SmallTest
    @Test
    public void testProperFallbackOrder2() {
        // Device 1, 2, 3 are connected in that order. Device 3 is activated.
        // Disconnect device 3, verify fallback to device 2. Disconnect device 2, fallback to
        // device 1.
        BluetoothRouteManager sm = setupStateMachine(
                BluetoothRouteManager.AUDIO_OFF_STATE_NAME, null);
        setupConnectedDevices(new BluetoothDevice[]{DEVICE3, DEVICE2, DEVICE1}, null);
        executeRoutingAction(sm, BluetoothRouteManager.CONNECT_HFP, DEVICE3.getAddress());
        verify(mHeadsetProxy, times(1)).connectAudio(DEVICE3.getAddress());

        setupConnectedDevices(new BluetoothDevice[]{DEVICE3, DEVICE2, DEVICE1}, DEVICE3);
        executeRoutingAction(sm, BluetoothRouteManager.HFP_IS_ON, DEVICE3.getAddress());

        // Disconnect device 2
        setupConnectedDevices(new BluetoothDevice[]{DEVICE2, DEVICE1}, null);
        executeRoutingAction(sm, BluetoothRouteManager.LOST_DEVICE, DEVICE3.getAddress());
        // Verify that we've fallen back to device 2
        verify(mHeadsetProxy, times(1)).connectAudio(DEVICE2.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX
                        + ":" + DEVICE2.getAddress(),
                sm.getCurrentState().getName());
        setupConnectedDevices(new BluetoothDevice[]{DEVICE2, DEVICE1}, DEVICE2);
        executeRoutingAction(sm, BluetoothRouteManager.HFP_IS_ON, DEVICE2.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX
                        + ":" + DEVICE2.getAddress(),
                sm.getCurrentState().getName());

        // Disconnect device 2
        setupConnectedDevices(new BluetoothDevice[]{DEVICE1}, null);
        executeRoutingAction(sm, BluetoothRouteManager.LOST_DEVICE, DEVICE2.getAddress());
        // Verify that we've fallen back to device 1
        verify(mHeadsetProxy, times(1)).connectAudio(DEVICE1.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTING_STATE_NAME_PREFIX
                        + ":" + DEVICE1.getAddress(),
                sm.getCurrentState().getName());
        setupConnectedDevices(new BluetoothDevice[]{DEVICE1}, DEVICE1);
        executeRoutingAction(sm, BluetoothRouteManager.HFP_IS_ON, DEVICE1.getAddress());
        assertEquals(BluetoothRouteManager.AUDIO_CONNECTED_STATE_NAME_PREFIX
                        + ":" + DEVICE1.getAddress(),
                sm.getCurrentState().getName());

        sm.getHandler().removeMessages(BluetoothRouteManager.CONNECTION_TIMEOUT);
        sm.quitNow();
    }

    private BluetoothRouteManager setupStateMachine(String initialState,
            BluetoothDevice initialDevice) {
        resetMocks();
        BluetoothRouteManager sm = new BluetoothRouteManager(mContext,
                new TelecomSystem.SyncRoot() { }, mDeviceManager, mTimeoutsAdapter);
        sm.setListener(mListener);
        sm.setInitialStateForTesting(initialState, initialDevice);
        waitForHandlerAction(sm.getHandler(), TEST_TIMEOUT);
        resetMocks();
        return sm;
    }

    private void setupConnectedDevices(BluetoothDevice[] devices, BluetoothDevice activeDevice) {
        when(mDeviceManager.getNumConnectedDevices()).thenReturn(devices.length);
        when(mDeviceManager.getConnectedDevices()).thenReturn(Arrays.asList(devices));
        when(mHeadsetProxy.getConnectedDevices()).thenReturn(Arrays.asList(devices));
        if (activeDevice != null) {
            when(mHeadsetProxy.isAudioConnected(eq(activeDevice))).thenReturn(true);
        }
        doAnswer(invocation -> {
            BluetoothDevice first = getFirstExcluding(devices,
                    (String) invocation.getArguments()[0]);
            return first == null ? null : first.getAddress();
        }).when(mDeviceManager).getMostRecentlyConnectedDevice(nullable(String.class));
    }

    static void executeRoutingAction(BluetoothRouteManager brm, int message, String
            device) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = Log.createSubsession();
        args.arg2 = device;
        brm.sendMessage(message, args);
        waitForHandlerAction(brm.getHandler(), TEST_TIMEOUT);
    }

    public static BluetoothDevice makeBluetoothDevice(String address) {
        Parcel p1 = Parcel.obtain();
        p1.writeString(address);
        p1.setDataPosition(0);
        BluetoothDevice device = BluetoothDevice.CREATOR.createFromParcel(p1);
        p1.recycle();
        return device;
    }

    private void resetMocks() {
        reset(mDeviceManager, mListener, mHeadsetProxy, mTimeoutsAdapter);
        when(mDeviceManager.getHeadsetService()).thenReturn(mHeadsetProxy);
        when(mHeadsetProxy.connectAudio(nullable(String.class))).thenReturn(true);
        when(mTimeoutsAdapter.getRetryBluetoothConnectAudioBackoffMillis(
                nullable(ContentResolver.class))).thenReturn(100000L);
        when(mTimeoutsAdapter.getBluetoothPendingTimeoutMillis(
                nullable(ContentResolver.class))).thenReturn(100000L);
    }

    private static BluetoothDevice getFirstExcluding(
            BluetoothDevice[] devices, String excludeAddress) {
        for (BluetoothDevice x : devices) {
            if (!Objects.equals(excludeAddress, x.getAddress())) {
                return x;
            }
        }
        return null;
    }
}