package cc.carm.outsource.plugin.coreprotectaddon.command.query;

import cc.carm.lib.easyplugin.command.SubCommand;
import cc.carm.outsource.plugin.coreprotectaddon.Main;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.QueryRecord;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.QueryRequest;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.QueryResult;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.QueryType;
import cc.carm.outsource.plugin.coreprotectaddon.command.CommandParameter;
import cc.carm.outsource.plugin.coreprotectaddon.command.QueryCommands;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginMessages;
import cc.carm.outsource.plugin.coreprotectaddon.service.CoreProtectQueryService;
import cc.carm.outsource.plugin.coreprotectaddon.utils.TimeFormatUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class CommandQueryCommand extends SubCommand<QueryCommands> {

    public CommandQueryCommand(@NotNull QueryCommands parent, String identifier, String... aliases) {
        super(parent, identifier, aliases);
    }

    @Override
    public Void execute(JavaPlugin plugin, CommandSender sender, String[] args) throws Exception {
        CommandParameter parameter = CommandParameter.parse(args);
        Integer page = parsePage(parameter.get("page", "p"));
        if (page == null) {
            PluginMessages.WRONG_PAGE.sendTo(sender);
            return null;
        }

        Main.getInstance().getScheduler().runAsync(() -> {
            QueryResult result = Main.getInstance().query(new QueryRequest(
                    QueryType.COMMAND,
                    parameter.get("user", "u"),
                    parameter.get("time", "t"),
                    page,
                    null,
                    parameter.get("content", "c", "command")
            ));

            if (!result.success()) {
                switch (result.errorCode()) {
                    case CoreProtectQueryService.ERROR_INVALID_TIME -> PluginMessages.WRONG_TIME.sendTo(sender);
                    case CoreProtectQueryService.ERROR_INVALID_PAGE -> PluginMessages.WRONG_PAGE.sendTo(sender);
                    case CoreProtectQueryService.ERROR_UNKNOWN_USER -> PluginMessages.UNKNOWN_USER.sendTo(sender, result.message());
                    default -> {
                        sender.sendMessage("§c指令查询失败: " + result.message());
                        Main.severe("指令查询失败: " + result.message());
                    }
                }
                return;
            }

            if (result.records().isEmpty()) {
                if (result.page() == 1) {
                    PluginMessages.EMPTY.sendTo(sender);
                } else {
                    PluginMessages.EMPTY_PAGE.sendTo(sender, result.page());
                }
            } else {
                PluginMessages.COST.prepare(result.costMs(), result.count()).to(sender);
                List<String> contents = new ArrayList<>();
                for (QueryRecord record : result.records()) {
                    contents.addAll(PluginMessages.CONTENT.prepare(
                            TimeFormatUtils.datetime(record.time()),
                            ("§b" + record.playerName()).replace("$", "\\$"),
                            record.message().replace("$", "\\$")
                    ).parse(sender));
                }
                PluginMessages.PAGE.prepare(result.page()).insert("content", contents).to(sender);
            }
        });
        return null;
    }

    private Integer parsePage(String pageString) {
        if (pageString == null) {
            return 1;
        }
        try {
            return Integer.parseInt(pageString);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

}
