package cc.carm.outsource.plugin.coreprotectaddon.manager;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/** Uses only duplicates of CoreProtect's already-open native database. Never opens a file or loads a driver. */
public final class DuckDBReadOnlyDataSource implements DataSource, AutoCloseable {
    @FunctionalInterface interface ConnectionFactory { Connection duplicate() throws Exception; }
    private final ConnectionFactory factory;
    private final Path databasePath;
    private final String tablePrefix;
    private final Set<Connection> connections = new HashSet<>();
    private boolean closed;

    public static DuckDBReadOnlyDataSource fromCoreProtect() throws Exception {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (plugin == null || !plugin.isEnabled())
            throw new SQLException("DuckDB 模式需要先启用支持 DuckDB 的 CoreProtect。");
        return fromRuntime(plugin.getClass().getClassLoader(), plugin.getDataFolder().toPath(), plugin::isEnabled);
    }

    // Kept separate from Bukkit so the exact supplied CoreProtect JAR can be tested in isolation.
    static DuckDBReadOnlyDataSource fromRuntime(ClassLoader loader, Path dataFolder,
                                                java.util.function.BooleanSupplier enabled) throws Exception {
        Class<?> config = Class.forName("net.coreprotect.config.ConfigHandler", false, loader);
        Field type = config.getField("databaseType");
        if (!"DUCKDB".equals(String.valueOf(type.get(null))))
            throw new SQLException("CoreProtect 当前未使用 DuckDB；请以实际运行的数据库类型配置 Addon。");
        Path path = verifiedPath(dataFolder, Path.of((String) config.getField("path").get(null))
                .resolve((String) config.getField("duckdb").get(null)));
        String prefix = (String) config.getField("prefix").get(null);
        Class<?> database = Class.forName("net.coreprotect.database.DuckDBDatabase", false, loader);
        Field rootField = database.getDeclaredField("rootConnection");
        rootField.setAccessible(true);
        final Connection root;
        synchronized (database) { root = (Connection) rootField.get(null); }
        if (root == null || root.isClosed())
            throw new SQLException("CoreProtect 的 DuckDB 尚未打开；Addon 不会创建或打开数据库文件。");
        Method duplicate = root.getClass().getMethod("duplicate");
        return new DuckDBReadOnlyDataSource(() -> {
            // The same monitor is used by CoreProtect open/close. Never invoke its getConnection(), which can create a file.
            synchronized (database) {
                if (!enabled.getAsBoolean() || !"DUCKDB".equals(String.valueOf(type.get(null)))
                        || rootField.get(null) != root || root.isClosed()
                        || !prefix.equals(config.getField("prefix").get(null)))
                    throw new SQLException("CoreProtect 数据库已关闭或切换，请重启服务器后再查询。");
                return (Connection) invoke(duplicate, root, null);
            }
        }, path, prefix);
    }

    DuckDBReadOnlyDataSource(ConnectionFactory factory, Path path, String prefix) {
        this.factory = factory;
        this.databasePath = path;
        this.tablePrefix = prefix;
    }

    static Path verifiedPath(Path dataFolder, Path database) throws Exception {
        Path folder = dataFolder.toRealPath();
        Path file = database.toRealPath(); // Missing files fail; never mkdir/create/connect by path.
        if (!file.startsWith(folder) || !Files.isRegularFile(file))
            throw new SQLException("DuckDB 文件必须是 CoreProtect 数据目录内的现有文件：" + database);
        return file;
    }

    public Path databasePath() { return databasePath; }
    public String tablePrefix() { return tablePrefix; }

    @Override public synchronized Connection getConnection() throws SQLException {
        if (closed) throw new SQLException("DuckDB 查询数据源已关闭。");
        Connection connection = null;
        try {
            connection = factory.duplicate();
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                // setReadOnly(true) is unsupported on duplicates. This is an engine-enforced read-only transaction.
                statement.execute("BEGIN TRANSACTION READ ONLY");
                try (ResultSet rs = statement.executeQuery(
                        "SELECT path FROM duckdb_databases() WHERE database_name = current_database()")) {
                    if (!rs.next() || rs.getString(1) == null
                            || !databasePath.equals(Path.of(rs.getString(1)).toRealPath()))
                        throw new SQLException("DuckDB 活动连接与 CoreProtect 数据文件路径不一致，已拒绝查询。");
                }
            }
            connections.add(connection);
            return guard(connection, Connection.class, connection, null);
        } catch (Exception ex) {
            if (connection != null) try { connection.close(); } catch (SQLException close) { ex.addSuppressed(close); }
            if (ex instanceof SQLException sql) throw sql;
            throw new SQLException("无法建立 CoreProtect DuckDB 只读事务：" + ex.getMessage(), ex);
        }
    }

    /** Prevents callers from ending the read-only transaction or escaping to a writable native connection. */
    private <T> T guard(T target, Class<T> api, Connection owned, Object parent) {
        return api.cast(Proxy.newProxyInstance(DuckDBReadOnlyDataSource.class.getClassLoader(), new Class<?>[]{api},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("unwrap")) throw new SQLFeatureNotSupportedException("Native DuckDB access is not exposed");
                    if (name.equals("isWrapperFor")) return false;
                    if (target instanceof Connection) {
                        if (name.equals("close")) {
                            try { owned.close(); } finally { synchronized (this) { connections.remove(owned); } }
                            return null;
                        }
                        if (name.equals("isReadOnly")) return true;
                        if (name.equals("getAutoCommit")) return false;
                        if (name.equals("setReadOnly") && Boolean.TRUE.equals(args[0])) return null;
                        if (name.equals("commit") || name.equals("rollback") || name.startsWith("set")
                                || name.equals("prepareCall") || name.equals("releaseSavepoint"))
                            throw new SQLException("DuckDB 查询连接只允许在既有只读事务中执行 SELECT。");
                        if (name.equals("prepareStatement")) requireSelect((String) args[0]);
                    }
                    if (target instanceof Statement) {
                        if (name.equals("getConnection")) return parent;
                        if (name.contains("Batch") || name.startsWith("executeUpdate") || name.startsWith("executeLargeUpdate"))
                            throw new SQLException("DuckDB 查询连接不允许更新或批处理。");
                        if (name.startsWith("execute") && args != null && args.length > 0 && args[0] instanceof String sql)
                            requireSelect(sql);
                    }
                    if (target instanceof DatabaseMetaData && name.equals("getConnection")) return parent;
                    if (target instanceof ResultSet && name.equals("getStatement")) return parent;
                    Object result = invoke(method, target, args);
                    if (result instanceof PreparedStatement statement) return guard(statement, PreparedStatement.class, owned, proxy);
                    if (result instanceof Statement statement) return guard(statement, Statement.class, owned, proxy);
                    if (result instanceof DatabaseMetaData meta) return guard(meta, DatabaseMetaData.class, owned, proxy);
                    if (result instanceof ResultSet rows) return guard(rows, ResultSet.class, owned,
                            target instanceof Statement ? proxy : null);
                    return result;
                }));
    }

    private static void requireSelect(String sql) throws SQLException {
        // Addon supplies the SQL structure; all user input is bound separately. No raw SQL/transaction API is provided.
        if (sql == null || !sql.stripLeading().regionMatches(true, 0, "SELECT ", 0, 7) || sql.indexOf(';') >= 0)
            throw new SQLException("DuckDB 查询连接只允许单条 SELECT。");
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            if (ex.getCause() instanceof Error error) throw error;
            throw ex;
        }
    }

    @Override public synchronized void close() throws SQLException {
        closed = true;
        SQLException failure = null;
        for (Connection connection : connections) {
            try { connection.close(); }
            catch (SQLException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        }
        connections.clear(); // CoreProtect's root connection is never owned or closed by Addon.
        if (failure != null) throw failure;
    }

    @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter writer) { }
    @Override public void setLoginTimeout(int seconds) { }
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() { return Logger.getLogger("CoreProtectAddon"); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
}
