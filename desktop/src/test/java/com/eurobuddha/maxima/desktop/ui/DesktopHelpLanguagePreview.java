package com.eurobuddha.maxima.desktop.ui;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioSystem;
import javax.swing.*;

/** Actual dialogs with synthetic PCM only: never opens a microphone, network or account. */
public final class DesktopHelpLanguagePreview {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void layout(Container root){root.invalidate();root.doLayout();for(Component c:root.getComponents())if(c instanceof Container)layout((Container)c);}
    private static void capture(Container root,Path out,String name)throws Exception{
        for(int i=0;i<6;i++)layout(root);
        BufferedImage image=new BufferedImage(root.getWidth(),root.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();root.printAll(g);g.dispose();ImageIO.write(image,"png",out.resolve(name+".png").toFile());
        for(Component c:DesktopChatLanguageTest.descendants(root)) if(c instanceof DKit.HoverButton){
            Rectangle bounds=SwingUtilities.convertRectangle(c.getParent(),c.getBounds(),root);
            check(c.getWidth()>=c.getPreferredSize().width,"Clipped voice action: "+((JLabel)c).getText());
            check(bounds.x>=0&&bounds.y>=0&&bounds.getMaxX()<=root.getWidth()&&bounds.getMaxY()<=root.getHeight(),"Voice action outside dialog");
        }
    }
    private static Object recorder(byte[] pcm)throws Exception{
        Class<?> type=Class.forName(VoiceNotes.class.getName()+"$Recorder");
        java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructor();ctor.setAccessible(true);
        Object rec=ctor.newInstance();
        DesktopChatLanguageTest.set(rec,"startMs",System.currentTimeMillis()-12000);
        ((ByteArrayOutputStream)DesktopChatLanguageTest.field(rec,"pcm")).write(pcm);
        return rec;
    }
    private static JDialog voice(JFrame owner,Theme theme,Object recorder,VoiceNotes.Sink sink)throws Exception{
        return (JDialog)DesktopChatLanguageTest.call(VoiceNotes.class,"showRecorder",
                new Class<?>[]{Window.class,Theme.class,VoiceNotes.Sink.class,recorder.getClass()},owner,theme,sink,recorder);
    }
    private static int listeners()throws Exception{java.lang.reflect.Field field=Language.class.getDeclaredField("LISTENERS");field.setAccessible(true);return ((java.util.Collection<?>)field.get(null)).size();}
    private static List<Component> widgets(Container root){return DesktopChatLanguageTest.descendants(root).stream().filter(c->!(c instanceof Box.Filler)).collect(java.util.stream.Collectors.toList());}
    public static void main(String[] args)throws Exception{
        System.setProperty("java.util.prefs.PreferencesFactory",DesktopLanguagePreview.MemoryFactory.class.getName());
        Path out=Paths.get(args[0]);Files.createDirectories(out);
        AtomicInteger sent=new AtomicInteger();byte[][] result={null};
        byte[] pcm=new byte[6400];for(int i=0;i<pcm.length;i++)pcm[i]=(byte)(i%97);
        int[] before={0};Object[] recorders=new Object[2];
        SwingUtilities.invokeAndWait(()->{
            JFrame owner=new JFrame("Language fixture");owner.setSize(500,500);
            try{
                before[0]=listeners();Theme theme=new Theme(Theme.Mode.LIGHT,Integer.getInteger("parlons.fixture.font",100));
                JDialog help=DesktopExplain.dialog(owner.getRootPane(),"direct",theme);
                help.setModalityType(Dialog.ModalityType.MODELESS);help.setVisible(true);
                JTextArea helpBody=(JTextArea)DesktopChatLanguageTest.descendants(help.getContentPane()).stream().filter(c->c instanceof JTextArea).findFirst().orElseThrow();
                Object helpDoc=helpBody.getDocument();
                Object rec=recorders[0]=recorder(pcm);
                JDialog voice=voice(owner,theme,rec,(bytes,caption)->{sent.incrementAndGet();result[0]=bytes;check(caption.matches("[0-9]+:[0-9]{2}\\|[0-9a-f]{32}"),"Caption changed format");});
                List<Component> widgets=widgets(voice.getContentPane());
                for(String code:new String[]{"en","ru","zh-Hans"}){
                    Language.choose(code);
                    check(widgets.equals(widgets(voice.getContentPane())),"Voice controls rebuilt");
                    check(voice.getTitle().equals(Language.text("desktopChat.voiceNote")),"Voice title stale");
                    check(help.getTitle().equals(DesktopExplain.title("direct"))&&helpBody.getText().equals(DesktopExplain.body("direct")),"Help stale");
                    check(helpBody.getDocument()==helpDoc,"Help document replaced");
                    check(!(Boolean)DesktopChatLanguageTest.field(rec,"stopped"),"Language stopped recording");
                    check(Arrays.equals(pcm,((ByteArrayOutputStream)DesktopChatLanguageTest.field(rec,"pcm")).toByteArray()),"PCM changed on language switch");
                    check(sent.get()==0,"Language sent a voice note");
                    capture(voice.getContentPane(),out,"voice-"+code);
                    capture(help.getContentPane(),out,"help-"+code);
                }
                DKit.HoverButton send=(DKit.HoverButton)widgets.stream().filter(c->c instanceof DKit.HoverButton).findFirst().orElseThrow();
                Runnable action=(Runnable)DesktopChatLanguageTest.field(send,"action");action.run();action.run();
                check(sent.get()==1,"Duplicate voice send");
                check(Arrays.equals(pcm,AudioSystem.getAudioInputStream(new ByteArrayInputStream(result[0])).readAllBytes()),"WAV payload differs from synthetic PCM");
                Object cancelled=recorders[1]=recorder(pcm);
                JDialog cancel=voice(owner,theme,cancelled,(bytes,caption)->sent.incrementAndGet());
                Language.choose("ru");cancel.dispose();help.dispose();
            }catch(Exception ex){throw new RuntimeException(ex);}
            finally{for(Window dialog:owner.getOwnedWindows())dialog.dispose();owner.dispose();}
        });
        SwingUtilities.invokeAndWait(()->{
            try{
                check(sent.get()==1,"Cancelled dialog sent audio");
                for(Object rec:recorders)check((Boolean)DesktopChatLanguageTest.field(rec,"stopped"),"Recorder left active");
                check(listeners()==before[0],"Closed dialogs leaked language listeners");
            }catch(Exception ex){throw new RuntimeException(ex);}
        });
        System.out.println("PASS: translated help and same voice controls/PCM through en/ru/zh-Hans; send exactly once, WAV bytes preserved, cancellation stops recording, listeners cleaned; no microphone or network");
    }
}
