package dev.nivic.cli;

import static io.undertow.servlet.Servlets.servlet;

import dev.nivic.sevlet.SevletWalletPayloadServlet;
import dev.nivic.web.RequestMiddlewareFilter;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.FilterInfo;
import jakarta.servlet.DispatcherType;

/**
 * Dev server with everything in-memory — no PostgreSQL required.
 *
 * <p>Usage: {@code mvn exec:java -Dexec.mainClass="dev.nivic.cli.DevServer"} or {@code java -cp
 * target/classes:target/dependency/* dev.nivic.cli.DevServer}</p>
 */
public final class DevServer {

  private DevServer() {}

  public static void main(String[] args) throws Exception {
    DeploymentInfo servletBuilder =
        Servlets.deployment()
            .setClassLoader(DevServer.class.getClassLoader())
            .setContextPath("/")
            .setDeploymentName("sevlet-wallet-dev.war")
            .addServlets(
                servlet(
                        "SevletWalletPayloadServlet",
                        SevletWalletPayloadServlet.class)
                    .addMapping("/sevlet/wallet/payload")
                    .addInitParam("midSecretMode", "skip")
                    .addInitParam("idempotencyStorage", "memory")
                    .addInitParam("ledgerStorage", "memory")
                    .addInitParam("journalStorage", "memory")
                    .addInitParam("paymentLedgerStorage", "memory")
                    .addInitParam("walletDisruptorRingSize", "0"))
            .addFilter(
                new FilterInfo(
                    "RequestMiddlewareFilter",
                    RequestMiddlewareFilter.class))
            .addFilterUrlMapping(
                "RequestMiddlewareFilter",
                "/*",
                DispatcherType.REQUEST);

    DeploymentManager manager =
        Servlets.defaultContainer().addDeployment(servletBuilder);
    manager.deploy();
    HttpHandler handler = manager.start();

    PathHandler pathHandler =
        Handlers.path()
            .addPrefixPath("/", handler);

    var server =
        io.undertow.Undertow.builder()
            .addHttpListener(8080, "0.0.0.0")
            .setHandler(pathHandler)
            .build();

    server.start();

    System.out.println();
    System.out.println("  Nivic Dev Server (in-memory, no DB)");
    System.out.println("  -----------------------------------");
    System.out.println("  POST  http://localhost:8080/sevlet/wallet/payload");
    System.out.println("  WAL:  " + System.getProperty("java.io.tmpdir") + "sevlet-wallet.wal");
    System.out.println();
    System.out.println("  Press Ctrl+C to stop.");
    System.out.println();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  System.out.println("Shutting down...");
                  server.stop();
                }));
  }
}
