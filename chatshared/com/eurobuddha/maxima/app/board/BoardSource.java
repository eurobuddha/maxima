package com.eurobuddha.maxima.app.board;

import java.util.ArrayList;
import java.util.List;

/**
 * What the board screens need from a host, with the host's threading hidden: every call blocks
 * and is made on a worker thread by the caller. The Parlons app answers from its in-process
 * {@code core/board/BoardEngine}; the Cloud Portal answers over {@code parlons.board.*} RPC.
 * Both produce the same rows, so {@link BoardActivity} and {@link BoardFlows} exist once.
 */
public interface BoardSource {

    final class BoardRow {
        public String id = "";
        public String name = "";
        public boolean open;
        public boolean admin;
        public boolean canPost;
        public boolean joining;
        public int members;
        public long lastActivity;
        public int unread;
        /** Only filled by {@link #create}. */
        public String link = "";
    }

    final class PostRow {
        public String id = "";
        public String from = "";
        public String name = "";
        public String address = "";
        public long ts;
        public String re = "";
        public String body = "";
        public boolean mine;
    }

    final class KnockRow {
        public String fp = "";
        public String name = "";
        public String address = "";
        public long ts;
    }

    final class Loaded {
        public BoardRow board;
        public final List<PostRow> posts = new ArrayList<>();
    }

    List<BoardRow> boards() throws Exception;

    BoardRow create(String zName, boolean zOpen) throws Exception;

    BoardRow join(String zLink) throws Exception;

    String link(String zId) throws Exception;

    Loaded posts(String zId) throws Exception;

    PostRow post(String zId, String zBody, String zRe) throws Exception;

    List<KnockRow> knocks(String zId) throws Exception;

    void approve(String zId, String zFp) throws Exception;

    void remove(String zId, String zFp) throws Exception;

    void rename(String zId, String zName) throws Exception;

    void leave(String zId) throws Exception;

    void markRead(String zId) throws Exception;

    /** The host's current source; null until the host's engine or session is up. */
    final class Holder {
        private static volatile BoardSource sSource;

        private Holder() {
        }

        public static void set(BoardSource zSource) {
            sSource = zSource;
        }

        public static BoardSource get() {
            return sSource;
        }
    }
}
