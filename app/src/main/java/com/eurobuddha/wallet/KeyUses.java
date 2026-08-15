package com.eurobuddha.wallet;

/**
 * KeyUses — the durable one-time-signature (WOTS) uses counter.
 *
 * <p><b>Why this exists.</b> Each Minima {@link org.minima.objects.keys.TreeKey} leaf is a
 * Winternitz one-time key. Signing <i>twice</i> at the same {@code uses} index leaks enough of the
 * private key material to forge signatures — i.e. it lets an attacker steal the funds guarded by
 * that key. Preventing a reuse is the single most important correctness/security requirement of the
 * whole wallet.
 *
 * <p><b>THE SAFETY CONTRACT (must hold for every implementation):</b>
 * <ol>
 *   <li><b>Reserve-before-sign.</b> {@link #reserveNextUse(int)} MUST durably persist
 *       {@code uses := n+1} (a committed / fsynced write, not an async one) <i>before</i> it
 *       returns the leaf index {@code n} to be signed. A caller obtains {@code n} ONLY through this
 *       method, and only after the +1 is already on disk. A crash between the persist and the
 *       actual signature therefore <i>skips</i> leaf {@code n} (harmless — a wasted leaf) and can
 *       never cause the same leaf to be handed out twice. Never sign-then-persist.</li>
 *   <li><b>Read = MAX across all sources.</b> {@link #currentUses(int)} returns the MAXIMUM value
 *       seen across every available source (local mirrors, a restored backup, a future chain
 *       probe). Signing <i>below</i> the true count is the only dangerous direction, so a higher
 *       value must always win; a partial or stale source can raise the counter but never lower it.
 *       {@link #recordExternalUses(int, int)} folds an externally-learned count in under the same
 *       MAX rule.</li>
 * </ol>
 *
 * <p>The API is deliberately shaped so a signature <i>cannot</i> be produced without first advancing
 * the counter: {@link WalletCore#signTransactionID} calls {@link #reserveNextUse(int)} and signs the
 * leaf it returns. There is no method that yields a leaf index without persisting the advance.
 *
 * <p>M0 note: a {@link PrefsKeyUses SharedPreferences}-backed implementation is sufficient for this
 * milestone. Seed-derived encryption of this store and its portable/manual backup are M3.
 */
public interface KeyUses {

    /**
     * The number of signatures already consumed for {@code keyIndex}, i.e. the next unused leaf.
     * MUST return the MAX across every source the implementation knows about. Never signs.
     */
    int currentUses(int keyIndex);

    /**
     * Reserve and durably commit the next leaf for {@code keyIndex}.
     *
     * <p>Computes {@code n = currentUses(keyIndex)}, persists {@code uses := n+1} with a committed
     * (synchronous, flushed) write to <b>every</b> mirror, and only then returns {@code n} — the
     * leaf the caller must sign. If the durable write fails this MUST throw rather than return a
     * leaf, so a signature is never emitted against an unpersisted advance.
     *
     * @return the leaf index {@code n} to sign (the value BEFORE the increment)
     */
    int reserveNextUse(int keyIndex);

    /**
     * Fold an externally-learned uses count (restored backup, chain probe, mirror reconcile) into
     * the store under the MAX rule: after this call {@code currentUses(keyIndex) >= uses}. Used to
     * raise — never lower — the counter. Persisted durably.
     */
    void recordExternalUses(int keyIndex, int uses);

    /**
     * Every key index this store currently holds a record for, mapped to its MAX-reconciled uses
     * count. Used to build the portable vault snapshot so that EVERY known index is persisted — a
     * higher index can never be silently dropped (and thus reset to 0) on a later restore. An index
     * that has never been touched need not appear; callers that require index 0 add it explicitly.
     */
    java.util.Map<Integer, Integer> snapshotAllUses();
}
