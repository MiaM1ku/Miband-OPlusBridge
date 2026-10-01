// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;

/** Official-app GATT UUIDs when present; otherwise Band 8 fe95 command characteristics. */
final class BleGattClient implements AutoCloseable {
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final UUID FE95 = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb");
    static final UUID CMD_READ = UUID.fromString("00000051-0000-1000-8000-00805f9b34fb");
    static final UUID CMD_WRITE = UUID.fromString("00000052-0000-1000-8000-00805f9b34fb");
    static final UUID ACTIVITY = UUID.fromString("00000053-0000-1000-8000-00805f9b34fb");
    private static final int ATT_PAYLOAD = 20;
    private final Context context;
    private final BluetoothDevice device;
    private final UUID serviceUuid;
    private final UUID readUuid;
    private final UUID writeUuid;
    private final UUID activityUuid;
    private final BlockingQueue<byte[]> incoming = new ArrayBlockingQueue<>(32);
    private final BlockingQueue<byte[]> transportAcks = new ArrayBlockingQueue<>(8);
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final CountDownLatch ready = new CountDownLatch(1);
    private final AtomicBoolean subscribeStarted = new AtomicBoolean();
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic writer;
    private volatile boolean writerNotifies;
    private volatile boolean closed;
    private volatile boolean servicesReady;
    private volatile boolean mtuSeen;
    private volatile int maxWrite = ATT_PAYLOAD;
    private final BleV1Codec.Reassembler commands = new BleV1Codec.Reassembler();
    private final BleV1Codec.Reassembler activity = new BleV1Codec.Reassembler();
    private final Object writeLock = new Object();
    private final BluetoothGattCharacteristic[] subscribe = new BluetoothGattCharacteristic[3];
    private BleNotifyQueue notifies;
    private byte[] previousNotification;
    private long previousAt;

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
        if (target == null || gatt == null) throw new SppDiagnosticClient.Failure("BLE_DISCONNECTED");
        int budget = writeBudget();
        List<byte[]> frames = BleV1Codec.encodeOutgoing(payload, budget, encrypted, counter);
        SessionLog.line(context, "ble mtu=" + budget + " frames=" + frames.size()
                + " wire=" + frames.get(0).length);
        if (frames.size() == 1) {
            writeRaw(target, frames.get(0));
            return;
        }
        if (!writerNotifies) throw new SppDiagnosticClient.Failure("BLE_CHUNK_ACK_UNSUPPORTED");
        transportAcks.clear();
        writeRaw(target, frames.get(0));
        if (!BleV1Codec.chunkAck(awaitChunkAck(1), 1)) {
            throw new SppDiagnosticClient.Failure("BLE_CHUNK_ACK_TIMEOUT");
        }
        for (int i = 1; i < frames.size(); i++) writeRaw(target, frames.get(i));
        if (!BleV1Codec.chunkAck(awaitChunkAck(0), 0)) {
            throw new SppDiagnosticClient.Failure("BLE_CHUNK_ACK_TIMEOUT");
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
        transportAcks.offer(new byte[0]);
    }

    private int writeBudget() {
        int value = maxWrite;
        return value < 8 ? ATT_PAYLOAD : value;
    }

    private byte[] awaitChunkAck(int subtype) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            String error = failure.get();
            if (error != null) throw new SppDiagnosticClient.Failure(error);
            if (closed) throw new SppDiagnosticClient.Failure("CANCELLED");
            long left = deadline - System.nanoTime();
            if (left <= 0) throw new SppDiagnosticClient.Failure("BLE_CHUNK_ACK_TIMEOUT");
            byte[] value = transportAcks.poll(left, TimeUnit.NANOSECONDS);
            if (value != null && BleV1Codec.chunkAck(value, subtype)) return value;
        }
    }

    private void writeRaw(BluetoothGattCharacteristic target, byte[] frame) throws Exception {
        synchronized (writeLock) {
            BluetoothGatt link = gatt;
            if (target == null || link == null || closed) {
                throw new SppDiagnosticClient.Failure("BLE_DISCONNECTED");
            }
            int props = target.getProperties();
            int type = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            target.setWriteType(type);
            target.setValue(frame);
            if (!link.writeCharacteristic(target)) throw new SppDiagnosticClient.Failure("BLE_WRITE_FAILED");
        }
    }

    private void ack(BluetoothGattCharacteristic source, byte[] frame) {
        try {
            writeRaw(source, frame);
        } catch (Exception error) {
            SessionLog.line(context, "ble ack failed " + error.getClass().getSimpleName());
        }
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

    private static boolean notifies(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return false;
        int props = characteristic.getProperties();
        return (props & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
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
            writerNotifies = notifies(writer) && !writer.getUuid().equals(reader.getUuid());
            int count = 0;
            if (writerNotifies) subscribe[count++] = writer;
            subscribe[count++] = reader;
            if (fitness != null && !fitness.getUuid().equals(reader.getUuid())) subscribe[count++] = fitness;
            notifies = new BleNotifyQueue(count);
            servicesReady = true;
            if (mtuSeen || !gatt.requestMtu(512)) {
                if (!mtuSeen) SessionLog.line(context, "mtu request rejected");
                startSubscribe(gatt);
            } else {
                BluetoothGatt link = gatt;
                Thread fallback = new Thread(() -> {
                    try {
                        if (!ready.await(3, TimeUnit.SECONDS) && !closed) startSubscribe(link);
                    } catch (InterruptedException ignored) { }
                }, "ble-mtu-fallback");
                fallback.setDaemon(true);
                fallback.start();
            }
        }

        @Override public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) {
                maxWrite = mtu - 3;
                SessionLog.line(context, "mtu=" + mtu);
            } else {
                SessionLog.line(context, "mtu status=" + status + " value=" + mtu);
            }
            mtuSeen = true;
            if (servicesReady) startSubscribe(gatt);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic, byte[] value) {
            onValue(characteristic, value);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic) {
            onValue(characteristic, characteristic.getValue());
        }

        @Override public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (closed || notifies == null || descriptor == null || !CCCD.equals(descriptor.getUuid())) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                SessionLog.line(context, "cccd status=" + status);
                fail("BLE_NOTIFY_FAILED");
                return;
            }
            if (!notifies.confirmed()) return;
            subscribeNext(gatt);
        }
    };

    private void onValue(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (closed || characteristic == null || value == null || value.length == 0) return;
        long now = System.nanoTime();
        if (previousNotification != null && now - previousAt < 5_000_000L
                && Arrays.equals(previousNotification, value)) return;
        previousNotification = Arrays.copyOf(value, value.length);
        previousAt = now;
        UUID uuid = characteristic.getUuid();
        try {
            if (writeUuid.equals(uuid)) {
                transportAcks.offer(value);
                return;
            }
            boolean activityChar = activityUuid.equals(uuid);
            BleV1Codec.Reassembler reassembler = activityChar ? activity : commands;
            if (BleV1Codec.control(value, BleV1Codec.TYPE_CHUNK_START)) {
                ack(characteristic, BleV1Codec.CHUNK_START_ACK);
            }
            byte[] payload = reassembler.accept(value);
            if (payload != null && payload.length > 0) {
                ack(characteristic, BleV1Codec.control(value, BleV1Codec.TYPE_SINGLE)
                        ? BleV1Codec.PAYLOAD_ACK : BleV1Codec.CHUNK_END_ACK);
                SessionLog.line(context, "rx ble bytes=" + payload.length
                        + (activityChar ? " activity" : ""));
                incoming.offer(payload);
            }
        } catch (IllegalArgumentException ignored) {
            fail("BLE_FRAME_INVALID");
        }
    }

    private void startSubscribe(BluetoothGatt link) {
        if (!subscribeStarted.compareAndSet(false, true)) return;
        subscribeNext(link);
    }

    private void subscribeNext(BluetoothGatt link) {
        BleNotifyQueue queue = notifies;
        if (closed || queue == null) {
            if (!closed) fail("BLE_NOTIFY_FAILED");
            return;
        }
        int index = queue.start();
        if (index < 0) {
            if (queue.complete()) {
                link.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);
                ready.countDown();
            }
            return;
        }
        BluetoothGattCharacteristic characteristic = subscribe[index];
        if (characteristic == null || !link.setCharacteristicNotification(characteristic, true)) {
            fail("BLE_NOTIFY_FAILED");
            return;
        }
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
        if (descriptor == null) {
            if (!queue.confirmed()) {
                fail("BLE_NOTIFY_FAILED");
                return;
            }
            subscribeNext(link);
            return;
        }
        int props = characteristic.getProperties();
        byte[] enable = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        int queued = link.writeDescriptor(descriptor, enable);
        if (queued != BluetoothStatusCodes.SUCCESS) {
            SessionLog.line(context, "cccd write rejected status=" + queued);
            fail("BLE_NOTIFY_FAILED");
        }
    }

    private void fail(String code) {
        failure.compareAndSet(null, code);
        ready.countDown();
    }
}
