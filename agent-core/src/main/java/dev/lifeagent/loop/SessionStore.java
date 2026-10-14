package dev.lifeagent.loop;

import java.util.Optional;

/**
 * Where a suspended session lives between "waiting for the user" and "resumed".
 *
 * <p>W2-3 ships only {@link InMemorySessionStore}. Redis (plus the plan's Δ5 TTL and per-user cap)
 * is a later card, which is exactly why the loop depends on this interface and not on a store
 * implementation.</p>
 */
public interface SessionStore {

    /**
     * Saves or replaces a session.
     *
     * @param session the session to persist
     */
    void save(LoopSession session);

    /**
     * @param sessionId the session to look up
     * @return the saved session, or empty when there is none
     */
    Optional<LoopSession> load(String sessionId);

    /**
     * Removes a session.
     *
     * @param sessionId the session to drop
     * @return {@code true} when something was removed
     */
    boolean remove(String sessionId);
}
