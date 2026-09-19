package cc.carm.outsource.plugin.coreprotectaddon.conf;

import cc.carm.lib.configuration.Configuration;
import cc.carm.lib.configuration.annotation.ConfigPath;
import cc.carm.lib.mineconfiguration.bukkit.value.ConfiguredMessage;


@ConfigPath(root = true)
public interface PluginMessages extends Configuration {

    ConfiguredMessage<String> COMMAND_USAGE = ConfiguredMessage.asString().defaults(
            "&3COQ &f查询命令帮助 &8(/coq help)",
            "&f/coq l a:chat|command|item|container t:1d [u:玩家]",
            "&f/coq l a:item i:iron_ingot e:diamond t:1d",
            "&f/coq l <页码>[:每页条数]",
            "&7聊天和命令支持 content: SQL 正则条件。完整帮助使用 /coq help。"
    ).build();

    ConfiguredMessage<String> NO_PERMISSION = ConfiguredMessage.asString().defaults(
            "&c&l抱歉！&f但您没有权限执行该命令。"
    ).build();

    ConfiguredMessage<String> UNKNOWN_USER = ConfiguredMessage.asString().defaults(
            "&e&l未知的用户。&f不存在任何与 &e%(player) &f关联的记录。"
    ).params("player").build();


    ConfiguredMessage<String> WRONG_TIME = ConfiguredMessage.asString().defaults(
            "&c&l时间格式错误。&f请输入正确的时间格式，形如 &e5mo4d3h2m1s &f表示从今往前的一段时间，或例如 &e10d-30d &f表示从10天前到30天前的时间段。"
    ).build();

    ConfiguredMessage<String> WRONG_PAGE = ConfiguredMessage.asString().defaults(
            "&c&l无效的页码。&f请输入正确的页码，应当为整数数字。"
    ).build();

    ConfiguredMessage<String> EMPTY = ConfiguredMessage.asString().defaults(
            "&f在指定的范围内没有找到任何相关记录。"
    ).build();

    ConfiguredMessage<String> EMPTY_PAGE = ConfiguredMessage.asString().defaults(
            "&f在指定的范围内，第 %(page) 页无相关记录。"
    ).params("page").build();

    ConfiguredMessage<String> COST = ConfiguredMessage.asString().defaults(
            "&e&l查询完成！&f本次查询共耗时 &e%(time)ms&f，获取到 &e%(count) &f条记录。"
    ).params("time", "count").build();

    ConfiguredMessage<String> PAGE = ConfiguredMessage.asString().defaults(
            "&e-------[ &6&l记录查询 &8(第&7%(page)&8页) &e]-------",
            "{&7- &r}#content#{0,0}",
            "&7&o可通过 page 参数继续查看下一页内容。"
    ).params("page").build();

    ConfiguredMessage<String> CONTENT = ConfiguredMessage.asString().defaults(
            "&7[&6%(time)&7] &b%(player) &f: %(message)"
    ).params("time", "player", "message").build();


}

