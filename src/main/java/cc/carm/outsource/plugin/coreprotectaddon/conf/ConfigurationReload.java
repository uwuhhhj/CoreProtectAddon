package cc.carm.outsource.plugin.coreprotectaddon.conf;

import cc.carm.lib.configuration.source.ConfigurationHolder;
import cc.carm.lib.configuration.value.ConfigValue;
import cc.carm.lib.mineconfiguration.bukkit.source.BukkitSource;
import cc.carm.outsource.plugin.coreprotectaddon.service.QueryLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

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
                || PluginConfig.QUERY.COMPONENT_MAX_CANDIDATES.getNotNull() < 1
                || PluginConfig.QUERY.COMPONENT_MAX_CANDIDATES.getNotNull() > 100000)
            throw new IllegalArgumentException("查询会话和组件候选数量必须大于 0。");
    }

    public void commit() { committed = true; }
    @Override public void close() { if (!committed) restore.forEach(Runnable::run); }

    /** Parse an isolated YAML document on a worker. Never binds the global ConfigValue fields. */
    public static YamlConfiguration read(java.nio.file.Path file) throws Exception {
        if (java.nio.file.Files.size(file) > 1024 * 1024) throw new IllegalArgumentException("配置文件超过 1 MiB。");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.loadFromString(java.nio.file.Files.readString(file));
        return yaml;
    }

    /** Main-thread, memory-only publication. Parsing errors are raised before changing the live source. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void apply(ConfigurationHolder<BukkitSource> holder,YamlConfiguration yaml) throws Exception {
        Map<ConfigValue,Object> values = new java.util.LinkedHashMap<>();
        for (ConfigValue value : holder.registeredValues().values()) {
            Object raw = yaml.get(value.path());
            Object parsed;
            if (raw == null) parsed = value.defaults();
            else if (value instanceof cc.carm.lib.configuration.value.standard.ConfiguredValue single)
                parsed = single.parser().parse(holder,single.type(),raw);
            else if (value instanceof cc.carm.lib.configuration.value.standard.ConfiguredList list) {
                if (!(raw instanceof List<?> entries)) throw new IllegalArgumentException(value.path() + " 必须为列表。");
                List<Object> out = new ArrayList<>();
                for (Object entry : entries) out.add(list.parser().parse(holder,list.paramType(),entry));
                parsed = out;
            } else if (value instanceof cc.carm.lib.configuration.value.text.ConfiguredText)
                parsed = cc.carm.lib.configuration.value.text.data.TextContents.deserialize(plain(raw));
            else throw new IllegalArgumentException("不支持的配置项：" + value.path());
            values.put(value,parsed);
        }
        var original = holder.config().original();
        new ArrayList<>(original.getKeys(false)).forEach(key -> original.set(key,null));
        yaml.getValues(true).forEach((key,value) -> {
            if (!(value instanceof org.bukkit.configuration.ConfigurationSection)) original.set(key,value);
        });
        values.forEach(ConfigValue::set);
    }
    private static Object plain(Object value) {
        if (value instanceof org.bukkit.configuration.ConfigurationSection section) {
            Map<String,Object> out = new java.util.LinkedHashMap<>();
            section.getValues(false).forEach((key,item) -> out.put(key,plain(item)));
            return out;
        }
        return value;
    }
}
