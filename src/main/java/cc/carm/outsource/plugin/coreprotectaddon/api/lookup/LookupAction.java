package cc.carm.outsource.plugin.coreprotectaddon.api.lookup;

import java.util.List;
import java.util.Locale;

/** CoreProtect 23.2 ActionParser / LookupRaw item groups (not inventory groups). */
public enum LookupAction {
    ALL("all", "all", List.of()),
    BLOCK("block", "block", List.of(0,1)), BLOCK_ADD("+block", "block", List.of(1)),
    BLOCK_REMOVE("-block", "block", List.of(0)), CLICK("click", "block", List.of(2)),
    KILL("kill", "block", List.of(3)),
    INVENTORY("inventory", "inventory", List.of()), INVENTORY_ADD("+inventory", "inventory", List.of()),
    INVENTORY_REMOVE("-inventory", "inventory", List.of()),
    SIGN("sign", "sign", List.of(1)), SESSION("session", "session", List.of(0,1)),
    SESSION_ADD("+session", "session", List.of(1)), SESSION_REMOVE("-session", "session", List.of(0)),
    USERNAME("username", "username_log", List.of()),
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
    public boolean inventory() { return family.equals("inventory"); }
    public boolean hasMaterials() { return isItem() || inventory() || family.equals("block") || this == ALL; }
    public boolean messages() { return this == CHAT || this == COMMAND || this == SIGN; }

    public static LookupAction byId(String input) {
        if (input == null) return null;
        String value = input.toLowerCase(Locale.ROOT);
        if (value.startsWith("#")) value = value.substring(1);
        value = switch (value) {
            case "blocks", "block-change", "change", "changes" -> "block";
            case "broke", "break", "remove", "destroy", "block-break", "block-remove", "-blocks", "block-" -> "-block";
            case "placed", "place", "block-place", "+blocks", "block+" -> "+block";
            case "clicks", "interact", "interaction", "player-interact", "player-interaction", "player-click" -> "click";
            case "death", "deaths", "entity-death", "entity-deaths", "kills", "entity-kill", "entity-kills" -> "kill";
            case "inv", "inventories" -> "inventory";
            case "+inv", "inv+", "inventory+", "+inventories" -> "+inventory";
            case "-inv", "inv-", "inventory-", "-inventories" -> "-inventory";
            case "signs" -> "sign";
            case "sessions", "connection", "connections" -> "session";
            case "login", "logins", "+sessions", "session+", "+connection", "connection+" -> "+session";
            case "logout", "logouts", "-sessions", "session-", "-connection", "connection-" -> "-session";
            case "usernames", "user", "users", "name", "names", "uuid", "uuids", "username-change", "username-changes", "name-change", "name-changes" -> "username";
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
