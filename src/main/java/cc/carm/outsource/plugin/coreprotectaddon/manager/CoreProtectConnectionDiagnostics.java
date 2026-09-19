package cc.carm.outsource.plugin.coreprotectaddon.manager;

import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Optional, read-only comparison against the running CoreProtect build. Never prints credential values. */
public final class CoreProtectConnectionDiagnostics {
    private CoreProtectConnectionDiagnostics() { }

    public static List<String> collect() {
        try {
            Plugin core = Bukkit.getPluginManager().getPlugin("CoreProtect");
            if (core == null || !core.isEnabled()) return List.of("CoreProtect 尚未启用，无法对比运行时连接配置。");
            ClassLoader loader = core.getClass().getClassLoader();
            Object config = Class.forName("net.coreprotect.config.Config", false, loader).getMethod("getGlobal").invoke(null);
            Object type = Class.forName("net.coreprotect.config.ConfigHandler", false, loader).getField("databaseType").get(null);
            Map<String, Object> addon = new LinkedHashMap<>();
            addon.put("CLICKHOUSE_HOST", PluginConfig.CLICKHOUSE_HOST.getNotNull());
            addon.put("CLICKHOUSE_PORT", PluginConfig.CLICKHOUSE_PORT.getNotNull());
            addon.put("CLICKHOUSE_DATABASE", PluginConfig.CLICKHOUSE_DATABASE.getNotNull());
            addon.put("CLICKHOUSE_USERNAME", PluginConfig.CLICKHOUSE_USERNAME.getNotNull());
            addon.put("CLICKHOUSE_PASSWORD", PluginConfig.CLICKHOUSE_PASSWORD.getNotNull());
            addon.put("CLICKHOUSE_TLS", PluginConfig.CLICKHOUSE_TLS.getNotNull());
            return compare(config, String.valueOf(type), addon);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            // This is supplementary diagnostics only; an unsupported CoreProtect build must not hide the original error.
            return List.of("无法读取此 CoreProtect 构建的运行时配置进行对照：" + ex.getClass().getSimpleName());
        }
    }

    static List<String> compare(Object coreConfig, String databaseType, Map<String, ?> addon) throws ReflectiveOperationException {
        List<String> result = new ArrayList<>();
        result.add("CoreProtect 当前运行数据库类型：" + databaseType);
        for (Map.Entry<String, ?> entry : addon.entrySet()) {
            Object actual = coreConfig.getClass().getField(entry.getKey()).get(coreConfig);
            String key = entry.getKey().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            result.add("运行时配置对照 " + key + "：" + (Objects.equals(actual, entry.getValue()) ? "相同" : "不同")
                    + (entry.getKey().equals("CLICKHOUSE_PASSWORD") ? "（不输出密码）" : ""));
        }
        return result;
    }
}
