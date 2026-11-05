package cc.carm.outsource.plugin.coreprotectaddon.manager;

import org.sqlite.Function;
import org.sqlite.ProgressHandler;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** SQLite has no built-in REGEXP and JDBC queryTimeout alone only bounds lock waits. */
public final class SQLiteQuerySupport {
    private SQLiteQuerySupport() { }

    public static void configure(Connection connection, long deadline) throws SQLException {
        ProgressHandler.setHandler(connection, 1000, new ProgressHandler() {
            @Override protected int progress() {
                return expired(deadline) ? 1 : 0;
            }
        });
        Function.create(connection, "regexp", new Function() {
            private String expression;
            private Pattern pattern;

            @Override protected void xFunc() throws SQLException {
                if (expired(deadline)) throw new SQLTimeoutException("SQLite query deadline expired");
                String regex = value_text(0), text = value_text(1);
                if (regex == null || text == null) { result(0); return; }
                if (regex.length() > 4096) throw new SQLException("SQLite 正则表达式不能超过 4096 字符。");
                try {
                    if (!regex.equals(expression)) {
                        pattern = Pattern.compile(regex);
                        expression = regex;
                    }
                    result(pattern.matcher(new TimedText(text, deadline)).find() ? 1 : 0);
                } catch (PatternSyntaxException ex) {
                    throw new SQLException("SQLite 正则表达式无效。", ex);
                } catch (RegexTimeout ex) {
                    throw new SQLTimeoutException("SQLite regex deadline expired", ex);
                } catch (StackOverflowError ex) {
                    throw new SQLException("SQLite 正则表达式过于复杂，请简化筛选条件。", ex);
                }
            }
        }, 2);
    }

    private static boolean expired(long deadline) {
        return Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline;
    }

    private static final class RegexTimeout extends RuntimeException { }

    /** Also checks time inside Java's backtracking matcher, between SQLite progress callbacks. */
    private record TimedText(String text, long deadline) implements CharSequence {
        private void check() { if (expired(deadline)) throw new RegexTimeout(); }
        @Override public int length() { check(); return text.length(); }
        @Override public char charAt(int index) { check(); return text.charAt(index); }
        @Override public CharSequence subSequence(int start, int end) {
            check(); return new TimedText(text.substring(start, end), deadline);
        }
        @Override public String toString() { check(); return text; }
    }
}
