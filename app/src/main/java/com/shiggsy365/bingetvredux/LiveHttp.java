package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.net.Network;
import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.okhttp.OkHttpDataSource;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * Live TV's HTTP connections. With live TV's VPN switched on (see {@link LiveVpn}) they are
 * opened on the VPN's network, names included, so the streams go through the tunnel even
 * though the rest of the app doesn't; redirects to another server stay on it too. With the VPN
 * switched on but not connected they fail rather than go round it. With it off they're the
 * app's usual connections.
 *
 * The network is looked up for each connection, so an HLS stream's later pieces follow the
 * VPN if it reconnects.
 */
@OptIn(markerClass = UnstableApi.class)
final class LiveHttp implements DataSource.Factory {
    private static Network sClientNetwork;
    private static OkHttpDataSource.Factory sClientFactory;

    private final Context mContext;
    private final DefaultHttpDataSource.Factory mDirect;

    LiveHttp(Context context, DefaultHttpDataSource.Factory direct) {
        mContext = context.getApplicationContext();
        mDirect = direct;
    }

    @Override
    public DataSource createDataSource() {
        if (!LiveVpn.isEnabled(mContext)) return mDirect.createDataSource();
        Network network = LiveVpn.network();
        if (network == null) return new NotConnected();
        return factoryFor(network).createDataSource();
    }

    private static synchronized OkHttpDataSource.Factory factoryFor(Network network) {
        if (!network.equals(sClientNetwork)) {
            OkHttpClient client = new OkHttpClient.Builder()
                    .socketFactory(network.getSocketFactory())
                    .dns(host -> Arrays.asList(network.getAllByName(host)))
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    // Panels often redirect to another server, sometimes across http/https.
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
            // Panels see the same User-Agent as without the VPN (some refuse OkHttp's).
            sClientFactory = new OkHttpDataSource.Factory((okhttp3.Call.Factory) client)
                    .setUserAgent(System.getProperty("http.agent"));
            sClientNetwork = network;
        }
        return sClientFactory;
    }

    /** Stands in while the VPN is switched on but not connected: nothing gets through. */
    private static final class NotConnected implements DataSource {
        private Uri mUri;

        @Override
        public void addTransferListener(TransferListener transferListener) {}

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            mUri = dataSpec.uri;
            throw new IOException("Live TV's VPN isn't connected");
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            throw new IOException("Live TV's VPN isn't connected");
        }

        @Override
        public Uri getUri() {
            return mUri;
        }

        @Override
        public void close() {}
    }
}
