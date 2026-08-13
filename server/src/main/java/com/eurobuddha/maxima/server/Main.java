package com.eurobuddha.maxima.server;

import com.eurobuddha.maxima.core.identity.Bip39;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Headless Maxima relay.
 *
 * <pre>
 *   maxima-server [--port 9001] [--data ~/.maxima] [--rate 600]
 * </pre>
 *
 * The seed phrase lives in {@code &lt;data&gt;/seed.txt}, generated on first run.
 * It is a real Minima phrase, so the same identity can be reproduced on another
 * machine - or on a phone - simply by using the same words.
 */
public final class Main {

    /** Build version. Keep in step with dist/ and the app's versionName. */
    public static final String VERSION = "0.1.0";

    public static void main(String[] args) throws Exception {
        int port = 9001;
        String data = System.getProperty("user.home") + "/.maxima";
        int rate = 600;
        String version = "1.0.48";

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--data": data = args[++i]; break;
                case "--rate": rate = Integer.parseInt(args[++i]); break;
                case "--version": version = args[++i]; break;
                default: break;
            }
        }

        Path dir = Paths.get(data);
        Files.createDirectories(dir);
        Path seedFile = dir.resolve("seed.txt");

        String phrase;
        if (Files.exists(seedFile)) {
            phrase = new String(Files.readAllBytes(seedFile), StandardCharsets.UTF_8).trim();
            System.out.println("Loaded identity from " + seedFile);
        } else {
            List<String> words = Bip39.generate(24);
            phrase = String.join(" ", words);
            Files.write(seedFile, phrase.getBytes(StandardCharsets.UTF_8));
            try {
                // Best effort: this file is a spendable Minima seed.
                Files.setPosixFilePermissions(seedFile,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {
            }
            System.out.println("Generated a NEW identity at " + seedFile);
            System.out.println();
            System.out.println("  !! This phrase is also a Minima WALLET seed. Back it up like money.");
            System.out.println("  " + phrase);
            System.out.println();
        }

        MaximaIdentity id = MaximaIdentity.fromPhrase(phrase);

        System.out.println("Maxima relay " + VERSION + " starting");
        System.out.println("  identity : " + id.mxIdentity().substring(0, 44) + "...");
        System.out.println("  port     : " + port);
        System.out.println("  data     : " + dir);
        System.out.println("  rate cap : " + rate + " msg/min per destination");

        RelayServer relay = new RelayServer(id, port, version);
        relay.setRateLimit(rate);
        relay.start();

        System.out.println("  listening on 0.0.0.0:" + port);
        System.out.println();

        Runtime.getRuntime().addShutdownHook(new Thread(relay::stop));

        // Status line, so an operator can see it working at a glance.
        while (true) {
            Thread.sleep(30_000);
            System.out.printf("[relay] conns=%d routes=%d relayed=%d stored=%d dropped=%d mail=%d dir=%d%n",
                    relay.connectionCount(), relay.routeCount(),
                    relay.relayedCount(), relay.storedCount(), relay.droppedCount(),
                    relay.mailbox().totalItems(), relay.directory().size());
        }
    }
}
