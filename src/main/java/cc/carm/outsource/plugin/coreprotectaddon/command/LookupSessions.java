package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.LookupRequest;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** Accessed on the server thread only. Paging keeps the original time anchor and expiry. */
public final class LookupSessions {
    public record Session(LookupRequest request, long anchor, long expiresAt) { }
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Clock clock;
    private final long ttlMillis;
    private final int capacity;

    public LookupSessions(Clock clock, int ttlSeconds, int capacity) {
        if (ttlSeconds < 1 || capacity < 1) throw new IllegalArgumentException("Invalid session limits");
        this.clock = clock;
        this.ttlMillis = ttlSeconds * 1000L;
        this.capacity = capacity;
    }
    public Session start(String sender, LookupRequest request) {
        long now = clock.millis();
        // Keep expired entries until accessed/evicted so another sender's new query does not erase the expiry notice.
        sessions.remove(sender);
        while (sessions.size() >= capacity) sessions.remove(sessions.keySet().iterator().next());
        Session session = new Session(request, now / 1000, now + ttlMillis);
        sessions.put(sender, session);
        return session;
    }
    public Session require(String sender) {
        Session session = sessions.get(sender);
        if (session == null) throw new QueryException("NO_QUERY", "没有最近的查询，请先使用 /coq l <参数>。");
        if (session.expiresAt <= clock.millis()) {
            sessions.remove(sender);
            throw new QueryException("QUERY_EXPIRED", "查询已过期，请重新输入查询条件。");
        }
        return session;
    }
    public Session page(String sender, int page, int size) {
        Session old = require(sender);
        Session updated = new Session(old.request.withPage(page, size), old.anchor, old.expiresAt);
        sessions.put(sender, updated);
        return updated;
    }
}
