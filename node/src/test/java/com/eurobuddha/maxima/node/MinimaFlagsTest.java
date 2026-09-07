package com.eurobuddha.maxima.node;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class MinimaFlagsTest {

    @Test
    public void confFileLinesBecomeFlagsAndBareKeysAreBooleans() throws Exception {
        File conf = File.createTempFile("parlons-node-conf", ".conf");
        conf.deleteOnExit();
        Files.write(conf.toPath(), Arrays.asList(
                "# the host app's 0600 file",
                "mdspassword=abcd-efgh-ijkl-mnop",
                "mdsenable",
                "",
                "mdsinit=/tmp/dapps"), StandardCharsets.UTF_8);
        List<String> out = MinimaFlags.expandConf(Arrays.asList("-conf", conf.getAbsolutePath(), "-port", "9111"));
        assertEquals(Arrays.asList("-mdspassword", "abcd-efgh-ijkl-mnop", "-mdsenable", "-mdsinit", "/tmp/dapps",
                "-port", "9111"), out);
    }

    @Test
    public void bootLogMasksTheMdsPassword() {
        String log = MinimaFlags.redacted(Arrays.asList("-mdspassword", "abcd-efgh-ijkl-mnop", "-mdsenable", "-port", "9111"));
        assertEquals("-mdspassword **** -mdsenable -port 9111", log);
    }

    @Test
    public void tokeniseHonoursQuotes() {
        assertEquals(Arrays.asList("-host", "1.2.3.4", "-data", "/a dir/x"),
                MinimaFlags.tokenise("-host 1.2.3.4 -data \"/a dir/x\""));
    }
}
