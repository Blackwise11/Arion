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
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Arion's vessel body — a real, server-side {@link ServerPlayer} the server fully tracks.
 *
 * Because it is a genuine {@code ServerPlayer} registered via {@code PlayerList.placeNewPlayer},
 * everything the client-only vessel could never do "just works": it renders to all clients, mob AI
 * targets it, skin/name resolve from the {@link GameProfile}, and blocks/damage are server-validated.
 * This is the Carpet-mod approach ({@code EntityPlayerMPFake}) ported to NeoForge.
 *
 * The vessel has no real network connection — {@link ServerVesselManager} pairs it with a
 * {@link FakeConnection}, so it never sends or receives a packet. It ticks as part of the server's
 * normal player tick loop.
 */
public final class FakeServerPlayer extends ServerPlayer {

    /**
     * Create the vessel named {@code name} in {@code level}. The UUID is an offline-style UUID
     * derived from the name (stable across restarts, never collides with a real account). The vessel
     * renders with the default (Steve/Alex) skin for now — real skin resolution via the server's
     * {@code ProfileResolver} is a later polish step (the modern async API differs across versions).
     */
    public FakeServerPlayer(MinecraftServer server, ServerLevel level, String name) {
        super(server, level, profileFor(name), ClientInformation.createDefault());
    }

    /**
     * Drive the vessel's physics + replication ourselves (Phase A). This is the Carpet-mod approach.
     *
     * <p><b>Why we must tick physics manually:</b> the server ticks each player's
     * {@code ServerGamePacketListenerImpl} via {@code ServerConnectionListener.tick()}, which iterates
     * the server's <i>connection list</i>. The vessel's {@link FakeConnection} was never added to
     * that list (it bypassed the real channel handshake), so its listener's {@code tick()} never
     * runs — meaning {@code tickPlayer() → doTick()} (the physics driver: gravity, knockback,
     * movement) never runs. The vessel would float with {@code dy=0} forever, taking damage but
     * never falling.
     *
     * <p>Carpet's {@code EntityPlayerMPFake.tick()} solves this by calling {@code doTick()} itself.
     * {@code super.tick()} ({@code ServerPlayer.tick}: game mode, load-timeout, container) runs for
     * bookkeeping; {@code doTick()} ({@code ServerPlayer.doTick → Player.tick → LivingEntity.tick →
     * aiStep → travel}) applies the physics the listener would have. Because the listener isn't
     * ticking the vessel, there's no double-tick.
     *
     * <p><b>Replication:</b> a {@code ServerPlayer}'s position changes aren't broadcast to tracking
     * clients the way mob movement is (the server normally trusts the player's own client). Every 10
     * ticks we force {@code resetPosition()} + {@code getChunkSource().move(this)} so the host (and
     * any other clients) see the vessel fall / get knocked back. We also replicate EVERY tick while
     * the vessel has meaningful velocity — knockback is a brief impulse that decays within a few
     * ticks, so the 10-tick cadence would miss it and the vessel would look like it never flinched.
     */
    @Override
    public void tick() {
        super.tick();
        // Physics: drive doTick() ourselves since the listener (which normally calls it) is never
        // ticked for our network-inert vessel. Wrap defensively — doTick touches a lot of state.
        try {
            this.doTick();
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("[Arion] vessel doTick failed: {}", t.toString());
        }
        boolean moving = this.getDeltaMovement().horizontalDistanceSqr() > 1.0E-6
                || Math.abs(this.getDeltaMovement().y) > 0.001;
        if (this.tickCount % 10 == 0 || moving) {
            try {
                this.connection.resetPosition();
                this.level().getChunkSource().move(this);
            } catch (Throwable t) {
                arion.Arion.LOGGER.warn("[Arion] vessel move-sync failed: {}", t.toString());
            }
        }
    }

    private static GameProfile profileFor(String name) {
        String clean = (name == null || name.isBlank()) ? "Arion" : name.trim();
        if (clean.length() > 16) clean = clean.substring(0, 16);
        return new GameProfile(UUIDUtil.createOfflinePlayerUUID(clean), clean);
    }
}
