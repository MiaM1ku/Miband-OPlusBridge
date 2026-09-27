// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;

/** Native home cards stay untouched. Imported records are what those pages read. */
public final class OHealthHomeMetricHook {
    private static final String HOST = "com.heytap.health";

    private OHealthHomeMetricHook() {}

    public static synchronized void install(Context supplied, ClassLoader loader) {
        if (!HOST.equals(supplied.getPackageName())) return;
    }
}
