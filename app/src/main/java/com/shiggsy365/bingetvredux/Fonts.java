package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Build;

/**
 * The app's typefaces for views styled in code: Figtree (res/font) from Android 8, where apps'
 * own fonts can be loaded; the system's sans-serif before, as the layouts get then.
 */
final class Fonts {
    private static Typeface sRegular, sMedium, sSemiBold;

    private Fonts() {}

    /** Figtree, whose bold is its SemiBold. */
    static synchronized Typeface regular(Context context) {
        if (sRegular == null) sRegular = load(context, R.font.figtree, "sans-serif", Typeface.NORMAL);
        return sRegular;
    }

    static synchronized Typeface medium(Context context) {
        if (sMedium == null) sMedium = load(context, R.font.figtree_medium, "sans-serif-medium", Typeface.NORMAL);
        return sMedium;
    }

    static synchronized Typeface semiBold(Context context) {
        if (sSemiBold == null) sSemiBold = load(context, R.font.figtree_semibold, "sans-serif-medium", Typeface.BOLD);
        return sSemiBold;
    }

    private static Typeface load(Context context, int font, String system, int style) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return context.getResources().getFont(font);
            } catch (Resources.NotFoundException e) {
                // The system's, then.
            }
        }
        return Typeface.create(system, style);
    }
}
