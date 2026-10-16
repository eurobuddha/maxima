package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.net.ReachabilityManager;
import java.util.prefs.Preferences;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopNetworkLanguageTest {
    @Test public void reachabilityDisplayPreservesAddressesAndProtocolValues() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            Preferences old=Language.usePreferences(new DesktopLanguageTest.MemoryPreferences());
            try {
                String address="203.0.113.7:9536";
                String[] details={"not started","listener not running","network changed during verification",
                        "verifying "+address+" (manual forward)…",
                        "manual forward: port 9536 is not open from outside — check the router rule",
                        "reachable at "+address+" (manual forward)",
                        "verifying "+address+" from outside…",
                        "reachable at "+address+" via upnp",
                        "lease renewed for "+address};
                for(String code:new String[]{"ru","zh-Hans","en"}){
                    Language.choose(code);
                    for(String detail:details){
                        String result=NetworkPanel.reachDetail(detail);
                        if("en".equals(code))assertEquals(detail,result);
                        else assertNotEquals(detail,result);
                        if(detail.contains(address))assertTrue(result.contains(address));
                    }
                    assertEquals(Language.text("nativeNetwork.mapping"),NetworkPanel.reachState(ReachabilityManager.State.MAPPING));
                    assertEquals(Language.text("nativeNetwork.probing"),NetworkPanel.reachState(ReachabilityManager.State.PROBING));
                    assertEquals(Language.text("nativeNetwork.directlyReachable"),NetworkPanel.reachState(ReachabilityManager.State.ADVERTISED));
                    assertEquals("MAPPING",ReachabilityManager.State.MAPPING.name());
                    assertEquals("unknown diagnostic",NetworkPanel.reachDetail("unknown diagnostic"));
                    assertEquals("",NetworkPanel.reachDetail(null));
                    String literal="{count} $1 <name>:9536";
                    assertTrue(NetworkPanel.reachDetail("reachable at "+literal+" via upnp").contains(literal));
                }
            }finally{Language.usePreferences(old);Language.applyDialogLabels();}
        });
    }
}
