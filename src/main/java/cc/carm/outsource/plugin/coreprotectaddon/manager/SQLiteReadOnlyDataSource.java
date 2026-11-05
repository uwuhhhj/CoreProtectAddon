package cc.carm.outsource.plugin.coreprotectaddon.manager;

import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/** Independent read-only connections to CoreProtect's existing SQLite file. */
public final class SQLiteReadOnlyDataSource implements DataSource, AutoCloseable {
    @FunctionalInterface private interface RuntimeCheck { void check() throws Exception; }
    private final Path databasePath;
    private final String tablePrefix;
    private final int timeoutSeconds;
    private final RuntimeCheck runtimeCheck;
    private final Set<Connection> connections = new HashSet<>();
    private boolean closed;

    static SQLiteReadOnlyDataSource fromRuntime(ClassLoader loader, Path dataFolder,
                                                BooleanSupplier enabled, int timeoutSeconds) throws Exception {
        Class<?> config = Class.forName("net.coreprotect.config.ConfigHandler", false, loader);
        if (!enabled.getAsBoolean() || !"SQLITE".equals(String.valueOf(config.getField("databaseType").get(null))))
            throw new SQLException("SQLite 模式需要已启用且正在使用 SQLite 的 CoreProtect。");
        Path path = verifiedPath(dataFolder, configuredPath(config));
        String prefix = (String) config.getField("prefix").get(null);
        return new SQLiteReadOnlyDataSource(path, prefix, timeoutSeconds, () -> {
            if (!enabled.getAsBoolean() || !"SQLITE".equals(String.valueOf(config.getField("databaseType").get(null)))
                    || !prefix.equals(config.getField("prefix").get(null))
                    || !path.equals(verifiedPath(dataFolder, configuredPath(config))))
                throw new SQLException("CoreProtect 数据库已关闭或切换，请执行 /coq reload 后再查询。");
        });
    }

    private static Path configuredPath(Class<?> config) throws ReflectiveOperationException {
        return Path.of((String) config.getField("path").get(null))
                .resolve((String) config.getField("sqlite").get(null));
    }

    private static Path verifiedPath(Path folder, Path database) throws Exception {
        Path path = database.toRealPath();
        if (!path.startsWith(folder.toRealPath()) || !Files.isRegularFile(path))
            throw new SQLException("SQLite 文件必须是 CoreProtect 数据目录内的现有文件。");
        return path;
    }

    private SQLiteReadOnlyDataSource(Path path, String prefix, int timeoutSeconds, RuntimeCheck runtimeCheck) {
        this.databasePath = path;
        this.tablePrefix = prefix;
        this.timeoutSeconds = timeoutSeconds;
        this.runtimeCheck = runtimeCheck;
    }

    public String tablePrefix() { return tablePrefix; }

    @Override public synchronized Connection getConnection() throws SQLException {
        if (closed) throw new SQLException("SQLite 查询数据源已关闭。");
        Connection connection = null;
        try {
            runtimeCheck.check();
            for (var iterator = connections.iterator(); iterator.hasNext(); ) {
                if (iterator.next().isClosed()) iterator.remove();
            }
            SQLiteConfig config = new SQLiteConfig();
            config.setReadOnly(true);
            config.setBusyTimeout((int) Math.min(Integer.MAX_VALUE, timeoutSeconds * 1000L));
            // URI escaping preserves spaces, # and ? in paths; mode=ro also forbids creating missing files.
            connection = new JDBC().connect("jdbc:sqlite:" + databasePath.toUri().toASCIIString() + "?mode=ro", config.toProperties());
            if (connection == null) throw new SQLException("SQLite JDBC 未接受数据库地址。");
            SQLiteQuerySupport.configure(connection, System.nanoTime() + timeoutSeconds * 1_000_000_000L);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only=ON");
            }
            runtimeCheck.check();
            connections.add(connection);
            return connection;
        } catch (Exception ex) {
            if (connection != null) try { connection.close(); } catch (SQLException close) { ex.addSuppressed(close); }
            if (ex instanceof SQLException sql) throw sql;
            throw new SQLException("无法建立 CoreProtect SQLite 只读连接：" + ex.getMessage(), ex);
        }
    }

    @Override public synchronized void close() throws SQLException {
        closed = true;
        SQLException failure = null;
        for (Connection connection : connections) {
            try { connection.close(); }
            catch (SQLException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        }
        connections.clear(); // These connections belong only to Addon; CoreProtect's writer is untouched.
        if (failure != null) throw failure;
    }

    @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter writer) { }
    @Override public void setLoginTimeout(int seconds) { }
    @Override public int getLoginTimeout() { return timeoutSeconds; }
    @Override public Logger getParentLogger() { return Logger.getLogger("CoreProtectAddon"); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
}
