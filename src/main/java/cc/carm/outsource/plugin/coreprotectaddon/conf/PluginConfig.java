package cc.carm.outsource.plugin.coreprotectaddon.conf;

import cc.carm.lib.configuration.Configuration;
import cc.carm.lib.configuration.annotation.ConfigPath;
import cc.carm.lib.configuration.annotation.HeaderComments;
import cc.carm.lib.configuration.value.standard.ConfiguredValue;
import cc.carm.lib.configuration.value.standard.ConfiguredList;


@ConfigPath(root = true)
public interface PluginConfig extends Configuration {

    ConfiguredValue<Boolean> DEBUG = ConfiguredValue.of(Boolean.class, false);

    @ConfigPath("database-type")
    @HeaderComments({"数据库类型：mysql（含 MariaDB）、clickhouse 或 duckdb；修改后执行 /coq reload。",
            "默认为 mysql，继续读取下方原有 database 配置；不会迁移历史数据。",
            "duckdb 复用已启用的 CoreProtect 数据库，以独立只读事务查询，不填写文件路径或账号。"})
    ConfiguredValue<String> DATABASE_TYPE = ConfiguredValue.of("mysql");

    @ConfigPath("table-prefix")
    @HeaderComments({"与 CoreProtect 的 table-prefix 一致，例如 co_。",
            "留空时沿用 database.tables 中的完整表名；填写后统一覆盖这些表名。",
            "duckdb 模式自动使用 CoreProtect 实际运行的表前缀，忽略此项及 database.tables。"})
    ConfiguredValue<String> TABLE_PREFIX = ConfiguredValue.of("");

    @ConfigPath("clickhouse-host")
    @HeaderComments({"ClickHouse 25.6 或更新版本的 HTTP(S) 连接配置，与 CoreProtect 同名。",
            "数据库及兼容视图须先由 CoreProtect 初始化；Addon 仅查询，不建表、不写入。"})
    ConfiguredValue<String> CLICKHOUSE_HOST = ConfiguredValue.of("localhost");
    @ConfigPath("clickhouse-port")
    ConfiguredValue<Integer> CLICKHOUSE_PORT = ConfiguredValue.of(8123);
    @ConfigPath("clickhouse-database")
    ConfiguredValue<String> CLICKHOUSE_DATABASE = ConfiguredValue.of("default");
    @ConfigPath("clickhouse-username")
    ConfiguredValue<String> CLICKHOUSE_USERNAME = ConfiguredValue.of("default");
    @ConfigPath("clickhouse-password")
    ConfiguredValue<String> CLICKHOUSE_PASSWORD = ConfiguredValue.of("");
    @ConfigPath("clickhouse-tls")
    ConfiguredValue<Boolean> CLICKHOUSE_TLS = ConfiguredValue.of(false);

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
            ConfiguredValue<String> BLOCK = ConfiguredValue.of("co_block");
            ConfiguredValue<String> ENTITIES = ConfiguredValue.of("co_entity_map");
            ConfiguredValue<String> SESSION = ConfiguredValue.of("co_session");
            ConfiguredValue<String> SIGN = ConfiguredValue.of("co_sign");
            ConfiguredValue<String> USERNAME = ConfiguredValue.of("co_username_log");
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
        @HeaderComments({"组件筛选最多检查的候选记录数；超过或超时会明确中止，不返回不完整计数。",
                "筛选与物品展示共用分 tick 还原预算，建议先限定玩家、物品类型和时间。"})
        ConfiguredValue<Integer> COMPONENT_MAX_CANDIDATES = ConfiguredValue.of(2000);

        @HeaderComments("仅为旧配置保留；lookup 始终按时间与 rowid 倒序")
        ConfiguredValue<Boolean> REVERSE_ORDER = ConfiguredValue.of(true);

    }

    @ConfigPath("item-panel")
    interface ITEM_PANEL extends Configuration {
        @HeaderComments({"面板展示、content: 补全和省略组件路径时搜索嵌套字符串值的组件白名单。",
                "例如 custom_data 在名单中时，content:\"smc:dou_dizhu_table\" 可匹配其任意层级字段/列表中的完整字符串值。",
                "显式组件条件和 debug 全部组件输出不受白名单限制；手动编辑后 /coq reload。",
                "/coq debug item 的加入/移出白名单按钮会自动保存并立即生效（需要 reload 权限）。",
                "不存在的组件跳过，最多输出前 12 个白名单组件。每行可复制完整 SNBT 条件，直接粘贴到 content: 后。"})
        ConfiguredList<String> COMPONENT_WHITELIST = ConfiguredList.of("minecraft:custom_name", "minecraft:lore",
                "minecraft:potion_contents", "minecraft:custom_model_data", "minecraft:custom_data", "minecraft:map_id");
        @HeaderComments("组件值的聊天预览字符数；复制内容保持完整。范围 20–300。")
        ConfiguredValue<Integer> COMPONENT_PREVIEW_LENGTH = ConfiguredValue.of(100);
    }


}
