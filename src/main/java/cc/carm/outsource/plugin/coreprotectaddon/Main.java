package cc.carm.outsource.plugin.coreprotectaddon;

import cc.carm.lib.easyplugin.EasyPlugin;
import cc.carm.lib.mineconfiguration.bukkit.MineConfiguration;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.QueryRequest;
import cc.carm.outsource.plugin.coreprotectaddon.api.query.QueryResult;
import cc.carm.outsource.plugin.coreprotectaddon.command.QueryCommands;
import cc.carm.outsource.plugin.coreprotectaddon.command.QueryTabCompleter;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginMessages;
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
    protected DataManager dataManager;
    protected CoreProtectQueryService queryService;

    @Override
    protected void load() {

        log("加载配置文件...");
        this.configuration = new MineConfiguration(this, PluginConfig.class, PluginMessages.class);

        log("加载数据库...");
        try {
            this.dataManager = new DataManager();
            this.queryService = new CoreProtectQueryService(this.dataManager);
        } catch (Exception e) {
            e.printStackTrace();
            log("§c数据库加载失败，插件无法正常运行，请确保数据库配置正确！");
            this.setEnabled(false);
        }

    }

    @Override
    protected boolean initialize() {

        log("注册命令...");
        if (getCommand("coreprotectquery") != null) {
            getCommand("coreprotectquery").setExecutor(new QueryCommands(this));
            getCommand("coreprotectquery").setTabCompleter(new QueryTabCompleter());
        }

        return true;
    }

    @Override
    protected void shutdown() {

        log("正在关闭数据库连接...");
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

    public @NotNull QueryResult query(@NotNull QueryRequest request) {
        return this.queryService.query(request);
    }

    public static CoreProtectQueryService getQueryService() {
        return getInstance().queryService;
    }

}
