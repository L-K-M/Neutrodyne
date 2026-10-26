// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bgactor;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * An inert endpoint whose only job is to exist: the driver's
 * `content call` is a synchronous binder transaction, and a synchronous
 * binder into a frozen process thaws it (the platform unfreezes a cached app
 * to serve an incoming call). The api36 freezer can ice this package's
 * waiting handler inside the activity-starter grace window, which would starve
 * the demotion poll; the driver's pokes keep the poll runnable. The method
 * name is a nop — the wake is the act of the call itself.
 */
public final class BgActorProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        return new Bundle();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
