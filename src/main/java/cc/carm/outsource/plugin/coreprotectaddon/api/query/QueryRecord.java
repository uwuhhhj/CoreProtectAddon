package cc.carm.outsource.plugin.coreprotectaddon.api.query;

import org.jetbrains.annotations.NotNull;

public record QueryRecord(
        long time,
        long playerId,
        @NotNull String playerName,
        @NotNull String message
) {
}
