package cc.carm.outsource.plugin.coreprotectaddon.service;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.*;
import cc.carm.outsource.plugin.coreprotectaddon.command.LookupParameters;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig;
import cc.carm.outsource.plugin.coreprotectaddon.manager.DataManager;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Independent, read-only CoreProtect SQL queries. Never dispatches a CoreProtect command. */
public class CoreProtectQueryService {
    public static final String ERROR_INVALID_QUERY_TYPE = "INVALID_QUERY_TYPE";
    public static final String ERROR_INVALID_TIME = "INVALID_TIME";
    public static final String ERROR_INVALID_PAGE = "INVALID_PAGE";
    public static final String ERROR_UNKNOWN_USER = "UNKNOWN_USER";
    public static final String ERROR_QUERY_FAILED = "QUERY_FAILED";
    private final DataSource dataSource;
    private final Supplier<Tables> tables;
    private final Supplier<QueryLimits> limits;

    public CoreProtectQueryService(DataManager manager) {
        this(manager.sql().getDataSource(), Tables::configured, QueryLimits::configured);
    }

    public CoreProtectQueryService(DataSource dataSource, Supplier<Tables> tables, Supplier<QueryLimits> limits) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.tables = tables;
        this.limits = limits;
    }

    public record Tables(String users, String chat, String command, String item, String container,
                         String materials, String worlds) {
        public Tables {
            for (String name : List.of(users, chat, command, item, container, materials, worlds)) quote(name);
        }
        public static Tables configured() {
            return new Tables(PluginConfig.DATABASE.TABLES.USERS.resolve(), PluginConfig.DATABASE.TABLES.CHAT.resolve(),
                    PluginConfig.DATABASE.TABLES.COMMAND.resolve(), PluginConfig.DATABASE.TABLES.ITEM.resolve(),
                    PluginConfig.DATABASE.TABLES.CONTAINER.resolve(), PluginConfig.DATABASE.TABLES.MATERIALS.resolve(),
                    PluginConfig.DATABASE.TABLES.WORLDS.resolve());
        }
        String forAction(LookupAction action) {
            return quote(switch (action.family()) {
                case "chat" -> chat;
                case "command" -> command;
                case "item" -> item;
                case "container" -> container;
                default -> throw new QueryException(ERROR_INVALID_QUERY_TYPE, "查询类型无效。");
            });
        }
    }

    /** Synchronous API: callers must use their own worker, never the server main thread. */
    public QueryResult query(QueryRequest request) {
        QueryType type = request.queryType();
        LookupResult result = lookup(new LookupRequest(type == null ? null : LookupAction.byId(type.id()),
                request.user() == null ? List.of() : List.of(request.user()), request.time(), request.page(),
                request.pageSize(), List.of(), List.of(), request.content()));
        if (!result.success()) return QueryResult.failure(type, result.errorCode(), result.message(),
                result.page(), result.pageSize(), result.costMs());
        List<QueryRecord> records = result.records().stream().map(r ->
                new QueryRecord(r.time(), r.playerId(), r.playerName(), r.message())).toList();
        return new QueryResult(true, result.truncated() ? "仅展示前 " + result.total() + " 条" :
                (records.isEmpty() ? "no records found" : "ok"), null, type,
                result.page(), result.pageSize(), records.size(), result.costMs(), records);
    }

    public LookupResult lookup(LookupRequest request) {
        return lookup(request, Instant.now().getEpochSecond());
    }

    /** Anchor is fixed for a sender's pagination session. No metadata columns are read. */
    public LookupResult lookup(LookupRequest request, long anchor) {
        long started = System.nanoTime();
        int page = request.page() == null ? 1 : request.page();
        int size = request.pageSize() == null ? 15 : request.pageSize();
        try {
            QueryLimits rules = limits.get();
            size = rules.pageSize(request.pageSize());
            int offset = rules.offset(page, size);
            if (request.action() == null) throw new QueryException(ERROR_INVALID_QUERY_TYPE, "查询类型无效。");
            long[] window = rules.window(request.time(), anchor);
            validate(request);
            Tables names = tables.get();
            String table = names.forAction(request.action());
            try (Connection connection = dataSource.getConnection()) {
                TimedSelect sql = new TimedSelect(connection, started + rules.timeoutSeconds() * 1_000_000_000L);
                StringBuilder where = new StringBuilder(" WHERE `time` >= ? AND `time` <= ?");
                List<Object> values = new ArrayList<>(List.of(window[0], window[1]));
                Map<String, Long> users = resolveIds(sql, names.users(), "rowid", "user", request.users());
                for (String user : request.users()) {
                    if (!users.containsKey(user.toLowerCase(Locale.ROOT)))
                        throw new QueryException(ERROR_UNKNOWN_USER, "找不到玩家：" + user);
                }
                in(where, values, "user", users.values(), false);
                if (request.action().isItem()) {
                    // CoreProtect 23.2 ActionParser / LookupRaw: a:item excludes inventory-only actions 8..12.
                    if (request.action() == LookupAction.ITEM) in(where, values, "action", List.of(8, 9, 10, 11, 12), true);
                    else in(where, values, "action", request.action().actions(), false);
                    List<String> includes = request.include().stream().map(LookupParameters::material).toList();
                    List<String> excludes = request.exclude().stream().map(LookupParameters::material).toList();
                    Collection<Long> includeIds = resolveIds(sql, names.materials(), "id", "material", includes).values();
                    if (!includes.isEmpty() && includeIds.isEmpty()) where.append(" AND 1=0");
                    else in(where, values, "type", includeIds, false);
                    in(where, values, "type", resolveIds(sql, names.materials(), "id", "material", excludes).values(), true);
                }
                if (request.content() != null) {
                    where.append(" AND `message` REGEXP ?");
                    values.add(request.content());
                }
                int found = sql.read("SELECT COUNT(*) FROM (SELECT `rowid` FROM " + table + where +
                        " LIMIT " + (rules.maxResults() + 1) + ") AS coq_bounded", values, rs -> {
                    rs.next(); return rs.getInt(1);
                });
                int total = Math.min(found, rules.maxResults());
                boolean truncated = found > rules.maxResults();
                if ((total == 0 && page > 1) || (total > 0 && offset >= total))
                    throw new QueryException("PAGE_OUT_OF_RANGE", "页码超出查询结果范围。");
                List<RawRow> rows = List.of();
                if (total > 0) {
                    String columns = "`rowid`,`time`,`user`,`wid`,`x`,`y`,`z`," +
                            (request.action().isItem() ? "`type`,`amount`,`action`" : "`message`");
                    rows = sql.read("SELECT " + columns + " FROM " + table + where +
                            " ORDER BY `time` DESC, `rowid` DESC LIMIT " + offset + "," + Math.min(size, total - offset), values, rs -> {
                        List<RawRow> records = new ArrayList<>();
                        while (rs.next()) records.add(new RawRow(rs.getLong("rowid"), rs.getLong("time"), rs.getLong("user"),
                                rs.getLong("wid"), nullableInt(rs, "x"), nullableInt(rs, "y"), nullableInt(rs, "z"),
                                request.action().isItem() ? null : Objects.toString(rs.getString("message"), ""),
                                request.action().isItem() ? rs.getLong("type") : 0,
                                request.action().isItem() ? rs.getInt("amount") : 0,
                                request.action().isItem() ? rs.getInt("action") : 0));
                        return records;
                    });
                }
                Map<Long, String> playerNames = labels(sql, names.users(), "rowid", "user", rows.stream().map(RawRow::user).toList());
                Map<Long, String> worlds = labels(sql, names.worlds(), "id", "world", rows.stream().map(RawRow::world).toList());
                Map<Long, String> materials = request.action().isItem() ? labels(sql, names.materials(), "id", "material",
                        rows.stream().map(RawRow::type).toList()) : Map.of();
                List<LookupRecord> result = rows.stream().map(r -> new LookupRecord(r.id, r.time, r.user,
                        playerNames.getOrDefault(r.user, "未知用户 #" + r.user), worlds.getOrDefault(r.world, "未知世界 #" + r.world),
                        r.x, r.y, r.z, r.message, materials.getOrDefault(r.type, "未知物品 #" + r.type), r.amount, r.action)).toList();
                return new LookupResult(true, null, "ok", request.action(), page, size, total, truncated, elapsed(started), result);
            }
        } catch (QueryException ex) {
            return LookupResult.failure(request.action(), ex.code(), ex.getMessage(), page, size, elapsed(started));
        } catch (SQLException ex) {
            boolean timeout = isTimeout(ex);
            Logger.getLogger("CoreProtectAddon").warning("Lookup SQL failed: state=" + ex.getSQLState() + ", code=" + ex.getErrorCode());
            return LookupResult.failure(request.action(), timeout ? "QUERY_TIMEOUT" : ERROR_QUERY_FAILED,
                    timeout ? "查询超时，请缩小时间范围或增加筛选条件。" : "数据库查询失败，请检查日志与数据库配置。", page, size, elapsed(started));
        } catch (RuntimeException ex) {
            Logger.getLogger("CoreProtectAddon").warning("Lookup failed: " + ex.getClass().getSimpleName());
            return LookupResult.failure(request.action(), ERROR_QUERY_FAILED, "查询失败，请检查配置与日志。", page, size, elapsed(started));
        }
    }

    private static void validate(LookupRequest request) {
        for (List<String> list : List.of(request.users(), request.include(), request.exclude())) {
            if (list.size() > 100 || list.stream().anyMatch(s -> s == null || s.isBlank()))
                throw new QueryException("INVALID_PARAMETER", "条件列表无效或超过 100 项。");
        }
        request.include().forEach(LookupParameters::material);
        request.exclude().forEach(LookupParameters::material);
        if (!request.action().isItem() && (!request.include().isEmpty() || !request.exclude().isEmpty()))
            throw new QueryException("INCOMPATIBLE_PARAMETER", "聊天和命令查询不支持物品条件。");
        if (request.action().isItem() && request.content() != null)
            throw new QueryException("INCOMPATIBLE_PARAMETER", "content: 仅用于聊天和命令查询。");
    }

    private static Map<String, Long> resolveIds(TimedSelect sql, String table, String id, String label, List<String> names) throws SQLException {
        if (names.isEmpty()) return Map.of();
        return sql.read("SELECT `" + id + "`,`" + label + "` FROM " + quote(table) + " WHERE LOWER(`" + label + "`) IN (" + marks(names.size()) + ")",
                names.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList(), rs -> {
                    Map<String, Long> result = new LinkedHashMap<>();
                    while (rs.next()) result.put(rs.getString(label).toLowerCase(Locale.ROOT), rs.getLong(id));
                    return result;
                });
    }

    private static Map<Long, String> labels(TimedSelect sql, String table, String id, String label, List<Long> ids) throws SQLException {
        List<Long> unique = ids.stream().distinct().toList();
        if (unique.isEmpty()) return Map.of();
        return sql.read("SELECT `" + id + "`,`" + label + "` FROM " + quote(table) + " WHERE `" + id + "` IN (" + marks(unique.size()) + ")", unique, rs -> {
            Map<Long, String> result = new HashMap<>();
            while (rs.next()) result.put(rs.getLong(id), rs.getString(label));
            return result;
        });
    }

    private static void in(StringBuilder where, List<Object> values, String column, Collection<?> ids, boolean exclude) {
        if (ids.isEmpty()) return;
        where.append(" AND `").append(column).append(exclude ? "` NOT IN (" : "` IN (").append(marks(ids.size())).append(')');
        values.addAll(ids);
    }
    private static String marks(int count) { return String.join(",", Collections.nCopies(count, "?")); }
    private static String quote(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)?"))
            throw new QueryException("INVALID_CONFIG", "数据库表名配置无效。");
        return "`" + name.replace(".", "`.`") + "`";
    }
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column); return rs.wasNull() ? null : value;
    }
    private static boolean isTimeout(SQLException ex) {
        for (SQLException e = ex; e != null; e = e.getNextException()) {
            if (e instanceof SQLTimeoutException || "70100".equals(e.getSQLState()) || e.getErrorCode() == 1969 || e.getErrorCode() == 3024) return true;
        }
        return false;
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private record RawRow(long id, long time, long user, long world, Integer x, Integer y, Integer z,
                          String message, long type, int amount, int action) { }
    @FunctionalInterface interface Rows<T> { T read(ResultSet rs) throws SQLException; }

    /** A shared deadline covers count, details and dictionary lookups, including time spent acquiring a connection. */
    static final class TimedSelect {
        private final Connection connection;
        private final long deadline;
        private final boolean maria;
        TimedSelect(Connection connection, long deadline) throws SQLException {
            this.connection = connection;
            this.deadline = deadline;
            DatabaseMetaData meta = connection.getMetaData();
            this.maria = (meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion()).toLowerCase(Locale.ROOT).contains("mariadb");
        }
        <T> T read(String query, List<?> params, Rows<T> reader) throws SQLException {
            long remaining = (deadline - System.nanoTime()) / 1_000_000;
            if (remaining < 1) throw new SQLTimeoutException("Query deadline expired");
            String timed = maria ? "SET STATEMENT max_statement_time=" + (remaining / 1000.0) + " FOR " + query :
                    "SELECT /*+ MAX_EXECUTION_TIME(" + remaining + ") */" + query.substring("SELECT".length());
            try (PreparedStatement statement = connection.prepareStatement(timed)) {
                statement.setQueryTimeout((int) Math.max(1, (remaining + 999) / 1000));
                for (int i = 0; i < params.size(); i++) statement.setObject(i + 1, params.get(i));
                // Connector/J classifies SET STATEMENT as non-query even though MariaDB returns SELECT rows.
                if (!statement.execute()) throw new SQLException("Lookup did not return a result set");
                try (ResultSet rs = statement.getResultSet()) {
                    T result = reader.read(rs);
                    if (System.nanoTime() >= deadline) throw new SQLTimeoutException("Query deadline expired");
                    return result;
                }
            }
        }
    }
}
