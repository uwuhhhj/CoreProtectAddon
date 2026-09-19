package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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
        if (!result.success()) return List.of(prefix().append(Component.text(result.message(), RED)));
        if (result.records().isEmpty()) return List.of(notice("没有找到记录。"));
        List<Component> lines = new ArrayList<>();
        lines.add(prefix().append(Component.text("查询结果 · a:" + result.action().id(), DARK_AQUA)));
        for (LookupRecord record : result.records()) {
            Component line = Component.text(relative(now - record.time()) + " - ", GRAY)
                    .append(literal(record.playerName(), DARK_AQUA));
            if (result.action().isItem()) {
                boolean plus = positive(result.action(), record.action());
                line = line.append(Component.text(plus ? " + " : " - ", plus ? GREEN : RED))
                        .append(Component.text(actionText(result.action(), record.action()) + " " + record.amount() + " × ", WHITE))
                        .append(literal(record.material(), DARK_AQUA));
            } else {
                line = line.append(Component.text(result.action() == LookupAction.CHAT ? " 说：" : " 执行：", WHITE))
                        .append(literal(record.message(), WHITE));
            }
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
        if (!interactive) footer = footer.append(Component.text(" · /coq l <页码>[:每页条数]", GRAY));
        lines.add(footer);
        return lines;
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
            default -> "物品动作 #" + action;
        };
    }
    public static List<Component> help() {
        return List.of(notice("查询帮助"),
                Component.text("/coq l|lookup a:<类型> t:<时间> [u:玩家] [i:物品] [e:排除物品]", WHITE),
                Component.text("已接通：chat、command、item、+item、-item、container、+container、-container", WHITE),
                Component.text("u:/i:/e: 支持逗号列表；i: 包含，e: 排除优先。长别名：user: time: action: include: exclude:", GRAY),
                Component.text("时间：t:1h、t:1d、t:10d-12d；聊天/命令正则：content:\"hello.*world\"", GRAY),
                Component.text("/coq l <页码>[:每页条数] · /coq page <页码> · page:1:15", WHITE),
                Component.text("示例：/coq l a:item i:iron_ingot t:1d", DARK_AQUA),
                Component.text("示例：/coq l a:chat u:Steve t:1d content:\"hello\"", DARK_AQUA),
                Component.text("未接通：默认 block、±block、click、kill、±inventory、sign、±session、username", GRAY),
                Component.text("未接通：near、r:/radius:、world:、坐标、#count/#sum 等统计与标记。执行会明确报错。", GRAY),
                Component.text("本版本尚不支持 name、Lore、自定义 ID、NBT 或物品组件条件。", GRAY));
    }
}
