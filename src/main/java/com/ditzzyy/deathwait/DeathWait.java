package com.ditzzyy.deathwait;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DeathWait extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private NamespacedKey untilKey, returnKey;
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureVoidWorld();
        untilKey = new NamespacedKey(this, "wait_until");
        returnKey = new NamespacedKey(this, "return_loc");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("deathwait").setExecutor(this);
        getCommand("deathwait").setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        tasks.values().forEach(BukkitTask::cancel);
        tasks.clear();
    }

    // ---------- void world ----------
    private void ensureVoidWorld() {
        var c = getConfig();
        if (!c.getBoolean("create-void-world", true)) return;
        String name = c.getString("wait-location.world", "deathwait_void");
        if (Bukkit.getWorld(name) != null) return;
        World w = Bukkit.createWorld(new WorldCreator(name).generator(new ChunkGenerator() {}).generateStructures(false));
        if (w != null) {
            w.setDifficulty(Difficulty.PEACEFUL);
            getLogger().info("World void '" + name + "' dibuat.");
        }
    }

    // ---------- helpers ----------
    private Component msg(String key, String s) {
        String raw = getConfig().getString("messages." + key, key).replace("%s", s);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
    }

    private boolean isWaiting(Player p) {
        return p.getPersistentDataContainer().has(untilKey, PersistentDataType.LONG);
    }

    private Location waitLocation() {
        var c = getConfig();
        World w = Bukkit.getWorld(c.getString("wait-location.world", "deathwait_void"));
        if (w == null) return null;
        Location loc = new Location(w, c.getDouble("wait-location.x"), c.getDouble("wait-location.y"),
                c.getDouble("wait-location.z"));
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                Block b = w.getBlockAt(loc.getBlockX() + dx, loc.getBlockY() - 1, loc.getBlockZ() + dz);
                if (b.getType().isAir()) b.setType(Material.OBSIDIAN);
            }
        return loc;
    }

    private String ser(Location l) {
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
    }

    private Location deser(String s) {
        try {
            String[] a = s.split(";");
            World w = Bukkit.getWorld(a[0]);
            if (w == null) return null;
            return new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                    Float.parseFloat(a[4]), Float.parseFloat(a[5]));
        } catch (Exception e) {
            return null;
        }
    }

    private void cancelTask(UUID id) {
        BukkitTask t = tasks.remove(id);
        if (t != null) t.cancel();
    }

    // ---------- efek ----------
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!getConfig().getBoolean("effects.lightning", true)) return;
        Location l = e.getEntity().getLocation();
        l.getWorld().strikeLightningEffect(l);
    }

    private void playSpawnEffect(Player p) {
        if (!p.isOnline()) return;
        Location l = p.getLocation();
        if (getConfig().getBoolean("effects.spawn-sound", true))
            l.getWorld().playSound(l, Sound.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 1f, 1f);
        if (getConfig().getBoolean("effects.spawn-particles", true))
            l.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, l.clone().add(0, 1, 0), 80, 0.4, 0.7, 0.4, 0.4);
    }

    // ---------- core ----------
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Location wait = waitLocation();
        if (wait == null) {
            getLogger().warning("World tempat menunggu tidak ditemukan! Cek config.yml");
            return;
        }
        long until = System.currentTimeMillis() + getConfig().getInt("wait-seconds", 60) * 1000L;
        var pdc = p.getPersistentDataContainer();
        pdc.set(untilKey, PersistentDataType.LONG, until);
        pdc.set(returnKey, PersistentDataType.STRING, ser(e.getRespawnLocation()));
        e.setRespawnLocation(wait);
        Bukkit.getScheduler().runTask(this, () -> { startWait(p); playSpawnEffect(p); });
    }

    private void startWait(Player p) {
        UUID id = p.getUniqueId();
        cancelTask(id);
        try {
            p.setGameMode(GameMode.valueOf(getConfig().getString("wait-gamemode", "ADVENTURE")));
        } catch (IllegalArgumentException ex) {
            p.setGameMode(GameMode.ADVENTURE);
        }
        p.setInvulnerable(true);
        p.setAllowFlight(true);
        p.setFlying(true);
        p.setFlySpeed((float) Math.min(1.0, getConfig().getDouble("fly-speed", 0.1)));
        tasks.put(id, Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!p.isOnline()) { cancelTask(id); return; }
            Long until = p.getPersistentDataContainer().get(untilKey, PersistentDataType.LONG);
            if (until == null) { cancelTask(id); return; }
            if (!p.getAllowFlight() || !p.isFlying()) { p.setAllowFlight(true); p.setFlying(true); }
            if (p.getLocation().getY() < p.getWorld().getMinHeight() - 5) {
                Location w = waitLocation();
                if (w != null) p.teleport(w);
            }
            long left = (until - System.currentTimeMillis() + 999) / 1000;
            if (left <= 0) { finish(p); return; }
            p.sendActionBar(msg("waiting", String.valueOf(left)));
        }, 0L, 20L));
    }

    private void finish(Player p) {
        cancelTask(p.getUniqueId());
        var pdc = p.getPersistentDataContainer();
        String raw = pdc.get(returnKey, PersistentDataType.STRING);
        Location back = raw == null ? null : deser(raw);
        if (back == null) back = Bukkit.getWorlds().get(0).getSpawnLocation();
        pdc.remove(untilKey);
        pdc.remove(returnKey);
        p.setInvulnerable(false);
        try {
            p.setGameMode(GameMode.valueOf(getConfig().getString("return-gamemode", "SURVIVAL")));
        } catch (IllegalArgumentException ex) {
            p.setGameMode(GameMode.SURVIVAL);
        }
        GameMode gm = p.getGameMode();
        if (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
        p.setFlySpeed(0.1f);
        p.teleportAsync(back).thenAccept(ok -> {
            if (getConfig().getBoolean("effects.effect-on-return", true)) playSpawnEffect(p);
        });
        p.sendActionBar(msg("returned", ""));
    }

    // ---------- terbang & void ----------
    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent e) {
        if (isWaiting(e.getPlayer()) && !e.isFlying()) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && isWaiting(p)
                && e.getCause() == EntityDamageEvent.DamageCause.VOID) {
            e.setCancelled(true);
            Location w = waitLocation();
            if (w != null) p.teleport(w);
        }
    }

    // ---------- reconnect / command block ----------
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancelTask(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (isWaiting(p)) Bukkit.getScheduler().runTask(this, () -> startWait(p));
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!isWaiting(p) || p.hasPermission("deathwait.bypass")) return;
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        List<String> allowed = getConfig().getStringList("allowed-commands");
        if (allowed.contains(cmd)) return;
        e.setCancelled(true);
        p.sendMessage(msg("command-blocked", ""));
    }

    // ---------- /deathwait ----------
    private Component fmt(String key, String player, String value) {
        String raw = getConfig().getString("messages." + key, key)
                .replace("%player%", player).replace("%s", value);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
    }

    private long secondsLeft(Player p) {
        Long until = p.getPersistentDataContainer().get(untilKey, PersistentDataType.LONG);
        return until == null ? 0 : Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
    }

    private void setTime(Player p, int seconds) {
        p.getPersistentDataContainer().set(untilKey, PersistentDataType.LONG,
                System.currentTimeMillis() + seconds * 1000L);
    }

    private boolean sendToWait(Player p, int seconds) {
        if (isWaiting(p)) { setTime(p, seconds); return true; }
        Location wait = waitLocation();
        if (wait == null) return false;
        p.getPersistentDataContainer().set(returnKey, PersistentDataType.STRING, ser(p.getLocation()));
        setTime(p, seconds);
        p.teleportAsync(wait).thenRun(() -> { startWait(p); playSpawnEffect(p); });
        return true;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) { s.sendMessage(fmt("admin-usage", "", "")); return true; }
        switch (a[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                ensureVoidWorld();
                s.sendMessage(fmt("admin-reloaded", "", ""));
            }
            case "list" -> {
                List<Player> waiting = Bukkit.getOnlinePlayers().stream()
                        .map(p -> (Player) p).filter(this::isWaiting).toList();
                if (waiting.isEmpty()) { s.sendMessage(fmt("admin-list-empty", "", "")); break; }
                s.sendMessage(fmt("admin-list-header", "", String.valueOf(waiting.size())));
                for (Player p : waiting)
                    s.sendMessage(fmt("admin-list-entry", p.getName(), String.valueOf(secondsLeft(p))));
            }
            case "skip", "time", "send" -> {
                String sub = a[0].toLowerCase();
                if (a.length < 2) { s.sendMessage(fmt("admin-usage", "", "")); break; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage(fmt("admin-no-player", a[1], "")); break; }
                int def = getConfig().getInt("wait-seconds", 60);
                int sec = def;
                if (!sub.equals("skip")) {
                    if (sub.equals("time") && a.length < 3) { s.sendMessage(fmt("admin-usage", "", "")); break; }
                    if (a.length >= 3) {
                        try { sec = Integer.parseInt(a[2]); } catch (NumberFormatException ex) { sec = -1; }
                        if (sec < 1) { s.sendMessage(fmt("admin-bad-number", "", a[2])); break; }
                    }
                }
                switch (sub) {
                    case "skip" -> {
                        if (!isWaiting(t)) { s.sendMessage(fmt("admin-not-waiting", t.getName(), "")); break; }
                        finish(t);
                        s.sendMessage(fmt("admin-skipped", t.getName(), ""));
                    }
                    case "time" -> {
                        if (!isWaiting(t)) { s.sendMessage(fmt("admin-not-waiting", t.getName(), "")); break; }
                        setTime(t, sec);
                        s.sendMessage(fmt("admin-time-set", t.getName(), String.valueOf(sec)));
                    }
                    default -> {
                        if (!sendToWait(t, sec)) { s.sendMessage(fmt("no-world", "", "")); break; }
                        s.sendMessage(fmt("admin-sent", t.getName(), String.valueOf(sec)));
                    }
                }
                               }
