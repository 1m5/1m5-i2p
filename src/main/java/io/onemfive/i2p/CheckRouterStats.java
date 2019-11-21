package io.onemfive.i2p;

import io.onemfive.core.util.tasks.TaskRunner;
import io.onemfive.sensors.SensorTask;

public class CheckRouterStats extends SensorTask {

    private I2PSensor sensor;

    public CheckRouterStats(String taskName, TaskRunner taskRunner, I2PSensor sensor) {
        super(taskName, taskRunner);
        super.periodicity = 60 * 1000L; // Every 60 seconds
        this.sensor = sensor;
    }

    @Override
    public Boolean execute() {
        sensor.checkRouterStats();
        return true;
    }
}
