package cc.carm.outsource.plugin.coreprotectaddon.manager;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class CoreProtectConnectionDiagnosticsTest {
    public static class RunningConfig {
        public String CLICKHOUSE_HOST = "db.internal";
        public String CLICKHOUSE_PASSWORD = "001234-secret";
    }

    @Test public void reportsParsedDifferencesWithoutExposingEitherPassword() throws Exception {
        String different = CoreProtectConnectionDiagnostics.compare(new RunningConfig(), "CLICKHOUSE",
                Map.of("CLICKHOUSE_HOST", "db.internal", "CLICKHOUSE_PASSWORD", "other-secret")).toString();
        assertTrue(different, different.contains("clickhouse-host：相同"));
        assertTrue(different, different.contains("clickhouse-password：不同"));
        assertFalse(different, different.contains("001234-secret"));
        assertFalse(different, different.contains("other-secret"));
        String same = CoreProtectConnectionDiagnostics.compare(new RunningConfig(), "DUCKDB",
                Map.of("CLICKHOUSE_PASSWORD", "001234-secret")).toString();
        assertTrue(same, same.contains("DUCKDB"));
        assertTrue(same, same.contains("clickhouse-password：相同"));
        assertFalse(same, same.contains("001234-secret"));
    }
}
