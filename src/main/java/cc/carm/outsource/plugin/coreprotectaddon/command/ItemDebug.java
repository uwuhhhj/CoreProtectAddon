package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.Main;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig;
import cc.carm.outsource.plugin.coreprotectaddon.service.ItemContent;
import cc.carm.outsource.plugin.coreprotectaddon.service.ItemComponents;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.*;
import static net.kyori.adventure.text.format.NamedTextColor.*;

final class ItemDebug {
    private final Main plugin;
    private final ItemPreparationQueue preparation;
    private final Set<UUID> pending = new HashSet<>();
    private volatile boolean closed;
    ItemDebug(Main plugin,ItemPreparationQueue preparation) { this.plugin = plugin; this.preparation = preparation; }
    void close() { closed = true; pending.forEach(id -> plugin.queryTasks().cancel("debug:"+id)); pending.clear(); }
    void execute(CommandSender sender,String[] args) {
        if (!sender.hasPermission("coreprotectaddon.command.debug")) {
            LookupRenderer.send(sender,LookupRenderer.notice("你没有查看手持物品组件的权限。")); return;
        }
        try {
            if (args.length > 1 && args[1].equalsIgnoreCase("whitelist")) {
                whitelist(plugin,sender,args);
                return;
            }
            if (args.length > 3 || (args.length > 1 && !args[1].equalsIgnoreCase("item"))) throw usage();
            int page = 1;
            if (args.length == 3) {
                try { page = Integer.parseInt(args[2]); } catch (NumberFormatException ex) { throw usage(); }
            }
            if (!(sender instanceof Player player)) throw new QueryException("PLAYER_ONLY","请在游戏内手持物品后执行 /coq debug item。");
            UUID id = player.getUniqueId();
            if (!pending.add(id)) throw new QueryException("QUERY_BUSY","上一条物品详情仍在处理，请稍候。");
            final int selectedPage = page;
            preparation.submitAction(() -> {
                if (closed || !player.isOnline()) { pending.remove(id); return false; }
                return true;
            },() -> {
                try {
                    var item = player.getInventory().getItemInMainHand();
                    if (item.getType().isAir()) throw new QueryException("EMPTY_HAND","主手没有物品。");
                    Set<String> keys = new TreeSet<>();
                    item.getDataTypes().forEach(type -> keys.add(type.getKey().toString()));
                    int pages = Math.max(1,(keys.size()+9)/10);
                    if (selectedPage < 1 || selectedPage > pages) throw new QueryException("INVALID_PAGE","组件页码应为 1–"+pages+"。");
                    Set<String> selected = new LinkedHashSet<>(keys.stream().skip((selectedPage-1L)*10).limit(10).toList());
                    var values = ItemComponents.values(item,selected);
                    String material = item.getType().getKey().toString();
                    boolean manage = player.hasPermission("coreprotectaddon.command.reload");
                    var whitelist = Set.copyOf(PluginConfig.ITEM_PANEL.COMPONENT_WHITELIST.copy());
                    plugin.queryTasks().submit("debug:"+id,5,cancellation -> {
                        cancellation.check();
                        return describePage(material,values,selectedPage,keys.size(),manage,whitelist);
                    },(lines,failure) -> {
                        if (closed || !plugin.isEnabled()) return;
                        plugin.getServer().getScheduler().runTask(plugin,() -> {
                            pending.remove(id);
                            if (closed || !player.isOnline()) return;
                            if (failure == null) lines.forEach(line -> LookupRenderer.send(player,line));
                            else LookupRenderer.send(player,LookupRenderer.notice("物品详情处理失败或超时，请稍后重试。"));
                        });
                    });
                } catch (RuntimeException ex) {
                    pending.remove(id); LookupRenderer.send(player,LookupRenderer.notice(ex instanceof QueryException ? ex.getMessage() : "无法读取物品详情。"));
                }
            });
        } catch (QueryException ex) { LookupRenderer.send(sender,LookupRenderer.notice(ex.getMessage())); }
    }

    static List<Component> describe(String material,Map<String,Object> values,int page) {
        return describe(material,values,page,false,Set.of());
    }
    static List<Component> describe(String material,Map<String,Object> values,int page,boolean canManage,Set<String> whitelist) {
        int pages = Math.max(1,(values.size()+9)/10);
        if (page < 1 || page > pages) throw new QueryException("INVALID_PAGE","组件页码应为 1–" + pages + "。");
        Map<String,Object> selected = new LinkedHashMap<>();
        new TreeMap<>(values).entrySet().stream().skip((page-1L)*10).limit(10).forEach(e -> selected.put(e.getKey(),e.getValue()));
        return describePage(material,selected,page,values.size(),canManage,whitelist);
    }
    private static List<Component> describePage(String material,Map<String,Object> values,int page,int total,boolean canManage,Set<String> whitelist) {
        int pages = Math.max(1,(total+9)/10);
        List<Component> lines = new ArrayList<>();
        lines.add(LookupRenderer.notice("主手 " + material + " · 全部有效组件 " + total + " 个（含默认值）")
                .append(copy(" [复制物品ID]",material)));
        lines.add(Component.text("每行一个组件；复制值或完整 content 条件。第 " + page + "/" + pages + " 页",GRAY));
        var entries = new TreeMap<>(values).entrySet();
        for (var entry : entries) {
            String value = entry.getValue().toString();
            String preview = value.replace('\n',' ').replace('\r',' ').replace("§","\\u00a7");
            if (preview.length() > 100) preview = preview.substring(0,100) + "…";
            Component line = Component.text(entry.getKey() + " = " + preview,AQUA)
                    .append(copy(" [复制值]",value))
                    .append(copy(" [复制条件]",ItemComponents.condition(entry.getKey(),entry.getValue())));
            if (canManage) line = line
                    .append(manage(" [加入白名单]","add",entry.getKey(),!whitelist.contains(entry.getKey())))
                    .append(manage(" [移出白名单]","remove",entry.getKey(),whitelist.contains(entry.getKey())));
            lines.add(line);
        }
        Component footer = Component.empty();
        if (page > 1) footer = footer.append(page("[上一页] ",page-1));
        if (page < pages) footer = footer.append(page("[下一页]",page+1));
        lines.add(footer);
        return lines;
    }
    private static Component copy(String label,String value) {
        return Component.text(label,DARK_AQUA).clickEvent(ClickEvent.copyToClipboard(value))
                .hoverEvent(Component.text("复制完整内容（" + value.length() + " 字符），不受预览省略影响。"));
    }
    private static Component manage(String label,String action,String key,boolean changes) {
        return Component.text(label,changes ? DARK_AQUA : GRAY)
                .clickEvent(ClickEvent.runCommand("/coq debug whitelist " + action + " " + key))
                .hoverEvent(Component.text(changes ? "保存到配置文件并立即更新组件补全与面板白名单。" : "当前已是此状态；重复点击不会产生重复项。"));
    }
    private static void whitelist(Main plugin,CommandSender sender,String[] args) {
        if (!sender.hasPermission("coreprotectaddon.command.reload"))
            throw new QueryException("NO_PERMISSION","修改组件白名单需要 coreprotectaddon.command.reload 权限。");
        if (args.length != 4 || (!args[2].equalsIgnoreCase("add") && !args[2].equalsIgnoreCase("remove")))
            throw new QueryException("INVALID_PARAMETER","用法：/coq debug whitelist <add|remove> <组件名>");
        String key = ItemContent.componentId(args[3]);
        boolean add = args[2].equalsIgnoreCase("add");
        try {
            plugin.updateComponentWhitelist(key,add,(changed,failure) -> {
                if (failure != null) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,"Unable to save component whitelist",failure);
                    LookupRenderer.send(sender,LookupRenderer.notice("白名单保存失败，未更新生效配置。")); return;
                }
                String state = changed ? (add ? "已加入白名单：" : "已移出白名单：") : (add ? "已在白名单中：" : "已不在白名单中：");
                LookupRenderer.send(sender,LookupRenderer.notice(state + key + "。配置已保存，补全与面板立即生效。"));
            });
        } catch (Exception ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,"Unable to save component whitelist",ex);
            throw new QueryException("CONFIG_SAVE_FAILED","白名单保存失败，未更新生效配置。请检查配置文件与服务器日志。");
        }
    }
    private static Component page(String label,int page) {
        return Component.text(label,DARK_AQUA).clickEvent(ClickEvent.runCommand("/coq debug item " + page));
    }
    private static QueryException usage() { return new QueryException("INVALID_PARAMETER","用法：/coq debug item [页码]"); }
}
