package cc.carm.outsource.plugin.coreprotectaddon.api.lookup;

/** message is populated for text records; material/amount/action for item records. No metadata is loaded. */
public record LookupRecord(long rowId, long time, long playerId, String playerName,
                           String world, Integer x, Integer y, Integer z,
                           String message, String material, int amount, int action) {
}
