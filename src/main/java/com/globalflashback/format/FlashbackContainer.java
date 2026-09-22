package com.globalflashback.format;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Clean-room Flashback replay ZIP container writer.
 *
 * <p>The file Flashback opens must use a {@code .zip} extension. Inside the archive,
 * chunk entries are still named {@code cN.flashback}.
 */
public final class FlashbackContainer {
    private FlashbackContainer() {}

    public static Writer create(Path file) throws IOException {
        return new Writer(file);
    }

    public static final class Writer implements Closeable {
        private final ZipOutputStream zip;

        private Writer(Path file) throws IOException {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.zip = new ZipOutputStream(Files.newOutputStream(file));
            this.zip.setLevel(Deflater.BEST_SPEED);
        }

        public void writeMetadata(FlashbackMeta meta) throws IOException {
            putEntry("metadata.json", meta.toJson().getBytes(StandardCharsets.UTF_8));
        }

        public void writeChunk(String name, byte[] data) throws IOException {
            putEntry(name, data);
        }

        private void putEntry(String name, byte[] data) throws IOException {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(data);
            zip.closeEntry();
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }
}
