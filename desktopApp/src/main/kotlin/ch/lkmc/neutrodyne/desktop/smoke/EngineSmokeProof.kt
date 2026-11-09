// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.smoke

import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * MD3 AC2's packaged engine proof (11 Smoke mode step 6): bound only in engine builds — the
 * no-engine graph binds `null` — and run only when the packaged smoke armed the replay
 * fixture ([SmokeMode.ENGINE_REPLAY_PROPERTY]). [run] must use the packaged interpreter,
 * shim, yt-dlp package and trust resources, resolve the repository's recorded fixture
 * through the real stdio host and application service, and return the small machine-readable
 * evidence object the launcher gate validates. Every deviation — missing or non-packaged
 * resources, a dev-tree interpreter, a failed resolve — is a failed smoke step, never a
 * fallback.
 */
fun interface EngineSmokeProof {
    /** Returns the engine evidence for the `SMOKE` line; throws on any failed check. */
    suspend fun run(replayDir: File): JsonObject
}
