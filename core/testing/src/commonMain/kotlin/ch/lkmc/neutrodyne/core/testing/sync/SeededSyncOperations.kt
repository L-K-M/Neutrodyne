// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing.sync

import kotlin.random.Random

/** Fixed names and an ordered argument list keep failing traces useful outside the JVM. */
enum class SyncOperation {
    SUBSCRIBE,
    UNSUBSCRIBE,
    FEED_MOVE,
    GROUP_CREATE,
    GROUP_RENAME,
    GROUP_DELETE,
    MEMBER_ADD,
    MEMBER_REMOVE,
    MEMBER_MOVE,
    QUEUE_ADD,
    QUEUE_MOVE,
    QUEUE_REMOVE,
    POSITION,
    ZERO_WITHOUT_RESET,
    RESET,
    PLAYED,
    UNPLAYED,
    FAVOURITE,
    SETTING,
    REKEY,
    OFFLINE,
    ONLINE,
    PUSH,
    DELIVER,
    PULL,
    EXPIRE,
}

data class SeededSyncOperation(
    val device: Int,
    val operation: SyncOperation,
    val subject: Int,
    val item: Int,
    val value: Int,
)

/** Every seed covers every operation; the random suffix and schedule vary conflicts and delivery. */
object SeededSyncOperations {
    const val REPLICAS = 5
    private const val GUARANTEED_ROUNDS = 2
    private const val RANDOM_OPERATIONS = 64
    private const val ITEMS = 3
    private const val VALUE_BOUND = 10_000

    fun generate(seed: Long): List<SeededSyncOperation> {
        val random = Random(seed)
        val kinds =
            buildList {
                repeat(GUARANTEED_ROUNDS) { addAll(SyncOperation.entries.shuffled(random)) }
                repeat(RANDOM_OPERATIONS) { add(SyncOperation.entries.random(random)) }
            }
        return kinds.map { kind ->
            SeededSyncOperation(
                random.nextInt(REPLICAS),
                kind,
                random.nextInt(REPLICAS),
                random.nextInt(ITEMS),
                random.nextInt(VALUE_BOUND),
            )
        }
    }
}
