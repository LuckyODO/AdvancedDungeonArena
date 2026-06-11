package su.nightexpress.dungeons.dungeon.menu;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.MenuType;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.dungeons.DungeonPlugin;
import su.nightexpress.dungeons.api.type.GameState;
import su.nightexpress.dungeons.config.Config;
import su.nightexpress.dungeons.dungeon.config.DungeonConfig;
import su.nightexpress.dungeons.dungeon.game.DungeonInstance;
import su.nightexpress.dungeons.dungeon.module.Features;
import su.nightexpress.dungeons.user.DungeonUser;
import su.nightexpress.nightcore.config.ConfigValue;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.config.Writeable;
import su.nightexpress.nightcore.core.config.CoreLang;
import su.nightexpress.nightcore.ui.menu.MenuViewer;
import su.nightexpress.nightcore.ui.menu.data.ConfigBased;
import su.nightexpress.nightcore.ui.menu.data.Filled;
import su.nightexpress.nightcore.ui.menu.data.MenuFiller;
import su.nightexpress.nightcore.ui.menu.data.MenuLoader;
import su.nightexpress.nightcore.ui.menu.item.MenuItem;
import su.nightexpress.nightcore.ui.menu.type.NormalMenu;
import su.nightexpress.nightcore.util.Lists;
import su.nightexpress.nightcore.util.bukkit.NightItem;
import su.nightexpress.nightcore.util.time.TimeFormatType;
import su.nightexpress.nightcore.util.time.TimeFormats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import static su.nightexpress.dungeons.Placeholders.*;
import static su.nightexpress.nightcore.util.text.tag.Tags.*;

public class DungeonBrowseMenu extends NormalMenu<DungeonPlugin> implements Filled<DungeonBrowseMenu.BrowseEntry>, ConfigBased {

    public static final String FILE_NAME = "dungeon_browse.yml";

    private static final String COOLDOWN = "%cooldown%";
    private static final String BROWSE_GROUP = "%browse_group%";
    private static final String BROWSE_ROOMS = "%browse_rooms%";
    private static final String BROWSE_AVAILABLE_ROOMS = "%browse_available_rooms%";
    private static final String BROWSE_BUSY_ROOMS = "%browse_busy_rooms%";
    private static final String BROWSE_INGAME_ROOMS = "%browse_ingame_rooms%";
    private static final String BROWSE_FULL_ROOMS = "%browse_full_rooms%";
    private static final String BROWSE_SELECTED_ROOM = "%browse_selected_room%";

    private String       dungeonName;
    private List<String> dungeonUnlockedInfo;
    private List<String> dungeonLockedInfo;
    private List<String> dungeonCooldownInfo;
    private List<String> dungeonAllBusyInfo;

    private boolean                  gridAutoEnabled;
    private int[]                    gridAutoSlots;
    private int                      gridCustomPages;
    private Map<String, DungeonSlot> gridCustomSlots;

    private boolean groupingEnabled;
    private GroupMode groupingMode;
    private Map<String, String> groupOverrides;
    private boolean busyWhenIngame;
    private boolean busyWhenFull;

    private final Map<String, String> selectedDungeonByGroup;

    public DungeonBrowseMenu(@NotNull DungeonPlugin plugin) {
        super(plugin, MenuType.GENERIC_9X4, BLACK.wrap("Dungeons"));

        this.selectedDungeonByGroup = new HashMap<>();

        this.load(FileConfig.loadOrExtract(plugin, Config.DIR_MENU, FILE_NAME));
    }

    @Override
    @NotNull
    public MenuFiller<BrowseEntry> createFiller(@NotNull MenuViewer viewer) {
        Player player = viewer.getPlayer();

        return MenuFiller.<BrowseEntry>builder(this)
            .setSlots(this.gridAutoSlots)
            .setItems(this.getBrowseEntries())
            .setItemCreator(entry -> this.getDungeonIcon(player, entry))
            .setItemClick(entry -> (viewer1, event) -> this.onDungeonClick(viewer1, entry))
            .build();
    }

    @Override
    public void autoFill(@NotNull MenuViewer viewer) {
        if (this.gridAutoEnabled) {
            Filled.super.autoFill(viewer);
            return;
        }

        Player player = viewer.getPlayer();
        int page = viewer.getPage();

        this.getBrowseEntries().forEach(entry -> {
            DungeonSlot slot = this.getCustomSlot(entry);
            if (slot == null || slot.page != page) return;

            NightItem item = this.getDungeonIcon(player, entry);
            MenuItem menuItem = item.toMenuItem().setSlots(slot.slots).setPriority(100).setHandler((viewer1, event) -> {
                this.onDungeonClick(viewer1, entry);
            }).build();

            viewer.addItem(menuItem);
        });
    }

    private void onDungeonClick(@NotNull MenuViewer viewer, @NotNull BrowseEntry entry) {
        Player player = viewer.getPlayer();

        this.runNextTick(() -> {
            BrowseSelection selection = this.selectDungeon(player, entry);
            if (!selection.canJoin()) {
                this.flush(player);
                return;
            }

            this.plugin.getDungeonManager().prepareForInstance(player, selection.config().getInstance());
        });
    }

    @NotNull
    private NightItem getDungeonIcon(@NotNull Player player, @NotNull BrowseEntry entry) {
        BrowseSelection selection = this.selectDungeon(player, entry);
        DungeonConfig config = selection.config();
        DungeonInstance dungeon = config.getInstance();
        DungeonUser user = plugin.getUserManager().getOrFetch(player);
        boolean onCooldown = user.isOnCooldown(dungeon);
        BrowseCounts counts = BrowseCounts.of(player, entry, this);
        List<String> lore;

        if (selection.canJoin()) {
            lore = this.dungeonUnlockedInfo;
        }
        else if (selection.allBusy()) {
            lore = this.dungeonAllBusyInfo;
        }
        else {
            lore = onCooldown ? this.dungeonCooldownInfo : this.dungeonLockedInfo;
        }

        return config.getIcon()
            .hideAllComponents()
            .setDisplayName(this.dungeonName)
            .setLore(lore)
            .replacement(replacer -> replacer
                .replace(dungeon.replacePlaceholders())
                .replace(BROWSE_GROUP, entry.group())
                .replace(BROWSE_ROOMS, counts.total())
                .replace(BROWSE_AVAILABLE_ROOMS, counts.available())
                .replace(BROWSE_BUSY_ROOMS, counts.busy())
                .replace(BROWSE_INGAME_ROOMS, counts.ingame())
                .replace(BROWSE_FULL_ROOMS, counts.full())
                .replace(BROWSE_SELECTED_ROOM, dungeon.getId())
                .replace(COOLDOWN, () -> {
                    if (onCooldown) {
                        return TimeFormats.formatDuration(user.getArenaCooldown(dungeon), TimeFormatType.LITERAL);
                    }

                    Features features = config.features();
                    int cooldown = features.getEntranceCooldown().getSmallest(player).intValue();
                    if (cooldown == 0L) return CoreLang.OTHER_NONE.text();

                    return TimeFormats.formatAmount(cooldown * 1000L, TimeFormatType.LITERAL);
                })
            );
    }

    @NotNull
    private List<BrowseEntry> getBrowseEntries() {
        List<DungeonConfig> dungeons = this.plugin.getDungeonManager().getDungeons().stream()
            .filter(config -> !config.isBroken())
            .sorted(Comparator.comparing(DungeonConfig::getName).thenComparing(DungeonConfig::getId))
            .toList();

        if (!this.groupingEnabled) {
            return dungeons.stream().map(config -> new BrowseEntry(config.getId(), List.of(config))).toList();
        }

        Map<String, List<DungeonConfig>> grouped = new LinkedHashMap<>();
        dungeons.forEach(config -> grouped.computeIfAbsent(this.getGroup(config), k -> new ArrayList<>()).add(config));

        return grouped.entrySet().stream()
            .map(entry -> new BrowseEntry(entry.getKey(), List.copyOf(entry.getValue())))
            .sorted(Comparator.comparing(BrowseEntry::displayName).thenComparing(BrowseEntry::group))
            .toList();
    }

    @NotNull
    private String getGroup(@NotNull DungeonConfig config) {
        String override = this.groupOverrides.get(config.getId().toLowerCase(Locale.ROOT));
        if (override != null && !override.isBlank()) {
            return this.normalizeGroup(override);
        }

        String value = switch (this.groupingMode) {
            case ID -> config.getId();
            case NAME -> config.getName();
            case PREFIX -> config.getPrefix();
        };

        return this.normalizeGroup(value);
    }

    @NotNull
    private String normalizeGroup(@NotNull String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? DEFAULT : normalized;
    }

    @NotNull
    private BrowseSelection selectDungeon(@NotNull Player player, @NotNull BrowseEntry entry) {
        List<DungeonConfig> configs = entry.configs();
        String selectedId = this.selectedDungeonByGroup.get(entry.group());
        int selectedIndex = selectedId == null ? -1 : entry.indexOf(selectedId);

        if (selectedIndex >= 0) {
            DungeonConfig selected = configs.get(selectedIndex);
            if (this.canBrowseJoin(player, selected)) {
                return new BrowseSelection(selected, true, false);
            }
        }

        int startIndex = selectedIndex < 0 ? 0 : selectedIndex + 1;
        DungeonConfig fallback = null;

        for (int index = 0; index < configs.size(); index++) {
            DungeonConfig config = configs.get((startIndex + index) % configs.size());

            if (this.canBrowseJoin(player, config)) {
                this.selectedDungeonByGroup.put(entry.group(), config.getId());
                return new BrowseSelection(config, true, false);
            }

            if (fallback == null && !this.isBusy(config)) {
                fallback = config;
            }
        }

        boolean allBusy = entry.configs().stream().allMatch(this::isBusy);
        return new BrowseSelection(fallback == null ? entry.representative() : fallback, false, allBusy);
    }

    private boolean canBrowseJoin(@NotNull Player player, @NotNull DungeonConfig config) {
        DungeonInstance dungeon = config.getInstance();
        return !this.isBusy(config) && dungeon.canJoin(player, false, false);
    }

    private boolean isBusy(@NotNull DungeonConfig config) {
        DungeonInstance dungeon = config.getInstance();
        if (this.busyWhenIngame && dungeon.getState() == GameState.INGAME) return true;

        int maxPlayers = config.gameSettings().getMaxPlayers();
        return this.busyWhenFull && maxPlayers > 0 && dungeon.countPlayers() >= maxPlayers;
    }

    private boolean isFull(@NotNull DungeonConfig config) {
        DungeonInstance dungeon = config.getInstance();
        int maxPlayers = config.gameSettings().getMaxPlayers();
        return maxPlayers > 0 && dungeon.countPlayers() >= maxPlayers;
    }

    private DungeonSlot getCustomSlot(@NotNull BrowseEntry entry) {
        DungeonSlot groupSlot = this.gridCustomSlots.get(entry.group());
        if (groupSlot != null) return groupSlot;

        for (DungeonConfig config : entry.configs()) {
            DungeonSlot slot = this.gridCustomSlots.get(config.getId());
            if (slot != null) return slot;
        }

        return null;
    }

    @Override
    protected void onPrepare(@NotNull MenuViewer viewer, @NotNull InventoryView view) {
        if (!this.gridAutoEnabled) {
            viewer.setPages(this.gridCustomPages);
        }

        this.autoFill(viewer);
    }

    @Override
    protected void onReady(@NotNull MenuViewer viewer, @NotNull Inventory inventory) {

    }

    @Override
    public void loadConfiguration(@NotNull FileConfig config, @NotNull MenuLoader loader) {
        int[] defSlots = IntStream.range(10, 17).toArray();

        this.groupingEnabled = ConfigValue.create("Dungeon.Grouping.Enabled", true,
            "When enabled, duplicate dungeon rooms are displayed as one browse button.",
            "The button dynamically points to the first available room in the group."
        ).read(config);
        this.groupingMode = ConfigValue.create("Dungeon.Grouping.Mode", GroupMode.class, GroupMode.NAME,
            "Defines how duplicate rooms are grouped by default.",
            "Allowed values: ID, NAME, PREFIX.",
            "Use ID to keep one button per dungeon."
        ).read(config);
        this.groupOverrides = ConfigValue.forMapById("Dungeon.Grouping.Overrides",
            (cfg, path) -> cfg.getString(path, ""),
            map -> {
                map.put("your_dungeon_1", "shared_dungeon_group");
                map.put("your_dungeon_2", "shared_dungeon_group");
            },
            "Optional per-dungeon group keys. Dungeon ids mapped to the same value share one browse button."
        ).read(config);
        this.busyWhenIngame = ConfigValue.create("Dungeon.Grouping.Busy.When_Ingame", true,
            "When true, INGAME rooms are skipped by browse buttons and counted as busy."
        ).read(config);
        this.busyWhenFull = ConfigValue.create("Dungeon.Grouping.Busy.When_Full", true,
            "When true, full lobby rooms are skipped by browse buttons and counted as busy."
        ).read(config);
        this.setAutoRefreshInterval(ConfigValue.create("Dungeon.Grouping.Refresh_Ticks", 20,
            "How often an opened browse menu refreshes itself, in server ticks.",
            "Set to -1 to disable automatic visual refresh. Clicks still resolve the latest room."
        ).read(config));

        this.gridAutoEnabled = ConfigValue.create("Dungeon.Grid.Auto.Enabled", true).read(config);
        this.gridAutoSlots = ConfigValue.create("Dungeon.Grid.Auto.Slots", defSlots).read(config);
        this.gridCustomPages = ConfigValue.create("Dungeon.Grid.Custom.Pages", 1).read(config);
        this.gridCustomSlots = ConfigValue.forMapById("Dungeon.Grid.Custom.Slots",
            DungeonSlot::read,
            map -> {
                map.put("your_dungeon", new DungeonSlot(1, new int[]{12, 13, 21, 22}));
                map.put("another_dungeon", new DungeonSlot(1, new int[]{15, 16, 24, 25}));
            }
        ).read(config);

        this.dungeonName = ConfigValue.create("Dungeon.Name",
            DUNGEON_NAME
        ).read(config);

        this.dungeonUnlockedInfo = ConfigValue.create("Dungeon.Info.Unlocked", Lists.newList(
            DUNGEON_DESCRIPTION,
            EMPTY_IF_ABOVE,
            GRAY.wrap(WHITE.wrap("-") + " State: " + WHITE.wrap(DUNGEON_STATE)),
            GRAY.wrap(WHITE.wrap("-") + " Players: " + WHITE.wrap(DUNGEON_PLAYERS) + "/" + WHITE.wrap(DUNGEON_MAX_PLAYERS)),
            GRAY.wrap(WHITE.wrap("-") + " Enter Cost: " + WHITE.wrap(DUNGEON_ENTRANCE_PAYMENT)),
            GRAY.wrap(WHITE.wrap("-") + " Level Required: " + WHITE.wrap(DUNGEON_LEVEL_REQUIREMENT)),
            GRAY.wrap(WHITE.wrap("-") + " Cooldown: " + WHITE.wrap(COOLDOWN)),
            "",
            LIGHT_YELLOW.wrap("[LMB] " + LIGHT_GRAY.wrap("Click to") + " enter" + LIGHT_GRAY.wrap("."))
        )).read(config);

        this.dungeonLockedInfo = ConfigValue.create("Dungeon.Info.Locked", Lists.newList(
            DUNGEON_DESCRIPTION,
            EMPTY_IF_ABOVE,
            GRAY.wrap(LIGHT_RED.wrap("!") + " Upgrade your " + LIGHT_RED.wrap("/rank") + " to unlock this dungeon!")
        )).read(config);

        this.dungeonCooldownInfo = ConfigValue.create("Dungeon.Info.Cooldown", Lists.newList(
            DUNGEON_DESCRIPTION,
            EMPTY_IF_ABOVE,
            GRAY.wrap(LIGHT_RED.wrap("!") + " Dungeon is on cooldown: " + LIGHT_RED.wrap(COOLDOWN))
        )).read(config);

        this.dungeonAllBusyInfo = ConfigValue.create("Dungeon.Info.AllBusy", Lists.newList(
            DUNGEON_DESCRIPTION,
            EMPTY_IF_ABOVE,
            GRAY.wrap(LIGHT_RED.wrap("!") + " \u5f53\u524d\u5730\u7262\u623f\u95f4\u5df2\u5168\u90e8\u6ee1\u5458\u6216\u6b63\u5728\u8fdb\u884c\u4e2d\uff0c\u8bf7\u7a0d\u7b49\u7247\u523b\u3002"),
            "",
            GRAY.wrap(WHITE.wrap("-") + " Rooms: " + WHITE.wrap(BROWSE_ROOMS)),
            GRAY.wrap(WHITE.wrap("-") + " Busy: " + WHITE.wrap(BROWSE_BUSY_ROOMS)),
            GRAY.wrap(WHITE.wrap("-") + " In Game: " + WHITE.wrap(BROWSE_INGAME_ROOMS)),
            GRAY.wrap(WHITE.wrap("-") + " Full: " + WHITE.wrap(BROWSE_FULL_ROOMS)),
            GRAY.wrap(WHITE.wrap("-") + " Available: " + WHITE.wrap(BROWSE_AVAILABLE_ROOMS))
        ),
            "Shown when every grouped room is full or in INGAME state.",
            "Additional placeholders: " + BROWSE_GROUP + ", " + BROWSE_ROOMS + ", " + BROWSE_AVAILABLE_ROOMS + ",",
            BROWSE_BUSY_ROOMS + ", " + BROWSE_INGAME_ROOMS + ", " + BROWSE_FULL_ROOMS + ", " + BROWSE_SELECTED_ROOM + "."
        ).read(config);

        loader.addDefaultItem(MenuItem.buildExit(this, 31));
        loader.addDefaultItem(MenuItem.buildNextPage(this, 32).setPriority(10));
        loader.addDefaultItem(MenuItem.buildPreviousPage(this, 30).setPriority(10));
    }

    private enum GroupMode {
        ID, NAME, PREFIX
    }

    public record BrowseEntry(@NotNull String group, @NotNull List<DungeonConfig> configs) {

        public BrowseEntry {
            if (configs.isEmpty()) throw new IllegalArgumentException("Browse entry must have at least one dungeon.");
        }

        @NotNull
        public DungeonConfig representative() {
            return this.configs.get(0);
        }

        @NotNull
        public String displayName() {
            return this.representative().getName();
        }

        public int indexOf(@NotNull String dungeonId) {
            for (int index = 0; index < this.configs.size(); index++) {
                if (this.configs.get(index).getId().equalsIgnoreCase(dungeonId)) {
                    return index;
                }
            }

            return -1;
        }
    }

    private record BrowseSelection(@NotNull DungeonConfig config, boolean canJoin, boolean allBusy) {

    }

    private record BrowseCounts(int total, int available, int busy, int ingame, int full) {

        @NotNull
        public static BrowseCounts of(@NotNull Player player, @NotNull BrowseEntry entry, @NotNull DungeonBrowseMenu menu) {
            int total = entry.configs().size();
            int available = 0;
            int busy = 0;
            int ingame = 0;
            int full = 0;

            for (DungeonConfig config : entry.configs()) {
                DungeonInstance dungeon = config.getInstance();

                if (dungeon.getState() == GameState.INGAME) ingame++;
                if (menu.isFull(config)) full++;
                if (menu.isBusy(config)) busy++;
                if (menu.canBrowseJoin(player, config)) available++;
            }

            return new BrowseCounts(total, available, busy, ingame, full);
        }
    }

    private static class DungeonSlot implements Writeable {

        private final int page;
        private final int[] slots;

        public DungeonSlot(int page, int[] slots) {
            this.page = page;
            this.slots = slots;
        }

        @NotNull
        public static DungeonSlot read(@NotNull FileConfig config, @NotNull String path) {
            int page = ConfigValue.create(path + ".Page", 1).read(config);
            int[] slots = ConfigValue.create(path + ".Slots", new int[0]).read(config);

            return new DungeonSlot(page, slots);
        }

        @Override
        public void write(@NotNull FileConfig config, @NotNull String path) {
            config.set(path + ".Page", this.page);
            config.setIntArray(path + ".Slots", this.slots);
        }
    }
}
