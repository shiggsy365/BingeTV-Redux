package com.shiggsy365.bingetvredux;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Reads a YouTube (googlevideo) stream in fixed-size pieces, each asked for with YouTube's own
 * {@code range=start-end} parameter. A single open-ended download of a trailer's video is
 * throttled and then dropped a few seconds in, which stalled trailers; ranged requests come at
 * full speed. Other URLs are read as they are.
 */
@OptIn(markerClass = UnstableApi.class)
final class YoutubeChunks implements DataSource {
    private static final long CHUNK = 4L * 1024 * 1024;

    static final class Factory implements DataSource.Factory {
        private final DefaultHttpDataSource.Factory mHttp;

        Factory(DefaultHttpDataSource.Factory http) {
            mHttp = http;
        }

        @Override
        public DataSource createDataSource() {
            return new YoutubeChunks(mHttp.createDataSource());
        }
    }

    private final DefaultHttpDataSource mUpstream;
    private DataSpec mSpec;
    private boolean mChunked;
    // The whole file's length (from the URL's clen), or C.LENGTH_UNSET.
    private long mTotal;
    // Where the next byte comes from, in the file, and where the open piece ends.
    private long mPosition;
    private long mChunkStart;
    private long mChunkEnd;
    private long mReadInChunk;

    private YoutubeChunks(DefaultHttpDataSource upstream) {
        mUpstream = upstream;
    }

    @Override
    public void addTransferListener(TransferListener listener) {
        mUpstream.addTransferListener(listener);
    }

    @Override
    public long open(DataSpec spec) throws IOException {
        String host = spec.uri.getHost();
        mChunked = host != null && host.endsWith("googlevideo.com");
        if (!mChunked) return mUpstream.open(spec);
        mSpec = spec;
        mTotal = C.LENGTH_UNSET;
        try {
            String clen = spec.uri.getQueryParameter("clen");
            if (clen != null) mTotal = Long.parseLong(clen);
        } catch (NumberFormatException ignored) {
            // Read piece by piece until one comes back short.
        }
        mPosition = spec.position;
        openChunk();
        if (spec.length != C.LENGTH_UNSET) return spec.length;
        return mTotal != C.LENGTH_UNSET ? mTotal - spec.position : C.LENGTH_UNSET;
    }

    private void openChunk() throws IOException {
        long end = mPosition + CHUNK - 1;
        if (mTotal != C.LENGTH_UNSET) end = Math.min(end, mTotal - 1);
        if (mSpec.length != C.LENGTH_UNSET) end = Math.min(end, mSpec.position + mSpec.length - 1);
        mChunkStart = mPosition;
        mChunkEnd = end;
        mReadInChunk = 0;
        Uri ranged = mSpec.uri.buildUpon().appendQueryParameter("range", mPosition + "-" + end).build();
        mUpstream.open(mSpec.buildUpon().setUri(ranged).setPosition(0).setLength(C.LENGTH_UNSET).build());
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (!mChunked) return mUpstream.read(buffer, offset, length);
        int n = mUpstream.read(buffer, offset, length);
        if (n == C.RESULT_END_OF_INPUT) {
            boolean shortPiece = mReadInChunk < mChunkEnd - mChunkStart + 1;
            boolean atEnd = (mTotal != C.LENGTH_UNSET && mPosition >= mTotal)
                    || (mSpec.length != C.LENGTH_UNSET && mPosition >= mSpec.position + mSpec.length)
                    || (mTotal == C.LENGTH_UNSET && shortPiece);
            if (atEnd) return C.RESULT_END_OF_INPUT;
            mUpstream.close();
            openChunk();
            n = mUpstream.read(buffer, offset, length);
            if (n == C.RESULT_END_OF_INPUT) return n;
        }
        mPosition += n;
        mReadInChunk += n;
        return n;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return mChunked && mSpec != null ? mSpec.uri : mUpstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return mUpstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        mUpstream.close();
    }
}
