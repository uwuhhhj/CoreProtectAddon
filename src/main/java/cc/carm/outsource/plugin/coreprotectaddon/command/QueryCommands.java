package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.Main;
import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.LookupRequest;
import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.LookupResult;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryException;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryLimits;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

public final class QueryCommands implements CommandExecutor {
    private final Main plugin;
    private final LookupSessions sessions;
    private final Set<String> pending = new HashSet<>();
    public QueryCommands(Main plugin) {
        this.plugin = plugin;
        this.sessions = new LookupSessions(Clock.systemUTC(), PluginConfig.QUERY.SESSION_TTL_SECONDS.resolve(),
                PluginConfig.QUERY.MAX_SESSIONS.resolve());
    }
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("coreprotectaddon.command.query")) {
            LookupRenderer.send(sender, LookupRenderer.notice("你没有使用此命令的权限。"));
            return true;
        }
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("help"))) {
            LookupRenderer.help().forEach(line -> LookupRenderer.send(sender, line));
            return true;
        }
        String key = senderKey(sender);
        if (pending.contains(key)) {
            LookupRenderer.send(sender, LookupRenderer.notice("上一条查询仍在执行，请稍候。"));
            return true;
        }
        try {
            LookupParameters.Parsed parsed = LookupParameters.parse(args);
            QueryLimits limits = QueryLimits.configured();
            LookupSessions.Session session;
            if (parsed.continuation()) {
                LookupSessions.Session previous = sessions.require(key);
                int rows = limits.pageSize(parsed.rows() == null ? previous.request().pageSize() : parsed.rows());
                limits.offset(parsed.page(), rows);
                session = sessions.page(key, parsed.page(), rows);
            } else {
                LookupRequest request = parsed.request();
                int rows = limits.pageSize(request.pageSize());
                limits.offset(request.page(), rows);
                limits.window(request.time(), Instant.now().getEpochSecond());
                session = sessions.start(key, request.withPage(request.page(), rows));
            }
            pending.add(key);
            LookupRenderer.send(sender, LookupRenderer.notice("正在查询…"));
            try {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    LookupResult result = Main.getQueryService().lookup(session.request(), session.anchor());
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        pending.remove(key);
                        if (sender instanceof Player player && !player.isOnline()) return;
                        LookupRenderer.render(result, Instant.now().getEpochSecond(), sender instanceof Player)
                                .forEach(line -> LookupRenderer.send(sender, line));
                    });
                });
            } catch (RuntimeException ex) {
                pending.remove(key);
                throw ex;
            }
        } catch (QueryException ex) {
            LookupRenderer.send(sender, LookupRenderer.notice(ex.getMessage()));
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Unable to dispatch lookup: " + ex.getClass().getSimpleName());
            LookupRenderer.send(sender, LookupRenderer.notice("无法开始查询，请检查配置与日志。"));
        }
        return true;
    }
    private static String senderKey(CommandSender sender) {
        if (sender instanceof Player player) return "player:" + player.getUniqueId();
        if (sender instanceof BlockCommandSender block) {
            org.bukkit.block.Block b = block.getBlock();
            return "block:" + b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
        }
        // Console and remote console remain separate; the key is never an unqualified player name.
        return sender.getClass().getName() + ":" + sender.getName();
    }
}
