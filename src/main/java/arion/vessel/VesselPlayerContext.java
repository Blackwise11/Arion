/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.vessel;

import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IPlayerController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

/**
 * An {@link IPlayerContext} bound to the vessel instead of the primary player.
 *
 * Only the {@code baritone.api} interface is on the compile classpath (the concrete
 * {@code BaritonePlayerContext} is not), so this implements the interface directly.
 * {@code player()} returns the vessel, and position/rotation-derived defaults
 * ({@code playerFeet}, {@code playerRotations}, …) are computed from it. Everything
 * world-level (the world, the world-data cache) is delegated to the vessel's original
 * context. {@code playerController()} is a {@link NoopPlayerController} so no Baritone
 * process can emit a break/place/attack packet through it.
 *
 * Installed onto the vessel's Baritone via {@link VesselBaritoneBinder}.
 */
public final class VesselPlayerContext implements IPlayerContext {

    private final LocalPlayer vessel;
    private final IPlayerContext delegate;

    public VesselPlayerContext(LocalPlayer vessel, IPlayerContext delegate) {
        this.vessel = vessel;
        this.delegate = delegate;
    }

    @Override
    public Minecraft minecraft() {
        return delegate.minecraft();
    }

    @Override
    public LocalPlayer player() {
        return vessel;
    }

    @Override
    public IPlayerController playerController() {
        return NoopPlayerController.INSTANCE;
    }

    @Override
    public Level world() {
        return delegate.world();
    }

    @Override
    public baritone.api.cache.IWorldData worldData() {
        return delegate.worldData();
    }

    @Override
    public HitResult objectMouseOver() {
        return delegate.objectMouseOver();
    }

    @Override
    public BetterBlockPos viewerPos() {
        // the renderer/camera still belongs to the primary player
        return delegate.viewerPos();
    }
}
