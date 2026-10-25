package com.supabounty;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.command.*;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class SupaBounty extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private Economy economy;
    private File dataFile;
    private YamlConfiguration data;
    private final Map<UUID, PendingBounty> pending = new HashMap<>();
    private final Map<UUID, Integer> pages = new HashMap<>();
    private final Map<UUID, String> sorts = new HashMap<>();
    private final Map<UUID, UUID> guiTargets = new HashMap<>();
    private final Set<UUID> awaitingSearch = new HashSet<>();
    private final Map<UUID, String> searchFilters = new HashMap<>();
    private static final Pattern HEX = Pattern.compile("<##([A-Fa-f0-9]{6})>");

    private record PendingBounty(UUID target, double amount) {}

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!setupEconomy()) {
            getLogger().severe("Vault economy provider not found. Install Vault and an economy plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        dataFile = new File(getDataFolder(), "bounties.yml");
        if (!dataFile.exists()) {
            try { getDataFolder().mkdirs(); dataFile.createNewFile(); }
            catch (IOException ex) { getLogger().severe("Could not create bounties.yml: " + ex.getMessage()); }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
        Objects.requireNonNull(getCommand("bounty")).setExecutor(this);
        Objects.requireNonNull(getCommand("bounty")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("SupaBounty enabled.");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) return false;
        economy = provider.getProvider();
        return economy != null;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("supabounty.admin")) { send(sender, msg("no-permission")); return true; }
            reloadConfig();
            send(sender, msg("reload"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used in-game.");
            return true;
        }
        if (!player.hasPermission("supabounty.use")) { send(player, msg("no-permission")); return true; }
        if (args.length == 0) { searchFilters.remove(player.getUniqueId()); openBounties(player, 1); return true; }
        if (args[0].equalsIgnoreCase("add")) {
            if (args.length < 3) { player.sendMessage(color("&cUsage: /bounty add <player> <amount>")); return true; }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            if (!target.isOnline() && !target.hasPlayedBefore()) { send(player, msg("player-not-found")); errorSound(player); return true; }
            if (!getConfig().getBoolean("settings.allow-self-bounty", false) && target.getUniqueId().equals(player.getUniqueId())) {
                send(player, msg("self-bounty")); errorSound(player); return true;
            }
            double amount = parseAmount(args[2]);
            double min = Math.max(0.01, getConfig().getDouble("settings.minimum-bounty", 1.0));
            double max = Math.max(min, getConfig().getDouble("settings.max-bounty", 1.0E12));
            if (!Double.isFinite(amount) || amount < min || amount > max) { send(player, msg("invalid-amount")); errorSound(player); return true; }
            if (economy.getBalance(player) < amount) { send(player, msg("not-enough-money")); errorSound(player); return true; }
            openConfirmation(player, target, amount);
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!hasBounty(target.getUniqueId())) { send(player, msg("no-bounty")); errorSound(player); return true; }
        send(player, msg("bounty-view").replace("{player}", safeName(target)).replace("{amount}", money(getBounty(target.getUniqueId()))));
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> choices = new ArrayList<>(List.of("add"));
            if (sender.hasPermission("supabounty.admin")) choices.add("reload");
            Bukkit.getOnlinePlayers().forEach(p -> choices.add(p.getName()));
            return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).distinct().sorted().toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("add")) return List.of("100", "1k", "10k", "100k", "1m");
        return Collections.emptyList();
    }

    private void openConfirmation(Player player, OfflinePlayer target, double amount) {
        pending.put(player.getUniqueId(), new PendingBounty(target.getUniqueId(), amount));
        Inventory inv = Bukkit.createInventory(null, 27, color(getConfig().getString("settings.confirmation-title", "ᴄᴏɴꜰɪʀᴍ ʙᴏᴜɴᴛʏ")));
        inv.setItem(11, item(Material.RED_STAINED_GLASS_PANE, getConfig().getString("gui.cancel-name"), List.of(getConfig().getString("gui.cancel-lore", "&7Click to cancel.")), null));
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skull = (SkullMeta) head.getItemMeta();
        if (skull != null) { skull.setOwningPlayer(target); skull.setDisplayName(color("<##00F986>" + safeName(target))); skull.setLore(List.of(color(msg("confirm-prompt").replace("{amount}", money(amount)).replace("{player}", safeName(target))))); head.setItemMeta(skull); }
        inv.setItem(13, head);
        inv.setItem(15, item(Material.LIME_STAINED_GLASS_PANE, getConfig().getString("gui.confirm-name"), List.of(getConfig().getString("gui.confirm-lore", "&7Click to confirm.")), null));
        player.openInventory(inv);
    }

    private void openBounties(Player player, int page) {
        pages.put(player.getUniqueId(), Math.max(1, page));
        String sort = sorts.getOrDefault(player.getUniqueId(), getConfig().getString("settings.sort-default", "amount"));
        List<UUID> ids = getBountyIds();
        String filter = searchFilters.get(player.getUniqueId());
        if (filter != null && !filter.isBlank()) {
            String query = filter.toLowerCase(Locale.ROOT);
            ids.removeIf(id -> !safeName(Bukkit.getOfflinePlayer(id)).toLowerCase(Locale.ROOT).contains(query));
        }
        if (sort.equalsIgnoreCase("recent")) ids.sort(Comparator.comparingLong((UUID id) -> data.getLong("bounties." + id + ".created")).reversed());
        else ids.sort(Comparator.comparingDouble((UUID id) -> getBounty(id)).reversed());
        int perPage = Math.max(1, Math.min(45, getConfig().getInt("settings.entries-per-page", 45)));
        int start = (page - 1) * perPage;
        Inventory inv = Bukkit.createInventory(null, 54, color(getConfig().getString("settings.gui-title", "<##00F986>ʙᴏᴜɴᴛɪᴇꜱ") + " (Page " + page + "/" + Math.max(1, (int)Math.ceil((double)ids.size()/perPage)) + ")"));
        if (ids.isEmpty()) inv.setItem(22, item(Material.PAPER, getConfig().getString("gui.empty-name", "&7No bounties yet"), List.of(filter == null ? "&7No active bounties to display." : "&7No bounty names match: " + filter), null));
        for (int i = start; i < Math.min(start + perPage, ids.size()); i++) {
            UUID id = ids.get(i);
            OfflinePlayer target = Bukkit.getOfflinePlayer(id);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(target);
                meta.setDisplayName(color("<##00F986>" + safeName(target)));
                meta.setLore(List.of(color(getConfig().getString("gui.bounty-lore", "&fBounty: &7{amount}").replace("{amount}", money(getBounty(id))))));
                head.setItemMeta(meta);
            }
            inv.setItem(i - start, head);
            guiTargets.put(id, id);
        }
        inv.setItem(45, item(Material.OAK_SIGN, "&eSearch", List.of("&7Click, then type part of a player name in chat.", "&7Current search: " + (filter == null ? "none" : filter), "&7Type cancel in chat to cancel."), null));
        inv.setItem(46, item(Material.HOPPER, color(sort.equalsIgnoreCase("recent") ? getConfig().getString("gui.sort-recent-name") : getConfig().getString("gui.sort-amount-name")), List.of("&7Click to change sorting."), null));
        inv.setItem(48, item(Material.ARROW, getConfig().getString("gui.back-name"), List.of("&7Previous page"), null));
        inv.setItem(49, item(Material.SKELETON_SKULL, getConfig().getString("gui.refresh-name"), List.of("&7Click to refresh"), null));
        inv.setItem(50, item(Material.ARROW, getConfig().getString("gui.next-name"), List.of("&7Next page"), null));
        player.openInventory(inv);
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = color(getConfig().getString("settings.confirmation-title", "ᴄᴏɴꜰɪʀᴍ ʙᴏᴜɴᴛʏ"));
        if (event.getView().getTitle().equals(title)) {
            event.setCancelled(true);
            PendingBounty order = pending.get(player.getUniqueId());
            if (order == null) { player.closeInventory(); return; }
            if (event.getRawSlot() == 11) {
                pending.remove(player.getUniqueId()); player.closeInventory(); send(player, msg("cancelled")); return;
            }
            if (event.getRawSlot() == 15) {
                pending.remove(player.getUniqueId());
                OfflinePlayer target = Bukkit.getOfflinePlayer(order.target());
                if (economy.getBalance(player) < order.amount()) { player.closeInventory(); send(player, msg("not-enough-money")); errorSound(player); return; }
                if (!economy.withdrawPlayer(player, order.amount()).transactionSuccess()) { player.closeInventory(); send(player, msg("not-enough-money")); return; }
                String path = "bounties." + order.target();
                data.set(path + ".amount", getBounty(order.target()) + order.amount());
                data.set(path + ".name", safeName(target));
                if (!data.contains(path + ".created")) data.set(path + ".created", System.currentTimeMillis());
                saveData();
                player.closeInventory();
                send(player, msg("bounty-added").replace("{amount}", money(order.amount())).replace("{player}", safeName(target)));
                successSound(player);
            }
            return;
        }
        if (event.getView().getTitle().startsWith(color(getConfig().getString("settings.gui-title", "<##00F986>ʙᴏᴜɴᴛɪᴇꜱ")))) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot == 45) {
                awaitingSearch.add(player.getUniqueId());
                player.closeInventory();
                send(player, "&7Type a player name or part of a name in chat to search bounties. Type &ccancel &7to cancel.");
            }
            else if (slot == 46) { sorts.put(player.getUniqueId(), sorts.getOrDefault(player.getUniqueId(), getConfig().getString("settings.sort-default", "amount")).equalsIgnoreCase("recent") ? "amount" : "recent"); openBounties(player, 1); }
            else if (slot == 49) openBounties(player, pages.getOrDefault(player.getUniqueId(), 1));
            else if (slot == 48) openBounties(player, Math.max(1, pages.getOrDefault(player.getUniqueId(), 1) - 1));
            else if (slot == 50) {
                int next = pages.getOrDefault(player.getUniqueId(), 1) + 1;
                int perPage = Math.max(1, Math.min(45, getConfig().getInt("settings.entries-per-page", 45)));
                if ((next - 1) * perPage < getVisibleBountyIds(player).size()) openBounties(player, next);
            }
        }
    }

    @EventHandler public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && pending.containsKey(player.getUniqueId())
                && event.getView().getTitle().equals(color(getConfig().getString("settings.confirmation-title", "ᴄᴏɴꜰɪʀᴍ ʙᴏᴜɴᴛʏ")))) pending.remove(player.getUniqueId());
    }

    @EventHandler public void onDeath(PlayerDeathEvent event) {
        if (!getConfig().getBoolean("settings.pay-killer-on-death", true)) return;
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;
        double amount = getBounty(victim.getUniqueId());
        if (amount <= 0) return;
        economy.depositPlayer(killer, amount);
        data.set("bounties." + victim.getUniqueId(), null);
        saveData();
        Bukkit.getScheduler().runTask(this, () -> send(killer, msg("bounty-paid").replace("{amount}", money(amount)).replace("{player}", victim.getName())));
    }

    @EventHandler public void onChatSearch(AsyncPlayerChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (!awaitingSearch.remove(playerId)) return;
        event.setCancelled(true);
        String query = event.getMessage().trim();
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) return;
            if (query.equalsIgnoreCase("cancel")) {
                send(player, "&7Bounty search cancelled.");
                openBounties(player, pages.getOrDefault(playerId, 1));
                return;
            }
            if (query.isBlank()) {
                searchFilters.remove(playerId);
                send(player, "&7Search cleared.");
            } else {
                searchFilters.put(playerId, query);
                send(player, "&aSearching bounties for: &f" + query);
            }
            openBounties(player, 1);
        });
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        // UUID-keyed storage means players retain bounties when they change names.
    }

    private List<UUID> getVisibleBountyIds(Player player) {
        List<UUID> ids = getBountyIds();
        String filter = searchFilters.get(player.getUniqueId());
        if (filter != null && !filter.isBlank()) {
            String query = filter.toLowerCase(Locale.ROOT);
            ids.removeIf(id -> !safeName(Bukkit.getOfflinePlayer(id)).toLowerCase(Locale.ROOT).contains(query));
        }
        return ids;
    }

    private List<UUID> getBountyIds() {
        ConfigurationSection section = data.getConfigurationSection("bounties");
        if (section == null) return new ArrayList<>();
        return section.getKeys(false).stream().map(key -> { try { return UUID.fromString(key); } catch (IllegalArgumentException ex) { return null; } })
                .filter(Objects::nonNull).filter(id -> getBounty(id) > 0).collect(Collectors.toCollection(ArrayList::new));
    }
    private boolean hasBounty(UUID id) { return getBounty(id) > 0; }
    private double getBounty(UUID id) { return Math.max(0, data.getDouble("bounties." + id + ".amount", 0)); }
    private String safeName(OfflinePlayer player) { return player.getName() == null ? "Unknown" : player.getName(); }
    private void saveData() { try { data.save(dataFile); } catch (IOException ex) { getLogger().severe("Could not save bounties.yml: " + ex.getMessage()); } }
    private String msg(String path) { return getConfig().getString("messages." + path, "&cMissing message: " + path); }
    private void send(CommandSender sender, String message) { sender.sendMessage(color(message)); }
    private void successSound(Player player) { playConfiguredSound(player, "settings.sounds.success", "ENTITY_EXPERIENCE_ORB_PICKUP"); }
    private void errorSound(Player player) { playConfiguredSound(player, "settings.sounds.error", "ENTITY_VILLAGER_NO"); }
    private void playConfiguredSound(Player player, String path, String fallback) {
        try { player.playSound(player.getLocation(), Sound.valueOf(getConfig().getString(path, fallback).toUpperCase(Locale.ROOT)), 1f, 1f); }
        catch (IllegalArgumentException ex) { player.playSound(player.getLocation(), Sound.valueOf(fallback), 1f, 1f); }
    }
    private ItemStack item(Material material, String name, List<String> lore, OfflinePlayer owner) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name == null ? "" : name));
            meta.setLore(lore.stream().map(this::color).toList());
            if (meta instanceof SkullMeta skull && owner != null) skull.setOwningPlayer(owner);
            stack.setItemMeta(meta);
        }
        return stack;
    }
    private String money(double amount) {
        DecimalFormat format = new DecimalFormat("#,##0.##");
        return color(getConfig().getString("settings.currency-symbol", "$")) + format.format(amount);
    }
    private double parseAmount(String input) {
        String value = input.trim().replace(",", "").replace("_", "");
        double multiplier = 1;
        if (!value.isEmpty()) {
            char last = Character.toLowerCase(value.charAt(value.length() - 1));
            multiplier = switch (last) { case 'k' -> 1e3; case 'm' -> 1e6; case 'b' -> 1e9; case 't' -> 1e12; default -> 1; };
            if (multiplier != 1) value = value.substring(0, value.length() - 1);
        }
        try { return Double.parseDouble(value) * multiplier; } catch (NumberFormatException ex) { return Double.NaN; }
    }
    private String color(String input) {
        if (input == null) return "";
        Matcher matcher = HEX.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) replacement.append('§').append(c);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(result);
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', result.toString());
    }
}