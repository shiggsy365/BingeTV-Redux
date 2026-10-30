package com.shiggsy365.bingetvredux;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

/** A profile tile's picture: the one uploaded on the web page, else the name's initial. */
final class Avatars {
    private Avatars() {}

    static void bind(View avatar, Profiles.Profile profile) {
        TextView letter = avatar.findViewById(R.id.avatar_letter);
        ImageView image = avatar.findViewById(R.id.avatar_image);
        File file = Profiles.avatarFile(avatar.getContext(), profile.id);
        avatar.setClipToOutline(true);
        if (file.exists()) {
            letter.setText("");
            image.setVisibility(View.VISIBLE);
            Images.loadFile(image, file);
        } else {
            image.setVisibility(View.GONE);
            letter.setText(profile.name.isEmpty() ? "?" : profile.name.substring(0, 1).toUpperCase(Locale.ROOT));
        }
    }
}
