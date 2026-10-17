// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import ch.lkmc.neutrodyne.core.database.SqliteDriverBindings

/** The platform driver, running a callback once a transaction containing an armed statement commits. */
internal class CommitCancellingDriver : SQLiteDriver {
    private val delegate = SqliteDriverBindings.sqliteDriver()

    @Volatile private var statement: String? = null

    @Volatile private var onCommit: (() -> Unit)? = null

    @Volatile private var stepStatement: String? = null

    @Volatile private var onStep: (() -> Unit)? = null

    @Volatile var cancellations = 0
        private set

    fun arm(
        statement: String,
        onCommit: () -> Unit,
    ) {
        this.statement = statement
        this.onCommit = onCommit
    }

    /** Runs [onStep] once, inside the `step()` of the first statement matching [statement]. */
    fun armOnStep(
        statement: String,
        onStep: () -> Unit,
    ) {
        this.stepStatement = statement
        this.onStep = onStep
    }

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        // Per connection: only the transaction that ran the armed statement may fire the hook.
        var ranHere = false
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val prepared = connection.prepare(sql)
                return object : SQLiteStatement by prepared {
                    override fun step(): Boolean {
                        val row = prepared.step()
                        stepStatement?.let { armed ->
                            if (sql.startsWith(armed)) {
                                stepStatement = null
                                onStep?.invoke()
                            }
                        }
                        val armed = statement ?: return row
                        if (sql.startsWith(armed)) ranHere = true
                        if (sql.startsWith("ROLLBACK")) ranHere = false
                        if (ranHere && (sql.startsWith("END TRANSACTION") || sql.startsWith("COMMIT"))) {
                            ranHere = false
                            statement = null
                            cancellations++
                            onCommit?.invoke()
                        }
                        return row
                    }
                }
            }
        }
    }
}
