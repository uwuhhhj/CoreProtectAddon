package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import cc.carm.outsource.plugin.coreprotectaddon.service.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Native registry/codec and shared scheduler verification; runs only in the disposable server. */
public final class ContentProbe {
    private final JavaPlugin plugin;
    private final LookupRecord row;
    private final ItemPreparationQueue queue;
    public ContentProbe(JavaPlugin plugin,LookupRecord row) {
        this.plugin=plugin; this.row=row; this.queue=new ItemPreparationQueue(plugin);
    }
    public void run() {
        try {
            ItemStack item = HistoricalItemDecoder.decode(row);
            Set<String> keys = item.getDataTypes().stream().map(t -> t.getKey().toString()).collect(Collectors.toSet());
            Map<String,Object> all = ItemComponents.values(item,keys);
            check(all.keySet().equals(keys),"ALL effective components must be encodable: " + keys + " vs " + all.keySet());
            check(all.containsKey("minecraft:max_stack_size"),"default component included");
            for (var entry : all.entrySet()) {
                String condition = ItemComponents.condition(entry.getKey(),entry.getValue());
                String parsed = LookupParameters.parse(("lookup a:item t:1h content:"+condition+" rows:3").split(" ")).request().content();
                check(condition.equals(parsed),"clipboard survives command parsing: " + entry.getKey());
                check(ItemComponents.matches(ItemComponents.parse(parsed),all),"native component roundtrip: " + entry.getKey());
            }
            check(!ItemComponents.matches(ItemComponents.parse("{\"minecraft:max_stack_size\":64}"),all),"different default value rejected");
            check(!ItemComponents.matches(ItemComponents.parse("{\"minecraft:max_stack_size\":1b}"),all),"NBT numeric types remain distinct");
            check(!ItemComponents.matches(ItemComponents.parse("{\"minecraft:damage\":1}"),all),"missing component cannot match");
            var left = ItemComponents.parse("{\"minecraft:custom_data\":{a:1,b:[B;1b,-2b],c:{text:'a b',value:2L}}}");
            var right = ItemComponents.parse("{\"minecraft:custom_data\":{c:{value:2L,text:'a b'},b:[B;1b,-2b],a:1}}");
            check(ItemComponents.matches(left,right),"compound order ignored, typed arrays preserved");
            for (String bad : List.of("{}","{oops:1}","{\"minecraft:lore\":}")) rejects("INVALID_COMPONENT_CONTENT",() -> ItemComponents.parse(bad));
            LookupResult result = new LookupResult(true,null,"ok",LookupAction.ITEM,1,15,1,false,1,List.of(row));
            List<Component> details = ItemPanelDetails.describe(row,result,item,List.of("minecraft:lore","minecraft:max_stack_size"),20);
            check(details.size()==5,"only whitelist components printed");
            check(plain(details.get(3)).startsWith("minecraft:lore"),"whitelist order preserved");
            check(plain(details.get(3)).contains("…"),"long preview is bounded");
            for (Component line : details) if (line.clickEvent()!=null) {
                check(line.clickEvent().action()==ClickEvent.Action.COPY_TO_CLIPBOARD,"component line copies");
                check(ItemComponents.matches(ItemComponents.parse(line.clickEvent().value()),all),"copied condition includes complete typed value");
            }
            check(details.get(3).clickEvent().value().contains("Alcoholic"),"truncation never changes clipboard");
            check(details.get(1).children().get(0).clickEvent().value().equals("/tppos 1 64 2"),"exact tppos syntax");
            check(ItemPanelDetails.describe(row,result,item,List.of(),20).size()==3,"empty whitelist produces info only");
            String condition = ItemComponents.condition("minecraft:custom_name",all.get("minecraft:custom_name"));
            Bukkit.getScheduler().runTaskAsynchronously(plugin,() -> async(condition,keys.size()));
        } catch (Throwable failure) { finish(failure,0); }
    }
    private void async(String condition,int count) {
        try {
            LookupRecord plain = new LookupRecord(2,100,1,"Steve","world",1,64,2,null,"minecraft:potion",1,3,new ItemSnapshot(null));
            BitSet matched = queue.match(List.of(plain,row),condition,System.nanoTime()+5_000_000_000L);
            check(matched.equals(BitSet.valueOf(new long[]{2})),"async main-thread matching selects only historical named potion");
            matched = queue.match(List.of(plain,row),"{\"minecraft:max_stack_size\":1}",System.nanoTime()+5_000_000_000L);
            check(matched.cardinality()==2,"default component searches are independent of display whitelist");
            rejects("INVALID_COMPONENT_CONTENT",() -> queue.match(List.of(),"{}",System.nanoTime()+5_000_000_000L));
            LookupRecord broken = new LookupRecord(3,100,1,"Steve","world",1,64,2,null,"minecraft:potion",1,3,new ItemSnapshot(new byte[]{1,2,3}));
            rejects("COMPONENT_DECODE_FAILED",() -> queue.match(List.of(broken),condition,System.nanoTime()+5_000_000_000L));
            rejects("QUERY_TIMEOUT",() -> queue.match(Collections.nCopies(32,row),condition,System.nanoTime()+1_000_000L));
            Bukkit.getScheduler().runTask(plugin,() -> finish(null,count));
        } catch (Throwable failure) { Bukkit.getScheduler().runTask(plugin,() -> finish(failure,count)); }
    }
    private void finish(Throwable failure,int count) {
        queue.close();
        if (failure!=null) failure.printStackTrace();
        try { Files.writeString(Path.of("coq-content-probe.txt"), failure==null
                ? "PASS: all " + count + " effective components copied/parsed/matched; defaults; typed NBT; compound order; whitelist/preview/full clipboard; tppos; async scheduler; invalid content; corrupt metadata; timeout.\n" + Bukkit.getVersion()
                : "FAIL: " + failure); } catch (Exception e) { e.printStackTrace(); }
        Bukkit.shutdown();
    }
    private static String plain(Component c) { return PlainTextComponentSerializer.plainText().serialize(c); }
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
    private static void rejects(String code,Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (QueryException e) { check(code.equals(e.code()),"expected " + code + " got " + e.code()); }
    }
}
