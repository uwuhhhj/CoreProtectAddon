package cc.carm.outsource.plugin.coreprotectaddon.conf;

import cc.carm.lib.configuration.Configuration;
import cc.carm.lib.configuration.annotation.ConfigPath;
import cc.carm.lib.configuration.annotation.HeaderComments;
import cc.carm.lib.configuration.value.standard.ConfiguredValue;


@ConfigPath(root = true)
public interface PluginConfig extends Configuration {

    ConfiguredValue<Boolean> DEBUG = ConfiguredValue.of(Boolean.class, false);

    @HeaderComments("数据库相关配置")
    interface DATABASE extends Configuration {

        @ConfigPath("driver")
        ConfiguredValue<String> DRIVER_NAME = ConfiguredValue.of("com.mysql.cj.jdbc.Driver");

        ConfiguredValue<String> HOST = ConfiguredValue.of("127.0.0.1");
        ConfiguredValue<Integer> PORT = ConfiguredValue.of(3306);
        ConfiguredValue<String> DATABASE = ConfiguredValue.of("minecraft");
        ConfiguredValue<String> USERNAME = ConfiguredValue.of("root");
        ConfiguredValue<String> PASSWORD = ConfiguredValue.of("password");
        ConfiguredValue<String> EXTRA = ConfiguredValue.of("?sslMode=DISABLED&serverTimezone=Asia/Shanghai");

        static String buildJDBC() {
            return String.format("jdbc:mysql://%s:%s/%s%s",
                    HOST.getNotNull(), PORT.getNotNull(), DATABASE.getNotNull(), EXTRA.getNotNull()
            );
        }

        @HeaderComments("插件相关表的名称")
        interface TABLES extends Configuration {
            ConfiguredValue<String> USERS = ConfiguredValue.of(String.class, "co_user");
            ConfiguredValue<String> CHAT = ConfiguredValue.of(String.class, "co_chat");
            ConfiguredValue<String> COMMAND = ConfiguredValue.of(String.class, "co_command");
            ConfiguredValue<String> ITEM = ConfiguredValue.of(String.class, "co_item");
            ConfiguredValue<String> CONTAINER = ConfiguredValue.of(String.class, "co_container");
            ConfiguredValue<String> MATERIALS = ConfiguredValue.of(String.class, "co_material_map");
            ConfiguredValue<String> WORLDS = ConfiguredValue.of(String.class, "co_world");
        }

    }

    @HeaderComments("查询相关配置")
    interface QUERY extends Configuration {
        @HeaderComments("每页显示的数据数量")
        ConfiguredValue<Integer> PAGE_SIZE = ConfiguredValue.of(15);

        @HeaderComments("所有查询（包括 Java API）是否必须提供时间条件")
        ConfiguredValue<Boolean> REQUIRE_TIME = ConfiguredValue.of(true);
        @HeaderComments("最大查询区间宽度，单位秒；默认 7 天")
        ConfiguredValue<Long> MAX_TIME_SECONDS = ConfiguredValue.of(604800L);
        ConfiguredValue<Integer> MAX_PAGE_SIZE = ConfiguredValue.of(100);
        @HeaderComments("每次查询最多可浏览的记录数，不代表数据库扫描行数")
        ConfiguredValue<Integer> MAX_RESULTS = ConfiguredValue.of(1000);
        ConfiguredValue<Integer> TIMEOUT_SECONDS = ConfiguredValue.of(5);
        ConfiguredValue<Integer> SESSION_TTL_SECONDS = ConfiguredValue.of(300);
        ConfiguredValue<Integer> MAX_SESSIONS = ConfiguredValue.of(256);

        @HeaderComments("仅为旧配置保留；lookup 始终按时间与 rowid 倒序")
        ConfiguredValue<Boolean> REVERSE_ORDER = ConfiguredValue.of(true);

    }


}
