// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl;

import androidx.media3.common.MediaItem;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** Exercise pinned Media3's recent-root dispatch, including its boolean false callback. */
public final class RecentRootProbe {
    private RecentRootProbe() {}

    public static ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> children(
            MediaLibraryService.MediaLibrarySession session, MediaSession.ControllerInfo controller) {
        Object impl = ReflectionHelpers.callInstanceMethod(session, "getImpl");
        MediaLibraryService.LibraryParams params =
                new MediaLibraryService.LibraryParams.Builder().setRecent(true).build();
        ListenableFuture<LibraryResult<MediaItem>> rootFuture = ReflectionHelpers.callInstanceMethod(
                impl, "onGetLibraryRootOnHandler",
                ClassParameter.from(MediaSession.ControllerInfo.class, controller),
                ClassParameter.from(MediaLibraryService.LibraryParams.class, params));
        return Futures.transformAsync(rootFuture, root -> ReflectionHelpers.callInstanceMethod(
                impl, "onGetChildrenOnHandler",
                ClassParameter.from(MediaSession.ControllerInfo.class, controller),
                ClassParameter.from(String.class, root.value.mediaId),
                ClassParameter.from(int.class, 0), ClassParameter.from(int.class, 1),
                ClassParameter.from(MediaLibraryService.LibraryParams.class, params)),
                MoreExecutors.directExecutor());
    }
}
