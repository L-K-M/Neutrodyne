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

    @Volatile private var ranStatement = false

    @Volatile var cancellations = 0
        private set

    fun arm(
        statement: String,
        onCommit: () -> Unit,
    ) {
        ranStatement = false
        this.statement = statement
        this.onCommit = onCommit
    }

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val prepared = connection.prepare(sql)
                return object : SQLiteStatement by prepared {
                    override fun step(): Boolean {
                        val row = prepared.step()
                        val armed = statement ?: return row
                        if (sql.startsWith(armed)) ranStatement = true
                        if (ranStatement && (sql.startsWith("END TRANSACTION") || sql.startsWith("COMMIT"))) {
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
