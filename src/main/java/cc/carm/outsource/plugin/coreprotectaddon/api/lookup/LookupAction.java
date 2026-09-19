package cc.carm.outsource.plugin.coreprotectaddon.api.lookup;

import java.util.List;
import java.util.Locale;

/** CoreProtect 23.2 ActionParser / LookupRaw item groups (not inventory groups). */
public enum LookupAction {
    CHAT("chat", "chat", List.of()), COMMAND("command", "command", List.of()),
    ITEM("item", "item", List.of(0, 1, 2, 3, 4, 5, 6, 7)),
    ITEM_ADD("+item", "item", List.of(3, 4)),
    ITEM_REMOVE("-item", "item", List.of(2, 5, 6, 7)),
    CONTAINER("container", "container", List.of(0, 1)),
    CONTAINER_ADD("+container", "container", List.of(1)),
    CONTAINER_REMOVE("-container", "container", List.of(0));

    private final String id;
    private final String family;
    private final List<Integer> actions;

    LookupAction(String id, String family, List<Integer> actions) {
        this.id = id;
        this.family = family;
        this.actions = actions;
    }

    public String id() { return id; }
    public String family() { return family; }
    public List<Integer> actions() { return actions; }
    public boolean isItem() { return family.equals("item") || family.equals("container"); }

    public static LookupAction byId(String input) {
        if (input == null) return null;
        String value = input.toLowerCase(Locale.ROOT);
        if (value.startsWith("#")) value = value.substring(1);
        value = switch (value) {
            case "chats" -> "chat";
            case "commands" -> "command";
            case "items" -> "item";
            case "item+", "items+", "+items", "pickup", "pickups", "withdraw", "withdraws", "withdrew" -> "+item";
            case "item-", "items-", "-items", "drop", "drops", "deposit", "deposits", "deposited" -> "-item";
            case "containers", "container-change", "chest", "transaction", "transactions" -> "container";
            case "container+", "container-add", "add-container" -> "+container";
            case "container-", "remove-container" -> "-container";
            default -> value;
        };
        for (LookupAction action : values()) if (action.id.equals(value)) return action;
        return null;
    }
}
