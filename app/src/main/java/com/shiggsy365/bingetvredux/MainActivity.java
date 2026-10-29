package com.shiggsy365.bingetvredux;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.Collections;

/**
 * TV settings: how to open the web page, where accounts, addons, channels and everything else
 * are set up. Shows the address, a QR code that opens it with the PIN filled in, and the PIN,
 * which can be changed. "Forget all browsers" makes every browser enter the PIN again.
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
        findViewById(R.id.sync).requestFocus();
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
