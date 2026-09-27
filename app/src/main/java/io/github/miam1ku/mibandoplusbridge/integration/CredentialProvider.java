// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.integration;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;
import io.github.miam1ku.mibandoplusbridge.BuildConfig;
import io.github.miam1ku.mibandoplusbridge.HostIdentity;
import io.github.miam1ku.mibandoplusbridge.data.BindingStore;
import io.github.miam1ku.mibandoplusbridge.data.ImportWindow;
import io.github.miam1ku.mibandoplusbridge.data.ProtocolCaptureStore;
import io.github.miam1ku.mibandoplusbridge.data.TransportObservation;
import org.json.JSONObject;

/** A selected-device, process-lifetime import window. No credential read IPC. */
public final class CredentialProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://io.github.miam1ku.mibandoplusbridge.credentials");
    private static final String[] STRINGS = {"address", "did", "model", "productId", "userId",
            "region", "appDeviceId", "token", "firmware", "oob", "deviceName"};
    private static final String[] UUID_FIELDS = {"fitness", "mass", "otaRX", "otaTX", "protoRX",
            "protoTX", "service", "voice"};
    private BindingStore store;
    private final ImportWindow window = new ImportWindow();
    private final ImportWindow diagnosticWindow = new ImportWindow();
    private int diagnosticWrites;
    private final ImportWindow captureWindow = new ImportWindow();
    private ProtocolCaptureStore captureStore;
    private String captureError = "";

    @Override public boolean onCreate() {
        store = new BindingStore(getContext());
        captureStore = new ProtocolCaptureStore(getContext());
        return true;
    }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        boolean self = HostIdentity.isSelf();
        if (!self) {
            HostIdentity.requireMiCaller(getContext());
        }
        if (!getContext().getSystemService(UserManager.class).isUserUnlocked()) {
            window.close();
            diagnosticWindow.close();
            closeCapture();
            return result("USER_LOCKED");
        }
        boolean open = window.isOpen(SystemClock.elapsedRealtime());
        boolean diagnosticOpen = diagnosticWindow.isOpen(SystemClock.elapsedRealtime());
        boolean captureOpen = captureWindow.isOpen(SystemClock.elapsedRealtime());
        if (!captureOpen) closeCapture();
        try {
            return switch (method) {
                case "openWindow" -> {
                    requireSelf(self);
                    if (extras == null) throw new IllegalArgumentException("DEVICE_SELECTION_REQUIRED");
                    String address = extras.getString("address", "");
                    window.open(address, SystemClock.elapsedRealtime());
                    diagnosticWindow.open(address, SystemClock.elapsedRealtime());
                    diagnosticWrites = 0;
                    closeCapture();
                    yield result("IMPORT_WINDOW_OPEN");
                }
                case "closeWindow" -> {
                    requireSelf(self);
                    window.close();
                    diagnosticWindow.close();
                    closeCapture();
                    yield result("IMPORT_WINDOW_CLOSED");
                }
                case "getImportRequest" -> {
                    Bundle request = result(open ? "IMPORT_WINDOW_OPEN" : "IMPORT_WINDOW_CLOSED");
                    if (open) {
                        request.putString("address", window.address());
                        request.putString("nonce", window.nonce());
                    }
                    yield request;
                }
                case "importBinding" -> {
                    if (self) throw new SecurityException("MI_CALLER_REQUIRED");
                    yield importBinding(extras);
                }
                case "openDiagnostics" -> {
                    requireSelf(self);
                    JSONObject binding = store.read();
                    if (binding == null) yield result("UNPROVISIONED");
                    window.close();
                    closeCapture();
                    diagnosticWindow.open(binding.getString("address"), SystemClock.elapsedRealtime());
                    diagnosticWrites = 0;
                    yield result("DIAGNOSTIC_WINDOW_OPEN");
                }
                case "getDiagnosticRequest" -> {
                    ImportWindow active = diagnosticWindow.isOpen(SystemClock.elapsedRealtime())
                            ? diagnosticWindow
                            : (open ? window : null);
                    Bundle request = result(active != null ? "DIAGNOSTIC_WINDOW_OPEN" : "DIAGNOSTIC_WINDOW_CLOSED");
                    if (active != null) {
                        request.putString("address", active.address());
                        request.putString("nonce", active.nonce());
                    }
                    yield request;
                }
                case "observeTransport" -> {
                    if (self) throw new SecurityException("MI_CALLER_REQUIRED");
                    yield observeTransport(extras);
                }
                case "openCapture" -> {
                    requireSelf(self);
                    if (!BuildConfig.DEBUG) throw new SecurityException("ANALYSIS_BUILD_REQUIRED");
                    JSONObject binding = store.read();
                    if (binding == null) yield result("UNPROVISIONED");
                    window.close();
                    closeCapture();
                    captureError = "";
                    captureStore.start();
                    captureWindow.open(binding.getString("address"), SystemClock.elapsedRealtime());
                    diagnosticWindow.open(binding.getString("address"), SystemClock.elapsedRealtime());
                    diagnosticWrites = 0;
                    Bundle status = result("PROTOCOL_CAPTURE_OPEN");
                    captureStore.addStatus(status);
                    yield status;
                }
                case "getCaptureRequest" -> {
                    Bundle request = result(BuildConfig.DEBUG && captureOpen
                            ? "PROTOCOL_CAPTURE_OPEN" : "PROTOCOL_CAPTURE_CLOSED");
                    if (BuildConfig.DEBUG && captureOpen) {
                        request.putString("address", captureWindow.address());
                        request.putString("nonce", captureWindow.nonce());
                    }
                    yield request;
                }
                case "recordPacket" -> {
                    if (self || !BuildConfig.DEBUG) throw new SecurityException("MI_ANALYSIS_CALLER_REQUIRED");
                    if (extras == null) throw new SecurityException("PROTOCOL_CAPTURE_CLOSED");
                    captureWindow.authorize(extras.getString("nonce"), extras.getString("address"), SystemClock.elapsedRealtime());
                    captureStore.append(extras);
                    yield result("PACKET_CAPTURED");
                }
                case "captureFault" -> {
                    if (self || !BuildConfig.DEBUG || extras == null) throw new SecurityException("MI_ANALYSIS_CALLER_REQUIRED");
                    captureWindow.authorize(extras.getString("nonce"), extras.getString("address"), SystemClock.elapsedRealtime());
                    captureError = "CAPTURE_INCOMPLETE";
                    closeCapture();
                    getContext().getContentResolver().notifyChange(URI, null);
                    yield result(captureError);
                }
                case "status" -> {
                    requireSelf(self);
                    JSONObject saved = store.read();
                    JSONObject observation = TransportObservation.read(getContext());
                    boolean ready = saved != null && TransportObservation.supportsLive(
                            observation, saved.optString("model", ""));
                    Bundle status = result(open ? "IMPORT_WINDOW_OPEN"
                            : saved == null ? "UNPROVISIONED"
                            : ready ? "BINDING_SAVED" : "BINDING_INCOMPLETE");
                    if (saved != null) {
                        TransportObservation.applyToBinding(saved, observation);
                        for (String field : new String[]{"address", "model", "productId", "firmware"}) {
                            status.putString(field, saved.optString(field, ""));
                        }
                        status.putString("missing", TransportObservation.missingForLive(saved, observation));
                        status.putString("transportObservation", observation.toString());
                        status.putBoolean("diagnosticOpen", diagnosticOpen);
                        status.putBoolean("captureOpen", captureOpen);
                        status.putString("captureError", captureError);
                        captureStore.addStatus(status);
                    }
                    yield status;
                }
                default -> throw new IllegalArgumentException("UNSUPPORTED_OPERATION");
            };
        } catch (SecurityException | IllegalArgumentException rejected) {
            throw rejected;
        } catch (Exception unavailable) {
            window.close();
            diagnosticWindow.close();
            if (captureOpen) captureError = "CAPTURE_STORAGE_FAILED";
            closeCapture();
            return result("CREDENTIAL_STORAGE_FAILED");
        }
    }

    private void closeCapture() {
        captureWindow.close();
        try {
            captureStore.close();
        } catch (Exception failure) {
            captureError = "CAPTURE_CLOSE_FAILED";
        }
    }

    private Bundle observeTransport(Bundle input) throws Exception {
        if (input == null) throw new SecurityException("DIAGNOSTIC_WINDOW_CLOSED");
        String nonce = input.getString("nonce");
        String address = input.getString("address");
        long now = SystemClock.elapsedRealtime();
        try {
            diagnosticWindow.authorize(nonce, address, now);
        } catch (SecurityException rejected) {
            window.authorize(nonce, address, now);
        }
        if (diagnosticWrites >= 32) {
            diagnosticWindow.close();
            throw new SecurityException("DIAGNOSTIC_LIMIT_REACHED");
        }
        JSONObject observation = TransportObservation.read(getContext());
        for (String name : new String[]{"transport", "connectionClass", "queueClass", "versionName", "authImplementation", "rfcommUuid", "model", "firmware", "productId"}) {
            String value = input.getString(name);
            if (value != null && !value.isBlank()) {
                if (value.length() > 256) throw new IllegalArgumentException("OBSERVATION_TOO_LARGE");
                observation.put(name, value);
            }
        }
        for (String name : new String[]{"apiVersion", "authCtorVersion", "appCapability", "deviceType", "accessType"}) {
            if (input.containsKey(name)) observation.put(name, input.getInt(name));
        }
        for (String name : new String[]{"officialAuthConnected", "authFlagObserved", "rfcommSecure", "rfcommSocketConnected", "authAppDeviceIdPresent", "authOobPresent"}) {
            if (input.containsKey(name)) observation.put(name, input.getBoolean(name));
        }
        observation.put("recordedAtMs", System.currentTimeMillis());
        if (!TransportObservation.write(getContext(), observation)) {
            throw new IllegalStateException("OBSERVATION_STORAGE_FAILED");
        }
        JSONObject saved = store.read();
        if (saved != null) {
            TransportObservation.applyToBinding(saved, observation);
            saved.put("missing", TransportObservation.missingForLive(saved, observation));
            store.save(saved);
        }
        diagnosticWrites++;
        getContext().getContentResolver().notifyChange(URI, null);
        return result("TRANSPORT_OBSERVED");
    }

    private Bundle importBinding(Bundle input) throws Exception {
        if (input == null) throw new SecurityException("IMPORT_WINDOW_CLOSED");
        window.authorize(input.getString("nonce"), input.getString("address"), SystemClock.elapsedRealtime());
        JSONObject record = new JSONObject();
        for (String key : STRINGS) {
            String value = input.getString(key);
            if (value != null) {
                if (value.length() > 4096) throw new IllegalArgumentException("FIELD_TOO_LARGE");
                record.put(key, value);
            }
        }
        for (String key : new String[]{"type", "accessType"}) {
            if (input.containsKey(key)) record.put(key, input.getInt(key));
        }
        Bundle uuids = input.getBundle("privateUUID");
        if (uuids != null) {
            JSONObject privateUUID = new JSONObject();
            for (String key : UUID_FIELDS) {
                String value = uuids.getString(key);
                if (value != null) {
                    if (value.length() > 128) throw new IllegalArgumentException("UUID_TOO_LARGE");
                    privateUUID.put(key, value);
                }
            }
            record.put("privateUUID", privateUUID);
        }
        JSONObject observation = TransportObservation.read(getContext());
        TransportObservation.applyToBinding(record, observation);
        record.put("missing", TransportObservation.missingForLive(record, observation));
        store.save(record);
        closeCapture();
        window.close();
        getContext().getContentResolver().notifyChange(URI, null);
        boolean ready = record.optString("missing").isBlank()
                && TransportObservation.supportsLive(observation, record.optString("model", ""));
        return result(ready ? "BINDING_SAVED" : "BINDING_INCOMPLETE");
    }


    private static void requireSelf(boolean self) {
        if (!self) throw new SecurityException("OWNER_ONLY");
    }

    private static Bundle result(String status) {
        Bundle out = new Bundle();
        out.putString("status", status);
        return out;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        throw new SecurityException("CREDENTIAL_QUERY_FORBIDDEN");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("USE_IMPORT_WINDOW"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new SecurityException("USE_IMPORT_WINDOW"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new SecurityException("CREDENTIAL_DELETE_FORBIDDEN"); }
}
