package cc.carm.outsource.plugin.coreprotectaddon.api.lookup;

/** Immutable database item payload, shared by display and future component predicates. */
public final class ItemSnapshot {
    public static final int MAX_METADATA_BYTES = 8 * 1024 * 1024;
    private final byte[] metadata;
    private final boolean oversized;

    public ItemSnapshot(byte[] metadata) {
        this.oversized = metadata != null && metadata.length > MAX_METADATA_BYTES;
        this.metadata = oversized || metadata == null ? null : metadata.clone();
    }
    private ItemSnapshot() { this.metadata = null; this.oversized = true; }
    public static ItemSnapshot oversized() { return new ItemSnapshot(); }
    public boolean tooLarge() { return oversized; }
    public int size() { return metadata == null ? 0 : metadata.length; }

    public byte[] metadata() { return metadata == null ? null : metadata.clone(); }
}
