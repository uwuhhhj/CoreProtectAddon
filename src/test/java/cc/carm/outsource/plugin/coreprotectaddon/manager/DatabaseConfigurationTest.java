package cc.carm.outsource.plugin.coreprotectaddon.manager;

import cc.carm.lib.mineconfiguration.bukkit.MineConfiguration;
import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig;
import cc.carm.outsource.plugin.coreprotectaddon.service.CoreProtectQueryService.Tables;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;

import static org.junit.Assert.*;

public class DatabaseConfigurationTest {
    @Test public void missingNetworkTimeoutsAreBoundedWithoutOverwritingExplicitSettings() {
        assertEquals("jdbc:mysql://localhost/test?sslMode=DISABLED&connectTimeout=5000&socketTimeout=5000",
                DataManager.boundedJdbc("jdbc:mysql://localhost/test?sslMode=DISABLED",5));
        assertEquals("jdbc:mysql://localhost/test?connectTimeout=2000&socketTimeout=3000",
                DataManager.boundedJdbc("jdbc:mysql://localhost/test?connectTimeout=2000&socketTimeout=3000",5));
    }
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void oldConfigKeepsItsConnectionAndTableNamesWhileNewKeysRoundTrip() throws Exception {
        var file = temporary.newFile("config.yml").toPath();
        Files.writeString(file, """
                database:
                  host: old-db
                  port: 3308
                  database: old_logs
                  username: old_user
                  password: old_password
                  tables:
                    users: archive_user
                    chat: archive_chat
                """);
        var holder = MineConfiguration.from(file.toFile(), "UTF-8");
        holder.initialize(PluginConfig.class);
        assertEquals(DatabaseType.MYSQL, DatabaseType.parse(PluginConfig.DATABASE_TYPE.getNotNull()));
        assertTrue(PluginConfig.DATABASE.buildJDBC().startsWith("jdbc:mysql://old-db:3308/old_logs"));
        assertEquals("archive_user", Tables.configured().users());
        assertEquals("archive_chat", Tables.configured().chat());
        assertEquals("", PluginConfig.CLICKHOUSE_PASSWORD.getNotNull());
        holder.save();
        String generated = Files.readString(file);
        for (String key : new String[]{"database-type", "table-prefix", "clickhouse-host", "clickhouse-port",
                "clickhouse-database", "clickhouse-username", "clickhouse-password", "clickhouse-tls"}) {
            assertTrue(key, generated.matches("(?s).*(?:^|\\n)" + key + ":.*"));
        }
        var yaml = holder.config().original();
        yaml.set("database-type", "clickhouse");
        yaml.set("table-prefix", "shared_");
        yaml.set("clickhouse-host", "clickhouse");
        yaml.set("clickhouse-password", "p'&?#");
        yaml.set("clickhouse-tls", true);
        yaml.set("item-panel.component-whitelist", java.util.List.of("minecraft:max_stack_size","minecraft:custom_data"));
        yaml.set("item-panel.component-preview-length", 80);
        yaml.set("query.component-max-candidates", 750);
        holder.save();
        holder.reload();
        assertEquals(DatabaseType.CLICKHOUSE, DatabaseType.parse(PluginConfig.DATABASE_TYPE.getNotNull()));
        assertEquals("shared_user", Tables.configured().users());
        assertEquals("shared_material_map", Tables.configured().materials());
        assertEquals("p'&?#", PluginConfig.CLICKHOUSE_PASSWORD.getNotNull());
        assertTrue(PluginConfig.CLICKHOUSE_TLS.getNotNull());
        assertEquals(java.util.List.of("minecraft:max_stack_size","minecraft:custom_data"),PluginConfig.ITEM_PANEL.COMPONENT_WHITELIST.copy());
        assertEquals(Integer.valueOf(80),PluginConfig.ITEM_PANEL.COMPONENT_PREVIEW_LENGTH.getNotNull());
        assertEquals(Integer.valueOf(750),PluginConfig.QUERY.COMPONENT_MAX_CANDIDATES.getNotNull());
        yaml = holder.config().original();
        yaml.set("database-type", "duckdb");
        holder.save();
        holder.reload();
        assertEquals(DatabaseType.DUCKDB, DatabaseType.parse(PluginConfig.DATABASE_TYPE.getNotNull()));
        assertEquals("old_user", PluginConfig.DATABASE.USERNAME.getNotNull());
        assertEquals("p'&?#", PluginConfig.CLICKHOUSE_PASSWORD.getNotNull());
        yaml = holder.config().original();
        yaml.set("database-type", "mysql");
        yaml.set("table-prefix", "");
        holder.save();
        holder.reload();
        assertEquals("archive_user", Tables.configured().users());
        assertEquals("old_user", PluginConfig.DATABASE.USERNAME.getNotNull());
    }
}
