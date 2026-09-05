package com.eurobuddha.maxima.app.portal.wallet;

import android.widget.Toast;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Drives a multi-batch Distribute: spread a fixed amount across many addresses, working around
 * Minima's 14-recipient-per-transaction limit by auto-chaining up to {@link #MAX_BATCHES}
 * transactions. Each batch sends 14 recipients + one change output; the change coin from one
 * batch becomes the input of the next, identified by its exact coinid (captured from the posted
 * transaction) once it confirms on a new block.
 *
 * The heartbeat is {@link #onCoinsUpdated()}, called by {@link WalletActivity#reload()} after every
 * coin refresh (which itself fires on each NEWBLOCK via the NOTIFY receiver).
 */
public class DistributeManager {

    public static final int BATCH = 14;
    public static final int MAX_BATCHES = 4;            // 4 × 14 = 56 addresses
    private static final int EXPIRY_BLOCKS = 20;
    private static final int MAX_RETRIES = 3;

    private final WalletActivity act;
    private DistributeJob job;
    private boolean inFlight = false;   // a TxnBuilder is mid-post
    private boolean starting = false;   // reserved between the Tools-tab tap and start()

    public DistributeManager(WalletActivity a) {
        act = a;
        job = DistributeJob.load(a);    // resume a job left over from a previous run
    }

    /** True while a job exists (running or waiting between batches). */
    public boolean isActive() {
        return job != null;
    }

    /** Active, or reserved while addresses are being fetched (prevents a concurrent start). */
    public boolean isBusy() {
        return job != null || starting;
    }

    /** Reserve the right to start a job. Returns false if one is already running/starting. */
    public boolean reserve() {
        if (isBusy()) return false;
        starting = true;
        return true;
    }

    /** Release a reservation that never became a job (e.g. address fetch failed). */
    public void cancelReserve() {
        starting = false;
    }

    /** One-line progress string for the Tools tab, or null when idle. */
    public String statusLine() {
        if (job == null) return null;
        String s = "Distribute running · " + job.batchesDone + " batch(es) done · "
                + job.remaining.size() + " address(es) left"
                + (job.waiting ? " · waiting for confirmation…" : " · posting…");
        if (job.retryCount > 0) s += " · retry " + job.retryCount + "/" + MAX_RETRIES;
        return s;
    }

    // ===== start a new job =====

    /** Begin a new job: validate funds, build the job state, then post the first batch from the
     *  user's selected coins. Later batches chain off each batch's change coin (see onCoinsUpdated). */
    public void start(List<Coin> inputs, List<String> addrs, String per, String tokenid, String tokenName) {
        starting = false;   // committing the reservation (if any) into a real job
        if (job != null) { toast("A distribute job is already running."); return; }
        if (inputs.isEmpty() || addrs.isEmpty()) { toast("Nothing to distribute."); return; }

        BigDecimal total = sum(inputs);
        BigDecimal required = new BigDecimal(per).multiply(new BigDecimal(addrs.size()));
        if (required.compareTo(total) > 0) { toast("Selected coins don't cover the distribution."); return; }

        job = new DistributeJob();
        job.tokenid = tokenid;
        job.tokenName = tokenName;
        job.per = per;
        job.remaining = new ArrayList<>(addrs);

        // Batch 1 spends the user's selected coins.
        doNextBatch(inputs, total);
    }

    // ===== heartbeat: advance the chain on each coin refresh =====

    /** Heartbeat from every coin refresh: when the previous batch's change coin has confirmed,
     *  chain the next batch onto it; bail out if it never shows within EXPIRY_BLOCKS. */
    public void onCoinsUpdated() {
        if (job == null || inFlight || !job.waiting) return;

        // Give up on a stuck batch rather than hang forever.
        if (act.chainBlock() > 0 && job.atBlock > 0 && act.chainBlock() - job.atBlock > EXPIRY_BLOCKS) {
            abort("timed out waiting for the change coin to confirm");
            return;
        }

        Coin change = findChangeCoin();
        if (change == null) return;   // not confirmed yet — keep waiting

        // Change coin confirmed: feed it as the sole input of the next batch.
        job.waiting = false;
        List<Coin> inputs = Collections.singletonList(change);
        doNextBatch(inputs, new BigDecimal(change.amount));
    }

    // ===== core: build + post one batch =====

    /** Build one batch: up to 14 recipients plus a change output for the remainder, fetching a fresh
     *  change address first when there's a remainder to return. */
    private void doNextBatch(final List<Coin> inputs, final BigDecimal inputTotal) {
        final int n = Math.min(BATCH, job.remaining.size());
        final List<String> recipients = new ArrayList<>(job.remaining.subList(0, n));

        final BigDecimal perBD = new BigDecimal(job.per);
        final BigDecimal paid = perBD.multiply(new BigDecimal(n));
        final BigDecimal change = inputTotal.subtract(paid);
        // Minima burns inputs−outputs, so we MUST return any remainder as a change output.
        final boolean hasChange = change.signum() > 0;
        final String changeStr = hasChange ? change.stripTrailingZeros().toPlainString() : "";

        inFlight = true;
        toast("Distribute: posting batch " + (job.batchesDone + 1) + " (" + n + " addresses)…");

        if (hasChange) {
            act.node().cmd("getaddress", new NodeApi.Cb() {
                @Override public void onResult(org.json.JSONObject j) {
                    org.json.JSONObject r = j.optJSONObject("response");
                    String addr = r == null ? "" : r.optString("miniaddress", r.optString("address", ""));
                    if (addr.isEmpty()) { failBatch("could not get a change address"); return; }
                    postBatch(inputs, recipients, n, changeStr, addr);
                }
                @Override public void onError(String message) { failBatch(message); }
            });
        } else {
            postBatch(inputs, recipients, n, "", "");
        }
    }

    /** Post the batch transaction; on success advance the job, capture the change coinid to chain on,
     *  and either arm the wait for the next batch or complete. */
    private void postBatch(List<Coin> inputs, final List<String> recipients, final int n,
                           final String changeStr, final String changeAddr) {
        List<TxnBuilder.Out> outs = new ArrayList<>();
        for (String a : recipients) outs.add(new TxnBuilder.Out(a, job.per));
        final boolean hasChange = !changeStr.isEmpty();
        if (hasChange) outs.add(new TxnBuilder.Out(changeAddr, changeStr));

        final String internalid = TxnUtil.recordPosting(act,
                "Distribute batch " + (job.batchesDone + 1) + " → " + n + " addrs",
                Util.tidyAmount(new BigDecimal(job.per).multiply(new BigDecimal(n)).toPlainString()),
                job.tokenid, job.tokenName, inputs, outs, hasChange ? changeAddr : null, null);
        job.currentInternalId = internalid;

        new TxnBuilder(act, inputs, outs, job.tokenid, new TxnBuilder.Done() {
            @Override public void onPosted(String txpowid, List<TxnBuilder.OutCoin> outputs) {
                // Resolver fills the real on-chain txpowid via the stored input coinids.
                act.history().update(internalid, HistoryDb.STATUS_POSTED, null, "");

                // Remove the addresses we just funded.
                for (int i = 0; i < n; i++) job.remaining.remove(0);
                job.batchesDone++;
                job.retryCount = 0;

                // Capture the change coin's EXACT coinid to chain on (robust vs. address reuse).
                job.expectedChangeCoinId = hasChange ? changeCoinId(outputs, changeAddr, changeStr) : "";
                job.nextChangeAddr = changeAddr;
                job.expectedChangeAmt = changeStr;
                job.atBlock = act.chainBlock();
                inFlight = false;

                // More batches only if addresses remain, we're under the cap, AND there's a change coin
                // to fund them — without change there's nothing to chain onto.
                boolean more = !job.remaining.isEmpty()
                        && job.batchesDone < job.maxBatches
                        && hasChange;
                if (more) {
                    job.waiting = true;   // onCoinsUpdated picks up once the change coin confirms
                    job.save(act);        // persist so a restart resumes mid-distribution
                } else {
                    complete();
                }
                act.refreshTools();
            }

            @Override public void onFailed(String message) { failBatch(message); }
        }).run();
    }

    /** The change output's coinid: match by address (either form) + amount, else the last output. */
    private String changeCoinId(List<TxnBuilder.OutCoin> outputs, String changeAddr, String changeStr) {
        for (TxnBuilder.OutCoin oc : outputs) {
            if (oc.addressIs(changeAddr) && amountsEqual(oc.amount, changeStr)) return oc.coinid;
        }
        return outputs.isEmpty() ? "" : outputs.get(outputs.size() - 1).coinid;
    }

    // ===== finding the change coin to chain on =====

    /** Locate the confirmed, sendable change coin from the last batch — by exact coinid if captured,
     *  else by address+amount — so the next batch can spend it. Null until it confirms on-chain. */
    private Coin findChangeCoin() {
        for (Coin c : act.coins()) {
            if (!c.sendable || !c.confirmed) continue;
            if (!job.tokenid.equals(c.tokenid)) continue;
            if (!job.expectedChangeCoinId.isEmpty()) {
                if (job.expectedChangeCoinId.equals(c.coinid)) return c;   // exact, unambiguous
                continue;
            }
            // Fallback (no coinid captured): match by address + amount.
            if (!job.nextChangeAddr.equals(c.miniaddress) && !job.nextChangeAddr.equals(c.address)) continue;
            if (!job.expectedChangeAmt.isEmpty() && !amountsEqual(c.amount, job.expectedChangeAmt)) continue;
            return c;
        }
        return null;
    }

    // ===== terminal states =====

    /** All batches done: clear persisted state and refresh the UI. */
    private void complete() {
        inFlight = false;
        DistributeJob.clear(act);
        job = null;
        toast("Distribute complete.");
        act.refreshTools();
    }

    /** Give up: mark the current row errored, clear the job, and report how many addresses went unfunded. */
    private void abort(String why) {
        int left = job != null ? job.remaining.size() : 0;
        if (job != null && !job.currentInternalId.isEmpty()) {
            act.history().update(job.currentInternalId, HistoryDb.STATUS_ERROR, null, "Distribute aborted: " + why);
        }
        inFlight = false;
        DistributeJob.clear(act);
        job = null;
        toast("Distribute aborted (" + left + " address(es) unfunded): " + why);
        act.refreshTools();
    }

    /**
     * A batch failed. Batch 1 failing means nothing was sent — clear cleanly. A later batch failing
     * leaves its input (the previous change coin) intact, so retry it on the next block up to
     * {@link #MAX_RETRIES} before giving up — never silently strand a half-finished distribution.
     */
    private void failBatch(String message) {
        if (NodeApi.ERR_NOT_ENABLED.equals(message)) message = "wallet not enabled in Minima Core";
        inFlight = false;
        if (job == null) return;

        if (!job.currentInternalId.isEmpty()) {
            act.history().update(job.currentInternalId, HistoryDb.STATUS_ERROR, null, "batch attempt failed: " + message);
        }

        if (job.batchesDone == 0) {
            toast("Distribute couldn't start: " + message);
            DistributeJob.clear(act);
            job = null;
            act.refreshTools();
            return;
        }

        job.retryCount++;
        if (job.retryCount > MAX_RETRIES) {
            abort("batch " + (job.batchesDone + 1) + " failed after " + MAX_RETRIES + " retries: " + message);
            return;
        }
        job.waiting = true;
        job.save(act);
        toast("Distribute batch failed; retrying next block (" + job.retryCount + "/" + MAX_RETRIES + ")");
        act.refreshTools();
    }

    // ===== helpers =====

    /** Total a coin list (skipping unparseable amounts). */
    private BigDecimal sum(List<Coin> coins) {
        BigDecimal t = BigDecimal.ZERO;
        for (Coin c : coins) {
            try { t = t.add(new BigDecimal(c.amount)); } catch (Exception ignored) {}
        }
        return t;
    }

    /** Numeric amount equality (falls back to string compare if either isn't a valid number). */
    private boolean amountsEqual(String a, String b) {
        try { return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0; }
        catch (Exception e) { return a != null && a.equals(b); }
    }

    /** Short toast helper. */
    private void toast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }
}
