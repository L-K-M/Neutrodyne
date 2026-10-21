// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.transportprobe;

import android.app.Activity;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Synthetic platform BackupAgent fixture, never a product-app seed hook. */
public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            write(new File(getFilesDir(), "allowed/payload"), "transport-probe-v1\n");
            write(new File(getFilesDir(), "other/excluded"), "excluded-files-canary\n");
            write(new File(getNoBackupFilesDir(), "excluded"), "excluded-no-backup-canary\n");
        } catch (IOException error) {
            throw new IllegalStateException("Cannot prepare transport fixture", error);
        }
        finish();
    }

    private static void write(File file, String value) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create directory");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }
}
