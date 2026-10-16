package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.chat.ChatFile;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.files.FileCommands;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Real transfer dialog and local backend; synthetic data, no node.start or network peers. */
public final class DesktopFileLanguagePreview {
    private static Map<String,String> call(FileCommands commands, String... values) throws Exception {
        Map<String,String> input = new LinkedHashMap<>();
        for (int i=0; i<values.length; i+=2) input.put(values[i], values[i+1]);
        return commands.call(input);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void awaitUi(BooleanSupplier ready) throws Exception {
        long until=System.currentTimeMillis()+8000;
        while (System.currentTimeMillis()<until) {
            boolean[] result={false}; SwingUtilities.invokeAndWait(()->result[0]=ready.getAsBoolean());
            if (result[0]) return;
            Thread.sleep(30);
        }
        throw new AssertionError("Transfer UI did not reach expected state");
    }
    private static void capture(JDialog dialog, Path out, String name) throws Exception {
        Container root=dialog.getContentPane();
        dialog.validate();
        BufferedImage image=new BufferedImage(root.getWidth(),root.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=image.createGraphics();root.printAll(graphics);graphics.dispose();
        ImageIO.write(image,"png",out.resolve(name+".png").toFile());
    }
    private static void verifyPhotoViewer(JFrame owner, Path out) throws Exception {
        com.eurobuddha.maxima.core.chat.ChatImages photos=new com.eurobuddha.maxima.core.chat.ChatImages(Arrays.asList(
                new com.eurobuddha.maxima.core.chat.ChatImages.Photo("one",com.eurobuddha.maxima.core.chat.ChatMedia.wrap("image/png","synthetic-one",""),1),
                new com.eurobuddha.maxima.core.chat.ChatImages.Photo("two",com.eurobuddha.maxima.core.chat.ChatMedia.wrap("image/png","synthetic-two",""),2)),"one");
        BufferedImage sample=new BufferedImage(320,200,BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=sample.createGraphics();graphics.setColor(new Color(80,130,155));graphics.fillRect(0,0,320,200);
        graphics.setColor(Color.WHITE);graphics.fillOval(80,20,160,160);graphics.dispose();
        java.util.concurrent.atomic.AtomicInteger loads=new java.util.concurrent.atomic.AtomicInteger();
        SwingUtilities.invokeLater(()->ImageViewer.open(owner,photos,photo->{
            int count=loads.incrementAndGet();return count==2?null:sample;
        }));
        JDialog[] viewer={null};JComponent[] canvas={null};
        awaitUi(()->{
            for(Window candidate:owner.getOwnedWindows())if(candidate instanceof JDialog&&candidate.isShowing()){
                JDialog found=(JDialog)candidate;
                for(Component c:DesktopChatLanguageTest.descendants(found.getContentPane()))if(c.getClass().getName().endsWith("ImageViewer$Canvas")){
                    viewer[0]=found;canvas[0]=(JComponent)c;
                    try{return DesktopChatLanguageTest.field(c,"img")!=null;}catch(Exception e){throw new RuntimeException(e);}
                }
            }
            return false;
        });
        try {
            java.util.List<Component> widgets=new ArrayList<>();
            for(Component c:DesktopChatLanguageTest.descendants(viewer[0].getContentPane()))
                if(c instanceof JButton || c instanceof JLabel || c==canvas[0])widgets.add(c);
            SwingUtilities.invokeAndWait(()->canvas[0].dispatchEvent(new java.awt.event.MouseWheelEvent(canvas[0],
                    java.awt.event.MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,100,100,0,false,
                    java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,1,-1)));
            Object zoom=DesktopChatLanguageTest.field(canvas[0],"zoom");
            for(String code:new String[]{"en","ru","zh-Hans"}){
                SwingUtilities.invokeAndWait(()->{
                    viewer[0].setSize(430,650); viewer[0].validate();
                    Language.choose(code);
                    java.util.List<Component> actual=DesktopChatLanguageTest.descendants(viewer[0].getContentPane());
                    check(actual.containsAll(widgets),"Photo controls replaced");
                    try{
                        check(sample==DesktopChatLanguageTest.field(canvas[0],"img"),"Photo replaced");
                        check(zoom.equals(DesktopChatLanguageTest.field(canvas[0],"zoom")),"Photo zoom reset");
                        viewer[0].setSize(430,650);capture(viewer[0],out,"desktop-photo-"+code);
                    }catch(Exception e){throw new RuntimeException(e);}
                });
                check(loads.get()==1,"Language switch reloaded photo");
            }
            JButton next=(JButton)widgets.stream().filter(c->c instanceof JButton&&Language.text("photos.next").equals(((JButton)c).getToolTipText())).findFirst().orElseThrow();
            JButton retry=(JButton)widgets.stream().filter(c->c instanceof JButton&&Language.text("desktopPhotos.retry").equals(((JButton)c).getText())).findFirst().orElseThrow();
            SwingUtilities.invokeAndWait(next::doClick);awaitUi(retry::isVisible);
            SwingUtilities.invokeAndWait(()->{
                Language.choose("ru");check(retry.getText().equals(Language.text("desktopPhotos.retry")),"Retry label stale");retry.doClick();
            });
            awaitUi(()->{
                try{return !retry.isVisible()&&loads.get()==3&&sample==DesktopChatLanguageTest.field(canvas[0],"img");}
                catch(Exception e){throw new RuntimeException(e);}
            });
            check(photos.index()==1,"Photo position lost");
            System.out.println("PASS: photo image/zoom/widgets retained across en/ru/zh-Hans; no reload on language choice; next and failed-photo Retry work");
        } finally {SwingUtilities.invokeAndWait(viewer[0]::dispose);}
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.prefs.PreferencesFactory", DesktopLanguagePreview.MemoryFactory.class.getName());
        Path out=Paths.get(args[0]);Files.createDirectories(out);
        Path data=Files.createTempDirectory(out,"synthetic-transfer-");
        Files.writeString(data.resolve("relays-builtin.txt"),"off");
        DesktopNode node=new DesktopNode(MaximaIdentity.fromPhrase(Bip39.fromEntropy(new byte[32])),data,"Transfer fixture");
        JFrame[] frame={null};JDialog[] dialog={null};
        String upload="1234567890abcdef1234567890abcdef";
        try {
            byte[] entropy=new byte[32];entropy[0]=1;
            String peer=MaximaIdentity.fromPhrase(Bip39.fromEntropy(entropy)).publicKeyHex();
            node.node().storeContact(new Contact(peer));
            FileCommands commands=node.files();
            byte[] plain=new byte[3*48*1024];new Random(7).nextBytes(plain);
            String name="Preparing 中文 файл {size}.bin";
            call(commands,"action","begin","id",upload,"size",Integer.toString(plain.length),"name",name,"peer",peer,"group","false");
            SwingUtilities.invokeAndWait(()->{
                frame[0]=new JFrame("Disposable transfer fixture");frame[0].setSize(500,400);
                try {
                    Method show=PrivateFileDialog.class.getDeclaredMethod("show",Component.class,DesktopNode.class,String.class,ChatFile.class,String.class,boolean.class);
                    show.setAccessible(true);show.invoke(null,frame[0].getContentPane(),node,upload,null,peer,false);
                    dialog[0]=(JDialog)frame[0].getOwnedWindows()[0];
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            java.util.List<Component> components=DesktopChatLanguageTest.descendants(dialog[0].getContentPane());
            JTextArea info=(JTextArea)components.stream().filter(c->c instanceof JTextArea).findFirst().orElseThrow();
            java.util.List<JButton> buttons=new ArrayList<>();
            for(Component c:components)if(c instanceof JButton)buttons.add((JButton)c);
            JButton action=buttons.get(0),save=buttons.get(1),remove=buttons.get(2);
            awaitUi(()->info.getText().contains(name)&&!action.isEnabled());
            String[] codes={"en","ru","zh-Hans"};
            for(int i=0;i<codes.length;i++) {
                String code=codes[i];
                SwingUtilities.invokeAndWait(()->{
                    Language.choose(code);
                    check(components.equals(DesktopChatLanguageTest.descendants(dialog[0].getContentPane())),"Transfer widgets replaced");
                    check(info.getText().startsWith(Language.system("Uploading")),"Upload status not refreshed");
                    check(info.getText().contains(name),"Filename translated");
                    check(!action.isEnabled()&&!save.isEnabled()&&!remove.isEnabled(),"Language choice changed transfer controls");
                    check(dialog[0].getTitle().equals(Language.text("files.private")),"Transfer title stale");
                    try {capture(dialog[0],out,"desktop-transfer-upload-"+code);}catch(Exception e){throw new RuntimeException(e);}
                });
                int offset=i*48*1024;
                call(commands,"action","append","id",upload,"offset",Integer.toString(offset),"data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(plain,offset,offset+48*1024)));
                check(Integer.toString(offset+48*1024).equals(call(commands,"action","status","id",upload).get("done")),"Upload offset lost");
                check(node.files()==commands,"File service replaced");
            }
            call(commands,"action","finish","id",upload);
            awaitUi(save::isEnabled);
            String fileId=call(commands,"action","status","id",upload).get("fileId");
            java.io.ByteArrayOutputStream exported=new java.io.ByteArrayOutputStream();
            long offset=0;
            while(offset<plain.length){
                Map<String,String> chunk=call(commands,"action","read","id",fileId,"offset",Long.toString(offset));
                exported.write(Base64.getDecoder().decode(chunk.get("data")));offset=Long.parseLong(chunk.get("next"));
            }
            check(Arrays.equals(plain,exported.toByteArray()),"Transfer bytes changed");
            for(String code:codes){
                SwingUtilities.invokeAndWait(()->{
                    Language.choose(code);
                    check(save.isEnabled()&&remove.isEnabled()&&action.isEnabled(),"Ready state changed");
                    check(action.getText().equals(Language.text("files.pause")),"Pause label stale");
                    check(info.getText().contains(name),"Ready filename changed");
                    try{capture(dialog[0],out,"desktop-transfer-ready-"+code);}catch(Exception e){throw new RuntimeException(e);}
                });
            }
            SwingUtilities.invokeAndWait(action::doClick);
            awaitUi(()->action.getText().equals(Language.text("files.resume")));
            check("true".equals(call(commands,"action","status","id",fileId).get("paused")),"Pause command changed");
            SwingUtilities.invokeAndWait(()->{Language.choose("ru");action.doClick();});
            awaitUi(()->action.getText().equals(Language.text("files.pause")));
            check("false".equals(call(commands,"action","status","id",fileId).get("paused")),"Resume command changed");
            System.out.println("PASS: real local upload, encryption and byte-for-byte export across en/ru/zh-Hans; same transfer controls, working Pause/Resume; no public peers");
            SwingUtilities.invokeAndWait(dialog[0]::dispose);
            verifyPhotoViewer(frame[0],out);
        } finally {
            SwingUtilities.invokeAndWait(()->{if(dialog[0]!=null)dialog[0].dispose();if(frame[0]!=null)frame[0].dispose();});
            SwingUtilities.invokeAndWait(()->{}); // drain windowClosed before closing the backend
            node.shutdown();
        }
    }
}
