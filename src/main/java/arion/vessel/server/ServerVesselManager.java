/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.vessel.server;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side owner of the vessel: spawns/despawns a real {@link FakeServerPlayer} registered with
 * the server's player list, and reports status. One vessel per server.
 *
 * <p>This runs on the server thread (the integrated server in single-player). The client reaches it
 * via {@code Minecraft.getInstance().getSingleplayerServer()} + {@code server.execute(...)} (Phase A);
 * a packet channel replaces those direct calls in Phase B for LAN support.
 *
 * <p>State is keyed by {@link MinecraftServer} in a {@link ConcurrentHashMap}. The integrated
 * server is recreated on each world load, but {@link #shutdown} (on {@code ServerStoppingEvent})
 * removes the entry, so a vessel never leaks across worlds. Using a concurrent map (rather than a
 * {@code WeakHashMap}) lets the client thread read vessel state via {@link #peek} without racing a
 * concurrent {@code computeIfAbsent} on the server thread.
 */
public final class ServerVesselManager {

    private static final ConcurrentHashMap<MinecraftServer, ServerVesselManager> BY_SERVER = new ConcurrentHashMap<>();

    private final MinecraftServer server;
    private volatile FakeServerPlayer vessel;
    private volatile UUID hostUuid;          // the player who owns the vessel (respawn comes back to them)
    private volatile UUID vesselUuid;        // last vessel's UUID — lets despawn scrub a lingering body even
                                             // after `this.vessel` has been cleared. Volatile: read
                                             // off-thread by the client via peek paths.

    private ServerVesselManager(MinecraftServer server) {
        this.server = server;
    }

    /** Get (or lazily create) the manager for a server. Server thread. */
    public static ServerVesselManager get(MinecraftServer server) {
        return BY_SERVER.computeIfAbsent(server, ServerVesselManager::new);
    }

    /**
     * Non-creating lookup — safe to call from the client thread for a quick status read. Returns
     * null if no manager exists yet for this server (e.g. before {@code ServerStartingEvent}).
     */
    public static ServerVesselManager peek(MinecraftServer server) {
        return BY_SERVER.get(server);
    }

    /** Tear down on server stop — drop any vessel so it never survives into the next world. */
    public static void shutdown(MinecraftServer server) {
        ServerVesselManager m = BY_SERVER.remove(server);
        if (m != null) m.despawn();
    }

    public boolean hasVessel() {
        return vessel != null && vessel.isAlive();
    }

    /**
     * Spawn the vessel at the host player's position (so it appears next to whoever typed
     * {@code ai spawn}). Returns a status message for the host's chat.
     */
    public String spawn(String name, UUID hostPlayerUuid) {
        // If the previous vessel died and somehow lingered (auto-respawn normally handles death, but
        // a crash mid-respawn or a pre-respawn-build vessel could leave a dead body), drop it first.
        if (vessel != null && !vessel.isAlive()) {
            try { server.getPlayerList().remove(vessel); } catch (Throwable ignored) {}
            vessel = null;
        }
        if (hasVessel()) {
            return vessel.getGameProfile().name() + " is already here. ('ai despawn' first)";
        }
        ServerPlayer host = server.getPlayerList().getPlayer(hostPlayerUuid);
        if (host == null) return "Couldn't find you on the server — try again.";

        this.hostUuid = hostPlayerUuid;
        return createAndRegister(name, (ServerLevel) host.level(), host.position());
    }

    /**
     * Respawn the vessel after death. Called from {@link FakeServerPlayer#die} on the next server
     * tick (deferred via {@code server.execute}, so we run BEFORE the level ticks entities — the
     * dead body has health ≤ 0 but {@code deathTime} hasn't reached 20 yet, so {@code remove(KILLED)}
     * has NOT fired and the entity is still tracked by clients).
     *
     * <p><b>Revives the same {@link FakeServerPlayer} in place</b> — resets health/death-state and
     * teleports it to its <b>own</b> respawn point (bed/anchor set with {@code /spawnpoint Arion},
     * else the world spawn), exactly like vanilla — rather than tearing it down and re-registering a
     * new one. The vessel stays in the {@code PlayerList} the whole time, so there's no
     * "Arion joined the game" message and no entity vanish/reappear: it's a real respawn, not a
     * despawn+spawn. No-op if the vessel isn't actually dead (e.g. a NeoForge death event cancelled
     * it). Falls back to the recreate path only in the (timing-impossible) case the body was already
     * removed.
     */
    void respawnVessel() {
        if (vessel == null || vessel.isAlive()) return;
        String name = vessel.getGameProfile().name();

        // Vanilla respawn semantics, for the vessel ITSELF:
        //   - its own bed/anchor if one is set (/spawnpoint Arion, or a bed it actually slept in),
        //   - otherwise the world spawn (level.dat), with vanilla's safe-position search,
        //   - in the configured dimension.
        // We deliberately do NOT use the host player's position or the host's bed: respawning beside
        // the host teleported the body to the player (often hundreds of blocks from spawn) and made
        // "respawn at spawn" impossible. findRespawnPositionAndUseSpawnBlock handles a missing or
        // obstructed bed (missingRespawnBlock) and the no-config default (createDefault).
        net.minecraft.world.level.portal.TeleportTransition tt =
                vessel.findRespawnPositionAndUseSpawnBlock(
                        false, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING);
        ServerLevel level = tt.newLevel();
        Vec3 at = tt.position();

        if (vessel.isRemoved() || level != vessel.level()) {
            // A removed body, or a respawn in a DIFFERENT dimension (e.g. died in the Nether, bed is
            // in the overworld): reviveInPlace only snaps the entity's position inside its current
            // level, so it cannot move the body across levels. Re-register a fresh body in the target
            // level instead. This does print the join message.
            arion.Arion.LOGGER.warn("[Arion] vessel {} {} — falling back to recreate in {}",
                    name, vessel.isRemoved() ? "was already removed" : "changes dimension on respawn",
                    level.dimension().identifier());
            UUID deadUuid = vessel.getUUID();
            try { server.getPlayerList().remove(vessel); } catch (Throwable ignored) {}
            vessel = null;
            purgeByUuid(deadUuid);
            createAndRegister(name, level, at);
        } else {
            arion.Arion.LOGGER.info("[Arion] vessel {} died — reviving at its respawn point {}", name, at);
            vessel.reviveInPlace(level, at);
        }
        if (hostUuid != null) tellHost(server, hostUuid, name + " respawned at the spawn point.");
    }

    /**
     * Create + register a fresh vessel named {@code name} at {@code pos} in {@code level}. Shared by
     * {@link #spawn} and {@link #respawnVessel}. Defensively purges any lingering same-UUID player
     * first, so the single-vessel invariant holds even if prior state was dirty.
     */
    private String createAndRegister(String name, ServerLevel level, Vec3 pos) {
        String clean = nameOrArion(name);
        GameProfile profile = new GameProfile(
                net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(clean), clean);
        purgeByUuid(profile.id());

        FakeServerPlayer fp = new FakeServerPlayer(server, level, clean);
        fp.snapTo(pos.x, pos.y, pos.z, 0.0f, 0.0f);
        fp.setOldPosAndRot();
        fp.setHealth(fp.getMaxHealth());
        fp.getFoodData().setFoodLevel(20);

        CommonListenerCookie cookie = new CommonListenerCookie(profile, 0, ClientInformation.createDefault(), false);
        try {
            server.getPlayerList().placeNewPlayer(
                    new FakeConnection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), fp, cookie);
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("ServerVesselManager: placeNewPlayer failed: {}", t.toString());
            try { fp.discard(); } catch (Throwable ignored) {}
            return "Couldn't spawn the vessel: " + t;
        }

        try { fp.connection.markClientLoaded(); } catch (Throwable ignored) {}

        // Force SURVIVAL. placeNewPlayer inherits the world's default game type (ServerPlayer ctor →
        // calculateGameModeForNewPlayer), so on a creative world the vessel would spawn creative →
        // abilities.invulnerable=true → Player.hurtServer returns false for ALL melee damage. Survival
        // clears that flag so the vessel is genuinely damageable.
        try {
            fp.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            fp.getAbilities().invulnerable = false;   // belt-and-suspenders; survival already clears it
            fp.onUpdateAbilities();
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("[Arion] forcing vessel SURVIVAL failed: {}", t.toString());
        }

        this.vessel = fp;
        this.vesselUuid = profile.id();
        return "Summoned " + clean + " (server-side, survival). Mobs can see it; it takes real damage.";
    }

    /**
     * Remove every player whose UUID matches {@code uuid} from the PlayerList + level. The host's
     * real UUID never equals the vessel's offline UUID, so the host is never touched. Idempotent.
     * This is the single-vessel safety net: even if a dead body lingered or a spawn raced, there's
     * never more than one Arion.
     */
    private void purgeByUuid(UUID uuid) {
        if (uuid == null) return;
        PlayerList pl = server.getPlayerList();
        for (ServerPlayer p : new java.util.ArrayList<>(pl.getPlayers())) {
            if (p.getUUID().equals(uuid)) {
                try { pl.remove(p); } catch (Throwable ignored) {}
                try { p.discard(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Remove the vessel from the server. Also scrubs any lingering same-UUID body. */
    public String despawn() {
        UUID uuid = vessel != null ? vessel.getUUID() : vesselUuid;
        if (uuid == null) return "No vessel is active.";
        String name = vessel != null ? vessel.getGameProfile().name() : "Vessel";
        if (vessel != null) {
            try { server.getPlayerList().remove(vessel); } catch (Throwable ignored) {}
            try { vessel.discard(); } catch (Throwable ignored) {}
            vessel = null;
        }
        purgeByUuid(uuid);   // scrub any lingering body (e.g. a dead one this.vessel lost track of)
        return name + " despawned.";
    }

    /**
     * Order the vessel to walk straight toward a world position (Phase B / M1). Server thread —
     * the client reaches this via {@code server.execute} from {@link arion.vessel.FakePlayerManager}.
     * Returns a status message, or null if the walk started (no chat needed).
     */
    public String walkTo(double x, double y, double z) {
        if (vessel == null || !vessel.isAlive()) return "No vessel. Summon one with 'ai spawn'.";
        vessel.walkTo(x, y, z);
        return null;
    }

    /** Stop any in-progress walk. Server thread. Returns null (no chat). */
    public String stopWalk() {
        if (vessel == null) return null;
        vessel.stopWalking();
        return null;
    }

    /**
     * Make the vessel eat (real, timed food use from its hotbar). Server thread.
     *
     * @return a status message when nothing could start, or null when eating began
     */
    public String eat() {
        FakeServerPlayer v = vessel;
        if (v == null || !v.isAlive()) return "No vessel. Summon one with 'ai spawn'.";
        if (v.isEating()) return null; // already eating
        if (v.getFoodData().getFoodLevel() >= 20) return "Vessel isn't hungry.";
        if (!v.tryEatFromHotbar()) return "Vessel has no food in its hotbar.";
        return null;
    }

    /**
     * Raise/lower the vessel's offhand shield and face the threat point. Server thread.
     *
     * @return a status message on failure, or null
     */
    public String shield(boolean raise, double x, double y, double z) {
        FakeServerPlayer v = vessel;
        if (v == null || !v.isAlive()) return "No vessel.";
        if (raise) v.startShield(new Vec3(x, y, z));
        else v.stopShield();
        return null;
    }

    /**
     * Hold the vessel's offhand shield for a moment regardless of threats — the 'ai shield test'
     * probe. Reports the authoritative state via the debug log when the window closes. Server thread.
     */
    public String testShield(int ticks) {
        FakeServerPlayer v = vessel;
        if (v == null || !v.isAlive()) return "No vessel.";
        v.forceShield(ticks);
        return null;
    }

    /**
     * Open a real container UI on the host that exposes the vessel's 36 inventory slots
     * (hotbar + main). The host can move items in/out with normal chest interactions; the
     * vessel's Inventory is the live body inventory, so Baritone sees the result after the
     * shadow sync.
     *
     * @return a status message on failure, or null when the menu opened
     */
    public String openInventory(ServerPlayer host) {
        FakeServerPlayer v = vessel;
        if (v == null || !v.isAlive()) return "No vessel. Summon one with 'ai spawn'.";
        if (host == null || host.isRemoved()) return "Host player is not available.";
        // 5 rows: main inventory + hotbar, then the four armor slots (helmet, chest, legs, boots)
        // and the offhand. VesselInventoryView writes through the body's real Inventory, so armor
        // attributes and the offhand shield apply immediately (no /item replace needed).
        host.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new VesselChestMenu(ArionMenus.VESSEL_INVENTORY.get(), id, inv,
                        new VesselInventoryView(v.getInventory())),
                Component.literal(v.getGameProfile().name() + "'s Inventory")));
        return null;
    }

    /** Authoritative body vitals for the System 1 prompt (server thread). */
    public record Vitals(float health, int food) {}

    /**
     * Read the vessel's real health/food. The client-side shadow does NOT mirror either, so the
     * Laya state must use these values (the planner already gets them in {@link #vesselStatusJson}).
     *
     * @return null when there is no live vessel
     */
    public Vitals vitals() {
        FakeServerPlayer v = vessel;
        if (v == null || !v.isAlive()) return null;
        return new Vitals((float) v.getHealth(), v.getFoodData().getFoodLevel());
    }

    /** One-line status for {@code ai vessel}. */
    public String status() {
        if (vessel == null || !vessel.isAlive()) return "No vessel. Summon one with 'ai spawn'.";
        String invuln = "";
        if (vessel.invulnerableTime > 0 || vessel.getAbilities().invulnerable) {
            invuln = ", INVULNERABLE(t=" + vessel.invulnerableTime
                    + ", abilities=" + vessel.getAbilities().invulnerable + ")";
        }
        return String.format("%s at [%d, %d, %d], hp=%d/%d%s%s (server-side)",
                vessel.getGameProfile().name(),
                (int) vessel.getX(), (int) vessel.getY(), (int) vessel.getZ(),
                (int) Math.ceil(vessel.getHealth()), (int) vessel.getMaxHealth(),
                vessel.isWalking() ? ", walking" : "", invuln);
    }

    /**
     * Compact vessel-body status for the brain: health/food/saturation/armor, the armor + offhand
     * slots (with remaining durability), and the hotbar + main inventory. Server thread — the
     * vessel is a server-side entity, so this is the only safe place to read its body. Returns null
     * when there's no live vessel, so the caller can omit the block entirely from the prompt.
     *
     * <p>Kept short on purpose — local models choke on giant prompts.
     */
    public String vesselStatus() {
        if (vessel == null || !vessel.isAlive()) return null;
        FakeServerPlayer v = vessel;
        StringBuilder sb = new StringBuilder(256);
        sb.append("vessel_health=").append((int) Math.ceil(v.getHealth())).append('/')
                .append((int) v.getMaxHealth())
                .append(" food=").append(v.getFoodData().getFoodLevel()).append("/20")
                .append(" saturation=").append(String.format(java.util.Locale.ROOT, "%.1f",
                        v.getFoodData().getSaturationLevel()))
                .append(" armor=").append(v.getArmorValue()).append('\n');
        // armor + offhand: name + remaining durability %
        for (net.minecraft.world.entity.EquipmentSlot s : new net.minecraft.world.entity.EquipmentSlot[]{
                net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS,
                net.minecraft.world.entity.EquipmentSlot.FEET,
                net.minecraft.world.entity.EquipmentSlot.OFFHAND}) {
            net.minecraft.world.item.ItemStack a = v.getItemBySlot(s);
            if (!a.isEmpty()) {
                int pct = a.getMaxDamage() > 0
                        ? (a.getMaxDamage() - a.getDamageValue()) * 100 / a.getMaxDamage() : 100;
                sb.append("  ").append(s.getName()).append('=')
                        .append(a.getHoverName().getString()).append(" (").append(pct).append("%)\n");
            }
        }
        // hotbar + main inventory (slots 0..35; armor/offhand shown above)
        java.util.List<String> inv = new java.util.ArrayList<>();
        for (int i = 0; i < 36; i++) {
            net.minecraft.world.item.ItemStack s = v.getInventory().getItem(i);
            if (!s.isEmpty()) inv.add(s.getHoverName().getString() + " x" + s.getCount());
            if (inv.size() >= 20) break;
        }
        sb.append("vessel_inventory=").append(inv.isEmpty() ? "empty" : String.join(", ", inv));
        return sb.toString();
    }

    /**
     * Structured vessel status for the brain: the same body state as {@link #vesselStatus()} plus
     * the fields the planner needs to reason about progression (selected slot, held tool and
     * durability, armor, effects, XP, velocity, fluid/fire state). Compact JSON (no pretty
     * printing) so local models can read it without a token blow-up.
     *
     * <p>Server thread only (it reads the live body); callers hop with {@code server.execute}.
     * Returns null when there is no live vessel.
     */
    public String vesselStatusJson() {
        FakeServerPlayer v = vessel;
        if (v == null || !v.isAlive()) return null;

        JsonObject root = new JsonObject();
        root.addProperty("name", v.getGameProfile().name());
        root.addProperty("uuid", v.getUUID().toString());
        root.addProperty("dimension", v.level().dimension().identifier().toString());
        root.add("pos", vec(v.getX(), v.getY(), v.getZ()));
        root.add("velocity", vec(v.getDeltaMovement().x, v.getDeltaMovement().y, v.getDeltaMovement().z));
        root.addProperty("on_ground", v.onGround());
        root.addProperty("in_water", v.isInWater());
        root.addProperty("on_fire", v.isOnFire());
        root.addProperty("sneaking", v.isCrouching());
        root.addProperty("sprinting", v.isSprinting());
        root.addProperty("using_item", v.isUsingItem());
        root.addProperty("game_mode", v.gameMode.getGameModeForPlayer().getName());

        JsonObject health = new JsonObject();
        health.addProperty("current", round1(v.getHealth()));
        health.addProperty("max", round1(v.getMaxHealth()));
        health.addProperty("absorption", round1(v.getAbsorptionAmount()));
        root.add("health", health);

        JsonObject food = new JsonObject();
        food.addProperty("level", v.getFoodData().getFoodLevel());
        food.addProperty("max", 20);
        food.addProperty("saturation", round1(v.getFoodData().getSaturationLevel()));
        root.add("food", food);

        root.addProperty("air", v.getAirSupply());
        root.addProperty("armor", v.getArmorValue());
        root.addProperty("xp_level", v.experienceLevel);
        // Post-respawn diagnostics: all three must be zero/false for the body to be damageable.
        root.addProperty("invulnerable_time", v.invulnerableTime);
        root.addProperty("abilities_invulnerable", v.getAbilities().invulnerable);
        root.addProperty("hurt_time", v.hurtTime);

        JsonArray effects = new JsonArray();
        for (MobEffectInstance e : v.getActiveEffects()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value()).toString());
            o.addProperty("amplifier", e.getAmplifier());
            o.addProperty("duration_ticks", e.getDuration());
            effects.add(o);
        }
        root.add("effects", effects);

        root.addProperty("selected_slot", v.getInventory().getSelectedSlot());
        root.add("held", itemJson(v.getMainHandItem()));
        root.add("offhand", itemJson(v.getItemBySlot(EquipmentSlot.OFFHAND)));

        JsonObject armorItems = new JsonObject();
        for (EquipmentSlot s : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            armorItems.add(s.getName(), itemJson(v.getItemBySlot(s)));
        }
        root.add("armor_items", armorItems);

        JsonArray inv = new JsonArray();
        for (int i = 0; i < 36; i++) {
            ItemStack s = v.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            JsonObject o = itemJson(s);
            o.addProperty("slot", i);
            inv.add(o);
        }
        root.add("inventory", inv);
        return root.toString();
    }

    private static JsonObject vec(double x, double y, double z) {
        JsonObject o = new JsonObject();
        o.addProperty("x", round1(x));
        o.addProperty("y", round1(y));
        o.addProperty("z", round1(z));
        return o;
    }

    private static JsonObject itemJson(ItemStack s) {
        JsonObject o = new JsonObject();
        if (s.isEmpty()) {
            o.addProperty("id", "minecraft:air");
            o.addProperty("count", 0);
            return o;
        }
        o.addProperty("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        o.addProperty("count", s.getCount());
        if (s.isDamageableItem()) {
            o.addProperty("damage", s.getDamageValue());
            o.addProperty("max_damage", s.getMaxDamage());
        }
        return o;
    }

    private static double round1(double d) {
        return Math.round(d * 10.0) / 10.0;
    }

    /** Send a message to the host player (server-side chat). */
    public static void tellHost(MinecraftServer server, UUID hostPlayerUuid, String message) {
        ServerPlayer host = server.getPlayerList().getPlayer(hostPlayerUuid);
        if (host != null) {
            host.sendSystemMessage(Component.literal("[Arion] " + message));
        }
    }

    /** The vessel, or null — used by later phases (movement/combat) to drive the body. */
    public FakeServerPlayer vessel() {
        return vessel;
    }

    private static String nameOrArion(String name) {
        String clean = (name == null || name.isBlank()) ? "Arion" : name.trim();
        if (clean.length() > 16) clean = clean.substring(0, 16);
        return clean;
    }
}
