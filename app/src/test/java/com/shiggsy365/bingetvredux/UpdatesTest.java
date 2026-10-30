package com.shiggsy365.bingetvredux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Which release download suits the device, and version order. */
public class UpdatesTest {
    private static JSONArray assets(String... names) throws Exception {
        JSONArray out = new JSONArray();
        for (String n : names) out.put(new JSONObject().put("name", n).put("browser_download_url", "u/" + n));
        return out;
    }

    @Test
    public void picksTheDevicesAbi() throws Exception {
        JSONArray all = assets("BingeTV-Redux-1.0.5.apk", "BingeTV-Redux-1.0.5-arm64-v8a.apk",
                "BingeTV-Redux-1.0.5-armeabi-v7a.apk");
        assertEquals("u/BingeTV-Redux-1.0.5-armeabi-v7a.apk",
                Updates.apkFor(all, new String[]{"armeabi-v7a", "armeabi"}));
        assertEquals("u/BingeTV-Redux-1.0.5-arm64-v8a.apk",
                Updates.apkFor(all, new String[]{"arm64-v8a", "armeabi-v7a"}));
        // An ABI with no APK of its own gets the universal one.
        assertEquals("u/BingeTV-Redux-1.0.5.apk", Updates.apkFor(all, new String[]{"x86_64"}));
    }

    @Test
    public void olderReleasesWithOneApk() throws Exception {
        assertEquals("u/BingeTV-Redux-1.0.4.apk", Updates.apkFor(
                assets("notes.txt", "BingeTV-Redux-1.0.4.apk"), new String[]{"armeabi-v7a"}));
        assertNull(Updates.apkFor(assets("notes.txt"), new String[]{"armeabi-v7a"}));
    }

    @Test
    public void versionsCompareByNumber() {
        assertTrue(Updates.compare("1.10.0", "1.9.2") > 0);
        assertTrue(Updates.compare("1.0.4", "1.0.5") < 0);
        assertEquals(0, Updates.compare("1.0", "1.0.0"));
    }
}
