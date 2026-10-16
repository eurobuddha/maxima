package com.eurobuddha.maxima.app.i18n;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reuses Explain's native bindings while retaining the dialog, inputs and listeners. */
public final class LanguageDialog extends AlertDialog.Builder {
    private CharSequence title, message;
    private final Map<Integer, CharSequence> buttons = new HashMap<>();

    public LanguageDialog(Context context) { super(context); }
    @Override public LanguageDialog setTitle(CharSequence value) {
        title = value; super.setTitle(value); return this;
    }
    @Override public LanguageDialog setMessage(CharSequence value) {
        message = value; super.setMessage(value); return this;
    }
    @Override public LanguageDialog setPositiveButton(CharSequence text, DialogInterface.OnClickListener action) {
        buttons.put(AlertDialog.BUTTON_POSITIVE, text); super.setPositiveButton(text, action); return this;
    }
    @Override public LanguageDialog setNegativeButton(CharSequence text, DialogInterface.OnClickListener action) {
        buttons.put(AlertDialog.BUTTON_NEGATIVE, text); super.setNegativeButton(text, action); return this;
    }
    @Override public LanguageDialog setNeutralButton(CharSequence text, DialogInterface.OnClickListener action) {
        buttons.put(AlertDialog.BUTTON_NEUTRAL, text); super.setNeutralButton(text, action); return this;
    }
    /** IDs remain stable even while their visible labels change. */
    public LanguageDialog actions(List<Integer> resources, DialogInterface.OnClickListener action) {
        CharSequence[] labels = new CharSequence[resources.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = Language.msg(resources.get(i));
        return setItems(labels, action);
    }
    @Override public LanguageDialog setItems(CharSequence[] items, DialogInterface.OnClickListener action) {
        CharSequence[] labels = items.clone();
        super.setAdapter(new ArrayAdapter<CharSequence>(getContext(),
                androidx.appcompat.R.layout.select_dialog_item_material, android.R.id.text1, labels) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                View view = super.getView(position, recycled, parent);
                TextView label = view.findViewById(android.R.id.text1);
                Language.setText(label, getItem(position));
                return view;
            }
        }, action);
        return this;
    }
    @Override public AlertDialog create() {
        if (title instanceof Language.Message) {
            TextView heading = new TextView(getContext());
            heading.setTextAppearance(android.R.style.TextAppearance_Material_Title);
            int padding = Math.round(24 * getContext().getResources().getDisplayMetrics().density);
            heading.setPadding(padding, padding, padding, padding / 2);
            Language.setText(heading, title);
            super.setCustomTitle(heading);
        }
        AlertDialog dialog = super.create();
        CharSequence body = message;
        Map<Integer, CharSequence> labels = new HashMap<>(buttons);
        dialog.setOnShowListener(ignored -> {
            TextView text = dialog.findViewById(android.R.id.message);
            if (text != null && body instanceof Language.Message) Language.setText(text, body);
            for (Map.Entry<Integer, CharSequence> entry : labels.entrySet()) {
                TextView button = dialog.getButton(entry.getKey());
                if (button != null) Language.setText(button, entry.getValue());
            }
        });
        return dialog;
    }
}
