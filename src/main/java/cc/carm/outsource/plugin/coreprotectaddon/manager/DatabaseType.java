package cc.carm.outsource.plugin.coreprotectaddon.manager;

import java.util.Locale;

public enum DatabaseType {
    MYSQL, CLICKHOUSE, DUCKDB, SQLITE;

    public static DatabaseType parse(String value) {
        return switch (value == null ? "mysql" : value.trim().toLowerCase(Locale.ROOT)) {
            case "mysql", "mariadb" -> MYSQL;
            case "clickhouse" -> CLICKHOUSE;
            case "duckdb" -> DUCKDB;
            case "sqlite" -> SQLITE;
            default -> throw new IllegalArgumentException("database-type 仅支持 mysql、mariadb、clickhouse、duckdb 或 sqlite：" + value);
        };
    }
}
