// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public final class BindingShapeTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String MAC = "AA:BB:CC:DD:EE:FF";

    @Test public void blankUserIdStillRegisters() throws Exception {
        assertNull(BindingShape.reject(binding().put("userId", "")));
    }

    @Test public void regionAndTokenAndAddressAreNamed() throws Exception {
        assertEquals("BINDING_REGION", BindingShape.reject(binding().put("region", "")));
        assertEquals("TOKEN_ENCODING_UNSUPPORTED", BindingShape.reject(binding().put("token", "not-a-key")));
        assertEquals("BINDING_ADDRESS", BindingShape.reject(binding().put("address", "aa:bb:cc:dd:ee:ff")));
        assertEquals("UNPROVISIONED", BindingShape.reject(null));
    }

    @Test public void descriptionOmitsSecrets() throws Exception {
        String text = BindingShape.describe(binding());
        assertFalse(text.contains(TOKEN));
        assertFalse(text.contains(MAC));
        assertFalse(text.contains("CN"));
        assertFalse(text.contains("123456"));
        assertEquals("address=ok userId=set region=set token=hex32 model=miwear.watch.n67cn", text);
    }

    @Test public void otherTokenReportsLengthOnly() throws Exception {
        String text = BindingShape.describe(binding().put("token", "zzzz"));
        assertEquals("address=ok userId=set region=set token=other len=4 model=miwear.watch.n67cn", text);
    }

    private static JSONObject binding() throws Exception {
        return new JSONObject()
                .put("address", MAC)
                .put("userId", "123456")
                .put("region", "CN")
                .put("token", TOKEN)
                .put("model", "miwear.watch.n67cn");
    }
}
