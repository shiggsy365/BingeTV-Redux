package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * A list of choices on a panel along the right of the screen, keeping the picture in view (the
 * OSD's audio, subtitles, picture and speed, and live TV's channels).
 */
final class ChoiceMenu {
    private static final int WIDTH_DP = 400;

    interface OnChoose {
        void choose(int index);
    }

    /** A row of a menu: a choice, with details on the right, or a section's heading. */
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

    /** A plain list: {@code labels}, the {@code chosen} one ticked; closing it calls {@code onClose} (may be null). */
    static PopupWindow show(View anchor, String title, List<String> labels, int chosen,
            OnChoose onChoose, Runnable onClose) {
        List<Row> rows = new ArrayList<>();
        for (String label : labels) rows.add(Row.choice(label, ""));
        return show(anchor, title, rows, chosen, onChoose, onClose, WIDTH_DP);
    }

    /**
     * A menu of sections: {@code onChoose} gets the index in {@code rows} of the one chosen, and
     * {@code chosen} (-1: none) is the index of the one to tick.
     */
    static PopupWindow show(View anchor, String title, List<Row> rows, int chosen, OnChoose onChoose,
            Runnable onClose, int widthDp) {
        Context context = anchor.getContext();
        float dp = context.getResources().getDisplayMetrics().density;
        List<Row> all = new ArrayList<>();
        int offset = title != null ? 1 : 0;
        if (title != null) all.add(new Row(title, "", Row.TITLE));
        all.addAll(rows);
        LayoutInflater inflater = LayoutInflater.from(context);
        ListView list = new ListView(context);
        list.setAdapter(new BaseAdapter() {
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
                view.findViewById(R.id.choice_tick).setVisibility(position - offset == chosen ? View.VISIBLE : View.INVISIBLE);
                return view;
            }
        });
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        int padding = Math.round(24 * dp);
        list.setPadding(0, padding, 0, padding);
        PopupWindow popup = new PopupWindow(list, Math.round(widthDp * dp), ViewGroup.LayoutParams.MATCH_PARENT, true);
        popup.setBackgroundDrawable(context.getDrawable(R.drawable.choice_sheet));
        popup.setAnimationStyle(R.style.ChoiceSheet);
        list.setOnItemClickListener((parent, view, which, id) -> {
            if (all.get(which).type != Row.CHOICE) return;
            popup.dismiss();
            onChoose.choose(which - offset);
        });
        if (onClose != null) popup.setOnDismissListener(onClose::run);
        popup.showAtLocation(anchor, Gravity.END | Gravity.TOP, 0, 0);
        int first = offset;
        while (first < all.size() - 1 && all.get(first).type != Row.CHOICE) first++;
        list.setSelection(chosen >= 0 ? chosen + offset : first);
        list.requestFocus();
        return popup;
    }
}
