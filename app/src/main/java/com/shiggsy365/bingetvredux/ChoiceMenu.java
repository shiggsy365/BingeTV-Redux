package com.shiggsy365.bingetvredux;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.List;

/** A drop-down list of choices beside a button (the OSD's audio, subtitles, picture and speed). */
final class ChoiceMenu {
    interface OnChoose {
        void choose(int index);
    }

    /** A row of a sectioned menu: a choice, with details on the right, or a section's heading. */
    static final class Row {
        private static final int TITLE = 0;
        private static final int HEADING = 1;
        private static final int CHOICE = 2;

        final String text;
        final String detail;
        final int type;

        private Row(String text, String detail, int type) {
            this.text = text;
            this.detail = detail;
            this.type = type;
        }

        static Row heading(String text) {
            return new Row(text, "", HEADING);
        }

        static Row choice(String text, String detail) {
            return new Row(text, detail, CHOICE);
        }
    }

    private ChoiceMenu() {}

    /**
     * A menu of sections: {@code onChoose} gets the index in {@code rows} of the one chosen, and
     * {@code chosen} (-1: none) is the index of the one to tick.
     */
    static ListPopupWindow show(View anchor, String title, List<Row> rows, int chosen, OnChoose onChoose,
            Runnable onClose, int widthDp) {
        float dp = anchor.getResources().getDisplayMetrics().density;
        List<Row> all = new java.util.ArrayList<>();
        all.add(new Row(title, "", Row.TITLE));
        all.addAll(rows);
        LayoutInflater inflater = LayoutInflater.from(anchor.getContext());
        ListPopupWindow popup = new ListPopupWindow(anchor.getContext());
        popup.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return all.size();
            }

            @Override
            public Object getItem(int position) {
                return all.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public int getViewTypeCount() {
                return 3;
            }

            @Override
            public int getItemViewType(int position) {
                return all.get(position).type;
            }

            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public boolean isEnabled(int position) {
                return all.get(position).type == Row.CHOICE;
            }

            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                Row row = all.get(position);
                if (row.type != Row.CHOICE) {
                    TextView text = convertView != null ? (TextView) convertView : (TextView) inflater.inflate(
                            row.type == Row.TITLE ? R.layout.choice_title : R.layout.choice_heading, parent, false);
                    text.setText(row.text);
                    return text;
                }
                View view = convertView != null ? convertView : inflater.inflate(R.layout.choice_row, parent, false);
                ((TextView) view.findViewById(R.id.choice_title)).setText(row.text);
                ((TextView) view.findViewById(R.id.choice_detail)).setText(row.detail);
                view.findViewById(R.id.choice_tick).setVisibility(position - 1 == chosen ? View.VISIBLE : View.INVISIBLE);
                return view;
            }
        });
        int height = 8;
        for (Row row : all) height += row.type == Row.TITLE ? 46 : row.type == Row.HEADING ? 30 : 42;
        popup.setAnchorView(anchor);
        popup.setDropDownGravity(Gravity.START);
        popup.setWidth(Math.round(widthDp * dp));
        popup.setHeight(Math.round(Math.min(440, height) * dp));
        popup.setBackgroundDrawable(anchor.getContext().getDrawable(R.drawable.filter_popup));
        popup.setModal(true);
        popup.setOnItemClickListener((parent, view, which, id) -> {
            if (all.get(which).type != Row.CHOICE) return;
            popup.dismiss();
            onChoose.choose(which - 1);
        });
        if (onClose != null) popup.setOnDismissListener(onClose::run);
        popup.show();
        if (popup.getListView() != null) {
            popup.getListView().setSelector(android.R.color.transparent);
            popup.getListView().setDivider(null);
            int first = 1;
            while (first < all.size() - 1 && all.get(first).type != Row.CHOICE) first++;
            popup.getListView().setSelection(chosen >= 0 ? chosen + 1 : first);
            popup.getListView().requestFocus();
        }
        return popup;
    }

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
