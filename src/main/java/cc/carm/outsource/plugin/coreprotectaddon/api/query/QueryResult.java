package cc.carm.outsource.plugin.coreprotectaddon.api.query;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record QueryResult(
        boolean success,
        @NotNull String message,
        @Nullable String errorCode,
        @Nullable QueryType queryType,
        int page,
        int pageSize,
        int count,
        long costMs,
        @NotNull List<QueryRecord> records
) {

    public QueryResult {
        message = message == null ? "" : message;
        records = List.copyOf(records == null ? List.of() : records);
    }

    public static @NotNull QueryResult success(
            @NotNull QueryType queryType, int page, int pageSize, int count, long costMs, @NotNull List<QueryRecord> records
    ) {
        String message = records.isEmpty() ? "no records found" : "ok";
        return new QueryResult(true, message, null, queryType, page, pageSize, count, costMs, records);
    }

    public static @NotNull QueryResult failure(
            @Nullable QueryType queryType, @NotNull String errorCode, @NotNull String message, int page, int pageSize, long costMs
    ) {
        return new QueryResult(false, message, errorCode, queryType, page, pageSize, 0, costMs, List.of());
    }
}
