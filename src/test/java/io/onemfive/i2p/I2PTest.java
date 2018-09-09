package io.onemfive.i2p;

import io.onemfive.core.util.Wait;
import io.onemfive.data.*;
import io.onemfive.data.util.DLC;
import io.onemfive.sensors.SensorStatus;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.util.concurrent.CountDownLatch;

/**
 * TODO: Add Description
 *
 * @author objectorange
 */
public class I2PTest {

    private static I2PSensor sensor;

    private static CountDownLatch lock;

    @BeforeClass
    public static void startUp() {
//        sensor = new I2PSensor(null, Envelope.Sensitivity.MEDIUM, 100);
//        sensor.start(null);
//        try {
//            Thread.sleep(3000);
//        } catch (InterruptedException e) {
//
//        }
    }

//    public void testSend() {
//        long maxWaitMs = 10 * 60 * 1000; // 10 minutes
//        long periodicWaitMs = 30 * 1000; // 30 seconds
//        long currentWaitMs = 0;
//        while(currentWaitMs < maxWaitMs || sensor.getStatus() == SensorStatus.NETWORK_CONNECTED) {
//            if(sensor.getStatus() == SensorStatus.NETWORK_CONNECTED) {
//                Envelope e = Envelope.documentFactory();
//                DLC.addContent("Hello World",e);
//                sensor.send(e);
//            }
//            Wait.aMs(periodicWaitMs);
//            currentWaitMs += periodicWaitMs;
//        }
//    }

    @AfterClass
    public static void tearDown() {
//        sensor.shutdown();
    }

}
