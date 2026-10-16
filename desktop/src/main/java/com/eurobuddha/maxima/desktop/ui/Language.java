package com.eurobuddha.maxima.desktop.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.json.JSONObject;

/** Manual device-local language selection. Only explicitly marked UI text is translated. */
public final class Language {
    private static final String KEY = "parlons.language";
    private static final String BINDING = Language.class.getName() + ".text";
    private static final String TOOLTIP = Language.class.getName() + ".tooltip";
    private static final String FONT = Language.class.getName() + ".font";
    private static final Pattern SLOT = Pattern.compile("\\{(\\w+)\\}");
    private static final JSONObject CATALOG = load("messages.json");
    private static final JSONObject SYSTEM_KEYS = load("system-keys.json");
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static Preferences preferences = Preferences.userRoot().node(DesktopNode.PREFS);
    private static volatile String language = savedLanguage();
    private Language() { }

    private static JSONObject load(String name) {
        try (InputStream in = Language.class.getResourceAsStream("/i18n/" + name)) {
            if (in == null) throw new IllegalStateException("Missing language catalog");
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    public static boolean supported(String code) {
        return "en".equals(code) || "zh-Hans".equals(code) || "ru".equals(code);
    }
    private static String savedLanguage() {
        String code = preferences.get(KEY, "");
        return supported(code) ? code : "en";
    }
    public static String code() { return language; }
    public static Locale locale() { return Locale.forLanguageTag(language); }
    public static boolean hasChoice() { return supported(preferences.get(KEY, "")); }

    /** Swing callbacks and preferences change together on the event thread. No window rebuild. */
    public static void choose(String code) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Use the Swing event thread");
        if (!supported(code)) throw new IllegalArgumentException("Unsupported language: " + code);
        preferences.put(KEY, code);
        language = code;
        applyDialogLabels();
        for (Runnable listener : LISTENERS) listener.run();
    }

    public static void addListener(Runnable listener) { LISTENERS.addIfAbsent(listener); }
    public static void removeListener(Runnable listener) { LISTENERS.remove(listener); }

    /** Returns false when the first-launch chooser is closed without making a choice. */
    public static boolean chooser(Component parent) {
        String[] codes = {"en", "zh-Hans", "ru"};
        String[] names = {"English", "简体中文", "Русский"};
        int selected = java.util.Arrays.asList(codes).indexOf(code());
        int choice = JOptionPane.showOptionDialog(parent, "Language · 语言 · Язык", "Parlons!",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, names, names[selected]);
        if (choice < 0) return false;
        choose(codes[choice]);
        return true;
    }

    public static void applyDialogLabels() {
        UIManager.put("OptionPane.okButtonText", text("common.ok"));
        UIManager.put("OptionPane.cancelButtonText", text("common.cancel"));
        UIManager.put("OptionPane.yesButtonText", text("desktop.yes"));
        UIManager.put("OptionPane.noButtonText", text("desktop.no"));
        FileChooserLabels.apply();
    }

    /** Construct the existing modal picker using the explicit app choice, before setting any draft. */
    public static javax.swing.JFileChooser fileChooser() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Use the Swing event thread");
        applyDialogLabels();
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setLocale(locale());
        chooser.updateUI();
        FileChooserLabels.configure(chooser);
        return chooser;
    }

    /** Fixed protocol statuses/errors only. Never pass user messages, names or filenames. */
    public static String system(String value) { return system(value, 0, false); }
    private static String system(String value, int depth, boolean diagnosticOnly) {
        if (value == null) return "";
        if (depth >= 8) return value;
        String key = SYSTEM_KEYS.optString(value, "");
        if (!key.isEmpty() && (!diagnosticOnly || key.startsWith("serverError.") || key.startsWith("pairing."))) return text(key);
        for (String[] entry : DIAGNOSTIC_PREFIXES) if (value.startsWith(entry[0])) {
            String tail = value.substring(entry[0].length());
            return text(entry[1], entry[2], entry[2].equals("error") ? system(tail, depth + 1, true) : tail);
        }
        for (int i = 0; i < COUNTER_PREFIXES.length; i++) if (value.startsWith(COUNTER_PREFIXES[i])) {
            String number = value.substring(COUNTER_PREFIXES[i].length());
            if (number.matches("[0-9]+")) return text(i == 0 ? "serverError.counterAboveCurrent" : "serverError.counterAboveMaximum", i == 0 ? "current" : "maximum", number);
        }
        Matcher group = GROUP_LIMIT.matcher(value);
        if (group.matches()) return text("serverError.groupLimit", "maximum", group.group(1), "requested", group.group(2));
        return value;
    }
    private static final String[][] DIAGNOSTIC_PREFIXES = {
        {"resync refused: ", "serverError.resyncRefused", "error"},
        {"node error: ", "serverError.nodeFailure", "error"},
        {"hosting failed: ", "serverError.hostFailure", "error"},
        {"could not create the collection folder: ", "serverError.collectionFolder", "error"},
        {"could not list: ", "serverError.listFailure", "error"},
        {"could not delete: ", "serverError.deleteFailure", "error"},
        {"unknown contact ", "iosError.unknownContact", "id"},
        {"chunk unavailable: ", "iosError.chunkUnavailable", "id"},
    };
    private static final String[] COUNTER_PREFIXES = {"can only RAISE above the current ", "above the key's maximum "};
    private static final Pattern GROUP_LIMIT = Pattern.compile("a group holds at most ([0-9]+) members [(]([0-9]+) asked[)]");

    public static Message msg(String key, Object... pairs) { return new Message(key, pairs); }
    public static String text(String key, Object... pairs) { return msg(key, pairs).toString(); }

    /** Immutable descriptor, like the Android Language.Message; user values stay literal. */
    public static final class Message implements CharSequence {
        private final String key;
        private final Map<String, Object> arguments;
        private Message(String key, Object[] pairs) {
            if (!CATALOG.getJSONObject("en").has(key)) throw new IllegalArgumentException("Unknown translation key: " + key);
            if (pairs.length % 2 != 0) throw new IllegalArgumentException("Named argument pairs required");
            this.key = key;
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
            arguments = Collections.unmodifiableMap(values);
        }
        @Override public String toString() { return format(language, key, arguments); }
        @Override public int length() { return toString().length(); }
        @Override public char charAt(int index) { return toString().charAt(index); }
        @Override public CharSequence subSequence(int start, int end) { return toString().subSequence(start, end); }
    }

    static String format(String code, String key, Map<String, Object> arguments) {
        Object entry = CATALOG.getJSONObject(code).get(key);
        String template;
        if (entry instanceof JSONObject) {
            Object count = arguments.get("count");
            if (!(count instanceof Number)) throw new IllegalArgumentException("Plural count must be a number");
            JSONObject forms = (JSONObject) entry;
            template = forms.optString(plural(code, ((Number) count).doubleValue()), forms.getString("other"));
        } else template = (String) entry;
        Matcher matcher = SLOT.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!arguments.containsKey(name)) throw new IllegalArgumentException("Missing translation argument: " + name);
            // Replace once: braces, '$' and backslashes in names/addresses are never interpreted.
            matcher.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(arguments.get(name))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // CLDR cardinal rules for the three supported languages (integer counts and decimal fallback).
    static String plural(String code, double count) {
        double n = Math.abs(count);
        if ("zh-Hans".equals(code)) return "other";
        if ("en".equals(code)) return n == 1 ? "one" : "other";
        if (!Double.isFinite(n) || n != Math.floor(n)) return "other";
        double ten = n % 10, hundred = n % 100;
        if (ten == 1 && hundred != 11) return "one";
        if (ten >= 2 && ten <= 4 && (hundred < 12 || hundred > 14)) return "few";
        return "many";
    }

    private static final class Binding {
        final java.util.function.Supplier<String> value;
        final UnaryOperator<String> transform;
        String applied;
        Binding(java.util.function.Supplier<String> value, UnaryOperator<String> transform) { this.value = value; this.transform = transform; }
    }

    public static <T extends JComponent> T bind(T component, CharSequence value) {
        return bind(component, value, UnaryOperator.identity());
    }
    /** Dates and structured previews update in place, leaving live media controls untouched. */
    public static <T extends JComponent> T render(T component, java.util.function.Supplier<String> value) {
        Binding binding = new Binding(value, UnaryOperator.identity());
        binding.applied = value.get();
        component.putClientProperty(BINDING, binding);
        setText(component, binding.applied);
        return component;
    }
    public static void title(javax.swing.JDialog dialog, CharSequence value) {
        dialog.getRootPane().putClientProperty("parlons.language.title", value instanceof Message ? value : null);
        dialog.setTitle(value.toString());
    }
    public static String date(long ms, String pattern) {
        return new java.text.SimpleDateFormat(pattern, locale()).format(new java.util.Date(ms));
    }
    public static String relative(long ageMs) {
        long minutes = Math.max(0, ageMs / 60_000);
        return minutes < 60 ? text("desktopChat.minutesAgo", "count", minutes)
                : minutes < 1440 ? text("desktopChat.hoursAgo", "count", minutes / 60)
                : text("desktopChat.daysAgo", "count", minutes / 1440);
    }
    public static void tooltip(JComponent component, Message value) {
        component.putClientProperty(TOOLTIP, value);
        component.setToolTipText(value.toString());
        if (component.getAccessibleContext() != null) component.getAccessibleContext().setAccessibleName(value.toString());
    }
    static <T extends JComponent> T bind(T component, CharSequence value, UnaryOperator<String> transform) {
        Binding binding = value instanceof Message ? new Binding(value::toString, transform) : null;
        component.putClientProperty(BINDING, binding);
        String result = transform.apply(value == null ? "" : value.toString());
        setText(component, result);
        if (binding != null) binding.applied = result;
        return component;
    }
    private static void setText(JComponent c, String text) {
        if (c instanceof JLabel) ((JLabel) c).setText(text);
        else if (c instanceof AbstractButton) ((AbstractButton) c).setText(text);
        else throw new IllegalArgumentException("Only display labels/buttons may bind text");
        refreshFont(c);
    }
    public static Font fontFor(Font font, String text) {
        if (text == null || font.canDisplayUpTo(text) < 0) return font;
        Font system = new Font(Font.SANS_SERIF, font.getStyle(), 1).deriveFont(font.getSize2D());
        if (system.canDisplayUpTo(text) < 0) return system;
        Font chinese = Theme.chineseFont(font.getStyle(), font.getSize2D());
        // An unsupported emoji must not discard otherwise available Chinese glyphs.
        // Prefer the face that loses fewer code points; keep the OS face on a tie.
        return missingGlyphs(chinese, text) < missingGlyphs(system, text) ? chinese : system;
    }

    private static long missingGlyphs(Font font, String text) {
        return text.codePoints().filter(cp -> !font.canDisplay(cp)).count();
    }

    /** Keep the authored family/weight separate from the temporary glyph fallback. */
    public static void font(JComponent component, Font base) {
        FontBinding binding = fontBinding(component);
        binding.base = base;
        binding.apply(displayText(component));
    }
    public static void font(JComponent component, Font base, String text) {
        FontBinding binding = fontBinding(component);
        binding.base = base;
        binding.apply(text);
    }
    static void refreshFont(JComponent component) {
        fontBinding(component).apply(displayText(component));
    }
    static void resizeFont(JComponent component, float ratio) {
        FontBinding binding = fontBinding(component);
        if (binding.base != null) {
            binding.base = binding.base.deriveFont(binding.base.getSize2D() * ratio);
            binding.apply(displayText(component));
        }
    }
    private static String displayText(JComponent c) {
        if (c instanceof JLabel) return ((JLabel) c).getText();
        if (c instanceof AbstractButton) return ((AbstractButton) c).getText();
        // Never read password contents for glyph selection.
        if (c instanceof javax.swing.text.JTextComponent && !(c instanceof javax.swing.JPasswordField))
            return ((javax.swing.text.JTextComponent) c).getText();
        return "";
    }
    private static FontBinding fontBinding(JComponent component) {
        FontBinding binding = (FontBinding) component.getClientProperty(FONT);
        if (binding == null) {
            binding = new FontBinding(component);
            component.putClientProperty(FONT, binding);
        }
        return binding;
    }
    private static final class FontBinding implements javax.swing.event.DocumentListener {
        final JComponent component;
        Font base;
        boolean applying;
        javax.swing.text.Document document;
        FontBinding(JComponent component) {
            this.component = component;
            base = component.getFont();
            component.addPropertyChangeListener(event -> {
                if (applying) return;
                if ("font".equals(event.getPropertyName())) base = component.getFont();
                else if ("document".equals(event.getPropertyName())) observeDocument();
                else if (!"text".equals(event.getPropertyName())) return;
                apply(displayText(component));
            });
            observeDocument();
        }
        void observeDocument() {
            if (document != null) document.removeDocumentListener(this);
            document = component instanceof javax.swing.text.JTextComponent
                    && !(component instanceof javax.swing.JPasswordField)
                    // HTML parsing must finish before changing display properties;
                    // a font update during insert notification can invalidate its views.
                    && !(component instanceof javax.swing.JEditorPane)
                    ? ((javax.swing.text.JTextComponent) component).getDocument() : null;
            if (document != null) document.addDocumentListener(this);
        }
        void apply(String text) {
            if (applying || base == null) return;
            applying = true;
            try {
                Font next = fontFor(base, text);
                if ("zh-Hans".equals(code())) next = fontFor(next, "中文");
                if (!next.equals(component.getFont())) component.setFont(next);
            } finally { applying = false; }
        }
        public void insertUpdate(javax.swing.event.DocumentEvent e) { apply(displayText(component)); }
        public void removeUpdate(javax.swing.event.DocumentEvent e) { apply(displayText(component)); }
        public void changedUpdate(javax.swing.event.DocumentEvent e) { apply(displayText(component)); }
    }

    /** Refresh labels in place; never set text on an editable field or replace its document. */
    public static void refresh(Component root) {
        if (root instanceof javax.swing.JDialog) {
            javax.swing.JDialog dialog = (javax.swing.JDialog) root;
            Object value = dialog.getRootPane().getClientProperty("parlons.language.title");
            if (value instanceof Message) dialog.setTitle(value.toString());
        }
        Dimension previous = root.getPreferredSize();
        Dimension max = root.isMaximumSizeSet() ? root.getMaximumSize() : null;
        if (root instanceof JComponent) {
            JComponent c = (JComponent) root;
            Message tooltip = (Message) c.getClientProperty(TOOLTIP);
            if (tooltip != null) tooltip(c, tooltip);
            Binding binding = (Binding) c.getClientProperty(BINDING);
            if (binding != null) {
                String current = c instanceof JLabel ? ((JLabel) c).getText() : ((AbstractButton) c).getText();
                if (!java.util.Objects.equals(current, binding.applied)) c.putClientProperty(BINDING, null);
                else {
                    binding.applied = binding.transform.apply(binding.value.get());
                    setText(c, binding.applied);
                }
            }
            refreshFont(c);
        }
        if (root instanceof Container) for (Component child : ((Container) root).getComponents()) refresh(child);
        // Same sizing convention as Theme.resizeFonts, preserving layout caps.
        if (max != null && !(root instanceof javax.swing.JScrollPane)) {
            Dimension preferred = root.getPreferredSize();
            root.setMaximumSize(new Dimension(max.width == previous.width ? preferred.width : max.width,
                    max.height == previous.height ? preferred.height : Math.max(max.height, preferred.height)));
        }
        root.revalidate();
        root.repaint();
    }

    // Package-private test seam: tests use an isolated preferences node, never the user's settings.
    static Preferences usePreferences(Preferences value) {
        Preferences previous = preferences; preferences = value; language = savedLanguage(); return previous;
    }
}
