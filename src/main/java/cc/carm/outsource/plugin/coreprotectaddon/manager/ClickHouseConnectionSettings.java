package cc.carm.outsource.plugin.coreprotectaddon.manager;

import com.clickhouse.jdbc.DataSourceImpl;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.Properties;

/** Explicit HTTP(S) target; credentials are never included in the URL. */
public final class ClickHouseConnectionSettings {
    private final String jdbcUrl;
    private final Properties properties = new Properties();

    public ClickHouseConnectionSettings(String host, int port, String database, String username,
                                       String password, boolean tls, int timeoutSeconds) {
        if (host == null || host.isBlank() || !host.equals(host.trim()) || host.matches(".*[/\\\\?#@].*"))
            throw new IllegalArgumentException("clickhouse-host 必须是主机名或 IP，不含协议、端口或路径。");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("clickhouse-port 必须在 1–65535 之间。");
        if (database == null || !database.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("clickhouse-database 必须是有效的数据库标识符。");
        if (username == null || username.isBlank() || password == null)
            throw new IllegalArgumentException("ClickHouse 用户名不能为空，密码可为空字符串。");
        try {
            URI endpoint = new URI(tls ? "https" : "http", null, host, port, "/" + database, null, null);
            if (endpoint.getHost() == null) throw new URISyntaxException(host, "Missing host");
            jdbcUrl = "jdbc:clickhouse:" + endpoint.toASCIIString();
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("clickhouse-host 必须是主机名或 IP，不含协议、端口或路径。", ex);
        }
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        String timeout = Long.toString(Math.max(1, timeoutSeconds) * 1000L);
        properties.setProperty("connection_timeout", timeout);
        properties.setProperty("socket_timeout", timeout);
        properties.setProperty("retry", "0");
        properties.setProperty("ssl", Boolean.toString(tls));
        // Buffer the small, bounded results so HTTP errors cannot look like a successful partial query.
        properties.setProperty("clickhouse_setting_wait_end_of_query", "1");
        properties.setProperty("clickhouse_setting_prefer_column_name_to_alias", "1");
    }

    public String jdbcUrl() { return jdbcUrl; }
    public Properties properties() {
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }
    public DataSource createDataSource() throws SQLException {
        // Select V2 directly; CoreProtect or another plugin may have enabled the legacy driver globally.
        return new DataSourceImpl(jdbcUrl, properties());
    }
}
