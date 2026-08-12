package io.casehub.drafthouse.debate;

import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

public final class PipelineWatcher implements Closeable {

    private static final Logger LOG = Logger.getLogger(PipelineWatcher.class.getName());

    private final String dimension;
    private final Path workspacePath;
    private final BiConsumer<String, ProgressLogParser.ProgressEvent> listener;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private DirectoryWatcher directoryWatcher;
    private volatile long offset;

    public PipelineWatcher(String dimension, Path workspacePath,
                           BiConsumer<String, ProgressLogParser.ProgressEvent> listener) {
        this.dimension = dimension;
        this.workspacePath = workspacePath;
        this.listener = listener;
    }

    public String dimension() { return dimension; }

    public void start() throws IOException {
        Path logPath = workspacePath.resolve("progress.log");
        this.offset = Files.exists(logPath) ? Files.size(logPath) : 0;
        this.directoryWatcher = DirectoryWatcher.builder()
                .path(workspacePath)
                .listener(this::onFileEvent)
                .build();
        this.directoryWatcher.watchAsync();
    }

    public void stop() {
        if (stopped.compareAndSet(false, true) && directoryWatcher != null) {
            try { directoryWatcher.close(); }
            catch (IOException e) { LOG.warning("Failed to close watcher: " + e.getMessage()); }
        }
    }

    @Override
    public void close() { stop(); }

    private void onFileEvent(DirectoryChangeEvent event) {
        if (stopped.get()) return;
        if (!event.path().getFileName().toString().equals("progress.log")) return;
        tailProgressLog();
    }

    private void tailProgressLog() {
        Path logPath = workspacePath.resolve("progress.log");
        if (!Files.exists(logPath)) return;
        try {
            long fileSize = Files.size(logPath);
            if (fileSize <= offset) return;
            String newContent;
            try (var raf = new RandomAccessFile(logPath.toFile(), "r")) {
                raf.seek(offset);
                byte[] bytes = new byte[(int) (fileSize - offset)];
                raf.readFully(bytes);
                newContent = new String(bytes);
            }
            offset = fileSize;
            for (String line : newContent.split("\n")) {
                var parsed = ProgressLogParser.parse(line.trim());
                if (parsed != null) listener.accept(dimension, parsed);
            }
        } catch (IOException e) {
            LOG.warning("Failed to tail progress.log: " + e.getMessage());
        }
    }
}
