package com.continuuity.gateway.runtime;

import com.continuuity.app.guice.LocationRuntimeModule;
import com.continuuity.common.conf.CConfiguration;
import com.continuuity.common.guice.ConfigModule;
import com.continuuity.common.guice.DiscoveryRuntimeModule;
import com.continuuity.common.guice.IOModule;
import com.continuuity.common.metrics.OverlordMetricsReporter;
import com.continuuity.data.operation.executor.remote.Constants;
import com.continuuity.data.runtime.DataFabricModules;
import com.continuuity.gateway.Gateway;
import com.continuuity.weave.zookeeper.RetryStrategies;
import com.continuuity.weave.zookeeper.ZKClientService;
import com.continuuity.weave.zookeeper.ZKClientServices;
import com.continuuity.weave.zookeeper.ZKClients;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Main is a simple class that allows us to launch the Gateway as a standalone
 * program. This is also where we do our runtime injection.
 * <p/>
 */
public class Main {

  private static final Logger LOG = LoggerFactory.getLogger(Main.class);

  /**
   * Our main method.
   *
   * @param args Our command line options
   */
  public static void main(String[] args) {
    // Load our configuration from our resource files
    CConfiguration configuration = CConfiguration.create();

    String zookeeper = configuration.get(Constants.CFG_ZOOKEEPER_ENSEMBLE);
    if (zookeeper == null) {
      LOG.error("No zookeeper quorum provided.");
      System.exit(1);
    }
    ZKClientService zkClientService =
      ZKClientServices.delegate(
        ZKClients.reWatchOnExpire(
          ZKClients.retryOnFailure(
            ZKClientService.Builder.of(zookeeper).build(),
            RetryStrategies.exponentialDelay(500, 2000, TimeUnit.MILLISECONDS)
          )
        ));
    zkClientService.startAndWait();

    // Set up our Guice injections
    Injector injector = Guice.createInjector(
        new GatewayModules().getDistributedModules(),
        new DataFabricModules().getDistributedModules(),
        new ConfigModule(configuration),
        new IOModule(),
        new LocationRuntimeModule().getDistributedModules(),
        new DiscoveryRuntimeModule(zkClientService).getDistributedModules()
        );

    // Get our fully wired Gateway
    Gateway theGateway = injector.getInstance(Gateway.class);

    // Now, initialize the Gateway
    try {

      // enable metrics for this JVM. Note this may only be done once
      // per JVM, hence we do it only in the gateway.Main.
      OverlordMetricsReporter.enable(1, TimeUnit.SECONDS, configuration);

      // Start the gateway!
      theGateway.start(null, configuration);

    } catch (Exception e) {
      LOG.error(e.toString(), e);
      System.exit(-1);
    } finally {
      zkClientService.stopAndWait();
    }
  }

} // End of Main

