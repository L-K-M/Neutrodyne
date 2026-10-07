// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp.ytx;

import ch.lkmc.neutrodyne.youtube.ytdlp.ytx.IYtxCallback;

/**
 * Binder API of the `:ytx` engine host (04 Binder API). Payloads and results are JSON strings;
 * no Python object crosses the boundary.
 */
interface IYtxEngine {
    // Queues `method` (04's shared method table) and returns at once; the answer arrives on `cb`.
    void call(long callId, String method, String payloadJson, long deadlineAtMs, IYtxCallback cb);
    // Cooperative cancel of a queued or running call.
    void cancel(long callId);
    // JSON: pid, engineVersion, ejsVersion, shimApi, python, jsChallenges, running, queued.
    String status();
}
