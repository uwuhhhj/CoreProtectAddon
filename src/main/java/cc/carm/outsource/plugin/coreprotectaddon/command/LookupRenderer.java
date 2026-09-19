package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import static net.kyori.adventure.text.format.NamedTextColor.*;

public final class LookupRenderer {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());
    private LookupRenderer() { }
    public static Component prefix() { return Component.text("COQ » ", DARK_AQUA); }
    public static Component notice(String text) { return prefix().append(Component.text(text, WHITE)); }
    public static void send(CommandSender sender, Component text) {
        if (sender instanceof Player player) player.sendMessage(text);
        else sender.sendMessage(PlainTextComponentSerializer.plainText().serialize(text));
    }
    public static List<Component> render(LookupResult result, long now) {
        return render(result, now, true);
    }
    public static List<Component> render(LookupResult result, long now, boolean interactive) {
        return render(result, now, interactive, LookupRenderer::itemHover);
    }
    static List<Component> render(LookupResult result, long now, boolean interactive,
                                  Function<LookupRecord, HoverEvent<?>> itemHover) {
        if (!result.success()) return List.of(prefix().append(Component.text(result.message(), RED)));
        if (result.countOnly()) return List.of(notice((result.truncated() ? "匹配记录超过 " : "匹配记录共 ")
                + result.total() + " 条 · " + result.costMs() + " ms"));
        if (result.records().isEmpty()) return List.of(notice("没有找到记录。"));
        List<Component> lines = new ArrayList<>();
        lines.add(prefix().append(Component.text("查询结果 · a:" + result.action().id(), DARK_AQUA)));
        for (LookupRecord record : result.records()) {
            LookupAction type = record.recordAction(result.action());
            Component line = Component.text(relative(now - record.time()) + " - ", GRAY)
                    .append(literal(record.playerName(), DARK_AQUA));
            if (type.isItem()) {
                boolean plus = positive(type, record.action());
                Component material = literal(record.material(), DARK_AQUA);
                if (interactive) material = material.hoverEvent(itemHover.apply(record));
                line = line.append(Component.text(plus ? " + " : " - ", plus ? GREEN : RED))
                        .append(Component.text(actionText(type, record.action()) + " " + record.amount() + " × ", WHITE))
                        .append(material);
            } else if (type.family().equals("block")) {
                line = line.append(Component.text(" " + switch(record.action()) {
                    case 0 -> "破坏"; case 1 -> "放置"; case 2 -> "交互"; case 3 -> "击杀"; default -> "方块动作 #"+record.action();
                } + " ", WHITE)).append(literal(record.material(),DARK_AQUA));
            } else if (type.family().equals("session")) {
                line = line.append(Component.text(record.action()==1 ? " 登录服务器" : " 退出服务器",WHITE));
            } else if (type==LookupAction.USERNAME) {
                line = line.append(Component.text(" 使用此名字 · UUID ",WHITE)).append(literal(record.message(),GRAY));
            } else {
                line = line.append(Component.text(type == LookupAction.CHAT ? " 说：" : type == LookupAction.SIGN ? " 招牌文字：" : " 执行：", WHITE))
                        .append(literal(record.message(), WHITE));
            }
            if (record.rolledBack()!=0) line=line.append(Component.text(" [回滚标记 "+record.rolledBack()+"]",GRAY));
            if (result.verbose()) line=line.append(literal(" ["+record.source()+":"+record.rowId()+" · "+record.world()+" · "+coordinates(record)+"]",GRAY));
            Component hover = Component.text(DATE.format(Instant.ofEpochSecond(record.time())), GRAY)
                    .append(interactive ? Component.newline() : Component.text(" · ")).append(literal(record.world(), GRAY))
                    .append(Component.text(" · " + coordinates(record), GRAY));
            lines.add(interactive ? line.hoverEvent(hover) : line.append(Component.text(" [", GRAY)).append(hover)
                    .append(Component.text("]", GRAY)));
        }
        String count = result.truncated() ? "仅展示前 " + result.total() + " 条" : "共 " + result.total() + " 条";
        lines.add(Component.text(count + " · " + result.costMs() + " ms", GRAY));
        Component footer = prefix();
        if (result.page() > 1) footer = footer.append(pageButton("[上一页]", result.page() - 1));
        footer = footer.append(Component.text(" 第 " + result.page() + "/" + result.totalPages() + " 页 ", DARK_AQUA)
                .clickEvent(ClickEvent.suggestCommand("/coq l ")).hoverEvent(Component.text("输入页码，或 页码:每页条数")));
        if (result.page() < result.totalPages()) footer = footer.append(pageButton("[下一页]", result.page() + 1));
        if (interactive && result.action().isItem()) footer = footer.append(Component.text(" [箱子面板]",DARK_AQUA)
                .clickEvent(ClickEvent.runCommand("/coq items"))
                .hoverEvent(Component.text("打开当前查询页的临时物品面板（每页最多 45 条）")));
        if (!interactive) footer = footer.append(Component.text(" · /coq l <页码>[:每页条数]", GRAY));
        lines.add(footer);
        return lines;
    }
    private static HoverEvent<?> itemHover(LookupRecord record) {
        return ItemPreparationQueue.prepare(record).hover();
    }
    private static Component pageButton(String text, int page) {
        return Component.text(text, DARK_AQUA).clickEvent(ClickEvent.runCommand("/coq l " + page))
                .hoverEvent(Component.text("/coq l " + page, GRAY));
    }
    /** Database strings are text nodes, never MiniMessage, legacy formatting or click commands. */
    private static Component literal(String value, NamedTextColor color) {
        return Component.text(value == null ? "" : value.replace("§", "\\u00a7").replace('\n', ' ').replace('\r', ' '), color);
    }
    private static String coordinates(LookupRecord record) {
        return record.x() == null || record.y() == null || record.z() == null ? "位置未知" : record.x() + ", " + record.y() + ", " + record.z();
    }
    private static String relative(long seconds) {
        seconds = Math.max(0, seconds);
        if (seconds >= 86400) return seconds / 86400 + "天前";
        if (seconds >= 3600) return seconds / 3600 + "小时前";
        if (seconds >= 60) return seconds / 60 + "分钟前";
        return seconds + "秒前";
    }
    public static boolean positive(LookupAction type, int action) {
        if (type.family().equals("container")) return action == 1;
        return action == 1 || action == 3 || action == 4 || action == 10 || action == 12;
    }
    public static String actionText(LookupAction type, int action) {
        if (type.family().equals("container")) return action == 1 ? "放入容器" : "取出容器";
        return switch (action) {
            case 0 -> "移除物品";
            case 1 -> "添加物品";
            case 2 -> "掉落物品";
            case 3 -> "拾取物品";
            case 4 -> "末影箱取出";
            case 5 -> "末影箱放入";
            case 6 -> "投掷物品";
            case 7 -> "射出物品";
            case 8 -> "物品损坏";
            case 9 -> "物品销毁";
            case 10 -> "创建物品";
            case 11 -> "出售物品";
            case 12 -> "购买物品";
            default -> "物品动作 #" + action;
        };
    }
    private static Component command(String syntax, String description, String suggestion) {
        return Component.text(syntax, DARK_AQUA).clickEvent(ClickEvent.suggestCommand(suggestion))
                .hoverEvent(Component.text("点击填入命令", GRAY))
                .append(Component.text(" · " + description, WHITE));
    }
    private static Component helpLink(String text, int page) {
        return Component.text(text, DARK_AQUA).clickEvent(ClickEvent.runCommand("/coq help " + page))
                .hoverEvent(Component.text("查看帮助第 " + page + " 页", GRAY));
    }
    public static List<Component> home(boolean canReload) {
        return home(canReload,false);
    }
    public static List<Component> home(boolean canReload,boolean canDebug) {
        List<Component> lines = new ArrayList<>();
        lines.add(notice("CoreProtect 历史记录查询"));
        lines.add(command("/coq l a:<类型> t:<时间>", "开始查询", "/coq l a:item t:1h"));
        lines.add(command("/coq l <页码>[:每页条数]", "继续最近查询", "/coq l "));
        lines.add(command("/coq items", "打开最近物品查询的箱子面板", "/coq items"));
        if (canDebug) lines.add(command("/coq debug item", "分行查看并复制主手物品的全部组件", "/coq debug item"));
        if (canReload) lines.add(command("/coq reload", "重载配置与数据库连接", "/coq reload"));
        lines.add(helpLink("[查询入门] ",1).append(helpLink("[筛选与组件] ",2)).append(helpLink("[面板与管理]",3)));
        lines.add(Component.text("/coq help [1-3] 查看详细帮助；点击命令可填入聊天栏。",GRAY));
        return lines;
    }
    public static List<Component> help() { return help(1,false); }
    public static List<Component> help(int page, boolean canReload) {
        return help(page,canReload,false);
    }
    public static List<Component> help(int page, boolean canReload,boolean canDebug) {
        if (page < 1 || page > 3) throw new IllegalArgumentException("Invalid help page");
        List<Component> lines = new ArrayList<>();
        String title = switch (page) { case 1 -> "查询入门"; case 2 -> "筛选与组件"; default -> "面板与管理"; };
        lines.add(notice("帮助 " + page + "/3 · " + title));
        switch (page) {
            case 1 -> {
                lines.add(command("/coq l|lookup a:<类型> t:<时间>","创建查询", "/coq l a:item t:1h"));
                lines.add(Component.text("类型：block / click / kill / item / container / inventory",WHITE));
                lines.add(Component.text("文字与玩家：chat / command / sign / session / username；省略 a: 为混合查询。",GRAY));
                lines.add(Component.text("动作：+item / -item、+container / -container 筛选增加 / 减少记录",GRAY));
                lines.add(Component.text("时间：t:30m、t:1h、t:1d；t:10d-12d 表示 10 至 12 天前",GRAY));
                lines.add(command("/coq l a:item i:iron_ingot t:1d","查询铁锭记录", "/coq l a:item i:iron_ingot t:1d"));
                lines.add(command("/coq l a:chat u:Steve t:1d","查询玩家聊天", "/coq l a:chat u:Steve t:1d"));
                lines.add(command("/coq l 2:15","翻到第 2 页，每页 15 条；也可 /coq page 2", "/coq l 2:15"));
                lines.add(Component.text("悬停物品名查看历史物品；悬停记录查看时间与位置。",GRAY));
            }
            case 2 -> {
                lines.add(Component.text("u:/user: 玩家 · i:/include: 包含物品 · e:/exclude: 排除物品",WHITE));
                lines.add(Component.text("支持逗号列表，如 u:Steve,Alex；排除条件优先于包含条件。",GRAY));
                lines.add(Component.text("长别名：a: = action:、t: = time:；page:1:15 或 rows:15 设置分页。",GRAY));
                lines.add(Component.text("空间：r:5（XZ）· r:5x5（XYZ）· r:#we · world:世界 · coord:x,y,z",GRAY));
                lines.add(command("/coq near t:1h","附近 XYZ 各 5 格的混合记录", "/coq near t:1h"));
                lines.add(command("/coq l a:chat t:1d content:\"hello.*world\"","聊天 / 命令正则", "/coq l a:chat t:1d content:\"hello.*world\""));
                lines.add(command("/coq l a:item i:potion t:1h content:{\"minecraft:max_stack_size\":1}","物品组件条件", "/coq l a:item i:potion t:1h content:{\"minecraft:max_stack_size\":1}"));
                lines.add(Component.text("组件支持有效组件及默认值；多个组件写入同一 {}，必须全部匹配。",GRAY));
                lines.add(Component.text("content:\"minecraft:map_id\" 判断组件存在；content:\"minecraft:filled_map\" 匹配地图物品。",GRAY));
                lines.add(Component.text("Tab 补全组件名即可筛选拥有该组件的物品；加 =值 则精确匹配，如 content:map_id=101205。",GRAY));
                lines.add(Component.text("也可直接填白名单组件中的字符串值，如 content:\"smc:dou_dizhu_table\"，自动搜索嵌套字段和列表。",GRAY));
                lines.add(Component.text("在面板查看详情后，点击聊天中的组件行复制完整条件，粘贴到 content: 后。",GRAY));
            }
            default -> {
                if (canDebug) lines.add(command("/coq debug item [页码]","全部有效组件（含默认值），每行复制完整值或条件", "/coq debug item"));
                lines.add(command("/coq items [页码[:每页条数]]","打开最近 item / container 查询", "/coq items"));
                lines.add(Component.text("每页最多 45 条；可用 /coq items 1:45 调整。面板支持上一页 / 下一页。",GRAY));
                lines.add(Component.text("创造模式：左键领取显示数量的历史物品副本，右键查看详情。",WHITE));
                lines.add(Component.text("其他模式：左键 / 右键查看详情。Shift、数字键、双击与拖拽不取物。",GRAY));
                lines.add(Component.text("背包满时提示；无法还原的记录不能领取。关闭面板即释放本页数据。",GRAY));
                if (canReload) {
                    lines.add(command("/coq reload","重载配置、消息与数据库连接", "/coq reload"));
                    lines.add(Component.text("重载成功清理旧面板与查询会话；失败保留原配置和连接。",GRAY));
                }
                lines.add(Component.text("#count / #sum 只显示有界记录计数；#verbose 显示来源、ID 与坐标。",GRAY));
                lines.add(Component.text("inventory 须提供 u:；near 仍须按配置提供 t:。c: 始终是 content: 别名。",GRAY));
            }
        }
        Component footer = prefix();
        if (page > 1) footer = footer.append(helpLink("[上一页] ",page-1));
        footer = footer.append(Component.text("/coq help " + page + " ",GRAY));
        if (page < 3) footer = footer.append(helpLink("[下一页]",page+1));
        lines.add(footer);
        return lines;
    }
}
