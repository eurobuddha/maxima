package com.eurobuddha.maxima.dexrelay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * In-memory relay state: live orders (highest seq per signPk|orderId), a bounded change log with a monotonic cursor
 * for long-poll deltas, and per-recipient inboxes for sealed trade messages. One monitor; publishes notify waiters.
 * Nothing persists — a restarted relay starts empty and makers republish on their next tick.
 */
final class Store {
    static final int MAX_ORDERS = 20_000;
    static final int MAX_MSGS = 20_000;
    static final int MAX_INBOX_PER_PK = 200;

    static final class Changes {
        final boolean reset; final long cursor; final List<Envelope> orders;
        Changes(boolean reset, long cursor, List<Envelope> orders) { this.reset = reset; this.cursor = cursor; this.orders = orders; }
    }

    private static final class Logged { final long cursor; final Envelope env; Logged(long c, Envelope e) { cursor = c; env = e; } }

    private final int logCapacity;
    private final Map<String, Envelope> orders = new HashMap<>();
    private final ArrayDeque<Logged> log = new ArrayDeque<>();
    private long cursor = 0;

    private final Map<String, ArrayDeque<Logged>> inbox = new HashMap<>();
    private long msgCursor = 0;
    private int msgCount = 0;

    Store() { this(10_000); }
    Store(int logCapacity) { this.logCapacity = Math.max(1, logCapacity); }

    /** @return true if accepted (strictly higher seq than anything known for the key). */
    synchronized boolean publish(Envelope o) {
        if (o.kind != Envelope.Kind.ORDER) throw new IllegalArgumentException("not an order");
        Envelope prev = orders.get(o.key());
        if (prev != null && prev.seq >= o.seq) return false;
        if (prev == null && orders.size() >= MAX_ORDERS) return false;
        orders.put(o.key(), o);
        cursor++;
        log.addLast(new Logged(cursor, o));
        while (log.size() > logCapacity) log.removeFirst();
        notifyAll();
        return true;
    }

    synchronized boolean isFull() { return orders.size() >= MAX_ORDERS; }
    synchronized long cursor() { return cursor; }
    synchronized int liveOrders() { int n = 0; for (Envelope e : orders.values()) if (!e.isCancel()) n++; return n; }

    /** Live (non-cancel) orders, optionally filtered by market. */
    synchronized List<Envelope> snapshot(String market) {
        List<Envelope> out = new ArrayList<>();
        for (Envelope e : orders.values()) if (!e.isCancel() && (market == null || market.equals(e.market))) out.add(e);
        return out;
    }

    /** since == 0, or older than the retained log, yields a reset snapshot; otherwise the exact deltas after since. */
    synchronized Changes changesSince(String market, long since) {
        if (since >= cursor) return new Changes(false, cursor, Collections.<Envelope>emptyList());
        long oldest = log.isEmpty() ? cursor + 1 : log.peekFirst().cursor;
        if (since <= 0 || since < oldest - 1) return new Changes(true, cursor, snapshot(market));
        List<Envelope> out = new ArrayList<>();
        for (Logged l : log) if (l.cursor > since && (market == null || market.equals(l.env.market))) out.add(l.env);
        return new Changes(false, cursor, out);
    }

    /** Block until cursor > since or timeout. */
    synchronized void await(long since, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (cursor <= since) {
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            if (left <= 0) return;
            try { wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    // ---- inbox ----

    synchronized boolean deliver(Envelope m) {
        if (m.kind != Envelope.Kind.MSG) throw new IllegalArgumentException("not a msg");
        if (msgCount >= MAX_MSGS) return false;
        ArrayDeque<Logged> q = inbox.computeIfAbsent(m.to.toLowerCase(), k -> new ArrayDeque<>());
        if (q.size() >= MAX_INBOX_PER_PK) return false;
        msgCursor++;
        q.addLast(new Logged(msgCursor, m));
        msgCount++;
        notifyAll();
        return true;
    }

    synchronized long inboxCursor(String to) { return msgCursor; }

    synchronized List<Envelope> inboxSince(String to, long since) {
        ArrayDeque<Logged> q = inbox.get(to.toLowerCase());
        if (q == null) return Collections.emptyList();
        List<Envelope> out = new ArrayList<>();
        for (Logged l : q) if (l.cursor > since) out.add(l.env);
        return out;
    }

    /** Block until a message for {@code to} with cursor > since exists, or timeout. */
    synchronized void awaitInbox(String to, long since, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (inboxSince(to, since).isEmpty()) {
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            if (left <= 0) return;
            try { wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** Drop everything whose expiry has passed. Cursors are unaffected (clients expire by their own clock too). */
    synchronized void sweep(long nowSeconds) {
        orders.values().removeIf(e -> e.expiry <= nowSeconds);
        for (Iterator<Map.Entry<String, ArrayDeque<Logged>>> it = inbox.entrySet().iterator(); it.hasNext();) {
            ArrayDeque<Logged> q = it.next().getValue();
            int before = q.size();
            q.removeIf(l -> l.env.expiry <= nowSeconds);
            msgCount -= before - q.size();
            if (q.isEmpty()) it.remove();
        }
    }
}
