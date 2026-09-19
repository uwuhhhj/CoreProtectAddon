package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import cc.carm.outsource.plugin.coreprotectaddon.service.*;
import org.bukkit.Location;
import org.junit.Test;
import java.time.Clock;
import java.util.*;
import static org.junit.Assert.*;

public class SpatialCommandTest {
    private LookupRequest parse(String args) { return LookupParameters.parse(args.split(" "),s->false).request(); }
    @Test public void radiusMatchesNativeParserIncludingNegativeCentersAndDecimals() {
        for(String value:List.of("0","5","5x3","5x3x8","5.9x3.2x8.1")) {
            var nativeBounds=net.coreprotect.command.parser.LocationParser.parseRadius(new String[]{"l","r:"+value},null,new Location(null,-12.9,64.9,30.2));
            SpatialBounds ours=SpatialResolver.resolve(parse("l t:1h r:"+value).options(),"world",-13,64,30);
            assertEquals(Arrays.asList(nativeBounds).subList(1,7),Arrays.asList(ours.minX(),ours.maxX(),ours.minY(),ours.maxY(),ours.minZ(),ours.maxZ()));
        }
    }
    @Test public void nearTimeContentAndCoordinatesKeepCoqRules() {
        LookupRequest near=parse("near t:1h");assertEquals(LookupAction.ALL,near.action());assertEquals("5x5",near.options().radius());
        assertEquals("TIME_REQUIRED",assertThrows(QueryException.class,()->new QueryLimits(true,604800,15,100,1000,5).window(parse("near").time(),100000)).code());
        assertEquals("hello",parse("l a:chat t:1h c:hello").content());
        SpatialBounds b=SpatialResolver.resolve(parse("l a:chat t:1h world:w coord:-1.2,64,3 r:2x2").options(),null,0,0,0);
        assertEquals(new SpatialBounds("w",-4,0,62,66,1,5),b);
        assertNull(SpatialResolver.resolve(parse("l t:1h r:#global").options(),"w",0,0,0));
        assertEquals(new SpatialBounds("other",null,null,null,null,null,null),SpatialResolver.resolve(parse("l t:1h r:#other").options(),null,0,0,0));
        assertEquals("#we",parse("l t:1h radius:#we").options().radius());
        for(String invalid:List.of("r:-2","r:NaN","r:1xx3","r:1x2x3x4","coord:NaN,3","coord:1,2,3,4","x:3","r:999999999999"))
            assertThrows(invalid,QueryException.class,()->parse("l t:1h "+invalid));
        assertThrows(QueryException.class,()->SpatialResolver.resolve(parse("l t:1h r:1").options(),"w",Integer.MAX_VALUE,0,0));
        assertThrows(QueryException.class,()->parse("l a:username t:1h r:#global"));
    }
    @Test public void everyCoreProtect24ActionFamilyIsParsed() {
        Map<String,List<Integer>> expected=new LinkedHashMap<>();
        expected.put("block",List.of(0,1));expected.put("+block",List.of(1));expected.put("-block",List.of(0));expected.put("click",List.of(2));expected.put("kill",List.of(3));
        expected.put("inventory",List.of(4,11));expected.put("+inventory",List.of(4,11,0));expected.put("-inventory",List.of(4,11,1));
        expected.put("session",List.of(8));expected.put("+session",List.of(8,1));expected.put("-session",List.of(8,0));expected.put("sign",List.of(10));expected.put("username",List.of(9));
        expected.forEach((name,ids)->{
            assertEquals(ids,net.coreprotect.command.parser.ActionParser.parseAction(new String[]{"l","a:"+name}));
            assertEquals(name,parse("l a:"+name+" t:1h u:Steve").action().id());
        });
        assertThrows(QueryException.class,()->parse("l a:inventory t:1h"));
    }
    @Test public void optionsAndBoundsSurvivePagingAndCountHasNoFakeEmptyResult() {
        LookupRequest request=parse("near t:1h #count #verbose");
        SpatialBounds bounds=SpatialResolver.resolve(request.options(),"world",1,64,2);
        request=request.withOptions(request.options().resolved(bounds));
        LookupSessions sessions=new LookupSessions(Clock.systemUTC(),300,2);
        sessions.start("player",request);
        assertEquals(request.options(),sessions.page("player",2,10).request().options());
        assertTrue(parse("l t:1h #sum").options().countOnly());
        assertTrue(parse("l t:1h #verbose").options().verbose());
        assertFalse(parse("l t:1h #verbose #silent").options().verbose());
        LookupResult result=new LookupResult(true,null,"ok",LookupAction.ALL,1,15,10,false,2,List.of(),true,false);
        String rendered=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(LookupRenderer.render(result,0,false).get(0));
        assertTrue(rendered,rendered.contains("10 条"));assertFalse(rendered.contains("没有找到"));
    }
}
