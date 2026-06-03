package dev.nivic.coa.query;

import com.lmax.disruptor.EventFactory;

/**
 * Factory for creating BalanceQueryEvent instances in the Disruptor ring buffer.
 * Pre-allocated events are reused (no GC pressure).
 */
public class BalanceQueryEventFactory implements EventFactory<BalanceQueryEvent> {

  @Override
  public BalanceQueryEvent newInstance() {
    return new BalanceQueryEvent();
  }
}
