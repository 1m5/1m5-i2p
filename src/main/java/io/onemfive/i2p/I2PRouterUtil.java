package io.onemfive.i2p;

import io.onemfive.core.OneMFiveAppContext;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;

import java.io.File;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * TODO: Add Description
 *
 * @author objectorange
 */
public class I2PRouterUtil {

    private static final Logger LOG = Logger.getLogger(I2PRouterUtil.class.getName());

    public static Router getGlobalI2PRouter(Properties properties, boolean autoStart) {
        Router globalRouter = null;
        RouterContext routerContext = RouterContext.listContexts().get(0);
        if(routerContext != null) {
            globalRouter = routerContext.router();
            if(globalRouter == null) {
                LOG.info("Instantiating I2P Router...");
                File baseDir = OneMFiveAppContext.getInstance().getBaseDir();
                String baseDirPath = baseDir.getAbsolutePath();
                System.setProperty("i2p.dir.base", baseDirPath);
                System.setProperty("i2p.dir.config", baseDirPath);
                System.setProperty("wrapper.logfile", baseDirPath + "/wrapper.log");
                globalRouter = new Router(properties);
            }
            if(autoStart && !globalRouter.isAlive()) {
                LOG.info("Starting I2P Router...");
                globalRouter.setKillVMOnEnd(false);
                globalRouter.runRouter();
            }
        }
        return globalRouter;
    }

}
