package io.onemfive.i2p.bote.email;

import javax.activation.DataHandler;

public interface Attachment {
    String getFileName();
    DataHandler getDataHandler();
    boolean clean();
}
