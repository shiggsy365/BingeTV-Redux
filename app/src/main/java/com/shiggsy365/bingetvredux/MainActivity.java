package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.leanback.widget.HorizontalGridView;
import androidx.recyclerview.widget.RecyclerView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Profiles and settings, opened from the logo on the menu bar. The top half picks who's
 * watching. The bottom half is how to open the web page, where accounts, addons, channels and
 * everything else are set up: the address, a QR code that opens it with the PIN filled in, and
 * the PIN, which can be changed; "Forget all browsers" makes every browser enter it again. And
 * a check for a newer version.
 */
public class MainActivity extends Activity {
    private static final long STATUS_TICK_MS = 1_000;

    private ImageView mQr;
    private TextView mUrl;
    private TextView mPin;
    private TextView mBrowsers;
    private TextView mStatus;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            showStatus();
            mHandler.postDelayed(this, STATUS_TICK_MS);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);
        WebServerService.start(this);

        mQr = findViewById(R.id.qr);
        mUrl = findViewById(R.id.web_url);
        mPin = findViewById(R.id.pin);
        mBrowsers = findViewById(R.id.browsers);
        mStatus = findViewById(R.id.status);
        findViewById(R.id.sync).setOnClickListener(v -> {
            ChannelSyncWorker.syncNow(getApplicationContext());
            mStatus.setText("Syncing…");
        });
        findViewById(R.id.new_pin).setOnClickListener(v -> {
            WebAuth.newPin(this);
            showPairing();
        });
        findViewById(R.id.forget).setOnClickListener(v -> {
            WebAuth.forgetBrowsers(this);
            showPairing();
        });
        findViewById(R.id.updates).setOnClickListener(v -> Updates.check(this, true));
        ((TextView) findViewById(R.id.version)).setText("BingeTV Redux " + BuildConfig.VERSION_NAME
                + "  ·  Profile: " + Profiles.current(this).name);
        showProfiles();
    }

    /** Who's watching: picking a profile switches to it and goes back to Home. */
    private void showProfiles() {
        List<Profiles.Profile> profiles = Profiles.all(this);
        String current = Profiles.current(this).id;
        HorizontalGridView grid = findViewById(R.id.profiles);
        grid.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.profile_tile, parent, false)) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                Profiles.Profile p = profiles.get(position);
                View avatar = holder.itemView.findViewById(R.id.avatar);
                Avatars.bind(avatar, p);
                TextView name = holder.itemView.findViewById(R.id.name);
                boolean isCurrent = p.id.equals(current);
                name.setText(isCurrent ? p.name + "  ✓" : p.name);
                name.setTextColor(getColor(isCurrent ? R.color.guide_accent : R.color.guide_text));
                avatar.setOnClickListener(v -> {
                    if (!isCurrent) Profiles.setCurrent(MainActivity.this, p.id);
                    finish();
                });
                avatar.setOnFocusChangeListener((v, focused) -> {
                    float scale = focused ? 1.08f : 1f;
                    v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
                });
            }

            @Override
            public int getItemCount() {
                return profiles.size();
            }
        });
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(current)) grid.setSelectedPosition(i);
        }
        // Centred while they fit across the screen (a tile is 160dp, with a 24dp gap).
        float dp = getResources().getDisplayMetrics().density;
        int row = Math.round(profiles.size() * 184 * dp - 24 * dp);
        int screen = getResources().getDisplayMetrics().widthPixels;
        int side = Math.max(Math.round(24 * dp), (screen - row) / 2);
        ViewGroup.LayoutParams lp = grid.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        grid.setLayoutParams(lp);
        grid.setPadding(side, grid.getPaddingTop(), side, grid.getPaddingBottom());
        grid.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showPairing();
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mTick);
        super.onPause();
    }

    private void showPairing() {
        String url = WebServerService.pageUrl();
        String pin = WebAuth.pin(this);
        mUrl.setText(url != null ? url : "No network connection");
        mPin.setText(pin);
        mQr.setImageBitmap(url != null ? qrCode(url + "/?pin=" + pin, 600) : null);
        int browsers = WebAuth.browserCount(this);
        mBrowsers.setText(browsers == 0 ? "No browsers paired yet"
                : browsers == 1 ? "1 browser paired" : browsers + " browsers paired");
    }

    private void showStatus() {
        if (GuideSync.sSyncRunning) {
            mStatus.setText(GuideSync.sSyncStatus);
            return;
        }
        long lastSync = XtreamClient.prefs(this).getLong(XtreamClient.PREF_LAST_SYNC, 0);
        String status = GuideSync.sSyncStatus;
        mStatus.setText((status != null && !status.isEmpty() ? status + " · " : "")
                + (lastSync == 0 ? "Live TV not synced yet"
                : "Live TV last synced " + DateUtils.getRelativeTimeSpanString(lastSync)));
    }

    private static Bitmap qrCode(String text, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Collections.singletonMap(EncodeHintType.MARGIN, 0));
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565);
        } catch (WriterException e) {
            return null;
        }
    }
}
