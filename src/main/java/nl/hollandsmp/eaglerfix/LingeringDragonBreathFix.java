package nl.hollandsmp.lingeringdragonbreathfix;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class LingeringDragonBreathFix extends JavaPlugin {

    private EaglerPlayerDetector detector;
    private TrackerPatcher patcher;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getConfig().getBoolean("enabled", true)) {
            getLogger().info("[LingeringDragonBreathFix] Disabled by config");
            return;
        }

        detector = new EaglerPlayerDetector(this);
        detector.enable();

        patcher = new TrackerPatcher(this, detector, getConfig().getBoolean("debug", false));
        try {
            patcher.enable();
            getLogger().info("[LingeringDragonBreathFix] Enabled");
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "[LingeringDragonBreathFix] Failed to enable tracker patcher, disabling Eagler protection", t);
            getLogger().warning("[LingeringDragonBreathFix] Eagler protection disabled.");
        }
    }

    @Override
    public void onDisable() {
        if (patcher != null) {
            try {
                patcher.disable();
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "[LingeringDragonBreathFix] Error disabling patcher", t);
            }
        }
        if (detector != null) detector.disable();
        getLogger().info("[LingeringDragonBreathFix] Disabled");
    }
}
