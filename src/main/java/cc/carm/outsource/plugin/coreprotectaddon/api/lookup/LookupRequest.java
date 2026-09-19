package cc.carm.outsource.plugin.coreprotectaddon.api.lookup;

import java.util.List;

/** Independent lookup API; the legacy six-argument QueryRequest is unchanged. */
public record LookupRequest(LookupAction action, List<String> users, String time,
                            Integer page, Integer pageSize, List<String> include,
                            List<String> exclude, String content) {
    public LookupRequest {
        users = copy(users);
        include = copy(include);
        exclude = copy(exclude);
        time = normalize(time);
        content = normalize(content);
    }

    private static List<String> copy(List<String> values) { return values == null ? List.of() : List.copyOf(values); }
    private static String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public LookupRequest withPage(int number, int size) {
        return new LookupRequest(action, users, time, number, size, include, exclude, content);
    }
}
