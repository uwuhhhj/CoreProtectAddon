package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import cc.carm.outsource.plugin.coreprotectaddon.service.*;
import net.coreprotect.command.parser.ActionParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.Test;
import java.time.*;
import java.util.List;
import static org.junit.Assert.*;

public class LookupCommandTest {
    @Test public void helpNavigationAndAdministrativeCommandsRespectPermissions() {
        assertTrue(LookupRenderer.home(false).size() < LookupRenderer.help(1,false).size());
        assertTrue(LookupRenderer.home(false).stream().flatMap(c -> clicks(c).stream()).noneMatch("/coq reload"::equals));
        assertTrue(LookupRenderer.home(true).stream().flatMap(c -> clicks(c).stream()).anyMatch("/coq reload"::equals));
        for (int page=1; page<=3; page++) {
            var lines = LookupRenderer.help(page,true);
            assertTrue(plain(lines.get(0)).contains(page + "/3"));
            if (page<3) assertTrue(clicks(lines.get(lines.size()-1)).contains("/coq help " + (page+1)));
        }
        assertThrows(IllegalArgumentException.class,() -> LookupRenderer.help(4,true));
        assertEquals(List.of("1","2","3"),complete("help",""));
        assertEquals(List.of("reload"),complete("rel"));
        assertTrue(complete("reload","").isEmpty());
    }
    private static LookupParameters.Parsed parse(String command) { return LookupParameters.parse(command.split(" ")); }
    private static void rejects(String code, Runnable task) {
        QueryException error = assertThrows(QueryException.class, task::run);
        assertEquals(code, error.code());
    }
    @Test public void aliasesListsAndRegexSurviveParsing() {
        LookupRequest r = parse("lookup action:+items users:Steve, Alex time:1.5h include:iron_ingot, minecraft:diamond exclude:iron_ingot page:2:10").request();
        assertEquals(LookupAction.ITEM_ADD, r.action());
        assertEquals(List.of("Steve", "Alex"), r.users());
        assertEquals(List.of("iron_ingot", "minecraft:diamond"), r.include());
        assertEquals(Integer.valueOf(2), r.page());
        assertEquals(Integer.valueOf(10), r.pageSize());
        assertEquals("hello \\d+.*world", parse("l a:chat t:1d content:\"hello \\d+.*world\"").request().content());
        assertEquals("/op", parse("l a:command t:1h command:/op").request().content());
    }
    @Test public void omittedActionResolvesOnlyUnambiguousPositiveItemIncludes() {
        java.util.function.Function<String, LookupRequest> implicit = command -> LookupParameters.parse(command.split(" "),
                name -> List.of("minecraft:potion", "minecraft:diamond").contains(name)).request();
        assertEquals(LookupAction.ITEM, implicit.apply("lookup user:Loliiiico include:potion time:1h").action());
        assertEquals(LookupAction.ITEM, implicit.apply("l i:minecraft:potion,diamond t:1h").action());
        assertEquals(LookupAction.CONTAINER, implicit.apply("l a:container i:potion t:1h").action());
        for (String command : List.of("l t:1h", "l e:potion t:1h", "l i:stone t:1h",
                "l i:stone,potion t:1h"))
            assertEquals(LookupAction.ALL,implicit.apply(command).action());
        rejects("INCOMPATIBLE_PARAMETER", () -> implicit.apply("l a:chat i:potion t:1h"));
    }
    @Test public void inventoryPageLimitsDoNotSilentlyTruncateChatPages() {
        ItemLookupPanel.validate(parse("l a:item t:1h rows:45").request());
        ItemLookupPanel.validate(parse("l a:container t:1h rows:1").request());
        rejects("INVALID_PAGE", () -> ItemLookupPanel.validate(parse("l a:item t:1h rows:46").request()));
        rejects("INCOMPATIBLE_PARAMETER", () -> ItemLookupPanel.validate(parse("l a:chat t:1h rows:15").request()));
        assertTrue(complete("items", "1:").contains("1:45"));
    }
    @Test public void itemHoverOverridesLocationOnlyOnTheMaterialAndConsoleNeverDecodes() {
        LookupRecord row = new LookupRecord(1, 100, 1, "Loliiiico", "world", 1, 64, 2,
                null, "minecraft:potion", 1, 3, new ItemSnapshot(new byte[]{1}));
        LookupResult result = new LookupResult(true,null,"ok",LookupAction.ITEM,1,15,1,false,1,List.of(row));
        HoverEvent<?> item = HoverEvent.showItem(Key.key("minecraft:potion"),1);
        Component line = LookupRenderer.render(result,120,true,r -> { assertSame(row,r); return item; }).get(1);
        assertEquals(HoverEvent.Action.SHOW_TEXT, line.hoverEvent().action());
        Component material = line.children().stream().filter(c -> plain(c).equals("minecraft:potion")).findFirst().orElseThrow();
        assertEquals(item, material.hoverEvent());
        assertNoClick(line);
        LookupRenderer.render(result,120,false,r -> { fail("Console must not decode item metadata"); return null; });
    }
    @Test public void legacyRootsAndUnsupportedFiltersNeverBecomeBroadQueries() {
        for (String root : List.of("chat", "command", "item")) rejects("UNKNOWN_COMMAND", () -> parse(root + " t:1d"));
        for (String command : List.of("l a:item t:1d #preview", "l a:item t:1d i:#logs")) rejects("UNSUPPORTED_QUERY", () -> parse(command));
        rejects("INVALID_PARAMETER", () -> parse("l a:item t:1d typoo:foo"));
        rejects("INVALID_PARAMETER", () -> parse("l a:item t:1d time:1h"));
        rejects("INVALID_PARAMETER", () -> parse("l a:item t:1d i:stone,,dirt"));
        rejects("INCOMPATIBLE_PARAMETER", () -> parse("l a:chat t:1d i:diamond"));
        rejects("INVALID_COMPONENT_CONTENT", () -> parse("l a:item t:1d content:test"));
    }
    @Test public void componentsAreExplicitlyRejectedEvenWithSpaces() {
        rejects("UNSUPPORTED_COMPONENTS", () -> parse("l a:item t:1d i:minecraft:iron_ingot[custom_name={extra: [\"1\"], text: \"\"}]"));
        rejects("UNSUPPORTED_COMPONENTS", () -> parse("l a:item t:1d e:paper{tag:1}"));
    }
    @Test public void copiedComponentCompoundPreservesNestedTextSpacesAndTypes() {
        String content = "{\"minecraft:custom_data\":{label:'a time:1h \\\"b\\\"',bytes:[B;1b,-2b],nested:[{text:\"a \\\"quote\\\" \\\\ end\"}]},\"minecraft:max_stack_size\":1}";
        LookupRequest request = parse("l a:item content:" + content + " t:1d rows:3").request();
        assertEquals(content,request.content());
        assertEquals("1d",request.time());
        assertEquals(Integer.valueOf(3),request.pageSize());
        assertEquals(content,parse("l a:container t:1d content:" + content).request().content());
        for (String invalid : List.of("{", "{a:[1}", "{a:\"unterminated}", "{a:1]"))
            rejects("INVALID_COMPONENT_CONTENT", () -> parse("l a:item t:1h content:" + invalid));
    }
    @Test public void panelLocationRunsOnlyTheRequestedTpposCommand() {
        LookupRecord row = new LookupRecord(260,100,1,"Steve","world",-7470,111,11197,null,"minecraft:potion",1,3);
        LookupResult result = new LookupResult(true,null,"ok",LookupAction.ITEM,4,15,200,false,1,List.of(row));
        List<Component> lines = ItemPanelDetails.describe(row,result,null,List.of(),100);
        assertEquals(List.of("/tppos -7470 111 11197"),clicks(lines.get(1)));
        Component position = lines.get(1).children().get(0);
        assertEquals(net.kyori.adventure.text.event.ClickEvent.Action.RUN_COMMAND,position.clickEvent().action());
        LookupRecord unknown = new LookupRecord(260,100,1,"Steve","world",null,111,11197,null,"minecraft:potion",1,3);
        assertTrue(clicks(ItemPanelDetails.describe(unknown,result,null,List.of(),100).get(1)).isEmpty());
    }
    @Test public void pageCommandsAndOverflow() {
        assertTrue(parse("l 2:30").continuation());
        assertEquals(Integer.valueOf(30), parse("l 2:30").rows());
        assertEquals(Integer.valueOf(2), parse("page 2").page());
        assertEquals(Integer.valueOf(2), parse("l page:2").page());
        rejects("INVALID_PAGE", () -> parse("l 0"));
        rejects("INVALID_PAGE", () -> parse("l 99999999999999999999"));
        rejects("INVALID_PAGE", () -> parse("l a:item t:1d page:1:2 rows:3"));
    }
    @Test public void implementedActionsMatchCoreProtect232Parser() {
        for (String alias : List.of("item", "items", "+item", "items+", "pickup", "withdraw", "-item", "drop", "deposit",
                "container", "chest", "+container", "add-container", "-container", "remove-container", "chat", "commands")) {
            LookupAction action = LookupAction.byId(alias);
            List<Integer> nativeAction = ActionParser.parseAction(new String[]{"lookup", "a:" + alias});
            List<Integer> expected = switch (action) {
                case ITEM -> List.of(11); case ITEM_ADD -> List.of(11, 1); case ITEM_REMOVE -> List.of(11, 0);
                case CONTAINER -> List.of(4); case CONTAINER_ADD -> List.of(4, 1); case CONTAINER_REMOVE -> List.of(4, 0);
                case CHAT -> List.of(6); case COMMAND -> List.of(7);
                default -> throw new AssertionError("Unexpected action in item/message fixture: " + action);
            };
            assertEquals(alias, expected, nativeAction);
        }
    }
    @Test public void completionCoversOnlyNewRootsAndCanonicalKeyAliases() {
        List<String> roots = complete("");
        assertTrue(roots.containsAll(List.of("l", "lookup", "help", "page", "near")));
        assertFalse(roots.contains("chat")); assertFalse(roots.contains("command")); assertFalse(roots.contains("item"));
        assertEquals(List.of("u:Steve,Alex"), complete("l", "u:Steve,A"));
        assertEquals(List.of("i:minecraft:iron_ingot"), complete("l", "i:minecraft:ir"));
        assertTrue(complete("l", "a:").contains("a:+item"));
        assertTrue(complete("l", "a:").contains("a:block"));
        assertTrue(complete("l", "radius:").contains("radius:#worldedit"));
        assertTrue(complete("l", "time:").contains("time:1d"));
        assertTrue(complete("l", "time:2h30").contains("time:2h30m"));
        assertEquals(List.of("iron_ingot"), complete("l", "i:", "ir"));
        assertTrue(complete("l", "a:", "").contains("+item"));
        assertFalse(complete("l", "a:item", "").contains("action:"));
        assertTrue(complete("chat", "").isEmpty());
    }
    private static List<String> complete(String... args) {
        return QueryTabCompleter.complete(args, List.of("Steve", "Alex"), List.of("iron_ingot", "diamond"), List.of("world"));
    }
    @Test public void sessionsAreIsolatedBoundedAndKeepTimeAcrossPaging() {
        MutableClock clock = new MutableClock();
        LookupSessions sessions = new LookupSessions(clock, 300, 2);
        LookupRequest request = parse("l a:item t:1d page:1:15").request();
        LookupSessions.Session first = sessions.start("one", request);
        rejects("NO_QUERY", () -> sessions.require("two"));
        clock.millis += 100_000;
        LookupSessions.Session next = sessions.page("one", 2, 30);
        assertEquals(first.anchor(), next.anchor());
        assertEquals(first.expiresAt(), next.expiresAt());
        assertEquals(Integer.valueOf(30), sessions.require("one").request().pageSize());
        sessions.start("two", request); sessions.start("three", request);
        rejects("NO_QUERY", () -> sessions.require("one"));
        clock.millis += 300_000;
        sessions.start("two", request);
        rejects("QUERY_EXPIRED", () -> sessions.require("three"));
        LookupSessions.Session fresh = sessions.start("two", request);
        assertEquals(clock.millis / 1000, fresh.anchor());
    }
    @Test public void databaseMarkupIsLiteralAndOnlyFooterHasClickActions() {
        String raw = "<click:run_command:'/op attacker'>hello</click>";
        LookupRecord record = new LookupRecord(1, 100, 1, "Steve", "world", 1, 64, 2, raw, null, 0, 0);
        LookupResult result = new LookupResult(true, null, "ok", LookupAction.CHAT, 2, 1, 3, true, 2, List.of(record));
        List<Component> lines = LookupRenderer.render(result, 120);
        assertTrue(plain(lines.get(1)).contains(raw));
        assertNoClick(lines.get(1));
        assertNotNull(lines.get(1).hoverEvent());
        String hover = plain((Component) lines.get(1).hoverEvent().value());
        assertTrue(hover.contains("world")); assertTrue(hover.contains("1, 64, 2"));
        assertTrue(plain(lines.get(2)).contains("仅展示前 3 条"));
        assertTrue(clicks(lines.get(3)).containsAll(List.of("/coq l 1", "/coq l ", "/coq l 3")));
        List<Component> console = LookupRenderer.render(result, 120, false);
        assertTrue(plain(console.get(1)).contains("fixture_world") || plain(console.get(1)).contains("world"));
        assertTrue(plain(console.get(1)).contains("1, 64, 2"));
        assertTrue(plain(console.get(3)).contains("/coq l <页码>"));
    }
    @Test public void actionLabelsDisambiguateEnderChestAndOrdinaryContainers() {
        assertEquals("末影箱取出", LookupRenderer.actionText(LookupAction.ITEM, 4));
        assertTrue(LookupRenderer.positive(LookupAction.ITEM, 4));
        assertFalse(LookupRenderer.positive(LookupAction.ITEM, 5));
        assertEquals("放入容器", LookupRenderer.actionText(LookupAction.CONTAINER, 1));
        assertTrue(LookupRenderer.positive(LookupAction.CONTAINER, 1));
        assertFalse(LookupRenderer.positive(LookupAction.CONTAINER, 0));
    }
    private static String plain(Component c) { return PlainTextComponentSerializer.plainText().serialize(c); }
    private static void assertNoClick(Component c) { assertNull(c.clickEvent()); c.children().forEach(LookupCommandTest::assertNoClick); }
    private static List<String> clicks(Component c) {
        java.util.ArrayList<String> events = new java.util.ArrayList<>();
        if (c.clickEvent() != null) events.add(c.clickEvent().value());
        for (Component child : c.children()) events.addAll(clicks(child));
        return events;
    }
    private static final class MutableClock extends Clock {
        long millis = 1_800_000_000_000L;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
