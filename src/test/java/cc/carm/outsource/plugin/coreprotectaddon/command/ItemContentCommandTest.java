package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.service.ItemContent;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryException;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ItemContentCommandTest {
    @Test public void mapAndPresenceInputsSurviveCommandParsing() {
        for (String content : List.of("minecraft:filled_map","minecraft:map_id","minecraft:filled_map[map_id=101205]")) {
            var request = LookupParameters.parse(("lookup user:Loliiiico time:1d action:item content:\"" + content + "\"").split(" ")).request();
            assertEquals(content,request.content());
        }
        String content = "minecraft:filled_map[map_id=101205,custom_data={text:'a b,c=2',bytes:[B;1b,2b]}]";
        assertEquals(content,LookupParameters.parse(("lookup a:item content:" + content + " t:1d").split(" ")).request().content());
        var parsed = ItemContent.parse(content);
        assertTrue(parsed.itemOnly());
        assertEquals(Set.of("minecraft:map_id","minecraft:custom_data"),parsed.present());
        assertTrue(parsed.compound().contains("\"minecraft:map_id\":101205"));
        assertNull(ItemContent.parse("minecraft:filled_map[map_id]").compound());
        assertEquals("test",ItemContent.parse("test").valueSearch());
        for (String bad : List.of("minecraft:filled_map[map_id=]","minecraft:filled_map[map_id=1,]",
                "minecraft:filled_map[map_id=1,map_id=2]","minecraft:filled_map[custom_data={a:1]]"))
            assertThrows(bad,QueryException.class,() -> ItemContent.parse(bad));
    }
    @Test public void completionUsesCurrentWhitelistAndPreservesQuotes() {
        List<String> keys = List.of("minecraft:map_id","minecraft:lore","bad key","minecraft:map_id");
        assertEquals(List.of("content:\"minecraft:map_id\"","content:\"minecraft:lore\""),
                complete(keys,"lookup","a:item","content:\""));
        assertEquals(List.of("c:'minecraft:map_id'"),complete(keys,"lookup","a:item","c:'minecraft:ma"));
        assertEquals(List.of("\"minecraft:map_id\""),complete(keys,"lookup","content:","\"minecraft:ma"));
        assertTrue(complete(List.of(),"lookup","content:\"").isEmpty());
        assertEquals(List.of("content:\"minecraft:custom_data\""),complete(List.of("minecraft:custom_data"),"lookup","content:\""));
        assertTrue(complete(keys,"lookup","a:chat","content:\"").isEmpty());
    }
    @Test public void debugPaginatesEveryComponentAndCopiesFullValues() {
        Map<String,Object> values = new TreeMap<>();
        String longValue = "\"" + "long text ".repeat(40) + "\"";
        for (int i=0;i<23;i++) values.put("minecraft:test_"+String.format("%02d",i),longValue);
        int seen = 0;
        for (int page=1;page<=3;page++) {
            var lines = ItemDebug.describe("minecraft:filled_map",values,page);
            for (int i=2;i<lines.size()-1;i++) {
                var buttons = lines.get(i).children();
                assertEquals(ClickEvent.Action.COPY_TO_CLIPBOARD,buttons.get(0).clickEvent().action());
                assertEquals(longValue,buttons.get(0).clickEvent().value());
                assertTrue(buttons.get(1).clickEvent().value().contains(longValue));
                seen++;
            }
        }
        assertEquals(23,seen);
        assertThrows(QueryException.class,() -> ItemDebug.describe("minecraft:filled_map",values,4));
    }
    private static List<String> complete(List<String> keys,String... args) {
        return QueryTabCompleter.complete(args,List.of(),List.of(),List.of(),keys);
    }
}
