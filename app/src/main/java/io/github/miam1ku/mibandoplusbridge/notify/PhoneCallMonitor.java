// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.notify;

import android.Manifest;
import android.app.AppOpsManager;
import android.media.AudioManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserManager;
import android.telecom.TelecomManager;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;
import io.github.miam1ku.mibandoplusbridge.protocol.BandNotificationCommand;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
/** Runs on the service's serial coordinator; never acquires its own transport. */
public final class PhoneCallMonitor implements AutoCloseable {
    private final Context context;
    private final BandStateRepository repository;
    private final ScheduledExecutorService coordinator;
    private final SubscriptionManager subscriptions;
    private final TelephonyManager telephony;
    private final AppOpsManager appOps;
    private final SharedPreferences settings;
    private final Map<Integer, CallListener> listeners = new HashMap<>();
    private final PhoneCallGate gate;
    private boolean subscriptionListenerRegistered;
    private boolean gateConnected;
    private boolean sessionConnected;
    private volatile String caller = "来电";
    private int savedRingerMode = -1;
    private volatile boolean closed;
    private volatile String monitorFailure;

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (preferences, key) -> {
                if ("callsEnabled".equals(key) || "callsHangup".equals(key) || "callsSmsReply".equals(key)
                        || "mode".equals(key) || "ownsDisable".equals(key)
                        || "officialRestored".equals(key) || "registered".equals(key)) refresh();
            };
    private final AppOpsManager.OnOpChangedListener permissionListener = (op, packageName) -> refresh();
    private final SubscriptionManager.OnSubscriptionsChangedListener subscriptionListener =
            new SubscriptionManager.OnSubscriptionsChangedListener() {
                @Override public void onSubscriptionsChanged() { refresh(); }
            };

    public PhoneCallMonitor(Context context, ScheduledExecutorService coordinator) {
        this.context = context.getApplicationContext();
        repository = new BandStateRepository(this.context);
        this.coordinator = coordinator;
        subscriptions = this.context.getSystemService(SubscriptionManager.class);
        telephony = this.context.getSystemService(TelephonyManager.class);
        appOps = this.context.getSystemService(AppOpsManager.class);
        settings = this.context.getSharedPreferences("notification-settings", Context.MODE_PRIVATE);
        gate = new PhoneCallGate(new PhoneCallGate.Sender() {
            @Override public CompletionStage<Void> send(PhoneCallGate.Phase phase) {
                SessionLog.line(PhoneCallMonitor.this.context, "CALL_PHASE " + phase);
                if (phase == PhoneCallGate.Phase.NONE) restoreRinger();
                return BandLiveService.forwardHostNotification(PhoneCallMonitor.this.context, commandFor(phase));
            }
            @Override public void cancelQueuedRing() {
                BandLiveService.cancelCall(PhoneCallMonitor.this.context);
            }
        });
        settings.registerOnSharedPreferenceChangeListener(preferenceListener);
        appOps.startWatchingMode(AppOpsManager.OPSTR_READ_PHONE_STATE, this.context.getPackageName(), permissionListener);
        appOps.startWatchingMode(AppOpsManager.OPSTR_ANSWER_PHONE_CALLS, this.context.getPackageName(), permissionListener);
        appOps.startWatchingMode(AppOpsManager.OPSTR_SEND_SMS, this.context.getPackageName(), permissionListener);
        refresh();
    }

    public void refresh() {
        if (!closed) coordinator.execute(() -> refreshNow(false));
    }

    public void connected() {
        if (!closed) coordinator.execute(() -> {
            if (closed) return;
            sessionConnected = true;
            refreshNow(true);
        });
    }

    public void disconnected() {
        if (!closed) coordinator.execute(() -> {
            sessionConnected = false;
            disconnectGate();
        });
    }

    public void noteAnswered() {
        if (!closed) coordinator.execute(() -> {
            if (gate.markAnswered()) SessionLog.line(context, "CALL_ANSWERED");
        });
    }

    /** Content-free status; a submission or transport failure never appears as delivered. */
    public String lastFailureCode() {
        if (monitorFailure != null) return monitorFailure;
        return gate.lastFailure() == null ? null : "CALL_SUBMISSION_FAILED";
    }

    public void onBandCommand(XiaomiProto.Command command) {
        if (closed || command == null || command.getType() != 7) return;
        coordinator.execute(() -> handleBandCommand(command));
    }

    private void handleBandCommand(XiaomiProto.Command command) {
        if (closed) return;
        int subtype = command.getSubtype();
        SessionLog.line(context, "CALL_IN subtype=" + subtype);
        if (subtype == 5) {
            silence();
            return;
        }
        if (subtype == 2 || isCallDismiss(command)) {
            android.util.Log.i("OplusBandBridge", "CALL_HANGUP_FROM_BAND subtype=" + subtype);
            SessionLog.line(context, "CALL_HANGUP subtype=" + subtype);
            hangup();
            return;
        }
        if (subtype == 13 && command.hasNotification() && command.getNotification().hasNotificationReply()) {
            replySms(command.getNotification().getNotificationReply());
        }
    }

    private static boolean isCallDismiss(XiaomiProto.Command command) {
        if (command.getSubtype() != 1 || !command.hasNotification()
                || !command.getNotification().hasNotificationDismiss()) return false;
        var dismiss = command.getNotification().getNotificationDismiss();
        if (dismiss.getNotificationIdCount() != 1) return false;
        var id = dismiss.getNotificationId(0);
        return "phone".equals(id.getPackage()) && id.getId() == 0;
    }

    private boolean hangupEnabled() {
        return !closed
                && context.getSystemService(UserManager.class).isUserUnlocked()
                && repository.isRegistered()
                && "NATIVE".equals(io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(context, "ownership")
                        .getString("mode", "OFFICIAL"))
                && context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void hangup() {
        if (!hangupEnabled()) {
            android.util.Log.i("OplusBandBridge", "CALL_HANGUP_SKIPPED granted="
                    + (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
                            == PackageManager.PERMISSION_GRANTED));
            return;
        }
        try {
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            boolean ended = telecom != null && telecom.endCall();
            android.util.Log.i("OplusBandBridge", "CALL_HANGUP_PHONE ended=" + ended);
        } catch (SecurityException failure) {
            android.util.Log.i("OplusBandBridge", "CALL_HANGUP_DENIED");
        }
    }

    private void silence() {
        try {
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            if (telecom != null) telecom.silenceRinger();
            SessionLog.line(context, "CALL_IGNORE silence");
            return;
        } catch (SecurityException denied) {
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (audio == null) {
                SessionLog.line(context, "CALL_IGNORE denied");
                return;
            }
            int mode = audio.getRingerMode();
            if (mode != AudioManager.RINGER_MODE_SILENT) {
                savedRingerMode = mode;
                audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            }
            SessionLog.line(context, "CALL_IGNORE ringer");
        }
    }

    private void restoreRinger() {
        if (savedRingerMode < 0) return;
        AudioManager audio = context.getSystemService(AudioManager.class);
        int mode = savedRingerMode;
        savedRingerMode = -1;
        if (audio != null) {
            try { audio.setRingerMode(mode); }
            catch (SecurityException denied) { SessionLog.line(context, "CALL_RINGER_RESTORE denied"); }
        }
    }

    private XiaomiProto.Command commandFor(PhoneCallGate.Phase phase) {
        Instant now = Instant.now();
        ZoneId zone = ZoneId.systemDefault();
        return switch (phase) {
            case INCOMING -> {
                String title = BandNotificationListener.currentIncomingCallTitle(context);
                if (title != null && !title.isBlank()) caller = title;
                yield BandNotificationCommand.call(caller, null, BandNotificationCommand.CALL_INCOMING, now, zone, false);
            }
            case ACTIVE -> BandNotificationCommand.call(
                    "来电".equals(caller) ? "通话中" : caller, null,
                    BandNotificationCommand.CALL_ACTIVE, now, zone, false);
            case OUTGOING -> BandNotificationCommand.call("去电", null,
                    BandNotificationCommand.CALL_OUTGOING, now, zone, false);
            case NONE -> BandNotificationCommand.endCall();
        };
    }

    public boolean ownsCalls() {
        return !closed && callsEnabled() && eligible();
    }

    private boolean callsEnabled() {
        return settings.getBoolean("callsEnabled", false);
    }

    private boolean smsReplyEnabled() {
        return hangupEnabled()
                && context.checkSelfPermission(Manifest.permission.SEND_SMS)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void replySms(XiaomiProto.NotificationReply reply) {
        boolean sent = false;
        try {
            String number = reply.getNumber();
            String message = reply.getMessage();
            if (smsReplyEnabled() && BandNotificationCommand.usableNumber(number)
                    && message != null && !message.isBlank() && message.length() <= 400) {
                SmsManager.getDefault().sendTextMessage(number.trim(), null, message, null, null);
                sent = true;
            }
        } catch (RuntimeException ignored) { }
        BandLiveService.sendSessionCommand(BandNotificationCommand.smsReplyAck(sent));
        if (sent) hangup();
    }


    private boolean eligible() {
        return !closed && context.getSystemService(UserManager.class).isUserUnlocked()
                && context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                && phoneStateAllowed()
                && repository.isRegistered()
                && "NATIVE".equals(io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(context, "ownership")
                        .getString("mode", "OFFICIAL"))
                && io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(context, "ownership")
                        .getBoolean("ownsDisable", false)
                && !io.github.miam1ku.mibandoplusbridge.data.LocalPrefs.open(context, "ownership")
                        .getBoolean("officialRestored", false);
    }

    private boolean phoneStateAllowed() {
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_READ_PHONE_STATE,
                Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
                || mode == AppOpsManager.MODE_FOREGROUND;
    }

    private void refreshNow(boolean freshSession) {
        if (closed) return;
        if (!eligible() || !callsEnabled()) {
            if (!BandLiveService.notificationSessionReady(context)) disconnectGate();
            gate.setEnabled(false);
            stopListening();
            SessionLog.line(context, "CALL_MONITOR off eligible=" + eligible() + " enabled=" + callsEnabled());
            return;
        }
        try {
            if (subscriptions == null || telephony == null) {
                throw new UnsupportedOperationException("TELEPHONY_UNAVAILABLE");
            }
            if (!subscriptionListenerRegistered) {
                subscriptions.addOnSubscriptionsChangedListener(coordinator, subscriptionListener);
                subscriptionListenerRegistered = true;
            }
            List<SubscriptionInfo> active = subscriptions.getActiveSubscriptionInfoList();
            Set<Integer> ids = new HashSet<>();
            if (active != null) for (SubscriptionInfo info : active) ids.add(info.getSubscriptionId());
            var iterator = listeners.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (!ids.contains(entry.getKey())) {
                    unregister(entry.getValue());
                    iterator.remove();
                }
            }
            for (int id : ids) {
                if (listeners.containsKey(id)) continue;
                CallListener listener = new CallListener(id, telephony.createForSubscriptionId(id));
                listeners.put(id, listener);
                listener.manager.registerTelephonyCallback(TelephonyManager.INCLUDE_LOCATION_DATA_NONE,
                        coordinator, listener);
            }
            // Unlike getCallState(), this query belongs to this exact SIM. Do not query inside callbacks.
            Map<Integer, PhoneCallGate.State> current = new HashMap<>();
            for (CallListener listener : listeners.values()) {
                current.put(listener.id, stateOf(listener.manager.getCallStateForSubscription()));
            }
            gate.setEnabled(true);
            if (sessionConnected && BandLiveService.notificationSessionReady(context)
                    && (freshSession || !gateConnected)) {
                gate.connected(current);
                gateConnected = true;
            } else {
                gate.replaceStates(current);
            }
            monitorFailure = null;
        } catch (RuntimeException failure) {
            monitorFailure = failure instanceof SecurityException ? "PHONE_PERMISSION_REQUIRED" : "PHONE_STATE_UNAVAILABLE";
            gate.setEnabled(false);
            stopListening();
        }
    }

    private void disconnectGate() {
        gateConnected = false;
        gate.disconnected();
    }

    private void stopListening() {
        for (CallListener listener : listeners.values()) unregister(listener);
        listeners.clear();
        if (subscriptionListenerRegistered) {
            try { subscriptions.removeOnSubscriptionsChangedListener(subscriptionListener); }
            catch (RuntimeException ignored) { /* The subscription service may already be unavailable. */ }
            subscriptionListenerRegistered = false;
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        settings.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        appOps.stopWatchingMode(permissionListener);
        coordinator.execute(() -> {
            disconnectGate();
            gate.setEnabled(false);
            stopListening();
        });
    }

    private static PhoneCallGate.State stateOf(int state) {
        return switch (state) {
            case TelephonyManager.CALL_STATE_RINGING -> PhoneCallGate.State.RINGING;
            case TelephonyManager.CALL_STATE_OFFHOOK -> PhoneCallGate.State.OFFHOOK;
            default -> PhoneCallGate.State.IDLE;
        };
    }

    private void unregister(CallListener listener) {
        try { listener.manager.unregisterTelephonyCallback(listener); }
        catch (RuntimeException ignored) { /* Permission revocation must still release our references. */ }
    }

    private final class CallListener extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        final int id;
        final TelephonyManager manager;
        CallListener(int id, TelephonyManager manager) { this.id = id; this.manager = manager; }
        @Override public void onCallStateChanged(int state) {
            if (closed || listeners.get(id) != this) return;
            if (!eligible() || !callsEnabled()) { refreshNow(false); return; }
            gate.onState(id, stateOf(state));
        }
    }
}
