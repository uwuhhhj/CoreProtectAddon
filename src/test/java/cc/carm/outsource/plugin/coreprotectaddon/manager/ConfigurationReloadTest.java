package cc.carm.outsource.plugin.coreprotectaddon.manager;

import cc.carm.lib.mineconfiguration.bukkit.MineConfiguration;
import cc.carm.outsource.plugin.coreprotectaddon.conf.ConfigurationReload;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ConfigurationReloadTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void detachedLoadDoesNotPublishCandidateUntilAppliedAndCanRollBack() throws Exception {
        var file = temporary.newFile("staged.yml").toPath();
        Files.writeString(file,"query:\n  page-size: 12\nitem-panel:\n  component-whitelist: [minecraft:map_id]\n");
        var holder = MineConfiguration.from(file.toFile(),"UTF-8"); holder.initialize(PluginConfig.class);
        Files.writeString(file,"query:\n  page-size: 21\nitem-panel:\n  component-whitelist: [minecraft:custom_data]\n");
        var staged = ConfigurationReload.read(file);
        assertEquals(Integer.valueOf(12),PluginConfig.QUERY.PAGE_SIZE.getNotNull());
        try (var rollback = new ConfigurationReload(holder)) {
            ConfigurationReload.apply(holder,staged); ConfigurationReload.validate();
            assertEquals(Integer.valueOf(21),PluginConfig.QUERY.PAGE_SIZE.getNotNull());
            assertEquals(java.util.List.of("minecraft:custom_data"),PluginConfig.ITEM_PANEL.COMPONENT_WHITELIST.copy());
        }
        assertEquals(Integer.valueOf(12),PluginConfig.QUERY.PAGE_SIZE.getNotNull());
        assertEquals(java.util.List.of("minecraft:map_id"),PluginConfig.ITEM_PANEL.COMPONENT_WHITELIST.copy());
        ConfigurationReload.apply(holder,staged);
        assertEquals(Integer.valueOf(21),PluginConfig.QUERY.PAGE_SIZE.getNotNull());
    }

    @Test public void detachedMessagesPreserveTextContents() throws Exception {
        var file = temporary.newFile("messages.yml").toPath(); Files.writeString(file,"no-permission: before\n");
        var holder = MineConfiguration.from(file.toFile(),"UTF-8");
        holder.initialize(cc.carm.outsource.plugin.coreprotectaddon.conf.PluginMessages.class);
        Files.writeString(file,"no-permission: [after, second]\n");
        ConfigurationReload.apply(holder,ConfigurationReload.read(file));
        assertEquals(java.util.List.of("after","second"),cc.carm.outsource.plugin.coreprotectaddon.conf.PluginMessages.NO_PERMISSION.getNotNull().lines());
    }

    @Test public void failedReloadRestoresCachedValuesAndSourceWithoutOverwritingEditedFile() throws Exception {
        var file = temporary.newFile("config.yml").toPath();
        Files.writeString(file,"query:\n  page-size: 12\n  session-ttl-seconds: 60\ndatabase-type: mysql\n");
        var holder = MineConfiguration.from(file.toFile(),"UTF-8");
        holder.initialize(PluginConfig.class);
        assertEquals(Integer.valueOf(12),PluginConfig.QUERY.PAGE_SIZE.getNotNull());
        String edited = "query:\n  page-size: 99\n  session-ttl-seconds: 0\ndatabase-type: invalid\n";
        try (var transaction = new ConfigurationReload(holder)) {
            Files.writeString(file,edited);
            holder.reload();
            assertEquals(Integer.valueOf(99),PluginConfig.QUERY.PAGE_SIZE.getNotNull());
            assertThrows(IllegalArgumentException.class,ConfigurationReload::validate);
        }
        assertEquals(Integer.valueOf(12),PluginConfig.QUERY.PAGE_SIZE.getNotNull());
        assertEquals(Integer.valueOf(60),PluginConfig.QUERY.SESSION_TTL_SECONDS.getNotNull());
        assertEquals("mysql",PluginConfig.DATABASE_TYPE.getNotNull());
        assertEquals(12,holder.config().original().getInt("query.page-size"));
        assertEquals(edited,Files.readString(file));
    }

    @Test public void committedReloadKeepsNewValues() throws Exception {
        var file = temporary.newFile("config.yml").toPath();
        Files.writeString(file,"query:\n  page-size: 12\n");
        var holder = MineConfiguration.from(file.toFile(),"UTF-8");
        holder.initialize(PluginConfig.class);
        try (var transaction = new ConfigurationReload(holder)) {
            Files.writeString(file,"query:\n  page-size: 20\n  session-ttl-seconds: 120\n");
            holder.reload();
            ConfigurationReload.validate();
            transaction.commit();
        }
        assertEquals(Integer.valueOf(20),PluginConfig.QUERY.PAGE_SIZE.getNotNull());
        assertEquals(Integer.valueOf(120),PluginConfig.QUERY.SESSION_TTL_SECONDS.getNotNull());
    }
}
