package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.config.InetEndpoint;
import com.wireguard.config.Interface;
import com.wireguard.config.Peer;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A WireGuard VPN (any provider's WireGuard config: Surfshark, Mullvad, Proton and others) that
 * carries live TV's video and nothing else.
 *
 * Android VPNs work per app, not per connection, so it's done in two parts:
 * <ul>
 *   <li>The VPN applies to this app only, and while it's up the app's process is bound to the
 *   ordinary network (Wi-Fi or Ethernet). As the VPN's owner the app may do that, so
 *   everything it fetches (addons, images, radio, the web page, the panel's API) carries on
 *   as before.</li>
 *   <li>Live TV's players open their connections on the VPN's own network instead (see
 *   {@link LiveHttp}), so the live and catch-up streams, and only they, go through the
 *   tunnel. With the VPN switched on but not connected, those streams don't play at all
 *   rather than go round it.</li>
 * </ul>
 *
 * The config is saved from the web page; the switch is on the live TV guide. Android allows
 * one VPN at a time, so connecting this one disconnects any other VPN app.
 */
public final class LiveVpn {
    private static final String TAG = "LiveVpn";

    static final String PREFS = "vpn";
    private static final String PREF_CONFIG = "config";
    private static final String PREF_ENABLED = "enabled";
    // Used when the config gives no DNS server: names resolve through the tunnel.
    private static final String FALLBACK_DNS = "1.1.1.1";
    private static final long CONNECT_TIMEOUT_MS = 15_000;

    public enum Status { OFF, CONNECTING, ON, FAILED }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Object LOCK = new Object();

    private static Context sApp;
    private static GoBackend sBackend;
    private static volatile Status sStatus = Status.OFF;
    private static volatile String sError;
    private static volatile Network sVpnNetwork;
    // Ordinary (non-VPN) networks that are up; the process is bound to sBound, one of them.
    private static final List<Network> sPhysical = new ArrayList<>();
    private static Network sBound;
    private static ConnectivityManager.NetworkCallback sVpnCallback;
    private static ConnectivityManager.NetworkCallback sPhysicalCallback;
    // Whether stop() was asked for, so a tunnel that drops by itself can be told apart.
    private static volatile boolean sStopping;

    private static final Tunnel TUNNEL = new Tunnel() {
        @Override
        public String getName() {
            // Shown in Android's VPN notice and settings.
            return "BingeTV-Live";
        }

        @Override
        public void onStateChange(State state) {
            // Down without stop(): another VPN app took over, or Android revoked this one.
            if (state == State.DOWN && !sStopping && sStatus == Status.ON) {
                Log.i(TAG, "Tunnel went down");
                EXECUTOR.execute(() -> {
                    disconnect(sApp);
                    setStatus(Status.FAILED, "Disconnected (another VPN may have taken over)");
                });
            }
        }
    };

    private LiveVpn() {}

    private static synchronized Context app(Context context) {
        if (sApp == null) sApp = context.getApplicationContext();
        return sApp;
    }

    private static SharedPreferences prefs(Context context) {
        return app(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- Settings ----

    public static boolean hasConfig(Context context) {
        return !TextUtils.isEmpty(prefs(context).getString(PREF_CONFIG, ""));
    }

    /** Whether live TV is set to go through the VPN (whether or not it's connected yet). */
    public static boolean isEnabled(Context context) {
        return hasConfig(context) && prefs(context).getBoolean(PREF_ENABLED, false);
    }

    /** The server the saved config connects to, for the web page; null if none is saved. */
    public static String endpoint(Context context) {
        String text = prefs(context).getString(PREF_CONFIG, "");
        if (TextUtils.isEmpty(text)) return null;
        try {
            for (Peer peer : parse(text).getPeers()) {
                InetEndpoint e = peer.getEndpoint().orElse(null);
                if (e != null) return e.getHost() + ":" + e.getPort();
            }
        } catch (Exception e) {
            Log.w(TAG, "Saved config doesn't parse", e);
        }
        return "";
    }

    /**
     * Checks and saves a WireGuard config (the text of a .conf file). Throws
     * IllegalArgumentException with a readable message if it isn't one. A connected VPN
     * reconnects with it.
     */
    public static void saveConfig(Context context, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Paste a WireGuard config");
        Config config;
        try {
            config = parse(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException("That isn't a WireGuard config: " + e.getMessage());
        }
        if (config.getPeers().isEmpty()) {
            throw new IllegalArgumentException("The config has no [Peer] section");
        }
        prefs(context).edit().putString(PREF_CONFIG, trimmed).commit();
        if (sStatus == Status.ON || sStatus == Status.CONNECTING) restart(context);
    }

    public static void removeConfig(Context context) {
        prefs(context).edit().remove(PREF_CONFIG).putBoolean(PREF_ENABLED, false).commit();
        stop(context);
    }

    /**
     * Android's "allow this VPN" request, to start with startActivityForResult before switching
     * on; null when it's already allowed.
     */
    public static Intent permissionIntent(Context context) {
        return VpnService.prepare(app(context));
    }

    /** Switches live TV's VPN on (connecting it) or off (disconnecting it). */
    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_ENABLED, enabled).commit();
        if (enabled) start(context);
        else stop(context);
    }

    // ---- State ----

    public static Status status() {
        return sStatus;
    }

    /** Why the last connection failed, or null. */
    public static String error() {
        return sError;
    }

    /** The VPN's network while it's connected, else null. */
    static Network network() {
        return sStatus == Status.ON ? sVpnNetwork : null;
    }

    /** Called on the main thread whenever status() changes. */
    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void setStatus(Status status, String error) {
        sStatus = status;
        sError = error;
        MAIN.post(() -> {
            for (Runnable r : LISTENERS) r.run();
        });
    }

    /** Connects if live TV's VPN is switched on and isn't connected (e.g. after a restart). */
    public static void ensure(Context context) {
        if (isEnabled(context) && (sStatus == Status.OFF || sStatus == Status.FAILED)) {
            start(context);
        }
    }

    /** Whether live TV may play now: with the VPN switched off, or connected. */
    public static boolean isReady(Context context) {
        return !isEnabled(context) || network() != null;
    }

    /**
     * Whether live TV may play now: true with the VPN switched off, or once it has connected.
     * Waits for it to connect, so call it off the main thread.
     */
    public static boolean awaitReady(Context context, long timeoutMs) {
        if (!isEnabled(context)) return true;
        ensure(context);
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            Status s = sStatus;
            if (s == Status.ON && sVpnNetwork != null) return true;
            if (s == Status.FAILED || s == Status.OFF) return false;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ---- Connecting ----

    private static void start(Context context) {
        if (sStatus == Status.ON) return;
        Context app = app(context);
        setStatus(Status.CONNECTING, null);
        EXECUTOR.execute(() -> connectOrFail(app));
    }

    /** Reconnects with a new config. */
    private static void restart(Context context) {
        Context app = app(context);
        setStatus(Status.CONNECTING, null);
        EXECUTOR.execute(() -> {
            disconnect(app);
            connectOrFail(app);
        });
    }

    private static void connectOrFail(Context app) {
        try {
            connect(app);
        } catch (Exception e) {
            Log.w(TAG, "Couldn't connect", e);
            disconnect(app);
            setStatus(Status.FAILED, message(e));
        }
    }

    private static void stop(Context context) {
        Context app = app(context);
        EXECUTOR.execute(() -> {
            disconnect(app);
            setStatus(Status.OFF, null);
        });
    }

    private static void connect(Context app) throws Exception {
        if (sStatus == Status.ON) return;
        String text = prefs(app).getString(PREF_CONFIG, "");
        if (TextUtils.isEmpty(text)) throw new IllegalStateException("No VPN config saved");
        if (VpnService.prepare(app) != null) {
            throw new IllegalStateException("Android hasn't allowed the VPN: switch it on again");
        }
        Config config = forThisApp(app, parse(text));
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);

        // The ordinary network first, so nothing else of the app's goes into the tunnel.
        watchPhysical(cm);
        watchVpn(cm);

        sStopping = false;
        synchronized (LOCK) {
            if (sBackend == null) sBackend = new GoBackend(app);
        }
        sBackend.setState(TUNNEL, Tunnel.State.UP, config);

        long end = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
        while (sVpnNetwork == null && System.currentTimeMillis() < end) Thread.sleep(100);
        if (sVpnNetwork == null) throw new IllegalStateException("The VPN didn't come up");
        synchronized (LOCK) {
            if (sBound == null) {
                // No ordinary network to keep the rest of the app on: don't run.
                throw new IllegalStateException("No Wi-Fi or Ethernet network");
            }
        }
        Log.i(TAG, "Connected");
        setStatus(Status.ON, null);
    }

    private static void disconnect(Context app) {
        sStopping = true;
        try {
            if (sBackend != null) sBackend.setState(TUNNEL, Tunnel.State.DOWN, null);
        } catch (Exception e) {
            Log.w(TAG, "Couldn't stop the tunnel", e);
        }
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        synchronized (LOCK) {
            if (sVpnCallback != null) unregister(cm, sVpnCallback);
            if (sPhysicalCallback != null) unregister(cm, sPhysicalCallback);
            sVpnCallback = null;
            sPhysicalCallback = null;
            sVpnNetwork = null;
            sPhysical.clear();
        }
        unbind();
    }

    private static void unregister(ConnectivityManager cm,
                                   ConnectivityManager.NetworkCallback callback) {
        try {
            cm.unregisterNetworkCallback(callback);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered.
        }
    }

    private static void unbind() {
        synchronized (LOCK) {
            if (sApp == null) return;
            sBound = null;
            sApp.getSystemService(ConnectivityManager.class).bindProcessToNetwork(null);
        }
    }

    /** Keeps the process bound to an ordinary network that's up. */
    private static void watchPhysical(ConnectivityManager cm) {
        synchronized (LOCK) {
            if (sPhysicalCallback != null) return;
            // Bind straight away to the network in use now (the VPN isn't up yet, so it's
            // the ordinary one), before the callback reports it.
            Network active = cm.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                sPhysical.add(active);
                bindLocked(cm, active);
            }
            sPhysicalCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    synchronized (LOCK) {
                        if (!sPhysical.contains(network)) sPhysical.add(network);
                        if (sBound == null) bindLocked(cm, network);
                    }
                }

                @Override
                public void onLost(Network network) {
                    synchronized (LOCK) {
                        sPhysical.remove(network);
                        if (network.equals(sBound)) {
                            sBound = null;
                            if (!sPhysical.isEmpty()) bindLocked(cm, sPhysical.get(0));
                        }
                    }
                }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), sPhysicalCallback);
        }
    }

    private static void bindLocked(ConnectivityManager cm, Network network) {
        if (cm.bindProcessToNetwork(network)) {
            sBound = network;
            Log.i(TAG, "App bound to " + network);
        } else {
            Log.w(TAG, "Couldn't bind the app to " + network);
        }
    }

    /** Tracks the VPN's own network, which live TV's connections are opened on. */
    private static void watchVpn(ConnectivityManager cm) {
        synchronized (LOCK) {
            if (sVpnCallback != null) return;
            sVpnCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    sVpnNetwork = network;
                }

                @Override
                public void onLost(Network network) {
                    if (network.equals(sVpnNetwork)) sVpnNetwork = null;
                }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), sVpnCallback);
        }
    }

    // ---- Config ----

    private static Config parse(String text) throws Exception {
        return Config.parse(new BufferedReader(new StringReader(text)));
    }

    /**
     * The config as given, but applying to this app only (whatever app lists it has), and with
     * a DNS server so names on the VPN's network resolve through the tunnel.
     */
    private static Config forThisApp(Context app, Config config) throws Exception {
        Interface in = config.getInterface();
        Interface.Builder b = new Interface.Builder()
                .setKeyPair(in.getKeyPair())
                .addAddresses(in.getAddresses())
                .addDnsSearchDomains(in.getDnsSearchDomains())
                .includeApplication(app.getPackageName());
        if (in.getDnsServers().isEmpty()) {
            b.addDnsServer(InetAddress.getByName(FALLBACK_DNS));
        } else {
            b.addDnsServers(in.getDnsServers());
        }
        if (in.getMtu().isPresent()) b.setMtu(in.getMtu().get());
        if (in.getListenPort().isPresent()) b.setListenPort(in.getListenPort().get());
        return new Config.Builder().setInterface(b.build()).addPeers(config.getPeers()).build();
    }

    private static String message(Exception e) {
        if (e instanceof com.wireguard.android.backend.BackendException) {
            switch (((com.wireguard.android.backend.BackendException) e).getReason()) {
                case VPN_NOT_AUTHORIZED:
                    return "Android hasn't allowed the VPN: switch it on again";
                case DNS_RESOLUTION_FAILURE:
                    return "Couldn't look up the VPN server";
                default:
                    return "The VPN couldn't start (" + ((com.wireguard.android.backend
                            .BackendException) e).getReason() + ")";
            }
        }
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
