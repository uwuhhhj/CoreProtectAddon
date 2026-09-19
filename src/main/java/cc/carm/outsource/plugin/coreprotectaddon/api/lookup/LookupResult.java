package cc.carm.outsource.plugin.coreprotectaddon.api.lookup;

import java.util.List;

public record LookupResult(boolean success, String errorCode, String message, LookupAction action,
                           int page, int pageSize, int total, boolean truncated, long costMs,
                           List<LookupRecord> records, boolean countOnly, boolean verbose) {
    public LookupResult(boolean success,String errorCode,String message,LookupAction action,int page,int pageSize,
                        int total,boolean truncated,long costMs,List<LookupRecord> records) {
        this(success,errorCode,message,action,page,pageSize,total,truncated,costMs,records,false,false);
    }
    public LookupResult { records = List.copyOf(records); }
    public int totalPages() { return total == 0 ? 0 : (int) (((long) total + pageSize - 1) / pageSize); }

    public static LookupResult failure(LookupAction action, String code, String message, int page, int size, long cost) {
        return new LookupResult(false, code, message, action, page, size, 0, false, cost, List.of());
    }
}
