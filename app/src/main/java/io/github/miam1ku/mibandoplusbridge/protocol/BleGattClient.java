// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Official-app GATT UUIDs when present; otherwise Band 8 fe95 command characteristics. */
final class BleGattClient implements AutoCloseable {
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final UUID FE95 = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb");
    static final UUID CMD_READ = UUID.fromString("00000051-0000-1000-8000-00805f9b34fb");
    static final UUID CMD_WRITE = UUID.fromString("00000052-0000-1000-8000-00805f9b34fb");
    static final UUID ACTIVITY = UUID.fromString("00000053-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final BluetoothDevice device;
    private final UUID serviceUuid;
    private final UUID readUuid;
    private final UUID writeUuid;
    private final UUID activityUuid;
    private final BlockingQueue<byte[]> incoming = new ArrayBlockingQueue<>(32);
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic writer;
    private volatile boolean closed;
    private final BleV1Codec.Reassembler commands = new BleV1Codec.Reassembler();
    private final BleV1Codec.Reassembler activity = new BleV1Codec.Reassembler();
    private final Object writeLock = new Object();

    BleGattClient(Context context, BluetoothDevice device, JSONObject binding) {
        this.context = context;
        this.device = device;
        JSONObject uuids = binding.optJSONObject("privateUUID");
        this.serviceUuid = uuid(uuids, "service", FE95);
        this.readUuid = uuid(uuids, "protoRX", CMD_READ);
        this.writeUuid = uuid(uuids, "protoTX", CMD_WRITE);
        this.activityUuid = uuid(uuids, "fitness", ACTIVITY);
    }

    void connect() throws Exception {
        BluetoothGatt created = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        if (created == null) throw new SppDiagnosticClient.Failure("BLE_CONNECT_FAILED");
        gatt = created;
        if (!ready.await(20, TimeUnit.SECONDS)) throw new SppDiagnosticClient.Failure("BLE_READY_TIMEOUT");
        String error = failure.get();
        if (error != null) throw new SppDiagnosticClient.Failure(error);
    }

    void write(byte[] payload, boolean encrypted, int counter) throws Exception {
        BluetoothGattCharacteristic target = writer;
        BluetoothGatt link = gatt;
        if (target == null || link == null) throw new SppDiagnosticClient.Failure("BLE_DISCONNECTED");
        List<byte[]> frames = BleV1Codec.encodeOutgoing(payload, BleV1Codec.DEFAULT_MTU_PAYLOAD, encrypted, counter);
        synchronized (writeLock) {
            for (byte[] frame : frames) {
                target.setValue(frame);
                target.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                if (!link.writeCharacteristic(target)) throw new SppDiagnosticClient.Failure("BLE_WRITE_FAILED");
            }
        }
    }

    byte[] take(long timeoutMs) throws Exception {
        String error = failure.get();
        if (error != null) throw new SppDiagnosticClient.Failure(error);
        byte[] payload = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (payload != null) return payload;
        if (closed) throw new SppDiagnosticClient.Failure("CANCELLED");
        error = failure.get();
        if (error != null) throw new SppDiagnosticClient.Failure(error);
        throw new SppDiagnosticClient.Failure("BLE_READ_TIMEOUT");
    }

    @Override public void close() {
        closed = true;
        BluetoothGatt link = gatt;
        gatt = null;
        if (link != null) {
            try { link.disconnect(); } catch (Exception ignored) {}
            try { link.close(); } catch (Exception ignored) {}
        }
        incoming.offer(new byte[0]);
    }

    private static UUID uuid(JSONObject uuids, String key, UUID fallback) {
        if (uuids == null) return fallback;
        String value = uuids.optString(key, "");
        if (value.isBlank()) return fallback;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                gatt.discoverServices();
                return;
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail("BLE_DISCONNECTED");
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("BLE_DISCOVER_FAILED");
                return;
            }
            BluetoothGattService service = gatt.getService(serviceUuid);
            if (service == null) service = gatt.getService(FE95);
            if (service == null) {
                fail("BLE_SERVICE_MISSING");
                return;
            }
            BluetoothGattCharacteristic reader = service.getCharacteristic(readUuid);
            writer = service.getCharacteristic(writeUuid);
            BluetoothGattCharacteristic fitness = service.getCharacteristic(activityUuid);
            if (reader == null || writer == null) {
                fail("BLE_CHARACTERISTIC_MISSING");
                return;
            }
            if (!enableNotify(gatt, reader) || (fitness != null && !enableNotify(gatt, fitness))) {
                fail("BLE_NOTIFY_FAILED");
                return;
            }
            ready.countDown();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value == null) return;
            try {
                boolean activityChar = characteristic.getUuid().equals(activityUuid);
                byte[] payload = (activityChar ? activity : commands).accept(value);
                if (payload != null && payload.length > 0) incoming.offer(payload);
            } catch (IllegalArgumentException ignored) {
                fail("BLE_FRAME_INVALID");
            }
        }
    };

    private boolean enableNotify(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false;
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
        if (descriptor == null) return true;
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        return gatt.writeDescriptor(descriptor);
    }

    private void fail(String code) {
        failure.compareAndSet(null, code);
        ready.countDown();
    }
}
