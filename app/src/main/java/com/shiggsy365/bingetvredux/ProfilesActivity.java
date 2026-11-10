package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.leanback.widget.HorizontalGridView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Locale;

/**
 * "Who's watching?": picks the profile whose history, settings and MDBList are used. Opened from
 * the logo on the menu bar; the only way to Settings is through here.
 */
public class ProfilesActivity extends Activity {
    private List<Profiles.Profile> mProfiles;
    private String mCurrent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.profiles_activity);
        mProfiles = Profiles.all(this);
        mCurrent = Profiles.current(this).id;
        HorizontalGridView grid = findViewById(R.id.profiles);
        ProfileCarousel.setUp(grid);
        grid.setAdapter(new ProfileAdapter());
        for (int i = 0; i < mProfiles.size(); i++) {
            if (mProfiles.get(i).id.equals(mCurrent)) grid.setSelectedPosition(i);
        }
        grid.requestFocus();
        findViewById(R.id.settings).setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
    }

    private final class ProfileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.profile_tile, parent, false)) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Profiles.Profile p = mProfiles.get(position);
            View avatar = holder.itemView.findViewById(R.id.avatar);
            Avatars.bind(avatar, p);
            TextView name = holder.itemView.findViewById(R.id.name);
            boolean isCurrent = p.id.equals(mCurrent);
            name.setText(isCurrent ? p.name + "  ✓" : p.name);
            name.setTextColor(getColor(isCurrent ? R.color.guide_accent : R.color.guide_text));
            avatar.setOnClickListener(v -> {
                if (!isCurrent) Profiles.setCurrent(ProfilesActivity.this, p.id);
                finish();
            });
            ProfileCarousel.style(holder.itemView, avatar.hasFocus(), false);
            avatar.setOnFocusChangeListener((v, focused) -> ProfileCarousel.style(holder.itemView, focused, true));
        }

        @Override
        public int getItemCount() {
            return mProfiles.size();
        }
    }
}
