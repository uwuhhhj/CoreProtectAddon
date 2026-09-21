package cc.carm.outsource.plugin.coreprotectaddon.service;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.*;
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
    private static final java.util.concurrent.Semaphore ACTIVE_QUERIES = new java.util.concurrent.Semaphore(2);
    public static final String ERROR_INVALID_QUERY_TYPE = "INVALID_QUERY_TYPE";
    public static final String ERROR_INVALID_TIME = "INVALID_TIME";
    public static final String ERROR_INVALID_PAGE = "INVALID_PAGE";
    public static final String ERROR_UNKNOWN_USER = "UNKNOWN_USER";
    public static final String ERROR_QUERY_FAILED = "QUERY_FAILED";
    private final DataSource dataSource;
    private final Supplier<Tables> tables;
    private final Supplier<QueryLimits> limits;
    private volatile ComponentMatcher componentMatcher = (rows,content,deadline) -> {
        throw new QueryException("COMPONENT_UNAVAILABLE","组件查询服务尚未初始化。");
    };
    private Supplier<Integer> componentMaxCandidates = () -> 2000;

    public CoreProtectQueryService(DataManager manager) {
        this(manager.dataSource(), manager::tables, manager::queryLimits);
        componentMaxCandidates = manager::componentMaxCandidates;
    }

    public void setComponentMatcher(ComponentMatcher matcher) { this.componentMatcher = Objects.requireNonNull(matcher); }

    public CoreProtectQueryService(DataSource dataSource, Supplier<Tables> tables, Supplier<QueryLimits> limits) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.tables = tables;
        this.limits = limits;
    }

    public record Tables(String users, String chat, String command, String item, String container,
                         String materials, String worlds, String block, String entities, String session,
                         String sign, String username) {
        public Tables(String users,String chat,String command,String item,String container,String materials,String worlds) {
            this(users,chat,command,item,container,materials,worlds,
                    sibling(item,"block"),sibling(item,"entity_map"),sibling(item,"session"),sibling(item,"sign"),sibling(item,"username_log"));
        }
        private static String sibling(String item,String suffix) {
            return (item.endsWith("item") ? item.substring(0,item.length()-4) : "co_") + suffix;
        }
        public Tables {
            for (String name : List.of(users, chat, command, item, container, materials, worlds,block,entities,session,sign,username)) quote(name);
        }
        public static Tables configured() {
            String prefix = PluginConfig.TABLE_PREFIX.getNotNull();
            if (!prefix.isEmpty()) return prefixed(prefix);
            return new Tables(PluginConfig.DATABASE.TABLES.USERS.resolve(), PluginConfig.DATABASE.TABLES.CHAT.resolve(),
                    PluginConfig.DATABASE.TABLES.COMMAND.resolve(), PluginConfig.DATABASE.TABLES.ITEM.resolve(),
                    PluginConfig.DATABASE.TABLES.CONTAINER.resolve(), PluginConfig.DATABASE.TABLES.MATERIALS.resolve(),
                    PluginConfig.DATABASE.TABLES.WORLDS.resolve(),PluginConfig.DATABASE.TABLES.BLOCK.resolve(),
                    PluginConfig.DATABASE.TABLES.ENTITIES.resolve(),PluginConfig.DATABASE.TABLES.SESSION.resolve(),
                    PluginConfig.DATABASE.TABLES.SIGN.resolve(),PluginConfig.DATABASE.TABLES.USERNAME.resolve());
        }
        public static Tables prefixed(String prefix) {
            if (prefix == null || !prefix.matches("[A-Za-z0-9_]*"))
                throw new QueryException("INVALID_CONFIG", "table-prefix 只能包含字母、数字和下划线。");
            return new Tables(prefix + "user", prefix + "chat", prefix + "command", prefix + "item",
                    prefix + "container", prefix + "material_map", prefix + "world");
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

    /** Anchor is fixed for a sender's pagination session. Component searches load bounded candidate batches. */
    public LookupResult lookup(LookupRequest request, long anchor) {
        return lookup(request,anchor,new QueryCancellation(limits.get().timeoutSeconds()));
    }

    public LookupResult lookup(LookupRequest request, long anchor, QueryCancellation cancellation) {
        long started = System.nanoTime();
        int page = request.page() == null ? 1 : request.page();
        int size = request.pageSize() == null ? 15 : request.pageSize();
        boolean acquired = false;
        try {
            if (org.bukkit.Bukkit.getServer() != null && org.bukkit.Bukkit.isPrimaryThread())
                throw new QueryException("ASYNC_REQUIRED","数据库查询必须在工作线程执行。");
            cancellation.check();
            if (!(acquired = ACTIVE_QUERIES.tryAcquire()))
                throw new QueryException("QUERY_BUSY","数据库查询繁忙，请稍后重试。");
            QueryLimits rules = limits.get();
            size = rules.pageSize(request.pageSize());
            int offset = rules.offset(page, size);
            if (request.action() == null) throw new QueryException(ERROR_INVALID_QUERY_TYPE, "查询类型无效。");
            long[] window = rules.window(request.time(), anchor);
            Tables names = tables.get();
            try (Connection connection = dataSource.getConnection()) {
                cancellation.check();
                TimedSelect sql = new TimedSelect(connection, cancellation);
                return new LookupPlan(sql,names,request,rules,window,componentMatcher,componentMaxCandidates.get())
                        .execute(page,size,offset,started);
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
        } finally {
            if (acquired) ACTIVE_QUERIES.release();
        }
    }

    private static String quote(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)?"))
            throw new QueryException("INVALID_CONFIG", "数据库表名配置无效。");
        return "`" + name.replace(".", "`.`") + "`";
    }
    static byte[] readItemMetadata(ResultSet rs, boolean clickhouse) throws SQLException {
        int column = rs.findColumn("item_metadata");
        byte[] bytes = rs.getBytes(column);
        // CoreProtect's ClickHouse compatibility views encode binary as Array(Int8):
        // [] = SQL NULL, [0, ...payload] = present (including an empty payload).
        if (clickhouse && rs.getMetaData().getColumnType(column) == Types.ARRAY && bytes != null) {
            if (bytes.length == 0) return null;
            if (bytes[0] != 0) throw new SQLException("Invalid CoreProtect binary marker");
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }
    private static boolean isTimeout(SQLException ex) {
        for (SQLException e = ex; e != null; e = e.getNextException()) {
            if (e instanceof SQLTimeoutException || "70100".equals(e.getSQLState())
                    || e.getErrorCode() == 1969 || e.getErrorCode() == 3024
                    || e.getErrorCode() == 159 || e.getErrorCode() == 160) return true;
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.util.concurrent.TimeoutException)
                    return true;
                // The ClickHouse driver can wrap server exceptions without preserving their numeric error code.
                if (cause instanceof com.clickhouse.client.api.ServerException server &&
                        (server.getCode() == 159 || server.getCode() == 160)) return true;
            }
        }
        return false;
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    @FunctionalInterface interface Rows<T> { T read(ResultSet rs) throws SQLException; }

    /** A shared deadline covers count, details and dictionary lookups, including time spent acquiring a connection. */
    static final class TimedSelect {
        private final Connection connection;
        final long deadline;
        private final QueryCancellation cancellation;
        private final boolean maria;
        final boolean clickhouse;
        final boolean duckdb;
        TimedSelect(Connection connection, long deadline) throws SQLException {
            this(connection,deadline,null);
        }
        TimedSelect(Connection connection, QueryCancellation cancellation) throws SQLException {
            this(connection,cancellation.deadline(),cancellation);
        }
        private TimedSelect(Connection connection, long deadline, QueryCancellation cancellation) throws SQLException {
            this.connection = connection;
            this.deadline = deadline;
            this.cancellation = cancellation;
            DatabaseMetaData meta = connection.getMetaData();
            String product = meta.getDatabaseProductName().toLowerCase(Locale.ROOT);
            this.clickhouse = product.contains("clickhouse");
            this.duckdb = product.contains("duckdb");
            this.maria = !clickhouse && !duckdb && (product + " " + meta.getDatabaseProductVersion()).toLowerCase(Locale.ROOT).contains("mariadb");
        }
        <T> T read(String query, List<?> params, Rows<T> reader) throws SQLException {
            if (cancellation != null) cancellation.check();
            long remaining = (deadline - System.nanoTime()) / 1_000_000;
            if (remaining < 1) throw new SQLTimeoutException("Query deadline expired");
            // Backticks occur only in generated identifiers; user strings are bound after SQL preparation.
            String timed = duckdb ? query.replace('`', '"') : clickhouse ? query + " SETTINGS max_execution_time=" + (remaining / 1000.0)
                    + ", timeout_overflow_mode='throw', timeout_before_checking_execution_speed=0" :
                    maria ? "SET STATEMENT max_statement_time=" + (remaining / 1000.0) + " FOR " + query :
                    "SELECT /*+ MAX_EXECUTION_TIME(" + remaining + ") */" + query.substring("SELECT".length());
            try (PreparedStatement statement = connection.prepareStatement(timed)) {
                statement.setQueryTimeout((int) Math.max(1, (remaining + 999) / 1000));
                for (int i = 0; i < params.size(); i++) statement.setObject(i + 1, params.get(i));
                // Connector/J classifies SET STATEMENT as non-query even though MariaDB returns SELECT rows.
                if (!statement.execute()) throw new SQLException("Lookup did not return a result set");
                try (ResultSet rs = statement.getResultSet()) {
                    T result = reader.read(rs);
                    if (cancellation != null) cancellation.check();
                    if (System.nanoTime() >= deadline) throw new SQLTimeoutException("Query deadline expired");
                    return result;
                }
            } catch (SQLException ex) {
                // DuckDB's JDBC timeout interrupts the query but reports a plain SQLException.
                if (duckdb && System.nanoTime() >= deadline) throw new SQLTimeoutException("DuckDB query deadline expired", ex);
                throw ex;
            }
        }
    }
}
