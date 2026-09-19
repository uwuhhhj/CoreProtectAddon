package cc.carm.outsource.plugin.coreprotectaddon.api.lookup;

/** Raw spatial arguments are resolved on the command thread before entering SQL. */
public record LookupOptions(String radius, String world, String coordinates, SpatialBounds bounds,
                            boolean countOnly, boolean verbose) {
    public static final LookupOptions DEFAULT = new LookupOptions(null,null,null,null,false,false);
    public LookupOptions resolved(SpatialBounds value) {
        return new LookupOptions(null,null,null,value,countOnly,verbose);
    }
    public boolean unresolved() { return radius != null || world != null || coordinates != null; }
}
