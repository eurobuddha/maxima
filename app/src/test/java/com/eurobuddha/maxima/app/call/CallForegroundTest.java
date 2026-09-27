package com.eurobuddha.maxima.app.call;

import android.Manifest;
import android.app.Service;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CallForegroundTest {
    @Test public void idleServiceNeverRequestsMediaAndVideoIsOptIn() {
        int special = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        int mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        int cam = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        assertEquals(special, CallForeground.types(35, false, true));
        assertEquals(special | mic, CallForeground.types(35, true, false));
        assertEquals(special | mic | cam, CallForeground.types(35, true, true));
        assertEquals(mic | cam, CallForeground.types(30, true, true));
        assertEquals(0, CallForeground.types(29, true, true));
    }
    @Test public void deniedPermissionOrPromotionCannotPublishActiveMedia() {
        Service service = mock(Service.class);
        CallForeground f = new CallForeground(service, 1);
        when(service.checkSelfPermission(Manifest.permission.RECORD_AUDIO)).thenReturn(PackageManager.PERMISSION_DENIED);
        try { f.start(false, null); fail("denied microphone"); } catch (SecurityException expected) { }
        assertFalse(f.active());
        when(service.checkSelfPermission(Manifest.permission.RECORD_AUDIO)).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(service.checkSelfPermission(Manifest.permission.CAMERA)).thenReturn(PackageManager.PERMISSION_DENIED);
        try { f.start(true, null); fail("denied camera"); } catch (SecurityException expected) { }
        assertFalse(f.active());
        doThrow(new SecurityException("background")).when(service).startForeground(1, null);
        try { f.start(false, null); fail("OS refused promotion"); } catch (SecurityException expected) { }
        assertFalse(f.active());
    }
    @Test public void endingCallRetiresMediaState() {
        Service service = mock(Service.class);
        CallForeground f = new CallForeground(service, 1);
        f.start(false, null); assertTrue(f.active());
        f.stop(null); assertFalse(f.active());
    }
}
