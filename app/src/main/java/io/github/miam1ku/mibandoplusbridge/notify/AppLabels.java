// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/** Human application label. A package id is not a name the band should show. */
public final class AppLabels {
    private AppLabels() {}

    /** Empty when the package is invisible or its label is only the package id. */
    public static String label(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isBlank()) return "";
        try {
            PackageManager packages = context.getPackageManager();
            ApplicationInfo info = packages.getApplicationInfo(packageName, 0);
            String loaded = prefer(packageName, info.loadLabel(packages));
            if (!loaded.isBlank()) return loaded;
            return prefer(packageName, packages.getApplicationLabel(info));
        } catch (PackageManager.NameNotFoundException unavailable) {
            return "";
        }
    }

    /** A label equal to the package id is the lookup failure, not the application name. */
    public static String prefer(String packageName, CharSequence label) {
        String clean = NotificationRelay.sanitize(label);
        if (clean.isBlank() || packageName == null || clean.equals(packageName)) return "";
        return clean;
    }
}
