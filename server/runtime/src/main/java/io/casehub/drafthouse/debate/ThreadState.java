package io.casehub.drafthouse.debate;

import java.util.Map;

public record ThreadState(Map<String, ThreadView> threads) {
    public static ThreadState empty() {
        return new ThreadState(Map.of());
    }
}
