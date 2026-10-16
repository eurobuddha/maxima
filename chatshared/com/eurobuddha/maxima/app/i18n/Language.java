package com.eurobuddha.maxima.app.i18n;

import android.app.Activity;
import android.app.Application;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import com.eurobuddha.maxima.app.R;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** Explicit device language and native-resource bindings, shared by both Android apps.
 * Only marked application labels are rebound. Messages, names, addresses and input values
 * stay literal. Locale changes never recreate an activity or its transfer/call workers. */
public final class Language {
    private static final String PREFS = "parlons_language";
    private static final String[] CODES = {"en", "zh-Hans", "ru"};
    private static final String[] NAMES = {"English", "简体中文", "Русский"};
    private static final Map<View, Map<String, Binding>> bindings = new WeakHashMap<>();
    private static Application application;
    private static volatile String current = "en";
    private static java.lang.ref.WeakReference<AlertDialog> chooser = new java.lang.ref.WeakReference<>(null);
    private Language() {}

    public static boolean supported(String code) {
        for (String candidate : CODES) if (candidate.equals(code)) return true;
        return false;
    }
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    public static String code() { return current; }
    public static Locale locale() { return Locale.forLanguageTag(current); }
    public static boolean hasChoice(Context context) { return prefs(context).getBoolean("chosen", false); }

    public static void initialize(Application app) {
        application = app;
        String saved = prefs(app).getString("code", "en");
        current = supported(saved) ? saved : "en";
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleList explicit = app.getSystemService(LocaleManager.class).getApplicationLocales();
            if (!explicit.isEmpty() && supported(explicit.get(0).toLanguageTag())) {
                String systemChoice = explicit.get(0).toLanguageTag();
                if (!systemChoice.equals(current)) prefs(app).edit().putBoolean("chosen", true).apply();
                current = systemChoice;
            }
        }
        prefs(app).edit().putString("code", current).apply();
        applyPlatform(app);
        NotificationLabels.update(app);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle state) {}
            public void onActivityStarted(Activity a) { apply(a.getWindow().getDecorView()); }
            public void onActivityResumed(Activity a) {
                // Incoming call screens remain immediately available before a language has been chosen.
                String name = a.getClass().getSimpleName();
                if (("MainActivity".equals(name) || "OnboardingActivity".equals(name)) && !a.isFinishing())
                    a.getWindow().getDecorView().post(() -> { if (!a.isFinishing() && !a.isDestroyed() && !hasChoice(a)) choose(a, true); });
            }
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle state) {}
            public void onActivityDestroyed(Activity a) {
                AlertDialog dialog = chooser.get();
                if (dialog != null && dialog.getOwnerActivity() == a) dialog.dismiss();
            }
        });
    }
    private static void applyPlatform(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            LocaleList requested = LocaleList.forLanguageTags(current);
            if (!requested.equals(manager.getApplicationLocales())) manager.setApplicationLocales(requested);
        } else AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(current));
    }
    public static void select(Context context, String code) {
        if (!supported(code)) throw new IllegalArgumentException("Unsupported language: " + code);
        current = code;
        prefs(context).edit().putString("code", code).putBoolean("chosen", true).apply();
        applyPlatform(context);
        refresh();
    }
    /** Called by activities handling locale/layoutDirection changes without recreation.
     * Android 16 can also change the screenLayout compatibility bit on the first app-locale
     * selection; manifests handle that configuration too. Existing views relayout normally. */
    public static void configurationChanged(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleList explicit = context.getSystemService(LocaleManager.class).getApplicationLocales();
            String code = explicit.isEmpty() ? current : explicit.get(0).toLanguageTag();
            if (supported(code) && !code.equals(current)) {
                current = code;
                prefs(context).edit().putString("code", code).putBoolean("chosen", true).apply();
            }
            // A system "default" selection retains the user's explicit in-app language.
            if (explicit.isEmpty()) applyPlatform(context);
        }
        if (context instanceof Activity) apply(((Activity)context).getWindow().getDecorView());
        refresh();
    }
    public static Resources resources(Context context) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocales(LocaleList.forLanguageTags(current));
        return context.createConfigurationContext(config).getResources();
    }
    public static String text(Context context, int resource, Object... arguments) {
        Resources resources = resources(context);
        return arguments.length == 0 ? resources.getString(resource) : resources.getString(resource, arguments);
    }
    public static String quantity(Context context, int resource, int count, Object... arguments) {
        return resources(context).getQuantityString(resource, count, arguments);
    }
    /** Locale-aware display time; never used for serialized dates or protocol values. */
    public static String shortStamp(long timestamp) {
        String skeleton = System.currentTimeMillis() - timestamp < 20L * 60 * 60 * 1000 ? "Hm" : "MMMd";
        return date(timestamp, skeleton);
    }
    public static String date(long timestamp, String skeleton) {
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(locale(), skeleton);
        return new java.text.SimpleDateFormat(pattern, locale()).format(new java.util.Date(timestamp));
    }
    /** Calendar-day comparison, as in desktop ChatsPanel: handles DST and New Year. */
    static int recentDay(long timestamp, long now, java.util.TimeZone zone) {
        java.util.Calendar day = new java.util.GregorianCalendar(zone);
        java.util.Calendar cursor = new java.util.GregorianCalendar(zone);
        day.setTimeInMillis(timestamp); cursor.setTimeInMillis(now);
        for (int offset = 0; offset < 7; offset++) {
            if (day.get(java.util.Calendar.YEAR) == cursor.get(java.util.Calendar.YEAR)
                    && day.get(java.util.Calendar.DAY_OF_YEAR) == cursor.get(java.util.Calendar.DAY_OF_YEAR)) return offset;
            cursor.add(java.util.Calendar.DAY_OF_YEAR, -1);
        }
        return -1;
    }
    public static String dayLabel(Context context, long timestamp, boolean shortWeek) {
        int offset = recentDay(timestamp, System.currentTimeMillis(), java.util.TimeZone.getDefault());
        if (offset == 0) return text(context, R.string.l10n_date_today);
        if (offset == 1) return text(context, R.string.l10n_date_yesterday);
        return date(timestamp, shortWeek && offset > 1 ? "EEEE" : shortWeek ? "yMMMd" : "yMMMMEEEEd");
    }
    public static String ago(long elapsedMillis) {
        android.icu.text.RelativeDateTimeFormatter formatter = android.icu.text.RelativeDateTimeFormatter.getInstance(locale());
        long minutes = Math.max(0, elapsedMillis) / 60_000;
        if (minutes == 0) return formatter.format(android.icu.text.RelativeDateTimeFormatter.Direction.PLAIN,
                android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit.NOW);
        android.icu.text.RelativeDateTimeFormatter.RelativeUnit unit = minutes < 60
                ? android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MINUTES
                : minutes < 1440 ? android.icu.text.RelativeDateTimeFormatter.RelativeUnit.HOURS
                : android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS;
        long quantity = minutes < 60 ? minutes : minutes < 1440 ? minutes / 60 : minutes / 1440;
        return formatter.format(quantity, android.icu.text.RelativeDateTimeFormatter.Direction.LAST, unit);
    }
    /** Carries a resource through the existing UI factories without treating arbitrary text as a key. */
    public static final class Message implements CharSequence {
        final int resource; final Object[] arguments; final boolean upper; final Integer count;
        Message(int resource, Object[] arguments, boolean upper) {
            this(resource, arguments, upper, null);
        }
        Message(int resource, Object[] arguments, boolean upper, Integer count) {
            this.resource = resource; this.arguments = arguments.clone(); this.upper = upper; this.count = count;
            for (Object argument : arguments) if (argument != null && !(argument instanceof String)
                    && !(argument instanceof Number) && !(argument instanceof Boolean) && !(argument instanceof Character)
                    && !(argument instanceof Message))
                throw new IllegalArgumentException("Translation arguments must be immutable values");
        }
        String resolve(Context context) {
            Object[] resolved = arguments.clone();
            for (int i = 0; i < resolved.length; i++)
                if (resolved[i] instanceof Message) resolved[i] = ((Message) resolved[i]).resolve(context);
            String value;
            if (resource == 0) {
                StringBuilder joined = new StringBuilder();
                for (Object part : resolved) joined.append(part);
                value = joined.toString();
            } else if (resource == -1) value = date(((Number) resolved[0]).longValue(), (String) resolved[1]);
            else if (resource == -2) value = ago(System.currentTimeMillis() - ((Number) resolved[0]).longValue());
            else value = count == null ? text(context, resource, resolved) : quantity(context, resource, count, resolved);
            return upper ? value.toUpperCase(locale()) : value;
        }
        public String toString() { return resolve(application); }
        public int length() { return toString().length(); }
        public char charAt(int index) { return toString().charAt(index); }
        public CharSequence subSequence(int start, int end) { return toString().subSequence(start, end); }
    }
    public static Message msg(int resource, Object... arguments) { return new Message(resource, arguments, false); }
    public static Message count(int resource, int count) { return new Message(resource, new Object[]{count}, false, count); }
    public static Message count(int resource, int count, Object... arguments) { return new Message(resource, arguments, false, count); }
    /** Immutable composed display text. Literal fragments never become translation keys. */
    public static Message join(Object... parts) { return new Message(0, parts, false); }
    public static Message stamp(long timestamp, String skeleton) { return new Message(-1, new Object[]{timestamp, skeleton}, false); }
    public static Message since(long timestamp) { return new Message(-2, new Object[]{timestamp}, false); }
    public static final class TextBuilder {
        private final java.util.List<Object> parts = new ArrayList<>();
        public TextBuilder append(Object part) { parts.add(part); return this; }
        public Message build() { return join(parts.toArray()); }
    }
    public static CharSequence upper(CharSequence value) {
        if (value instanceof Message) { Message m = (Message) value; return new Message(m.resource, m.arguments, true, m.count); }
        return value.toString().toUpperCase(locale());
    }
    public static void setText(TextView view, CharSequence value) { set(view, "text", value); }
    public static void hint(TextView view, CharSequence value) { set(view, "hint", value); }
    public static void description(View view, CharSequence value) { set(view, "description", value); }
    private static final class Binding {
        final Message message; String last;
        Binding(Message message, String last) { this.message = message; this.last = last; }
    }
    private static void set(View view, String property, CharSequence value) {
        CharSequence resolved = value instanceof Message ? ((Message) value).resolve(view.getContext()) : value;
        if (value instanceof Message) bindings.computeIfAbsent(view, key -> new LinkedHashMap<>()).put(property, new Binding((Message) value, resolved.toString()));
        else if (bindings.containsKey(view)) bindings.get(view).remove(property);
        write(view, property, resolved);
    }
    private static void write(View view, String property, CharSequence value) {
        if ("text".equals(property)) ((TextView) view).setText(value);
        else if ("hint".equals(property)) ((TextView) view).setHint(value);
        else if ("description".equals(property)) view.setContentDescription(value);
        else throw new IllegalArgumentException("Unknown translated attribute: " + property);
    }
    public static void refresh() {
        if (application != null) NotificationLabels.update(application);
        // Values contain only resource IDs and interpolation arguments, never a view/activity.
        for (View view : new ArrayList<>(bindings.keySet())) {
            Map<String, Binding> entries = bindings.get(view);
            if (view == null || entries == null) continue;
            java.util.Iterator<Map.Entry<String, Binding>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Binding> entry = iterator.next();
                String property = entry.getKey(); Binding binding = entry.getValue();
                CharSequence actual = "text".equals(property) ? ((TextView) view).getText()
                        : "hint".equals(property) ? ((TextView) view).getHint() : view.getContentDescription();
                // An existing event handler may have replaced a static XML label with a status.
                // Never resurrect an obsolete label or overwrite an edited input value.
                if (!binding.last.contentEquals(actual == null ? "" : actual)) { iterator.remove(); continue; }
                binding.last = binding.message.resolve(view.getContext());
                write(view, property, binding.last);
            }
        }
    }
    /** XML marks only application-owned attributes: l10n:text=resource;hint=resource. */
    public static void apply(View root) {
        Object tag = root.getTag();
        if (tag instanceof String && ((String) tag).startsWith("l10n:")) {
            root.setTag(null);
            for (String item : ((String) tag).substring(5).split(";")) {
                String[] pair = item.split("=", 2);
                if (pair.length != 2) throw new IllegalArgumentException("Invalid language binding");
                int resource = root.getResources().getIdentifier(pair[1], "string", root.getContext().getPackageName());
                if (resource == 0) throw new IllegalArgumentException("Unknown language resource: " + pair[1]);
                set(root, pair[0], msg(resource));
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i));
        }
    }
    public static void choose(Activity activity, boolean required) {
        AlertDialog existing = chooser.get();
        if ((existing != null && existing.isShowing()) || activity.isFinishing() || activity.isDestroyed()) return;
        int selected = -1;
        if (hasChoice(activity)) for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(current)) selected = i;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("Language · 语言 · Язык")
                .setSingleChoiceItems(NAMES, selected, (dialog, which) -> { select(activity, CODES[which]); dialog.dismiss(); })
                .setCancelable(!required);
        if (!required) builder.setNegativeButton(text(activity, R.string.l10n_language_close), null);
        AlertDialog dialog = builder.create();
        dialog.setOwnerActivity(activity);
        chooser = new java.lang.ref.WeakReference<>(dialog);
        dialog.show();
    }
}
