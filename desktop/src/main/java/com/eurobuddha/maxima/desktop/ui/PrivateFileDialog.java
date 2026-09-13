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
        JFileChooser picker=new JFileChooser();picker.setDialogTitle("Send private file (up to 512 MB)");
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
        for(int i=0;i<labels.length;i++)labels[i]=array.getJSONObject(i).getString("name")+" · "+array.getJSONObject(i).getString("status");
        SwingUtilities.invokeLater(()->{if(labels.length==0){JOptionPane.showMessageDialog(parent,"No local file transfers");return;}
            JList<String> list=new JList<>(labels);list.setSelectedIndex(0);if(JOptionPane.showConfirmDialog(parent,new JScrollPane(list),"File transfers",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION&&list.getSelectedIndex()>=0)
                show(parent,node,array.getJSONObject(list.getSelectedIndex()).getString("id"),null,null,false);});
    }catch(Exception e){error(parent,e);}},"private-file-list").start();}
    private static void show(Component parent,DesktopNode node,String initialId,ChatFile offer,String peer,boolean group){
        JDialog dialog=new JDialog(SwingUtilities.getWindowAncestor(parent),offer==null?"Private file":offer.name,Dialog.ModalityType.MODELESS);
        JPanel box=new JPanel();box.setLayout(new BoxLayout(box,BoxLayout.Y_AXIS));box.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
        JTextArea status=new JTextArea("Encrypted file sharing with this conversation.\nActive downloads can also upload encrypted pieces.");status.setEditable(false);status.setLineWrap(true);status.setWrapStyleWord(true);status.setOpaque(false);box.add(status);
        JProgressBar progress=new JProgressBar(0,100);box.add(progress);
        JButton action=new JButton("Download"),save=new JButton("Save file…"),remove=new JButton("Remove local transfer");save.setEnabled(false);remove.setEnabled(false);box.add(action);box.add(save);box.add(remove);
        dialog.setContentPane(box);dialog.setSize(460,290);dialog.setLocationRelativeTo(parent);
        ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"private-file-sheet");t.setDaemon(true);return t;});
        String[] id={initialId};boolean[] exists={false},paused={false},busy={false};
        javax.swing.Timer timer=new javax.swing.Timer(1200,event->{if(busy[0])return;busy[0]=true;
            worker.execute(()->{try{Map<String,String> s=call(node.files(),"status","id",id[0]);SwingUtilities.invokeLater(()->{
                id[0]=s.getOrDefault("fileId",id[0]);exists[0]=true;paused[0]="true".equals(s.get("paused"))||"Failed".equals(s.get("status"));
                status.setText(s.get("status")+"\n"+s.getOrDefault("name","")+"\n"+s.getOrDefault("done","0")+" / "+s.getOrDefault("size","0")+" bytes"+(s.getOrDefault("error","").isEmpty()?"":"\n"+s.get("error")));
                progress.setValue((int)(100*Long.parseLong(s.getOrDefault("done","0"))/Math.max(1,Long.parseLong(s.getOrDefault("size","0")))));
                save.setEnabled("true".equals(s.get("ready")));boolean preparing="Preparing".equals(s.get("status"))||"Uploading".equals(s.get("status"));
                action.setEnabled(!preparing);remove.setEnabled(!preparing);action.setText(paused[0]?"Resume":"Pause sharing");
            });}catch(Exception e){if(offer==null)SwingUtilities.invokeLater(()->status.setText(e.getMessage()));}finally{SwingUtilities.invokeLater(()->busy[0]=false);}});
        });
        dialog.addWindowListener(new java.awt.event.WindowAdapter(){public void windowClosed(java.awt.event.WindowEvent e){timer.stop();worker.shutdownNow();}});
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        action.addActionListener(e->{String command=paused[0]?"resume":"pause";boolean known=exists[0];action.setEnabled(false);worker.execute(()->{
            try{if(!known){if(offer==null)throw new IOException("Transfer unavailable");call(node.files(),"download","ref",offer.ref(),"peer",peer,"group",Boolean.toString(group));}
                else call(node.files(),command,"id",id[0]);}catch(Exception ex){error(dialog,ex);}finally{SwingUtilities.invokeLater(()->action.setEnabled(true));}
        });});
        save.addActionListener(e->{JFileChooser fc=new JFileChooser();fc.setSelectedFile(new File(offer==null?"Parlons-file":offer.name));if(fc.showSaveDialog(dialog)!=JFileChooser.APPROVE_OPTION)return;
            Path destination=fc.getSelectedFile().toPath();if(Files.exists(destination)&&JOptionPane.showConfirmDialog(dialog,"Replace the existing file?","Save file",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            worker.execute(()->{Path temporary=null;try{temporary=Files.createTempFile(destination.toAbsolutePath().getParent(),".parlons-",".tmp");
                try(OutputStream out=Files.newOutputStream(temporary)){long offset=0,size;
                    do{Map<String,String> s=call(node.files(),"read","id",id[0],"offset",Long.toString(offset));out.write(Base64.getDecoder().decode(s.get("data")));long next=Long.parseLong(s.get("next"));size=Long.parseLong(s.get("size"));if(next<=offset&&offset<size)throw new IOException("Download interrupted");offset=next;}while(offset<size);}
                Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);temporary=null;
                SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(dialog,"File saved"));
            }catch(Exception ex){error(dialog,ex);}finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){}}});
        });
        remove.addActionListener(e->{if(JOptionPane.showConfirmDialog(dialog,"Remove this local transfer and stop sharing? Saved copies are kept.","Remove transfer",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            worker.execute(()->{try{call(node.files(),"remove","id",id[0]);SwingUtilities.invokeLater(dialog::dispose);}catch(Exception ex){error(dialog,ex);}});});
        timer.setInitialDelay(0);timer.start();dialog.setVisible(true);
    }
    private static void error(Component parent,Exception e){SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(parent,e.getMessage(),"Private file",JOptionPane.ERROR_MESSAGE));}
    private PrivateFileDialog(){}
}
