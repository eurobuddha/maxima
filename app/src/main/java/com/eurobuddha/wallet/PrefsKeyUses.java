package com.eurobuddha.wallet;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * SharedPreferences-backed {@link KeyUses} for M0.
 *
 * <p>Implements the SAFETY CONTRACT from {@link KeyUses}:
 * <ul>
 *   <li><b>Durable reserve-before-sign.</b> {@link #reserveNextUse(int)} writes {@code n+1} with
 *       {@link SharedPreferences.Editor#commit()} (synchronous — returns only after the write is
 *       persisted) to BOTH mirrors before returning {@code n}. If either commit fails it throws.</li>
 *   <li><b>Redundancy + MAX read.</b> The count is mirrored to two separate prefs files and every
 *       read reconciles to the MAX, so a partial restore or a torn write can only ever raise the
 *       counter, never lower it.</li>
 * </ul>
 *
 * <p>App-private SharedPreferences survive app upgrades automatically. Seed-derived encryption of
 * this blob and the portable/manual backup path are deferred to M3 per the plan; the shape here
 * (two mirrors, MAX-on-read, commit-before-return) is the durable foundation those build on.
 *
 * <p>NOTE: this class touches the Android framework, so pure-JVM unit tests use a lightweight
 * in-memory {@link KeyUses} instead; the safety logic that tests exercise lives in
 * {@link WalletCore#signTransactionID}.
 */
public class PrefsKeyUses implements KeyUses {

    private static final String FILE_A = "keyuses_mirror_a";
    private static final String FILE_B = "keyuses_mirror_b";
    private static final String KEY_PREFIX = "uses_";

    private final SharedPreferences mMirrorA;
    private final SharedPreferences mMirrorB;

    /** Entry prefix for THIS derivation's counters — {@code "uses_"} or {@code "uses_<ns>_"}. */
    private final String mPrefix;

    /** FUND-CRITICAL: the two mirror files are physically shared by EVERY
     *  PrefsKeyUses instance in the process (one per PaymentSender, plus the
     *  wallet tab). A per-instance monitor lets two instances reserve the same
     *  one-time WOTS leaf and sign two different transactions with it - the
     *  exact key-forgery the reserve-before-sign contract exists to prevent.
     *  reserveNextUse locks on this ONE static monitor so the read-modify-write
     *  is atomic across the whole process. */
    private static final Object USES_LOCK = new Object();

    /** Legacy ({@link Derivation#V1}) store: entries stay {@code uses_<i>}, byte-identical to what
     *  already exists on every shipped install. */
    public PrefsKeyUses(Context zContext) {
        this(zContext, "");
    }

    /**
     * A counter store namespaced per {@link Derivation}. FUND-CRITICAL: the v1 and v2 wallet keys are
     * DIFFERENT keys that both live at key index 0, so their counts must not share an entry — a shared
     * entry would let one derivation's spends silently advance (or, worse, appear to justify) the
     * other's leaf position. {@code ""} keeps the legacy entry names untouched.
     */
    public PrefsKeyUses(Context zContext, String zNamespace) {
        Context app = zContext.getApplicationContext();
        mMirrorA = app.getSharedPreferences(FILE_A, Context.MODE_PRIVATE);
        mMirrorB = app.getSharedPreferences(FILE_B, Context.MODE_PRIVATE);
        mPrefix  = (zNamespace == null || zNamespace.isEmpty()) ? KEY_PREFIX : KEY_PREFIX + zNamespace + "_";
    }

    private String key(int zIndex) {
        return mPrefix + zIndex;
    }

    @Override
    public int currentUses(int zKeyIndex) {
        synchronized (USES_LOCK) {
            int a = mMirrorA.getInt(key(zKeyIndex), 0);
            int b = mMirrorB.getInt(key(zKeyIndex), 0);
            return Math.max(a, b);
        }
    }

    /**
     * Reconcile the counter UPWARD to at least {@code zValue} - raise-only, never
     * lower. Used to correct a counter that under-reports how many times this
     * key has actually signed (anywhere), so the next {@link #reserveNextUse}
     * cannot re-issue an already-spent one-time leaf. Lowering is refused because
     * it is the one dangerous direction (it would reuse a leaf and expose the
     * key). Returns the resulting count. Persisted to both mirrors with commit().
     */
    public int raiseTo(int zKeyIndex, int zValue) {
        synchronized (USES_LOCK) {
            int cur = currentUses(zKeyIndex);
            if (zValue <= cur) {
                return cur;   // never lower
            }
            boolean okA = mMirrorA.edit().putInt(key(zKeyIndex), zValue).commit();
            boolean okB = mMirrorB.edit().putInt(key(zKeyIndex), zValue).commit();
            if (!okA || !okB) {
                throw new IllegalStateException(
                    "KeyUses: failed to persist raiseTo for key " + zKeyIndex
                        + " (mirrorA=" + okA + ", mirrorB=" + okB + ")");
            }
            return zValue;
        }
    }

    @Override
    public int reserveNextUse(int zKeyIndex) {
        synchronized (USES_LOCK) {
        int n = currentUses(zKeyIndex);
        int next = n + 1;

        // Durably persist the advance to BOTH mirrors BEFORE returning the leaf to sign.
        // commit() is synchronous and returns success/failure; if it fails we throw so no
        // signature is ever produced against an unpersisted advance.
        boolean okA = mMirrorA.edit().putInt(key(zKeyIndex), next).commit();
        boolean okB = mMirrorB.edit().putInt(key(zKeyIndex), next).commit();
        if (!okA || !okB) {
            throw new IllegalStateException(
                "KeyUses: failed to durably persist uses advance for key " + zKeyIndex
                    + " (mirrorA=" + okA + ", mirrorB=" + okB + ") — refusing to sign");
        }
        return n;
        }
    }

    @Override
    public java.util.Map<Integer, Integer> snapshotAllUses() {
      synchronized (USES_LOCK) {
        // Union the key indices recorded in EITHER mirror, then read each via the MAX-on-read path so
        // the returned value is the reconciled count. A torn/partial mirror can only add an index or
        // raise a value — never drop one — matching the store's raise-never-lower invariant.
        java.util.LinkedHashMap<Integer, Integer> out = new java.util.LinkedHashMap<>();
        java.util.TreeSet<Integer> indices = new java.util.TreeSet<>();
        collectIndices(mMirrorA, indices);
        collectIndices(mMirrorB, indices);
        for (int idx : indices) {
            out.put(idx, currentUses(idx));
        }
        return out;
      }
    }

    /** Indices recorded under THIS derivation's prefix only. Both derivations share the two mirror
     *  files, so the suffix must parse as a bare integer — that is what excludes another namespace's
     *  entries (e.g. {@code uses_v2_0} is skipped when this store's prefix is {@code uses_}). */
    private void collectIndices(SharedPreferences zPrefs, java.util.Set<Integer> zInto) {
        for (String k : zPrefs.getAll().keySet()) {
            if (k != null && k.startsWith(mPrefix)) {
                try {
                    zInto.add(Integer.parseInt(k.substring(mPrefix.length())));
                } catch (NumberFormatException ignore) { /* another namespace, or not a uses entry */ }
            }
        }
    }

    @Override
    public void recordExternalUses(int zKeyIndex, int zUses) {
        synchronized (USES_LOCK) {
            int merged = Math.max(currentUses(zKeyIndex), zUses);
            boolean okA = mMirrorA.edit().putInt(key(zKeyIndex), merged).commit();
            boolean okB = mMirrorB.edit().putInt(key(zKeyIndex), merged).commit();
            if (!okA || !okB) {
                throw new IllegalStateException(
                    "KeyUses: failed to durably record external uses for key " + zKeyIndex);
            }
        }
    }
}
