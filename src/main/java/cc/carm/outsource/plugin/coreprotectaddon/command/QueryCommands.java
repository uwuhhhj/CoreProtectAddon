package cc.carm.outsource.plugin.coreprotectaddon.command;

import cc.carm.outsource.plugin.coreprotectaddon.Main;
import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.LookupRequest;
import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.LookupResult;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryException;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryLimits;
import cc.carm.outsource.plugin.coreprotectaddon.service.CoreProtectQueryService;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

public final class QueryCommands implements CommandExecutor, AutoCloseable {
    private final Main plugin;
    private final LookupSessions sessions;
    private final Set<String> pending = new HashSet<>();
    private final ItemPreparationQueue preparation;
    private final ItemLookupPanel panels;
    private final CoreProtectQueryService queryService;
    private volatile boolean closed;
    public QueryCommands(Main plugin) {
        this(plugin,Main.getQueryService());
    }
    public QueryCommands(Main plugin, CoreProtectQueryService queryService) {
        this.plugin = plugin;
        this.queryService = queryService;
        this.sessions = new LookupSessions(Clock.systemUTC(), PluginConfig.QUERY.SESSION_TTL_SECONDS.resolve(),
                PluginConfig.QUERY.MAX_SESSIONS.resolve());
        this.preparation = new ItemPreparationQueue(plugin);
        queryService.setComponentMatcher(preparation::match);
        this.panels = new ItemLookupPanel(plugin,sessions,preparation,queryService::lookup);
    }
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (closed) return true;
        if (args.length > 0 && args[0].equalsIgnoreCase("debug")) {
            ItemDebug.execute(plugin,sender,args);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("coreprotectaddon.command.reload")) {
                LookupRenderer.send(sender,LookupRenderer.notice("你没有重载配置的权限。"));
            } else if (args.length != 1) {
                LookupRenderer.send(sender,LookupRenderer.notice("用法：/coq reload"));
            } else {
                LookupRenderer.send(sender,LookupRenderer.notice("正在重载配置与数据库连接…"));
                try {
                    plugin.reloadAddon();
                    LookupRenderer.send(sender,LookupRenderer.notice("重载完成：配置与数据库连接已更新，旧面板及查询会话已清理。"));
                } catch (Exception ex) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,"COQ reload failed",ex);
                    LookupRenderer.send(sender,LookupRenderer.notice("重载失败，已保留原配置与连接。请检查配置和服务器日志。"));
                }
            }
            return true;
        }
        if (!sender.hasPermission("coreprotectaddon.command.query")) {
            LookupRenderer.send(sender, LookupRenderer.notice("你没有使用此命令的权限。"));
            return true;
        }
        if (args.length == 0) {
            LookupRenderer.home(sender.hasPermission("coreprotectaddon.command.reload"),sender.hasPermission("coreprotectaddon.command.debug")).forEach(line -> LookupRenderer.send(sender,line));
            return true;
        }
        if (args[0].equalsIgnoreCase("help")) {
            try {
                if (args.length > 2) throw new IllegalArgumentException();
                int page = args.length == 1 ? 1 : Integer.parseInt(args[1]);
                LookupRenderer.help(page,sender.hasPermission("coreprotectaddon.command.reload"),sender.hasPermission("coreprotectaddon.command.debug")).forEach(line -> LookupRenderer.send(sender,line));
            } catch (IllegalArgumentException ex) {
                LookupRenderer.send(sender,LookupRenderer.notice("用法：/coq help [1-3]"));
            }
            return true;
        }
        String key = senderKey(sender);
        if (pending.contains(key) || (sender instanceof Player player && panels.busy(player))) {
            LookupRenderer.send(sender, LookupRenderer.notice("上一条查询仍在执行，请稍候。"));
            return true;
        }
        try {
            if (args[0].equalsIgnoreCase("items")) {
                if (!(sender instanceof Player player)) throw new QueryException("PLAYER_ONLY","箱子面板只能由游戏内玩家打开。");
                if (args.length > 2) throw new QueryException("INVALID_PARAMETER","用法：/coq items [页码[:每页条数]]");
                LookupSessions.Session session = sessions.require(key);
                if (args.length == 2) {
                    int[] page = LookupParameters.page(args[1]);
                    int size = QueryLimits.configured().pageSize(page[1] == 0 ? session.request().pageSize() : page[1]);
                    QueryLimits.configured().offset(page[0],size);
                    LookupRequest request = session.request().withPage(page[0],size);
                    ItemLookupPanel.validate(request);
                    session = new LookupSessions.Session(request,session.anchor(),session.expiresAt());
                }
                panels.open(player,session);
                return true;
            }
            LookupParameters.Parsed parsed = LookupParameters.parse(args);
            QueryLimits limits = QueryLimits.configured();
            LookupSessions.Session session;
            if (parsed.continuation()) {
                LookupSessions.Session previous = sessions.require(key);
                int rows = limits.pageSize(parsed.rows() == null ? previous.request().pageSize() : parsed.rows());
                limits.offset(parsed.page(), rows);
                session = sessions.page(key, parsed.page(), rows);
            } else {
                LookupRequest request = SpatialResolver.resolve(sender,LookupParameters.resolveTags(parsed.request()));
                int rows = limits.pageSize(request.pageSize());
                limits.offset(request.page(), rows);
                limits.window(request.time(), Instant.now().getEpochSecond());
                session = sessions.start(key, request.withPage(request.page(), rows));
            }
            if (sender instanceof Player player) panels.close(player);
            pending.add(key);
            LookupRenderer.send(sender, LookupRenderer.notice("正在查询…"));
            try {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    if (closed) return;
                    LookupResult result = queryService.lookup(session.request(), session.anchor());
                    if (closed || !plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (closed) return;
                        if (sender instanceof Player player && !player.isOnline()) { pending.remove(key); return; }
                        if (sender instanceof Player player && result.success() && result.records().stream().anyMatch(r -> r.recordAction(result.action()).isItem())) {
                            var hovers = new java.util.IdentityHashMap<cc.carm.outsource.plugin.coreprotectaddon.api.lookup.LookupRecord,
                                    net.kyori.adventure.text.event.HoverEvent<?>>();
                            preparation.submit(result.records().stream().filter(r -> r.recordAction(result.action()).isItem()).toList(), () -> {
                                if (closed || !player.isOnline()) { pending.remove(key); return false; }
                                return true;
                            }, (record, item) -> hovers.put(record,item.hover()), () -> {
                                pending.remove(key);
                                LookupRenderer.render(result,Instant.now().getEpochSecond(),true,hovers::get)
                                        .forEach(line -> LookupRenderer.send(sender,line));
                            });
                        } else {
                            pending.remove(key);
                            LookupRenderer.render(result, Instant.now().getEpochSecond(), sender instanceof Player)
                                    .forEach(line -> LookupRenderer.send(sender, line));
                        }
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
    @Override public void close() { closed = true; panels.close(); preparation.close(); pending.clear(); sessions.clear(); }

    static String senderKey(CommandSender sender) {
        if (sender instanceof Player player) return "player:" + player.getUniqueId();
        if (sender instanceof BlockCommandSender block) {
            org.bukkit.block.Block b = block.getBlock();
            return "block:" + b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
        }
        // Console and remote console remain separate; the key is never an unqualified player name.
        return sender.getClass().getName() + ":" + sender.getName();
    }
}
