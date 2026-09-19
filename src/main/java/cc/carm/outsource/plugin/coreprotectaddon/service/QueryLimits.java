package cc.carm.outsource.plugin.coreprotectaddon.service;

import cc.carm.outsource.plugin.coreprotectaddon.conf.PluginConfig;
import cc.carm.outsource.plugin.coreprotectaddon.utils.TimeFormatUtils;
import java.time.Duration;

/** Small shared validation helper, used by both lookup and the legacy Java API. */
public record QueryLimits(boolean requireTime, long maxSpanSeconds, int defaultPageSize,
                          int maxPageSize, int maxResults, int timeoutSeconds) {
    public QueryLimits {
        if (maxSpanSeconds < 1 || defaultPageSize < 1 || maxPageSize < defaultPageSize || maxResults < 1
                || maxResults == Integer.MAX_VALUE || timeoutSeconds < 1 || timeoutSeconds > 3600) {
            throw new QueryException("INVALID_CONFIG", "查询限制配置无效，请检查 query 配置。");
        }
    }

    public static QueryLimits configured() {
        return new QueryLimits(PluginConfig.QUERY.REQUIRE_TIME.resolve(), PluginConfig.QUERY.MAX_TIME_SECONDS.resolve(),
                PluginConfig.QUERY.PAGE_SIZE.resolve(), PluginConfig.QUERY.MAX_PAGE_SIZE.resolve(),
                PluginConfig.QUERY.MAX_RESULTS.resolve(), PluginConfig.QUERY.TIMEOUT_SECONDS.resolve());
    }

    public int pageSize(Integer requested) {
        int size = requested == null ? defaultPageSize : requested;
        if (size < 1 || size > maxPageSize) throw new QueryException("INVALID_PAGE", "每页条数必须为 1–" + maxPageSize + "。");
        return size;
    }

    public int offset(int page, int size) {
        long offset = ((long) page - 1) * size;
        if (page < 1 || offset < 0 || offset >= maxResults) throw new QueryException("INVALID_PAGE", "页码超出可浏览范围（最多 " + maxResults + " 条）。");
        return (int) offset;
    }

    public long[] window(String input, long anchor) {
        if (input == null) {
            if (requireTime) throw new QueryException("TIME_REQUIRED", "请提供 t:<时间>，例如 t:1d。");
            return new long[]{0, anchor};
        }
        Duration[] interval = TimeFormatUtils.parseInterval(input);
        if (interval == null) throw new QueryException("INVALID_TIME", "时间格式无效，例如 t:1d 或 t:10d-12d。");
        long older = Math.max(interval[0].getSeconds(), interval[1].getSeconds());
        long newer = Math.min(interval[0].getSeconds(), interval[1].getSeconds());
        if (older <= newer || older - newer > maxSpanSeconds || older > anchor) {
            throw new QueryException("INVALID_TIME", "时间区间必须大于 0，且宽度不超过 " + maxSpanSeconds + " 秒。");
        }
        return new long[]{anchor - older, anchor - newer};
    }
}
