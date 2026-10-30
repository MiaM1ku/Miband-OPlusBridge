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
import io.github.miam1ku.mibandoplusbridge.data.MiProductImage;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.json.JSONObject;

/** One authenticated SPP session. Live mode stays open until closed. An activity file is confirmed after it is stored. */
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
    /** The on-wire header is two bytes. The CCM nonce includes this counter and the session key, so counting from 1 again after 0xffff repeats a nonce. */
    private volatile boolean closed;
    private volatile boolean timedOut;
    private volatile String linkStop;
    private final Object writeLock = new Object();
    private InputStream input;
    private OutputStream output;
    private XiaomiSessionCrypto.Session session;
    private int sentSequence;
    private final SppV2Codec.ReceiveCursor receive = new SppV2Codec.ReceiveCursor();
    private int frameCount;
    private boolean authenticated;
    /** This session opened an RFCOMM socket without the system bond. See {@link #run}. */
    private boolean insecureAttempt;
    private int peerPayloadLimit;
    private int deadlineSeconds = 30;
    private Consumer<Result> onState;
    private Consumer<LiveCommandQueue> onLiveReady;
    private Consumer<byte[]> onFileStored;
    private Consumer<XiaomiProto.Command> onLiveCommand;
    private Consumer<java.util.List<BandHistoryParser.Measurement>> onSleepFile;
    private volatile LiveCommandQueue liveCommands;
    private Result verifiedDevice;
    private boolean liveHold;
    private final ArrayDeque<byte[]> heldActivity = new ArrayDeque<>();
    private BandHistoryParser history;
    private String historyDeviceId;
    private io.github.miam1ku.mibandoplusbridge.data.RawFitnessFileStore rawHistory;
    private XiaomiProto.WeatherLocations observedLocations;
    private final IdentityHashMap<XiaomiProto.Command, byte[]> iconChunks = new IdentityHashMap<>();

    public int notificationPayloadLimit() {
        return liveCommands == null || closed ? 0 : Math.max(0, peerPayloadLimit - 2);
    }

    /** One data-channel part. The queue owns the sequence so it stays aligned with commands. */
    public CompletionStage<Void> sendDataChunk(byte[] chunk) {
        LiveCommandQueue queue = liveCommands;
        if (queue == null || closed || chunk == null || chunk.length == 0) {
            return java.util.concurrent.CompletableFuture.failedFuture(new Failure("ICON_UPLOAD_UNAVAILABLE"));
        }
        XiaomiProto.Command token = XiaomiProto.Command.newBuilder().setType(22).setSubtype(1).build();
        synchronized (iconChunks) { iconChunks.put(token, chunk.clone()); }
        return queue.send(token).whenComplete((ignored, error) -> {
            synchronized (iconChunks) { iconChunks.remove(token); }
        });
    }

    public record Result(int batteryPercent, Integer batteryState, String firmware, String hardware, String sku) {}
    /** RFCOMM connect budget. A band in range answers in a few seconds. */
    private static final long CONNECT_TIMEOUT_MS = 20_000L;

    public static final class Failure extends Exception {
        public final String code;
        public Failure(String code) { super(code); this.code = code; }
    }
    private record Inputs(String address, String region, byte[] key, int capability,
            String transport, JSONObject binding, JSONObject observation) {}

    /**
     * Next V1 counter for this session. 0xffff is the last frame: another frame would reuse a CCM
     * nonce under the same session key. {@code V1_COUNTER_EXHAUSTED} closes the link, and the next
     * connection authenticates again with the counter back at 1.
     */
    int takeV1Counter() throws Failure {
        if (v1EncryptCounter < 1 || v1EncryptCounter > 0xffff) throw new Failure("V1_COUNTER_EXHAUSTED");
        return v1EncryptCounter++;
    }

    void armV1Counter(int next) { v1EncryptCounter = next; }

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
            onSleepFile = null;
            liveHold = false;
            deadlineSeconds = 30;
        }
    }

    public void setSleepFiles(Consumer<java.util.List<BandHistoryParser.Measurement>> listener) {
        onSleepFile = listener;
    }

    private Result run(BandWeatherEncoder.Sample weather, boolean inspectCities) throws Exception {
        if (weather != null) BandWeatherEncoder.validateForecast(weather);
        OwnershipController.beginNativeSession();
        try {
            return runLocked(weather, inspectCities);
        } catch (Failure failure) {
            // An insecure link that dies before the handshake completes means the band wants
            // an encrypted link; repairing the system bond is the fix, not another socket.
            if (insecureAttempt && !authenticated && !"OFFICIAL_PAIRING_REQUIRED".equals(failure.code)
                    && !"CANCELLED".equals(failure.code) && !"BLUETOOTH_DISABLED".equals(failure.code)) {
                SessionLog.line(context, "insecure rfcomm died before auth " + failure.code);
                throw new Failure("OFFICIAL_PAIRING_REQUIRED");
            }
            throw failure;
        } finally {
            insecureAttempt = false;
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
            // SPP opens a secure RFCOMM socket and needs the system bond. GATT auth is the
            // captured key; ColorOS drops the BLE bond after 「小米运动健康」(Mi Fitness) is frozen.
            int bond = device.getBondState();
            boolean gatt = "GATT".equals(binding.transport());
            // A missing system bond must not cost the user a watch confirm prompt every time
            // ColorOS drops it. Session auth is the imported binding key at the application
            // layer, so the RFCOMM link itself can run insecure and pair exactly never.
            // If the band refuses the insecure socket, the failure maps back to the pairing
            // path and the service decides whether to ask for a bond.
            boolean insecure = !gatt && bond != BluetoothDevice.BOND_BONDED;
            if (insecure) {
                insecureAttempt = true;
                SessionLog.line(context, "bond missing, trying insecure rfcomm state=" + bond);
            }
            if (gatt && bond != BluetoothDevice.BOND_BONDED) {
                SessionLog.line(context, "gatt without system bond state=" + bond);
            }
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
                BluetoothSocket created = insecure
                        ? device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(SERVICE))
                        : device.createRfcommSocketToServiceRecord(UUID.fromString(SERVICE));
                synchronized (this) {
                    if (closed) { created.close(); throw new Failure("CANCELLED"); }
                    socket = created;
                }
                try {
                    connectWithTimeout(created);
                } catch (Exception refused) {
                    if (insecure) {
                        // The band wants an encrypted link after all. Let the service repair
                        // the system bond instead of retrying an insecure socket forever.
                        SessionLog.line(context, "insecure rfcomm refused " + refused);
                        throw new Failure("OFFICIAL_PAIRING_REQUIRED");
                    }
                    throw refused;
                }
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
                    .setUnknown3(io.github.miam1ku.mibandoplusbridge.data.TransportObservation
                            .withZenRuleSync(binding.capability())).setRegion(binding.region()).build();
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
                    info.getFirmware(), info.getModel(), MiProductImage.sku(info.getSerialNumber()));
            verifiedDevice = result;
            if (onState != null) {
                onState.accept(result);
                prepareHistory();
                liveHold = true;
                long timeout = 10_000L;
                liveCommands = new LiveCommandQueue(sentSequence, timeout, this::writeLiveCommand);
                flushHeldActivity();
                onLiveReady.accept(liveCommands);
                progress.accept("LIVE_SESSION_OPEN");
                deadline.cancel(false);
                holdOpen();
                progress.accept("LIVE_SESSION_CLOSED");
            }
            return result;
        } catch (Exception error) {
            SessionLog.line(context, "session failed " + failureText(error) + bondNow(binding));
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
            byte[] held;
            while ((held = heldActivity.poll()) != null) Arrays.fill(held, (byte) 0);
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
        JSONObject observation = TransportObservation.liveProfile(context, binding.optString("model"));
        if (!TransportObservation.supportsLive(observation, binding.optString("model"))) {
            throw new Failure("OBSERVED_PROFILE_REQUIRED");
        }
        if (observation.optBoolean("authOobPresent") || observation.optBoolean("authAppDeviceIdPresent")
                || !binding.optString("oob", "").isEmpty()) throw new Failure("OOB_BRANCH_UNSUPPORTED");
        String problem = io.github.miam1ku.mibandoplusbridge.data.BindingShape.reject(binding);
        if (problem != null) throw new Failure(problem);
        String address = binding.getString("address");
        String region = binding.getString("region");
        String token = binding.getString("token");
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
                if (frame.type() == SppV2Codec.TYPE_NAK) {
                    SessionLog.line(context, "rx spp2 nak seq=" + frame.sequence());
                    continue;
                }
                return decodeAndAck(frame);
            }
        }
        if (framing == 2) return readBle();
        return decodeV1(readV1Packet());
    }

    /**
     * m66nfc 1.8.124 keeps a live BLE session through silence. The firmware logs
     * an explicit "miwear app disconnect" packet, or a link supervision loss, which
     * surfaces here as BLE_DISCONNECTED. ios_keep_alive_adv only restarts advertising
     * while disconnected. A 30s read limit was tearing down every idle session.
     */
    private XiaomiProto.Command readBle() throws Exception {
        while (true) {
            if (timedOut) throw new Failure("DIAGNOSTIC_TIMEOUT");
            // close() clears the field. The handshake watchdog does that from another thread.
            BleGattClient link = ble;
            if (link == null) throw new Failure(closed ? "CANCELLED" : "BLE_DISCONNECTED");
            try {
                return decodeBle(link.take(liveHold ? 30_000L : 15_000L));
            } catch (Failure failure) {
                if (timedOut) throw new Failure("DIAGNOSTIC_TIMEOUT");
                if (!liveHold || closed || !"BLE_READ_TIMEOUT".equals(failure.code)) throw failure;
            }
        }
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
            byte[] fitness = payload;
            if (packet.dataType() == SppV1Codec.DATA_ENCRYPTED) {
                try {
                    fitness = session.decryptV1(payload);
                } catch (RuntimeException rejected) {
                    SessionLog.line(context, "rx spp1 fitness decrypt failed len=" + payload.length);
                    throw new Failure("V1_DECRYPT_FAILED");
                }
            }
            acceptV1Activity(fitness);
            if (fitness != payload) Arrays.fill(fitness, (byte) 0);
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

    private XiaomiProto.Command decodeBle(BleGattClient.Rx packet) throws Exception {
        if (packet == null || packet.payload == null || packet.payload.length == 0) return null;
        byte[] payload = packet.payload;
        if (packet.activity) {
            byte[] plain = payload;
            try {
                if (authenticated) plain = decryptBle(payload);
                acceptV1Activity(plain);
            } catch (Exception failed) {
                SessionLog.line(context, "rx ble activity decrypt failed len=" + payload.length);
            } finally {
                if (plain != payload) Arrays.fill(plain, (byte) 0);
            }
            return null;
        }
        byte[] plain = payload;
        try {
            if (authenticated) plain = decryptBle(payload);
            return publishParsed(plain);
        } catch (Exception failed) {
            SessionLog.line(context, "rx ble command rejected len=" + payload.length
                    + " " + failed.getClass().getSimpleName());
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

    private void acceptV1Activity(byte[] payload) {
        if (payload == null) return;
        // The fitness characteristic is notified as soon as the link is up, which is before
        // device info has created the parser and before file confirms can be sent. The BLE
        // payload ACK is already out, so dropping the bytes here makes the band wait for a
        // type-8 confirm that never comes and then push the file again on the next link.
        if (history == null || liveCommands == null) {
            if (heldActivity.size() >= 32) {
                SessionLog.line(context, "rx activity held dropped len=" + payload.length);
                return;
            }
            heldActivity.add(Arrays.copyOf(payload, payload.length));
            SessionLog.line(context, "rx activity held len=" + payload.length);
            return;
        }
        BandHistoryParser.FileResult result;
        try {
            result = history.acceptFragment(payload);
            progress.accept("HISTORY_FRAGMENT_RECEIVED");
        } catch (IllegalArgumentException malformed) {
            history.reset();
            progress.accept("HISTORY_FILE_REJECTED");
            SessionLog.line(context, "rx spp1 activity rejected len=" + payload.length
                    + " " + malformed.getMessage());
            return;
        }
        if (result != null) {
            byte[] fileId = archiveActivity(result);
            if (fileId != null) ackActivity(fileId);
        }
    }

    private void flushHeldActivity() {
        byte[] next;
        while ((next = heldActivity.poll()) != null) {
            try {
                acceptV1Activity(next);
            } finally {
                Arrays.fill(next, (byte) 0);
            }
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
            // SPP V1 and BLE have no transport ACK. Gadgetbridge completes the write
            // itself; request() still waits for the matching protobuf response.
            // sentSequence is the handshake cursor and is not advanced by live writes.
            if (framing != 0 && liveCommands != null) liveCommands.onAck(sequence);
        } catch (Exception failure) {
            // A missed icon chunk ACK must not drop the link. The radio is still delivering
            // commands during that wait; closing here is the Band 8 notification disconnect.
            if (failure instanceof Failure typed && "ICON_CHUNK_TIMEOUT".equals(typed.code)) throw failure;
            linkStop = failure instanceof Failure typed ? typed.code : failure.getClass().getSimpleName();
            SessionLog.line(context, "link stop " + linkStop);
            close();
            throw failure;
        }
    }

    private void writeCommand(int sequence, XiaomiProto.Command command) throws Exception {
        byte[] chunk;
        synchronized (iconChunks) { chunk = iconChunks.remove(command); }
        if (chunk != null) {
            try {
                writeIconChunk(sequence, chunk);
            } catch (Failure failure) {
                if ("BLE_CHUNK_ACK_TIMEOUT".equals(failure.code)) throw new Failure("ICON_CHUNK_TIMEOUT");
                throw failure;
            }
            return;
        }
        byte[] bytes = command.toByteArray();
        int plainLength = bytes.length;
        OwnershipController.beginNativeSession();
        try {
            if (!new OwnershipController(context).nativeReady()) throw new Failure("NATIVE_OWNERSHIP_LOST");
            if (framing == 2) {
                int counter = 0;
                if (authenticated) {
                    counter = takeV1Counter();
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
                    counter = takeV1Counter();
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

    /** SPP data channel is plaintext. BLE reuses the command characteristic framing on the upload characteristic. */
    private void writeIconChunk(int sequence, byte[] chunk) throws Exception {
        OwnershipController.beginNativeSession();
        try {
            if (!new OwnershipController(context).nativeReady()) throw new Failure("NATIVE_OWNERSHIP_LOST");
            if (framing == 2) {
                int counter = 0;
                byte[] body = chunk;
                if (authenticated) {
                    counter = takeV1Counter();
                    body = session.encryptV1(chunk, counter);
                }
                SessionLog.line(context, "tx ble icon part bytes=" + chunk.length + " counter=" + counter);
                ble.writeUpload(body, authenticated, counter);
                return;
            }
            if (chunk.length + 2 > peerPayloadLimit) throw new Failure("ICON_CHUNK");
            if (framing == 1) {
                SessionLog.line(context, "tx spp1 icon part bytes=" + chunk.length);
                synchronized (writeLock) {
                    output.write(SppV1Codec.encode(new SppV1Codec.Packet(
                            SppV1Codec.CHANNEL_MASS, true, false, SppV1Codec.OPCODE_SEND,
                            sequence, SppV1Codec.DATA_PLAIN, chunk)));
                    output.flush();
                }
                return;
            }
            SessionLog.line(context, "tx spp2 icon part bytes=" + chunk.length);
            sendFrame(SppV2Codec.dataFrame(sequence, SppV2Codec.CHANNEL_DATA,
                    SppV2Codec.OPCODE_PLAINTEXT, chunk));
        } finally {
            OwnershipController.endNativeSession();
        }
    }

    private static String safe(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}]", "");
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }

    /** Keeps the socket message. A bare class name cannot tell a remote reset from a local close. */
    private static String failureText(Exception error) {
        if (error instanceof Failure typed) return typed.code;
        StringBuilder text = new StringBuilder(error.getClass().getSimpleName());
        String message = SessionLog.redact(error.getMessage());
        if (!message.isEmpty()) text.append(" msg=").append(message);
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            text.append(" cause=").append(cause.getClass().getSimpleName());
            String causeMessage = SessionLog.redact(cause.getMessage());
            if (!causeMessage.isEmpty()) text.append(" causeMsg=").append(causeMessage);
        }
        return text.toString();
    }

    private String bondNow(Inputs binding) {
        try {
            var adapter = context.getSystemService(BluetoothManager.class).getAdapter();
            if (adapter == null) return " bond=adapter-null";
            int state = adapter.getRemoteDevice(binding.address()).getBondState();
            return " bond=" + state + "/" + bondName(state);
        } catch (RuntimeException unread) {
            return " bond=unread";
        }
    }

    private static String bondName(int state) {
        return switch (state) {
            case BluetoothDevice.BOND_NONE -> "none";
            case BluetoothDevice.BOND_BONDING -> "bonding";
            case BluetoothDevice.BOND_BONDED -> "bonded";
            default -> "other";
        };
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
        int channel = rawPayload.length == 0 ? -1 : rawPayload[0] & 0x0f;
        int expected = receive.expected();
        if (!frame.frx() && channel != 7 && channel != 10 && sequence != expected
                && receivedAt[sequence] >= 0 && receive.count() - receivedAt[sequence] <= 32
                && Arrays.equals(rawPayload, receivedPayloads[sequence])) {
            sendFrame(new SppV2Codec.Frame(SppV2Codec.TYPE_ACK, sequence, new byte[0]));
            return null;
        }
        SppV2Codec.ReceiveCursor.Decision decision = receive.offer(sequence, frame.frx(), channel);
        if (decision == SppV2Codec.ReceiveCursor.Decision.DROP) {
            SessionLog.line(context, "rx spp2 seq=" + sequence + " expected=" + expected
                    + " channel=" + channel + " action=drop");
            return null;
        }
        if (decision == SppV2Codec.ReceiveCursor.Decision.NAK) {
            SessionLog.line(context, "rx spp2 seq=" + sequence + " expected=" + expected
                    + " channel=" + channel + " action=nak");
            sendFrame(new SppV2Codec.Frame(SppV2Codec.TYPE_NAK, expected, new byte[0]));
            return null;
        }
        if (sequence != expected) {
            SessionLog.line(context, "rx spp2 seq=" + sequence + " expected=" + expected
                    + " channel=" + channel + " frx=" + frame.frx() + " action=resync");
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
        receivedAt[sequence] = receive.count() - 1;
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
        BandHistoryParser.FileResult result = null;
        if (history == null) {
            SessionLog.line(context, "rx spp2 activity before history seq=" + sequence);
        } else if (data.opcode() != SppV2Codec.OPCODE_ENCRYPTED) {
            throw new Failure("UNSUPPORTED_DATA_CHANNEL");
        } else {
            byte[] plain = session.decryptV2(data.payload());
            try {
                result = history.acceptFragment(plain);
                progress.accept("HISTORY_FRAGMENT_RECEIVED");
            } catch (IllegalArgumentException malformed) {
                history.reset();
                progress.accept("HISTORY_FILE_REJECTED");
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        }
        byte[] fileId = result == null ? null : archiveActivity(result);
        if (receivedPayloads[sequence] != null) Arrays.fill(receivedPayloads[sequence], (byte) 0);
        receivedPayloads[sequence] = rawPayload;
        receivedAt[sequence] = receive.count() - 1;
        sendFrame(new SppV2Codec.Frame(SppV2Codec.TYPE_ACK, sequence, new byte[0]));
        if (fileId != null) ackActivity(fileId);
    }

    /** Persist before any band confirm. Unsupported files are still acked so the band can send the next one. */
    private byte[] archiveActivity(BandHistoryParser.FileResult result) {
        byte[] fileId;
        try {
            rawHistory.persist(result, historyDeviceId, verifiedDevice.firmware(), System.currentTimeMillis());
            fileId = result.copyFileId();
            progress.accept("HISTORY_FILE_ARCHIVED");
        } catch (Exception storageFailed) {
            progress.accept("HISTORY_STORAGE_FAILED");
            String reason = storageFailed.getMessage();
            SessionLog.line(context, "history storage failed "
                    + storageFailed.getClass().getSimpleName()
                    + (reason != null && reason.matches("[A-Z][A-Z0-9_]{1,90}") ? " " + reason : ""));
            return null;
        }
        if ("PARSED".equals(result.parseStatus)) {
            try { summarizeSport(result.measurements); }
            catch (RuntimeException snapshotFailed) { progress.accept("HEALTH_SNAPSHOT_PAUSED"); }
            Consumer<java.util.List<BandHistoryParser.Measurement>> sleep = onSleepFile;
            if (sleep != null) {
                for (BandHistoryParser.Measurement measurement : result.measurements) {
                    if ("sleep_interval".equals(measurement.kind)) {
                        sleep.accept(result.measurements);
                        break;
                    }
                }
            }
        } else {
            progress.accept("HISTORY_FORMAT_UNSUPPORTED");
            SessionLog.line(context, "history " + result.parseStatus);
        }
        return fileId;
    }

    /** Type 8 subtype 5. SPP V1 and BLE have no transport ACK, so this confirm is what releases the next file. */
    private void ackActivity(byte[] fileId) {
        LiveCommandQueue commands = liveCommands;
        if (commands == null) return;
        commands.send(XiaomiProto.Command.newBuilder().setType(8).setSubtype(5)
                .setHealth(XiaomiProto.Health.newBuilder()
                        .setActivitySyncAckFileIds(ByteString.copyFrom(fileId))).build())
                .whenComplete((ignored, error) -> {
                    if (error == null) {
                        progress.accept("HISTORY_FILE_STORED");
                        Consumer<byte[]> stored = onFileStored;
                        if (stored != null) stored.accept(fileId);
                    } else progress.accept("HISTORY_CONFIRMATION_PENDING");
                });
    }

    private void summarizeSport(java.util.List<BandHistoryParser.Measurement> measurements) {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        long dayStart = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
        long dayEnd = java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        int heart = -1;
        long heartAt = -1;
        for (BandHistoryParser.Measurement measurement : measurements) {
            if (measurement.value == null || !"heart_rate".equals(measurement.kind)
                    || !("continuous".equals(measurement.measurementMode)
                            || "manual".equals(measurement.measurementMode))
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
            if (!BandWeatherEncoder.acceptableCityCode(city.getCode()) || !city.hasName()
                    || city.getName().isBlank()) continue;
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
            if (frame.type() == SppV2Codec.TYPE_NAK) {
                SessionLog.line(context, "rx spp2 nak seq=" + frame.sequence());
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
                    } else if (frame.type() == SppV2Codec.TYPE_NAK) {
                        SessionLog.line(context, "rx spp2 nak seq=" + frame.sequence());
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
                        // Transport ack already happened in writeLiveCommand. sentSequence is stale here.
                        if (onLiveCommand != null) onLiveCommand.accept(command);
                        liveCommands.onCommand(command);
                    }
                }
            }
            // close() wakes the reader with an empty packet, so a failed write used to
            // leave this loop without a trace and the supervisor treated it as a clean end.
            if (linkStop != null) throw new Failure(linkStop);
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
                verifiedDevice.firmware(), verifiedDevice.hardware(), verifiedDevice.sku()));
    }

    /**
     * An RFCOMM connect carries no timeout of its own: after the adapter cycles, the call can sit
     * in the stack for a minute while the band is reachable again. A watchdog closes the socket
     * instead, so the retry loop runs again right away.
     */
    private void connectWithTimeout(BluetoothSocket target) throws Exception {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread connector = new Thread(() -> {
            try {
                target.connect();
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "band-spp-connect");
        connector.setDaemon(true);
        connector.start();
        connector.join(CONNECT_TIMEOUT_MS);
        if (connector.isAlive()) {
            try { target.close(); } catch (Throwable ignored) { }
            SessionLog.line(context, "spp connect timeout");
            throw new Failure("CONNECT_TIMEOUT");
        }
        Throwable error = failure.get();
        if (error instanceof Exception typed) throw typed;
        if (error != null) throw new IllegalStateException(error);
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
