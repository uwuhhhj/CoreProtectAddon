package cc.carm.outsource.plugin.coreprotectaddon.manager;

import java.util.Locale;

public enum DatabaseType {
    MYSQL, CLICKHOUSE, DUCKDB;

    public static DatabaseType parse(String value) {
        return switch (value == null ? "mysql" : value.trim().toLowerCase(Locale.ROOT)) {
            case "mysql", "mariadb" -> MYSQL;
            case "clickhouse" -> CLICKHOUSE;
            case "duckdb" -> DUCKDB;
            default -> throw new IllegalArgumentException("database-type 仅支持 mysql、mariadb、clickhouse 或 duckdb：" + value);
        };
    }
}
