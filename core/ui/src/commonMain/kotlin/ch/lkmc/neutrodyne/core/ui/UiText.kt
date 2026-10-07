// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Resource-free text for ViewModels (01 ViewModels and UI state): a Compose resource plus its
 * format args, or a plural, resolved in composition. [Raw] is user content only (titles, names),
 * never app copy.
 */
public sealed interface UiText {
    /** `stringResource(id, *args)`; args are `String`/`Int`/`Long` or nested [UiText]. */
    public data class Res(
        val id: StringResource,
        val args: List<Any> = emptyList(),
    ) : UiText

    /** `pluralStringResource(id, count, *args)`; [count] selects the quantity string. */
    public data class Plural(
        val id: PluralStringResource,
        val count: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    /** Literal user content. Never use for app copy. */
    public data class Raw(
        val value: String,
    ) : UiText

    /**
     * Parts joined with a single separator when resolved (08's sentence-style summaries:
     * "{title}. {podcast}. {date}." → `Joined(parts, ". ")`). Empty parts are dropped.
     */
    public data class Joined(
        val parts: List<UiText>,
        val separator: String = ". ",
        val suffix: String = ".",
    ) : UiText
}

/** Resolves this text inside composition (`stringResource`/`pluralStringResource`). */
@Composable
public fun UiText.asString(): String =
    when (this) {
        is UiText.Res -> stringResource(id, *composeArgs(args))
        is UiText.Plural -> pluralStringResource(id, count, *composeArgs(args))
        is UiText.Raw -> value
        // `map`/`filter` are inline, so the composable `asString()` calls are legal here.
        is UiText.Joined ->
            parts.map { it.asString() }.filter { it.isNotEmpty() }.joinToString(separator) + suffix
    }

/**
 * Resolves this text outside composition (notifications, workers, desktop lanes) with Compose
 * resources' suspend `getString`/`getPluralString`.
 */
public suspend fun UiText.resolve(): String =
    when (this) {
        is UiText.Res -> getString(id, *suspendArgs(args))
        is UiText.Plural -> getPluralString(id, count, *suspendArgs(args))
        is UiText.Raw -> value
        is UiText.Joined -> joined(parts, separator, suffix) { it.resolve() }
    }

private suspend fun joined(
    parts: List<UiText>,
    separator: String,
    suffix: String,
    resolve: suspend (UiText) -> String,
): String = parts.map { resolve(it) }.filter { it.isNotEmpty() }.joinToString(separator) + suffix

// Args flatten nested UiText; primitives pass through to the platform formatter.
@Composable
private fun composeArgs(args: List<Any>): Array<Any> =
    Array(args.size) { i ->
        when (val a = args[i]) {
            is UiText -> a.asString()
            else -> a
        }
    }

private suspend fun suspendArgs(args: List<Any>): Array<Any> =
    Array(args.size) { i ->
        when (val a = args[i]) {
            is UiText -> a.resolve()
            else -> a
        }
    }
