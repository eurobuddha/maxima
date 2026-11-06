package com.eurobuddha.maxima.app.board;

import com.eurobuddha.maxima.core.board.BoardEngine;

import java.util.ArrayList;
import java.util.List;

/** The Parlons app's boards: straight from the in-process engine. */
public final class LocalBoardSource implements BoardSource {

    private final BoardEngine mEngine;

    public LocalBoardSource(BoardEngine zEngine) {
        mEngine = zEngine;
    }

    private BoardRow row(BoardEngine.Board b) {
        BoardRow r = new BoardRow();
        r.id = b.id;
        r.name = b.name;
        r.open = b.open;
        r.admin = b.iAmAdmin();
        r.canPost = b.canPost();
        r.joining = b.joining;
        r.removed = b.removed;
        r.members = b.memberCount();
        r.lastActivity = b.lastActivity;
        r.unread = mEngine.unread(b);
        return r;
    }

    private static PostRow post(BoardEngine.Board b, BoardEngine.Entry e) {
        PostRow p = new PostRow();
        p.id = e.id;
        p.from = e.from;
        p.name = b.nameOf(e.from);
        p.address = b.cardOf(e.from);
        p.ts = e.ts;
        p.re = e.re;
        p.body = e.body();
        p.mine = e.mine;
        return p;
    }

    @Override
    public List<BoardRow> boards() {
        List<BoardRow> out = new ArrayList<>();
        for (BoardEngine.Board b : mEngine.boards()) {
            out.add(row(b));
        }
        return out;
    }

    @Override
    public BoardRow create(String zName, boolean zOpen) {
        BoardEngine.Board b = mEngine.create(zName, zOpen);
        BoardRow r = row(b);
        r.link = mEngine.link(b.id);
        return r;
    }

    @Override
    public BoardRow join(String zLink) {
        return row(mEngine.join(zLink));
    }

    @Override
    public String link(String zId) {
        return mEngine.link(zId);
    }

    @Override
    public Loaded posts(String zId) {
        BoardEngine.Board b = mEngine.board(zId);
        if (b == null) {
            throw new IllegalArgumentException("no such board");
        }
        Loaded l = new Loaded();
        l.board = row(b);
        for (BoardEngine.Entry e : mEngine.posts(zId, 500)) {
            l.posts.add(post(b, e));
        }
        return l;
    }

    @Override
    public PostRow post(String zId, String zBody, String zRe) {
        BoardEngine.Board b = mEngine.board(zId);
        if (b == null) {
            throw new IllegalArgumentException("no such board");
        }
        return post(b, mEngine.post(zId, zBody, zRe));
    }

    @Override
    public PostRow postImage(String zId, byte[] zJpeg, String zCaption, String zRe) throws Exception {
        BoardEngine.Board b = mEngine.board(zId);
        return b == null ? null : post(b, mEngine.postMedia(zId, zJpeg, "image/jpeg", zCaption, zRe));
    }

    @Override
    public com.eurobuddha.maxima.core.media.MediaService media() {
        return com.eurobuddha.maxima.app.MaximaService.media();
    }

    @Override
    public List<KnockRow> knocks(String zId) {
        List<KnockRow> out = new ArrayList<>();
        for (BoardEngine.Knock k : mEngine.knocks(zId)) {
            KnockRow r = new KnockRow();
            r.fp = k.fp;
            r.name = k.name;
            r.address = k.address;
            r.ts = k.ts;
            out.add(r);
        }
        return out;
    }

    @Override
    public void approve(String zId, String zFp) {
        mEngine.approve(zId, zFp);
    }

    @Override
    public void remove(String zId, String zFp) {
        mEngine.remove(zId, zFp);
    }

    @Override
    public void rename(String zId, String zName) {
        mEngine.rename(zId, zName);
    }

    @Override
    public void leave(String zId) {
        mEngine.leave(zId);
    }

    @Override
    public void markRead(String zId) {
        mEngine.markRead(zId);
    }
}
