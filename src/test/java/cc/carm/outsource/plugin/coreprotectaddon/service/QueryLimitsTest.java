package cc.carm.outsource.plugin.coreprotectaddon.service;

import org.junit.Test;
import static org.junit.Assert.*;

public class QueryLimitsTest {
    private final QueryLimits limits = new QueryLimits(true, 604800, 15, 100, 1000, 5);
    @Test public void fixedHistoricalIntervalUsesWidthNotAge() {
        assertArrayEquals(new long[]{2_000_000 - 12 * 86400, 2_000_000 - 10 * 86400}, limits.window("10d-12d", 2_000_000));
        assertArrayEquals(new long[]{2_000_000 - 5400, 2_000_000}, limits.window("1.5h", 2_000_000));
        assertArrayEquals(new long[]{2_000_000 - 604800, 2_000_000}, limits.window("1w", 2_000_000));
    }
    @Test public void requiredTimeAndInvalidRangesAreEnforced() {
        for (String invalid : new String[]{null, "0s", "8d", "1d-", "1d-1d", "1dx", "9h2garbage", "999999999999999999y"})
            assertThrows(String.valueOf(invalid), QueryException.class, () -> limits.window(invalid, 2_000_000));
        assertArrayEquals(new long[]{0, 2_000_000}, new QueryLimits(false, 604800, 15, 100, 1000, 5).window(null, 2_000_000));
    }
    @Test public void pageLimitsAndArithmeticCannotOverflow() {
        assertEquals(15, limits.pageSize(null)); assertEquals(100, limits.pageSize(100));
        assertEquals(990, limits.offset(67, 15));
        assertThrows(QueryException.class, () -> limits.pageSize(101));
        assertThrows(QueryException.class, () -> limits.offset(Integer.MAX_VALUE, 100));
        assertThrows(QueryException.class, () -> limits.offset(0, 15));
        assertThrows(QueryException.class, () -> new QueryLimits(true, 604800, 15, 100, Integer.MAX_VALUE, 5));
    }
}
