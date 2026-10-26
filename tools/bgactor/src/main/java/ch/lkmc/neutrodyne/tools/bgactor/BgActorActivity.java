// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bgactor;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * The bgstart leg's bootstrap: the shell can launch an activity from the
 * background (`am start` is exempt from the bg-start restriction), while
 * `am startservice` on a cold package is denied for any caller whose package
 * targets S+. The activity's whole job is to start [BgActorService] and get out
 * of the way; the service then waits for this uid to demote before it binds.
 */
public final class BgActorActivity extends Activity {
    private static final String TAG = "BgActor";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            ComponentName started =
                startService(new Intent(this, BgActorService.class));
            if (started == null) {
                Log.e(TAG, "BGACTOR-FAIL startService returned null for BgActorService");
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "BGACTOR-FAIL startService threw", e);
        }
        finish();
    }
}
