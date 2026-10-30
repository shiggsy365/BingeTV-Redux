package com.shiggsy365.bingetvredux.stremio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

/** StreamInfo against AIOStreams' decorated text (real streams, with a dummy URL). */
public class StreamInfoTest {
    private static Stream stream(String name, String description, String filename) throws Exception {
        return Stream.fromJson(new JSONObject().put("name", name).put("description", description)
                .put("url", "https://example.com/v.mkv")
                .put("behaviorHints", new JSONObject().put("filename", filename)), "AIOStreams", 0);
    }

    @Test
    public void remuxWithDtsHd() throws Exception {
        StreamInfo info = StreamInfo.of(stream(
                "   4K ‍‍    ‍‍‍⚡‍‍\n  〈Remux〉‍ ",
                "✎  Inception (2010) \n▣  HEVC  ♬  DTS-HD MA  ♯ 5.1\n⛊ [TB] MediaFusion · FraMeSToR",
                "Inception.2010.UHD.BluRay.2160p.DTS-HD.MA.5.1.HEVC.REMUX-FraMeSToR"));
        assertEquals("4k", info.resolution);
        assertEquals("remux", info.release);
        assertEquals(Arrays.asList("dtshdma"), info.audio);
        assertEquals("5.1", info.channels);
        assertTrue(info.cached);
        assertEquals("TorBox", info.debrid);
        assertEquals("MediaFusion", info.provider);
        assertEquals("FraMeSToR", info.group);
        assertEquals(Arrays.asList("dl_cached", "res_4k", "rel_remux", "aud_dtshdma", "ch_51"), info.badges());
    }

    @Test
    public void dolbyVisionAndSizeInSmallCapitals() throws Exception {
        StreamInfo dv = StreamInfo.of(stream("4K ⚡\n〈Remux〉",
                "✎  Inception (2010) \n▣  HEVC  ✦  DV · HDR10  \n⛊ [TB] MediaFusion · NAHOM",
                "Inception.2010.2160p.4K.BDRemux.HEVC.HDR10.Dolby.Vision-NAHOM"));
        assertEquals(Arrays.asList("dv", "hdr10"), dv.visual);
        StreamInfo hdr = StreamInfo.of(stream("4K ⚡\n〈Bluray〉",
                "✎  Inception (2010) \n▣  HEVC  ✦  10bit · HDR  \n◈ 22.5 GB · 20.3 ᴹᵇᵖˢ \n⛊ [TB] StremThru Torz · ALT",
                "Inception.2010.UHD.BluRay.2160p.2Audio.DTS-HD.MA5.1.x265.10bit.HDR-ALT"));
        assertEquals("bluray", hdr.release);
        assertEquals(Arrays.asList("hdr"), hdr.visual);
        assertEquals("22.5 GB", hdr.size);
        assertEquals("20.3 MBps", hdr.bitrate.replace("bps", "Bps"));
        assertEquals("StremThru Torz", hdr.provider);
        assertEquals("ALT", hdr.group);
    }

    @Test
    public void normaliseDropsDecoration() {
        assertEquals("netflix", StreamInfo.normalise("ɴᴇᴛғʟɪx"));
        assertEquals("HEVC DV", StreamInfo.normalise("▣ HEVC ✦ DV"));
    }
}
