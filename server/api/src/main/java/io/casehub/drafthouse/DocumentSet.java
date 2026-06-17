package io.casehub.drafthouse;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public class DocumentSet {

    private final CopyOnWriteArrayList<DocumentEntry> documents = new CopyOnWriteArrayList<>();
    private volatile ComparisonPair currentComparison;

    public record DocumentEntry(String path, String label) {
        public DocumentEntry {
            Objects.requireNonNull(path, "path");
            if (path.isBlank()) throw new IllegalArgumentException("path must be non-blank");
            Objects.requireNonNull(label, "label");
        }
    }

    public record ComparisonPair(String pathA, String pathB) {}

    public boolean add(String path, String label) {
        for (DocumentEntry e : documents) {
            if (e.path().equals(path)) return false;
        }
        documents.add(new DocumentEntry(path, label));
        return true;
    }

    public boolean remove(String path) {
        boolean removed = documents.removeIf(e -> e.path().equals(path));
        if (removed) {
            ComparisonPair cp = currentComparison;
            if (cp != null && (path.equals(cp.pathA()) || path.equals(cp.pathB()))) {
                currentComparison = null;
            }
        }
        return removed;
    }

    public List<DocumentEntry> documents() {
        return List.copyOf(documents);
    }

    public Optional<DocumentEntry> primary() {
        return documents.isEmpty() ? Optional.empty() : Optional.of(documents.get(0));
    }

    public void setComparison(String pathA, String pathB) {
        currentComparison = new ComparisonPair(pathA, pathB);
    }

    public void clearComparison() {
        currentComparison = null;
    }

    public ComparisonPair currentComparison() {
        return currentComparison;
    }

    public static DocumentSet copyOf(DocumentSet source) {
        var copy = new DocumentSet();
        for (DocumentEntry e : source.documents) {
            copy.documents.add(e);
        }
        copy.currentComparison = source.currentComparison;
        return copy;
    }
}
