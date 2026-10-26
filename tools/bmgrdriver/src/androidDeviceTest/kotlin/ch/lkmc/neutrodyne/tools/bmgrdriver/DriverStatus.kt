// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.tools.bmgrdriver

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The durable status record one driver phase leaves in its own `files/bmgr/` vault. The host
 * pulls it read-only under root; the checker validates schema, phase, nonce and API so a
 * missing, unknown or foreign status fails closed.
 */
@Serializable
internal data class DriverStatus(
    val v: Int = 1,
    val phase: String,
    val nonce: String,
    val api: Int,
    val driverPackage: String,
    val targetPackage: String,
    val startedAt: Long,
    val steps: List<Step>,
    val result: String,
) {
    @Serializable
    internal data class Step(
        val name: String,
        val at: Long,
        val detail: String? = null,
    )
}

internal class StatusRecorder(
    private val phase: String,
    private val nonce: String,
    private val packageName: String,
) {
    private val startedAt = System.currentTimeMillis()
    private val steps = mutableListOf<DriverStatus.Step>()

    fun step(
        name: String,
        detail: String? = null,
    ) {
        steps += DriverStatus.Step(name = name, at = System.currentTimeMillis(), detail = detail)
    }

    fun write(
        dir: File,
        result: String,
    ) {
        val status =
            DriverStatus(
                phase = phase,
                nonce = nonce,
                api = Build.VERSION.SDK_INT,
                driverPackage = packageName,
                targetPackage = BmgrArming.TARGET_PACKAGE,
                startedAt = startedAt,
                steps = steps.toList(),
                result = result,
            )
        File(dir, "bmgr-status-$phase.json")
            .writeText(Json.encodeToString(DriverStatus.serializer(), status))
    }
}

/**
 * Instrumentation stream marker — the host checker requires it in the instrument log. Value
 * only: `sendStatus` carries it under the `bmgrDriver` Bundle key, which supplies the prefix.
 */
internal fun phaseStatusToken(
    phase: String,
    nonce: String,
    result: String,
) = "$phase:$nonce:$result"

/**
 * Shared phase epilogue: on failure first capture the live UI hierarchy, then write the
 * durable status JSON and emit the `bmgrDriver` marker the host's strict aggregate keys
 * on. Re-throws so the instrument run reports the failure honestly.
 */
internal fun finishDriverPhase(
    context: Context,
    phase: String,
    nonce: String,
    recorder: StatusRecorder,
    result: String,
    failure: Throwable? = null,
) {
    if (failure != null) {
        runCatching { UiRestore.captureFailure(phase) }
            .onFailure { recorder.step("ui-capture-error", it.toString()) }
    }
    recorder.write(SeedVault.dir(context), result)
    InstrumentationRegistry.getInstrumentation().sendStatus(
        0,
        Bundle().apply { putString("bmgrDriver", phaseStatusToken(phase, nonce, result)) },
    )
    failure?.let { throw it }
}
