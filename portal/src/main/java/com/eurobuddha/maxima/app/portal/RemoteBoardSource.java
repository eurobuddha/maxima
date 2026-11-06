package com.eurobuddha.maxima.app.portal;

import android.content.Context;

import com.eurobuddha.maxima.app.board.BoardSource;
import com.eurobuddha.maxima.cloud.ParlonsRemote;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** The Cloud Portal's boards: {@code parlons.board.*} on the account, over the session's lane. */
public final class RemoteBoardSource implements BoardSource {

    private final Context mApp;

    public RemoteBoardSource(Context zCtx) {
        mApp = zCtx.getApplicationContext();
    }

    private interface Call {
        JSONObject run(ParlonsRemote r) throws Exception;
    }

    /** Block the caller (a worker thread) on the session's interactive lane, then check ok. */
    private JSONObject call(Call zCall) throws Exception {
        final Object[] out = new Object[2];
        final CountDownLatch done = new CountDownLatch(1);
        CloudSession.connectInteractive(mApp, new CloudSession.Cb() {
            public void ok(ParlonsRemote r) {
                try {
                    out[0] = zCall.run(r);
                } catch (Exception e) {
                    out[1] = e;
                } finally {
                    done.countDown();
                }
            }

            public void err(String m) {
                out[1] = new Exception(m);
                done.countDown();
            }
        });
        if (!done.await(40, TimeUnit.SECONDS)) {
            throw new Exception("timeout");
        }
        if (out[1] != null) {
            throw (Exception) out[1];
        }
        JSONObject res = (JSONObject) out[0];
        Object ok = res.get("ok");
        if (!(ok instanceof Boolean) || !((Boolean) ok)) {
            throw new Exception(String.valueOf(res.get("error")));
        }
        return res;
    }

    private static String str(JSONObject o, String k) {
        Object v = o.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static long lng(JSONObject o, String k) {
        Object v = o.get(k);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean bool(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof Boolean ? (Boolean) v : "true".equals(String.valueOf(v));
    }

    private static BoardRow row(JSONObject b) {
        BoardRow r = new BoardRow();
        r.id = str(b, "id");
        r.name = str(b, "name");
        r.open = bool(b, "open");
        r.admin = bool(b, "admin");
        r.canPost = bool(b, "canPost");
        r.joining = bool(b, "joining");
        r.removed = bool(b, "removed");
        r.members = (int) lng(b, "members");
        r.lastActivity = lng(b, "lastActivity");
        r.unread = (int) lng(b, "unread");
        return r;
    }

    private static PostRow post(JSONObject p) {
        PostRow r = new PostRow();
        r.id = str(p, "id");
        r.from = str(p, "from");
        r.name = str(p, "name");
        r.address = str(p, "address");
        r.ts = lng(p, "ts");
        r.re = str(p, "re");
        r.body = str(p, "body");
        r.mine = bool(p, "mine");
        return r;
    }

    @Override
    public List<BoardRow> boards() throws Exception {
        JSONObject res = call(ParlonsRemote::boards);
        List<BoardRow> out = new ArrayList<>();
        JSONArray arr = (JSONArray) res.get("boards");
        if (arr != null) {
            for (Object o : arr) {
                out.add(row((JSONObject) o));
            }
        }
        return out;
    }

    @Override
    public BoardRow create(String zName, boolean zOpen) throws Exception {
        JSONObject res = call(r -> r.createBoard(zName, zOpen));
        BoardRow b = row((JSONObject) res.get("board"));
        b.link = str(res, "link");
        return b;
    }

    @Override
    public BoardRow join(String zLink) throws Exception {
        return row((JSONObject) call(r -> r.joinBoard(zLink)).get("board"));
    }

    @Override
    public String link(String zId) throws Exception {
        return str(call(r -> r.boardLink(zId)), "link");
    }

    @Override
    public Loaded posts(String zId) throws Exception {
        JSONObject res = call(r -> r.boardPosts(zId, 500));
        Loaded l = new Loaded();
        l.board = row((JSONObject) res.get("board"));
        JSONArray arr = (JSONArray) res.get("posts");
        if (arr != null) {
            for (Object o : arr) {
                l.posts.add(post((JSONObject) o));
            }
        }
        return l;
    }

    @Override
    public PostRow post(String zId, String zBody, String zRe) throws Exception {
        return post((JSONObject) call(r -> r.boardPost(zId, zBody, zRe)).get("post"));
    }

    @Override
    public PostRow postImage(String zId, byte[] zJpeg, String zCaption, String zRe) throws Exception {
        call(r -> r.boardMedia(zId, zRe, zJpeg, "image/jpeg", zCaption));
        return null;   // the account publishes on its own lane; the screen reloads to see it
    }

    @Override
    public com.eurobuddha.maxima.core.media.MediaService media() {
        return CloudSession.media(mApp);
    }

    /** Every read is a 500-post RPC over the cloud lane: 30 s, and the board push event wakes the screen. */
    @Override
    public long pollMs() {
        return 30000;
    }

    @Override
    public List<KnockRow> knocks(String zId) throws Exception {
        JSONObject res = call(r -> r.boardKnocks(zId));
        List<KnockRow> out = new ArrayList<>();
        JSONArray arr = (JSONArray) res.get("knocks");
        if (arr != null) {
            for (Object o : arr) {
                JSONObject k = (JSONObject) o;
                KnockRow r = new KnockRow();
                r.fp = str(k, "fp");
                r.name = str(k, "name");
                r.address = str(k, "address");
                r.ts = lng(k, "ts");
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public void approve(String zId, String zFp) throws Exception {
        call(r -> r.boardApprove(zId, zFp));
    }

    @Override
    public void remove(String zId, String zFp) throws Exception {
        call(r -> r.boardRemove(zId, zFp));
    }

    @Override
    public void rename(String zId, String zName) throws Exception {
        call(r -> r.boardRename(zId, zName));
    }

    @Override
    public void leave(String zId) throws Exception {
        call(r -> r.boardLeave(zId));
    }

    @Override
    public void markRead(String zId) throws Exception {
        call(r -> r.boardMarkRead(zId));
    }
}
