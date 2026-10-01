// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.MainActivity;

/** User-initiated tile tap. Wakes the bridge or opens it, then leaves no screen behind. */
public final class BandTileActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            if (BandTileState.click(BandLiveService.mayWake(this)) == BandTileState.Action.WAKE) {
                try {
                    BandLiveService.start(this);
                } catch (RuntimeException ignored) { }
            } else {
                startActivity(new Intent(this, MainActivity.class));
            }
        } finally {
            finish();
        }
    }
}
