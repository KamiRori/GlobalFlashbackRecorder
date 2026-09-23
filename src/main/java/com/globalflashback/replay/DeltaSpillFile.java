package com.globalflashback.replay;

import com.globalflashback.delta.DeltaFrame;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Append-only spill of immutable {@link DeltaFrame}s for long recordings (H11).
 *
 * <p>Written by an async worker; read sequentially at encode time so the heap does not retain
 * every delta for the full session. Format: magic + version + repeated Java-serialized frames.
 */
public final class DeltaSpillFile {
    private static final int MAGIC = 0x47465244; // "GFRD"
    private static final int VERSION = 1;

    private DeltaSpillFile() {}

    public static Writer create(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(path)));
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.flush();
        return new Writer(path, out);
    }

    public static Reader open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(path)));
        int magic = in.readInt();
        int version = in.readInt();
        if (magic != MAGIC) {
            in.close();
            throw new IOException("Bad delta spill magic: 0x" + Integer.toHexString(magic));
        }
        if (version != VERSION) {
            in.close();
            throw new IOException("Unsupported delta spill version: " + version);
        }
        return new Reader(path, in);
    }

    public static final class Writer implements Closeable {
        private final Path path;
        private final ObjectOutputStream out;
        private int frames;
        private boolean closed;

        private Writer(Path path, ObjectOutputStream out) {
            this.path = path;
            this.out = out;
        }

        public Path path() {
            return path;
        }

        public int frames() {
            return frames;
        }

        public synchronized void write(DeltaFrame frame) throws IOException {
            ensureOpen();
            out.writeObject(Objects.requireNonNull(frame, "frame"));
            // Reset handle table periodically so long spills do not retain the whole graph.
            if ((++frames & 0x3FF) == 0) {
                out.reset();
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("DeltaSpillFile.Writer is closed");
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            out.flush();
            out.close();
        }
    }

    public static final class Reader implements Closeable, Iterable<DeltaFrame> {
        private final Path path;
        private final ObjectInputStream in;
        private boolean closed;

        private Reader(Path path, ObjectInputStream in) {
            this.path = path;
            this.in = in;
        }

        public Path path() {
            return path;
        }

        @Override
        public Iterator<DeltaFrame> iterator() {
            return new Iterator<>() {
                private DeltaFrame next;
                private boolean primed;
                private boolean finished;

                private void prime() {
                    if (primed || finished || closed) {
                        return;
                    }
                    primed = true;
                    try {
                        next = (DeltaFrame) in.readObject();
                    } catch (EOFException eof) {
                        finished = true;
                        next = null;
                    } catch (ClassNotFoundException | IOException e) {
                        finished = true;
                        next = null;
                        throw new IllegalStateException("Failed reading delta spill " + path, e);
                    }
                }

                @Override
                public boolean hasNext() {
                    prime();
                    return next != null;
                }

                @Override
                public DeltaFrame next() {
                    prime();
                    if (next == null) {
                        throw new NoSuchElementException();
                    }
                    DeltaFrame frame = next;
                    next = null;
                    primed = false;
                    return frame;
                }
            };
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            in.close();
        }
    }
}
