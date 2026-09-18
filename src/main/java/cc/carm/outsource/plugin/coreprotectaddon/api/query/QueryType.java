package cc.carm.outsource.plugin.coreprotectaddon.api.query;

import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public enum QueryType {

    CHAT("chat") {
        @Override
        public @NotNull String tableName() {
            return PluginConfig.DATABASE.TABLES.CHAT.resolve();
        }
    },
    COMMAND("command") {
        @Override
        public @NotNull String tableName() {
            return PluginConfig.DATABASE.TABLES.COMMAND.resolve();
        }
    };

    private final @NotNull String id;

    QueryType(@NotNull String id) {
        this.id = id;
    }

    public @NotNull String id() {
        return this.id;
    }

    public abstract @NotNull String tableName();

    public static @Nullable QueryType byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (QueryType type : values()) {
            if (type.id.equalsIgnoreCase(id)) {
                return type;
            }
        }
        return null;
    }
}
