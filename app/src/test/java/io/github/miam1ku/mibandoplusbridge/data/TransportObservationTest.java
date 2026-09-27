// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TransportObservationTest {
    private static JSONObject verified(String model, String versionName) throws Exception {
        return new JSONObject()
                .put("model", model)
                .put("transport", "SPP")
                .put("versionName", versionName)
                .put("rfcommUuid", "00001101-0000-1000-8000-00805f9b34fb")
                .put("rfcommSecure", true)
                .put("officialAuthConnected", true)
                .put("appCapability", TransportObservation.APP_CAPABILITY)
                .put("authOobPresent", false)
                .put("authAppDeviceIdPresent", false);
    }

    @Test public void capturedSppWearAuthAllowsLiveSession() throws Exception {
        assertTrue(TransportObservation.supportsLive(
                verified("miwear.watch.q66cn", "3.2.15"), "miwear.watch.q66cn"));
        assertTrue(TransportObservation.supportsLive(
                verified("lchz.watch.m67", "1.0.50"), "lchz.watch.m67"));
    }

    @Test public void emptyMismatchedOrOobProfileBlocksLiveSession() throws Exception {
        JSONObject band11 = verified("miwear.watch.q66cn", "3.2.15");
        assertFalse(TransportObservation.supportsLive(new JSONObject(), "miwear.watch.q66cn"));
        assertFalse(TransportObservation.supportsLive(band11.put("officialAuthConnected", false),
                "miwear.watch.q66cn"));
        assertFalse(TransportObservation.supportsLive(verified("miwear.watch.q66cn", "3.2.15"), "other.model"));
        assertFalse(TransportObservation.supportsLive(null, "miwear.watch.q66cn"));
        assertFalse(TransportObservation.supportsLive(
                verified("lchz.watch.m67", "1.0.50").put("authOobPresent", true), "lchz.watch.m67"));
        assertFalse(TransportObservation.supportsLive(
                verified("lchz.watch.m67", "1.0.50").put("authAppDeviceIdPresent", true), "lchz.watch.m67"));
        assertFalse(TransportObservation.supportsLive(
                verified("lchz.watch.m67", "1.0.50").put("appCapability", 0), "lchz.watch.m67"));
    }
}
