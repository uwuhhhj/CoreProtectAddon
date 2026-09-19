package cc.carm.outsource.plugin.coreprotectaddon.api.lookup;

/** Immutable database item payload, shared by display and future component predicates. */
public final class ItemSnapshot {
    private final byte[] metadata;

    public ItemSnapshot(byte[] metadata) {
        this.metadata = metadata == null ? null : metadata.clone();
    }

    public byte[] metadata() { return metadata == null ? null : metadata.clone(); }
}
