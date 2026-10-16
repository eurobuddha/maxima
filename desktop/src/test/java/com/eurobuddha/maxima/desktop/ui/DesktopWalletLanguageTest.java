package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.desktop.wallet.DesktopWalletLedger;
import java.awt.Component;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopWalletLanguageTest {
    @Test public void paymentStatusTranslationKeepsValidationAndProtocolStringsStable() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            Preferences old=Language.usePreferences(new DesktopLanguageTest.MemoryPreferences());
            try{
                String[] statuses={"Selecting coins…","Signing on this device…","Publishing via gateway…",
                        "Publishing via connected node…","Not enough confirmed funds.","Sent ⛏ (mining into a block)",
                        "Send failed: coins timeout","Couldn't open wallet: wallet unavailable"};
                for(String code:new String[]{"en","ru","zh-Hans"}){
                    Language.choose(code);
                    for(String value:statuses){
                        String display=WalletPanel.systemText(value);
                        if("en".equals(code))assertEquals(value,display);else assertNotEquals(value,display);
                    }
                    assertEquals("unknown diagnostic $1 {count}",WalletPanel.systemText("unknown diagnostic $1 {count}"));
                    assertEquals("That looks like a burn address.",DesktopChatLanguageTest.call(WalletPanel.class,"validateRecipient",new Class<?>[]{String.class},"Mx00"));
                    assertNull(DesktopChatLanguageTest.call(WalletPanel.class,"validateRecipient",new Class<?>[]{String.class},"0x"+"11".repeat(32)));
                    assertEquals("That's the burn address.",DesktopChatLanguageTest.call(WalletPanel.class,"validateRecipient",new Class<?>[]{String.class},"0x"+"00".repeat(32)));
                }
            }catch(Exception e){throw new AssertionError(e);}
            finally{Language.usePreferences(old);Language.applyDialogLabels();}
        });
    }

    @Test public void walletRowsKeepExactAmountsAndLiteralTokenNamesWhileDatesChange() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            Preferences old=Language.usePreferences(new DesktopLanguageTest.MemoryPreferences());
            try{
                WalletPanel wallet=new WalletPanel(null,new Theme(Theme.Mode.LIGHT,100));
                DesktopWalletLedger.Row entry=new DesktopWalletLedger.Row();
                entry.amount="1.234567890123456789";entry.token="Balance 中文";entry.who="Mx-Русский-中文";entry.time=1767225600000L;
                JPanel root=new JPanel();
                root.add((Component)DesktopChatLanguageTest.call(wallet,"historyRow",new Class<?>[]{DesktopWalletLedger.Row.class},entry));
                org.json.JSONObject token=new org.json.JSONObject().put("tokenid","synthetic").put("token",new org.json.JSONObject().put("name","Send 中文")).put("confirmed","0.000000000123456789");
                root.add((Component)DesktopChatLanguageTest.call(wallet,"tokenCard",new Class<?>[]{org.json.JSONObject.class},token));
                List<Component> widgets=DesktopChatLanguageTest.descendants(root);
                for(String code:new String[]{"ru","zh-Hans","en"}){
                    Language.choose(code);Language.refresh(root);
                    assertEquals(widgets,DesktopChatLanguageTest.descendants(root));
                    assertTrue(widgets.stream().anyMatch(c->c instanceof DKit.WrapText&&((DKit.WrapText)c).getText().equals("+1.234567890123456789 Balance 中文")));
                    assertTrue(DesktopChatLanguageTest.hasLabel(root,Language.date(entry.time,"dd MMM HH:mm")));
                    assertTrue(widgets.stream().anyMatch(c->c instanceof JLabel&&((JLabel)c).getText().contains("Send 中文")));
                    assertTrue(widgets.stream().anyMatch(c->c instanceof DKit.WrapText&&((DKit.WrapText)c).getText().equals("0.000000000123456789")));
                    assertEquals("Send 中文",DesktopChatLanguageTest.call(WalletPanel.class,"tokenTitle",new Class<?>[]{org.json.JSONObject.class},token));
                }
                assertEquals("1.234567890123456789",entry.amount);
                assertEquals("A😀…",DesktopChatLanguageTest.call(WalletPanel.class,"clip",new Class<?>[]{String.class,int.class},"A😀BC",3));
            }catch(Exception e){throw new AssertionError(e);}
            finally{Language.usePreferences(old);Language.applyDialogLabels();}
        });
    }
}
