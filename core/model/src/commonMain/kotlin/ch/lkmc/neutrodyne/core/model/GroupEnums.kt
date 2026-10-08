// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model

/** `podcast_group.kind` (02 Naming and types): user-made or rule-driven. */
enum class GroupKind { MANUAL, SMART }

/** Why a `podcast_group_member` row exists (02; [RULE] rows are maintained by the smart rule). */
enum class MemberSource { MANUAL, RULE }
