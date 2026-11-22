// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers;
import io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider;
import org.json.JSONObject;

/**
 * Lock-screen 1x1 cards read UMS, and UMS reads health
 * {@code open/heartRate} ({@code lastHeartRate} JSON) and {@code open/sportData}.
 * A connected band has to answer those two queries the way an OPPO watch does.
 */
final class OHealthLockCardHook {
    private static final String HEART = "content://com.heytap.health.sporthealthprovider/open/heartRate";
    private static final String SPORT = "content://com.heytap.health.sporthealthprovider/assistantScreen/sport";

    private static volatile Context app;

    private OHealthLockCardHook() {}

    static void install(Context context, ClassLoader loader) {
        if (context != null) app = context.getApplicationContext();
        XC_MethodHook heartHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                param.setResult(heart((Cursor) param.getResult()));
            }
        };
        XC_MethodHook stepHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                param.setResult(steps((Cursor) param.getResult()));
            }
        };
        try {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.core.provider.adapter.open.HeartRateAdapter", loader,
                    "query", String[].class, String.class, String[].class, String.class, heartHook);
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.core.provider.adapter.open.SportDataAdapter", loader,
                    "query", String[].class, String.class, String[].class, String.class, stepHook);
        } catch (Throwable renamed) {
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.core.provider.adapter.open.c", loader,
                    "e", String.class, String.class, String[].class, heartHook);
            XposedHelpers.findAndHookMethod(
                    "com.heytap.health.core.provider.adapter.open.y", loader,
                    "e", String.class, String.class, String[].class, stepHook);
        }
    }

    static void notifyCards(Context context) {
        if (context == null) return;
        try {
            context.getContentResolver().notifyChange(Uri.parse(HEART), null);
            context.getContentResolver().notifyChange(Uri.parse(SPORT), null);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_LOCK_CARD_NOTIFY " + failure.getClass().getSimpleName());
        }
    }

    static String heartPayload(int heart, long at) {
        return "{\"value\":" + heart + ",\"timeStamp\":" + at + "}";
    }

    /** True when the band sample should replace the row the provider already built. */
    static boolean newerHeart(String existing, long at) {
        if (at <= 0) return false;
        if (existing == null || existing.isBlank()) return true;
        try {
            return at > new JSONObject(existing).optLong("timeStamp", 0);
        } catch (org.json.JSONException invalid) {
            return true;
        }
    }

    static long stepToShow(long shown, long band) {
        if (band <= 0 || band > 200_000L) return shown;
        return Math.max(shown, band);
    }

    private static Bundle band() {
        Bundle shown = OHealthDeviceHook.registeredSnapshot();
        if (shown != null) return shown;
        Context context = app;
        if (context == null) return null;
        long token = Binder.clearCallingIdentity();
        try {
            Bundle result = context.getContentResolver().call(DeviceCardProvider.URI, "bandDisplay", null, null);
            if (result == null || !result.getBoolean("registered", false)) return null;
            return result;
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_LOCK_BAND " + failure.getClass().getSimpleName());
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static Cursor heart(Cursor original) {
        Bundle band = band();
        if (band == null) {
            HookRuntime.log("OHEALTH_LOCK_HEART no-band");
            return rewind(original);
        }
        int rate = band.getInt("heartRate", -1);
        long at = band.getLong("heartRateAtMs", 0);
        if (rate < 40 || rate > 220) return rewind(original);
        String existing = column(original, "lastHeartRate");
        if (!newerHeart(existing, at)) return rewind(original);
        MatrixCursor out = new MatrixCursor(new String[] {"lastHeartRate"}, 1);
        out.addRow(new Object[] {heartPayload(rate, at)});
        if (original != null) original.close();
        HookRuntime.log("OHEALTH_LOCK_HEART served");
        return out;
    }

    private static Cursor steps(Cursor original) {
        Bundle band = band();
        if (band == null || original == null) return rewind(original);
        if (!original.moveToFirst()) return rewind(original);
        int index = original.getColumnIndex("step");
        if (index < 0) return rewind(original);
        long shown = readLong(original, index);
        long next = stepToShow(shown, band.getLong("stepsToday", -1));
        if (next == shown) return rewind(original);
        String[] names = original.getColumnNames();
        MatrixCursor out = new MatrixCursor(names, original.getCount());
        do {
            Object[] row = new Object[names.length];
            for (int i = 0; i < names.length; i++) {
                row[i] = i == index ? next : cell(original, i);
            }
            out.addRow(row);
        } while (original.moveToNext());
        Bundle extras = original.getExtras();
        if (extras != null) out.setExtras(new Bundle(extras));
        original.close();
        return out;
    }

    private static Cursor rewind(Cursor cursor) {
        if (cursor != null) cursor.moveToPosition(-1);
        return cursor;
    }

    private static String column(Cursor cursor, String name) {
        if (cursor == null || !cursor.moveToFirst()) return "";
        int index = cursor.getColumnIndex(name);
        if (index < 0 || cursor.isNull(index)) return "";
        String value = cursor.getString(index);
        return value == null ? "" : value;
    }

    private static long readLong(Cursor cursor, int index) {
        if (cursor.isNull(index)) return 0;
        return switch (cursor.getType(index)) {
            case Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index);
            case Cursor.FIELD_TYPE_FLOAT -> (long) cursor.getDouble(index);
            case Cursor.FIELD_TYPE_STRING -> parse(cursor.getString(index));
            default -> 0;
        };
    }

    private static long parse(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private static Object cell(Cursor cursor, int index) {
        if (cursor.isNull(index)) return null;
        return switch (cursor.getType(index)) {
            case Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index);
            case Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index);
            case Cursor.FIELD_TYPE_STRING -> cursor.getString(index);
            case Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index);
            default -> null;
        };
    }
}
