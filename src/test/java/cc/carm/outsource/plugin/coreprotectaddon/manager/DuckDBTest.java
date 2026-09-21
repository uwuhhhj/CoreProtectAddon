package cc.carm.outsource.plugin.coreprotectaddon.manager;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.*;
import cc.carm.outsource.plugin.coreprotectaddon.command.LookupParameters;
import cc.carm.outsource.plugin.coreprotectaddon.service.*;
import org.duckdb.DuckDBConnection;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import static org.junit.Assert.*;

/** Real native DuckDB engine. Every file belongs to this test's temporary directory. */
public class DuckDBTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private DuckDBConnection writer;
    private DuckDBReadOnlyDataSource source;
    private Path folder, path;
    private final CoreProtectQueryService.Tables tables = CoreProtectQueryService.Tables.prefixed("fixture_");
    private final long anchor = Instant.now().getEpochSecond();
    private static final long USER = 5_000_000_001L;
    private static final QueryLimits LIMITS = new QueryLimits(true, 604800, 15, 100, 1000, 5);

    @Test public void oversizedMetadataIsMarkedWithoutLoadingTheBlobIntoTheResult() throws Exception {
        try (var statement = writer.prepareStatement("UPDATE fixture_item SET data=? WHERE rowid=4")) {
            statement.setBytes(1,new byte[ItemSnapshot.MAX_METADATA_BYTES+1]); statement.executeUpdate();
        }
        var result = lookup("a:+item t:1d");
        assertTrue(result.message(),result.success());
        var snapshot = result.records().stream().filter(row -> row.rowId()==4).findFirst().orElseThrow().itemSnapshot();
        assertTrue(snapshot.tooLarge()); assertNull(snapshot.metadata());
    }

    @Test public void metadataBudgetRejectsAnOversizedPageBeforeLoadingPayloads() throws Exception {
        try (var statement = writer.prepareStatement("UPDATE fixture_item SET data=? WHERE action BETWEEN 2 AND 4")) {
            statement.setBytes(1,new byte[6*1024*1024]); statement.executeUpdate();
        }
        assertEquals("METADATA_LIMIT",lookup("a:item t:1d").errorCode());
    }

    @Before public void fixture() throws Exception {
        folder = temporary.newFolder("CoreProtect 数据").toPath().toRealPath();
        path = folder.resolve("database.duckdb");
        writer = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + path);
        path = path.toRealPath();
        source = new DuckDBReadOnlyDataSource(writer::duplicate, path, "fixture_");
        try (Statement s = writer.createStatement()) {
            s.execute("CREATE TABLE fixture_user (rowid BIGINT, \"user\" VARCHAR, uuid VARCHAR)");
            s.execute("CREATE TABLE fixture_world (rowid BIGINT, id BIGINT, world VARCHAR)");
            s.execute("CREATE TABLE fixture_material_map (rowid BIGINT, id BIGINT, material VARCHAR)");
            String base = "rowid BIGINT, time BIGINT, \"user\" BIGINT, wid BIGINT, x INT, y INT, z INT, ";
            for (String table : List.of("chat", "command")) s.execute("CREATE TABLE fixture_" + table + " (" + base + "message VARCHAR)");
            for (String table : List.of("item", "container")) s.execute("CREATE TABLE fixture_" + table + " (" + base
                    + "type BIGINT, amount INT, action INT, " + (table.equals("item") ? "data" : "metadata") + " BLOB)");
            s.execute("INSERT INTO fixture_user VALUES (" + USER + ",'Steve',NULL),(2,'Alex',NULL)");
            s.execute("INSERT INTO fixture_world VALUES (1,42,'fixture_world')");
            s.execute("INSERT INTO fixture_material_map VALUES (1,101,'minecraft:iron_ingot'),(2,202,'minecraft:diamond')");
            for (int action = 0; action <= 12; action++)
                s.execute("INSERT INTO fixture_item VALUES (" + (action + 1) + "," + (anchor - 10) + "," + USER
                        + ",42,NULL,64,20,101,32," + action + ",NULL)");
            s.execute("INSERT INTO fixture_container VALUES (1," + (anchor - 10) + "," + USER
                    + ",42,10,64,20,101,32,0,NULL),(2," + (anchor - 10) + ",2,42,10,64,20,202,16,1,NULL)");
            s.execute("INSERT INTO fixture_command VALUES (1," + (anchor - 10) + "," + USER + ",42,10,64,20,'/op Alex')");
            s.execute("ALTER TABLE fixture_item ADD COLUMN rolled_back INT DEFAULT 0");
            s.execute("ALTER TABLE fixture_container ADD COLUMN rolled_back INT DEFAULT 0");
            s.execute("CREATE TABLE fixture_block ("+base+"type BIGINT,data INT,action INT,rolled_back INT)");
            s.execute("CREATE TABLE fixture_entity_map (id BIGINT,entity VARCHAR)");
            s.execute("CREATE TABLE fixture_session ("+base+"action INT)");
            s.execute("CREATE TABLE fixture_sign ("+base+"action INT,face INT,line_1 VARCHAR,line_2 VARCHAR,line_3 VARCHAR,line_4 VARCHAR,line_5 VARCHAR,line_6 VARCHAR,line_7 VARCHAR,line_8 VARCHAR)");
            s.execute("CREATE TABLE fixture_username_log (rowid BIGINT,time BIGINT,uuid VARCHAR,\"user\" VARCHAR)");
            s.execute("CREATE SEQUENCE write_probe");
        }
        try (PreparedStatement s = writer.prepareStatement("INSERT INTO fixture_chat VALUES (?,?,?,42,NULL,64,20,?)")) {
            for (int i = 1; i <= 4; i++) {
                s.setLong(1, USER + i); s.setLong(2, anchor + (i == 4 ? 10 : -10)); s.setLong(3, USER);
                s.setString(4, i == 1 ? "hello ' ` ; \\ 世界" : i == 2 ? "<click:run_command:'/op me'>hello</click>" : "other");
                s.executeUpdate();
            }
        }
    }

    @After public void close() throws Exception {
        try { if (source != null) source.close(); }
        finally { if (writer != null) writer.close(); }
    }
    private CoreProtectQueryService service(QueryLimits limits) { return new CoreProtectQueryService(source, () -> tables, () -> limits); }
    private LookupRequest request(String args) { return LookupParameters.parse(("l " + args).split(" ")).request(); }
    private LookupResult lookup(String args) { return service(LIMITS).lookup(request(args), anchor); }
    private void ids(LookupResult result, Long... expected) {
        assertTrue(result.message(), result.success());
        assertEquals(Arrays.asList(expected), result.records().stream().map(LookupRecord::rowId).toList());
    }
    private long scalar(Connection connection, String sql) throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) { assertTrue(rs.next()); return rs.getLong(1); }
    }

    @Test public void readonlyTransactionBlocksWritesAndCannotBeEndedByJdbcCallers() throws Exception {
        try (Connection read = source.getConnection(); Statement s = read.createStatement()) {
            assertTrue(read.isReadOnly());
            assertSame(read, s.getConnection());
            assertSame(read, read.getMetaData().getConnection());
            DataManager.validateTables(s, tables, "\"");
            for (String sql : List.of("INSERT INTO fixture_user VALUES (3,'bad',NULL)", "DELETE FROM fixture_chat",
                    "DROP TABLE fixture_chat", "COMMIT", "ROLLBACK", "SELECT 1; COMMIT"))
                assertThrows(SQLException.class, () -> s.execute(sql));
            assertThrows(SQLException.class, read::commit);
            assertThrows(SQLException.class, read::rollback);
            assertThrows(SQLException.class, () -> read.setAutoCommit(true));
            assertThrows(SQLException.class, () -> read.setReadOnly(false));
            assertThrows(SQLException.class, () -> read.unwrap(DuckDBConnection.class));
            // A SELECT with a write side effect reaches the native engine and must be rejected there too.
            SQLException denied = assertThrows(SQLException.class, () -> s.executeQuery("SELECT nextval('write_probe')"));
            assertTrue(denied.getMessage(), denied.getMessage().contains("read-only"));
        }
        assertEquals(1, scalar(writer, "SELECT nextval('write_probe')"));
        assertEquals(2, scalar(writer, "SELECT COUNT(*) FROM fixture_user"));
    }

    @Test public void queriesPreserveActionsFiltersLongIdsCoordinatesRegexAndPagination() throws Exception {
        ids(lookup("a:item t:1d u:steve i:iron_ingot e:diamond"), 8L,7L,6L,5L,4L,3L,2L,1L);
        ids(lookup("a:+item t:1d"), 5L,4L);
        ids(lookup("a:-item t:1d"), 8L,7L,6L,3L);
        ids(lookup("a:container t:1d"), 2L,1L);
        ids(lookup("a:+container t:1d"), 2L);
        ids(lookup("a:-container t:1d"), 1L);
        ids(lookup("a:item t:1d i:missing"));
        ids(lookup("a:chat t:1d content:hello.*"), USER+2,USER+1);
        ids(lookup("a:command t:1d content:^/op"), 1L);
        assertEquals("UNKNOWN_USER", lookup("a:item t:1d u:nobody").errorCode());
        assertEquals("QUERY_FAILED", lookup("a:chat t:1d content:\"[\"").errorCode());
        LookupRecord row = lookup("a:+item t:1d").records().get(0);
        assertEquals(USER, row.playerId()); assertEquals("Steve", row.playerName());
        assertEquals("fixture_world", row.world()); assertEquals("minecraft:iron_ingot", row.material()); assertNull(row.x());
        CoreProtectQueryService limited = service(new QueryLimits(true,604800,3,100,7,5));
        LookupResult first = limited.lookup(request("a:item t:1d"), anchor);
        ids(first,8L,7L,6L); assertTrue(first.truncated()); assertEquals(7,first.total());
        ids(limited.lookup(request("a:item t:1d").withPage(3,3), anchor), 2L);
        assertEquals("INVALID_PAGE", limited.lookup(request("a:item t:1d").withPage(4,3), anchor).errorCode());
        assertEquals("PAGE_OUT_OF_RANGE", lookup("a:command t:1d page:2").errorCode());
        QueryResult legacy = service(LIMITS).query(new QueryRequest(QueryType.CHAT,"Steve","1d",1,15,"hello.*"));
        assertTrue(legacy.message(), legacy.success()); assertEquals(2, legacy.count());
        assertEquals("hello ' ` ; \\ 世界", legacy.records().get(1).message());
    }

    @Test public void writerContinuesDuringSnapshotAndAfterAddonCloses() throws Exception {
        Connection read = source.getConnection();
        assertEquals(2, scalar(read,"SELECT COUNT(*) FROM fixture_user"));
        try (Connection otherWriter = writer.duplicate(); Statement s = otherWriter.createStatement()) {
            s.execute("INSERT INTO fixture_user VALUES (3,'new_user',NULL)");
        }
        assertEquals(2, scalar(read,"SELECT COUNT(*) FROM fixture_user"));
        try (Connection fresh = source.getConnection()) { assertEquals(3, scalar(fresh,"SELECT COUNT(*) FROM fixture_user")); }
        source.close();
        assertTrue(read.isClosed()); assertFalse(writer.isClosed());
        assertThrows(SQLException.class, source::getConnection);
        try (Statement s = writer.createStatement()) { s.execute("INSERT INTO fixture_user VALUES (4,'still_writing',NULL)"); }
        assertEquals(4, scalar(writer,"SELECT COUNT(*) FROM fixture_user"));
    }
    @Test public void componentMatchingPrecedesCountsAndPagesAcrossKeysetBatches() throws Exception {
        try (Statement s = writer.createStatement()) {
            s.execute("DELETE FROM fixture_item");
            s.execute("INSERT INTO fixture_item SELECT i," + (anchor-10) + "," + USER
                    + ",42,1,64,2,101,i::INT,3,NULL,0 FROM range(1,101) r(i)");
        }
        CoreProtectQueryService service = service(LIMITS);
        List<Long> visited = new ArrayList<>();
        service.setComponentMatcher((records,content,deadline) -> {
            assertEquals("{\"minecraft:max_stack_size\":1}",content);
            assertTrue(System.nanoTime()<deadline);
            assertTrue(records.size()<=32);
            BitSet matches = new BitSet();
            for (int i=0;i<records.size();i++) {
                LookupRecord record = records.get(i);
                assertEquals("minecraft:iron_ingot",record.material());
                assertEquals("Steve",record.playerName());
                assertNotNull(record.itemSnapshot());
                visited.add(record.rowId());
                if (record.amount()%10==0) matches.set(i);
            }
            return matches;
        });
        LookupRequest request = request("a:+item u:Steve i:iron_ingot e:diamond t:1d rows:3 content:{\"minecraft:max_stack_size\":1}");
        LookupResult page = service.lookup(request.withPage(2,3),anchor);
        ids(page,70L,60L,50L); assertEquals(10,page.total()); assertFalse(page.truncated());
        assertEquals(100,visited.size()); assertEquals(100,new HashSet<>(visited).size());
        ids(service.lookup(request.withPage(4,3),anchor),10L);
        assertEquals("PAGE_OUT_OF_RANGE",service.lookup(request.withPage(5,3),anchor).errorCode());
        CoreProtectQueryService capped = service(new QueryLimits(true,604800,3,100,7,5));
        capped.setComponentMatcher((records,content,deadline) -> {
            BitSet matches = new BitSet();
            for (int i=0;i<records.size();i++) if (records.get(i).amount()%10==0) matches.set(i);
            return matches;
        });
        LookupResult last = capped.lookup(request.withPage(3,3),anchor);
        ids(last,40L); assertEquals(7,last.total()); assertTrue(last.truncated());
    }

    @Test public void componentFailuresAndScanLimitNeverPretendToBeNoMatches() throws Exception {
        CoreProtectQueryService service = service(LIMITS);
        LookupRequest request = request("a:item t:1d content:{\"minecraft:max_stack_size\":1}");
        for (String code : List.of("INVALID_COMPONENT_CONTENT","COMPONENT_DECODE_FAILED","QUERY_TIMEOUT","QUERY_CANCELLED")) {
            service.setComponentMatcher((rows,content,deadline) -> { throw new QueryException(code,"fixture"); });
            assertEquals(code,service.lookup(request,anchor).errorCode());
            assertEquals(code,service.lookup(request("a:item i:missing t:1d content:{\"minecraft:max_stack_size\":1}"),anchor).errorCode());
        }
        try (Statement s = writer.createStatement()) {
            s.execute("DELETE FROM fixture_item");
            s.execute("INSERT INTO fixture_item SELECT i," + (anchor-10) + "," + USER
                    + ",42,1,64,2,101,1,3,NULL,0 FROM range(1,2002) r(i)");
        }
        service.setComponentMatcher((rows,content,deadline) -> new BitSet());
        LookupResult limited = service.lookup(request,anchor);
        assertEquals(limited.message(),"COMPONENT_SCAN_LIMIT",limited.errorCode());
        try (Statement s = writer.createStatement()) { s.execute("DELETE FROM fixture_item WHERE rowid=2001"); }
        ids(service.lookup(request,anchor)); // Exactly the cap is a complete, valid search.
    }

    @Test public void containerContentReceivesItsHistoricalMetadata() throws Exception {
        byte[] metadata = {1,2,3,4};
        try (PreparedStatement s = writer.prepareStatement("UPDATE fixture_container SET metadata=? WHERE rowid=2")) {
            s.setBytes(1,metadata); s.executeUpdate();
        }
        CoreProtectQueryService service = service(LIMITS);
        service.setComponentMatcher((records,content,deadline) -> {
            BitSet matches = new BitSet();
            for (int i=0;i<records.size();i++) if (Arrays.equals(metadata,records.get(i).itemSnapshot().metadata())) matches.set(i);
            return matches;
        });
        ids(service.lookup(request("a:container t:1d content:{\"minecraft:max_stack_size\":1}"),anchor),2L);
    }
    @Test public void itemAndContainerMetadataAreReadWithoutAlteringBinaryOrDecodingOnWorker() throws Exception {
        byte[] bytes = {(byte) 0xac,(byte) 0xed,0,5,(byte) 0xff,0,42};
        for (String family : List.of("item","container")) {
            String column = family.equals("item") ? "data" : "metadata";
            try (PreparedStatement update = writer.prepareStatement("UPDATE fixture_" + family + " SET " + column + "=?")) {
                update.setBytes(1,bytes); update.executeUpdate();
            }
            LookupRecord record = lookup("a:" + family + " t:1d rows:1").records().get(0);
            assertArrayEquals(bytes,record.itemSnapshot().metadata());
            byte[] mutable = record.itemSnapshot().metadata(); mutable[0] = 0;
            assertArrayEquals(bytes,record.itemSnapshot().metadata());
        }
    }

    @Test public void pathsMustExistInsideCoreProtectAndMatchTheActiveConnection() throws Exception {
        assertEquals(path, DuckDBReadOnlyDataSource.verifiedPath(folder, path));
        Path missing = folder.resolve("missing.duckdb");
        assertThrows(NoSuchFileException.class, () -> DuckDBReadOnlyDataSource.verifiedPath(folder,missing));
        assertFalse(Files.exists(missing));
        Path outside = temporary.newFile("elsewhere.duckdb").toPath();
        assertThrows(SQLException.class, () -> DuckDBReadOnlyDataSource.verifiedPath(folder,outside));
        try (DuckDBReadOnlyDataSource wrong = new DuckDBReadOnlyDataSource(writer::duplicate,outside,"fixture_")) {
            assertThrows(SQLException.class, wrong::getConnection);
        }
        assertFalse(writer.isClosed());
    }

    @Test public void jdbcTimeoutActuallyInterruptsNativeQuery() throws Exception {
        try (Statement s = writer.createStatement()) {
            s.execute("CREATE VIEW fixture_slow_chat AS SELECT * FROM fixture_chat WHERE "
                    + "(SELECT sum(sqrt(i::DOUBLE)) FROM range(10000000000) t(i)) > 0");
        }
        var slowTables = new CoreProtectQueryService.Tables(tables.users(),"fixture_slow_chat",tables.command(),tables.item(),
                tables.container(),tables.materials(),tables.worlds());
        CoreProtectQueryService slow = new CoreProtectQueryService(source, () -> slowTables,
                () -> new QueryLimits(true,604800,15,100,1000,1));
        long started = System.nanoTime();
        LookupResult result = slow.lookup(request("a:chat t:1d"), anchor);
        assertEquals(result.message(), "QUERY_TIMEOUT",result.errorCode());
        assertTrue("Native query was not interrupted", System.nanoTime()-started < 5_000_000_000L);
        ids(lookup("a:command t:1d"),1L);
    }

    @Test public void suppliedCoreProtectRuntimeBridgeAndItsActualSchemaWork() throws Exception {
        String jar = System.getProperty("coq.test.coreprotectJar");
        Assume.assumeNotNull(jar);
        // Use the actual patched CoreProtect classes, sharing only Bukkit/JDBC dependencies with this test.
        try (URLClassLoader loader = new URLClassLoader(new URL[]{Path.of(jar).toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.coreprotect.")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> type = findLoadedClass(name);
                        if (type == null) type = findClass(name);
                        if (resolve) resolveClass(type);
                        return type;
                    }
                }
                return super.loadClass(name,resolve);
            }
        }) {
            Class<?> config = Class.forName("net.coreprotect.config.ConfigHandler",true,loader);
            Class<?> backend = Class.forName("net.coreprotect.database.DuckDBDatabase",true,loader);
            Field root = backend.getDeclaredField("rootConnection"); root.setAccessible(true); root.set(null,writer);
            Field type = config.getField("databaseType");
            type.set(null, type.getType().getField("DUCKDB").get(null));
            config.getField("path").set(null,folder.toString());
            config.getField("duckdb").set(null,path.getFileName().toString());
            config.getField("prefix").set(null,"actual_");
            Method create = backend.getDeclaredMethod("createTables",String.class,Connection.class,boolean.class);
            create.setAccessible(true); create.invoke(null,"actual_",writer,true);
            try (DuckDBReadOnlyDataSource actual = DuckDBReadOnlyDataSource.fromRuntime(loader,folder,() -> true)) {
                assertEquals("actual_",actual.tablePrefix()); assertEquals(path,actual.databasePath());
                // Validate the expanded lookup projections against the supplied JAR's real schema.
                CoreProtectQueryService nativeSchema = new CoreProtectQueryService(actual,
                        () -> CoreProtectQueryService.Tables.prefixed("actual_"), () -> LIMITS);
                for (LookupAction action : List.of(LookupAction.ALL,LookupAction.BLOCK,LookupAction.CLICK,LookupAction.KILL,
                        LookupAction.SIGN,LookupAction.SESSION,LookupAction.USERNAME,LookupAction.ITEM,LookupAction.CONTAINER)) {
                    LookupResult empty = nativeSchema.lookup(new LookupRequest(action,List.of(),"1h",1,15,List.of(),List.of(),null),anchor);
                    assertTrue(action+": "+empty.message(),empty.success()); assertEquals(0,empty.total());
                }
                try (Connection read = actual.getConnection(); Statement s = read.createStatement()) {
                    DataManager.validateTables(s, CoreProtectQueryService.Tables.prefixed("actual_"),"\"");
                }
                String pluginJar = System.getProperty("coq.test.pluginJar");
                if (pluginJar != null) {
                    try (URLClassLoader isolated = new URLClassLoader(new URL[]{Path.of(pluginJar).toUri().toURL()},
                            ClassLoader.getPlatformClassLoader())) {
                        Class<?> bridge = Class.forName(DuckDBReadOnlyDataSource.class.getName(),true,isolated);
                        Method factory = bridge.getDeclaredMethod("fromRuntime",ClassLoader.class,Path.class,
                                java.util.function.BooleanSupplier.class);
                        factory.setAccessible(true);
                        var packaged = (javax.sql.DataSource) factory.invoke(null,loader,folder,
                                (java.util.function.BooleanSupplier) () -> true);
                        try (AutoCloseable ignored = (AutoCloseable) packaged;
                             Connection read = packaged.getConnection(); Statement s = read.createStatement()) {
                            DataManager.validateTables(s, CoreProtectQueryService.Tables.prefixed("actual_"),"\"");
                            assertTrue(read.isReadOnly());
                            assertThrows(SQLException.class, read::commit);
                        }
                    }
                }
                root.set(null,null);
                assertThrows(SQLException.class, actual::getConnection);
            } finally { root.set(null,null); }
            assertThrows(SQLException.class, () -> DuckDBReadOnlyDataSource.fromRuntime(loader,folder,() -> true));
            assertFalse(writer.isClosed());
        }
    }
}
