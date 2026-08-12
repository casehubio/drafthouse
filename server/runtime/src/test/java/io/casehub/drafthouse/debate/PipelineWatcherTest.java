package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PipelineWatcherTest {

    @Test
    void tails_progress_log_and_delivers_events(@TempDir Path tmpDir) throws Exception {
        var events = new ArrayList<ProgressLogParser.ProgressEvent>();
        var dimensions = new ArrayList<String>();
        var latch = new CountDownLatch(1);
        var watcher = new PipelineWatcher("coherence", tmpDir, (dim, event) -> {
            dimensions.add(dim);
            events.add(event);
            if (event instanceof ProgressLogParser.ReviewTerminal) latch.countDown();
        });
        watcher.start();

        Path log = tmpDir.resolve("progress.log");
        Files.writeString(log, "[10:00:00]   Reviewer (fresh session)\n");
        Thread.sleep(500);
        Files.writeString(log,
                Files.readString(log) + "REVIEW DONE\n");

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        watcher.stop();

        assertTrue(events.stream().anyMatch(e -> e instanceof ProgressLogParser.AgentStart));
        assertTrue(events.stream().anyMatch(e -> e instanceof ProgressLogParser.ReviewTerminal));
        assertTrue(dimensions.stream().allMatch("coherence"::equals));
    }

    @Test
    void dimension_name_accessible() {
        var watcher = new PipelineWatcher("structure", Path.of("/tmp"), (d, e) -> {});
        assertEquals("structure", watcher.dimension());
    }
}
