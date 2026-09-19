package cc.carm.outsource.plugin.coreprotectaddon.service;

public class QueryException extends IllegalArgumentException {
    private final String code;
    public QueryException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
