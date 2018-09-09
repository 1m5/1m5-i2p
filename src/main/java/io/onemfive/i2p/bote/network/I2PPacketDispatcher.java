package io.onemfive.i2p.bote.network;

import io.onemfive.i2p.bote.Util;
import io.onemfive.i2p.bote.packet.CommunicationPacket;
import io.onemfive.i2p.bote.packet.I2PBotePacket;
import io.onemfive.i2p.bote.packet.MalformedCommunicationPacket;
import io.onemfive.i2p.bote.packet.MalformedPacketException;

import java.util.ArrayList;
import java.util.List;

import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.datagram.I2PDatagramDissector;
import net.i2p.client.datagram.I2PInvalidDatagramException;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * An {@link I2PSessionMuxedListener} that receives datagrams from the I2P network
 * and notifies {@link PacketListener}s.
 */
public class I2PPacketDispatcher implements I2PSessionMuxedListener {
    private Log log = new Log(I2PPacketDispatcher.class);
    private List<PacketListener> packetListeners;

    public I2PPacketDispatcher() {
        packetListeners = new ArrayList<PacketListener>();
    }

    public void addPacketListener(PacketListener listener) {
        synchronized(packetListeners) {
            packetListeners.add(listener);
        }
    }

    public void removePacketListener(PacketListener listener) {
        synchronized(packetListeners) {
            packetListeners.remove(listener);
        }
    }

    private void firePacketReceivedEvent(CommunicationPacket packet, Destination sender) {
        synchronized(packetListeners) {
            for (PacketListener listener: packetListeners)
                listener.packetReceived(packet, sender, System.currentTimeMillis());
        }
    }

    // I2PSessionMuxedListener implementation follows

    @Override
    public void reportAbuse(I2PSession session, int severity) {
    }

    @Override
    public void messageAvailable(I2PSession session, int msgId, long size, int proto, int fromPort, int toPort) {
        if (proto == I2PSession.PROTO_DATAGRAM)
            messageAvailable(session, msgId, size);
    }

    @Override
    public void messageAvailable(I2PSession session, int msgId, long size) {
        byte[] msg = new byte[0];
        try {
            msg = session.receiveMessage(msgId);
        } catch (I2PSessionException e) {
            log.error("Can't get new message from I2PSession.", e);
        }
        if (msg == null) {
            log.error("I2PSession returned a null message: msgId=" + msgId + ", size=" + size + ", " + session);
            return;
        }

        I2PDatagramDissector datagramDissector = new I2PDatagramDissector();
        try {
            datagramDissector.loadI2PDatagram(msg);
            datagramDissector.verifySignature();   // TODO keep this line or remove it?
            byte[] payload = datagramDissector.extractPayload();
            Destination sender = datagramDissector.getSender();

            dispatchPacket(payload, sender);
        }
        catch (DataFormatException e) {
            log.error("Invalid datagram received.", e);
        }
        catch (I2PInvalidDatagramException e) {
            log.error("Datagram failed verification.", e);
        }
        catch (Exception e) {
            log.error("Error processing datagram.", e);
        }
    }

    /**
     * Creates a packet from a byte array and fires listeners.
     * @param packetData
     * @param sender
     */
    private void dispatchPacket(byte[] packetData, Destination sender) {
        CommunicationPacket packet;
        try {
            packet = CommunicationPacket.createPacket(packetData);
            logPacket(packet, sender);
            firePacketReceivedEvent(packet, sender);
        } catch (MalformedPacketException e) {
            log.warn("Ignoring unparseable packet.", e);
            firePacketReceivedEvent(new MalformedCommunicationPacket(), sender);
        }
    }

    private void logPacket(I2PBotePacket packet, Destination sender) {
        String senderHash = Util.toShortenedBase32(sender);
        log.debug("I2P packet received: [" + packet + "] Sender: [" + senderHash + "], notifying " + packetListeners.size() + " PacketListeners.");
    }

    @Override
    public void errorOccurred(I2PSession session, String message, Throwable error) {
        log.error("Router says: " + message, error);
    }

    @Override
    public void disconnected(I2PSession session) {
        log.warn("I2P session disconnected.");
    }
}
