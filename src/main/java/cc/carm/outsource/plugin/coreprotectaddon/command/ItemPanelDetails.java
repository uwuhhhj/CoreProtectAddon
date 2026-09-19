package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig;
import cc.carm.outsource.plugin.coreprotectaddon.service.ItemComponents;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.inventory.ItemStack;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class ItemPanelDetails {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());
    private ItemPanelDetails() { }

    static List<Component> describe(LookupRecord row, LookupResult result, LookupSessions.Session session, ItemStack item) {
        return describe(row,result,item,PluginConfig.ITEM_PANEL.COMPONENT_WHITELIST.copy(),
                PluginConfig.ITEM_PANEL.COMPONENT_PREVIEW_LENGTH.getNotNull());
    }

    static List<Component> describe(LookupRecord row,LookupResult result,ItemStack item,List<String> whitelist,int previewLength) {
        List<Component> lines = new ArrayList<>();
        lines.add(LookupRenderer.notice("记录 #" + row.rowId() + " · " + row.playerName() + " · "
                + LookupRenderer.actionText(result.action(),row.action()) + " · " + row.material() + " × " + row.amount()));
        Component location = Component.text(DATE.format(Instant.ofEpochSecond(row.time())) + " · " + row.world() + " · ",NamedTextColor.GRAY);
        if (row.x() != null && row.y() != null && row.z() != null) {
            String command = "/tppos " + row.x() + " " + row.y() + " " + row.z();
            location = location.append(Component.text("[" + command + "]",NamedTextColor.DARK_AQUA)
                    .clickEvent(ClickEvent.runCommand(command)).hoverEvent(Component.text("点击执行 " + command)));
        } else location = location.append(Component.text("位置未知"));
        lines.add(location);
        lines.add(Component.text("a:" + result.action().id() + " · 第 " + result.page() + "/" + result.totalPages()
                + " 页 · 点击组件行复制完整条件，粘贴到 content: 后",NamedTextColor.GRAY));
        if (item == null) { lines.add(LookupRenderer.notice("历史元数据无法还原，不能提供可靠组件条件。")); return lines; }
        List<String> keys = whitelist.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().limit(12).toList();
        if (keys.isEmpty()) return lines;
        try {
            Map<String,Object> values = ItemComponents.values(item,new LinkedHashSet<>(keys));
            for (String key : keys) if (values.containsKey(key)) {
                String condition = ItemComponents.condition(key,values.get(key));
                String preview = values.get(key).toString().replace('\n',' ').replace('\r',' ').replace("§","\\u00a7");
                int length = Math.max(20,Math.min(300,previewLength));
                if (preview.length() > length) preview = preview.substring(0,length) + "…";
                Component line = Component.text(key + " = " + preview,NamedTextColor.AQUA);
                if (condition.length() <= ItemComponents.MAX_CONTENT)
                    line = line.append(Component.text(" [复制]",NamedTextColor.DARK_AQUA))
                            .clickEvent(ClickEvent.copyToClipboard(condition))
                            .hoverEvent(Component.text("复制完整 content 条件（" + condition.length() + " 字符），预览省略不影响复制内容。"));
                else line = line.append(Component.text(" [超过单条条件长度限制，无法复制查询]",NamedTextColor.GRAY));
                lines.add(line);
            }
        } catch (QueryException ex) { lines.add(LookupRenderer.notice(ex.getMessage())); }
        return lines;
    }
}
