package cc.carm.outsource.plugin.coreprotectaddon.manager;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import cc.carm.outsource.plugin.coreprotectaddon.command.LookupParameters;
import cc.carm.outsource.plugin.coreprotectaddon.service.*;
import org.duckdb.DuckDBConnection;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.sql.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.Assert.*;

/** Actual SQL execution, deliberately colliding IDs across sources and dictionaries. */
public class SpatialLookupTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private DuckDBConnection writer;
    private DuckDBReadOnlyDataSource source;
    private CoreProtectQueryService service;
    private static final long NOW=100000;
    @Before public void setup() throws Exception {
        Path path=tmp.newFolder().toPath().resolve("spatial.duckdb");
        writer=(DuckDBConnection)DriverManager.getConnection("jdbc:duckdb:"+path);
        source=new DuckDBReadOnlyDataSource(writer::duplicate,path.toRealPath(),"f_");
        service=new CoreProtectQueryService(source,()->CoreProtectQueryService.Tables.prefixed("f_"),()->new QueryLimits(true,604800,15,100,1000,5));
        try(Statement s=writer.createStatement()) {
            s.execute("CREATE TABLE f_user(rowid BIGINT, \"user\" VARCHAR,uuid VARCHAR)");
            s.execute("INSERT INTO f_user VALUES (1,'Steve','uuid-steve'),(2,'Alex','uuid-alex'),(3,'#hopper','')");
            s.execute("CREATE TABLE f_world(rowid BIGINT,id BIGINT,world VARCHAR)");s.execute("INSERT INTO f_world VALUES(1,42,'world'),(2,99,'nether')");
            s.execute("CREATE TABLE f_material_map(rowid BIGINT,id BIGINT,material VARCHAR)");s.execute("INSERT INTO f_material_map VALUES(1,101,'minecraft:stone'),(2,102,'minecraft:diamond'),(3,103,'minecraft:water')");
            s.execute("CREATE TABLE f_entity_map(rowid BIGINT,id BIGINT,entity VARCHAR)");s.execute("INSERT INTO f_entity_map VALUES(1,101,'zombie')");
            String base="rowid BIGINT,time BIGINT,\"user\" BIGINT,wid BIGINT,x INT,y INT,z INT,";
            s.execute("CREATE TABLE f_block("+base+"type BIGINT,amount INT DEFAULT 1,action INT,data BIGINT,rolled_back INT)");
            for(String name:List.of("item","container"))s.execute("CREATE TABLE f_"+name+"("+base+"type BIGINT,amount INT,action INT,rolled_back INT,"+(name.equals("item")?"data":"metadata")+" BLOB)");
            for(String name:List.of("chat","command")) {s.execute("CREATE TABLE f_"+name+"("+base+"message VARCHAR)");s.execute("INSERT INTO f_"+name+" VALUES(1,99990,1,42,0,64,0,'hello world')");}
            s.execute("CREATE TABLE f_session("+base+"action INT)");s.execute("INSERT INTO f_session VALUES(1,99990,1,42,0,64,0,1),(2,99990,1,42,0,64,0,0)");
            s.execute("CREATE TABLE f_username_log(rowid BIGINT,time BIGINT,uuid VARCHAR,\"user\" VARCHAR)");s.execute("INSERT INTO f_username_log VALUES(1,99990,'uuid-steve','OldSteve'),(2,99990,'uuid-alex','OldAlex')");
            s.execute("CREATE TABLE f_sign("+base+"action INT,face INT,line_1 VARCHAR,line_2 VARCHAR,line_3 VARCHAR,line_4 VARCHAR,line_5 VARCHAR,line_6 VARCHAR,line_7 VARCHAR,line_8 VARCHAR)");
            s.execute("INSERT INTO f_sign VALUES(1,99990,1,42,0,64,0,1,0,'front','hello','','','back','other','',''),(2,99990,1,42,0,64,0,1,1,'front','other','','','back','hello','',''),(3,99990,1,42,0,64,0,0,0,'removed','','','','','','','')");
            for(int a=0;a<=3;a++)s.execute("INSERT INTO f_block VALUES("+(a+1)+",99990,1,42,0,64,0,101,1,"+a+",0,0)");
            s.execute("INSERT INTO f_block VALUES(5,99990,1,42,0,64,0,0,1,3,2,1)");
            for(int a=0;a<=12;a++)s.execute("INSERT INTO f_item VALUES("+(a+1)+",99990,1,42,0,64,0,102,8,"+a+",0,NULL)");
            s.execute("INSERT INTO f_container VALUES(1,99990,1,42,0,64,0,102,3,0,0,NULL),(2,99990,1,42,0,64,0,102,3,1,0,NULL)");
        }
    }
    @After public void close() throws Exception {if(source!=null)source.close();if(writer!=null)writer.close();}
    private LookupRequest request(String command){return LookupParameters.parse(("l "+command).split(" ")).request();}
    private LookupResult run(String command){return run(request(command));}
    private LookupResult run(LookupRequest request){LookupResult r=service.lookup(request,NOW);assertTrue(r.errorCode()+": "+r.message(),r.success());return r;}
    private List<Long> ids(String command){return run(command).records().stream().map(LookupRecord::rowId).toList();}
    @Test public void mixedLookupPreservesSourceIdentitiesOrderAndBoundedCounts() {
        LookupResult all=run("t:1h rows:100");assertEquals(15,all.total());
        assertEquals(List.of("item","container","block"),all.records().stream().map(LookupRecord::source).distinct().toList());
        assertEquals(3,all.records().stream().filter(r->r.rowId()==1).count());
        LookupResult page=run("t:1h rows:2 page:5");assertEquals(List.of("container","container"),page.records().stream().map(LookupRecord::source).toList());
        LookupResult count=run("t:1h #sum");assertTrue(count.countOnly());assertEquals(all.total(),count.total());assertTrue(count.records().isEmpty());
        service=new CoreProtectQueryService(source,()->CoreProtectQueryService.Tables.prefixed("f_"),()->new QueryLimits(true,604800,2,100,3,5));
        LookupResult capped=run("t:1h #count");assertTrue(capped.truncated());assertEquals(3,capped.total());
    }
    @Test public void actionsEntitiesAndExclusionsUseCorrectDictionaries() {
        assertEquals(List.of(2L,1L),ids("a:block t:1h"));assertEquals(List.of(3L),ids("a:click t:1h"));
        LookupResult kills=run("a:kill t:1h");assertEquals(List.of("Alex","zombie"),kills.records().stream().map(LookupRecord::material).toList());
        assertEquals(1,kills.records().get(0).rolledBack());
        assertEquals(List.of(4L),ids("a:kill t:1h i:zombie"));assertEquals(List.of(5L),ids("a:kill t:1h i:player"));
        assertEquals(0,run("a:block t:1h e:stone").total());assertEquals(0,run("t:1h e:Steve").total());
        assertEquals(14,run("t:1h e:zombie").total());
        assertEquals("UNKNOWN_EXCLUDE",service.lookup(request("t:1h e:nobody"),NOW).errorCode());
    }
    @Test public void inventoryDirectionsIncludeAllNativeItemActionsAndRequireUser() {
        assertEquals(16,run("a:inventory t:1h u:Steve rows:100").total());
        LookupResult add=run("a:+inventory t:1h u:Steve rows:100");assertEquals(6,add.total());
        assertEquals(List.of(12,10,4,3,0),add.records().stream().filter(r->r.source().equals("item")).map(LookupRecord::action).toList());
        LookupResult remove=run("a:-inventory t:1h u:Steve rows:100");assertEquals(10,remove.total());assertTrue(remove.records().stream().anyMatch(r->r.source().equals("block")));
    }
    @Test public void sessionsSignsAndUsernameLookupsKeepSpecialSchemas() {
        assertEquals(List.of(1L),ids("a:+session t:1h"));assertEquals(List.of(2L),ids("a:-session t:1h"));
        LookupResult signs=run("a:sign t:1h content:hello");assertEquals(2,signs.total());
        assertTrue(signs.records().get(0).message().startsWith("back hello"));assertTrue(signs.records().get(1).message().startsWith("front hello"));
        LookupResult names=run("a:username t:1h u:Steve");assertEquals(1,names.total());assertEquals("OldSteve",names.records().get(0).playerName());assertEquals("uuid-steve",names.records().get(0).message());
        assertEquals(1,run("a:username t:1h e:Alex").total());
    }
    @Test public void spatialFilterPrecedesCountsPagingAndComponentCandidates() throws Exception {
        try(Statement s=writer.createStatement()) {
            s.execute("UPDATE f_item SET x=5,y=69,z=-5 WHERE rowid=1");
            s.execute("UPDATE f_item SET x=6 WHERE rowid=2");
            s.execute("UPDATE f_item SET y=70 WHERE rowid=3");
            s.execute("UPDATE f_item SET wid=99 WHERE rowid=4");
            s.execute("UPDATE f_item SET x=NULL WHERE rowid=5");
        }
        SpatialBounds box=new SpatialBounds("world",-5,5,59,69,-5,5);
        LookupRequest request=request("a:item t:1h").withOptions(LookupOptions.DEFAULT.resolved(box));
        LookupResult bounded=run(request);assertEquals(List.of(8L,7L,6L,1L),bounded.records().stream().map(LookupRecord::rowId).toList());
        assertEquals(4,bounded.total());assertEquals(List.of(6L,1L),run(request.withPage(2,2)).records().stream().map(LookupRecord::rowId).toList());
        assertEquals(5,run(request.withOptions(LookupOptions.DEFAULT.resolved(new SpatialBounds("world",-5,5,null,null,-5,5)))).total());
        assertEquals(7,run(request.withOptions(LookupOptions.DEFAULT.resolved(new SpatialBounds("world",null,null,null,null,null,null)))).total());
        List<Long> candidates=new ArrayList<>();
        service.setComponentMatcher((rows,content,deadline)->{BitSet matched=new BitSet();for(int i=0;i<rows.size();i++){candidates.add(rows.get(i).rowId());matched.set(i);}return matched;});
        LookupRequest component=request("a:item t:1h content:{\"minecraft:max_stack_size\":64}").withOptions(request.options());
        assertEquals(4,run(component).total());assertEquals(List.of(8L,7L,6L,1L),candidates);
        assertEquals("UNKNOWN_WORLD",service.lookup(request.withOptions(LookupOptions.DEFAULT.resolved(new SpatialBounds("missing",null,null,null,null,null,null))),NOW).errorCode());
        assertEquals("UNRESOLVED_LOCATION",service.lookup(request("a:item t:1h r:5"),NOW).errorCode());
    }
}
