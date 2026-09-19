package cc.carm.outsource.plugin.coreprotectaddon.service;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.ItemSnapshot;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class HistoricalItemDecoderTest {
    @Test public void preservesCoreProtectNestedMetadataAndRejectsCorruptPayloads() throws Exception {
        List<?> metadata = new ArrayList<>(List.of(new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("custom-name", "被封存的一缕灵魂", "lore", List.of("通陈", "Alcoholic")))))));
        assertEquals(metadata, HistoricalItemDecoder.readMetadata(serialize(metadata)));
        assertThrows(IOException.class, () -> HistoricalItemDecoder.readMetadata(new byte[]{1,2,3}));
        assertThrows(IOException.class, () -> HistoricalItemDecoder.readMetadata(serialize("not metadata")));
        assertThrows(IOException.class, () -> HistoricalItemDecoder.readMetadata(new byte[8*1024*1024+1]));
    }

    @Test public void snapshotDoesNotExposeMutableDatabaseBytes() {
        byte[] original = {1,2,3};
        ItemSnapshot snapshot = new ItemSnapshot(original);
        original[0] = 4;
        snapshot.metadata()[1] = 5;
        assertArrayEquals(new byte[]{1,2,3},snapshot.metadata());
        assertNull(new ItemSnapshot(null).metadata());
    }

    private static byte[] serialize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) { output.writeObject(value); }
        return bytes.toByteArray();
    }
}
