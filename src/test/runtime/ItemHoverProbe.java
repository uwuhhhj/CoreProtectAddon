package coqtest;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import cc.carm.outsource.plugin.coreprotectaddon.command.LookupParameters;
import cc.carm.outsource.plugin.coreprotectaddon.command.LookupRenderer;
import cc.carm.outsource.plugin.coreprotectaddon.service.HistoricalItemDecoder;
import net.coreprotect.utility.ItemUtils;
import net.coreprotect.utility.serialize.ItemMetaHandler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.nio.file.*;
import java.util.List;

/** Isolated Paper smoke-test plugin; never install on a production server (shuts down after testing). */
public final class ItemHoverProbe extends JavaPlugin {
    @Override public void onEnable() {
        getServer().getScheduler().runTask(this, () -> {
            try {
                // The fixture runs instead of Main; initialize the packaged configuration explicitly.
                Class<?> config = Class.forName("cc.carm.outsource.plugin.coreprotectaddon.lib.mineconfiguration.bukkit.MineConfiguration");
                Object holder = config.getMethod("from",java.io.File.class,String.class)
                        .invoke(null,Path.of("coq-probe-config.yml").toFile(),"UTF-8");
                holder.getClass().getMethod("initialize",Class.class)
                        .invoke(holder,cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig.class);
                check(LookupParameters.parse("lookup user:Loliiiico include:potion time:1h".split(" "))
                        .request().action() == LookupAction.ITEM, "implicit item action");
                ItemStack potion = new ItemStack(Material.POTION, 3);
                PotionMeta meta = (PotionMeta) potion.getItemMeta();
                meta.displayName(Component.text("被封存的一缕灵魂"));
                meta.lore(List.of(Component.text("它不记归途，不识旧主"), Component.text("通陈 Alcoholic")));
                meta.addCustomEffect(new PotionEffect(PotionEffectType.LUCK, 2400, 2), true);
                meta.setColor(Color.PURPLE);
                meta.getPersistentDataContainer().set(new NamespacedKey(this,"custom_id"), PersistentDataType.STRING,"soul_wine");
                potion.setItemMeta(meta);
                byte[] bytes = ItemUtils.convertByteData(ItemMetaHandler.serialize(potion, null, null, 0));
                LookupRecord row = record("minecraft:potion",3,bytes);
                ItemStack restored = HistoricalItemDecoder.decode(row);
                check(potion.equals(restored), "full potion metadata round trip: " + restored);
                for (LookupAction action : List.of(LookupAction.ITEM, LookupAction.CONTAINER)) {
                    LookupResult result = new LookupResult(true,null,"ok",action,1,15,1,false,1,List.of(row));
                    Component line = LookupRenderer.render(result,120).get(1);
                    Component material = line.children().get(line.children().size()-1);
                    check(material.hoverEvent() != null && material.hoverEvent().action() == HoverEvent.Action.SHOW_ITEM, "native item hover");
                    check(material.hoverEvent().equals(potion.asHoverEvent()), "hover preserves components");
                    check(line.hoverEvent().action() == HoverEvent.Action.SHOW_TEXT,"location hover retained");
                }
                check(HistoricalItemDecoder.decode(record("minecraft:diamond",1,null)).getType() == Material.DIAMOND,"plain item");
                LookupRecord broken = record("minecraft:potion",1,new byte[]{1,2,3});
                LookupResult result = new LookupResult(true,null,"ok",LookupAction.ITEM,1,15,2,false,1,List.of(broken,row));
                List<Component> lines = LookupRenderer.render(result,120);
                Component first = lines.get(1), second = lines.get(2);
                check(first.children().get(first.children().size()-1).hoverEvent().action() == HoverEvent.Action.SHOW_TEXT,"corrupt record fallback");
                check(second.children().get(second.children().size()-1).hoverEvent().action() == HoverEvent.Action.SHOW_ITEM,"corruption does not break next record");
                Files.writeString(Path.of("coq-item-probe.txt"), "PASS: implicit potion lookup; native item/container hover; name, lore, potion effects, color, persistent data, amount; plain item; corrupt-row isolation.\n" + Bukkit.getVersion());
                new cc.carm.outsource.plugin.coreprotectaddon.command.PanelProbe(this,row).run();
            } catch (Throwable failure) {
                failure.printStackTrace();
                try { Files.writeString(Path.of("coq-item-probe.txt"),"FAIL: " + failure); } catch (Exception ignored) { }
                Bukkit.shutdown();
            }
        });
    }
    private static LookupRecord record(String material,int amount,byte[] bytes) {
        return new LookupRecord(1,100,1,"Loliiiico","world",1,64,2,null,material,amount,3,new ItemSnapshot(bytes));
    }
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
