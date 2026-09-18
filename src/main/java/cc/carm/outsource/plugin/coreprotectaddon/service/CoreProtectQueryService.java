package cc.carm.outsource.plugin.coreprotectaddon.service;

import cc.carm.lib.easysql.api.builder.TableQueryBuilder;
import cc.carm.outsource.plugin.coreprotectaddon.Main;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.QueryRecord;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.QueryRequest;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.QueryResult;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.QueryType;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig;
import cc.carm.outsource.plugin.coreprotectaddon.data.UserKey;
import cc.carm.outsource.plugin.coreprotectaddon.data.UserKeyType;
import cc.carm.outsource.plugin.coreprotectaddon.manager.DataManager;
import cc.carm.outsource.plugin.coreprotectaddon.utils.TimeFormatUtils;
import org.jetbrains.annotations.NotNull;

import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class CoreProtectQueryService {

    public static final String ERROR_INVALID_QUERY_TYPE = "INVALID_QUERY_TYPE";
    public static final String ERROR_INVALID_TIME = "INVALID_TIME";
    public static final String ERROR_INVALID_PAGE = "INVALID_PAGE";
    public static final String ERROR_UNKNOWN_USER = "UNKNOWN_USER";
    public static final String ERROR_QUERY_FAILED = "QUERY_FAILED";

    private final @NotNull DataManager dataManager;

    public CoreProtectQueryService(@NotNull DataManager dataManager) {
        this.dataManager = dataManager;
    }

    public @NotNull QueryResult query(@NotNull QueryRequest request) {
        long startedAt = System.currentTimeMillis();

        QueryType queryType = request.queryType();
        int page = request.page() == null ? 1 : request.page();
        int pageSize = request.pageSize() == null ? PluginConfig.QUERY.PAGE_SIZE.resolve() : request.pageSize();

        if (queryType == null) {
            return QueryResult.failure(null, ERROR_INVALID_QUERY_TYPE, "missing queryType", page, pageSize, elapsed(startedAt));
        }
        if (page < 1 || pageSize < 1) {
            return QueryResult.failure(queryType, ERROR_INVALID_PAGE, "invalid page", page, pageSize, elapsed(startedAt));
        }

        try {
            TableQueryBuilder query = this.dataManager.sql().createQuery().inTable(queryType.tableName());

            String timeString = request.time();
            if (timeString != null) {
                Duration[] interval = TimeFormatUtils.parseInterval(timeString);
                if (interval == null) {
                    return QueryResult.failure(queryType, ERROR_INVALID_TIME, "invalid time", page, pageSize, elapsed(startedAt));
                }

                long a = System.currentTimeMillis() - interval[0].toMillis();
                if (interval[1].isZero()) {
                    query.addCondition("time", ">=", a / 1000);
                } else {
                    long b = System.currentTimeMillis() - interval[1].toMillis();
                    query.addCondition("time", ">=", Math.min(a, b) / 1000);
                    query.addCondition("time", "<=", Math.max(a, b) / 1000);
                }
            }

            if (request.content() != null) {
                query.addCondition("message", "REGEXP", request.content());
            }

            if (request.user() != null) {
                UserKey key = this.dataManager.getUser(UserKeyType.NAME, request.user());
                if (key == null) {
                    return QueryResult.failure(queryType, ERROR_UNKNOWN_USER, request.user(), page, pageSize, elapsed(startedAt));
                }
                query.addCondition("user", key.id());
            }

            query.setPageLimit((page - 1) * pageSize, pageSize);
            query.orderBy("time", !PluginConfig.QUERY.REVERSE_ORDER.resolve());

            List<QueryRecord> records = query.build().execute(sql -> {
                ResultSet rs = sql.getResultSet();
                List<QueryRecord> result = new ArrayList<>();

                while (rs.next()) {
                    long time = rs.getLong("time");
                    long userId = rs.getLong("user");
                    String message = rs.getString("message");
                    UserKey user = this.dataManager.getUser(UserKeyType.ID, userId);
                    String playerName = user == null ? "未知用户" : user.name();
                    result.add(new QueryRecord(time, userId, playerName, message == null ? "" : message));
                }
                return result;
            }, new ArrayList<>(), null);

            return QueryResult.success(queryType, page, pageSize, records.size(), elapsed(startedAt), records);
        } catch (Exception ex) {
            Main.debugging("查询失败: " + ex.getMessage());
            return QueryResult.failure(queryType, ERROR_QUERY_FAILED, ex.getMessage() == null ? "query failed" : ex.getMessage(),
                    page, pageSize, elapsed(startedAt));
        }
    }

    private static long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
