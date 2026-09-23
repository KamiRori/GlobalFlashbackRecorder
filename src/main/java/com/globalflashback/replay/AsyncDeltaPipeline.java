package com.globalflashback.replay;

import com.globalflashback.delta.DeltaFrame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Main-thread → async spill bridge (H11 / SPEC §34).
 *
 * <p>Capture/Diff produce immutable {@link DeltaFrame}s on the server thread; a single worker
 * drains a bounded queue and appends them to {@link DeltaSpillFile}. When the queue is full the
 * offer blocks (backpressure) — critical frames are never dropped.
 */
public final class AsyncDeltaPipeline implements AutoCloseable {
    /** Soft bound on in-flight frames awaiting spill (backpressure when full). */
    public static final int DEFAULT_QUEUE_CAPACITY = 256;

    private final BlockingQueue<DeltaFrame> queue;
    private final DeltaSpillFile.Writer writer;
    private final Thread worker;
    private final Logger logger;
    private final AtomicBoolean sealed = new AtomicBoolean();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicReference<Exception> failure = new AtomicReference<>();

    private AsyncDeltaPipeline(
            BlockingQueue<DeltaFrame> queue,
            DeltaSpillFile.Writer writer,
            Logger logger
    ) {
        this.queue = queue;
        this.writer = writer;
        this.logger = logger;
        this.worker = new Thread(this::runWorker, "gfr-delta-spill");
        this.worker.setDaemon(true);
    }

    public static AsyncDeltaPipeline start(Path spillPath, Logger logger) throws IOException {
        return start(spillPath, DEFAULT_QUEUE_CAPACITY, logger);
    }

    public static AsyncDeltaPipeline start(Path spillPath, int queueCapacity, Logger logger)
            throws IOException {
        Objects.requireNonNull(spillPath, "spillPath");
        Objects.requireNonNull(logger, "logger");
        if (queueCapacity < 8) {
            throw new IllegalArgumentException("queueCapacity must be >= 8");
        }
        DeltaSpillFile.Writer writer = DeltaSpillFile.create(spillPath);
        BlockingQueue<DeltaFrame> queue = new ArrayBlockingQueue<>(queueCapacity);
        AsyncDeltaPipeline pipeline = new AsyncDeltaPipeline(queue, writer, logger);
        pipeline.worker.start();
        return pipeline;
    }

    public Path path() {
        return writer.path();
    }

    public int spilledFrames() {
        return writer.frames();
    }

    /**
     * Enqueue a non-empty frame. Blocks if the spill worker is behind; never drops.
     */
    public void offer(DeltaFrame frame) throws InterruptedException {
        Objects.requireNonNull(frame, "frame");
        if (frame.isEmpty()) {
            return;
        }
        if (sealed.get()) {
            throw new IllegalStateException("AsyncDeltaPipeline is sealed");
        }
        Exception err = failure.get();
        if (err != null) {
            throw new IllegalStateException("Delta spill worker failed", err);
        }
        if (!queue.offer(frame, 5, TimeUnit.SECONDS)) {
            queue.put(frame);
        }
    }

    /**
     * Stop accepting frames, drain the queue, close the spill file.
     */
    public void sealAndAwait() throws InterruptedException, IOException {
        sealed.set(true);
        if (!finished.await(120, TimeUnit.SECONDS)) {
            worker.interrupt();
            throw new IOException("Timed out waiting for delta spill worker");
        }
        Exception err = failure.get();
        if (err instanceof IOException io) {
            throw io;
        }
        if (err != null) {
            throw new IOException("Delta spill failed", err);
        }
    }

    private void runWorker() {
        try {
            while (true) {
                DeltaFrame frame = queue.poll(200, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    writer.write(frame);
                    continue;
                }
                if (sealed.get() && queue.isEmpty()) {
                    break;
                }
            }
            DeltaFrame leftover;
            while ((leftover = queue.poll()) != null) {
                writer.write(leftover);
            }
            writer.close();
        } catch (Exception e) {
            failure.compareAndSet(null, e);
            logger.severe("Delta spill worker failed: " + e.getMessage());
            e.printStackTrace();
            try {
                writer.close();
            } catch (IOException ignored) {
                // already failing
            }
        } finally {
            finished.countDown();
        }
    }

    public void deleteQuietly() {
        try {
            Files.deleteIfExists(writer.path());
        } catch (IOException ignored) {
            // best-effort
        }
    }

    @Override
    public void close() {
        sealed.set(true);
        try {
            finished.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
