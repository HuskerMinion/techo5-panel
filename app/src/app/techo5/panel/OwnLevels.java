package app.techo5.panel;

/**
 * Which media volume changes are this app's own. Android reports a change late, on another thread, and
 * one report can arrive after the app has already set another level: a level held for an alarm, let go
 * a moment later, would come back as the alarm level after the release and be taken for somebody
 * turning the tablet up. So a level the app set itself in the last WINDOW_MS is its own whenever it is reported,
 * as is the level last set or reported, which Android may report more than once, until a person's
 * change is reported. Anything else is a person's, at once.
 *
 * Plain Java, no Android, so it can be tested on its own. Not thread safe: the caller holds its lock.
 */
final class OwnLevels {
    // Long enough for a busy tablet's late broadcast; a person pressing to one of these levels within
    // it is the rare loss, and a press to any other level still goes through.
    static final long WINDOW_MS = 5000;

    // The levels set recently: index -> when. Few: a hold and its release, a slider dragged.
    private final java.util.HashMap<Integer, Long> setAt = new java.util.HashMap<>();
    private int last = -1;

    /** The app set the volume to index at nowMs. */
    void set(int index, long nowMs) {
        setAt.put(index, nowMs);
        last = index;
        prune(nowMs);
    }

    /**
     * Android reported the volume at index, at nowMs: true when it is a person's change to pass on,
     * false when it is the app's own or the level already known.
     */
    boolean fromPerson(int index, long nowMs) {
        prune(nowMs);
        if (index == last) return false;
        Long at = setAt.get(index);
        if (at != null && nowMs - at <= WINDOW_MS) return false;
        // Android reports changes in the order they happened, so after a person's, the app's earlier
        // ones have mostly come through: a person going back to one of those levels is a person. A
        // report still in flight from just before the press is the one this can let through.
        setAt.clear();
        last = index;
        return true;
    }

    private void prune(long nowMs) {
        java.util.Iterator<java.util.Map.Entry<Integer, Long>> it = setAt.entrySet().iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().getValue() > WINDOW_MS) it.remove();
        }
    }
}
