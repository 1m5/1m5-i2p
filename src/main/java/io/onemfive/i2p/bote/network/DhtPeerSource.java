package io.onemfive.i2p.bote.network;

import java.util.Collection;

import net.i2p.data.Destination;

/**
 * An interface through which a DHT implementation can
 * get additional peers.
 */
public interface DhtPeerSource {

    Collection<Destination> getPeers();
}
