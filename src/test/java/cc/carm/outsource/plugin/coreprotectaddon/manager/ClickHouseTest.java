package cc.carm.outsource.plugin.coreprotectaddon.manager;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.*;
import cc.carm.outsource.plugin.coreprotectaddon.service.*;
import com.clickhouse.data.ClickHouseCompression;
import com.clickhouse.data.ClickHouseInputStream;
import com.clickhouse.data.ClickHouseOutputStream;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import org.junit.Assume;

import javax.sql.DataSource;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/** Real JDBC + local HTTP wire fixture. This does not substitute for executing SQL on ClickHouse. */
public class ClickHouseTest {
    private static final CoreProtectQueryService.Tables TABLES = CoreProtectQueryService.Tables.prefixed("fixture_");
    private static final long LARGE_ID = 5_000_000_001L;

    @Test public void backendSelectionAndInvalidTargetsFailExplicitly() {
        assertEquals(DatabaseType.MYSQL, DatabaseType.parse(null));
        assertEquals(DatabaseType.MYSQL, DatabaseType.parse("MariaDB"));
        assertEquals(DatabaseType.CLICKHOUSE, DatabaseType.parse(" ClickHouse "));
        assertEquals(DatabaseType.DUCKDB, DatabaseType.parse(" DuckDB "));
        for (String type : List.of("sqlite", "", "typo"))
            assertThrows(IllegalArgumentException.class, () -> DatabaseType.parse(type));
        for (String host : List.of("", "localhost/path", "https://localhost", "localhost?ssl=false", "user@host", " host"))
            assertThrows(host, IllegalArgumentException.class, () -> settings(host, 8123, false));
        assertThrows(IllegalArgumentException.class, () -> settings("localhost", 0, false));
        assertThrows(IllegalArgumentException.class, () -> settings("localhost", 65536, false));
        assertThrows(IllegalArgumentException.class, () -> new ClickHouseConnectionSettings("localhost", 8123, "db?x=y", "u", "p", false, 5));
        assertThrows(QueryException.class, () -> CoreProtectQueryService.Tables.prefixed("co_; DROP TABLE"));
    }

    @Test public void tlsIpv6AndCredentialsAreIndependentOfJdbcUrl() {
        assertEquals("jdbc:clickhouse:http://clickhouse:8123/coq_fixture", settings("clickhouse", 8123, false).jdbcUrl());
        assertEquals("jdbc:clickhouse:https://[::1]:8443/coq_fixture", settings("::1", 8443, true).jdbcUrl());
        assertEquals("jdbc:clickhouse:https://[::1]:8443/coq_fixture", settings("[::1]", 8443, true).jdbcUrl());
        ClickHouseConnectionSettings config = settings("localhost", 8123, true);
        assertEquals("p'&?#", config.properties().getProperty("password"));
        assertEquals("true", config.properties().getProperty("ssl"));
        assertFalse(config.jdbcUrl().contains("p'"));
        config.properties().setProperty("password", "changed");
        assertEquals("p'&?#", config.properties().getProperty("password"));
    }

    @Test public void realDriverReadsCompatibilityViewsAndPreservesLargeIdsAndNullCoordinates() throws Exception {
        try (Fixture fixture = new Fixture()) {
            DataSource source = fixture.source();
            DataManager.validateClickHouse(source, TABLES, 5);
            CoreProtectQueryService service = service(source);
            LookupResult result = service.lookup(new LookupRequest(LookupAction.ITEM_ADD, List.of("Steve"), "1d", 1, 15,
                    List.of("iron_ingot"), List.of("diamond"), null), 100_000);
            assertTrue(result.message() + fixture.queries, result.success());
            LookupRecord row = result.records().get(0);
            assertEquals(LARGE_ID, row.rowId());
            assertEquals(LARGE_ID, row.playerId());
            assertEquals("Steve", row.playerName());
            assertEquals("fixture_world", row.world());
            assertEquals("minecraft:iron_ingot", row.material());
            assertNull(row.x()); assertEquals(Integer.valueOf(64), row.y());
            assertEquals(64, row.amount());
            assertArrayEquals(new byte[]{(byte)0xac,(byte)0xed,0,5,(byte)0xff}, row.itemSnapshot().metadata());
            String detail = fixture.queries.stream().filter(q -> q.contains("ORDER BY")).findFirst().orElseThrow();
            assertTrue(detail, detail.contains("`action` IN (3,4)"));
            assertTrue(detail, detail.contains("`type` NOT IN (202)"));
            assertTrue(detail, detail.contains("ORDER BY `time` DESC, `source_order` DESC, `rowid` DESC LIMIT 1 OFFSET 0"));
            assertTrue(detail, detail.contains("SETTINGS max_execution_time="));
            assertFalse(detail, detail.contains("wait_end_of_query"));
            assertTrue(fixture.httpParams.toString(), fixture.httpParams.stream().allMatch(p -> p.contains("wait_end_of_query=1")));
            assertFalse(detail.contains("MAX_EXECUTION_TIME"));
            assertFalse(detail.contains("event_data"));
        }
    }

    @Test public void regexUsesMatchAndPreparedBindingAndLegacyApiStillWorks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            QueryResult result = service(fixture.source()).query(new QueryRequest(QueryType.CHAT, "Steve", "1d", 1, 15, "hello'\\d+"));
            assertTrue(result.message() + fixture.queries, result.success());
            assertEquals("hello ' \\ 世界", result.records().get(0).message());
            assertEquals(LARGE_ID, result.records().get(0).playerId());
            String detail = fixture.queries.stream().filter(q -> q.contains("ORDER BY")).findFirst().orElseThrow();
            assertTrue(detail, detail.contains("match(`message`, 'hello\\'\\\\d+')"));
            assertFalse(detail, detail.contains(" REGEXP "));
        }
    }

    @Test public void oldServerAndMissingViewsFailAtStartupAndServerTimeoutIsNotEmptySuccess() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.version = "25.5.1";
            assertThrows(SQLException.class, () -> DataManager.validateClickHouse(fixture.source(), TABLES, 5));
            fixture.version = "25.6.1";
            fixture.errorCode = 60;
            assertThrows(SQLException.class, () -> DataManager.validateClickHouse(fixture.source(), TABLES, 5));
            assertEquals("QUERY_FAILED", service(fixture.source()).query(new QueryRequest(QueryType.CHAT, null, "1d", 1, 15, null)).errorCode());
            fixture.errorCode = 159;
            assertEquals("QUERY_TIMEOUT", service(fixture.source()).query(new QueryRequest(QueryType.CHAT, null, "1d", 1, 15, null)).errorCode());
        }
    }

    @Test public void configuredCredentialsReachHttpAndWrongPasswordIsReportedAsAuthenticationFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            DataManager.validateClickHouse(fixture.source(), TABLES, 5);
            assertTrue("Fixture must have received authenticated requests", fixture.authenticatedRequests > 0);
            DataSource wrongPassword = new ClickHouseConnectionSettings("127.0.0.1", fixture.server.getAddress().getPort(),
                    "coq_fixture", "fixture_user", "wrong_password", false, 5).createDataSource();
            SQLException failure = assertThrows(SQLException.class, () -> DataManager.validateClickHouse(wrongPassword, TABLES, 5));
            assertEquals(516, failure.getErrorCode());
            String message = DataManager.connectionFailureMessage(new Exception("wrapped", failure));
            assertTrue(message, message.contains("认证失败（516）"));
            assertTrue(message, message.contains("plugins/CoreProtectAddon/config.yml"));
            assertFalse(message, message.contains("wrong_password"));
        }
    }

    @Test public void shadedPluginIncludesSelfContainedDriver() throws Exception {
        String jar = System.getProperty("coq.test.pluginJar");
        Assume.assumeNotNull(jar);
        try (Fixture fixture = new Fixture(); var loader = new java.net.URLClassLoader(
                new java.net.URL[]{new File(jar).toURI().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> settings = loader.loadClass(ClickHouseConnectionSettings.class.getName());
            Object config = settings.getConstructor(String.class, int.class, String.class, String.class, String.class, boolean.class, int.class)
                    .newInstance("127.0.0.1", fixture.server.getAddress().getPort(), "coq_fixture", "fixture_user", "p'&?#", false, 5);
            DataSource source = (DataSource) settings.getMethod("createDataSource").invoke(config);
            try (var connection = source.getConnection(); var statement = connection.prepareStatement("SELECT version()")) {
                statement.setQueryTimeout(5);
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("25.6.1", result.getString(1));
                }
            }
        }
    }

    @Test public void suppliedCoreProtectBuildUsesTheSameHttpCredentials() throws Exception {
        String jar = System.getProperty("coq.test.coreprotectJar");
        Assume.assumeNotNull(jar);
        try (Fixture fixture = new Fixture(); var loader = new java.net.URLClassLoader(
                new java.net.URL[]{new File(jar).toURI().toURL(), org.slf4j.LoggerFactory.class.getProtectionDomain().getCodeSource().getLocation()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> configType = loader.loadClass("net.coreprotect.database.clickhouse.ClickHouseJdbcConfig");
            Object config = configType.getConstructor(String.class, int.class, String.class, String.class, String.class, boolean.class)
                    .newInstance("127.0.0.1", fixture.server.getAddress().getPort(), "coq_fixture", "fixture_user", "p'&?#", false);
            String url = (String) configType.getMethod("getJdbcUrl").invoke(config);
            Properties props = (Properties) configType.getMethod("getProperties").invoke(config);
            DataSource source = (DataSource) loader.loadClass("com.clickhouse.jdbc.DataSourceImpl")
                    .getConstructor(String.class, Properties.class).newInstance(url, props);
            try (var connection = source.getConnection(); var statement = connection.prepareStatement("SELECT version()")) {
                try (var result = statement.executeQuery()) { assertTrue(result.next()); }
            }
            assertTrue(fixture.authenticatedRequests > 0);
            // The exact same fixture requires the exact same credentials from our data source.
            DataManager.validateClickHouse(fixture.source(), TABLES, 5);
            assertEquals(fixture.authModes.toString(), 1, fixture.authModes.stream().distinct().count());
        }
    }

    private static CoreProtectQueryService service(DataSource source) {
        return new CoreProtectQueryService(source, () -> TABLES, () -> new QueryLimits(true, 604800, 15, 100, 1000, 5));
    }
    private static ClickHouseConnectionSettings settings(String host, int port, boolean tls) {
        return new ClickHouseConnectionSettings(host, port, "coq_fixture", "fixture_user", "p'&?#", tls, 5);
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final List<String> queries = new CopyOnWriteArrayList<>();
        private final List<String> httpParams = new CopyOnWriteArrayList<>();
        private volatile String version = "25.6.1";
        private volatile int errorCode;
        private volatile int authenticatedRequests;
        private final List<String> authModes = new CopyOnWriteArrayList<>();
        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::respond);
            server.start();
        }
        DataSource source() throws SQLException { return settings("127.0.0.1", server.getAddress().getPort(), false).createDataSource(); }
        @Override public void close() { server.stop(0); }
        private void respond(HttpExchange exchange) throws IOException {
            try (exchange) {
                // Require authentication for every fixture request, including the isolated shaded JAR test.
                String expected = "Basic " + Base64.getEncoder().encodeToString("fixture_user:p'&?#".getBytes(StandardCharsets.UTF_8));
                boolean authenticated = expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))
                        || ("fixture_user".equals(exchange.getRequestHeaders().getFirst("X-ClickHouse-User"))
                        && "p'&?#".equals(exchange.getRequestHeaders().getFirst("X-ClickHouse-Key")));
                if (!authenticated) {
                    byte[] body = "Code: 516. DB::Exception: Authentication failed. (AUTHENTICATION_FAILED)".getBytes(StandardCharsets.UTF_8);
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("X-ClickHouse-Exception-Code", "516");
                    exchange.sendResponseHeaders(403, body.length);
                    exchange.getResponseBody().write(body);
                    return;
                }
                authenticatedRequests++;
                authModes.add(exchange.getRequestHeaders().containsKey("Authorization") ? "Authorization" : "X-ClickHouse-User/Key");
                String params = Objects.toString(exchange.getRequestURI().getRawQuery(), "");
                InputStream input = params.contains("decompress=1")
                        ? ClickHouseInputStream.of(exchange.getRequestBody(), ClickHouseCompression.LZ4) : exchange.getRequestBody();
                String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                queries.add(sql);
                httpParams.add(params);
                byte[] response;
                if (sql.contains("currentUser()")) {
                    response = rows(new String[]{"user", "timezone", "version"}, new String[]{"String", "String", "String"},
                            new Object[]{"fixture_user", "UTC", version});
                } else if (sql.contains("version()")) {
                    response = rows(new String[]{"version()"}, new String[]{"String"}, new Object[]{version});
                } else if (errorCode != 0) {
                    byte[] body = ("Code: " + errorCode + ". DB::Exception: fixture error. (" + (errorCode == 159 ? "TIMEOUT_EXCEEDED" : "UNKNOWN_TABLE") + ")").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("X-ClickHouse-Exception-Code", Integer.toString(errorCode));
                    exchange.sendResponseHeaders(500, body.length);
                    exchange.getResponseBody().write(body);
                    return;
                } else if (sql.matches("(?s).* LIMIT 0(?: SETTINGS .*)?")) {
                    response = rows(new String[]{"rowid"}, new String[]{"Int64"});
                } else if (sql.contains("COUNT(*)")) {
                    response = rows(new String[]{"count()"}, new String[]{"UInt64"}, new Object[]{1L});
                } else if (sql.contains("ORDER BY")) {
                    boolean item = sql.contains("fixture_item");
                    response = rows(new String[]{"rowid","time","user","wid","x","y","z","type","amount","action","data","rolled_back","message","uuid","source","source_order"},
                            new String[]{"Int64","Int64","Int64","Int64","Nullable(Int32)","Int32","Nullable(Int32)","Int64","Int32","Int8","Int64","Int8","String","String","String","Int32"},
                            new Object[]{LARGE_ID,99_900L,LARGE_ID,42L,null,64,null,101L,64,3,0L,0,item ? "" : "hello ' \\ 世界","",item ? "item" : "chat",item ? 2 : 0});
                } else if (sql.contains("item_metadata")) {
                    response = rows(new String[]{"rowid","item_metadata"},new String[]{"Int64","Array(Int8)"},
                            new Object[]{LARGE_ID,new byte[]{0,(byte)0xac,(byte)0xed,0,5,(byte)0xff}});
                } else if (sql.contains("fixture_user")) {
                    response = rows(new String[]{"rowid","user"}, new String[]{"Int64","String"}, new Object[]{LARGE_ID,"Steve"});
                } else if (sql.contains("fixture_material_map")) {
                    boolean diamond = sql.contains("diamond");
                    response = rows(new String[]{"id","material"}, new String[]{"Int64","String"},
                            new Object[]{diamond ? 202L : 101L, diamond ? "minecraft:diamond" : "minecraft:iron_ingot"});
                } else if (sql.contains("fixture_world")) {
                    response = rows(new String[]{"id","world"}, new String[]{"Int64","String"}, new Object[]{42L,"fixture_world"});
                } else throw new IOException("Unexpected fixture query: " + sql);
                if (params.matches(".*(?:^|&)compress=1(?:&.*)?$")) {
                    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                    try (OutputStream output = ClickHouseOutputStream.of(compressed, 8192, ClickHouseCompression.LZ4, -1, null)) {
                        output.write(response);
                    }
                    response = compressed.toByteArray();
                }
                exchange.getResponseHeaders().set("X-ClickHouse-Format", "RowBinaryWithNamesAndTypes");
                exchange.getResponseHeaders().set("X-ClickHouse-Timezone", "UTC");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        }
    }

    private static byte[] rows(String[] names, String[] types, Object[]... rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varint(out, names.length);
        for (String name : names) string(out, name);
        for (String type : types) string(out, type);
        for (Object[] row : rows) for (int i = 0; i < types.length; i++) {
            String type = types[i];
            if (type.startsWith("Nullable(")) {
                out.write(row[i] == null ? 1 : 0);
                if (row[i] == null) continue;
                type = type.substring(9, type.length() - 1);
            }
            if (type.equals("Array(Int8)")) { byte[] bytes = (byte[]) row[i]; varint(out,bytes.length); out.write(bytes); }
            else if (type.equals("String")) string(out, row[i].toString());
            else {
                int bytes = type.endsWith("64") ? 8 : type.endsWith("32") ? 4 : 1;
                long value = ((Number) row[i]).longValue();
                for (int b = 0; b < bytes; b++) out.write((int) (value >>> (b * 8)) & 0xff);
            }
        }
        return out.toByteArray();
    }
    private static void string(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        varint(out, bytes.length); out.write(bytes);
    }
    private static void varint(OutputStream out, int value) throws IOException {
        while (value > 127) { out.write((value & 127) | 128); value >>>= 7; }
        out.write(value);
    }
}
