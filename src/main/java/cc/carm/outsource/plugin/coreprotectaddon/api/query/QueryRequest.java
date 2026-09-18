package cc.carm.outsource.plugin.coreprotectaddon.api.query;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record QueryRequest(
        @Nullable QueryType queryType,
        @Nullable String user,
        @Nullable String time,
        @Nullable Integer page,
        @Nullable Integer pageSize,
        @Nullable String content
) {

    public QueryRequest {
        user = normalize(user);
        time = normalize(time);
        content = normalize(content);
    }

    private static @Nullable String normalize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public static @NotNull QueryRequest of(@NotNull QueryType queryType) {
        return new QueryRequest(queryType, null, null, null, null, null);
    }
}
