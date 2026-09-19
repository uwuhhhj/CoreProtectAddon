package cc.carm.outsource.plugin.coreprotectaddon.manager;

import cc.carm.lib.easysql.EasySQL;
import cc.carm.lib.easysql.api.SQLManager;
import cc.carm.outsource.plugin.coreprotectaddon.Main;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig;
import cc.carm.outsource.plugin.coreprotectaddon.data.UserKey;
import cc.carm.outsource.plugin.coreprotectaddon.data.UserKeyType;
import cc.carm.outsource.plugin.coreprotectaddon.service.CoreProtectQueryService.Tables;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryLimits;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class DataManager {

    protected SQLManager sqlManager;
    private DataSource dataSource;
    private final Tables tables;
    protected Cache<String, UserKey> userCache = CacheBuilder.newBuilder()
            .expireAfterAccess(30, TimeUnit.MINUTES).build();

    public DataManager() throws Exception {
        try {
            DatabaseType type = DatabaseType.parse(PluginConfig.DATABASE_TYPE.getNotNull());
            Main.info("尝试连接到数据库：" + type + "...");
            if (type == DatabaseType.DUCKDB) {
                DuckDBReadOnlyDataSource source = DuckDBReadOnlyDataSource.fromCoreProtect();
                this.dataSource = source;
                this.tables = Tables.prefixed(source.tablePrefix());
                Main.info("DuckDB 只读查询目标：" + source.databasePath() + "；表前缀：" + source.tablePrefix(),
                        "复用 CoreProtect 已打开的数据库；每次查询使用独立 READ ONLY 事务。");
                try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(QueryLimits.configured().timeoutSeconds());
                    validateTables(statement, tables, "\"");
                }
            } else {
                this.tables = Tables.configured();
                if (type == DatabaseType.CLICKHOUSE) {
                    ClickHouseConnectionSettings settings = new ClickHouseConnectionSettings(PluginConfig.CLICKHOUSE_HOST.getNotNull(),
                            PluginConfig.CLICKHOUSE_PORT.getNotNull(), PluginConfig.CLICKHOUSE_DATABASE.getNotNull(),
                            PluginConfig.CLICKHOUSE_USERNAME.getNotNull(), PluginConfig.CLICKHOUSE_PASSWORD.getNotNull(),
                            PluginConfig.CLICKHOUSE_TLS.getNotNull(), QueryLimits.configured().timeoutSeconds());
                    Main.info("ClickHouse 查询目标：" + settings.jdbcUrl() + "；用户：" + PluginConfig.CLICKHOUSE_USERNAME.getNotNull()
                            + "；密码状态：" + (PluginConfig.CLICKHOUSE_PASSWORD.getNotNull().isEmpty() ? "空密码" : "已设置"));
                    this.dataSource = settings.createDataSource();
                    validateClickHouse(this.dataSource, tables, QueryLimits.configured().timeoutSeconds());
                } else {
                    this.sqlManager = EasySQL.createManager(
                            PluginConfig.DATABASE.DRIVER_NAME.getNotNull(), PluginConfig.DATABASE.buildJDBC(),
                            PluginConfig.DATABASE.USERNAME.getNotNull(), PluginConfig.DATABASE.PASSWORD.getNotNull()
                    );
                    this.sqlManager.setDebugMode(() -> Main.getInstance().isDebugging());
                    this.dataSource = this.sqlManager.getDataSource();
                }
            }
        } catch (Exception exception) {
            shutdown();
            throw new Exception(connectionFailureMessage(exception), exception);
        }
    }


    public void shutdown() {
        if (this.sqlManager != null) EasySQL.shutdownManager(sqlManager);
        if (this.dataSource instanceof DuckDBReadOnlyDataSource source) {
            try { source.close(); }
            catch (SQLException ex) { Main.severe("关闭 DuckDB 查询连接失败：" + ex.getMessage()); }
        }
        this.sqlManager = null;
        // DataSourceImpl owns no pool; each JDBC connection owns and closes its HTTP client.
        this.dataSource = null;
        this.userCache.invalidateAll();
    }

    /** Legacy MySQL-only accessor. Use dataSource() for queries that support all engines. */
    public SQLManager sql() {
        if (this.sqlManager == null) throw new IllegalStateException("当前数据库不使用 EasySQL；请使用 dataSource()。");
        return this.sqlManager;
    }

    public DataSource dataSource() { return this.dataSource; }
    public Tables tables() { return this.tables; }

    static String connectionFailureMessage(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 516) {
                return "ClickHouse 认证失败（516）。请核对 plugins/CoreProtectAddon/config.yml 顶层的 "
                        + "clickhouse-host、clickhouse-port、clickhouse-username 和 clickhouse-password；"
                        + "Addon 不会自动读取 CoreProtect 的连接配置。";
            }
        }
        if ("duckdb".equalsIgnoreCase(PluginConfig.DATABASE_TYPE.getNotNull().trim()))
            return "DuckDB 只读连接失败：" + failure.getMessage();
        return "无法连接到数据库，请检查配置文件。";
    }

    static void validateClickHouse(DataSource source, Tables tables, int timeout) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (ResultSet rs = statement.executeQuery("SELECT version()")) {
                if (!rs.next()) throw new SQLException("ClickHouse 未返回版本号。");
                String[] parts = rs.getString(1).split("\\.");
                if (parts.length < 2 || Integer.parseInt(parts[0]) < 25
                        || (Integer.parseInt(parts[0]) == 25 && Integer.parseInt(parts[1]) < 6))
                    throw new SQLException("CoreProtect ClickHouse 模式要求 ClickHouse 25.6 或更新版本。");
            }
            // Only inspect the compatibility views. Never read event_data directly: that bypasses FINAL semantics.
            validateTables(statement, tables, "`");
        }
    }

    static void validateTables(Statement statement, Tables tables, String quote) throws SQLException {
        List<String> names = List.of(tables.users(), tables.chat(), tables.command(), tables.item(),
                tables.container(), tables.materials(), tables.worlds(),tables.block(),tables.entities(),tables.session(),tables.sign(),tables.username());
        List<String> columns = List.of("rowid,user,uuid", "rowid,time,user,wid,x,y,z,message",
                "rowid,time,user,wid,x,y,z,message", "rowid,time,user,wid,x,y,z,type,amount,action,data,rolled_back",
                "rowid,time,user,wid,x,y,z,type,amount,action,metadata,rolled_back", "id,material", "id,world",
                "rowid,time,user,wid,x,y,z,type,data,action,rolled_back","id,entity",
                "rowid,time,user,wid,x,y,z,action","rowid,time,user,wid,x,y,z,action,face,line_1,line_2,line_3,line_4,line_5,line_6,line_7,line_8",
                "rowid,time,uuid,user");
        for (int i = 0; i < names.size(); i++) {
            String query = "SELECT " + quote + columns.get(i).replace(",", quote + "," + quote) + quote + " FROM " + quote
                    + names.get(i).replace(".", quote + "." + quote) + quote + " LIMIT 0";
            try (ResultSet ignored = statement.executeQuery(query)) { }
            catch (SQLException ex) {
                throw new SQLException("无法读取 CoreProtect 表或兼容视图 " + names.get(i)
                        + "；请确认数据库、表前缀、SELECT 权限及 CoreProtect 初始化状态。", ex);
            }
        }
    }

    public <K> @Nullable UserKey getUser(UserKeyType<K> type, K param) {
        String cacheKey = (type == UserKeyType.ID ? "#" : "") + param.toString();
        // Check cache
        if (this.userCache != null) {
            UserKey cached = this.userCache.getIfPresent(cacheKey);
            if (cached != null) return cached;
        }

        String query = "SELECT `rowid`,`uuid`,`user` FROM `" + tables.users().replace(".", "`.`")
                + "` WHERE `" + type.dataKey() + "`=? LIMIT 1";
        if (dataSource instanceof DuckDBReadOnlyDataSource) query = query.replace('`', '"');
        try (Connection connection = dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setQueryTimeout(QueryLimits.configured().timeoutSeconds());
            statement.setObject(1, param instanceof java.util.UUID ? param.toString() : param);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                String uuid = rs.getString("uuid");
                UserKey key = new UserKey(rs.getLong("rowid"), uuid == null || uuid.isBlank() ? null : java.util.UUID.fromString(uuid), rs.getString("user"));
                this.userCache.put(cacheKey, key);
                return key;
            }
        } catch (SQLException ex) {
            Main.severe("玩家查询失败：state=" + ex.getSQLState() + ", code=" + ex.getErrorCode());
            return null;
        }

    }

    public void cache(@NotNull UserKey key) {
        if (this.userCache != null) {
            this.userCache.put("#" + key.id(), key);
            if (key.uuid() != null) {
                this.userCache.put(key.uuid().toString(), key);
            }
            this.userCache.put(key.name(), key);
        }
    }


}
