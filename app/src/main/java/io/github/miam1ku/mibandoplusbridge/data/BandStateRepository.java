// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider;
import io.github.miam1ku.mibandoplusbridge.service.OwnershipController;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.json.JSONObject;

/** Single writer for public, non-secret device state projected into MyDevices. */
public final class BandStateRepository {
    private static final Object STATE_LOCK = new Object();
    private final Context context;

    public BandStateRepository(Context context) {
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
    }

    public boolean isRegistered() {
        synchronized (STATE_LOCK) {
            // Do not open credential-encrypted preferences or decrypt bindings before unlock.
            return isUnlocked() && state().getBoolean("registered", false);
        }
    }

    public void registerDevice() throws Exception {
        synchronized (STATE_LOCK) {
            LocalPrefs state = state();
            JSONObject binding = new BindingStore(context).read();
            if (binding == null) throw new IllegalStateException("UNPROVISIONED");
            String model = binding.optString("model", "");
            if (model.isBlank()
                    || !TransportObservation.supportsLive(TransportObservation.read(context), model)) {
                throw new IllegalStateException("OBSERVED_PROFILE_REQUIRED");
            }
            String mac = binding.optString("address", "");
            if (!mac.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
                    || binding.optString("userId", "").isBlank()
                    || binding.optString("region", "").isBlank()) {
                throw new IllegalStateException("BINDING_INCOMPLETE");
            }
            if (!binding.optString("token", "").matches("[0-9A-Fa-f]{32}")) {
                throw new IllegalStateException("TOKEN_ENCODING_UNSUPPORTED");
            }
            String deviceId = deviceId(binding);
            String existing = state.getString("deviceId", "");
            // A retained snapshot owns its existing history, even after removal.
            if (!existing.isEmpty() && !existing.equals(deviceId)) {
                throw new IllegalStateException("DEVICE_IDENTITY_CHANGED");
            }
            if (state.getBoolean("registered", false)) {
                requireMatchingBinding(state, binding);
                return;
            }
            String name = binding.optString("deviceName", "").trim();
            if (name.isBlank() || name.length() > 80) name = model;
            long now = System.currentTimeMillis();
            if (!state.edit().putString("deviceId", deviceId)
                    .putString("identitySource", binding.optString("did", "").isBlank() ? "verifiedMac" : "did")
                    .putString("name", name).putString("mac", mac).putString("modelId", model)
                    .remove("firmware")
                    .putBoolean("registered", true).putBoolean("connected", false)
                    .putLong("registeredAtMs", state.getLong("registeredAtMs", 0) > 0
                            ? state.getLong("registeredAtMs", 0) : now)
                    .putLong("lastUpdateMs", now)
                    .putLong("revision", state.getLong("revision", 0) + 1).commit()) {
                throw new IllegalStateException("BAND_STATE_STORAGE_FAILED");
            }
            context.getContentResolver().notifyChange(DeviceCardProvider.URI, null);
        }
    }

    public void unregisterDevice() {
        synchronized (STATE_LOCK) {
            LocalPrefs state = state();
            if (!state.edit().putBoolean("registered", false).putBoolean("connected", false)
                    .putLong("lastUpdateMs", System.currentTimeMillis())
                    .putLong("revision", state.getLong("revision", 0) + 1).commit()) {
                throw new IllegalStateException("BAND_STATE_STORAGE_FAILED");
            }
            context.getContentResolver().notifyChange(DeviceCardProvider.URI, null);
        }
    }

    public void recordVerifiedDevice(int battery, Integer chargerState, boolean connected,
                                     String firmware, String hardware) throws Exception {
        if (!isRegistered()) return;
        boolean nativeReady = new OwnershipController(context).nativeReady();
        synchronized (STATE_LOCK) {
            if (!isRegistered()) return;
            if (!nativeReady) {
                throw new IllegalStateException("OFFICIAL_MODE");
            }
            if (battery < 0 || battery > 100 || chargerState == null
                    || (chargerState != 1 && chargerState != 2 && chargerState != 3)) {
                throw new IllegalArgumentException("BATTERY_STATE_UNCONFIRMED");
            }
            LocalPrefs state = state();
            BindingStore.Identity binding = new BindingStore(context).readIdentity();
            if (!state.getString("deviceId", "").equals(binding.deviceId())
                    || !state.getString("mac", "").equals(binding.address())
                    || !state.getString("modelId", "").equals(binding.model())) {
                throw new IllegalStateException("DEVICE_IDENTITY_CHANGED");
            }
            if (firmware == null || firmware.isBlank() || hardware == null || hardware.isBlank()) {
                throw new IllegalStateException("FIRMWARE_OR_MODEL_UNSUPPORTED");
            }
            if (!state.edit().putBoolean("connected", connected).putInt("battery", battery)
                    .putBoolean("charging", chargerState == 1)
                    .putString("verifiedFirmware", firmware).putString("verifiedHardware", hardware)
                    .putLong("lastUpdateMs", System.currentTimeMillis())
                    .putLong("revision", state.getLong("revision", 0) + 1).commit()) {
                throw new IllegalStateException("BAND_STATE_STORAGE_FAILED");
            }
            context.getContentResolver().notifyChange(DeviceCardProvider.URI, null);
        }
    }

    private boolean isUnlocked() {
        UserManager users = context.getSystemService(UserManager.class);
        return users != null && users.isUserUnlocked();
    }

    private LocalPrefs state() {
        if (!isUnlocked()) throw new IllegalStateException("USER_LOCKED");
        return LocalPrefs.open(context, "band-state");
    }

    public static String deviceId(JSONObject binding) throws Exception {
        String did = binding.optString("did", "");
        String mac = binding.optString("address", "");
        if (did.isBlank() && !mac.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) {
            throw new IllegalStateException("DEVICE_IDENTITY_UNCONFIRMED");
        }
        return "miband11_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((did.isBlank() ? mac.replace(":", "") : did).getBytes(StandardCharsets.UTF_8)));
    }

    private static void requireMatchingBinding(LocalPrefs state, JSONObject binding) throws Exception {
        if (!state.getString("deviceId", "").equals(deviceId(binding))
                || !state.getString("mac", "").equals(binding.optString("address", ""))
                || !state.getString("modelId", "").equals(binding.optString("model", ""))) {
            throw new IllegalStateException("DEVICE_IDENTITY_CHANGED");
        }
    }

    public void markSessionClosed() {
        synchronized (STATE_LOCK) {
            if (!isUnlocked()) return;
            LocalPrefs state = state();
            if (state.getString("deviceId", "").isBlank() || !state.getBoolean("connected", false)) return;
            if (!state.edit().putBoolean("connected", false)
                    .putLong("lastUpdateMs", System.currentTimeMillis())
                    .putLong("revision", state.getLong("revision", 0) + 1).commit()) {
                throw new IllegalStateException("BAND_STATE_STORAGE_FAILED");
            }
            context.getContentResolver().notifyChange(DeviceCardProvider.URI, null);
        }
    }

    public void disconnected() {
        synchronized (STATE_LOCK) {
            if (!isUnlocked()) return;
            LocalPrefs state = state();
            if (!state.getBoolean("connected", false)) return;
            if (!state.edit().putBoolean("connected", false).putInt("battery", -1)
                    .putLong("lastUpdateMs", System.currentTimeMillis())
                    .putLong("revision", state.getLong("revision", 0) + 1).commit()) {
                throw new IllegalStateException("BAND_STATE_STORAGE_FAILED");
            }
            context.getContentResolver().notifyChange(DeviceCardProvider.URI, null);
        }
    }

    public void recordSyncCompleted() {
        synchronized (STATE_LOCK) {
            if (!isRegistered()) return;
            LocalPrefs state = state();
            if (!state.edit().putLong("lastSyncAtMs", System.currentTimeMillis())
                    .putLong("revision", state.getLong("revision", 0) + 1).commit()) {
                throw new IllegalStateException("BAND_STATE_STORAGE_FAILED");
            }
            context.getContentResolver().notifyChange(DeviceCardProvider.URI, null);
        }
    }

    /** Band-scoped activity only. This never writes OHealth's shared daily statistics. */
    public void recordSport(long stepsToday, int heartRate, long measuredAtMs) {
        synchronized (STATE_LOCK) {
            if (!isUnlocked()) return;
            LocalPrefs state = state();
            if (state.getString("deviceId", "").isBlank()) return;
            if (measuredAtMs <= 0) return;
            boolean hasSteps = stepsToday >= 0 && stepsToday <= 200_000L
                    && measuredAtMs >= state.getLong("stepsAtMs", 0);
            boolean hasHeartRate = heartRate > 0 && heartRate <= 250
                    && measuredAtMs >= state.getLong("heartRateAtMs", 0);
            if (!hasSteps && !hasHeartRate) return;
            LocalPrefs.Editor edit = state.edit();
            if (hasSteps) edit.putLong("stepsToday", stepsToday).putLong("stepsAtMs", measuredAtMs);
            if (hasHeartRate) edit.putInt("heartRate", heartRate).putLong("heartRateAtMs", measuredAtMs);
            if (!edit.remove("sportAtMs")
                    .putLong("revision", state.getLong("revision", 0) + 1).commit()) {
                throw new IllegalStateException("BAND_STATE_STORAGE_FAILED");
            }
            context.getContentResolver().notifyChange(DeviceCardProvider.URI, null);
        }
    }
    /** A daily report replaces the total. Minute sums never lower the same local day. */
    public void recordDailySteps(long stepsToday, long measuredAtMs, boolean authoritative) {
        synchronized (STATE_LOCK) {
            if (!isUnlocked()) return;
            LocalPrefs state = state();
            if (state.getString("deviceId", "").isBlank()) return;
            if (measuredAtMs <= 0 || stepsToday < 0 || stepsToday > 200_000L) return;
            long previousAt = state.getLong("stepsAtMs", 0);
            long previous = state.getLong("stepsToday", -1);
            boolean sameDay = previousAt > 0 && LocalDate.ofInstant(Instant.ofEpochMilli(previousAt), ZoneId.systemDefault())
                    .equals(LocalDate.ofInstant(Instant.ofEpochMilli(measuredAtMs), ZoneId.systemDefault()));
            if (measuredAtMs < previousAt && !(authoritative && sameDay)) return;
            if (!authoritative && sameDay && previous >= stepsToday) return;
            long storedAt = authoritative && previousAt > measuredAtMs ? previousAt : measuredAtMs;
            if (previous == stepsToday && storedAt == previousAt) return;
            if (!state.edit().putLong("stepsToday", stepsToday).putLong("stepsAtMs", storedAt)
                    .remove("sportAtMs").putLong("revision", state.getLong("revision", 0) + 1).commit()) {
                throw new IllegalStateException("BAND_STATE_STORAGE_FAILED");
            }
            context.getContentResolver().notifyChange(DeviceCardProvider.URI, null);
        }
    }
    /** Already stored files update the card without waiting for another band download. */
    public static void refreshStoredSteps(Context context) {
        ZoneId zone = ZoneId.systemDefault();
        long dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
        long dayEnd = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        try {
            BindingStore.Identity binding = new BindingStore(context).readIdentity();
            if (binding == null || binding.deviceId().isBlank()) return;
            java.util.List<io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser.Measurement> rows;
            try (HealthRecordStore store = new HealthRecordStore(context)) {
                rows = store.storedSteps(binding.deviceId(), dayStart, dayEnd);
            }
            io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser.DailySteps steps =
                    io.github.miam1ku.mibandoplusbridge.protocol.BandHistoryParser.todaySteps(rows, dayStart, dayEnd);
            if (steps == null) return;
            new BandStateRepository(context).recordDailySteps(steps.steps, steps.measuredAtMs, steps.authoritative);
        } catch (Exception unavailable) {
            // A locked or unconfirmed store leaves the last displayed total unchanged.
        }
    }



}
