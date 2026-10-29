package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.ArrayList;
import java.util.Map;

import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer;

/**
 * Builds the app's players. Video and audio use the device's own decoders; audio they can't
 * decode (DTS and TrueHD on most Fire TV devices, for example) falls back to FFmpeg.
 */
@OptIn(markerClass = UnstableApi.class)
public final class Players {
    private Players() {}

    public static ExoPlayer build(Context context) {
        return build(context, null);
    }

    /** A player whose HTTP requests carry {@code headers} (for streams that need them). */
    public static ExoPlayer build(Context context, Map<String, String> headers) {
        // Panels and debrid services often redirect to another server, sometimes across
        // http/https.
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000);
        if (headers != null && !headers.isEmpty()) http.setDefaultRequestProperties(headers);
        return new ExoPlayer.Builder(context, new RenderersFactory(context))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
    }

    private static final class RenderersFactory extends DefaultRenderersFactory {
        RenderersFactory(Context context) {
            super(context);
            setEnableDecoderFallback(true);
        }

        @Override
        protected void buildAudioRenderers(Context context, int extensionRendererMode,
                MediaCodecSelector mediaCodecSelector, boolean enableDecoderFallback,
                AudioSink audioSink, Handler eventHandler,
                AudioRendererEventListener eventListener, ArrayList<Renderer> out) {
            super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector,
                    enableDecoderFallback, audioSink, eventHandler, eventListener, out);
            // After the device's decoders, so it's only used for formats they can't handle.
            out.add(new FfmpegAudioRenderer(eventHandler, eventListener, audioSink));
        }
    }
}
