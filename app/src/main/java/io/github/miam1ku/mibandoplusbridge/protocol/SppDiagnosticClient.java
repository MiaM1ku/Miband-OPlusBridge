// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import android.Manifest;
import android.app.KeyguardManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.format.DateFormat;
import com.google.protobuf.ByteString;
import io.github.miam1ku.mibandoplusbridge.data.AuthToken;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;
import io.github.miam1ku.mibandoplusbridge.data.BindingStore;
import io.github.miam1ku.mibandoplusbridge.data.TransportObservation;
import io.github.miam1ku.mibandoplusbridge.service.OwnershipController;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.json.JSONObject;

/** One authenticated SPP session. Live mode stays open until closed; it never confirms an activity file before that file is stored. */
public final class SppDiagnosticClient implements AutoCloseable {
    private static final AtomicReference<SppDiagnosticClient> ACTIVE = new AtomicReference<>();
    private static volatile CountDownLatch activeCompletion;
    private static final String SERVICE = "00001101-0000-1000-8000-00805f9b34fb";
    private final Context context;
    private final Consumer<String> progress;
    private final SppV2Codec.Decoder decoder = new SppV2Codec.Decoder();
    private final SppV1Codec.Decoder v1Decoder = new SppV1Codec.Decoder();
    private final ArrayDeque<SppV2Codec.Frame> pending = new ArrayDeque<>();
    private final ArrayDeque<SppV1Codec.Packet> v1Pending = new ArrayDeque<>();
    private final byte[] readBuffer = new byte[4096];
    private final byte[][] receivedPayloads = new byte[256][];
    private final int[] receivedAt = new int[256];
    private BluetoothSocket socket;
    private BleGattClient ble;
    private int framing;
    private int v1EncryptCounter = 1;
    private volatile boolean closed;
    private volatile boolean timedOut;
    private volatile String linkStop;
    private final Object writeLock = new Object();
    private InputStream input;
    private OutputStream output;
    private XiaomiSessionCrypto.Session session;
    private int sentSequence;
    private int receivedCount;
    private int frameCount;
    private boolean authenticated;
    private int peerPayloadLimit;
    private int deadlineSeconds = 30;
    private Consumer<Result> onState;
    private Consumer<LiveCommandQueue> onLiveReady;
    private Consumer<byte[]> onFileStored;
    private Consumer<XiaomiProto.Command> onLiveCommand;
    private volatile LiveCommandQueue liveCommands;
    private Result verifiedDevice;
    private boolean liveHold;
    private BandHistoryParser history;
    private String historyDeviceId;
    private io.github.miam1ku.mibandoplusbridge.data.RawFitnessFileStore rawHistory;
    private XiaomiProto.WeatherLocations observedLocations;

    public int notificationPayloadLimit() {
        return liveCommands == null || closed ? 0 : Math.max(0, peerPayloadLimit - 2);
    }

    public record Result(int batteryPercent, Integer batteryState, String firmware, String hardware) {}
    public static final class Failure extends Exception {
        public final String code;
        public Failure(String code) { super(code); this.code = code; }
    }
    private record Inputs(String address, String region, byte[] key, int capability,
            String transport, JSONObject binding, JSONObject observation) {}

    public SppDiagnosticClient(Context context, Consumer<String> progress) {
        this.context = context.getApplicationContext();
        this.progress = progress;
        Arrays.fill(receivedAt, -1);
    }

    public static boolean stopAllAndWait() {
        SppDiagnosticClient current = ACTIVE.get();
        if (current == null) return true;
        CountDownLatch finished = activeCompletion;
        if (finished == null) return false;
        current.close();
        try { return finished.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public Result run() throws Exception { return run(null, false); }

    public Result run(BandWeatherEncoder.Sample weather) throws Exception { return run(weather, false); }

    /** Stays authenticated until {@link #close()} or the RFCOMM link fails. Handshake still has a 45 second deadline. */
    public Result runLive(Consumer<Result> ready, Consumer<LiveCommandQueue> commandsReady,
            Consumer<byte[]> fileStored, Consumer<XiaomiProto.Command> commandReceived) throws Exception {
        onState = ready;
        onLiveReady = commandsReady;
        onFileStored = fileStored;
        onLiveCommand = commandReceived;
        deadlineSeconds = 45;
        try {
            return run(null, false);
        } finally {
            onState = null;
            onLiveReady = null;
            onFileStored = null;
            onLiveCommand = null;
            liveHold = false;
            deadlineSeconds = 30;
        }
    }

    public Result run(BandWeatherEncoder.Sample weather, boolean inspectCities) throws Exception {
        if (weather != null) BandWeatherEncoder.validateForecast(weather);
        OwnershipController.beginNativeSession();
        try {
            return runLocked(weather, inspectCities);
        } finally {
            OwnershipController.endNativeSession();
        }
    }

    private Result runLocked(BandWeatherEncoder.Sample weather, boolean inspectCities) throws Exception {
        Inputs binding = loadInputs();
        var watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "OplusBandDiagnosticDeadline");
            thread.setDaemon(true);
            return thread;
        });
        var deadline = watchdog.schedule(() -> { timedOut = true; close(); }, deadlineSeconds, TimeUnit.SECONDS);
        byte[] phoneNonce = new byte[16];
        CountDownLatch finished = new CountDownLatch(1);
        if (!ACTIVE.compareAndSet(null, this)) {
            deadline.cancel(false);
            watchdog.shutdownNow();
            Arrays.fill(binding.key(), (byte) 0);
            throw new Failure("CONNECTION_ALREADY_ACTIVE");
        }
        activeCompletion = finished;
        try {
            var adapter = context.getSystemService(BluetoothManager.class).getAdapter();
            if (adapter == null || !adapter.isEnabled()) throw new Failure("BLUETOOTH_DISABLED");
            BluetoothDevice device = adapter.getRemoteDevice(binding.address());
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) throw new Failure("OFFICIAL_PAIRING_REQUIRED");
            progress.accept("CONNECTING");
            if ("GATT".equals(binding.transport())) {
                framing = 2;
                peerPayloadLimit = 512;
                BleGattClient client = new BleGattClient(context, device, binding.binding());
                synchronized (this) {
                    if (closed) throw new Failure("CANCELLED");
                    ble = client;
                }
                client.connect();
                SessionLog.line(context, "version framing=2 transport=GATT sdk="
                        + safe(binding.observation().optString("versionName"))
                        + " queue=" + safe(binding.observation().optString("queueClass")));
            } else {
                BluetoothSocket created = device.createRfcommSocketToServiceRecord(UUID.fromString(SERVICE));
                synchronized (this) {
                    if (closed) { created.close(); throw new Failure("CANCELLED"); }
                    socket = created;
                }
                created.connect();
                input = created.getInputStream();
                output = created.getOutputStream();
                output.write(SppNegotiation.versionRequest(0));
                SppV1Codec.Packet version;
                try {
                    version = readV1Packet();
                } catch (IllegalArgumentException unsupported) {
                    throw new Failure("PROTOCOL_VERSION_UNSUPPORTED");
                }
                if (version.channel() != SppV1Codec.CHANNEL_VERSION || version.payload().length < 1) {
                    throw new Failure("PROTOCOL_VERSION_UNSUPPORTED");
                }
                if (TransportObservation.useV1Framing(binding.observation(), version.payload()[0] & 0xff)) {
                    framing = 1;
                    peerPayloadLimit = 2048;
                } else {
                    framing = 0;
                    sendFrame(SppNegotiation.startSessionRequest());
                    SppV2Codec.Frame configuration = readFrame();
                    var config = SppV2Codec.parseSessionConfig(configuration);
                    if (config.opcode() != 2 || config.version() == null || config.version().length != 3
                            || config.maxPacketSize() == null || config.maxPacketSize() < 128
                            || config.txWindow() == null || config.txWindow() < 1) {
                        throw new Failure("SESSION_CONFIGURATION_UNSUPPORTED");
                    }
                    peerPayloadLimit = config.maxPacketSize() - SppV2Codec.HEADER_LENGTH;
                }
                SessionLog.line(context, "version framing=" + framing
                        + " major=" + (version.payload()[0] & 0xff)
                        + " bytes=" + version.payload().length
                        + " queue=" + safe(binding.observation().optString("queueClass"))
                        + " sdk=" + safe(binding.observation().optString("versionName")));
            }
            new SecureRandom().nextBytes(phoneNonce);
            progress.accept("AUTHENTICATING");
            sendCommand(XiaomiProto.Command.newBuilder().setType(1).setSubtype(26)
                    .setAuth(XiaomiProto.Auth.newBuilder().setPhoneNonce(
                            XiaomiProto.PhoneNonce.newBuilder().setNonce(ByteString.copyFrom(phoneNonce)))).build());
            var challenge = awaitCommand(1, 26);
            if (!challenge.hasAuth() || !challenge.getAuth().hasWatchNonce()
                    || challenge.hasStatus() || challenge.getAuth().hasStatus()) {
                throw new Failure("AUTH_CHALLENGE_REJECTED");
            }
            var watchNonce = challenge.getAuth().getWatchNonce();
            session = XiaomiSessionCrypto.derive(binding.key(), phoneNonce, watchNonce.getNonce().toByteArray());
            Arrays.fill(binding.key(), (byte) 0);
            if (!session.verifyWatchProof(watchNonce.getHmac().toByteArray())) throw new Failure("CREDENTIAL_REFRESH_REQUIRED");
            var phoneInfo = XiaomiProto.AuthDeviceInfo.newBuilder().setUnknown1(0)
                    .setPhoneApiLevel((float) Build.VERSION.SDK_INT).setPhoneName(Build.MODEL)
                    .setUnknown3(binding.capability()).setRegion(binding.region()).build();
            var confirmation = XiaomiProto.AuthStep3.newBuilder()
                    .setEncryptedNonces(ByteString.copyFrom(session.phoneProof()))
                    .setEncryptedDeviceInfo(ByteString.copyFrom(session.encryptPhoneInfo(phoneInfo.toByteArray())));
            // The actual captured V2 command has no Auth.userId field.
            sendCommand(XiaomiProto.Command.newBuilder().setType(1).setSubtype(27)
                    .setAuth(XiaomiProto.Auth.newBuilder().setAuthStep3(confirmation)).build());
            var confirmed = awaitCommand(1, 27);
            if (!confirmed.hasAuth() || !confirmed.getAuth().hasAuthStep4()
                    || confirmed.getAuth().getAuthStep4().getUnknown1() != 1
                    || confirmed.hasStatus() || confirmed.getAuth().hasStatus()) {
                throw new Failure("DEVICE_AUTHENTICATION_REJECTED");
            }
            session.activate();
            authenticated = true;
            progress.accept("DEVICE_CONFIRMED_AUTHENTICATION");
            sendCommand(XiaomiProto.Command.newBuilder().setType(2).setSubtype(2).build());
            var infoResponse = awaitCommand(2, 2);
            if (!infoResponse.hasSystem() || !infoResponse.getSystem().hasDeviceInfo()) throw new Failure("DEVICE_INFO_MISSING");
            var info = infoResponse.getSystem().getDeviceInfo();
            if (info.getModel().isBlank() || info.getFirmware().isBlank()) {
                throw new Failure("FIRMWARE_OR_MODEL_UNSUPPORTED");
            }
            progress.accept("DEVICE_INFO_MATCHED");
            sendCommand(XiaomiProto.Command.newBuilder().setType(2).setSubtype(1).build());
            var batteryResponse = awaitCommand(2, 1);
            if (!batteryResponse.hasSystem() || !batteryResponse.getSystem().hasPower()
                    || !batteryResponse.getSystem().getPower().hasBattery()) throw new Failure("BATTERY_MISSING");
            var battery = batteryResponse.getSystem().getPower().getBattery();
            if (!battery.hasLevel() || battery.getLevel() < 0 || battery.getLevel() > 100) throw new Failure("BATTERY_VALUE_INVALID");
            sendObservedClock();
            if (weather != null) {
                if (inspectCities) {
                    inspectWeatherCities(weather);
                    progress.accept("WEATHER_CITIES_READY");
                } else {
                    progress.accept("WEATHER_CITY_CHECK");
                    sendWeather(weather);
                    progress.accept("WEATHER_TRANSPORT_ACKED");
                }
            }
            Result result = new Result(battery.getLevel(), battery.hasState() ? battery.getState() : null,
                    info.getFirmware(), info.getModel());
            verifiedDevice = result;
            if (onState != null) {
                onState.accept(result);
                prepareHistory();
                liveHold = true;
                long timeout = 10_000L;
                liveCommands = new LiveCommandQueue(sentSequence, timeout, this::writeLiveCommand);
                onLiveReady.accept(liveCommands);
                progress.accept("LIVE_SESSION_OPEN");
                deadline.cancel(false);
                holdOpen();
                progress.accept("LIVE_SESSION_CLOSED");
            }
            return result;
        } catch (Exception error) {
            SessionLog.line(context, "session failed " + (error instanceof Failure typed ? typed.code
                    : error.getClass().getSimpleName()));
            if (timedOut) throw new Failure("DIAGNOSTIC_TIMEOUT");
            throw error;
        } finally {
            deadline.cancel(false);
            watchdog.shutdownNow();
            close();
            if (session != null) session.close();
            Arrays.fill(binding.key(), (byte) 0);
            Arrays.fill(phoneNonce, (byte) 0);
            decoder.reset();
            v1Decoder.reset();
            for (byte[] payload : receivedPayloads) {
                if (payload != null) Arrays.fill(payload, (byte) 0);
            }
            observedLocations = null;
            ACTIVE.compareAndSet(this, null);
            finished.countDown();
        }
    }

    private Inputs loadInputs() throws Exception {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) throw new Failure("NEARBY_PERMISSION_REQUIRED");
        if (context.getSystemService(KeyguardManager.class).isDeviceLocked()) throw new Failure("USER_LOCKED");
        if (!new OwnershipController(context).nativeReady()) throw new Failure("NATIVE_OWNERSHIP_REQUIRED");
        JSONObject binding = new BindingStore(context).read();
        if (binding == null) throw new Failure("UNPROVISIONED");
        JSONObject observation = TransportObservation.read(context);
        if (!TransportObservation.supportsLive(observation, binding.optString("model"))) {
            throw new Failure("OBSERVED_PROFILE_REQUIRED");
        }
        if (observation.optBoolean("authOobPresent") || observation.optBoolean("authAppDeviceIdPresent")
                || !binding.optString("oob", "").isEmpty()) throw new Failure("OOB_BRANCH_UNSUPPORTED");
        String address = binding.getString("address");
        String region = binding.getString("region");
        if (!address.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}") || region.isBlank()
                || binding.getString("userId").isBlank()) throw new Failure("BINDING_INCOMPLETE");
        String token = binding.getString("token");
        if (!AuthToken.hex32(token)) throw new Failure("TOKEN_ENCODING_UNSUPPORTED");
        byte[] key = new byte[16];
        for (int i = 0; i < token.length(); i += 2) {
            key[i / 2] = (byte) Integer.parseInt(token.substring(i, i + 2), 16);
        }
        return new Inputs(address, region, key, observation.getInt("appCapability"),
                observation.optString("transport"), binding, observation);
    }

    private byte[] readExactly(int size) throws Exception {
        byte[] bytes = new byte[size];
        for (int offset = 0; offset < size;) {
            int count = input.read(bytes, offset, size - offset);
            if (count < 0) throw new EOFException();
            if (count == 0) throw new Failure("EMPTY_SOCKET_READ");
            offset += count;
        }
        return bytes;
    }

    private SppV2Codec.Frame readFrame() throws Exception {
        while (pending.isEmpty()) {
            int count = input.read(readBuffer);
            if (count < 0) throw new EOFException();
            if (count == 0) throw new Failure("EMPTY_SOCKET_READ");
            pending.addAll(decoder.feed(readBuffer, 0, count));
        }
        if (!liveHold && ++frameCount > 512) throw new Failure("UNEXPECTED_FRAME_VOLUME");
        return pending.removeFirst();
    }

    private void sendFrame(SppV2Codec.Frame frame) throws Exception {
        synchronized (writeLock) {
            output.write(SppV2Codec.encode(frame));
            output.flush();
        }
    }

    private XiaomiProto.Command nextCommand() throws Exception {
        if (framing == 0) {
            while (true) {
                SppV2Codec.Frame frame = readFrame();
                if (frame.type() == SppV2Codec.TYPE_ACK) {
                    if (liveCommands != null) liveCommands.onAck(frame.sequence());
                    continue;
                }
                return decodeAndAck(frame);
            }
        }
        if (framing == 2) return decodeBle(ble.take(liveHold ? 30_000L : 15_000L));
        return decodeV1(readV1Packet());
    }

    private SppV1Codec.Packet readV1Packet() throws Exception {
        while (v1Pending.isEmpty()) {
            int count = input.read(readBuffer);
            if (count < 0) throw new EOFException();
            if (count == 0) throw new Failure("EMPTY_SOCKET_READ");
            v1Pending.addAll(v1Decoder.feed(readBuffer, 0, count));
        }
        if (!liveHold && ++frameCount > 512) throw new Failure("UNEXPECTED_FRAME_VOLUME");
        return v1Pending.removeFirst();
    }

    private XiaomiProto.Command decodeV1(SppV1Codec.Packet packet) throws Exception {
        if (packet.channel() == SppV1Codec.CHANNEL_VERSION) return null;
        byte[] payload = packet.payload();
        if (packet.channel() == SppV1Codec.CHANNEL_FITNESS) {
            acceptV1Activity(payload);
            return null;
        }
        if (packet.channel() != SppV1Codec.CHANNEL_PROTO_RX && packet.channel() != SppV1Codec.CHANNEL_PROTO_TX) {
            return null;
        }
        if (packet.dataType() == SppV1Codec.DATA_ENCRYPTED) {
            try {
                payload = session.decryptV1(payload);
            } catch (RuntimeException rejected) {
                SessionLog.line(context, "rx decrypt failed channel=" + packet.channel()
                        + " dataType=" + packet.dataType() + " len=" + packet.payload().length);
                throw new Failure("V1_DECRYPT_FAILED");
            }
        }
        return publishParsed(payload);
    }

    private XiaomiProto.Command decodeBle(byte[] payload) throws Exception {
        if (payload == null || payload.length == 0) return null;
        byte[] plain = payload;
        try {
            if (authenticated) plain = decryptBle(payload);
            return publishParsed(plain);
        } catch (Exception ignored) {
            acceptV1Activity(payload);
            return null;
        }
    }

    /** Watch single-frame replies put an encryption flag in front of ciphertext. Nonce counter stays 0. */
    private byte[] decryptBle(byte[] payload) throws Exception {
        try {
            return session.decryptV1(payload);
        } catch (RuntimeException first) {
            if (payload.length > 5 && payload[0] == 1) {
                try {
                    return session.decryptV1(Arrays.copyOfRange(payload, 1, payload.length));
                } catch (RuntimeException ignored) {
                    SessionLog.line(context, "rx ble decrypt failed len=" + payload.length);
                    throw new Failure("V1_DECRYPT_FAILED");
                }
            }
            SessionLog.line(context, "rx ble decrypt failed len=" + payload.length);
            throw new Failure("V1_DECRYPT_FAILED");
        }
    }

    private XiaomiProto.Command publishParsed(byte[] plaintext) throws Exception {
        var command = XiaomiProto.Command.parseFrom(plaintext);
        if (command.getType() == 10 && command.hasSubtype() && command.getSubtype() == 5
                && command.hasWeather() && command.getWeather().hasLocations()) {
            observedLocations = command.getWeather().getLocations();
        }
        publishBattery(command);
        return command;
    }

    private void acceptV1Activity(byte[] payload) throws Exception {
        if (history == null) return;
        try {
            var result = history.acceptFragment(payload);
            if (result != null && onFileStored != null) {
                rawHistory.persist(result, historyDeviceId, verifiedDevice.firmware(), System.currentTimeMillis());
                if ("PARSED".equals(result.parseStatus)) summarizeSport(result.measurements);
                onFileStored.accept(result.copyFileId());
            }
        } catch (Exception ignored) {
            if (history != null) history.reset();
        }
    }

    private void sendCommand(XiaomiProto.Command command) throws Exception {
        writeCommand(sentSequence, command);
        sentSequence = (sentSequence + 1) & 255;
    }

    private void writeLiveCommand(int sequence, XiaomiProto.Command command) throws Exception {
        try {
            if (closed) throw new Failure("CANCELLED");
            writeCommand(sequence, command);
        } catch (Exception failure) {
            linkStop = failure instanceof Failure typed ? typed.code : failure.getClass().getSimpleName();
            close();
            throw failure;
        }
    }

    private void writeCommand(int sequence, XiaomiProto.Command command) throws Exception {
        byte[] bytes = command.toByteArray();
        int plainLength = bytes.length;
        OwnershipController.beginNativeSession();
        try {
            if (!new OwnershipController(context).nativeReady()) throw new Failure("NATIVE_OWNERSHIP_LOST");
            if (framing == 2) {
                int counter = 0;
                if (authenticated) {
                    counter = v1EncryptCounter++;
                    bytes = session.encryptV1(bytes, counter);
                }
                if (bytes.length > peerPayloadLimit) throw new Failure("COMMAND_EXCEEDS_NEGOTIATED_SIZE");
                SessionLog.line(context, "tx ble type=" + command.getType() + " subtype=" + command.getSubtype()
                        + " auth=" + authenticated + " counter=" + counter
                        + " plain=" + plainLength + " cipher=" + bytes.length);
                ble.write(bytes, authenticated, counter);
                return;
            }
            if (framing == 1) {
                int dataType = SppV1Codec.DATA_PLAIN;
                int counter = 0;
                if (!authenticated && command.getType() == 1 && command.getSubtype() >= 17) {
                    dataType = SppV1Codec.DATA_AUTH;
                } else if (authenticated) {
                    dataType = SppV1Codec.DATA_ENCRYPTED;
                    counter = v1EncryptCounter++;
                    bytes = SppV1Codec.sealEncrypted(counter, session.encryptV1(bytes, counter));
                }
                if (bytes.length > peerPayloadLimit) throw new Failure("COMMAND_EXCEEDS_NEGOTIATED_SIZE");
                SessionLog.line(context, "tx spp1 type=" + command.getType() + " subtype=" + command.getSubtype()
                        + " dataType=" + dataType + " counter=" + counter
                        + " plain=" + plainLength + " wire=" + bytes.length);
                synchronized (writeLock) {
                    output.write(SppV1Codec.encode(SppV1Codec.protobuf(sequence, dataType, bytes)));
                    output.flush();
                }
                return;
            }
            if (authenticated) bytes = session.encryptV2(bytes);
            if (bytes.length + 2 > peerPayloadLimit) throw new Failure("COMMAND_EXCEEDS_NEGOTIATED_SIZE");
            SessionLog.line(context, "tx spp2 type=" + command.getType() + " subtype=" + command.getSubtype()
                    + " auth=" + authenticated + " plain=" + plainLength + " wire=" + bytes.length);
            synchronized (writeLock) {
                sendFrame(SppV2Codec.dataFrame(sequence, SppV2Codec.CHANNEL_PROTOBUF,
                        authenticated ? SppV2Codec.OPCODE_ENCRYPTED : SppV2Codec.OPCODE_PLAINTEXT, bytes));
            }
        } finally {
            OwnershipController.endNativeSession();
        }
    }

    private static String safe(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}]", "");
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }

    private XiaomiProto.Command awaitCommand(int type, int subtype) throws Exception {
        while (true) {
            XiaomiProto.Command command = nextCommand();
            if (command != null && command.getType() == type
                    && command.hasSubtype() && command.getSubtype() == subtype) return command;
        }
    }

    private XiaomiProto.Command decodeAndAck(SppV2Codec.Frame frame) throws Exception {
        if (frame.type() != SppV2Codec.TYPE_DATA) throw new Failure("UNEXPECTED_SESSION_FRAME");
        int sequence = frame.sequence();
        byte[] rawPayload = frame.payload();
        if (peerPayloadLimit <= 0 || rawPayload.length > peerPayloadLimit) {
            throw new Failure("RECEIVE_SIZE_EXCEEDS_NEGOTIATED_LIMIT");
        }
        if (sequence != (receivedCount & 255)) {
            if (receivedAt[sequence] >= 0 && receivedCount - receivedAt[sequence] <= 32
                    && Arrays.equals(rawPayload, receivedPayloads[sequence])) {
                sendFrame(new SppV2Codec.Frame(SppV2Codec.TYPE_ACK, sequence, new byte[0]));
                return null;
            }
            throw new Failure("RECEIVE_SEQUENCE_MISMATCH");
        }
        var data = SppV2Codec.parseData(frame);
        if (data.flags() != 0) throw new Failure("UNSUPPORTED_DATA_CHANNEL");
        if (data.channel() == SppV2Codec.CHANNEL_ACTIVITY) {
            acceptActivity(data, sequence, rawPayload);
            return null;
        }
        if (data.channel() != SppV2Codec.CHANNEL_PROTOBUF) {
            throw new Failure("UNSUPPORTED_DATA_CHANNEL");
        }
        int expectedOpcode = authenticated ? SppV2Codec.OPCODE_ENCRYPTED : SppV2Codec.OPCODE_PLAINTEXT;
        if (data.opcode() != expectedOpcode) throw new Failure("ENCRYPTION_STATE_MISMATCH");
        byte[] plaintext = authenticated ? session.decryptV2(data.payload()) : data.payload();
        var command = XiaomiProto.Command.parseFrom(plaintext);
        if (command.getType() == 10 && command.hasSubtype() && command.getSubtype() == 5
                && command.hasWeather() && command.getWeather().hasLocations()) {
            observedLocations = command.getWeather().getLocations();
        }
        if (receivedPayloads[sequence] != null) Arrays.fill(receivedPayloads[sequence], (byte) 0);
        receivedPayloads[sequence] = rawPayload;
        receivedAt[sequence] = receivedCount++;
        sendFrame(new SppV2Codec.Frame(SppV2Codec.TYPE_ACK, sequence, new byte[0]));
        publishBattery(command);
        return command;
    }

    private void prepareHistory() throws Exception {
        BindingStore.Identity binding = new BindingStore(context).readIdentity();
        String identity = binding.did();
        if (identity.isBlank()) identity = binding.address().replace(":", "");
        historyDeviceId = binding.deviceId();
        history = new BandHistoryParser(verifiedDevice.firmware(), historyDeviceId, identity);
        rawHistory = new io.github.miam1ku.mibandoplusbridge.data.RawFitnessFileStore(context);
    }

    private void acceptActivity(SppV2Codec.Data data, int sequence, byte[] rawPayload) throws Exception {
        if (history == null || data.opcode() != SppV2Codec.OPCODE_ENCRYPTED) {
            throw new Failure("UNSUPPORTED_DATA_CHANNEL");
        }
        byte[] plain = session.decryptV2(data.payload());
        BandHistoryParser.FileResult result = null;
        try {
            result = history.acceptFragment(plain);
            progress.accept("HISTORY_FRAGMENT_RECEIVED");
        } catch (IllegalArgumentException malformed) {
            history.reset();
            progress.accept("HISTORY_FILE_REJECTED");
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        byte[] fileId = null;
        if (result != null) {
            try {
                rawHistory.persist(result, historyDeviceId, verifiedDevice.firmware(), System.currentTimeMillis());
                fileId = result.copyFileId();
                progress.accept("HISTORY_FILE_ARCHIVED");
            } catch (Exception storageFailed) {
                progress.accept("HISTORY_STORAGE_FAILED");
            }
            if (fileId != null) {
                if ("PARSED".equals(result.parseStatus)) {
                    try { summarizeSport(result.measurements); }
                    catch (RuntimeException snapshotFailed) { progress.accept("HEALTH_SNAPSHOT_PAUSED"); }
                } else progress.accept("HISTORY_FORMAT_UNSUPPORTED");
            }
        }
        if (receivedPayloads[sequence] != null) Arrays.fill(receivedPayloads[sequence], (byte) 0);
        receivedPayloads[sequence] = rawPayload;
        receivedAt[sequence] = receivedCount++;
        sendFrame(new SppV2Codec.Frame(SppV2Codec.TYPE_ACK, sequence, new byte[0]));
        if (fileId == null) return;
        byte[] savedId = fileId;
        LiveCommandQueue commands = liveCommands;
        if (commands != null) {
            commands.send(XiaomiProto.Command.newBuilder().setType(8).setSubtype(5)
                    .setHealth(XiaomiProto.Health.newBuilder()
                            .setActivitySyncAckFileIds(ByteString.copyFrom(savedId))).build())
                    .whenComplete((ignored, error) -> {
                        if (error == null) {
                            progress.accept("HISTORY_FILE_STORED");
                            Consumer<byte[]> stored = onFileStored;
                            if (stored != null) stored.accept(savedId);
                        } else progress.accept("HISTORY_CONFIRMATION_PENDING");
                    });
        }
    }

    private void summarizeSport(java.util.List<BandHistoryParser.Measurement> measurements) {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        long dayStart = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
        long dayEnd = java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        int heart = -1;
        long heartAt = -1;
        for (BandHistoryParser.Measurement measurement : measurements) {
            if (measurement.value == null || !"heart_rate".equals(measurement.kind)
                    || measurement.startMs <= heartAt) continue;
            heart = measurement.value.intValue();
            heartAt = measurement.startMs;
        }
        var repository = new io.github.miam1ku.mibandoplusbridge.data.BandStateRepository(context);
        BandHistoryParser.DailySteps steps = BandHistoryParser.todaySteps(measurements, dayStart, dayEnd);
        if (steps != null) repository.recordDailySteps(steps.steps, steps.measuredAtMs, steps.authoritative);
        if (heartAt >= 0) repository.recordSport(-1, heart, heartAt);
    }

    private void inspectWeatherCities(BandWeatherEncoder.Sample weather) throws Exception {
        if (observedLocations == null) {
            sendCommand(XiaomiProto.Command.newBuilder().setType(10).setSubtype(5).build());
            awaitCommand(10, 5);
        }
        if (observedLocations == null) throw new Failure("WEATHER_CITY_SELECTION_REQUIRED");
        var prefs = context.getSharedPreferences("weather-city-review", 0).edit()
                .putString("sourceKey", weather.locationKey())
                .putString("sourceCity", weather.cityName())
                .putString("sourcePlace", weather.locationName());
        int index = 0;
        for (var city : observedLocations.getLocationList()) {
            if (!city.getCode().matches("weathercn:[0-9]{9}") || !city.hasName() || city.getName().isBlank()) continue;
            prefs.putString("bandCode" + index, city.getCode()).putString("bandName" + index, city.getName());
            index++;
            if (index == 4) break;
        }
        prefs.putInt("bandCount", index);
        for (int extra = index; extra < 4; extra++) prefs.remove("bandCode" + extra).remove("bandName" + extra);
        if (!prefs.commit()) throw new Failure("WEATHER_CITY_STORAGE_FAILED");
    }

    private void sendWeather(BandWeatherEncoder.Sample weather) throws Exception {
        if (observedLocations == null) {
            sendCommand(XiaomiProto.Command.newBuilder().setType(10).setSubtype(5).build());
            awaitCommand(10, 5);
        }
        BandWeatherEncoder.Sample bound;
        try {
            var association = context.getSharedPreferences("weather-city-review", Context.MODE_PRIVATE);
            bound = BandWeatherEncoder.bindExplicitCity(weather,
                    association.getString("confirmedSourceKey", ""),
                    association.getString("confirmedSourceCity", ""),
                    association.getString("confirmedSourcePlace", ""),
                    association.getString("confirmedBandCode", ""),
                    association.getString("confirmedBandName", ""), observedLocations);
        } catch (IllegalArgumentException invalid) {
            throw new Failure(invalid.getMessage());
        }
        List<XiaomiProto.Command> commands = BandWeatherEncoder.encode(bound);
        progress.accept("WEATHER_SENDING");
        // Preserve the other configured cities; 10/6 would replace the whole list.
        for (int index = 0; index < commands.size(); index++) {
            int expectedAck = sentSequence;
            sendCommand(commands.get(index));
            awaitTransportAck(expectedAck);
        }
    }

    private void sendObservedClock() throws Exception {
        int clockAck = sentSequence;
        sendCommand(BandClockCommand.at(Instant.now(), ZoneId.systemDefault(),
                DateFormat.is24HourFormat(context)));
        awaitTransportAck(clockAck);
        progress.accept("CLOCK_ACKED");
    }

    private void awaitTransportAck(int sequence) throws Exception {
        if (framing != 0) return;
        while (true) {
            SppV2Codec.Frame frame = readFrame();
            if (frame.type() == SppV2Codec.TYPE_ACK) {
                if (frame.sequence() == sequence) return;
                continue;
            }
            XiaomiProto.Command command = decodeAndAck(frame);
            if (command != null && command.getType() == 10 && command.hasStatus()
                    && command.getStatus() != 0) throw new Failure("WEATHER_DEVICE_REJECTED");
        }
    }
    private void holdOpen() throws Exception {
        liveHold = true;
        linkStop = null;
        OwnershipController.endNativeSession();
        try {
            while (!closed) {
                if (framing == 0) {
                    SppV2Codec.Frame frame = readFrame();
                    if (frame.type() == SppV2Codec.TYPE_ACK) {
                        liveCommands.onAck(frame.sequence());
                    } else {
                        XiaomiProto.Command command = decodeAndAck(frame);
                        if (command != null) {
                            if (onLiveCommand != null) onLiveCommand.accept(command);
                            liveCommands.onCommand(command);
                        }
                    }
                } else {
                    XiaomiProto.Command command = nextCommand();
                    if (command != null) {
                        if (liveCommands != null) liveCommands.onAck(sentSequence);
                        if (onLiveCommand != null) onLiveCommand.accept(command);
                        liveCommands.onCommand(command);
                    }
                }
            }
        } catch (Exception error) {
            if (linkStop != null) throw new Failure(linkStop);
            if (!closed) throw error;
        } finally {
            OwnershipController.beginNativeSession();
        }
    }


    private void publishBattery(XiaomiProto.Command command) throws Exception {
        if (!liveHold || onState == null || verifiedDevice == null || command == null || command.getType() != 2
                || !command.hasSubtype() || command.getSubtype() != 1) return;
        if (!command.hasSystem() || !command.getSystem().hasPower()
                || !command.getSystem().getPower().hasBattery()) throw new Failure("BATTERY_MISSING");
        var battery = command.getSystem().getPower().getBattery();
        if (!battery.hasLevel() || battery.getLevel() < 0 || battery.getLevel() > 100
                || !battery.hasState() || (battery.getState() != 1 && battery.getState() != 2 && battery.getState() != 3)) {
            throw new Failure("BATTERY_VALUE_INVALID");
        }
        onState.accept(new Result(battery.getLevel(), battery.getState(),
                verifiedDevice.firmware(), verifiedDevice.hardware()));
    }

    @Override public void close() {
        LiveCommandQueue commands = liveCommands;
        if (commands != null) commands.close(new IllegalStateException("SESSION_CLOSED"));
        BluetoothSocket closing;
        BleGattClient gatt;
        synchronized (this) {
            closed = true;
            closing = socket;
            socket = null;
            gatt = ble;
            ble = null;
        }
        if (closing != null) {
            try { closing.close(); } catch (Exception ignored) { /* Socket closure cannot expose credentials. */ }
        }
        if (gatt != null) gatt.close();
    }
}
