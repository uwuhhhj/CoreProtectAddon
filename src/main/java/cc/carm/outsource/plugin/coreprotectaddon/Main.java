package cc.carm.outsource.plugin.coreprotectaddon;

import cc.carm.lib.easyplugin.EasyPlugin;
import cc.carm.lib.mineconfiguration.bukkit.MineConfiguration;
import cc.carm.outsource.plugin.coreprotectaddon.command.QueryCommands;
import cc.carm.outsource.plugin.coreprotectaddon.command.QueryTabCompleter;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginMessages;
import cc.carm.outsource.plugin.coreprotectaddon.manager.DataManager;
import org.bukkit.event.Listener;

public class Main extends EasyPlugin implements Listener {

    private static Main instance;

    public Main() {
        Main.instance = this;
    }

    protected MineConfiguration configuration;
    protected DataManager dataManager;

    @Override
    protected void load() {

        log("加载配置文件...");
        this.configuration = new MineConfiguration(this, PluginConfig.class, PluginMessages.class);

        log("加载数据库...");
        try {
            this.dataManager = new DataManager();
        } catch (Exception e) {
            e.printStackTrace();
            log("§c数据库加载失败，插件无法正常运行，请确保数据库配置正确！");
            this.setEnabled(false);
        }

    }

    @Override
    protected boolean initialize() {

        log("注册命令...");
        registerCommand("coreprotectquery", new QueryCommands(this));
        if (getCommand("coreprotectquery") != null) {
            getCommand("coreprotectquery").setTabCompleter(new QueryTabCompleter());
        }

        return true;
    }

    @Override
    protected void shutdown() {

        log("正在关闭数据库连接...");
        this.dataManager.shutdown();

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

}
