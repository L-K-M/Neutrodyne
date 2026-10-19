// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

/** Run with only the packaged fat JAR and this compiled probe on the classpath. */
public final class PackagedJdbcProbe {
    public static void main(String[] args) throws Exception {
        var data = Path.of(args[0]);
        Files.createDirectories(data.resolve("native"));
        System.setProperty("org.sqlite.tmpdir", data.resolve("native").toString());
        long started = System.nanoTime();
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("spike.db"));
                var statement = db.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("CREATE TABLE spike(value TEXT)");
            statement.execute("INSERT INTO spike VALUES ('ok')");
            try (var rows = statement.executeQuery("SELECT sqlite_version(), value FROM spike")) {
                if (!rows.next() || !"ok".equals(rows.getString(2))) throw new IllegalStateException("JDBC read failed");
                System.out.println("sqlite=" + rows.getString(1));
            }
            System.out.println("jdbc-service=" + DriverManager.getDriver("jdbc:sqlite:").getClass().getName());
            try (var paths = Files.list(data.resolve("native"))) {
                if (paths.noneMatch(path -> path.getFileName().toString().endsWith(".so"))) {
                    throw new IllegalStateException("Native library did not extract under data/native");
                }
            }
            System.out.println("native-extraction=ok");
            System.out.println("elapsed-ms=" + (System.nanoTime() - started) / 1_000_000);
        }
    }
}
