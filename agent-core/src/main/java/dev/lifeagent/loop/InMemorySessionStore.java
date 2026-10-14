package dev.lifeagent.loop;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link SessionStore} that keeps sessions in a map.
 *
 * <p>For tests and single-process runs; it does not survive a restart and has no TTL. Both are
 * deliberate - the durable store is a later card, and pretending otherwise here would hide the
 * missing expiry behind a green test.</p>
 *
 * <p>Stores a {@link LoopSession#copy() copy}, so a caller cannot mutate what the store holds by
 * keeping a reference to the session it saved.</p>
 */
public final class InMemorySessionStore implements SessionStore {

    private final Map<String, LoopSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(LoopSession session) {
        Objects.requireNonNull(session, "session");
        sessions.put(session.sessionId(), session.copy());
    }

    @Override
    public Optional<LoopSession> load(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return Optional.ofNullable(sessions.get(sessionId)).map(LoopSession::copy);
    }

    @Override
    public boolean remove(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return sessions.remove(sessionId) != null;
    }

    /** @return how many sessions are stored. */
    public int size() {
        return sessions.size();
    }
}
