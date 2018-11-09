package io.onemfive.i2p.tasks;

import io.onemfive.core.util.AppThread;
import io.onemfive.data.DID;
import io.onemfive.data.Envelope;
import io.onemfive.data.util.DLC;
import io.onemfive.i2p.I2PSensor;
import io.onemfive.sensors.SensorRequest;

import java.security.SecureRandom;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Runs CDNGraphTasks based on Timers.
 *
 * @author objectorange
 */
public class TaskRunner extends AppThread {

    private static final Logger LOG = Logger.getLogger(TaskRunner.class.getName());

    public enum Status {Running, Stopping, Shutdown}
    private static final short timeBetweenRunsMinutes = 10;
    private String connectedVerifier;

    private final Object lock = new Object();
    private Status status = Status.Shutdown;
    private I2PSensor sensor;
    private DID localDID;
    private Properties properties;
    private long lastSent;
    private boolean lastSentReceived = true;

    public TaskRunner(I2PSensor sensor, DID localDID, Properties properties) {
        this.sensor = sensor;
        this.localDID = localDID;
        this.properties = properties;
    }

    @Override
    public void run() {
        status = Status.Running;
        LOG.info("I2PSensor Task Runner running...");
        while(status == Status.Running) {
            try {
                LOG.info("Sleeping for "+timeBetweenRunsMinutes+" minutes..");
                synchronized (this) {
                    this.wait(timeBetweenRunsMinutes * 60 * 1000);
                }
            } catch (InterruptedException ex) {
            }
            if(lastSentReceived) {
                LOG.info("Awoke, begin building connection verifier request...");
                lastSent = System.currentTimeMillis();
                // Now send a message to itself to verify it's online
                connectedVerifier = new SecureRandom().nextLong() + "";
                SensorRequest r = new SensorRequest();
                r.to = localDID;
                r.from = localDID;
                Envelope e = Envelope.documentFactory();
                DLC.addData(SensorRequest.class, r, e);
                DLC.addContent(connectedVerifier, e);
                sensor.send(e);
                lastSentReceived = false;
                LOG.info("I2PSensor connection verifier request sent, sleeping...");
            } else {
                LOG.info("Awoke but last sent not received yet.");
            }
        }
        LOG.info("Task Runner Stopped.");
        status = Status.Shutdown;
    }

    public void verify(String code) {
        long now = System.currentTimeMillis();
        boolean verified = connectedVerifier.equals(code);
        LOG.info("Received connection verifier response in "+ (now-lastSent) + " milliseconds. Connection "
                +((verified)?"verified":"not verified."));
        lastSentReceived = true;
    }

    public void shutdown() {
        status = Status.Stopping;
        LOG.info("Signaled Task Runner to shutdown after all tasks complete...");
    }

}
