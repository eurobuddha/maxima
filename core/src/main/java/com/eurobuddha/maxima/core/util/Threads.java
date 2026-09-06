package com.eurobuddha.maxima.core.util;

import java.lang.reflect.Method;

/**
 * Thread factory that uses VIRTUAL threads where the runtime has them (JDK 21+) and plain
 * daemon platform threads everywhere else (JDK 11/17 boxes, Android). Compiled against
 * Java 11, so the Loom API is reached reflectively; every fallback keeps the old behaviour
 * exactly, which is what lets one jar serve the whole mixed fleet.
 *
 * Why it matters: a relay parks one thread per attached client in a blocking read. A
 * platform thread costs a ~1 MB stack and a kernel task (the TasksMax ceiling that once
 * killed the accept loop); a virtual thread costs a few hundred bytes, so a box holds
 * thousands of quiet clients instead of hundreds.
 */
public final class Threads {

    private static final Method OF_VIRTUAL;
    private static final Method BUILDER_NAME;
    private static final Method BUILDER_UNSTARTED;

    static {
        Method ov = null;
        Method nm = null;
        Method un = null;
        try {
            ov = Thread.class.getMethod("ofVirtual");
            Class<?> builder = Class.forName("java.lang.Thread$Builder");
            nm = builder.getMethod("name", String.class);
            un = builder.getMethod("unstarted", Runnable.class);
            // Prove it actually works here (a preview build or a restricted runtime may
            // expose the API and still refuse): build one and never start it.
            Object b = ov.invoke(null);
            b = nm.invoke(b, "vthread-probe");
            un.invoke(b, (Runnable) () -> { });
        } catch (Throwable t) {
            ov = null;
            nm = null;
            un = null;
        }
        OF_VIRTUAL = ov;
        BUILDER_NAME = nm;
        BUILDER_UNSTARTED = un;
    }

    private Threads() {
    }

    /** True when this runtime can create virtual threads. */
    public static boolean virtualAvailable() {
        return OF_VIRTUAL != null;
    }

    /**
     * An UNSTARTED thread: virtual when {@code zPreferVirtual} and the runtime allows it,
     * otherwise a daemon platform thread with the same name. Virtual threads are always
     * daemon, so either way the thread never keeps a JVM alive.
     */
    public static Thread newThread(String zName, Runnable zTask, boolean zPreferVirtual) {
        if (zPreferVirtual && OF_VIRTUAL != null) {
            try {
                Object b = OF_VIRTUAL.invoke(null);
                b = BUILDER_NAME.invoke(b, zName);
                return (Thread) BUILDER_UNSTARTED.invoke(b, zTask);
            } catch (Throwable ignored) {
                // fall through to a platform thread - never fail to serve a connection
            }
        }
        Thread t = new Thread(zTask, zName);
        t.setDaemon(true);
        return t;
    }
}
