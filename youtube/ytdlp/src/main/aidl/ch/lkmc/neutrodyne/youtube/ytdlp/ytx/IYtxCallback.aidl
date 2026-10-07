// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp.ytx;

/** Answers of `IYtxEngine.call` (04 Binder API). Oneway: never blocks the `:ytx` side. */
oneway interface IYtxCallback {
    void onResult(long callId, String resultJson);
    void onError(long callId, String code, String message);
}
