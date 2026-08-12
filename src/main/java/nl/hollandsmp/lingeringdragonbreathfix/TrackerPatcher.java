package nl.hollandsmp.lingeringdragonbreathfix;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Patches EntityTracker entries for EntityAreaEffectCloud so that Eagler players are not added to trackedPlayers.
 * All reflection access is defensive; if anything fails the patcher disables itself and logs a warning.
 */
@SuppressWarnings({"rawtypes","unchecked"})
public class TrackerPatcher {
    private final Plugin plugin;
    private final EaglerPlayerDetector detector;
    private final boolean debug;

    // caches for cleanup
    private final Map<Object, WrappedState> patchedEntries = new ConcurrentHashMap<>();
    private final Map<Object, Object> patchedMaps = new ConcurrentHashMap<>();

    public TrackerPatcher(Plugin plugin, EaglerPlayerDetector detector, boolean debug) {
        this.plugin = plugin;
        this.detector = detector;
        this.debug = debug;
    }

    public void enable() throws Exception {
        // attempt to find NMS EntityAreaEffectCloud class to verify environment
        Class<?> areaClass;
        try {
            areaClass = Class.forName("net.minecraft.server.v1_12_R1.EntityAreaEffectCloud");
        } catch (ClassNotFoundException e) {
            throw new Exception("NMS EntityAreaEffectCloud not found; running without Eagler protection.");
        }

        // iterate worlds and patch
        for (World w : Bukkit.getWorlds()) {
            try {
                patchWorld(w, areaClass);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "[LingeringDragonBreathFix] Failed to patch world " + w.getName(), t);
            }
        }

        // schedule a synchronous task to re-scan periodically to catch newly loaded worlds or entries
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                for (World w : Bukkit.getWorlds()) patchWorld(w, areaClass);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "[LingeringDragonBreathFix] periodic patch scan failed", t);
            }
        }, 20L * 5, 20L * 30);
    }

    public void disable() {
        // try to restore original sets/maps
        for (Map.Entry<Object, WrappedState> e : patchedEntries.entrySet()) {
            try {
                WrappedState s = e.getValue();
                Field f = s.trackedPlayersField;
                f.setAccessible(true);
                f.set(e.getKey(), s.originalSet);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "[LingeringDragonBreathFix] failed to restore entry set", t);
            }
        }
        for (Map.Entry<Object, Object> e : patchedMaps.entrySet()) {
            try {
                Object tracker = e.getKey();
                Field mapField = (Field) e.getValue();
                mapField.setAccessible(true);
                // no reliable original stored globally; best-effort only
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "[LingeringDragonBreathFix] failed to restore map", t);
            }
        }
    }

    private void patchWorld(World w, Class<?> areaClass) throws Exception {
        // get CraftWorld handle
        Object craftWorld = w.getClass().getName().startsWith("org.bukkit.craftbukkit") ? w : null;
        if (craftWorld == null) throw new Exception("Unexpected world class: " + w.getClass().getName());

        // call getHandle()
        Method getHandle = w.getClass().getMethod("getHandle");
        Object worldServer = getHandle.invoke(w);
        if (worldServer == null) throw new Exception("getHandle returned null for world " + w.getName());

        // find tracker field
        Field trackerField = null;
        for (Field f : worldServer.getClass().getDeclaredFields()) {
            if (f.getType().getSimpleName().equals("EntityTracker") || f.getType().getName().contains("EntityTracker")) {
                trackerField = f;
                break;
            }
        }
        if (trackerField == null) throw new Exception("EntityTracker field not found on WorldServer");
        trackerField.setAccessible(true);
        Object tracker = trackerField.get(worldServer);
        if (tracker == null) throw new Exception("WorldServer.tracker is null");

        // find trackedEntities Map field on tracker
        Field trackedMapField = null;
        for (Field f : tracker.getClass().getDeclaredFields()) {
            if (Map.class.isAssignableFrom(f.getType())) {
                trackedMapField = f;
                break;
            }
        }
        if (trackedMapField == null) throw new Exception("trackedEntities Map field not found on EntityTracker");
        trackedMapField.setAccessible(true);
        Map tracked = (Map) trackedMapField.get(tracker);
        if (tracked == null) return;

        // wrap the map so future puts are intercepted
        if (!patchedMaps.containsKey(tracker)) {
            Map wrapped = new InterceptingMap(tracked, areaClass);
            trackedMapField.set(tracker, wrapped);
            patchedMaps.put(tracker, trackedMapField);
            if (debug) plugin.getLogger().info("[LingeringDragonBreathFix] replaced trackedEntities map for world " + w.getName());
        }

        // iterate existing entries
        Collection entries = new ArrayList(tracked.values());
        for (Object entry : entries) {
            try {
                patchEntryIfNeeded(entry, areaClass);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINER, "[LingeringDragonBreathFix] failed to patch entry", t);
            }
        }
    }

    private void patchEntryIfNeeded(Object entry, Class<?> areaClass) throws Exception {
        if (patchedEntries.containsKey(entry)) return;
        // find the tracked entity field (type extends net.minecraft.server.Entity)
        Object trackedEntity = null;
        Field trackedEntityField = null;
        for (Field f : entry.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object val = f.get(entry);
            if (val == null) continue;
            Class<?> c = val.getClass();
            if (c.getName().startsWith("net.minecraft.server.v1_12_R1.Entity")) {
                trackedEntity = val;
                trackedEntityField = f;
                break;
            }
        }
        if (trackedEntity == null) return;
        // check if entity is EntityAreaEffectCloud
        if (!areaClass.isAssignableFrom(trackedEntity.getClass())) return;

        // find trackedPlayers Set field
        Field setField = null;
        Set originalSet = null;
        for (Field f : entry.getClass().getDeclaredFields()) {
            if (Set.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Object o = f.get(entry);
                if (o instanceof Set) {
                    // assume this is trackedPlayers
                    setField = f;
                    originalSet = (Set) o;
                    break;
                }
            }
        }
        if (setField == null || originalSet == null) return;

        // replace with filtered set
        FilteredSet filtered = new FilteredSet(originalSet, detector, true);
        setField.set(entry, filtered);
        patchedEntries.put(entry, new WrappedState(setField, originalSet));
        if (debug) plugin.getLogger().info("[LingeringDragonBreathFix] patched EntityTrackerEntry for AEC");
    }

    // state holder
    private static class WrappedState {
        final Field trackedPlayersField;
        final Set originalSet;

        WrappedState(Field f, Set s) {
            this.trackedPlayersField = f;
            this.originalSet = s;
        }
    }

    // Map wrapper to intercept future additions of entries
    private class InterceptingMap implements Map {
        private final Map delegate;
        private final Class<?> areaClass;

        InterceptingMap(Map delegate, Class<?> areaClass) {
            this.delegate = delegate;
            this.areaClass = areaClass;
        }

        private void maybeWrapEntry(Object value) {
            try {
                patchEntryIfNeeded(value, areaClass);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "[LingeringDragonBreathFix] failed to wrap new entry", t);
            }
        }

        @Override public int size() { return delegate.size(); }
        @Override public boolean isEmpty() { return delegate.isEmpty(); }
        @Override public boolean containsKey(Object key) { return delegate.containsKey(key); }
        @Override public boolean containsValue(Object value) { return delegate.containsValue(value); }
        @Override public Object get(Object key) { return delegate.get(key); }
        @Override public Object put(Object key, Object value) { maybeWrapEntry(value); return delegate.put(key, value); }
        @Override public Object remove(Object key) { return delegate.remove(key); }
        @Override public void putAll(Map m) { for (Object v : m.values()) maybeWrapEntry(v); delegate.putAll(m); }
        @Override public void clear() { delegate.clear(); }
        @Override public Set keySet() { return delegate.keySet(); }
        @Override public Collection values() { return delegate.values(); }
        @Override public Set<Entry> entrySet() { return delegate.entrySet(); }
    }

    // Set wrapper that filters additions of EntityPlayer objects that are Eagler players
    private static class FilteredSet implements Set {
        private final Set delegate;
        private final EaglerPlayerDetector detector;
        private final boolean isAEC;

        FilteredSet(Set delegate, EaglerPlayerDetector detector, boolean isAEC) {
            this.delegate = delegate;
            this.detector = detector;
            this.isAEC = isAEC;
        }

        private boolean shouldAdd(Object o) {
            if (!isAEC) return true;
            if (o == null) return true;
            try {
                // try to get UUID from NMS EntityPlayer via getUniqueID()
                Method m = o.getClass().getMethod("getUniqueID");
                Object uuidObj = m.invoke(o);
                if (uuidObj instanceof java.util.UUID) {
                    java.util.UUID uuid = (java.util.UUID) uuidObj;
                    return !detector.isEaglerPlayerByUUID(uuid);
                }
            } catch (Throwable ignored) {}
            return true;
        }

        @Override public int size() { return delegate.size(); }
        @Override public boolean isEmpty() { return delegate.isEmpty(); }
        @Override public boolean contains(Object o) { return delegate.contains(o); }
        @Override public Iterator iterator() { return delegate.iterator(); }
        @Override public Object[] toArray() { return delegate.toArray(); }
        @Override public Object[] toArray(Object[] a) { return delegate.toArray(a); }
        @Override public boolean add(Object o) {
            if (!shouldAdd(o)) return false;
            return delegate.add(o);
        }
        @Override public boolean remove(Object o) { return delegate.remove(o); }
        @Override public boolean containsAll(Collection c) { return delegate.containsAll(c); }
        @Override public boolean addAll(Collection c) {
            boolean changed = false;
            for (Object o : c) changed |= add(o);
            return changed;
        }
        @Override public boolean retainAll(Collection c) { return delegate.retainAll(c); }
        @Override public boolean removeAll(Collection c) { return delegate.removeAll(c); }
        @Override public void clear() { delegate.clear(); }
    }
}
