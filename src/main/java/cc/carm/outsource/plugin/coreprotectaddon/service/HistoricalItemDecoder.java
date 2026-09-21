package cc.carm.outsource.plugin.coreprotectaddon.service;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.LookupRecord;
import net.coreprotect.database.rollback.RollbackUtil;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.util.List;

/** Restore a detached item on the server thread, using CoreProtect's own metadata format.
 * Does not query CoreProtect, use its lookup cache, or perform a rollback.
 */
public final class HistoricalItemDecoder {
    private HistoricalItemDecoder() { }

    public static ItemStack decode(LookupRecord record) throws IOException, ClassNotFoundException {
        if (record.itemSnapshot() == null) throw new IOException("Historical item was not loaded");
        if (record.itemSnapshot().tooLarge()) throw new IOException("Historical metadata too large");
        Material material = Material.matchMaterial(record.material());
        if (material == null || !material.isItem() || material.isAir()) throw new IOException("Unknown historical material");
        ItemStack item = new ItemStack(material, Math.max(1, record.amount()));
        byte[] bytes = record.itemSnapshot().metadata();
        if (bytes == null || bytes.length == 0) return item;
        Object metadata = readMetadata(bytes);
        // Use the material name resolved from THIS database, not CoreProtect's active id map.
        Object[] restored = RollbackUtil.populateItemStack(item, metadata);
        if (restored.length < 3 || !(restored[2] instanceof ItemStack stack))
            throw new IOException("CoreProtect could not restore the historical item");
        stack.setAmount(Math.max(1, record.amount()));
        return stack;
    }

    static Object readMetadata(byte[] bytes) throws IOException, ClassNotFoundException {
        if (bytes.length > 8 * 1024 * 1024) throw new IOException("Historical metadata too large");
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            input.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                    "maxdepth=64;maxrefs=100000;maxarray=8388608;maxbytes=8388608"));
            Object data = input.readObject();
            if (!(data instanceof List<?>)) throw new IOException("Unexpected CoreProtect metadata format");
            return data;
        }
    }
}
