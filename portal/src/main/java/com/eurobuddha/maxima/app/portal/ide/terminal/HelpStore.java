package com.eurobuddha.maxima.app.portal.ide.terminal;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Offline full help for every node command, harvested from the node itself
 * (help command:x → fullhelp) and bundled as assets/help.json.
 */
public class HelpStore {

    private static JSONObject HELP;

    private static synchronized JSONObject load(Context ctx) {
        if (HELP == null) {
            try {
                InputStream in = ctx.getAssets().open("help.json");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                HELP = new JSONObject(out.toString("UTF-8"));
            } catch (Exception e) {
                HELP = new JSONObject();
            }
        }
        return HELP;
    }

    public static boolean has(Context ctx, String command) {
        return load(ctx).has(command);
    }

    /** Short one-line help, or null. */
    public static String brief(Context ctx, String command) {
        JSONObject entry = load(ctx).optJSONObject(command);
        return entry != null ? entry.optString("help", null) : null;
    }

    /** The full help page, or null. */
    public static String full(Context ctx, String command) {
        JSONObject entry = load(ctx).optJSONObject(command);
        if (entry == null) return null;
        String fh = entry.optString("fullhelp", "");
        return fh.isEmpty() ? null : fh.trim();
    }
}
