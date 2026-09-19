package cc.carm.outsource.plugin.coreprotectaddon.api.lookup;

import java.util.List;

/** Independent lookup API; the legacy six-argument QueryRequest is unchanged. */
public record LookupRequest(LookupAction action, List<String> users, String time,
                            Integer page, Integer pageSize, List<String> include,
                            List<String> exclude, String content, LookupOptions options) {
    public LookupRequest(LookupAction action, List<String> users, String time, Integer page,
                         Integer pageSize, List<String> include, List<String> exclude, String content) {
        this(action,users,time,page,pageSize,include,exclude,content,LookupOptions.DEFAULT);
    }
    public LookupRequest {
        users = copy(users);
        include = copy(include);
        exclude = copy(exclude);
        time = normalize(time);
        content = normalize(content);
        options = options == null ? LookupOptions.DEFAULT : options;
    }

    private static List<String> copy(List<String> values) { return values == null ? List.of() : List.copyOf(values); }
    private static String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public LookupRequest withPage(int number, int size) {
        return new LookupRequest(action, users, time, number, size, include, exclude, content, options);
    }
    public LookupRequest withOptions(LookupOptions value) {
        return new LookupRequest(action,users,time,page,pageSize,include,exclude,content,value);
    }
}
