package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.desktop.wallet.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Real wallet UI with a synthetic wallet and ledger. No HTTP, signatures or publication. */
public final class DesktopWalletLanguagePreview {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void layout(Container root){root.invalidate();root.doLayout();for(Component c:root.getComponents())if(c instanceof Container)layout((Container)c);}
    private static void capture(Container root,Path out,String name)throws Exception{
        for(int i=0;i<6;i++)layout(root);
        BufferedImage image=new BufferedImage(root.getWidth(),root.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();root.printAll(g);g.dispose();ImageIO.write(image,"png",out.resolve(name+".png").toFile());
        for (Component component : DesktopChatLanguageTest.descendants(root)) {
            if (!(component instanceof DKit.WrapText) || !component.isVisible()) continue;
            DKit.WrapText text = (DKit.WrapText) component;
            java.awt.geom.Rectangle2D end = text.modelToView2D(text.getDocument().getLength());
            check(text.getWidth() > 0 && end != null && end.getMaxY() <= text.getHeight() + 1,
                    "Full wallet value clipped in " + name);
        }
    }
    private static List<JTextField> inputs(Container root){
        List<JTextField> result=new ArrayList<>();
        for(Component c:DesktopChatLanguageTest.descendants(root))if(c instanceof JTextField)result.add((JTextField)c);
        return result;
    }
    public static void main(String[] args)throws Exception{
        System.setProperty("java.util.prefs.PreferencesFactory",DesktopLanguagePreview.MemoryFactory.class.getName());
        java.net.URL.setURLStreamHandlerFactory(protocol->("http".equals(protocol)||"https".equals(protocol))?new java.net.URLStreamHandler(){
            protected java.net.URLConnection openConnection(java.net.URL url){throw new AssertionError("Network disabled in wallet fixture");}
        }:null);
        Path out=Paths.get(args[0]);Files.createDirectories(out);
        Path data=Files.createTempDirectory(out,"synthetic-wallet-");Files.writeString(data.resolve("relays-builtin.txt"),"off");
        String phrase=String.join(" ",Bip39.fromEntropy(new byte[32]));
        DesktopNode node=new DesktopNode(MaximaIdentity.fromPhrase(phrase),data,"Wallet fixture");
        java.io.File walletDir=data.resolve("wallet").toFile();
        DesktopWallet wallet=DesktopWallet.open(phrase,walletDir);wallet.ensureAddress();
        new DesktopKeyUses(walletDir,DesktopWallet.USES_NAMESPACE).recordExternalUses(DesktopWallet.KEY_INDEX,37);
        byte[] counterA=Files.readAllBytes(data.resolve("wallet/keyuses_a.properties"));
        byte[] counterB=Files.readAllBytes(data.resolve("wallet/keyuses_b.properties"));
        DesktopWalletPublisher publisher=new DesktopWalletPublisher(null);
        DesktopWalletLedger ledger=new DesktopWalletLedger(walletDir);
        ledger.add(true,"1.234567890123456789","MINIMA","Mx-synthetic-中文","synthetic-tx-sent","0x00");
        ledger.add(false,"0.000000000123456789","Token 中文","Mx-synthetic-Русский","synthetic-tx-received","synthetic-token");
        try{
            SwingUtilities.invokeAndWait(()->{
                MaximaWindow window=new MaximaWindow(node,new Theme(Theme.Mode.LIGHT,Integer.getInteger("parlons.fixture.font",100)));
                try{
                    WalletPanel panel=window.wallet();
                    DesktopChatLanguageTest.set(panel,"mWallet",wallet);
                    DesktopChatLanguageTest.set(panel,"mPub",publisher);
                    DesktopChatLanguageTest.set(panel,"mLedger",ledger);
                    DesktopChatLanguageTest.set(panel,"mBuilt",true);
                    DesktopChatLanguageTest.set(panel,"mLastFetch",Long.MAX_VALUE);
                    DesktopChatLanguageTest.set(panel,"mConf","123.4567890123456789");
                    DesktopChatLanguageTest.set(panel,"mSpend","120.4567890123456789");
                    org.json.JSONObject token=new org.json.JSONObject().put("tokenid","synthetic-token").put("token",new org.json.JSONObject().put("name","Balance 中文 Русский")).put("confirmed","0.000000000123456789");
                    DesktopChatLanguageTest.set(panel,"mBalanceArr",new org.json.JSONArray().put(new org.json.JSONObject().put("tokenid","0x00")).put(token));
                    DesktopChatLanguageTest.call(panel,"buildScaffold",new Class<?>[0]);window.select(2);
                    DesktopChatLanguageTest.call(panel,"showSend",new Class<?>[0]);
                    JDialog send=(JDialog)window.frame().getOwnedWindows()[0];
                    List<JTextField> fields=inputs(send);
                    fields.get(0).setText("0x"+"11".repeat(32));fields.get(0).select(3,8);
                    fields.get(1).setText("12.34567890123456789");fields.get(1).select(2,7);
                    Object[] docs=fields.stream().map(JTextField::getDocument).toArray();
                    String[] drafts=fields.stream().map(JTextField::getText).toArray(String[]::new);
                    List<Component> sendWidgets=DesktopChatLanguageTest.descendants(send.getContentPane());
                    DKit.HoverButton sign=(DKit.HoverButton)sendWidgets.stream().filter(c->c instanceof DKit.HoverButton).findFirst().orElseThrow();
                    sign.setButtonEnabled(false); // in-flight visual state only; no payment is started
                    DesktopChatLanguageTest.call(panel,"showReceive",new Class<?>[0]);
                    DesktopChatLanguageTest.call(panel,"showTokenDetail",new Class<?>[]{org.json.JSONObject.class},token);
                    DesktopChatLanguageTest.call(panel,"showHistoryDetail",new Class<?>[]{DesktopWalletLedger.Row.class},ledger.list().get(0));
                    List<JDialog> dialogs=new ArrayList<>();
                    for(Window owned:window.frame().getOwnedWindows())if(owned instanceof JDialog)dialogs.add((JDialog)owned);
                    for(String code:new String[]{"en","ru","zh-Hans"}){
                        Language.choose(code);
                        for(int i=0;i<fields.size();i++){
                            check(fields.get(i).getDocument()==docs[i]&&fields.get(i).getText().equals(drafts[i]),"Payment draft changed");
                            check(fields.get(i).getSelectionStart()==(i==0?3:2)&&fields.get(i).getSelectionEnd()==(i==0?8:7),"Payment selection changed");
                        }
                        check(sendWidgets.equals(DesktopChatLanguageTest.descendants(send.getContentPane())),"Payment form rebuilt");
                        check(!(Boolean)DesktopChatLanguageTest.field(sign,"enabledFlag"),"Language switch re-enabled payment");
                        check(DesktopChatLanguageTest.field(panel,"mWallet")==wallet&&DesktopChatLanguageTest.field(panel,"mPub")==publisher,"Wallet or publisher replaced");
                        check(wallet.uses(walletDir)==37,"Key-use counter changed");
                        for(int width:new int[]{430,1040}){
                            window.frame().setSize(width,820);window.frame().getContentPane().setSize(width,820);panel.onWidth(width);
                            capture(window.frame().getContentPane(),out,"desktop-wallet-"+code+"-"+width);
                            JScrollPane scroll=(JScrollPane)panel.getComponent(0);scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                            capture(window.frame().getContentPane(),out,"desktop-wallet-bottom-"+code+"-"+width);scroll.getVerticalScrollBar().setValue(0);
                        }
                        for(int i=0;i<dialogs.size();i++){
                            check(i==2?dialogs.get(i).getTitle().equals("Balance 中文 Русский"):true,"Token title translated");
                            capture(dialogs.get(i).getContentPane(),out,"desktop-wallet-dialog-"+i+"-"+code);
                        }
                    }
                    DesktopChatLanguageTest.set(panel,"mSeg",1);DesktopChatLanguageTest.set(panel,"mHistFilter",2);
                    DesktopChatLanguageTest.call(panel,"rebuildPanes",new Class<?>[0]);
                    Object history=DesktopChatLanguageTest.field(panel,"mPaneHolder");
                    List<Component> rows=DesktopChatLanguageTest.descendants((Container)history);
                    for(String code:new String[]{"en","ru","zh-Hans"}){
                        Language.choose(code);check(rows.equals(DesktopChatLanguageTest.descendants((Container)history)),"History rebuilt");
                        check((Integer)DesktopChatLanguageTest.field(panel,"mHistFilter")==2,"History filter changed");
                        window.frame().setSize(430,820);window.frame().getContentPane().setSize(430,820);
                        capture(window.frame().getContentPane(),out,"desktop-wallet-history-"+code);
                    }
                    for(JDialog dialog:dialogs)dialog.dispose();
                    System.out.println("PASS: same real synthetic wallet/publisher/key-use count, payment draft/selection/disabled state and history filter across en/ru/zh-Hans; no network or signing");
                }catch(Exception e){throw new RuntimeException(e);}
                finally{
                    try{((Timer)DesktopChatLanguageTest.field(window,"mBeat")).stop();}catch(Exception ignored){}
                    window.frame().dispose();
                }
            });
            check(java.util.Arrays.equals(counterA,Files.readAllBytes(data.resolve("wallet/keyuses_a.properties"))),"Counter A changed");
            check(java.util.Arrays.equals(counterB,Files.readAllBytes(data.resolve("wallet/keyuses_b.properties"))),"Counter B changed");
        }finally{publisher.shutdown();node.shutdown();}
    }
}
