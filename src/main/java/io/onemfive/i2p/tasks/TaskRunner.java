package io.onemfive.i2p.tasks;

import io.onemfive.core.util.AppThread;
import io.onemfive.data.DID;
import io.onemfive.data.NetworkPeer;
import io.onemfive.i2p.I2PSensor;

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
    private Properties properties;

    public TaskRunner(I2PSensor sensor, Properties properties) {
        this.sensor = sensor;
        this.properties = properties;
    }

    @Override
    public void run() {
        status = Status.Running;
        LOG.info("I2PSensor Task Runner running...");
        while(status == Status.Running) {
            sensor.checkRouterStats();
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

    public void shutdown() {
        status = Status.Stopping;
        LOG.info("Signaled Task Runner to shutdown after all tasks complete...");
    }

}
