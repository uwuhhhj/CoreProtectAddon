package cc.carm.outsource.plugin.coreprotectaddon.api.lookup;

/** Immutable, inclusive database coordinates. Null X/Z bounds mean the whole world. */
public record SpatialBounds(String world, Integer minX, Integer maxX, Integer minY, Integer maxY,
                            Integer minZ, Integer maxZ) {
    public SpatialBounds {
        if (world == null || world.isBlank()) throw new IllegalArgumentException("World is required");
        pair(minX,maxX); pair(minY,maxY); pair(minZ,maxZ);
        if ((minX == null) != (minZ == null) || (minY != null && minX == null))
            throw new IllegalArgumentException("Incomplete spatial bounds");
    }
    private static void pair(Integer min,Integer max) {
        if ((min == null) != (max == null) || (min != null && min > max))
            throw new IllegalArgumentException("Invalid spatial bounds");
    }
}
