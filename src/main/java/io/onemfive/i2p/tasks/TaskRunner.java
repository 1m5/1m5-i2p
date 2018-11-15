package io.onemfive.i2p.tasks;

import io.onemfive.core.util.AppThread;
import io.onemfive.data.DID;
import io.onemfive.data.Envelope;
import io.onemfive.data.Peer;
import io.onemfive.data.util.DLC;
import io.onemfive.i2p.I2PSensor;
import io.onemfive.sensors.SensorRequest;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Runs I2P Tasks.
 *
 * @author objectorange
 */
public class TaskRunner extends AppThread {

    private static final Logger LOG = Logger.getLogger(TaskRunner.class.getName());

    public enum Status {Running, Stopping, Shutdown}
    private static final short timeBetweenRunsMinutes = 10;

    private Status status = Status.Shutdown;
    private I2PSensor sensor;
    private boolean isSeed = false;
    private DID seedDID;
    private DID localDID;
    private Properties properties;
    private Map<String,Long> checks = new HashMap<>();

    public TaskRunner(I2PSensor sensor, DID localDID, DID seedDID, Properties properties) {
        this.sensor = sensor;
        this.localDID = localDID;
        this.seedDID = seedDID;
        this.properties = properties;
        isSeed = (localDID != null
                && seedDID != null
                && localDID.getPeer(Peer.NETWORK_I2P) != null
                && seedDID.getPeer(Peer.NETWORK_I2P) != null
                && localDID.getPeer(Peer.NETWORK_I2P).getAddress() != null
                && seedDID.getPeer(Peer.NETWORK_I2P).getAddress() != null
                && localDID.getPeer(Peer.NETWORK_I2P).getAddress().equals(seedDID.getPeer(Peer.NETWORK_I2P).getAddress()));
    }

    @Override
    public void run() {
        status = Status.Running;
        LOG.info("I2PSensor Task Runner running...");
        while(status == Status.Running) {
            LOG.info("I2P Router Status: " + sensor.getRouterStatus().name());
            // Now send a message to the seed node to verify it's online
            long now = System.currentTimeMillis();
            if(!isSeed) {
                String connectedVerifier = "1M5-ConnectionVerify:" + now;
                checks.put(connectedVerifier, now);
                LOG.info("Sending: " + connectedVerifier);
                LOG.info("  To: "+seedDID.getPeer(Peer.NETWORK_I2P).getAddress());
                LOG.info("  From: "+localDID.getPeer(Peer.NETWORK_I2P).getAddress());
                SensorRequest r = new SensorRequest();
                r.to = seedDID;
                r.from = localDID;
                Envelope e = Envelope.documentFactory();
                DLC.addData(SensorRequest.class, r, e);
                DLC.addContent(connectedVerifier, e);
                sensor.send(e);
            }
            try {
                synchronized (this) {
                    this.wait(timeBetweenRunsMinutes * 60 * 1000);
                }
            } catch (InterruptedException ex) {
            }
        }
        LOG.info("Task Runner Stopped.");
        status = Status.Shutdown;
    }

    public void verify(String code) {
        Long begin = checks.get(code);
        if(begin == null)
            LOG.info("Message received ("+code+") not a connection verifier. Ignoring.");
        else {
            LOG.info("Received connection verifier " + code + " response in " + ((System.currentTimeMillis() - begin)/1000) + " seconds.");
            checks.remove(code);
        }
    }

    public void shutdown() {
        status = Status.Stopping;
        LOG.info("Signaled Task Runner to shutdown after all tasks complete...");
    }

}
