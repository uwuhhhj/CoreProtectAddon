package cc.carm.outsource.plugin.coreprotectaddon.conf;

import cc.carm.lib.configuration.source.ConfigurationHolder;
import cc.carm.lib.configuration.value.ConfigValue;
import cc.carm.lib.mineconfiguration.bukkit.source.BukkitSource;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryLimits;
import java.util.ArrayList;
import java.util.List;

/** Restores in-memory configuration on failure; never overwrites the administrator's files. */
public final class ConfigurationReload implements AutoCloseable {
    private final List<Runnable> restore = new ArrayList<>();
    private boolean committed;

    @SafeVarargs
    public ConfigurationReload(ConfigurationHolder<BukkitSource>... holders) {
        for (var holder : holders) {
            String yaml = holder.config().original().saveToString();
            restore.add(() -> {
                try { holder.config().original().loadFromString(yaml); }
                catch (Exception ex) { throw new IllegalStateException("Cannot restore configuration",ex); }
            });
            holder.registeredValues().values().forEach(value -> restore.add(snapshot(value)));
        }
    }

    private static <T> Runnable snapshot(ConfigValue<T, ?> value) {
        T previous = value.resolve();
        return () -> value.set(previous);
    }

    public static void validate() {
        QueryLimits.configured();
        if (PluginConfig.QUERY.SESSION_TTL_SECONDS.getNotNull() < 1
                || PluginConfig.QUERY.MAX_SESSIONS.getNotNull() < 1
                || PluginConfig.QUERY.COMPONENT_MAX_CANDIDATES.getNotNull() < 1)
            throw new IllegalArgumentException("查询会话和组件候选数量必须大于 0。");
    }

    public void commit() { committed = true; }
    @Override public void close() { if (!committed) restore.forEach(Runnable::run); }
}
