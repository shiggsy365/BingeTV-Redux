package com.shiggsy365.bingetvredux;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListPopupWindow;

import androidx.annotation.NonNull;

import java.util.List;

/** A drop-down list of choices beside a button (the OSD's audio, subtitles, picture and speed). */
final class ChoiceMenu {
    interface OnChoose {
        void choose(int index);
    }

    private ChoiceMenu() {}

    /**
     * Shows {@code labels} by {@code anchor}, the {@code chosen} one marked; closing it without
     * a choice calls {@code onClose} (may be null).
     */
    static ListPopupWindow show(View anchor, String title, List<String> labels, int chosen,
            OnChoose onChoose, Runnable onClose) {
        float dp = anchor.getResources().getDisplayMetrics().density;
        ListPopupWindow popup = new ListPopupWindow(anchor.getContext());
        List<String> rows = new java.util.ArrayList<>();
        if (title != null) rows.add(title);
        rows.addAll(labels);
        int offset = title != null ? 1 : 0;
        popup.setAdapter(new ArrayAdapter<String>(anchor.getContext(), R.layout.filter_option, rows) {
            @Override
            public boolean isEnabled(int position) {
                return position >= offset;
            }

            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                view.setActivated(position - offset == chosen);
                view.setAlpha(position < offset ? 0.6f : 1f);
                return view;
            }
        });
        popup.setAnchorView(anchor);
        popup.setDropDownGravity(Gravity.START);
        popup.setWidth(Math.round(300 * dp));
        popup.setHeight(Math.round(Math.min(380, rows.size() * 34 + 8) * dp));
        popup.setBackgroundDrawable(anchor.getContext().getDrawable(R.drawable.filter_popup));
        popup.setModal(true);
        popup.setOnItemClickListener((parent, view, which, id) -> {
            if (which < offset) return;
            popup.dismiss();
            onChoose.choose(which - offset);
        });
        if (onClose != null) popup.setOnDismissListener(onClose::run);
        popup.show();
        if (popup.getListView() != null) {
            popup.getListView().setSelector(android.R.color.transparent);
            popup.getListView().setSelection(Math.max(offset, chosen + offset));
            popup.getListView().requestFocus();
        }
        return popup;
    }
}
