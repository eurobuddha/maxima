package com.eurobuddha.maxima.node;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * The watch address goes into an admin "balance megammr:true address:.." command line (ParlonsControl). It is
 * checked when set and - since 2026-11-23 - when loaded from watch.txt: anything but a full address is dropped.
 */
public class NodeAccountWalletWatchTest {

    @Test public void onlyAFullAddressIsLoaded() throws Exception {
        Path dir = Files.createTempDirectory("watch");
        try {
            Path f = dir.resolve("watch.txt");
            Files.write(f, "MxABC;send address:0x01 amount:1".getBytes(StandardCharsets.UTF_8));
            assertEquals("", new NodeAccountWallet(dir.toFile()).watchAddress());
            Files.write(f, "MxG0861MPQ3ZQTM4GFTZ0UJA74Y48A4GDPYM1NTVKDTU0B34BFDV86G5A0PD21N\n".getBytes(StandardCharsets.UTF_8));
            assertEquals("MxG0861MPQ3ZQTM4GFTZ0UJA74Y48A4GDPYM1NTVKDTU0B34BFDV86G5A0PD21N", new NodeAccountWallet(dir.toFile()).watchAddress());
        } finally {
            for (java.io.File x : dir.toFile().listFiles()) x.delete();
            dir.toFile().delete();
        }
    }
}
