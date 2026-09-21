package cc.carm.outsource.plugin.coreprotectaddon;

import cc.carm.lib.easyplugin.EasyPlugin;
import cc.carm.lib.mineconfiguration.bukkit.MineConfiguration;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.QueryRequest;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.QueryResult;
import cc.carm.outsource.plugin.coreprotectaddon.command.QueryCommands;
import cc.carm.outsource.plugin.coreprotectaddon.command.QueryTabCompleter;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginMessages;
import cc.carm.outsource.plugin.coreprotectaddon.conf.ConfigurationReload;
import cc.carm.outsource.plugin.coreprotectaddon.manager.DataManager;
import cc.carm.outsource.plugin.coreprotectaddon.service.CoreProtectQueryService;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

public class Main extends EasyPlugin implements Listener {

    private static Main instance;

    public Main() {
        Main.instance = this;
    }

    protected MineConfiguration configuration;
    protected volatile DataManager dataManager;
    protected volatile CoreProtectQueryService queryService;
    private QueryCommands queryCommands;
    private final cc.carm.outsource.plugin.coreprotectaddon.service.QueryTaskPool queryTasks = new cc.carm.outsource.plugin.coreprotectaddon.service.QueryTaskPool();
    private final java.util.concurrent.ExecutorService maintenance = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task,"COQ-maintenance"); thread.setDaemon(true); return thread;
    });
    private volatile boolean stopping;
    private boolean maintenanceBusy;
    public cc.carm.outsource.plugin.coreprotectaddon.service.QueryTaskPool queryTasks() { return queryTasks; }
    private void onMain(Runnable work,Runnable discarded) {
        if (stopping) { discarded.run(); return; }
        try { getServer().getScheduler().runTask(this,() -> {
            if (stopping) discarded.run(); else work.run();
        }); } catch (RuntimeException ex) { discarded.run(); }
    }
    private void retire(DataManager manager) {
        if (manager != null) {
            try { maintenance.execute(manager::shutdown); }
            catch (java.util.concurrent.RejectedExecutionException ex) {
                Thread cleanup = new Thread(manager::shutdown,"COQ-cleanup"); cleanup.setDaemon(true); cleanup.start();
            }
        }
    }


    @Override
    protected void load() {

        log("加载配置文件...");
        this.configuration = new MineConfiguration(this, PluginConfig.class, PluginMessages.class);

    }

    @Override
    protected boolean initialize() {
        var command = getCommand("coreprotectquery");
        if (command == null) return false;
        command.setExecutor((sender,cmd,label,args) -> { sender.sendMessage("COQ 数据库正在初始化，请稍候。"); return true; });
        try {
            var settings = DataManager.Settings.capture();
            maintenance.execute(() -> {
                try {
                    DataManager loaded = new DataManager(settings);
                    onMain(() -> {
                        dataManager = loaded; queryService = new CoreProtectQueryService(loaded);
                        queryCommands = new QueryCommands(this,queryService);
                        command.setExecutor(queryCommands); command.setTabCompleter(new QueryTabCompleter());
                        log("数据库初始化完成，COQ 已就绪。");
                    },() -> retire(loaded));
                } catch (Exception ex) {
                    onMain(() -> {
                        getLogger().log(java.util.logging.Level.SEVERE,"COQ 数据库初始化失败",ex);
                        getServer().getPluginManager().disablePlugin(this);
                    },() -> { });
                }
            });
            return true;
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE,"COQ 配置初始化失败",ex); return false;
        }
    }

    @Override protected void shutdown() {
        stopping = true;
        if (queryCommands != null) queryCommands.close();
        queryTasks.close();
        retire(dataManager);
        maintenance.shutdown(); // Never wait for a JDBC operation on the server thread.
    }

    public static void info(String... messages) {
        getInstance().log(messages);
    }

    public static void severe(String... messages) {
        getInstance().error(messages);
    }

    public static void debugging(String... messages) {
        getInstance().debug(messages);
    }

    @Override
    public boolean isDebugging() {
        return PluginConfig.DEBUG.resolve();
    }

    public static Main getInstance() {
        return instance;
    }

    public static DataManager getDataManager() {
        return getInstance().dataManager;
    }

    /** Serialize configuration writes and reloads; file I/O is performed only by maintenance. */
    public void updateComponentWhitelist(String component, boolean add,
            java.util.function.BiConsumer<Boolean,Throwable> done) {
        if (maintenanceBusy) throw new cc.carm.outsource.plugin.coreprotectaddon.service.QueryException("CONFIG_BUSY","配置操作正在进行，请稍后重试。");
        String key = cc.carm.outsource.plugin.coreprotectaddon.service.ItemContent.componentId(component);
        maintenanceBusy = true;
        String path = "item-panel.component-whitelist";
        var file = getDataFolder().toPath().resolve("config.yml");
        String fallback = configuration.getConfig().config().original().saveToString();
        var defaults = PluginConfig.ITEM_PANEL.COMPONENT_WHITELIST.copy();
        maintenance.execute(() -> {
            try {
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.options().parseComments(true);
        if (java.nio.file.Files.exists(file)) yaml.load(file.toFile());
        else yaml.loadFromString(fallback);
        if (yaml.contains(path) && (!yaml.isList(path) || yaml.getList(path).stream().anyMatch(value -> !(value instanceof String))))
            throw new IllegalArgumentException("component-whitelist 必须是组件名列表。");
        var whitelist = new java.util.LinkedHashSet<String>();
        for (String entry : yaml.contains(path) ? yaml.getStringList(path) : defaults)
            whitelist.add(cc.carm.outsource.plugin.coreprotectaddon.service.ItemContent.componentId(entry));
        boolean changed = add ? whitelist.add(key) : whitelist.remove(key);
        var updated = new java.util.ArrayList<>(whitelist);
        yaml.set(path,updated);
        java.nio.file.Files.createDirectories(file.getParent());
        var temporary = java.nio.file.Files.createTempFile(file.getParent(),"coq-whitelist-",".yml");
        try {
            java.nio.file.Files.writeString(temporary,yaml.saveToString(),java.nio.charset.StandardCharsets.UTF_8);
            try { java.nio.file.Files.move(temporary,file,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                java.nio.file.Files.move(temporary,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { java.nio.file.Files.deleteIfExists(temporary); }
                onMain(() -> {
                    configuration.getConfig().config().original().set(path,updated);
                    PluginConfig.ITEM_PANEL.COMPONENT_WHITELIST.set(updated);
                    maintenanceBusy = false; done.accept(changed,null);
                },() -> { });
            } catch (Exception ex) { onMain(() -> { maintenanceBusy = false; done.accept(false,ex); },() -> { }); }
        });
    }

    /** Read off-thread, validate a detached candidate, connect off-thread, then publish on the server thread. */
    public void reloadAddon(java.util.function.Consumer<Throwable> done) {
        if (maintenanceBusy) throw new cc.carm.outsource.plugin.coreprotectaddon.service.QueryException("CONFIG_BUSY","配置操作正在进行，请稍后重试。");
        maintenanceBusy = true;
        var folder = getDataFolder().toPath();
        maintenance.execute(() -> {
            try {
                var config = ConfigurationReload.read(folder.resolve("config.yml"));
                var messages = ConfigurationReload.read(folder.resolve("messages.yml"));
                onMain(() -> {
                    final DataManager.Settings settings;
                    try (var rollback = new ConfigurationReload(configuration.getConfig(),configuration.getMessage())) {
                        ConfigurationReload.apply(configuration.getConfig(),config);
                        ConfigurationReload.apply(configuration.getMessage(),messages);
                        ConfigurationReload.validate();
                        settings = DataManager.Settings.capture();
                    } catch (Exception ex) { finishReload(done,ex); return; }
                    maintenance.execute(() -> {
                        try {
                            DataManager replacement = new DataManager(settings);
                            onMain(() -> publishReload(config,messages,replacement,done),() -> retire(replacement));
                        } catch (Exception ex) { onMain(() -> finishReload(done,ex),() -> { }); }
                    });
                },() -> { });
            } catch (Exception ex) { onMain(() -> finishReload(done,ex),() -> { }); }
        });
    }
    private void publishReload(org.bukkit.configuration.file.YamlConfiguration config,
            org.bukkit.configuration.file.YamlConfiguration messages,DataManager replacement,java.util.function.Consumer<Throwable> done) {
        DataManager previousData = dataManager;
        QueryCommands previousCommands = queryCommands;
        QueryCommands replacementCommands = null;
        try (var rollback = new ConfigurationReload(configuration.getConfig(),configuration.getMessage())) {
            ConfigurationReload.apply(configuration.getConfig(),config);
            ConfigurationReload.apply(configuration.getMessage(),messages);
            ConfigurationReload.validate();
            CoreProtectQueryService replacementService = new CoreProtectQueryService(replacement);
            replacementCommands = new QueryCommands(this,replacementService);
            java.util.Objects.requireNonNull(getCommand("coreprotectquery")).setExecutor(replacementCommands);
            dataManager = replacement; queryService = replacementService; queryCommands = replacementCommands;
            rollback.commit();
        } catch (Exception ex) {
            if (replacementCommands != null) replacementCommands.close();
            retire(replacement); finishReload(done,ex); return;
        }
        try { previousCommands.close(); }
        catch (RuntimeException ex) { getLogger().log(java.util.logging.Level.WARNING,"Unable to retire old queries",ex); }
        finally { retire(previousData); finishReload(done,null); }
    }
    private void finishReload(java.util.function.Consumer<Throwable> done,Throwable failure) {
        maintenanceBusy = false;
        if (failure != null) getLogger().log(java.util.logging.Level.WARNING,"COQ reload failed",failure);
        done.accept(failure);
    }

    public @NotNull QueryResult query(@NotNull QueryRequest request) {
        var service = this.queryService;
        if (service == null) return QueryResult.failure(request.queryType(),"NOT_READY","COQ 数据库尚未就绪。",1,15,0);
        return service.query(request);
    }

    public static CoreProtectQueryService getQueryService() {
        return getInstance().queryService;
    }

}
