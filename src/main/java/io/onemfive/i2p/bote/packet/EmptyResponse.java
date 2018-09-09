package io.onemfive.i2p.bote.packet;

public class EmptyResponse extends DataPacket {

    @Override
    public byte[] toByteArray() {
        return new byte[0];
    }
}
