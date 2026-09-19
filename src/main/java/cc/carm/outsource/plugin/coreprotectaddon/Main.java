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
import cc.carm.outsource.plugin.coreprotectaddon.manager.CoreProtectConnectionDiagnostics;
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

    @Override
    protected void load() {

        log("加载配置文件...");
        this.configuration = new MineConfiguration(this, PluginConfig.class, PluginMessages.class);

    }

    @Override
    protected boolean initialize() {

        // onLoad runs before dependencies are enabled. Connect here so CoreProtect can initialize its views first.
        log("加载数据库...");
        try {
            this.dataManager = new DataManager();
            this.queryService = new CoreProtectQueryService(this.dataManager);
        } catch (Exception e) {
            e.printStackTrace();
            log("§c数据库加载失败，已取消插件启用：" + e.getMessage());
            if ("clickhouse".equalsIgnoreCase(PluginConfig.DATABASE_TYPE.getNotNull().trim()))
                CoreProtectConnectionDiagnostics.collect().forEach(message -> log(message));
            if (this.dataManager != null) this.dataManager.shutdown();
            this.dataManager = null;
            this.queryService = null;
            // EasyPlugin.onEnable disables the plugin when initialize returns false.
            return false;
        }

        log("注册命令...");
        if (getCommand("coreprotectquery") != null) {
            this.queryCommands = new QueryCommands(this);
            getCommand("coreprotectquery").setExecutor(queryCommands);
            getCommand("coreprotectquery").setTabCompleter(new QueryTabCompleter());
        }

        return true;
    }

    @Override
    protected void shutdown() {

        log("正在关闭数据库连接...");
        if (this.queryCommands != null) this.queryCommands.close();
        if (this.dataManager != null) this.dataManager.shutdown();

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

    /** Server-thread edit of just the whitelist, preserving other administrator edits on disk. */
    public boolean updateComponentWhitelist(String component, boolean add) throws Exception {
        String key = cc.carm.outsource.plugin.coreprotectaddon.service.ItemContent.componentId(component);
        String path = "item-panel.component-whitelist";
        var file = getDataFolder().toPath().resolve("config.yml");
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.options().parseComments(true);
        if (java.nio.file.Files.exists(file)) yaml.load(file.toFile());
        else yaml.loadFromString(configuration.getConfig().config().original().saveToString());
        if (yaml.contains(path) && (!yaml.isList(path) || yaml.getList(path).stream().anyMatch(value -> !(value instanceof String))))
            throw new IllegalArgumentException("component-whitelist 必须是组件名列表。");
        var whitelist = new java.util.LinkedHashSet<String>();
        for (String entry : yaml.contains(path) ? yaml.getStringList(path) : PluginConfig.ITEM_PANEL.COMPONENT_WHITELIST.copy())
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
        configuration.getConfig().config().original().set(path,updated);
        PluginConfig.ITEM_PANEL.COMPONENT_WHITELIST.set(updated);
        return changed;
    }

    /** Called on the server thread. Publish replacements only after configuration and connection validation. */
    public void reloadAddon() throws Exception {
        var command = java.util.Objects.requireNonNull(getCommand("coreprotectquery"),"Missing COQ command");
        DataManager replacement = null;
        QueryCommands replacementCommands = null;
        DataManager previousData = this.dataManager;
        QueryCommands previousCommands = this.queryCommands;
        try (var reload = new ConfigurationReload(configuration.getConfig(),configuration.getMessage())) {
            configuration.reload();
            ConfigurationReload.validate();
            replacement = new DataManager();
            // MySQL pools may connect lazily; reject a bad target before retiring the working pool.
            try (var connection = replacement.dataSource().getConnection(); var statement = connection.createStatement()) {
                statement.setQueryTimeout(cc.carm.outsource.plugin.coreprotectaddon.service.QueryLimits.configured().timeoutSeconds());
                try (var result = statement.executeQuery("SELECT 1")) {
                    if (!result.next()) throw new java.sql.SQLException("数据库连接测试未返回结果。");
                }
            }
            CoreProtectQueryService replacementService = new CoreProtectQueryService(replacement);
            replacementCommands = new QueryCommands(this,replacementService);
            command.setExecutor(replacementCommands);
            this.dataManager = replacement;
            this.queryService = replacementService;
            this.queryCommands = replacementCommands;
            reload.commit();
        } catch (Exception ex) {
            if (replacementCommands != null) replacementCommands.close();
            if (replacement != null) replacement.shutdown();
            throw ex;
        }
        try { previousCommands.close(); }
        catch (RuntimeException ex) { getLogger().log(java.util.logging.Level.WARNING,"Unable to retire old queries",ex); }
        try { previousData.shutdown(); }
        catch (RuntimeException ex) { getLogger().log(java.util.logging.Level.WARNING,"Unable to retire old connection",ex); }
    }

    public @NotNull QueryResult query(@NotNull QueryRequest request) {
        return this.queryService.query(request);
    }

    public static CoreProtectQueryService getQueryService() {
        return getInstance().queryService;
    }

}
