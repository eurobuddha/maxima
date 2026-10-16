package com.eurobuddha.maxima.desktop.ui;

import com.eurobuddha.maxima.core.chat.ChatFile;
import com.eurobuddha.maxima.files.FileCommands;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Desktop counterpart of the shared Android transfer sheet. File I/O never runs on the EDT. */
final class PrivateFileDialog {
    private static Map<String,String> call(FileCommands commands,String action,String... args)throws Exception{
        Map<String,String> p=new LinkedHashMap<>();p.put("action",action);for(int i=0;i<args.length;i+=2)p.put(args[i],args[i+1]);return commands.call(p);
    }
    static void choose(Component parent,DesktopNode node,String peer,boolean group){
        JFileChooser picker=Language.fileChooser();picker.setDialogTitle(Language.text("desktopFiles.choose"));
        if(picker.showOpenDialog(parent)!=JFileChooser.APPROVE_OPTION)return;
        File source=picker.getSelectedFile();
        new Thread(()->{String id=UUID.randomUUID().toString().replace("-","");FileCommands commands=null;
            try{commands=node.files();String mime=Files.probeContentType(source.toPath());
                call(commands,"begin","id",id,"name",source.getName(),"mime",mime==null?"application/octet-stream":mime,"size",Long.toString(source.length()),"peer",peer,"group",Boolean.toString(group));
                SwingUtilities.invokeLater(()->show(parent,node,id,null,peer,group));
                try(InputStream in=Files.newInputStream(source.toPath())){byte[] buffer=new byte[48*1024];int n;long offset=0;
                    while((n=in.read(buffer))!=-1){if(n==0)continue;call(commands,"append","id",id,"offset",Long.toString(offset),"data",Base64.getEncoder().encodeToString(Arrays.copyOf(buffer,n)));offset+=n;}}
                call(commands,"finish","id",id);
            }catch(Exception e){if(commands!=null)try{call(commands,"cancelUpload","id",id);}catch(Exception ignored){}error(parent,e);}
        },"private-file-send").start();
    }
    static void showBody(Component parent,DesktopNode node,String body,String peer,boolean group){try{ChatFile f=ChatFile.fromBody(body);show(parent,node,f.id,f,peer,group);}catch(Exception e){error(parent,e);}}
    static void list(Component parent,DesktopNode node){new Thread(()->{try{
        org.json.JSONArray array=new org.json.JSONArray(call(node.files(),"list").get("transfers"));String[] labels=new String[array.length()];
        for(int i=0;i<labels.length;i++)labels[i]=array.getJSONObject(i).getString("name")+" · "+Language.system(array.getJSONObject(i).getString("status"));
        SwingUtilities.invokeLater(()->{if(labels.length==0){JOptionPane.showMessageDialog(parent,Language.text("nativeFiles.noTransfers"));return;}
            JList<String> list=new JList<>(labels);list.setSelectedIndex(0);if(JOptionPane.showConfirmDialog(parent,new JScrollPane(list),Language.text("files.title"),JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION&&list.getSelectedIndex()>=0)
                show(parent,node,array.getJSONObject(list.getSelectedIndex()).getString("id"),null,null,false);});
    }catch(Exception e){error(parent,e);}},"private-file-list").start();}
    private static void show(Component parent,DesktopNode node,String initialId,ChatFile offer,String peer,boolean group){
        JDialog dialog=new JDialog(SwingUtilities.getWindowAncestor(parent),Dialog.ModalityType.MODELESS);
        JPanel box=new JPanel();box.setLayout(new BoxLayout(box,BoxLayout.Y_AXIS));box.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
        Language.title(dialog,offer==null?Language.msg("files.private"):offer.name);
        JTextArea status=new JTextArea(Language.text("files.description"));status.setEditable(false);status.setLineWrap(true);status.setWrapStyleWord(true);status.setOpaque(false);box.add(status);
        JProgressBar progress=new JProgressBar(0,100);box.add(progress);
        JButton action=Language.bind(new JButton(),Language.msg("files.download")),save=Language.bind(new JButton(),Language.msg("nativeFiles.save")),remove=Language.bind(new JButton(),Language.msg("files.remove"));save.setEnabled(false);remove.setEnabled(false);box.add(action);box.add(save);box.add(remove);
        dialog.setContentPane(box);dialog.setSize(490,340);
        java.util.concurrent.atomic.AtomicReference<java.util.function.Supplier<String>> display =
                new java.util.concurrent.atomic.AtomicReference<>(()->Language.text("files.description"));
        Runnable refresh=()->{
            status.setText(display.get().get());
            Language.refreshFont(status);
            Language.refresh(dialog);
        };
        Language.addListener(refresh);refresh.run();dialog.setLocationRelativeTo(parent);
        ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"private-file-sheet");t.setDaemon(true);return t;});
        String[] id={initialId};boolean[] exists={false},paused={false},busy={false};
        javax.swing.Timer timer=new javax.swing.Timer(1200,event->{if(busy[0])return;busy[0]=true;
            worker.execute(()->{try{Map<String,String> s=call(node.files(),"status","id",id[0]);SwingUtilities.invokeLater(()->{
                id[0]=s.getOrDefault("fileId",id[0]);exists[0]=true;paused[0]="true".equals(s.get("paused"))||"Failed".equals(s.get("status"));
                display.set(()->statusText(s));refresh.run();
                progress.setValue((int)(100*Long.parseLong(s.getOrDefault("done","0"))/Math.max(1,Long.parseLong(s.getOrDefault("size","0")))));
                save.setEnabled("true".equals(s.get("ready")));boolean preparing="Preparing".equals(s.get("status"))||"Uploading".equals(s.get("status"));
                action.setEnabled(!preparing);remove.setEnabled(!preparing);Language.bind(action,Language.msg(paused[0]?"files.resume":"files.pause"));
            });}catch(Exception e){if(offer==null)SwingUtilities.invokeLater(()->{display.set(()->Language.system(e.getMessage()));refresh.run();});}finally{SwingUtilities.invokeLater(()->busy[0]=false);}});
        });
        dialog.addWindowListener(new java.awt.event.WindowAdapter(){public void windowClosed(java.awt.event.WindowEvent e){timer.stop();worker.shutdownNow();Language.removeListener(refresh);}});
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        action.addActionListener(e->{String command=paused[0]?"resume":"pause";boolean known=exists[0];action.setEnabled(false);worker.execute(()->{
            try{if(!known){if(offer==null)throw new IOException("Transfer unavailable");call(node.files(),"download","ref",offer.ref(),"peer",peer,"group",Boolean.toString(group));}
                else call(node.files(),command,"id",id[0]);}catch(Exception ex){error(dialog,ex);}finally{SwingUtilities.invokeLater(()->action.setEnabled(true));}
        });});
        save.addActionListener(e->{JFileChooser fc=Language.fileChooser();fc.setSelectedFile(new File(offer==null?"Parlons-file":offer.name));if(fc.showSaveDialog(dialog)!=JFileChooser.APPROVE_OPTION)return;
            Path destination=fc.getSelectedFile().toPath();if(Files.exists(destination)&&JOptionPane.showConfirmDialog(dialog,Language.text("desktopFiles.replace"),Language.text("files.save"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            worker.execute(()->{Path temporary=null;try{temporary=Files.createTempFile(destination.toAbsolutePath().getParent(),".parlons-",".tmp");
                try(OutputStream out=Files.newOutputStream(temporary)){long offset=0,size;
                    do{Map<String,String> s=call(node.files(),"read","id",id[0],"offset",Long.toString(offset));out.write(Base64.getDecoder().decode(s.get("data")));long next=Long.parseLong(s.get("next"));size=Long.parseLong(s.get("size"));if(next<=offset&&offset<size)throw new IOException("Download interrupted");offset=next;}while(offset<size);}
                Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);temporary=null;
                SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(dialog,Language.text("nativeFiles.saved")));
            }catch(Exception ex){error(dialog,ex);}finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){}}});
        });
        remove.addActionListener(e->{if(JOptionPane.showConfirmDialog(dialog,Language.text("files.removeConfirm"),Language.text("desktopFiles.removeTitle"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            worker.execute(()->{try{call(node.files(),"remove","id",id[0]);SwingUtilities.invokeLater(dialog::dispose);}catch(Exception ex){error(dialog,ex);}});});
        timer.setInitialDelay(0);timer.start();dialog.setVisible(true);
    }
    static String statusText(Map<String,String> state){
        String error=state.getOrDefault("error","");
        return Language.system(state.getOrDefault("status","Waiting"))+"\n"+state.getOrDefault("name","")+"\n"
                +Language.text("desktopFiles.bytes","done",state.getOrDefault("done","0"),"size",state.getOrDefault("size","0"))
                +(error.isEmpty()?"":"\n"+Language.system(error));
    }
    private static void error(Component parent,Exception e){SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(parent,Language.system(e.getMessage()==null?"Transfer failed":e.getMessage()),Language.text("files.private"),JOptionPane.ERROR_MESSAGE));}
    private PrivateFileDialog(){}
}
