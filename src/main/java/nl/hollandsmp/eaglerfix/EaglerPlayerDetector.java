package nl.hollandsmp.lingeringdragonbreathfix;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects whether a Bukkit Player is an Eaglercraft player.
 * Uses reflection to call EaglerXServer API if available. Falls back to false.
 */
public class EaglerPlayerDetector implements Listener {
    private final Plugin plugin;
    private final Map<UUID, Boolean> cache = new ConcurrentHashMap<>();

    // reflection handles
    private Object eaglerApiInstance = null;
    private Method isEaglerPlayerMethod = null; // may be (Player) or (UUID)

    public EaglerPlayerDetector(Plugin plugin) {
        this.plugin = plugin;
        discoverEaglerApi();
    }

    private void discoverEaglerApi() {
        try {
            Class<?> apiClass = Class.forName("net.lax1dude.eaglercraft.backend.EaglerXServerAPI");
            Method instanceMethod = apiClass.getMethod("instance");
            eaglerApiInstance = instanceMethod.invoke(null);

            // try method (org.bukkit.entity.Player)
            try {
                isEaglerPlayerMethod = apiClass.getMethod("isEaglerPlayer", org.bukkit.entity.Player.class);
            } catch (NoSuchMethodException ignore) {
                // try UUID
                try {
                    isEaglerPlayerMethod = apiClass.getMethod("isEaglerPlayerByUUID", java.util.UUID.class);
                } catch (NoSuchMethodException ignore2) {
                    plugin.getLogger().warning("[LingeringDragonBreathFix] EaglerXServer API found but no known isEagler method.");
                    eaglerApiInstance = null;
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[LingeringDragonBreathFix] EaglerXServer API not available. Eagler protection disabled.");
            eaglerApiInstance = null;
            isEaglerPlayerMethod = null;
        }
    }

    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // prime cache for online players
        for (Player p : Bukkit.getOnlinePlayers()) {
            cache.put(p.getUniqueId(), detect(p));
        }
    }

    public void disable() {
        cache.clear();
        try {
            org.bukkit.event.HandlerList.unregisterAll(this);
        } catch (Throwable ignore) {}
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        cache.put(e.getPlayer().getUniqueId(), detect(e.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cache.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        cache.put(e.getPlayer().getUniqueId(), detect(e.getPlayer()));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        cache.put(e.getPlayer().getUniqueId(), detect(e.getPlayer()));
    }

    private boolean detect(Player p) {
        if (eaglerApiInstance == null || isEaglerPlayerMethod == null) return false;
        try {
            Class<?>[] params = isEaglerPlayerMethod.getParameterTypes();
            if (params.length == 1 && params[0].equals(org.bukkit.entity.Player.class)) {
                Object res = isEaglerPlayerMethod.invoke(eaglerApiInstance, p);
                return Boolean.TRUE.equals(res);
            } else if (params.length == 1 && params[0].equals(UUID.class)) {
                Object res = isEaglerPlayerMethod.invoke(eaglerApiInstance, p.getUniqueId());
                return Boolean.TRUE.equals(res);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[HollandEaglerFix] Error calling EaglerXServer API: " + t.getMessage());
        }
        return false;
    }

    public boolean isEaglerPlayerByUUID(UUID uuid) {
        Boolean b = cache.get(uuid);
        return b != null && b;
    }
}
