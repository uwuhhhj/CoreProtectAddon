package cc.carm.outsource.plugin.coreprotectaddon.api.lookup;

/** Historical item bytes are preserved independently of the live CoreProtect material-id cache. */
public record LookupRecord(long rowId, long time, long playerId, String playerName,
                           String world, Integer x, Integer y, Integer z,
                           String message, String material, int amount, int action, ItemSnapshot itemSnapshot,
                           String source, int rolledBack) {
    public LookupRecord(long rowId, long time, long playerId, String playerName,
                        String world, Integer x, Integer y, Integer z, String message,
                        String material, int amount, int action, ItemSnapshot snapshot) {
        this(rowId,time,playerId,playerName,world,x,y,z,message,material,amount,action,snapshot,null,0);
    }
    public LookupAction recordAction(LookupAction fallback) {
        if (source == null) return fallback;
        return switch (source) {
            case "block" -> action == 2 ? LookupAction.CLICK : action == 3 ? LookupAction.KILL : LookupAction.BLOCK;
            case "username_log" -> LookupAction.USERNAME;
            default -> LookupAction.byId(source);
        };
    }
    public LookupRecord(long rowId, long time, long playerId, String playerName,
                        String world, Integer x, Integer y, Integer z,
                        String message, String material, int amount, int action) {
        this(rowId, time, playerId, playerName, world, x, y, z, message, material, amount, action, null);
    }
}
