package com.eurobuddha.maxima.app;

import android.content.Intent;
import com.eurobuddha.maxima.app.chat.ShareReceiverBase;
import com.eurobuddha.maxima.app.portal.*;
import com.eurobuddha.maxima.cloud.ParlonsRemote;
import org.minima.utils.json.*;
import java.util.*;
import java.util.function.Consumer;

public final class ShareActivity extends ShareReceiverBase {
    @Override protected boolean ready(){return CloudSession.isPaired(this);}
    @Override protected void load(Consumer<List<Target>> success,Consumer<String> failure){
        PortalService.start(this);
        CloudSession.connectInteractive(this,new CloudSession.Cb(){
            public void ok(ParlonsRemote remote){
                try {
                    List<Target> targets=new ArrayList<>();
                    JSONArray contacts=(JSONArray)remote.contacts().get("contacts");
                    if(contacts!=null)for(Object value:contacts){JSONObject c=(JSONObject)value;
                        targets.add(new Target(String.valueOf(c.get("key")),c.get("name")==null?"":c.get("name").toString(),false));}
                    JSONArray summaries=(JSONArray)remote.summaries().get("summaries");
                    if(summaries!=null)for(Object value:summaries){JSONObject c=(JSONObject)value;
                        if(Boolean.TRUE.equals(c.get("group")))targets.add(new Target(String.valueOf(c.get("peer")),
                            c.get("name")==null?"":c.get("name").toString(),true));}
                    success.accept(targets);
                }catch(Exception e){failure.accept(e.getMessage());}
            }
            public void err(String message){failure.accept(message);}
        });
    }
    @Override protected Intent conversation(Target target){
        return new Intent(this,CloudChatActivity.class).putExtra(CloudChatActivity.EXTRA_PEER,target.key)
            .putExtra(CloudChatActivity.EXTRA_NAME,target.name).putExtra(CloudChatActivity.EXTRA_GROUP,target.group);
    }
}
