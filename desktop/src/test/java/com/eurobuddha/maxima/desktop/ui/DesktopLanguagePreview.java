package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Disposable real desktop UI; isolated preferences, synthetic identity, no node.start or public peers. */
public final class DesktopLanguagePreview {
    public static final class MemoryFactory implements PreferencesFactory {
        private final Preferences root = new DesktopLanguageTest.MemoryPreferences();
        public Preferences userRoot() { return root; }
        public Preferences systemRoot() { return root; }
    }
    private static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static void invalidate(Container root) {
        root.invalidate();
        for (Component child : root.getComponents()) if (child instanceof Container) invalidate((Container) child);
    }
    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container) layout((Container) child);
    }
    private static void fields(Container root, List<JTextField> result) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextField) result.add((JTextField) child);
            else if (child instanceof Container) fields((Container) child, result);
        }
    }
    private static void snapshot(MaximaWindow window, Path out, String name, int width) throws Exception {
        window.frame().setSize(width, 820);
        Container root = window.frame().getContentPane(); root.setSize(width, 820);
        for (int i = 0; i < 5; i++) { invalidate(root); layout(root); }
        BufferedImage image = new BufferedImage(width, 820, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); root.printAll(g); g.dispose();
        ImageIO.write(image, "png", out.resolve(name + "-" + width + ".png").toFile());
    }
    private static void verifyChatAndContacts(MaximaWindow window, List<?> tabs, Path out) throws Exception {
        ContactsPanel contacts = (ContactsPanel) tabs.get(1);
        ChatsPanel chat = (ChatsPanel) tabs.get(0);
        com.eurobuddha.maxima.core.contacts.Contact contact = new com.eurobuddha.maxima.core.contacts.Contact(window.node().port().publicKeyHex());
        contact.name = "Chats 中文"; contact.lastSeen = System.currentTimeMillis() - 2 * 3600_000;
        window.node().node().storeContact(contact); // fixture-only local book; node is never started
        window.select(1);
        JTextField search = (JTextField) field(contacts, "mSearch");
        search.setText("Chats"); search.select(1, 4);
        Object searchDocument = search.getDocument();
        DesktopChatLanguageTest.call(contacts, "showAddDialog", new Class<?>[0]);
        JDialog add = (JDialog) window.frame().getOwnedWindows()[0];
        add.setVisible(false);
        List<JTextField> inputs = new ArrayList<>(); fields(add.getContentPane(), inputs);
        JTextField address = inputs.get(0); address.setText("Mx-中文-draft@127.0.0.1:1"); address.select(3, 7);
        Object addressDocument = address.getDocument();
        window.select(0); chat.openConversation(contact.publicKey, false);
        JPanel thread = (JPanel) field(chat, "mThread");
        String[] bodies = {"Literal message: Chats 中文 Русский",
                com.eurobuddha.maxima.core.chat.ChatPay.wrap("12.5", "MINIMA", "synthetic", "Unchanged memo 中文"),
                com.eurobuddha.maxima.core.chat.ChatMedia.wrap("application/octet-stream", "pf1:synthetic", "中文 файл.bin"),
                com.eurobuddha.maxima.core.chat.ChatMedia.wrap("audio/wav", "synthetic", "0:10|"),
                com.eurobuddha.maxima.core.chat.ChatContact.wrap("synthetic", "Chats", "Mx-synthetic@127.0.0.1:1")};
        for (int i = 0; i < bodies.length; i++) thread.add((JComponent) DesktopChatLanguageTest.call(chat, "bubble",
                new Class<?>[]{com.eurobuddha.maxima.core.chat.ChatEngine.Entry.class, boolean.class, boolean.class},
                DesktopChatLanguageTest.entry("visual-" + i, bodies[i], i == 1), true, true));
        List<Component> widgets = DesktopChatLanguageTest.descendants(thread);
        try {
            for (String code : new String[]{"en", "ru", "zh-Hans"}) {
                Language.choose(code);
                if (address.getDocument() != addressDocument || !"Mx-中文-draft@127.0.0.1:1".equals(address.getText())
                        || address.getSelectionStart() != 3 || address.getSelectionEnd() != 7)
                    throw new AssertionError("Add-contact draft changed");
                if (!Language.text("contacts.addHeading").equals(add.getTitle())) throw new AssertionError("Dialog title is stale");
                if (search.getDocument() != searchDocument || !"Chats".equals(search.getText())
                        || search.getSelectionStart() != 1 || search.getSelectionEnd() != 4)
                    throw new AssertionError("Contact search draft changed");
                if (!widgets.equals(DesktopChatLanguageTest.descendants(thread))) throw new AssertionError("Message/media widgets rebuilt");
                for (int width : new int[]{430, 1040}) {
                    window.select(1); contacts.onWidth(width);
                    snapshot(window, out, "desktop-contacts-" + code, width);
                    window.select(0); chat.onWidth(width);
                    snapshot(window, out, "desktop-chat-" + code, width);
                }
            }
            System.out.println("PASS: contact search and modeless add-contact draft/title, same message/media widgets across en/ru/zh-Hans");
        } finally { add.dispose(); }
    }
    private static void verifyContactCard(MaximaWindow window, List<?> tabs, Path out) throws Exception {
        ContactsPanel contacts = (ContactsPanel) tabs.get(1);
        com.eurobuddha.maxima.core.contacts.Contact contact = new com.eurobuddha.maxima.core.contacts.Contact("synthetic-card");
        contact.name = "Alice 中文";
        contact.addresses.add("Mx-synthetic-" + "a".repeat(80) + "@127.0.0.1:1");
        contact.lastSeen = System.currentTimeMillis() - 3600_000;
        DesktopChatLanguageTest.call(contacts, "showContactCard", new Class<?>[]{contact.getClass()}, contact);
        JDialog card = null;
        for (Window owned : window.frame().getOwnedWindows()) if (owned instanceof JDialog && owned.isDisplayable()) card = (JDialog) owned;
        if (card == null) throw new AssertionError("Contact card missing");
        List<Component> buttons = new ArrayList<>();
        JScrollPane scroll = null;
        for (Component child : DesktopChatLanguageTest.descendants(card.getContentPane())) {
            if (child instanceof DKit.HoverButton) buttons.add(child);
            if (child instanceof JScrollPane) scroll = (JScrollPane) child;
        }
        if (scroll == null || buttons.size() != 4) throw new AssertionError("Contact card actions or scrolling missing");
        try {
            for (int width : new int[]{430, 700}) {
                card.setSize(width, 420);
                for (int i = 0; i < 5; i++) { invalidate(card); layout(card); }
                scroll.getViewport().dispatchEvent(new java.awt.event.ComponentEvent(scroll.getViewport(), java.awt.event.ComponentEvent.COMPONENT_RESIZED));
                for (String code : new String[]{"en", "ru", "zh-Hans", "en"}) {
                    Language.choose(code);
                    dialogSnapshot(card, out, "desktop-contact-card-" + code + "-" + width);
                    List<Component> current = new ArrayList<>();
                    for (Component child : DesktopChatLanguageTest.descendants(card.getContentPane())) if (child instanceof DKit.HoverButton) current.add(child);
                    if (!buttons.equals(current)) throw new AssertionError("Contact card buttons replaced");
                    for (Component button : buttons) {
                        Point edge = SwingUtilities.convertPoint(button, button.getWidth(), 0, scroll.getViewport());
                        if (button.getWidth() + 1 < button.getPreferredSize().width || edge.x > scroll.getViewport().getWidth() + 1)
                            throw new AssertionError("Clipped contact action: " + ((JLabel) button).getText());
                    }
                    if (!contact.name.equals(card.getTitle())) throw new AssertionError("Literal contact title changed");
                }
            }
            System.out.println("PASS: eight contact-card language/width cases; same four actions, scrollable full addresses, no clipped action labels");
        } finally { card.dispose(); }
    }

    private static void dialogSnapshot(JDialog dialog,Path out,String name) throws Exception {
        Container root=dialog.getContentPane();dialog.validate();
        for(int i=0;i<5;i++){invalidate(root);layout(root);}
        BufferedImage image=new BufferedImage(root.getWidth(),root.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();root.printAll(g);g.dispose();ImageIO.write(image,"png",out.resolve(name+".png").toFile());
    }
    private static void verifyNetwork(MaximaWindow window,List<?> tabs,Path out)throws Exception {
        NetworkPanel network=(NetworkPanel)tabs.get(3);
        window.node().relayStore().add("127.0.0.1:9"); // fixture only; no connection request
        window.select(3);network.refresh();network.refresh();
        List<JTextField> draftFields=new ArrayList<>();fields(network,draftFields);
        JTextField relay=draftFields.get(0);relay.setText("unfinished.example:9537");relay.select(2,7);
        DesktopManualForward.set(false,"198.51.100.9",9537); // memory preferences; suppress external IP detection
        DesktopChatLanguageTest.call(network,"mlsSettings",new Class<?>[0]);
        DesktopChatLanguageTest.call(network,"manualForward",new Class<?>[0]);
        List<JDialog> dialogs=new ArrayList<>();
        for(Window owned:window.frame().getOwnedWindows())if(owned instanceof JDialog&&owned.isDisplayable()){
            JDialog dialog=(JDialog)owned;dialogs.add(dialog);fields(dialog.getContentPane(),draftFields);
        }
        for(int i=1;i<draftFields.size();i++)if(draftFields.get(i).isEditable()){
            draftFields.get(i).setText("unsaved-中文-"+i);draftFields.get(i).select(1,5);
        }
        Object[] docs=draftFields.stream().map(JTextField::getDocument).toArray();
        String[] drafts=draftFields.stream().map(JTextField::getText).toArray(String[]::new);
        int[] starts=draftFields.stream().mapToInt(JTextField::getSelectionStart).toArray();
        int[] ends=draftFields.stream().mapToInt(JTextField::getSelectionEnd).toArray();
        try{
            for(String code:new String[]{"en","ru","zh-Hans"}){
                Language.choose(code);network.refresh();
                for(int i=0;i<draftFields.size();i++){
                    JTextField f=draftFields.get(i);
                    if(f.getDocument()!=docs[i]||!f.getText().equals(drafts[i])||f.getSelectionStart()!=starts[i]||f.getSelectionEnd()!=ends[i])
                        throw new AssertionError("Network draft/document/selection changed");
                }
                for(int width:new int[]{430,1040}){
                    network.onWidth(width);snapshot(window,out,"desktop-network-"+code,width);
                    JScrollPane scroll=(JScrollPane)network.getComponent(0);
                    for(Component control:DesktopChatLanguageTest.descendants(network))if(control instanceof DKit.HoverButton){
                        if(control.getWidth()+1<control.getPreferredSize().width)
                            throw new AssertionError("Network control clipped: "+((JLabel)control).getText());
                        Point edge=SwingUtilities.convertPoint(control,control.getWidth(),0,scroll.getViewport());
                        if(edge.x>scroll.getViewport().getWidth()+1)throw new AssertionError("Network control outside viewport: "+((JLabel)control).getText());
                    }
                    scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum()/2);
                    snapshot(window,out,"desktop-network-middle-"+code,width);
                    scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                    snapshot(window,out,"desktop-network-bottom-"+code,width);
                    scroll.getVerticalScrollBar().setValue(0);
                }
                for(JDialog dialog:dialogs){
                    boolean manual=dialog.getTitle().equals(Language.text("nativeNetwork.manualTitle"));
                    if(!manual&&!dialog.getTitle().equals(Language.text("nativeNetwork.location")))throw new AssertionError("Network dialog title stale");
                    dialogSnapshot(dialog,out,"desktop-network-"+(manual?"manual-":"mls-")+code);
                }
            }
            DesktopChatLanguageTest.set(window.node(),"mReachState",com.eurobuddha.maxima.core.net.ReachabilityManager.State.MAPPING);
            DesktopChatLanguageTest.set(window.node(),"mReachDetail","asking the router for a public port…");
            network.refresh();
            JLabel state=(JLabel)field(network,"mDrState");
            if(!state.getText().equals(Language.text("nativeNetwork.mapping")))throw new AssertionError("Reachability state stale");
            if(relay.getDocument()!=docs[0])throw new AssertionError("Live status change rebuilt network draft");
            System.out.println("PASS: Network relay/location/manual-forward drafts, documents and selections retained across en/ru/zh-Hans; live reachability state updates in place; no network actions");
        }finally{for(JDialog dialog:dialogs)dialog.dispose();}
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.prefs.PreferencesFactory", MemoryFactory.class.getName());
        Path out = Paths.get(args[0]); Files.createDirectories(out);
        Path data = Files.createTempDirectory(out, "synthetic-node-");
        Files.writeString(data.resolve("relays-builtin.txt"), "off");
        DesktopNode node = new DesktopNode(MaximaIdentity.fromPhrase(Bip39.fromEntropy(new byte[32])), data, "Language fixture");
        try {
            SwingUtilities.invokeAndWait(() -> {
                Language.choose(System.getProperty("parlons.fixture.language", "en"));
                MaximaWindow window = new MaximaWindow(node, new Theme(Theme.Mode.LIGHT, Integer.getInteger("parlons.fixture.font", 100)));
                try {
                    List<?> tabs = (List<?>) field(window, "mTabs");
                    ChatsPanel chat = (ChatsPanel) tabs.get(0);
                    JTextArea draft = (JTextArea) field(chat, "mInput");
                    draft.setText("Keep 中文 черновик"); draft.select(3, 8);
                    Object document = draft.getDocument();
                    Object chatEngine = node.chat(), fileService = node.files();
                    window.select(4);
                    SettingsPanel settings = (SettingsPanel) tabs.get(4);
                    List<JTextField> inputs = new ArrayList<>(); fields(settings, inputs);
                    for (JTextField input : inputs) { input.setText("unsaved 中文 draft"); input.select(2, 6); }
                    Object[] documents = inputs.stream().map(JTextField::getDocument).toArray();
                    for (String code : new String[]{"en", "ru", "zh-Hans", "en"}) {
                        Language.choose(code);
                        if (document != draft.getDocument() || !"Keep 中文 черновик".equals(draft.getText())
                                || draft.getSelectionStart() != 3 || draft.getSelectionEnd() != 8)
                            throw new AssertionError("Chat draft changed");
                        if (node.chat() != chatEngine || node.files() != fileService)
                            throw new AssertionError("Engine or transfer service replaced");
                        for (int i = 0; i < inputs.size(); i++) {
                            JTextField input = inputs.get(i);
                            if (input.getDocument() != documents[i] || !"unsaved 中文 draft".equals(input.getText())
                                    || input.getSelectionStart() != 2 || input.getSelectionEnd() != 6)
                                throw new AssertionError("Settings draft changed");
                        }
                        for (int width : new int[]{430, 1040}) {
                            window.frame().setSize(width, 820);
                            Container root = window.frame().getContentPane();
                            root.setSize(width, 820);
                            settings.onWidth(width);
                            for (int i = 0; i < 5; i++) { invalidate(root); layout(root); }
                            BufferedImage image = new BufferedImage(width, 820, BufferedImage.TYPE_INT_RGB);
                            Graphics2D g = image.createGraphics(); root.printAll(g); g.dispose();
                            ImageIO.write(image, "png", out.resolve("desktop-settings-" + code + "-" + width + ".png").toFile());
                            JScrollPane scroll = (JScrollPane) settings.getComponent(0);
                            scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                            for (int i = 0; i < 3; i++) { invalidate(root); layout(root); }
                            g = image.createGraphics(); root.printAll(g); g.dispose();
                            ImageIO.write(image, "png", out.resolve("desktop-settings-bottom-" + code + "-" + width + ".png").toFile());
                            scroll.getVerticalScrollBar().setValue(0);
                        }
                    }
                    verifyChatAndContacts(window, tabs, out);
                    verifyContactCard(window, tabs, out);
                    verifyNetwork(window,tabs,out);
                    System.out.println("PASS: same window, chat document/selection, settings documents/selections, chat engine and file service in en/ru/zh-Hans");
                } catch (Exception e) { throw new RuntimeException(e); }
                finally {
                    try { ((Timer) field(window, "mBeat")).stop(); } catch (Exception ignored) { }
                    window.frame().dispose();
                }
            });
        } finally { node.shutdown(); }
    }
}
