package com.eurobuddha.maxima.app.i18n;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.eurobuddha.maxima.app.R;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Debug-only: actual chat dialogs, unpaired synthetic peer, no send/payment actions. API 29+. */
// WindowInspector is available only on API 29+; this class is excluded from release builds.
@androidx.annotation.RequiresApi(29)
public final class LanguageChatPreviewActivity extends AppCompatActivity {
    private static final String DRAFT = "Draft 中文 Привет {count} $1";
    private static final String CAPTION = "Caption 中文 Привет {count} $1";
    private String screen, mode;
    private Activity first;
    private EditText composer;
    private View dialogRoot;
    private final List<EditText> inputs = new ArrayList<>();
    private final List<String> values = new ArrayList<>();
    private int created, destroyed;
    private Application.ActivityLifecycleCallbacks callbacks;
    private BroadcastReceiver receiver;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Window inspection fixture requires Android 10 or newer");
        boolean portal = !getPackageName().equals("com.eurobuddha.maxima.app");
        screen = portal ? "com.eurobuddha.maxima.app.portal.CloudChatActivity"
                : "com.eurobuddha.maxima.app.chat.ChatActivity";
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "caption";
        callbacks = new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) { if (a.getClass().getName().equals(screen)) created++; }
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {
                if (!a.getClass().getName().equals(screen) || first != null) return;
                first = a;
                // Stop fixture polling before its posted callback runs. No account is paired.
                try {
                    Handler handler = (Handler) field(a, "mHandler");
                    handler.removeCallbacks((Runnable) field(a, portal ? "mTick" : "mPayWatch"));
                } catch (Exception e) { throw new IllegalStateException(e); }
                a.getWindow().getDecorView().post(() -> install(a));
            }
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) { if (a.getClass().getName().equals(screen)) destroyed++; }
        };
        getApplication().registerActivityLifecycleCallbacks(callbacks);
        receiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                if (first == null || dialogRoot == null) return;
                String code = intent.getStringExtra("code");
                if (!Language.supported(code)) return;
                Language.select(first, code);
                first.getWindow().getDecorView().postDelayed(() -> verify(), 1000);
            }
        };
        ContextCompat.registerReceiver(this, receiver,
                new IntentFilter(getPackageName()+".LANGUAGE_CHAT_FIXTURE"), ContextCompat.RECEIVER_EXPORTED);
        String peer = "0x" + "1".repeat(64);
        startActivity(new Intent().setClassName(this, screen).putExtra("peer", peer)
                .putExtra("conversation", peer).putExtra("name", "Chats 中文 {count} $1"));
    }

    private void install(Activity activity) {
        try {
            composer = activity.findViewById(R.id.chat_input);
            composer.setText(DRAFT); composer.setSelection(3, 8);
            List<View> before = WindowInspector.getGlobalWindowViews();
            if ("caption".equals(mode)) {
                Method method = activity.getClass().getDeclaredMethod("showCaptionDialog",
                        android.net.Uri.class, android.graphics.Bitmap.class);
                method.setAccessible(true); method.invoke(activity, android.net.Uri.EMPTY, null);
            } else if ("link".equals(mode)) {
                Method menu=com.eurobuddha.maxima.app.chat.ChatLinkText.class.getDeclaredMethod("showLinkMenu",TextView.class,String.class);
                menu.setAccessible(true);menu.invoke(null,new TextView(activity),"https://example.invalid/中文/Русский?count=$1");
            } else if ("info".equals(mode)) {
                showInfo(activity);
            } else if ("confirm".equals(mode)) {
                com.eurobuddha.maxima.core.contacts.Contact contact = new com.eurobuddha.maxima.core.contacts.Contact();
                contact.name = "Chats 中文 {count} $1";
                Method method = activity.getClass().getDeclaredMethod("confirmPay",
                        com.eurobuddha.maxima.core.chat.ChatEngine.class,
                        com.eurobuddha.maxima.core.contacts.Contact.class, String.class,
                        org.minima.objects.base.MiniNumber.class, String.class);
                method.setAccessible(true);
                method.invoke(activity, null, contact, "fixture-address",
                        new org.minima.objects.base.MiniNumber("1.23456789"), CAPTION);
            } else {
                Method method = activity.getClass().getDeclaredMethod("payment".equals(mode) ? "payContact" : "attachSheet");
                method.setAccessible(true); method.invoke(activity);
            }
            for (View root : WindowInspector.getGlobalWindowViews()) if (!before.contains(root)) dialogRoot = root;
            if (dialogRoot == null) throw new IllegalStateException("Dialog did not open");
            collectInputs(dialogRoot, inputs);
            for (int i = 0; i < inputs.size(); i++) {
                String value = "payment".equals(mode) && i == 0 ? "1.23456789" : CAPTION;
                inputs.get(i).setText(value); inputs.get(i).setSelection(2, 5); values.add(value);
            }
            dialogRoot.postDelayed(() -> verify(), 500);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void showInfo(Activity activity) throws Exception {
        String body = com.eurobuddha.maxima.core.chat.ChatPay.wrap("1.23456789", "MINIMA",
                "0x" + "abcd1234".repeat(8), CAPTION);
        Object entry;
        Class<?> type;
        String methodName;
        if (getPackageName().equals("com.eurobuddha.maxima.app")) {
            type = com.eurobuddha.maxima.core.chat.ChatEngine.Entry.class;
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(String.class,
                    String.class, String.class, String.class, String.class, long.class, boolean.class, String.class);
            constructor.setAccessible(true);
            entry = constructor.newInstance("fixture", "peer", "", "self", body, 1767225600000L, true, "read");
            methodName = "showMessageInfo";
        } else {
            type = Class.forName(screen + "$Msg");
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
            entry = constructor.newInstance();
            String[] names = {"body", "mine", "time", "arrived", "state", "delivered"};
            Object[] values = {body, true, 1767225600000L, 1767225720000L, "read", 22};
            for (int i=0;i<names.length;i++) { Field f=type.getDeclaredField(names[i]);f.setAccessible(true);f.set(entry,values[i]); }
            Field group=activity.getClass().getDeclaredField("mGroup");group.setAccessible(true);group.set(activity,true);
            methodName = "infoDialog";
        }
        Method method=activity.getClass().getDeclaredMethod(methodName,type);method.setAccessible(true);method.invoke(activity,entry);
    }

    private void verify() {
        try {
            boolean same = created == 1 && destroyed == 0 && !first.isDestroyed()
                    && composer == first.findViewById(R.id.chat_input)
                    && DRAFT.contentEquals(composer.getText())
                    && composer.getSelectionStart() == 3 && composer.getSelectionEnd() == 8
                    && WindowInspector.getGlobalWindowViews().contains(dialogRoot);
            List<EditText> current = new ArrayList<>(); collectInputs(dialogRoot, current);
            same &= current.equals(inputs);
            for (int i = 0; i < inputs.size(); i++) same &= values.get(i).contentEquals(inputs.get(i).getText())
                    && inputs.get(i).getSelectionStart() == 2 && inputs.get(i).getSelectionEnd() == 5;
            JSONArray labels = new JSONArray(); collectLabels(dialogRoot, labels);
            JSONObject result = new JSONObject().put("same_views_and_drafts", same)
                    .put("locale", Language.code()).put("mode", mode).put("labels", labels)
                    .put("created", created).put("destroyed", destroyed).put("input_count", inputs.size());
            try (java.io.FileOutputStream out = openFileOutput("language-chat-proof.json", MODE_PRIVATE)) {
                out.write(result.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void collectInputs(View view, List<EditText> out) {
        if (view instanceof EditText) out.add((EditText)view);
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) collectInputs(((ViewGroup)view).getChildAt(i),out);
    }
    private static void collectLabels(View view, JSONArray out) {
        if (view instanceof TextView) {
            TextView text = (TextView)view;
            if (text.getText().length()>0) out.put(text.getText().toString());
            if (text.getHint()!=null) out.put(text.getHint().toString());
        }
        if (view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) collectLabels(((ViewGroup)view).getChildAt(i),out);
    }
    @Override protected void onDestroy() {
        if (receiver != null) unregisterReceiver(receiver);
        if (callbacks != null) getApplication().unregisterActivityLifecycleCallbacks(callbacks);
        super.onDestroy();
    }
}
