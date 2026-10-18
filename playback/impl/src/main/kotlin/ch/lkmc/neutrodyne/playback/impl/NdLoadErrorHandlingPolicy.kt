// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import dev.zacsweers.metro.Inject
import java.net.HttpURLConnection

/** Recovery actions/position retention belong to W7; this only bounds connection-level retries. */
@Inject
internal class NdLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        val causes = generateSequence<Throwable>(loadErrorInfo.exception) { it.cause }.toList()
        if (causes.any {
                it is ContentChangedException || it is EpisodeGoneException ||
                    it is NoMediaException ||
                    (
                        it is YouTubeResolveException &&
                            it.result == ch.lkmc.neutrodyne.youtube.api.ResolveResult.Unsupported
                    )
            }
        ) {
            return C.TIME_UNSET
        }
        val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        if (http != null && http.dataSpec.key?.startsWith("ep:") == true &&
            http.responseCode in RSS_FINAL_RETRY_CODES
        ) {
            return if (loadErrorInfo.errorCount == 1) RSS_RETRY_MS else C.TIME_UNSET
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    private companion object {
        const val RSS_RETRY_MS = 1_000L
        val RSS_FINAL_RETRY_CODES =
            setOf(
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN,
                HttpURLConnection.HTTP_NOT_FOUND,
                HttpURLConnection.HTTP_GONE,
            )
    }
}
