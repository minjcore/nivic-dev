package dev.nivic.payment;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Periodically runs intent TTL expiry (daemon thread). */
public final class PaymentIntentExpiryScheduler implements AutoCloseable {

  private final ScheduledExecutorService exec;
  private final Runnable task;

  public PaymentIntentExpiryScheduler(Runnable task, long initialDelay, long period, TimeUnit unit) {
    this.task = Objects.requireNonNull(task, "task");
    this.exec =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "payment-intent-expiry");
              t.setDaemon(true);
              return t;
            });
    exec.scheduleAtFixedRate(task, initialDelay, period, unit);
  }

  @Override
  public void close() {
    exec.shutdown();
  }
}
