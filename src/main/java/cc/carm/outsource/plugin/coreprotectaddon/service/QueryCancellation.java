package cc.carm.outsource.plugin.coreprotectaddon.service;

/** A cooperative deadline, including time spent in the worker queue. Never calls JDBC on the caller. */
public final class QueryCancellation {
    private final long deadline;
    private volatile boolean cancelled;
    private Thread runner;

    public QueryCancellation(int seconds) { deadline = System.nanoTime() + seconds * 1_000_000_000L; }
    public long deadline() { return deadline; }
    public boolean cancelled() { return cancelled; }
    public synchronized void attach() { runner = Thread.currentThread(); check(); }
    public synchronized void detach() { runner = null; }
    public synchronized void cancel() {
        cancelled = true;
        if (runner != null) runner.interrupt();
    }
    public void check() {
        if (cancelled || Thread.currentThread().isInterrupted())
            throw new QueryException("QUERY_CANCELLED", "查询已取消。");
        if (System.nanoTime() >= deadline)
            throw new QueryException("QUERY_TIMEOUT", "查询超时，搜索未完成。");
    }
}
