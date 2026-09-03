package com.eurobuddha.maxima.node;

import java.io.File;

import org.minima.system.Main;
import org.minima.system.commands.CommandRunner;
import org.minima.system.params.GeneralParams;
import org.minima.system.params.GlobalParams;

/**
 * Parlons Node — the merged VPS binary. M1: boot a FULL Minima node ({@code new Main()}) in-process,
 * inside a JVM we own, and prove it comes up + answers the in-process command API. The {@code :core}
 * Maxima cape and the Parlons account layer are added on top in the next steps.
 *
 * <p>Guardrail #1: we do NOT call {@code org.minima.Minima.main()} — that reads stdin, calls
 * {@code System.exit}, {@code resetDefaults()}, and installs a JVM-global uncaught-exception handler.
 * We replicate only the essential setup here and drive the node via {@link Main} + {@link CommandRunner}.
 */
public final class ParlonsNodeMain {

    public static void main(String[] zArgs) throws Exception {
        // --- configure the embedded node's global params (mirrors Minima.main, minus the CLI bits) ---
        GeneralParams.resetDefaults();
        File dataFolder = new File(System.getProperty("parlons.node.data",
                new File(System.getProperty("user.home"), ".parlons-node").getAbsolutePath()));
        File minimaFolder = new File(dataFolder, GlobalParams.MINIMA_BASE_VERSION);
        GeneralParams.DATA_FOLDER     = minimaFolder.getAbsolutePath();
        GeneralParams.MDSFILE_PORT    = GeneralParams.MINIMA_PORT + 2;
        GeneralParams.MDSCOMMAND_PORT = GeneralParams.MINIMA_PORT + 3;
        GeneralParams.RPC_PORT        = GeneralParams.MINIMA_PORT + 4;
        minimaFolder.mkdirs();

        // JDBC drivers the node's SqlDB layer needs (same registration Minima.main does).
        try { new org.h2.Driver(); } catch (Exception ignored) {}
        try { Class.forName("com.mysql.cj.jdbc.Driver"); } catch (Exception ignored) {}

        System.out.println("[parlons-node] booting embedded Minima node "
                + GlobalParams.getFullMicroVersion() + " at " + GeneralParams.DATA_FOLDER);

        // --- boot the full node in-process (Main is a MessageProcessor; spawns its own threads) ---
        final Main main = new Main();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { main.shutdown(); } catch (Throwable ignored) {}
        }));

        // --- probe the node via the in-process command API once it has come up ---
        new Thread(() -> {
            for (int i = 0; i < 30; i++) {
                try {
                    Thread.sleep(4000);
                    Object res = CommandRunner.getRunner().runSingleCommand("status");
                    System.out.println("[parlons-node] status " + i + ": " + res);
                } catch (Throwable t) {
                    System.out.println("[parlons-node] status " + i + " not ready: " + t);
                }
            }
        }, "parlons-node-probe").start();

        // Keep the JVM alive — the node runs on its own threads.
        Object lock = new Object();
        synchronized (lock) { lock.wait(); }
    }
}
