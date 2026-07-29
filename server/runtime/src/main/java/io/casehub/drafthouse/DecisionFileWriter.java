package io.casehub.drafthouse;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class DecisionFileWriter {

    private static final Logger LOG = Logger.getLogger(DecisionFileWriter.class.getName());

    private final Map<String, Object> fileLocks = new ConcurrentHashMap<>();

    public void append(String workspacePath, int round, String section,
                       String pointId, String content) {
        if (workspacePath == null) return;

        Path decisionsDir = Path.of(workspacePath, "decisions");
        Path file = decisionsDir.resolve("human-round-" + round + ".md");
        String lockKey = file.toString();
        Object lock = fileLocks.computeIfAbsent(lockKey, k -> new Object());

        synchronized (lock) {
            try {
                Files.createDirectories(decisionsDir);

                boolean isNew = !Files.exists(file);
                StringBuilder sb = new StringBuilder();

                if (isNew) {
                    sb.append("# Human Decisions — Round ").append(round).append("\n");
                }

                String existing = isNew ? "" : Files.readString(file);
                if (!existing.contains("## " + section)) {
                    sb.append("\n## ").append(section).append("\n");
                }

                sb.append("\n### ").append(pointId).append("\n");
                sb.append(content).append("\n");

                Files.writeString(file, sb.toString(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to write decision file: " + file, e);
            }
        }
    }
}
