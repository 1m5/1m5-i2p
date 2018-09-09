package io.onemfive.i2p.bote.service;

import io.onemfive.i2p.bote.email.Email;

public interface OutboxListener {

    void emailSent(Email email);
}
