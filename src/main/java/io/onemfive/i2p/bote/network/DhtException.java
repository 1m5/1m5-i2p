package io.onemfive.i2p.bote.network;

public class DhtException extends Exception {
    private static final long serialVersionUID = -1286128818859245017L;

    public DhtException(String message) {
        super(message);
    }

    public DhtException(String message, Throwable cause) {
        super(message, cause);
    }
}
