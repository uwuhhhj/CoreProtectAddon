package cc.carm.outsource.plugin.coreprotectaddon.service;

import cc.carm.outsource.plugin.coreprotectaddon.Main;
import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.*;
import cc.carm.outsource.plugin.coreprotectaddon.command.LookupParameters;
import org.junit.*;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.Assert.*;

/** Opt in against a disposable database named coq_fixture, never an existing CoreProtect schema. */
public class SqlIntegrationTest {
    private static String url;
    private static String prefix;
    private static DataSource source;
    private static CoreProtectQueryService.Tables tables;
    private static long anchor;
    private static final QueryLimits LIMITS = new QueryLimits(true, 604800, 15, 100, 1000, 5);
    private static final List<String> ownedTables = new ArrayList<>();

    @BeforeClass public static void fixture() throws Exception {
        url = System.getProperty("coq.test.jdbc");
        Assume.assumeTrue("Provide -Psql-integration -Dcoq.test.jdbc=jdbc:mysql://127.0.0.1:PORT/coq_fixture", url != null);
        if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/coq_fixture(?:\\?.*)?"))
            throw new IllegalArgumentException("Integration tests require the local disposable coq_fixture database");
        Class.forName("com.mysql.cj.jdbc.Driver");
        prefix = "coq_test_" + UUID.randomUUID().toString().replace("-", "") + "_";
        source = new TestSource();
        tables = new CoreProtectQueryService.Tables(prefix + "user", prefix + "chat", prefix + "command", prefix + "item",
                prefix + "container", prefix + "material_map", prefix + "world");
        anchor = Instant.now().getEpochSecond();
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            create(s, "user", "rowid INT PRIMARY KEY, time INT, user VARCHAR(100), uuid VARCHAR(64)");
            create(s, "world", "rowid INT PRIMARY KEY, id INT, world VARCHAR(255)");
            create(s, "material_map", "rowid INT PRIMARY KEY, id INT, material VARCHAR(255)");
            String base = "rowid INT PRIMARY KEY, time INT, user INT, wid INT, x INT, y INT, z INT, ";
            create(s, "chat", base + "message VARCHAR(16000)");
            create(s, "command", base + "message VARCHAR(16000)");
            create(s, "item", base + "type INT, data BLOB, amount INT, action TINYINT, rolled_back TINYINT");
            create(s, "container", base + "type INT, data INT, amount INT, metadata BLOB, action TINYINT, rolled_back TINYINT");
            s.executeUpdate("INSERT INTO " + tables.users() + " VALUES (1,0,'Steve',NULL),(2,0,'Alex',NULL)");
            // Dictionary rowid deliberately differs from id to detect incorrect material/world joins.
            s.executeUpdate("INSERT INTO " + tables.materials() + " VALUES (1,101,'minecraft:iron_ingot'),(2,202,'minecraft:diamond')");
            s.executeUpdate("INSERT INTO " + tables.worlds() + " VALUES (1,42,'fixture_world')");
            for (int action = 0; action <= 12; action++)
                item(s, action + 1, anchor - 100, 1, 101, action + 1, action);
            item(s, 100, anchor - 50, 2, 202, 64, 3);
            item(s, 101, anchor + 100, 1, 101, 1, 3);
            item(s, 102, anchor - 90000, 1, 101, 1, 3);
            s.executeUpdate("INSERT INTO " + tables.container() + " VALUES (1," + (anchor - 20) + ",1,42,10,64,20,101,0,32,X'FFFF',0,0)," +
                    "(2," + (anchor - 20) + ",2,42,10,64,20,202,0,16,X'FFFF',1,0),(3," + (anchor - 30) + ",1,42,10,64,20,101,0,8,X'FFFF',1,0)");
            try (PreparedStatement chat = c.prepareStatement("INSERT INTO " + tables.chat() + " VALUES (?,?,?,42,10,64,20,?)")) {
                String[] messages = {"hello world", "<click:run_command:'/op me'>hello</click>", "quote ' and slash \\"};
                for (int i = 0; i < messages.length; i++) {
                    chat.setInt(1, i + 1); chat.setLong(2, anchor - (i == 0 ? 20 : 10));
                    chat.setInt(3, i == 1 ? 2 : 1); chat.setString(4, messages[i]); chat.executeUpdate();
                }
            }
            s.executeUpdate("INSERT INTO " + tables.command() + " VALUES (1," + (anchor - 20) + ",1,42,10,64,20,'/op Alex')");
        }
    }
    private static void create(Statement s, String name, String definition) throws SQLException {
        String table = prefix + name;
        String index = name.equals("material_map") || name.equals("world") ? "" : ", INDEX(time)";
        s.executeUpdate("CREATE TABLE " + table + " (" + definition + index + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        ownedTables.add(table);
    }
    private static void item(Statement s, int id, long time, int user, int type, int amount, int action) throws SQLException {
        // Invalid serialized data proves basic lookup never attempts to decode metadata.
        s.executeUpdate("INSERT INTO " + tables.item() + " VALUES (" + id + "," + time + "," + user + ",42,10,64,20," +
                type + ",X'FFFF'," + amount + "," + action + ",0)");
    }
    @AfterClass public static void cleanup() throws SQLException {
        if (source == null || ownedTables.isEmpty()) return;
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            for (String name : ownedTables) s.executeUpdate("DROP TABLE " + name);
        }
    }
    private static CoreProtectQueryService service(QueryLimits limits) { return new CoreProtectQueryService(source, () -> tables, () -> limits); }
    private static LookupRequest request(String args) { return LookupParameters.parse(("l " + args).split(" ")).request(); }
    private static LookupResult lookup(String args) { return service(LIMITS).lookup(request(args), anchor); }
    private static void ids(LookupResult result, Long... expected) {
        assertTrue(result.message(), result.success());
        assertEquals(Arrays.asList(expected), result.records().stream().map(LookupRecord::rowId).toList());
    }
    @Test public void itemGroupsMatch232LookupRawAndSortByTimeThenRowid() {
        ids(lookup("a:item t:1d"), 100L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);
        ids(lookup("a:+item t:1d"), 100L, 5L, 4L);
        ids(lookup("a:-item t:1d"), 8L, 7L, 6L, 3L);
        LookupRecord pickup = lookup("a:+item t:1d").records().get(0);
        assertEquals(64, pickup.amount()); assertEquals("Alex", pickup.playerName());
        assertEquals("minecraft:diamond", pickup.material()); assertEquals("fixture_world", pickup.world());
        assertNull(pickup.message());
    }
    @Test public void containerDirectionsAndCountMatch232() {
        ids(lookup("a:container t:1d"), 2L, 1L, 3L);
        ids(lookup("a:+container t:1d"), 2L, 3L);
        ids(lookup("a:-container t:1d"), 1L);
        assertEquals(32, lookup("a:-container t:1d").records().get(0).amount());
    }
    @Test public void includeExcludeUsersTimeAndMaterialMappingAreAppliedInSql() {
        ids(lookup("a:item t:1d u:steve i:iron_ingot e:diamond"), 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);
        ids(lookup("a:item t:1d u:Steve,Alex i:iron_ingot,diamond e:iron_ingot"), 100L);
        ids(lookup("a:container t:1d i:iron_ingot e:iron_ingot"));
        ids(lookup("a:item t:1d i:never_logged_material"));
        ids(lookup("a:item t:2d-1d"), 102L);
        assertEquals("UNKNOWN_USER", lookup("a:item t:1d u:nobody").errorCode());
        assertEquals("UNKNOWN_USER", lookup("a:item t:1d u:Steve,'OR'1=1").errorCode());
    }
    @Test public void capCountMatchesDetailsAndPartialFinalPage() {
        CoreProtectQueryService limited = service(new QueryLimits(true, 604800, 3, 100, 7, 5));
        LookupRequest request = request("a:item t:1d");
        LookupResult first = limited.lookup(request, anchor);
        ids(first, 100L, 8L, 7L); assertTrue(first.truncated()); assertEquals(7, first.total()); assertEquals(3, first.totalPages());
        ids(limited.lookup(request.withPage(2, 3), anchor), 6L, 5L, 4L);
        ids(limited.lookup(request.withPage(3, 3), anchor), 3L);
        assertFalse(limited.lookup(request.withPage(4, 3), anchor).success());
        assertEquals("PAGE_OUT_OF_RANGE", lookup("a:chat t:1d page:2").errorCode());
        assertEquals("PAGE_OUT_OF_RANGE", lookup("a:item t:1d i:missing page:2").errorCode());
        assertFalse(lookup("a:item t:1d i:diamond").truncated());
    }
    @Test public void regexRemainsParameterizedAndRawTextSurvives() {
        ids(lookup("a:chat t:1d content:hello.*"), 2L, 1L);
        ids(lookup("a:chat t:1d content:quote.*"), 3L);
        ids(lookup("a:command t:1d content:^/op"), 1L);
        ids(lookup("a:chat t:1d content:absent"));
        assertTrue(lookup("a:chat t:1d content:hello.*").records().get(0).message().contains("<click:"));
        assertEquals("QUERY_FAILED", lookup("a:chat t:1d content:[").errorCode());
    }
    @Test public void sqlErrorsNeverBecomeEmptySuccess() {
        CoreProtectQueryService broken = new CoreProtectQueryService(source, () -> new CoreProtectQueryService.Tables(tables.users(),
                prefix + "does_not_exist", tables.command(), tables.item(), tables.container(), tables.materials(), tables.worlds()), () -> LIMITS);
        LookupResult result = broken.lookup(request("a:chat t:1d"), anchor);
        assertFalse(result.success()); assertEquals("QUERY_FAILED", result.errorCode());
        assertTrue(lookup("a:chat t:1d content:absent").success());
    }
    @Test public void legacySixArgumentConstructorAndAstrBotAccessorsAreCompatible() throws Exception {
        Class<?> requestType = Class.forName("cc.carm.outsource.plugin.coreprotectaddon.api.query.QueryRequest");
        Class<?> queryType = Class.forName("cc.carm.outsource.plugin.coreprotectaddon.api.query.QueryType");
        assertEquals(QueryResult.class, Main.class.getMethod("query", requestType).getReturnType());
        Object chat = queryType.getMethod("byId", String.class).invoke(null, "chat");
        Object input = requestType.getConstructor(queryType, String.class, String.class, Integer.class, Integer.class, String.class)
                .newInstance(chat, "Steve", "1d", 1, 15, "hello");
        Object result = CoreProtectQueryService.class.getMethod("query", requestType).invoke(service(LIMITS), input);
        assertEquals(true, result.getClass().getMethod("success").invoke(result));
        for (String name : List.of("success", "message", "errorCode", "queryType", "page", "pageSize", "count", "costMs", "records"))
            result.getClass().getMethod(name).invoke(result);
        QueryRecord record = ((QueryResult) result).records().get(0);
        for (String name : List.of("time", "playerId", "playerName", "message")) record.getClass().getMethod(name).invoke(record);
        assertEquals("hello world", record.message());
        assertEquals("/op Alex", service(LIMITS).query(new QueryRequest(QueryType.COMMAND, "Steve", "1d", 1, 15, "^/op")).records().get(0).message());
    }
    @Test public void allLimitsAlsoApplyToLegacyJavaApi() {
        CoreProtectQueryService service = service(new QueryLimits(true, 604800, 1, 2, 2, 5));
        assertEquals("TIME_REQUIRED", service.query(QueryRequest.of(QueryType.CHAT)).errorCode());
        assertEquals("INVALID_TIME", service.query(new QueryRequest(QueryType.CHAT, null, "8d", 1, 1, null)).errorCode());
        assertEquals("INVALID_PAGE", service.query(new QueryRequest(QueryType.CHAT, null, "1d", 1, 3, null)).errorCode());
        QueryResult capped = service.query(new QueryRequest(QueryType.CHAT, null, "1d", 1, 2, null));
        assertTrue(capped.success()); assertEquals(2, capped.count()); assertEquals("仅展示前 2 条", capped.message());
        assertTrue(service(new QueryLimits(false, 604800, 15, 100, 1000, 5)).query(QueryRequest.of(QueryType.CHAT)).success());
    }
    @Test public void realDatabaseQueryIsCancelledWithoutDependingOnLimit() throws Exception {
        long started = System.nanoTime();
        try (Connection c = source.getConnection()) {
            CoreProtectQueryService.TimedSelect sql = new CoreProtectQueryService.TimedSelect(c, System.nanoTime() + 1_000_000_000L);
            SQLException timeout = assertThrows(SQLException.class, () -> sql.read("SELECT rowid FROM " + tables.item() + " WHERE SLEEP(10)=0", List.of(), rs -> { rs.next(); return rs.getInt(1); }));
            assertTrue(timeout.getMessage(), timeout instanceof SQLTimeoutException || "70100".equals(timeout.getSQLState())
                    || timeout.getErrorCode() == 1969 || timeout.getErrorCode() == 3024);
        }
        assertTrue("Must actually execute the slow query", (System.nanoTime() - started) > 400_000_000L);
        assertTrue("Query must be interrupted before the 10-second sleep completes", (System.nanoTime() - started) < 4_000_000_000L);
    }
    @Test public void timeoutIsReportedSeparatelyFromSqlFailureAndEmptyResults() throws Exception {
        String view = prefix + "slow_chat";
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE VIEW " + view + " AS SELECT * FROM " + tables.chat() + " WHERE SLEEP(10)=0");
        }
        try {
            CoreProtectQueryService slow = new CoreProtectQueryService(source, () -> new CoreProtectQueryService.Tables(tables.users(),
                    view, tables.command(), tables.item(), tables.container(), tables.materials(), tables.worlds()),
                    () -> new QueryLimits(true, 604800, 15, 100, 1000, 1));
            LookupResult result = slow.lookup(request("a:chat t:1d"), anchor);
            assertFalse(result.success()); assertEquals("QUERY_TIMEOUT", result.errorCode());
        } finally {
            try (Connection c = source.getConnection(); Statement s = c.createStatement()) { s.executeUpdate("DROP VIEW " + view); }
        }
    }
    private static final class TestSource implements DataSource {
        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url, "root", ""); }
        @Override public Connection getConnection(String user, String password) throws SQLException { return DriverManager.getConnection(url, user, password); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("Not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
