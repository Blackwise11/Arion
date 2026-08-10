/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.vessel.server;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;

/**
 * A network {@link Connection} for the server-side vessel that goes nowhere.
 *
 * Modelled on Carpet's {@code FakeClientConnection}: it extends {@link Connection}, wires an inert
 * {@link EmbeddedChannel} so {@code isOpen()}/{@code isConnected()} report true (the server's player
 * tick and several mechanics check channel state), and stubs every send / listener-setup /
 * disconnection method to a no-op. The vessel never has a real socket, so nothing is ever written
 * to the network and nothing is ever read — but to the server it looks like a connected player.
 *
 * Carpet sets the channel via a mixin-added interface; NeoForge has no such hook here, so we set the
 * private {@code channel} field reflectively (same effect). {@code Connection} ships on the classpath
 * (not a sealed module), so {@code setAccessible} succeeds.
 */
public final class FakeConnection extends Connection {

    public FakeConnection(PacketFlow flow) {
        super(flow);
        setChannelReflectively(new EmbeddedChannel());
    }

    private void setChannelReflectively(io.netty.channel.Channel channel) {
        try {
            Field f = Connection.class.getDeclaredField("channel");
            f.setAccessible(true);
            f.set(this, channel);
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("FakeConnection: couldn't set the channel field: {}", t.toString());
        }
    }

    @Override
    public void setReadOnly() {
        // no-op — nothing to read-only on an inert channel
    }

    @Override
    public void send(Packet<?> packet, @Nullable ChannelFutureListener channelFutureListener, boolean bl) {
        // no-op — the vessel never sends a packet. Swallow it so Baritone/ServerPlayer writes go nowhere.
    }

    @Override
    public void handleDisconnection() {
        // no-op
    }

    @Override
    public void disconnect(Component component) {
        // no-op — the vessel can't be disconnected over the network
    }

    @Override
    public void disconnect(DisconnectionDetails disconnectionDetails) {
        // no-op — CRITICAL: the keepalive-timeout path calls this overload (not the Component
        // one). The real Connection.disconnect(DisconnectionDetails) closes the channel, which
        // fires channelInactive → onDisconnect → PlayerList.remove → the vessel vanishes ~15s
        // after spawn (one keepalive interval). Swallow it so the inert connection never closes
        // and the server never drops the vessel. (Carpet's FakeClientConnection does the same.)
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener packetListener) {
        // no-op — never handshakes
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T packetListener) {
        // no-op — never receives inbound traffic
    }
}
