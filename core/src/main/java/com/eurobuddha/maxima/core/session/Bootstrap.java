package com.eurobuddha.maxima.core.session;

import java.util.Arrays;
import java.util.List;

/**
 * The relays a fresh node reaches for first, before it has learned any others.
 *
 * These are the "our-build" public relays — they answer the Maxima extensions
 * (mailbox, directory, witness, the reachability {@code Probe}, and relay
 * gossip), which stock classic Minima nodes do not. A new desktop relay uses
 * this list two ways: to ask one of them to dial it back and PROVE its port is
 * open, and as the seed set for relay-gossip discovery. Multi-homing means we
 * never depend on any single one, and gossip means the set grows past this
 * hardcoded floor the moment other nodes come online.
 *
 * Deliberately spread across operators and countries: a default list that all
 * lands in one datacentre is a single point of failure wearing the costume of a
 * decentralised one.
 */
public final class Bootstrap {

    private Bootstrap() {
    }

    /** The our-build relays that support the Maxima extensions and probe/gossip. */
    public static final List<String> RELAYS = Arrays.asList(
            "95.179.179.181:9501",     // sally       - Amsterdam, NL
            "65.109.31.226:9501",      // eurobuddha  - Helsinki, FI
            "45.77.246.226:9501",      // maxima      - Singapore, SG
            "78.141.237.9:9501",       // openproject - London, GB
            "45.77.57.24:9501",        // vigilance   - London, GB
            "192.248.151.55:9501",     // megammr     - London, GB
            "31.125.188.214:8001");    // the Pi      - residential, GB
}
