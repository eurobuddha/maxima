package com.eurobuddha.maxima.app;

import android.content.Intent;
import com.eurobuddha.maxima.app.chat.*;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.chat.Group;
import java.util.*;
import java.util.function.Consumer;

public final class ShareActivity extends ShareReceiverBase {
    @Override protected boolean ready(){return SeedStore.hasIdentity(this);}
    @Override protected void load(Consumer<List<Target>> success,Consumer<String> failure){
        MaximaService.start(this);
        new Thread(()->{
            try {
                if(MaximaService.port()==null || MaximaService.chat()==null)throw new IllegalStateException();
                List<Target> targets=new ArrayList<>();
                for(Contact c:MaximaService.port().contacts()) targets.add(new Target(c.publicKey,c.name,false));
                for(Group g:MaximaService.chat().groups()) targets.add(new Target(g.id,g.name,true));
                success.accept(targets);
            }catch(Exception e){failure.accept("Connecting");}
        },"share-contacts").start();
    }
    @Override protected Intent conversation(Target target){
        return new Intent(this,ChatActivity.class).putExtra(ChatActivity.EXTRA_CONVERSATION,target.key);
    }
}
