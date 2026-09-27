// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TransportObservationTest {
    private static JSONObject verified() throws Exception {
        return new JSONObject()
                .put("model", "miwear.watch.q66cn")
                .put("transport", "SPP")
                .put("versionName", "3.2.15")
                .put("rfcommUuid", "00001101-0000-1000-8000-00805f9b34fb")
                .put("rfcommSecure", true)
                .put("officialAuthConnected", true)
                .put("appCapability", TransportObservation.APP_CAPABILITY)
                .put("authOobPresent", false)
                .put("authAppDeviceIdPresent", false);
    }

    @Test public void verifiedProfileAllowsLiveSession() throws Exception {
        assertTrue(TransportObservation.supportsLive(verified(), "miwear.watch.q66cn"));
    }

    @Test public void emptyOrPartialProfileBlocksLiveSession() throws Exception {
        assertFalse(TransportObservation.supportsLive(new JSONObject(), "miwear.watch.q66cn"));
        assertFalse(TransportObservation.supportsLive(verified().put("officialAuthConnected", false),
                "miwear.watch.q66cn"));
        assertFalse(TransportObservation.supportsLive(verified(), "other.model"));
        assertFalse(TransportObservation.supportsLive(null, "miwear.watch.q66cn"));
    }
}
