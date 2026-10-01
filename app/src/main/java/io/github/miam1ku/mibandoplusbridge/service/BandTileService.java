// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.app.PendingIntent;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider;

/** SystemUI binding this tile starts the bridge. A tap never stops the connection. */
public final class BandTileService extends TileService {
    private ContentObserver observer;

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (BandLiveService.mayWake(this)) BandLiveService.ensureProcess(this);
        if (observer == null) {
            observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) { render(); }
            };
            try {
                getContentResolver().registerContentObserver(DeviceCardProvider.URI, false, observer);
            } catch (RuntimeException unavailable) {
                observer = null;
            }
        }
        render();
    }

    @Override
    public void onStopListening() {
        if (observer != null) {
            getContentResolver().unregisterContentObserver(observer);
            observer = null;
        }
        super.onStopListening();
    }

    @Override
    public void onClick() {
        Intent open = new Intent(this, BandTileActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        startActivityAndCollapse(pending);
    }

    private void render() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean registered = false;
        boolean connected = false;
        int battery = -1;
        try {
            registered = new BandStateRepository(this).isRegistered();
            if (registered) {
                LocalPrefs state = LocalPrefs.open(this, "band-state");
                connected = state.getBoolean("connected", false);
                battery = state.getInt("battery", -1);
            }
        } catch (RuntimeException ignored) {
            registered = false;
        }
        BandTileState.View view = BandTileState.of(
                registered, BandLiveService.isRunning(), connected, battery);
        tile.setState(view.state());
        tile.setSubtitle(view.subtitle());
        tile.updateTile();
    }
}
