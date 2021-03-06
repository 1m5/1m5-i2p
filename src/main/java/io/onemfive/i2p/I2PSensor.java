package io.onemfive.i2p;

import io.onemfive.core.Config;
import io.onemfive.core.ServiceRequest;
import io.onemfive.core.notification.NotificationService;
import io.onemfive.core.util.tasks.TaskRunner;
import io.onemfive.data.*;
import io.onemfive.data.util.DLC;
import io.onemfive.sensors.*;
import net.i2p.I2PException;
import net.i2p.client.*;
import net.i2p.client.datagram.I2PDatagramDissector;
import net.i2p.client.datagram.I2PDatagramMaker;
import net.i2p.client.datagram.I2PInvalidDatagramException;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterLaunch;
import net.i2p.util.*;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Provides an API for I2P Router as a Sensor.
 * I2P in 1M5 is used as Message-Oriented-Middleware (MOM)
 * supporting real-time anonymous messaging.
 *
 * @author objectorange
 */
public class I2PSensor extends BaseSensor implements I2PSessionMuxedListener {

    /**
     * 1 = ElGamal-2048 / DSA-1024
     * 2 = ECDH-256 / ECDSA-256
     * 3 = ECDH-521 / ECDSA-521
     * 4 = NTRUEncrypt-1087 / GMSS-512
     */
    protected static int ElGamal2048DSA1024 = 1;
    protected static int ECDH256ECDSA256 = 2;
    protected static int ECDH521EDCSA521 = 3;
    protected static int NTRUEncrypt1087GMSS512 = 4;

    private static final Logger LOG = Logger.getLogger(I2PSensor.class.getName());

    private static final String DEST_KEY_FILE_NAME = "local_dest.key";

    protected Properties properties;

    // I2P Router and Context
    private File i2pDir;
    private RouterContext routerContext;
    protected Router router;
    protected CommSystemFacade.Status i2pRouterStatus;

    private String i2pBaseDir;
    protected String i2pAppDir;

    private I2PSession i2pSession;
    private I2PSocketManager socketManager;

    // I2CP parameters allowed in the config file
    // Undefined parameters use the I2CP defaults
    private static final String PARAMETER_I2CP_DOMAIN_SOCKET = "i2cp.domainSocket";
    private static final List<String> I2CP_PARAMETERS = Arrays.asList(new String[] {
            PARAMETER_I2CP_DOMAIN_SOCKET,
            "inbound.length",
            "inbound.lengthVariance",
            "inbound.quantity",
            "inbound.backupQuantity",
            "outbound.length",
            "outbound.lengthVariance",
            "outbound.quantity",
            "outbound.backupQuantity",
    });

    private Long startTimeBlockedMs = 0L;
    private static final Long BLOCK_TIME_UNTIL_RESTART = 3 * 60 * 1000L; // 4 minutes
    private Integer restartAttempts = 0;
    private static final Integer RESTART_ATTEMPTS_UNTIL_HARD_RESTART = 3;
    private boolean isTest = false;

    public I2PSensor() {super();}

    public I2PSensor(SensorManager sensorManager, Envelope.Sensitivity sensitivity, Integer priority) {
        super(sensorManager, sensitivity, priority);
    }

    @Override
    public String[] getOperationEndsWith() {
        return new String[]{".i2p"};
    }

    @Override
    public String[] getURLBeginsWith() {
        return new String[]{"i2p"};
    }

    @Override
    public String[] getURLEndsWith() {
        return new String[]{".i2p"};
    }


    /**
     * Sends UTF-8 content to a Destination using I2P.
     * @param envelope Envelope containing SensorRequest as data.
     *                 To DID must contain base64 encoded I2P destination key.
     * @return boolean was successful
     */
    @Override
    public boolean send(Envelope envelope) {
        LOG.info("Sending I2P Message...");
        SensorRequest request = (SensorRequest)DLC.getData(SensorRequest.class,envelope);
        if(request == null){
            LOG.warning("No SensorRequest in Envelope.");
            request.errorCode = ServiceRequest.REQUEST_REQUIRED;
            return false;
        }
        NetworkPeer toPeer = request.to.getPeer(NetworkPeer.Network.I2P.name());
        if(toPeer == null) {
            LOG.warning("No Peer for I2P found in toDID while sending to I2P.");
            request.errorCode = SensorRequest.TO_PEER_REQUIRED;
            return false;
        }
        if(!NetworkPeer.Network.I2P.name().equals((toPeer.getNetwork()))) {
            LOG.warning("I2P requires an I2P Peer.");
            request.errorCode = SensorRequest.TO_PEER_WRONG_NETWORK;
            return false;
        }
        LOG.info("Content to send: "+request.content);
        if(request.content == null) {
            LOG.warning("No content found in Envelope while sending to I2P.");
            request.errorCode = SensorRequest.NO_CONTENT;
            return false;
        }
        if(request.content.length() > 31500) {
            // Just warn for now
            // TODO: Split into multiple serialized datagrams
            LOG.warning("Content longer than 31.5kb. May have issues.");
        }

        try {
            Destination toDestination = i2pSession.lookupDest(toPeer.getAddress());
            if(toDestination == null) {
                LOG.warning("I2P Peer To Destination not found.");
                request.errorCode = SensorRequest.TO_PEER_NOT_FOUND;
                return false;
            }
            I2PDatagramMaker m = new I2PDatagramMaker(i2pSession);
            byte[] payload = m.makeI2PDatagram(request.content.getBytes());
            if(i2pSession.sendMessage(toDestination, payload, I2PSession.PROTO_UNSPECIFIED, I2PSession.PORT_ANY, I2PSession.PORT_ANY)) {
                LOG.info("I2P Message sent.");
                return true;
            } else {
                LOG.warning("I2P Message sending failed.");
                request.errorCode = SensorRequest.SENDING_FAILED;
                return false;
            }
        } catch (I2PSessionException e) {
            String errMsg = "Exception while sending I2P message: " + e.getLocalizedMessage();
            LOG.warning(errMsg);
            request.exception = e;
            request.errorMessage = errMsg;
            if("Already closed".equals(e.getLocalizedMessage())) {
                LOG.info("I2P Connection closed. Could be no internet access or getting blocked. Assume blocked for re-route. If not blocked, I2P will automatically re-establish connection when network access returns.");
                updateStatus(SensorStatus.NETWORK_BLOCKED);
            }
            return false;
        }
    }

    /**
     * Incoming
     * @param e
     * @return
     */
    @Override
    public boolean reply(Envelope e) {
        sensorManager.sendToBus(e);
        return true;
    }

    /**
     * Will be called only if you register via
     * setSessionListener() or addSessionListener().
     * And if you are doing that, just use I2PSessionListener.
     *
     * If you register via addSessionListener(),
     * this will be called only for the proto(s) and toport(s) you register for.
     *
     * After this is called, the client should call receiveMessage(msgId).
     * There is currently no method for the client to reject the message.
     * If the client does not call receiveMessage() within a timeout period
     * (currently 30 seconds), the session will delete the message and
     * log an error.
     *
     * @param session session to notify
     * @param msgId message number available
     * @param size size of the message - why it's a long and not an int is a mystery
     */
    @Override
    public void messageAvailable(I2PSession session, int msgId, long size) {
        LOG.info("Message received by I2P Sensor...");
        byte[] msg = new byte[0];
        try {
            msg = session.receiveMessage(msgId);
        } catch (I2PSessionException e) {
            LOG.warning("Can't get new message from I2PSession: " + e.getLocalizedMessage());
            return;
        }
        if (msg == null) {
            LOG.warning("I2PSession returned a null message: msgId=" + msgId + ", size=" + size + ", " + session);
            return;
        }

        try {
            LOG.info("Loading I2P Datagram...");
            I2PDatagramDissector d = new I2PDatagramDissector();
            d.loadI2PDatagram(msg);
            LOG.info("I2P Datagram loaded.");
            byte[] payload = d.getPayload();
            String strPayload = new String(payload);
            LOG.info("Getting sender as I2P Destination...");
            Destination sender = d.getSender();
            String address = sender.toBase64();
            String fingerprint = sender.getHash().toBase64();
            LOG.info("Received I2P Message:\n    From: " + address +"\n    Content: " + strPayload);
//            taskRunner.verify(strPayload);
//            if(!isTest) {
                Envelope e = Envelope.eventFactory(EventMessage.Type.TEXT);
                NetworkPeer from = new NetworkPeer(NetworkPeer.Network.I2P.name());
                from.setAddress(address);
                from.setFingerprint(fingerprint);
                DID did = new DID();
                did.addPeer(from);
                e.setDID(did);
                EventMessage m = (EventMessage) e.getMessage();
                m.setName(fingerprint);
                m.setMessage(strPayload);
                DLC.addRoute(NotificationService.class, NotificationService.OPERATION_PUBLISH, e);
                LOG.info("Sending Event Message to Notification Service...");
                sensorManager.sendToBus(e);
//            }
        } catch (DataFormatException e) {
            e.printStackTrace();
            LOG.warning("Invalid datagram received: " + e.getLocalizedMessage());
        } catch (I2PInvalidDatagramException e) {
            e.printStackTrace();
            LOG.warning("Datagram failed verification: " + e.getLocalizedMessage());
        } catch (Exception e) {
            e.printStackTrace();
            LOG.severe("Error processing datagram: " + e.getLocalizedMessage());
        }
    }

    /**
     * Instruct the client that the given session has received a message
     *
     * Will be called only if you register via addMuxedSessionListener().
     * Will be called only for the proto(s) and toport(s) you register for.
     *
     * After this is called, the client should call receiveMessage(msgId).
     * There is currently no method for the client to reject the message.
     * If the client does not call receiveMessage() within a timeout period
     * (currently 30 seconds), the session will delete the message and
     * log an error.
     *
     * Only one listener is called for a given message, even if more than one
     * have registered. See I2PSessionDemultiplexer for details.
     *
     * @param session session to notify
     * @param msgId message number available
     * @param size size of the message - why it's a long and not an int is a mystery
     * @param proto 1-254 or 0 for unspecified
     * @param fromPort 1-65535 or 0 for unspecified
     * @param toPort 1-65535 or 0 for unspecified
     */
    @Override
    public void messageAvailable(I2PSession session, int msgId, long size, int proto, int fromPort, int toPort) {
//        if (proto == I2PSession.PROTO_DATAGRAM || proto == I2PSession.PROTO_STREAMING)
            messageAvailable(session, msgId, size);
//        else
//            LOG.warning("Received unhandled message with proto="+proto+" and id="+msgId);
    }

    /**
     * Instruct the client that the session specified seems to be under attack
     * and that the client may wish to move its destination to another router.
     * All registered listeners will be called.
     *
     * Unused. Not fully implemented.
     *
     * @param i2PSession session to report abuse to
     * @param severity how bad the abuse is
     */
    @Override
    public void reportAbuse(I2PSession i2PSession, int severity) {
        LOG.warning("I2P Session reporting abuse. Severity="+severity);
        reportRouterStatus();
    }

    /**
     * Notify the client that the session has been terminated.
     * All registered listeners will be called.
     *
     * @param session session to report disconnect to
     */
    @Override
    public void disconnected(I2PSession session) {
        LOG.warning("I2P Session reporting disconnection.");
        reportRouterStatus();
    }

    /**
     * Notify the client that some throwable occurred.
     * All registered listeners will be called.
     *
     * @param session session to report error occurred
     * @param message message received describing error
     * @param throwable throwable thrown during error
     */
    @Override
    public void errorOccurred(I2PSession session, String message, Throwable throwable) {
        LOG.severe("Router says: "+message+": "+throwable.getLocalizedMessage());
        reportRouterStatus();
    }

    public File getDirectory() {
        if(i2pDir==null) {
            i2pDir = new File(i2pBaseDir);
        }
        return i2pDir;
    }

    /**
     * Sets up a {@link I2PSession}, using the I2P destination stored on disk or creating a new I2P
     * destination if no key file exists.
     */
    private void initializeSession() throws I2PSessionException {
        LOG.info("Initializing I2P Session, Starting I2P Sensor....");
        updateStatus(SensorStatus.STARTING);
        Properties sessionProperties = new Properties();
        // set tunnel names
        sessionProperties.setProperty("inbound.nickname", "I2PSensor");
        sessionProperties.setProperty("outbound.nickname", "I2PSensor");
        sessionProperties.putAll(getI2CPOptions());

        // read the local destination key from the key file if it exists
        File destinationKeyFile = getDestinationKeyFile();
        FileReader fileReader = null;
        try {
            fileReader = new FileReader(destinationKeyFile);
            char[] destKeyBuffer = new char[(int)destinationKeyFile.length()];
            fileReader.read(destKeyBuffer);
            byte[] localDestinationKey = Base64.decode(new String(destKeyBuffer));
            ByteArrayInputStream inputStream = new ByteArrayInputStream(localDestinationKey);
            socketManager = I2PSocketManagerFactory.createDisconnectedManager(inputStream, null, 0, sessionProperties);
        } catch (IOException e) {
            LOG.info("Destination key file doesn't exist or isn't readable." + e);
        } catch (I2PSessionException e) {
            // Won't happen, inputStream != null
            e.printStackTrace();
            LOG.warning(e.getLocalizedMessage());
        } finally {
            if (fileReader != null) {
                try {
                    fileReader.close();
                } catch (IOException e) {
                    LOG.warning("Error closing file: " + destinationKeyFile.getAbsolutePath() + ": " + e);
                }
            }
        }

        // if the local destination key can't be read or is invalid, create a new one
        if (socketManager == null) {
            LOG.info("Creating new local destination key");
            try {
                ByteArrayOutputStream arrayStream = new ByteArrayOutputStream();
                I2PClientFactory.createClient().createDestination(arrayStream);
                byte[] localDestinationKey = arrayStream.toByteArray();

                LOG.info("Creating I2P Socket Manager...");
                ByteArrayInputStream inputStream = new ByteArrayInputStream(localDestinationKey);
                socketManager = I2PSocketManagerFactory.createDisconnectedManager(inputStream, null, 0, sessionProperties);
                LOG.info("I2P Socket Manager created.");

                destinationKeyFile = new SecureFile(destinationKeyFile.getAbsolutePath());
                if (destinationKeyFile.exists()) {
                    File oldKeyFile = new File(destinationKeyFile.getPath() + "_backup");
                    if (!destinationKeyFile.renameTo(oldKeyFile))
                        LOG.warning("Cannot rename destination key file <" + destinationKeyFile.getAbsolutePath() + "> to <" + oldKeyFile.getAbsolutePath() + ">");
                } else if (!destinationKeyFile.createNewFile()) {
                    LOG.warning("Cannot create destination key file: <" + destinationKeyFile.getAbsolutePath() + ">");
                }

                BufferedWriter fileWriter = new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(destinationKeyFile)));
                try {
                    fileWriter.write(Base64.encode(localDestinationKey));
                }
                finally {
                    fileWriter.close();
                }
            } catch (I2PException e) {
                LOG.warning("Error creating local destination key: " + e.getLocalizedMessage());
            } catch (IOException e) {
                LOG.warning("Error writing local destination key to file: " + e.getLocalizedMessage());
            }
        }

        i2pSession = socketManager.getSession();
        // Throws I2PSessionException if the connection fails
        LOG.info("I2P Session connecting...");
        long start = System.currentTimeMillis();
        i2pSession.connect();
        long end = System.currentTimeMillis();
        long durationMs = end - start;
        LOG.info("I2P Session connected. Took "+(durationMs/1000)+" seconds.");

        Destination localDestination = i2pSession.getMyDestination();
        String address = localDestination.toBase64();
        String fingerprint = localDestination.calculateHash().toBase64();
        LOG.info("I2PSensor Local destination key in base64: " + address);
        LOG.info("I2PSensor Local destination fingerprint (hash) in base64: " + fingerprint);

        i2pSession.addMuxedSessionListener(this, I2PSession.PROTO_ANY, I2PSession.PORT_ANY);

        NetworkPeer np = new NetworkPeer(NetworkPeer.Network.I2P.name());
        np.setAddress(address);
        np.setFingerprint(fingerprint);

        if(!isTest) {
            // Publish local I2P address
            LOG.info("Publishing local I2P Network Peer key...");
            Envelope e = Envelope.eventFactory(EventMessage.Type.STATUS_DID);
            EventMessage m = (EventMessage) e.getMessage();
            m.setName(fingerprint);
            m.setMessage(np);
            DLC.addRoute(NotificationService.class, NotificationService.OPERATION_PUBLISH, e);
            sensorManager.sendToBus(e);
        }
        if(taskRunner==null) {
            taskRunner = new TaskRunner();
        }
        taskRunner.addTask(new CheckRouterStats("I2PStatusCheck", taskRunner, this));
        if(taskRunner.getStatus() != TaskRunner.Status.Running) {
            new Thread(taskRunner).start();
        }
    }

    @Override
    public boolean start(Properties p) {
        LOG.info("Initializing I2P Sensor...");
        properties = p;
        updateStatus(SensorStatus.INITIALIZING);
        isTest = "true".equals(properties.getProperty("1m5.sensors.i2p.isTest"));
        // I2P Sensor Starting
        LOG.info("Loading I2P properties...");
        properties = p;
        // Set up I2P Directories within sensors directory
        i2pBaseDir = properties.getProperty("1m5.dir.sensors") + "/i2p";
        i2pDir = new File(i2pBaseDir);
        if(!i2pDir.exists()) {
            if (!i2pDir.mkdir()) {
                LOG.severe("Unable to create I2P base directory: " + i2pBaseDir + "; exiting...");
                return false;
            }
        }
        System.setProperty("i2p.dir.base",i2pBaseDir);
        properties.setProperty("i2p.dir.base",i2pBaseDir);
        properties.setProperty("1m5.dir.sensors.i2p",i2pBaseDir);
        // Config Directory
        String i2pConfigDir = i2pBaseDir + "/config";
        File i2pConfigFolder = new File(i2pConfigDir);
        if(!i2pConfigFolder.exists())
            if(!i2pConfigFolder.mkdir())
                LOG.warning("Unable to create I2P config directory: " +i2pConfigDir);
        if(i2pConfigFolder.exists()) {
            System.setProperty("i2p.dir.config",i2pConfigDir);
            properties.setProperty("i2p.dir.config",i2pConfigDir);
        }
        // Router Directory
        String i2pRouterDir = i2pBaseDir + "/router";
        File i2pRouterFolder = new File(i2pRouterDir);
        if(!i2pRouterFolder.exists())
            if(!i2pRouterFolder.mkdir())
                LOG.warning("Unable to create I2P router directory: "+i2pRouterDir);
        if(i2pRouterFolder.exists()) {
            System.setProperty("i2p.dir.router",i2pRouterDir);
            properties.setProperty("i2p.dir.router",i2pRouterDir);
        }
        // PID Directory
        String i2pPIDDir = i2pBaseDir + "/pid";
        File i2pPIDFolder = new File(i2pPIDDir);
        if(!i2pPIDFolder.exists())
            if(!i2pPIDFolder.mkdir())
                LOG.warning("Unable to create I2P PID directory: "+i2pPIDDir);
        if(i2pPIDFolder.exists()) {
            System.setProperty("i2p.dir.pid",i2pPIDDir);
            properties.setProperty("i2p.dir.pid",i2pPIDDir);
        }
        // Log Directory
        String i2pLogDir = i2pBaseDir + "/log";
        File i2pLogFolder = new File(i2pLogDir);
        if(!i2pLogFolder.exists())
            if(!i2pLogFolder.mkdir())
                LOG.warning("Unable to create I2P log directory: "+i2pLogDir);
        if(i2pLogFolder.exists()) {
            System.setProperty("i2p.dir.log",i2pLogDir);
            properties.setProperty("i2p.dir.log",i2pLogDir);
        }
        // App Directory
        i2pAppDir = i2pBaseDir + "/app";
        File i2pAppFolder = new File(i2pAppDir);
        if(!i2pAppFolder.exists())
            if(!i2pAppFolder.mkdir())
                LOG.warning("Unable to create I2P app directory: "+i2pAppDir);
        if(i2pAppFolder.exists()) {
            System.setProperty("i2p.dir.app", i2pAppDir);
            properties.setProperty("i2p.dir.app", i2pAppDir);
        }

        // Running Internal I2P Router
        System.setProperty(I2PClient.PROP_TCP_HOST, "internal");
        System.setProperty(I2PClient.PROP_TCP_PORT, "internal");

        // Merge router.config files
        mergeRouterConfig(null);

        // Certificates
        File certDir = new File(i2pBaseDir, "certificates");
        if(!certDir.exists())
            if(!certDir.mkdir()) {
                LOG.severe("Unable to create certificates directory in: "+i2pBaseDir+"; exiting...");
                return false;
            }
        File seedDir = new File(certDir, "reseed");
        if(!seedDir.exists())
            if(!seedDir.mkdir()) {
                LOG.severe("Unable to create "+i2pBaseDir+"/certificates/reseed directory; exiting...");
                return false;
            }
        File sslDir = new File(certDir, "ssl");
        if(!sslDir.exists())
            if(!sslDir.mkdir()) {
                LOG.severe("Unable to create "+i2pBaseDir+"/certificates/ssl directory; exiting...");
                return false;
            }

        File seedCertificates = new File(certDir, "reseed");
//        File[] allSeedCertificates = seedCertificates.listFiles();
//        if ( allSeedCertificates != null) {
//            for (File f : allSeedCertificates) {
//                LOG.info("Deleting old seed certificate: " + f);
//                FileUtil.rmdir(f, false);
//            }
//        }

        File sslCertificates = new File(certDir, "ssl");
//        File[] allSSLCertificates = sslCertificates.listFiles();
//        if ( allSSLCertificates != null) {
//            for (File f : allSSLCertificates) {
//                LOG.info("Deleting old ssl certificate: " + f);
//                FileUtil.rmdir(f, false);
//            }
//        }

        if(!copyCertificatesToBaseDir(seedCertificates, sslCertificates))
            return false;

        // Start I2P Router
        LOG.info("Launching I2P Router...");
        new Thread(new RouterStarter()).start();

        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(1);

        try {
            updateStatus(SensorStatus.WAITING);
            LOG.info("Waiting 3 minutes for I2P Router to warm up...");
            // TODO: Replace with wait time based on I2P router status to lower start up time
            startSignal.await(3, TimeUnit.MINUTES);
            LOG.info("I2P Router should be warmed up. Initializing session...");
            initializeSession();
            if(routerContext.commSystem().isInStrictCountry()) {
                LOG.warning("This peer is in a 'strict' country defined by I2P.");
            }
            if(routerContext.router().isHidden()) {
                LOG.warning("Router was placed in Hidden mode. 1M5 setting for hidden mode: "+properties.getProperty("hidden"));
            }
            doneSignal.countDown();
        } catch (InterruptedException e) {
            LOG.warning("Start interrupted, exiting");
            updateStatus(SensorStatus.ERROR);
            e.printStackTrace();
            return false;
        } catch (Exception e) {
            LOG.severe("Unable to start I2PSensor: "+e.getLocalizedMessage());
            updateStatus(SensorStatus.ERROR);
            e.printStackTrace();
            return false;
        }
        LOG.info("Started.");
        return true;
    }

    @Override
    public boolean pause() {
        return false;
    }

    @Override
    public boolean unpause() {
        return false;
    }

    @Override
    public boolean restart() {
        if(router==null) {
            router = routerContext.router();
        }
        if(router != null) {
            if(restartAttempts.equals(RESTART_ATTEMPTS_UNTIL_HARD_RESTART)) {
                LOG.info("Full restart of I2P Router...");
                if(!shutdown()) {
                    LOG.warning("Issues shutting down I2P Router. Will attempt to start regardless...");
                }
                if(!start(properties)) {
                    LOG.warning("Issues starting I2P Router.");
                    return false;
                } else {
                    LOG.info("Hard restart of I2P Router completed.");
                }
            } else {
                LOG.info("Soft restart of I2P Router...");
                updateStatus(SensorStatus.RESTARTING);
                router.restart();
                LOG.info("I2P Router soft restart completed.");
            }
            return true;
        } else {
            LOG.warning("Unable to restart I2P Router. Router instance not found in RouterContext.");
        }
        return false;
    }

    @Override
    public boolean shutdown() {
        updateStatus(SensorStatus.SHUTTING_DOWN);
        taskRunner.shutdown();
        new Thread(new RouterStopper()).start();
        return true;
    }

    @Override
    public boolean gracefulShutdown() {
        updateStatus(SensorStatus.GRACEFULLY_SHUTTING_DOWN);
        // will teardown in 11 minutes or less
        new Thread(new RouterGracefulStopper()).start();
        return true;
    }

    private class RouterStarter implements Runnable {
        public void run() {
            RouterLaunch.main(null);
            List<RouterContext> routerContexts = RouterContext.listContexts();
            routerContext = routerContexts.get(0);
            router = routerContext.router();
            // Override hidden mode even when in I2P defined 'strict' countries
            router.saveConfig(Router.PROP_HIDDEN, properties.getProperty("hidden"));
            router.setKillVMOnEnd(false);
            routerContext.addShutdownTask(new RouterStopper());
            // Hard code to INFO for now for troubleshooting; need to move to configuration
            routerContext.logManager().setDefaultLimit(Log.STR_INFO);
            routerContext.logManager().setFileSize(100000000); // 100 MB
        }
    }

    private class RouterStopper implements Runnable {
        public void run() {
            LOG.info("I2P router stopping...");
            try {
                if (i2pSession != null)
                    i2pSession.destroySession();
            } catch (I2PSessionException e) {
                LOG.warning("Can't destroy I2P session.: "+e.getLocalizedMessage());
            }

            if (socketManager != null)
                socketManager.destroySocketManager();

            if(router != null) {
                router.shutdown(Router.EXIT_HARD);
            }
            updateStatus(SensorStatus.SHUTDOWN);
            LOG.info("I2P router stopped.");
        }
    }

    private class RouterGracefulStopper implements Runnable {
        public void run() {
            LOG.info("I2P router gracefully stopping...");

            try {
                if (i2pSession != null)
                    i2pSession.destroySession();
            } catch (I2PSessionException e) {
                LOG.warning("Can't destroy I2P session.: "+e.getLocalizedMessage());
            }

            if (socketManager != null)
                socketManager.destroySocketManager();

            if(router != null) {
                router.shutdownGracefully(Router.EXIT_GRACEFUL);
            }
            updateStatus(SensorStatus.GRACEFULLY_SHUTDOWN);
            LOG.info("I2P router gracefully stopped.");
        }
    }

    private Properties getI2CPOptions() {
        Properties opts = new Properties();
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            if (I2CP_PARAMETERS.contains(entry.getKey()))
                opts.put(entry.getKey(), entry.getValue());
        }
        return opts;
    }

    private File getDestinationKeyFile() {
        return new File(i2pDir, DEST_KEY_FILE_NAME);
    }

    private void reportRouterStatus() {

        switch (getRouterStatus()) {
            case UNKNOWN:
                LOG.info("Testing I2P Network...");
                updateStatus(SensorStatus.NETWORK_CONNECTING);
                break;
            case IPV4_DISABLED_IPV6_UNKNOWN:
                LOG.info("IPV4 Disabled but IPV6 Testing...");
                updateStatus(SensorStatus.NETWORK_CONNECTING);
                break;
            case IPV4_FIREWALLED_IPV6_UNKNOWN:
                LOG.info("IPV4 Firewalled but IPV6 Testing...");
                updateStatus(SensorStatus.NETWORK_CONNECTING);
                break;
            case IPV4_SNAT_IPV6_UNKNOWN:
                LOG.info("IPV4 SNAT but IPV6 Testing...");
                updateStatus(SensorStatus.NETWORK_CONNECTING);
                break;
            case IPV4_UNKNOWN_IPV6_FIREWALLED:
                LOG.info("IPV6 Firewalled but IPV4 Testing...");
                updateStatus(SensorStatus.NETWORK_CONNECTING);
                break;
            case OK:
                LOG.info("Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateStatus(SensorStatus.NETWORK_CONNECTED);
                break;
            case IPV4_DISABLED_IPV6_OK:
                LOG.info("IPV4 Disabled but IPV6 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateStatus(SensorStatus.NETWORK_CONNECTED);
                break;
            case IPV4_FIREWALLED_IPV6_OK:
                LOG.info("IPV4 Firewalled but IPV6 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateStatus(SensorStatus.NETWORK_CONNECTED);
                break;
            case IPV4_SNAT_IPV6_OK:
                LOG.info("IPV4 SNAT but IPV6 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateStatus(SensorStatus.NETWORK_CONNECTED);
                break;
            case IPV4_UNKNOWN_IPV6_OK:
                LOG.info("IPV4 Testing but IPV6 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateStatus(SensorStatus.NETWORK_CONNECTED);
                break;
            case IPV4_OK_IPV6_FIREWALLED:
                LOG.info("IPV6 Firewalled but IPV4 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateStatus(SensorStatus.NETWORK_CONNECTED);
                break;
            case IPV4_OK_IPV6_UNKNOWN:
                LOG.info("IPV6 Testing but IPV4 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateStatus(SensorStatus.NETWORK_CONNECTED);
                break;
            case IPV4_DISABLED_IPV6_FIREWALLED:
                LOG.warning("IPV4 Disabled but IPV6 Firewalled. Connected to I2P network.");
                updateStatus(SensorStatus.NETWORK_CONNECTED);
                break;
            case DISCONNECTED:
                LOG.info("Disconnected from I2P Network.");
                updateStatus(SensorStatus.NETWORK_STOPPED);
                restart();
                break;
            case DIFFERENT:
                LOG.warning("Symmetric NAT: Error connecting to I2P Network.");
                updateStatus(SensorStatus.NETWORK_ERROR);
                break;
            case HOSED:
                LOG.warning("Unable to open UDP port for I2P - Port Conflict. Verify another instance of I2P is not running.");
                updateStatus(SensorStatus.NETWORK_PORT_CONFLICT);
                break;
            case REJECT_UNSOLICITED:
                LOG.warning("Blocked. Unable to connect to I2P network.");
                if(startTimeBlockedMs==0) {
                    startTimeBlockedMs = System.currentTimeMillis();
                    updateStatus(SensorStatus.NETWORK_BLOCKED);
                } else if((System.currentTimeMillis() - startTimeBlockedMs) > BLOCK_TIME_UNTIL_RESTART) {
                    restart();
                    startTimeBlockedMs = 0L; // Restart the clock to give it some time to connect
                } else {
                    updateStatus(SensorStatus.NETWORK_BLOCKED);
                }
                break;
            default: {
                LOG.warning("Not connected to I2P Network.");
                updateStatus(SensorStatus.NETWORK_STOPPED);
            }
        }
    }

    private CommSystemFacade.Status getRouterStatus() {
        return routerContext.commSystem().getStatus();
    }

    public void checkRouterStats() {
        if(i2pRouterStatus==null) {
            i2pRouterStatus = getRouterStatus();
        }
        reportRouterStatus();
        LOG.info("I2P Statistics:\n\tRouter Status: "+getRouterStatus().name());
    }

    /**
     *  Load defaults from internal router.config on classpath,
     *  then add props from i2pDir/router.config overriding any from internal router.config,
     *  then override these with the supplied overrides if not null which would likely come from 3rd party app (not yet supported),
     *  then write back to i2pDir/router.config.
     *
     *  @param overrides local overrides or null
     */
    private void mergeRouterConfig(Properties overrides) {
        Properties props = new OrderedProperties();
        File f = new File(i2pBaseDir,"router.config");
        boolean i2pBaseRouterConfigIsNew = false;
        if(!f.exists()) {
            if(!f.mkdir()) {
                LOG.warning("While merging router.config files, unable to create router.config in i2pBaseDirectory: "+i2pBaseDir);
            } else {
                i2pBaseRouterConfigIsNew = true;
            }
        }
        InputStream i2pBaseRouterConfig = null;
        try {
            props.putAll(Config.loadFromClasspath("router.config"));

            if(!i2pBaseRouterConfigIsNew) {
                i2pBaseRouterConfig = new FileInputStream(f);
                DataHelper.loadProps(props, i2pBaseRouterConfig);
            }

            // override with user settings
            if (overrides != null)
                props.putAll(overrides);

            DataHelper.storeProps(props, f);
        } catch (Exception e) {
            LOG.warning("Exception caught while merging router.config properties: "+e.getLocalizedMessage());
        } finally {
            if (i2pBaseRouterConfig != null) try {
                i2pBaseRouterConfig.close();
            } catch (IOException ioe) {
            }
        }
    }

    /**
     *  Copy all certificates found in resources/io/onemfive/core/sensors/i2p/bote/certificates
     *  into i2pBaseDir/certificates
     *
     *  @param reseedCertificates destination directory for reseed certificates
     *  @param sslCertificates destination directory for ssl certificates
     */
    private boolean copyCertificatesToBaseDir(File reseedCertificates, File sslCertificates) {
        final String path = "io/onemfive/i2p";
        // Android apps are doing this within their startup as unable to extract these files from jars
        if(!isTest) {
            if(!SystemVersion.isAndroid()) {
                // Other - extract as jar
                String jarPath = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
                final File jarFile = new File(jarPath);
                if (jarFile.isFile()) {
                    // called by a user of the 1M5 Core jar
                    try {
                        final JarFile jar = new JarFile(jarFile);
                        JarEntry entry;
                        File f = null;
                        final Enumeration<JarEntry> entries = jar.entries(); //gives ALL entries in jar
                        while (entries.hasMoreElements()) {
                            entry = entries.nextElement();
                            final String name = entry.getName();
                            if (name.startsWith(path + "/certificates/reseed/")) { //filter according to the path
                                if (!name.endsWith("/")) {
                                    String fileName = name.substring(name.lastIndexOf("/") + 1);
                                    LOG.info("fileName to save: " + fileName);
                                    f = new File(reseedCertificates, fileName);
                                }
                            }
                            if (name.startsWith(path + "/certificates/ssl/")) {
                                if (!name.endsWith("/")) {
                                    String fileName = name.substring(name.lastIndexOf("/") + 1);
                                    LOG.info("fileName to save: " + fileName);
                                    f = new File(sslCertificates, fileName);
                                }
                            }
                            if (f != null) {
                                boolean fileReadyToSave = false;
                                if (!f.exists() && f.createNewFile()) fileReadyToSave = true;
                                else if (f.exists() && f.delete() && f.createNewFile()) fileReadyToSave = true;
                                if (fileReadyToSave) {
                                    FileOutputStream fos = new FileOutputStream(f);
                                    byte[] byteArray = new byte[1024];
                                    int i;
                                    InputStream is = getClass().getClassLoader().getResourceAsStream(name);
                                    //While the input stream has bytes
                                    while ((i = is.read(byteArray)) > 0) {
                                        //Write the bytes to the output stream
                                        fos.write(byteArray, 0, i);
                                    }
                                    //Close streams to prevent errors
                                    is.close();
                                    fos.close();
                                    f = null;
                                } else {
                                    LOG.warning("Unable to save file from 1M5 jar and is required: " + name);
                                    return false;
                                }
                            }
                        }
                        jar.close();
                    } catch (IOException e) {
                        LOG.warning(e.getLocalizedMessage());
                        return false;
                    }
                }
            }
        } else {
            // called while testing in an IDE
            URL boteFolderURL = I2PSensor.class.getClassLoader().getResource(path);
            File boteResFolder = null;
            try {
                boteResFolder = new File(boteFolderURL.toURI());
            } catch (URISyntaxException e) {
                LOG.warning("Unable to access bote resource directory.");
                return false;
            }
            File[] boteResFolderFiles = boteResFolder.listFiles();
            File certResFolder = null;
            for (File f : boteResFolderFiles) {
                if ("certificates".equals(f.getName())) {
                    certResFolder = f;
                    break;
                }
            }
            if (certResFolder != null) {
                File[] folders = certResFolder.listFiles();
                for (File folder : folders) {
                    if ("reseed".equals(folder.getName())) {
                        File[] reseedCerts = folder.listFiles();
                        for (File reseedCert : reseedCerts) {
                            FileUtil.copy(reseedCert, reseedCertificates, true, false);
                        }
                    } else if ("ssl".equals(folder.getName())) {
                        File[] sslCerts = folder.listFiles();
                        for (File sslCert : sslCerts) {
                            FileUtil.copy(sslCert, sslCertificates, true, false);
                        }
                    }
                }
                return true;
            }
            return false;
        }
        return true;
    }

    public static void main(String[] args) {
        File f = new File(args[0]);
        if(!f.exists() && !f.mkdir()) {
            System.out.println("Unable to create directory "+args[0]);
            System.exit(-1);
        }
        Properties p = new Properties();
        p.setProperty("1m5.dir.base",args[0]);
        p.setProperty("1m5.sensors.i2p.isTest","true");
        I2PSensor s = new I2PSensor(null, Envelope.Sensitivity.HIGH, 100);
        s.start(p);
    }

}
