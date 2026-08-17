package com.eurobuddha.maxima.app.relay;

import android.content.Context;

import com.eurobuddha.maxima.app.AndroidContribution;
import com.eurobuddha.maxima.app.EventLog;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.server.RelayRuntime;

import java.io.File;

/**
 * The phone as a genuine forwarding relay — but only when it's safe.
 *
 * A phone is not a public server, so this is strictly gated: it runs the real
 * {@link RelayRuntime} (the same forwarding code the desktop/server node uses)
 * ONLY while the phone is plugged in, above {@link #START_BATTERY}% and on
 * Wi-Fi, and the user has "Help the network" on. It stands down automatically
 * the moment any of those stops being true — with a battery hysteresis so a
 * reading hovering around the threshold can't flap it on and off.
 *
 * It runs on its own dedicated port and data dir (never the node's), under the
 * phone's own identity, so it's genuinely YOU relaying for others.
 */
public final class RelayHost {

    public enum State { OFF, RUNNING }

    /** Why the relay is not running — drives a plain-language suggestion. */
    public enum Blocker { NONE, CONTRIB_OFF, NEEDS_WIFI, NEEDS_CHARGING, NEEDS_BATTERY }

    /** Dedicated relay listener port (distinct from the node's direct port). */
    public static final int RELAY_PORT = 9535;
    /** Start relaying only at/above this battery %. */
    public static final int START_BATTERY = 60;
    /** …and keep going down to here before standing down (hysteresis). */
    private static final int STOP_BATTERY = 55;
    private static final int RATE = 600;          // messages/min per peer, server default
    private static final String VERSION = "1.0.48";

    private final Context mCtx;
    private final MaximaNode mNode;
    private final AndroidContribution mPolicy;

    private volatile RelayRuntime mRuntime;
    private volatile State mState = State.OFF;
    private volatile Blocker mBlocker = Blocker.NEEDS_CHARGING;
    private volatile String mDetail = "off";

    public RelayHost(Context zCtx, MaximaNode zNode, AndroidContribution zPolicy) {
        mCtx = zCtx;
        mNode = zNode;
        mPolicy = zPolicy;
    }

    public State state() {
        return mState;
    }

    public Blocker blocker() {
        return mBlocker;
    }

    public String detail() {
        return mDetail;
    }

    public int port() {
        return RELAY_PORT;
    }

    public RelayRuntime.Stats stats() {
        RelayRuntime r = mRuntime;
        return r == null ? null : r.stats();
    }

    /** Heartbeat: start or stand down per the gate. Cheap; safe to call every tick. */
    public synchronized void tick() {
        boolean running = mState == State.RUNNING;
        Blocker b = gate(running);
        mBlocker = b;
        if (b == Blocker.NONE) {
            if (!running) {
                startRelay();
            } else {
                mDetail = "relaying for others on port " + RELAY_PORT;
            }
        } else {
            if (running) {
                stopRelay(reason(b));
            }
            mDetail = detailFor(b);
        }
    }

    public synchronized void shutdown() {
        stopRelay("shutting down");
    }

    // ---------------------------------------------------------------

    private Blocker gate(boolean running) {
        if (!AndroidContribution.isEnabled(mCtx)) {
            return Blocker.CONTRIB_OFF;
        }
        if (!mPolicy.isUnmetered()) {
            return Blocker.NEEDS_WIFI;
        }
        if (!mPolicy.isCharging()) {
            return Blocker.NEEDS_CHARGING;
        }
        // Hysteresis: need >= 60% to START, but keep running down to 55%.
        int floor = running ? STOP_BATTERY : START_BATTERY;
        if (mPolicy.batteryPercent() < floor) {
            return Blocker.NEEDS_BATTERY;
        }
        return Blocker.NONE;
    }

    private void startRelay() {
        try {
            File dir = new File(mCtx.getFilesDir(), "relay");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            RelayRuntime rt = new RelayRuntime(
                    mNode.identity(), RELAY_PORT, VERSION, RATE, "", dir.toPath());
            rt.setBlobBytes(0);   // forward messages + mailbox; no media shelf on a phone
            rt.start();
            mRuntime = rt;
            mState = State.RUNNING;
            mDetail = "relaying for others on port " + RELAY_PORT;
            EventLog.add("RELAY started on " + RELAY_PORT);
        } catch (Exception e) {
            mRuntime = null;
            mState = State.OFF;
            mDetail = "relay could not start: " + e.getMessage();
            EventLog.add("RELAY start failed: " + e.getMessage());
        }
    }

    private void stopRelay(String zWhy) {
        RelayRuntime rt = mRuntime;
        mRuntime = null;
        mState = State.OFF;
        if (rt != null) {
            try {
                rt.stop();
            } catch (Exception ignored) {
            }
            EventLog.add("RELAY stopped (" + zWhy + ")");
        }
    }

    private static String reason(Blocker b) {
        switch (b) {
            case CONTRIB_OFF: return "help turned off";
            case NEEDS_WIFI: return "left Wi-Fi";
            case NEEDS_CHARGING: return "unplugged";
            case NEEDS_BATTERY: return "battery low";
            default: return "conditions changed";
        }
    }

    private String detailFor(Blocker b) {
        switch (b) {
            case CONTRIB_OFF: return "off";
            case NEEDS_WIFI: return "needs Wi-Fi to relay";
            case NEEDS_CHARGING: return "plug in to relay for others";
            case NEEDS_BATTERY: return "charge above " + START_BATTERY + "% to relay (now "
                    + mPolicy.batteryPercent() + "%)";
            default: return "ready";
        }
    }
}
