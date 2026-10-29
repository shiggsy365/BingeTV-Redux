package com.shiggsy365.bingetvredux;

import android.app.Activity;
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

/** "Who's watching?": picks the profile whose history, settings and MDBList are used. */
public class ProfilesActivity extends Activity {
    private List<Profiles.Profile> mProfiles;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.profiles_activity);
        mProfiles = Profiles.all(this);
        HorizontalGridView grid = findViewById(R.id.profiles);
        grid.setAdapter(new ProfileAdapter());
        String current = Profiles.current(this).id;
        for (int i = 0; i < mProfiles.size(); i++) {
            if (mProfiles.get(i).id.equals(current)) grid.setSelectedPosition(i);
        }
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
            TextView avatar = holder.itemView.findViewById(R.id.avatar);
            avatar.setText(p.name.isEmpty() ? "?" : p.name.substring(0, 1).toUpperCase(Locale.ROOT));
            ((TextView) holder.itemView.findViewById(R.id.name)).setText(p.name);
            avatar.setOnClickListener(v -> {
                Profiles.setCurrent(ProfilesActivity.this, p.id);
                finish();
            });
            avatar.setOnFocusChangeListener((v, focused) -> {
                float scale = focused ? 1.08f : 1f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
            });
        }

        @Override
        public int getItemCount() {
            return mProfiles.size();
        }
    }
}
