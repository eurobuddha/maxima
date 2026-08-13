package com.eurobuddha.maxima.core.services;

/**
 * Whether this node should take on work for other people right now.
 *
 * A phone can contribute a great deal, but not unconditionally: holding other
 * people's mail on a metered connection at 3% battery is not generosity, it is
 * a bug. The POLICY lives with the host (which knows about batteries and
 * tariffs); :core only asks.
 *
 * Refusing must always be safe. Every Tier 1 duty is replicated k-of-n and
 * idempotent precisely so that any individual phone can decline, or vanish
 * mid-request, without the network caring.
 */
public interface ContributionPolicy {

    /** Accept and hold mail for peers who are offline. */
    boolean acceptMailbox();

    /** Accept content-addressed blobs for others. */
    boolean acceptStorage();

    /** Answer directory lookups and share learned addresses. */
    boolean acceptDirectory();

    /** Countersign delivery receipts. Cheap - a signature, no storage. */
    boolean acceptWitness();

    /** Always on. Sensible for a server, and for a phone on wifi and charging. */
    ContributionPolicy ALWAYS = new ContributionPolicy() {
        public boolean acceptMailbox() { return true; }
        public boolean acceptStorage() { return true; }
        public boolean acceptDirectory() { return true; }
        public boolean acceptWitness() { return true; }
        public String toString() { return "always"; }
    };

    /** Consume only. Nothing is stored or served for anyone else. */
    ContributionPolicy NONE = new ContributionPolicy() {
        public boolean acceptMailbox() { return false; }
        public boolean acceptStorage() { return false; }
        public boolean acceptDirectory() { return false; }
        public boolean acceptWitness() { return false; }
        public String toString() { return "none"; }
    };
}
