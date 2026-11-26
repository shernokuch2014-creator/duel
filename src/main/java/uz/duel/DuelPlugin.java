package uz.duel;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public class DuelPlugin extends JavaPlugin implements Listener, CommandExecutor {

    /* ===================== REJIMLAR ===================== */
    enum Mode {
        SWORD("Qilich", "Olmos qilich + olmos zirh"),
        MACE("Mace", "Netherite zirh, mace, wind charge, sehrlangan olma, ender pearl"),
        SPEAR("Spear", "Mace rejimidagi hamma narsa + spear");

        final String title;
        final String desc;

        Mode(String title, String desc) {
            this.title = title;
            this.desc = desc;
        }
    }

    /* ===================== MA'LUMOTLAR ===================== */
    static class MenuHolder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    record Request(UUID from, UUID to, Mode mode) {}

    record State(ItemStack[] inv, Location loc, GameMode gm, double health, int food, int level, float exp) {}

    static class Duel {
        World world;
        UUID a, b;
        Mode mode;
        boolean ended;
    }

    private static final String WORLD_PREFIX = "duel_";

    private final Map<UUID, Mode> awaiting = new HashMap<>();   // nik yozishni kutayotganlar
    private final Map<UUID, Request> pending = new HashMap<>(); // key = qabul qiluvchi
    private final Map<UUID, Duel> inDuel = new HashMap<>();
    private final Map<UUID, State> saved = new HashMap<>();

    /* ===================== ISHGA TUSHISH ===================== */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        new File(getDataFolder(), "template").mkdirs();
        getCommand("duel").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);

        // Oldingi crash'dan qolgan duel olamlarini tozalash
        File[] files = Bukkit.getWorldContainer().listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory() && f.getName().startsWith(WORLD_PREFIX)) {
                    deleteDir(f);
                }
            }
        }
    }

    @Override
    public void onDisable() {
        Set<Duel> duels = new HashSet<>(inDuel.values());
        for (UUID id : new ArrayList<>(saved.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) restore(p);
        }
        for (Duel d : duels) {
            destroyWorld(d.world);
        }
        inDuel.clear();
    }

    /* ===================== /duel BUYRUG'I ===================== */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Bu buyruq faqat o'yinchilar uchun.");
            return true;
        }
        if (inDuel.containsKey(p.getUniqueId())) {
            msg(p, "Duel paytida buyruq ishlamaydi!", NamedTextColor.RED);
            return true;
        }

        if (args.length == 0) {
            openMenu(p);
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("accept")) {
            Request r = pending.get(p.getUniqueId());
            Player challenger = Bukkit.getPlayerExact(args[1]);
            if (r == null || challenger == null || !r.from().equals(challenger.getUniqueId())) {
                msg(p, "So'rov topilmadi yoki muddati tugagan.", NamedTextColor.RED);
                return true;
            }
            pending.remove(p.getUniqueId());
            if (inDuel.containsKey(challenger.getUniqueId())) {
                msg(p, "Bu o'yinchi hozir boshqa duelda.", NamedTextColor.RED);
                return true;
            }
            startDuel(challenger, p, r.mode());
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("deny")) {
            Request r = pending.get(p.getUniqueId());
            Player challenger = Bukkit.getPlayerExact(args[1]);
            if (r == null || challenger == null || !r.from().equals(challenger.getUniqueId())) {
                msg(p, "So'rov topilmadi yoki muddati tugagan.", NamedTextColor.RED);
                return true;
            }
            pending.remove(p.getUniqueId());
            msg(p, "Duel so'rovini rad etdingiz.", NamedTextColor.YELLOW);
            msg(challenger, p.getName() + " duel so'rovini rad etdi.", NamedTextColor.RED);
            return true;
        }

        openMenu(p);
        return true;
    }

    /* ===================== MENYU ===================== */
    private void openMenu(Player p) {
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Duel rejimini tanlang"));
        holder.inv = inv;

        ItemStack filler = named(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), " ", null);
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);

        Material spearMat = spearMaterial();
        inv.setItem(11, named(new ItemStack(Material.DIAMOND_SWORD), Mode.SWORD.title, Mode.SWORD.desc));
        inv.setItem(13, named(new ItemStack(Material.MACE), Mode.MACE.title, Mode.MACE.desc));
        inv.setItem(15, named(new ItemStack(spearMat), Mode.SPEAR.title, Mode.SPEAR.desc));

        p.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;

        Mode mode = switch (e.getRawSlot()) {
            case 11 -> Mode.SWORD;
            case 13 -> Mode.MACE;
            case 15 -> Mode.SPEAR;
            default -> null;
        };
        if (mode == null) return;

        p.closeInventory();
        awaiting.put(p.getUniqueId(), mode);
        msg(p, "Rejim: " + mode.title + ". Endi duel qilmoqchi bo'lgan o'yinchi nikini chatga yozing.",
                NamedTextColor.GREEN);
        msg(p, "(Bekor qilish uchun: cancel)", NamedTextColor.GRAY);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }

    /* ===================== NIK KIRITISH (CHAT) ===================== */
    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Mode mode = awaiting.get(p.getUniqueId());
        if (mode == null) return;

        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();

        Bukkit.getScheduler().runTask(this, () -> {
            awaiting.remove(p.getUniqueId());
            if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("bekor")) {
                msg(p, "Bekor qilindi.", NamedTextColor.YELLOW);
                return;
            }
            Player target = Bukkit.getPlayerExact(text);
            if (target == null || !target.isOnline()) {
                msg(p, "Bunday o'yinchi topilmadi: " + text, NamedTextColor.RED);
                return;
            }
            if (target.equals(p)) {
                msg(p, "O'zingiz bilan duel qila olmaysiz!", NamedTextColor.RED);
                return;
            }
            if (inDuel.containsKey(target.getUniqueId()) || inDuel.containsKey(p.getUniqueId())) {
                msg(p, "O'yinchi hozir duelda.", NamedTextColor.RED);
                return;
            }
            sendChallenge(p, target, mode);
        });
    }

    private void sendChallenge(Player from, Player to, Mode mode) {
        Request req = new Request(from.getUniqueId(), to.getUniqueId(), mode);
        pending.put(to.getUniqueId(), req);

        msg(from, to.getName() + " ga duel so'rovi yuborildi (" + mode.title + ").", NamedTextColor.GREEN);

        Component accept = Component.text("[QABUL QILISH]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/duel accept " + from.getName()));
        Component deny = Component.text("[RAD ETISH]", NamedTextColor.RED, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/duel deny " + from.getName()));

        to.sendMessage(Component.empty());
        to.sendMessage(Component.text(from.getName() + " sizni duelga chaqirdi! Rejim: " + mode.title,
                NamedTextColor.GOLD));
        to.sendMessage(accept.append(Component.text("   ")).append(deny));
        to.sendMessage(Component.empty());

        // 30 soniyadan keyin so'rov bekor bo'ladi
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (pending.get(to.getUniqueId()) == req) {
                pending.remove(to.getUniqueId());
                msg(from, "Duel so'rovi muddati tugadi.", NamedTextColor.GRAY);
                msg(to, "Duel so'rovi muddati tugadi.", NamedTextColor.GRAY);
            }
        }, 20L * 30);
    }

    /* ===================== DUELNI BOSHLASH ===================== */
    private void startDuel(Player a, Player b, Mode mode) {
        String name = WORLD_PREFIX + System.currentTimeMillis();
        // plugins/Duel/template/ ichida level.dat bo'lsa, tayyor map nusxalanadi
        File template = new File(getDataFolder(), "template");
        boolean custom = new File(template, "level.dat").exists();
        World w;
        if (custom) {
            File dest = new File(Bukkit.getWorldContainer(), name);
            copyDir(template.toPath(), dest.toPath());
            new File(dest, "uid.dat").delete();
            new File(dest, "session.lock").delete();
            w = new WorldCreator(name).createWorld();
        } else {
            w = new WorldCreator(name)
                    .environment(World.Environment.NORMAL)
                    .generator(new ChunkGenerator() {}) // bo'sh (void) olam
                    .generateStructures(false)
                    .createWorld();
        }

        if (w == null) {
            msg(a, "Duel olamini yaratib bo'lmadi!", NamedTextColor.RED);
            msg(b, "Duel olamini yaratib bo'lmadi!", NamedTextColor.RED);
            return;
        }
        w.setTime(6000);
        w.setStorm(false);

        if (!custom) {
            w.setSpawnLocation(0, 65, 0);
            buildArena(w);
        }

        Duel d = new Duel();
        d.world = w;
        d.a = a.getUniqueId();
        d.b = b.getUniqueId();
        d.mode = mode;
        inDuel.put(d.a, d);
        inDuel.put(d.b, d);

        saveState(a);
        saveState(b);

        Location la = custom ? spawn(w, "spawn-a") : new Location(w, -15.5, 65, 0.5, -90f, 0f);
        Location lb = custom ? spawn(w, "spawn-b") : new Location(w, 15.5, 65, 0.5, 90f, 0f);
        prepare(a, la, mode);
        prepare(b, lb, mode);

        Title t = Title.title(Component.text("DUEL!", NamedTextColor.RED),
                Component.text(mode.title + " rejimi", NamedTextColor.GOLD));
        a.showTitle(t);
        b.showTitle(t);
    }

    /** config.yml dagi spawn-a / spawn-b koordinatalarini o'qiydi. */
    private Location spawn(World w, String key) {
        double x = getConfig().getDouble(key + ".x");
        double y = getConfig().getDouble(key + ".y");
        double z = getConfig().getDouble(key + ".z");
        float yaw = (float) getConfig().getDouble(key + ".yaw");
        return new Location(w, x, y, z, yaw, 0f);
    }

    private void copyDir(Path src, Path dst) {
        try (Stream<Path> s = Files.walk(src)) {
            s.forEach(p -> {
                try {
                    Path target = dst.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(target);
                    else Files.copy(p, target);
                } catch (IOException ex) {
                    getLogger().warning("Nusxalashda xato: " + ex.getMessage());
                }
            });
        } catch (IOException ex) {
            getLogger().warning("Template topilmadi: " + ex.getMessage());
        }
    }

    private void saveState(Player p) {
        saved.put(p.getUniqueId(), new State(
                p.getInventory().getContents().clone(),
                p.getLocation(),
                p.getGameMode(),
                p.getHealth(),
                p.getFoodLevel(),
                p.getLevel(),
                p.getExp()));
    }

    private void restore(Player p) {
        State s = saved.remove(p.getUniqueId());
        if (s == null) return;
        p.getInventory().clear();
        p.getInventory().setContents(s.inv());
        p.getActivePotionEffects().forEach(ef -> p.removePotionEffect(ef.getType()));
        p.setGameMode(s.gm());
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setFoodLevel(s.food());
        p.setLevel(s.level());
        p.setExp(s.exp());
        p.teleport(s.loc());
        p.setHealth(Math.max(1.0, Math.min(20.0, s.health())));
    }

    private void prepare(Player p, Location loc, Mode mode) {
        p.getInventory().clear();
        p.getActivePotionEffects().forEach(ef -> p.removePotionEffect(ef.getType()));
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.teleport(loc);
        giveKit(p, mode);
    }

    /* ===================== KITLAR ===================== */
    private void giveKit(Player p, Mode mode) {
        PlayerInventory inv = p.getInventory();
        switch (mode) {
            case SWORD -> {
                setArmor(p, Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE,
                        Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS);
                inv.addItem(ench(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 3));
            }
            case MACE, SPEAR -> {
                setArmor(p, Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
                        Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS);
                inv.addItem(ench(Material.MACE, Enchantment.DENSITY, 5));
                if (mode == Mode.SPEAR) {
                    Material spear = spearMaterial();
                    inv.addItem(new ItemStack(spear));
                }
                inv.addItem(new ItemStack(Material.WIND_CHARGE, 64));
                inv.addItem(new ItemStack(Material.WIND_CHARGE, 64));      // 2 stak
                inv.addItem(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 16));
                inv.addItem(new ItemStack(Material.ENDER_PEARL, 16));
            }
        }
    }

    private void setArmor(Player p, Material h, Material c, Material l, Material b) {
        PlayerInventory inv = p.getInventory();
        inv.setHelmet(ench(h, Enchantment.PROTECTION, 4));
        inv.setChestplate(ench(c, Enchantment.PROTECTION, 4));
        inv.setLeggings(ench(l, Enchantment.PROTECTION, 4));
        inv.setBoots(ench(b, Enchantment.PROTECTION, 4));
    }

    private ItemStack ench(Material m, Enchantment en, int lvl) {
        ItemStack i = new ItemStack(m);
        i.addUnsafeEnchantment(en, lvl);
        i.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        return i;
    }

    /** Spear 1.21.11 da qo'shilgan; topilmasa trident beriladi. */
    private Material spearMaterial() {
        Material m = Material.matchMaterial("NETHERITE_SPEAR");
        if (m == null) m = Material.matchMaterial("DIAMOND_SPEAR");
        return m != null ? m : Material.TRIDENT;
    }

    /* ===================== ARENA: ORK KOLIZEYSI ===================== */
    /**
     * Uchib yurgan qumli orol ustida dumaloq kolizey:
     *  - radius 22 qum maydon, markazda qizil/qora belgi
     *  - atrofida zinapoyali tomoshabin devori, qizil ustunlar bilan
     *  - maydon ichida 4 ta to'siq ustun
     *  - 4 tomonda ikki qavatli qizil pagoda minoralar
     *  - ko'rinmas barrier devor va shift (wind charge bilan ham chiqib bo'lmaydi)
     */
    private void buildArena(World w) {
        final int y = 64;
        final int top = 130;
        final int IN = 22, OUT = 26, ISLAND = 38;

        // 1) Uchib yurgan orol + maydon poli
        for (int x = -ISLAND; x <= ISLAND; x++) {
            for (int z = -ISLAND; z <= ISLAND; z++) {
                double d = Math.hypot(x, z);
                if (d > ISLAND) continue;
                int depth = (int) Math.round(9 * (1 - d / ISLAND));
                for (int k = 1; k <= depth; k++) {
                    Material m = (k % 3 == 0) ? Material.TERRACOTTA
                            : (k % 2 == 0 ? Material.RED_SANDSTONE : Material.SANDSTONE);
                    set(w, x, y - k, z, m);
                }
                Material surface;
                if (d < IN) surface = arenaFloor(x, z, d);
                else surface = (((x * 7 + z * 13) & 7) == 0) ? Material.CUT_SANDSTONE : Material.SMOOTH_SANDSTONE;
                set(w, x, y, z, surface);
            }
        }

        // 2) Zinapoyali devor (tomoshabin o'rindiqlari)
        for (int x = -OUT; x <= OUT; x++) {
            for (int z = -OUT; z <= OUT; z++) {
                double d = Math.hypot(x, z);
                if (d < IN || d >= OUT) continue;
                int step = (int) Math.floor(d) - IN;      // 0..3
                int h = 4 + 2 * step;                       // 4, 6, 8, 10
                double deg = (Math.toDegrees(Math.atan2(z, x)) + 360) % 360;
                boolean pillar = step == 0 && (deg % 30) < 4;

                if (pillar) {
                    for (int k = 1; k <= 12; k++) set(w, x, y + k, z, Material.RED_TERRACOTTA);
                    set(w, x, y + 13, z, Material.SHROOMLIGHT);
                    continue;
                }
                for (int k = 1; k <= h; k++) {
                    Material m = Material.DEEPSLATE_BRICKS;
                    if (step == 0 && k == 3) m = Material.RED_NETHER_BRICKS;
                    if (k == h) m = Material.POLISHED_BLACKSTONE;
                    set(w, x, y + k, z, m);
                }
                if (step == 3 && ((x + z) & 1) == 0) {
                    set(w, x, y + h + 1, z, Material.DEEPSLATE_BRICKS); // tishli parapet
                }
            }
        }

        // 3) Maydon ichidagi to'siq ustunlar
        int[][] cover = {{-11, -11}, {10, -11}, {-11, 10}, {10, 10}};
        for (int[] c : cover) {
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    for (int k = 1; k <= 5; k++) set(w, c[0] + dx, y + k, c[1] + dz, Material.DEEPSLATE_BRICKS);
                    set(w, c[0] + dx, y + 6, c[1] + dz, Material.RED_NETHER_BRICKS);
                }
            }
            set(w, c[0], y + 7, c[1], Material.LANTERN);
        }

        // 4) To'rtta pagoda minora
        int[][] towers = {{30, 0}, {-30, 0}, {0, 30}, {0, -30}};
        for (int[] t : towers) tower(w, t[0], t[1]);

        // 5) Ko'rinmas devor va shift (faqat bo'sh joylarga)
        for (int x = -24; x <= 24; x++) {
            for (int z = -24; z <= 24; z++) {
                double d = Math.hypot(x, z);
                if (d >= 23.5) continue;
                if (d >= IN) {
                    for (int yy = y + 1; yy < top; yy++) {
                        if (w.getBlockAt(x, yy, z).getType().isAir()) set(w, x, yy, z, Material.BARRIER);
                    }
                }
                set(w, x, top, z, Material.BARRIER);
            }
        }
    }

    private Material arenaFloor(int x, int z, double d) {
        if (d < 4) return Material.POLISHED_BLACKSTONE_BRICKS;
        if (d < 5) return Material.RED_NETHER_BRICKS;
        if (d < 7) return Material.SMOOTH_RED_SANDSTONE;
        int ring = (int) Math.floor(d);
        if (ring == 14 || ring == 21) return Material.RED_SANDSTONE;
        return ((x + z) & 1) == 0 ? Material.SMOOTH_SANDSTONE : Material.SANDSTONE;
    }

    /** Ikki qavatli qizil pagoda. */
    private void tower(World w, int cx, int cz) {
        final int y = 64;
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 4; dz++)
                set(w, cx + dx, y, cz + dz, Material.POLISHED_BLACKSTONE_BRICKS);

        // 1-qavat gavdasi (5x5)
        for (int k = y + 1; k <= y + 8; k++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Material m;
                    boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                    boolean mid = (Math.abs(dx) == 2 && dz == 0) || (Math.abs(dz) == 2 && dx == 0);
                    if (corner) m = Material.DARK_OAK_LOG;
                    else if (k == y + 4) m = Material.DARK_OAK_PLANKS;
                    else if (mid && (k == y + 6 || k == y + 7)) m = Material.BLACK_STAINED_GLASS;
                    else m = Material.RED_TERRACOTTA;
                    set(w, cx + dx, k, cz + dz, m);
                }
            }
        }
        // 1-tom
        roofLayer(w, cx, cz, y + 9, 4, Material.DARK_OAK_PLANKS);
        roofLayer(w, cx, cz, y + 10, 3, Material.RED_NETHER_BRICKS);
        roofLayer(w, cx, cz, y + 11, 2, Material.RED_NETHER_BRICKS);
        roofLayer(w, cx, cz, y + 12, 1, Material.RED_NETHER_BRICKS);

        // 2-qavat (3x3)
        for (int k = y + 13; k <= y + 15; k++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean corner = Math.abs(dx) == 1 && Math.abs(dz) == 1;
                    set(w, cx + dx, k, cz + dz, corner ? Material.DARK_OAK_LOG : Material.RED_TERRACOTTA);
                }
            }
        }
        // 2-tom
        roofLayer(w, cx, cz, y + 16, 3, Material.DARK_OAK_PLANKS);
        roofLayer(w, cx, cz, y + 17, 2, Material.RED_NETHER_BRICKS);
        roofLayer(w, cx, cz, y + 18, 1, Material.RED_NETHER_BRICKS);
        roofLayer(w, cx, cz, y + 19, 0, Material.RED_NETHER_BRICKS);

        // Shpil
        for (int k = y + 20; k <= y + 23; k++) set(w, cx, k, cz, Material.DARK_OAK_FENCE);
        set(w, cx, y + 24, cz, Material.SHROOMLIGHT);
    }

    private void roofLayer(World w, int cx, int cz, int yy, int r, Material m) {
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                set(w, cx + dx, yy, cz + dz, m);
    }

    private void set(World w, int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }

    /* ===================== DUEL TUGASHI ===================== */
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getPlayer();
        Duel d = inDuel.get(dead.getUniqueId());
        if (d == null || d.ended) return;

        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.getDrops().clear();
        e.setDroppedExp(0);
        e.deathMessage(null);

        Player winner = opponent(d, dead);
        finish(d, winner, dead);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        awaiting.remove(p.getUniqueId());
        pending.remove(p.getUniqueId());

        Duel d = inDuel.get(p.getUniqueId());
        if (d == null) return;

        restore(p);
        inDuel.remove(p.getUniqueId());

        if (!d.ended) {
            Player winner = opponent(d, p);
            finish(d, winner, null);
        }
    }

    private Player opponent(Duel d, Player p) {
        UUID other = p.getUniqueId().equals(d.a) ? d.b : d.a;
        return Bukkit.getPlayer(other);
    }

    private void finish(Duel d, Player winner, Player loser) {
        d.ended = true;

        if (winner != null) {
            winner.showTitle(Title.title(Component.text("G'ALABA!", NamedTextColor.GREEN),
                    Component.text("Duelda yutdingiz", NamedTextColor.GOLD)));
        }
        if (loser != null) {
            loser.showTitle(Title.title(Component.text("MAG'LUBIYAT", NamedTextColor.RED),
                    Component.empty()));
        }

        // Yutqazgan: respawn -> asl holatga qaytarish
        if (loser != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!loser.isOnline()) return;
                if (loser.isDead()) loser.spigot().respawn();
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (!loser.isOnline()) return;
                    restore(loser);
                    inDuel.remove(loser.getUniqueId());
                }, 3L);
            }, 2L);
        }

        // G'olib: 4 soniyadan keyin qaytariladi
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (winner != null && winner.isOnline()) {
                restore(winner);
            }
            if (winner != null) inDuel.remove(winner.getUniqueId());
            Bukkit.getScheduler().runTaskLater(this, () -> destroyWorld(d.world), 10L);
        }, 80L);
    }

    private void destroyWorld(World w) {
        if (w == null) return;
        World main = Bukkit.getWorlds().get(0);
        for (Player p : new ArrayList<>(w.getPlayers())) {
            p.teleport(main.getSpawnLocation());
        }
        File folder = w.getWorldFolder();
        if (Bukkit.unloadWorld(w, false)) {
            deleteDir(folder);
        }
    }

    private void deleteDir(File dir) {
        Path root = dir.toPath();
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /* ===================== DUEL PAYTIDA CHEKLOVLAR ===================== */
    /** Duel paytida HECH QANDAY buyruq ishlamaydi. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (inDuel.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            msg(e.getPlayer(), "Duel paytida buyruqlar ishlamaydi!", NamedTextColor.RED);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (inDuel.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (inDuel.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getLocation().getWorld().getName().startsWith(WORLD_PREFIX)) e.setCancelled(true);
    }

    /* ===================== YORDAMCHILAR ===================== */
    private void msg(Player p, String text, TextColor color) {
        p.sendMessage(Component.text("[Duel] ", NamedTextColor.DARK_GRAY)
                .append(Component.text(text, color)));
    }

    private ItemStack named(ItemStack item, String name, String lore) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        if (lore != null) {
            List<Component> l = new ArrayList<>();
            l.add(Component.text(lore, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            meta.lore(l);
        }
        item.setItemMeta(meta);
        return item;
    }
}
