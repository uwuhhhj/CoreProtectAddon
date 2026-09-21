package cc.carm.outsource.plugin.coreprotectaddon.service;

import cc.carm.outsource.plugin.coreprotectaddon.api.lookup.LookupRecord;
import java.util.BitSet;
import java.util.List;

/** Worker-side contract. Implementation schedules bounded item work on the server thread. */
@FunctionalInterface
public interface ComponentMatcher {
    BitSet match(List<LookupRecord> records, String content, long deadlineNanos);
    /** Compile once per query. Existing embedders/test matchers retain their original contract. */
    default ComponentMatcher prepare(String content, long deadlineNanos) {
        match(List.of(),content,deadlineNanos);
        return this;
    }
}
