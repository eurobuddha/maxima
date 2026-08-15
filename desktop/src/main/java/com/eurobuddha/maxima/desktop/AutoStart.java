package com.eurobuddha.maxima.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * "Set and forget": register the app to start at login, per-OS, without admin.
 *
 * A relay is only useful while it is up, and a home desktop is rebooted often —
 * so the default posture is to relaunch on login. This is a per-user login item
 * (no elevation): a launchd LaunchAgent on macOS, an XDG autostart entry on
 * Linux, and a Startup-folder shortcut on Windows. Installed once (idempotent),
 * and only when we can identify the installed app's launcher — in a dev run from
 * Gradle there is no launcher to point at, so it is skipped.
 */
final class AutoStart {

    private static final String LABEL = "com.eurobuddha.maxima.node";

    private AutoStart() {
    }

    /** Best-effort install; never throws into the caller. */
    static void installOnce() {
        try {
            String launcher = installedLauncher();
            if (launcher == null) {
                return;   // dev run (no packaged launcher) — nothing to autostart
            }
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac")) {
                installMac(launcher);
            } else if (os.contains("win")) {
                installWindows(launcher);
            } else {
                installLinux(launcher);
            }
        } catch (Exception ignored) {
            // Autostart is a convenience, never a hard requirement.
        }
    }

    /**
     * The path to the packaged launcher, or null when running unpackaged (dev).
     *
     * jpackage sets {@code jpackage.app-path} to the launched executable; its
     * absence means we are running from a plain classpath / Gradle, where there
     * is nothing durable to point a login item at.
     */
    private static String installedLauncher() {
        String p = System.getProperty("jpackage.app-path");
        if (p != null && !p.isEmpty() && Files.exists(Paths.get(p))) {
            return p;
        }
        return null;
    }

    private static void installMac(String launcher) throws IOException {
        Path dir = Paths.get(System.getProperty("user.home"), "Library", "LaunchAgents");
        Files.createDirectories(dir);
        Path plist = dir.resolve(LABEL + ".plist");
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\"><dict>\n"
                + "  <key>Label</key><string>" + LABEL + "</string>\n"
                + "  <key>ProgramArguments</key><array><string>" + xmlEscape(launcher)
                + "</string></array>\n"
                + "  <key>RunAtLoad</key><true/>\n"
                + "  <key>ProcessType</key><string>Background</string>\n"
                + "</dict></plist>\n";
        Files.write(plist, xml.getBytes(StandardCharsets.UTF_8));
    }

    private static void installLinux(String launcher) throws IOException {
        Path dir = Paths.get(System.getProperty("user.home"), ".config", "autostart");
        Files.createDirectories(dir);
        Path entry = dir.resolve("maxima-node.desktop");
        String desktop = "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=Maxima Node\n"
                + "Comment=Maxima relay (set-and-forget)\n"
                + "Exec=" + launcher + "\n"
                + "X-GNOME-Autostart-enabled=true\n"
                + "Terminal=false\n";
        Files.write(entry, desktop.getBytes(StandardCharsets.UTF_8));
    }

    private static void installWindows(String launcher) throws IOException {
        // A .bat in the Startup folder is the no-dependency way to launch at login
        // without a COM/shortcut library. Startup runs whatever it finds there.
        String appData = System.getenv("APPDATA");
        if (appData == null) {
            return;
        }
        Path startup = Paths.get(appData, "Microsoft", "Windows", "Start Menu",
                "Programs", "Startup");
        Files.createDirectories(startup);
        Path bat = startup.resolve("MaximaNode.bat");
        String script = "@echo off\r\nstart \"\" \"" + launcher + "\"\r\n";
        Files.write(bat, script.getBytes(StandardCharsets.UTF_8));
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
