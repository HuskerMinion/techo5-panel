package app.techo5.panel;

import java.util.ArrayDeque;

/** Android limits each app to five scan registrations per rolling 30 seconds. */
final class ScanStartBudget {
    // A small margin avoids races with the Bluetooth service's clock/window boundary.
    static final long WINDOW_MS = 31_000;
    private final ArrayDeque<Long> starts = new ArrayDeque<>();

    long delay(long now) {
        while (!starts.isEmpty() && now - starts.peekFirst() >= WINDOW_MS) starts.removeFirst();
        return starts.size() < 5 ? 0 : Math.max(0, starts.peekFirst() + WINDOW_MS - now);
    }

    void started(long now) {
        if (delay(now) != 0) throw new IllegalStateException("scan registration budget exhausted");
        starts.addLast(now);
    }
}
