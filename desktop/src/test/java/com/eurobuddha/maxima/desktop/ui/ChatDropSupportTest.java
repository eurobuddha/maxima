package com.eurobuddha.maxima.desktop.ui;

import org.junit.Test;
import static org.junit.Assert.*;
import javax.swing.*;
import java.awt.datatransfer.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.*;

public class ChatDropSupportTest {
    private static Transferable files(Object value){return new Transferable(){
        public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{DataFlavor.javaFileListFlavor};}
        public boolean isDataFlavorSupported(DataFlavor flavor){return DataFlavor.javaFileListFlavor.equals(flavor);}
        public Object getTransferData(DataFlavor flavor){return value;}
    };}
    @Test public void snapshotsEveryFileAndRejectsDropsWithoutConversation() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            JTextArea area=new JTextArea();AtomicBoolean enabled=new AtomicBoolean(true);AtomicReference<List<File>> got=new AtomicReference<>();
            ChatDropSupport.install(area,enabled::get,got::set);
            ChatDropSupport handler=(ChatDropSupport)area.getTransferHandler();
            List<File> source=new ArrayList<>(Arrays.asList(new File("one.pdf"),new File("two.png")));
            try{
                assertTrue(handler.receiveFiles(files(source)));source.clear();assertEquals(2,got.get().size());
                enabled.set(false);got.set(null);assertFalse(handler.receiveFiles(files(Arrays.asList(new File("three")))));assertNull(got.get());
                enabled.set(true);assertFalse(handler.receiveFiles(files(Arrays.asList("not a file"))));assertFalse(handler.receiveFiles(files(Collections.emptyList())));
            }catch(Exception e){throw new AssertionError(e);}
        });
    }
    @Test public void textAndExistingImageClipboardStillWork() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            JTextArea area=new JTextArea("draft");area.setCaretPosition(5);
            AtomicInteger images=new AtomicInteger();
            ImagePasteSupport.install(area,()->true,t->images.incrementAndGet());
            ChatDropSupport.install(area,()->true,t->fail("Clipboard treated as a drop"));
            assertTrue(area.getTransferHandler().importData(area,new StringSelection(" text")));assertEquals("draft text",area.getText());
            assertTrue(area.getTransferHandler().importData(new TransferHandler.TransferSupport(area,files(Arrays.asList(new File("photo.png"))))));assertEquals(1,images.get());
            Clipboard clipboard=new Clipboard("isolated");area.select(0,5);area.getTransferHandler().exportToClipboard(area,clipboard,TransferHandler.MOVE);assertEquals(" text",area.getText());
            try{assertEquals("draft",clipboard.getData(DataFlavor.stringFlavor));}catch(Exception e){throw new AssertionError(e);}
        });
    }
}
