package com.eurobuddha.maxima.app.board;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.eurobuddha.maxima.app.R;
import com.eurobuddha.maxima.app.i18n.Language;
import com.eurobuddha.maxima.app.i18n.LanguageDialog;
import com.eurobuddha.maxima.app.ui.Qr;
import com.eurobuddha.maxima.app.ui.Ui;
import com.eurobuddha.maxima.core.board.BoardLink;

import java.util.function.Consumer;

/**
 * The dialogs around a board that are not the board itself: make one, join one from a link
 * or a scan, and show a link as text + QR. Shared by the app and the portal.
 */
public final class BoardFlows {

    private BoardFlows() {
    }

    /** A scanner the host provides: call it with the sink that receives the scanned text. */
    public interface Scanner {
        void scan(Consumer<String> zSink);
    }

    private static void toast(Activity zAct, CharSequence zMsg) {
        Toast.makeText(zAct, zMsg, Toast.LENGTH_SHORT).show();
    }

    private static BoardSource source(Activity zAct) {
        BoardSource s = BoardSource.Holder.get();
        if (s == null) {
            toast(zAct, Language.text(zAct, R.string.l10n_native_chat_starting));
        }
        return s;
    }

    public static void open(Activity zAct, String zBoardId) {
        Intent i = new Intent(zAct, BoardActivity.class);
        i.putExtra(BoardActivity.EXTRA_BOARD, zBoardId);
        zAct.startActivity(i);
    }

    /** Name + open/invite-only, then create and show the link. */
    public static void newBoard(Activity zAct) {
        if (source(zAct) == null) {
            return;
        }
        LinearLayout box = new LinearLayout(zAct);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(zAct, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView help = new TextView(zAct);
        Language.setText(help, Language.msg(R.string.l10n_board_new_help));
        box.addView(help);
        EditText name = new EditText(zAct);
        Language.hint(name, Language.msg(R.string.l10n_board_name));
        name.setSingleLine(true);
        box.addView(name);
        CheckBox open = new CheckBox(zAct);
        Language.setText(open, Language.msg(R.string.l10n_board_open_label));
        open.setChecked(true);
        box.addView(open);
        TextView openHelp = new TextView(zAct);
        Language.setText(openHelp, Language.msg(R.string.l10n_board_open_help));
        box.addView(openHelp);
        new LanguageDialog(zAct)
                .setTitle(Language.msg(R.string.l10n_board_new))
                .setView(box)
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_board_create), (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) {
                        toast(zAct, Language.text(zAct, R.string.l10n_board_name_required));
                        return;
                    }
                    final boolean isOpen = open.isChecked();
                    new Thread(() -> {
                        try {
                            BoardSource.BoardRow b = source(zAct).create(n, isOpen);
                            zAct.runOnUiThread(() -> {
                                toast(zAct, Language.text(zAct, R.string.l10n_board_created));
                                open(zAct, b.id);
                                showLink(zAct, b, b.link);
                            });
                        } catch (Exception e) {
                            zAct.runOnUiThread(() -> toast(zAct, Language.text(zAct, R.string.l10n_native_chat_operation_failed, String.valueOf(e.getMessage()))));
                        }
                    }, "board-create").start();
                })
                .show();
        name.requestFocus();
    }

    /** Paste or scan a link. */
    public static void joinBoard(Activity zAct, Scanner zScanner) {
        if (source(zAct) == null) {
            return;
        }
        LinearLayout box = new LinearLayout(zAct);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(zAct, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView help = new TextView(zAct);
        Language.setText(help, Language.msg(R.string.l10n_board_join_help));
        box.addView(help);
        EditText link = new EditText(zAct);
        link.setHint(BoardLink.PREFIX + "…");
        link.setSingleLine(true);
        box.addView(link);
        LanguageDialog b = new LanguageDialog(zAct)
                .setTitle(Language.msg(R.string.l10n_board_join_title))
                .setNegativeButton(Language.msg(R.string.l10n_common_cancel), null)
                .setPositiveButton(Language.msg(R.string.l10n_board_join_button), (d, w) ->
                        join(zAct, link.getText().toString()));
        if (zScanner != null) {
            b.setNeutralButton(Language.msg(R.string.l10n_board_scan), (d, w) -> zScanner.scan(text -> join(zAct, text)));
        }
        b.setView(box);
        b.show();
        link.requestFocus();
    }

    /** Join from link text (a paste, a scan, a message). False when the text is not a board link. */
    public static boolean join(Activity zAct, String zText) {
        if (!BoardLink.isLink(zText)) {
            toast(zAct, Language.text(zAct, R.string.l10n_board_invalid_link));
            return false;
        }
        BoardSource s = source(zAct);
        if (s == null) {
            return true;
        }
        final String link = zText.trim();
        new Thread(() -> {
            try {
                BoardSource.BoardRow b = s.join(link);
                zAct.runOnUiThread(() -> {
                    toast(zAct, Language.text(zAct, b.joining ? R.string.l10n_board_knocked : R.string.l10n_board_joined));
                    open(zAct, b.id);
                });
            } catch (Exception e) {
                zAct.runOnUiThread(() -> toast(zAct, Language.text(zAct, R.string.l10n_native_chat_operation_failed, String.valueOf(e.getMessage()))));
            }
        }, "board-join").start();
        return true;
    }

    /** The link as QR + text, with copy and share. */
    public static void showLink(Activity zAct, BoardSource.BoardRow zBoard, String zLink) {
        if (zLink == null || zLink.isEmpty()) {
            return;
        }
        LinearLayout box = new LinearLayout(zAct);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(zAct, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView help = new TextView(zAct);
        Language.setText(help, Language.msg(zBoard.open ? R.string.l10n_board_link_open_help : R.string.l10n_board_link_invite_help));
        box.addView(help);
        ImageView qr = new ImageView(zAct);
        int px = Ui.dp(zAct, 220);
        qr.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        qr.setBackgroundColor(0xFFFFFFFF);
        qr.setPadding(Ui.dp(zAct, 6), Ui.dp(zAct, 6), Ui.dp(zAct, 6), Ui.dp(zAct, 6));
        box.addView(qr);
        TextView text = new TextView(zAct);
        text.setText(zLink);
        text.setTextIsSelectable(true);
        text.setTextSize(11);
        box.addView(text);
        new Thread(() -> {
            try {
                Bitmap bm = Qr.encode(zLink, px);
                zAct.runOnUiThread(() -> qr.setImageBitmap(bm));
            } catch (Exception ignored) {
            }
        }, "board-qr").start();
        new LanguageDialog(zAct)
                .setTitle(Language.msg(R.string.l10n_board_link))
                .setView(box)
                .setNegativeButton(Language.msg(R.string.l10n_language_close), null)
                .setNeutralButton(Language.msg(R.string.l10n_board_copy_link), (d, w) -> {
                    ClipboardManager cm = zAct.getSystemService(ClipboardManager.class);
                    cm.setPrimaryClip(ClipData.newPlainText("board", zLink));
                    toast(zAct, Language.text(zAct, R.string.l10n_common_copied));
                })
                .setPositiveButton(Language.msg(R.string.l10n_board_share_link), (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_SUBJECT, zBoard.name);
                    i.putExtra(Intent.EXTRA_TEXT, zLink);
                    zAct.startActivity(Intent.createChooser(i, zBoard.name));
                })
                .show();
    }

    /** Hide a view when the host layout has it (the chat layout's call/attach buttons). */
    static void gone(View zRoot, int zId) {
        View v = zRoot.findViewById(zId);
        if (v != null) {
            v.setVisibility(View.GONE);
        }
    }
}
