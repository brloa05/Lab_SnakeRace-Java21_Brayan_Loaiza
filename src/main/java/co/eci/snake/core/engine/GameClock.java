package co.eci.snake.core.engine;

import co.eci.snake.core.GameState;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives periodic game events (e.g. screen repaints) at a fixed rate.
 *
 * <p>The clock can be started once and then paused/resumed any number of times.
 * While paused, the scheduled task fires but skips execution — this is not
 * busy-waiting because the scheduler thread sleeps between firings.
 */
public final class GameClock implements AutoCloseable {

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final long periodMillis;
  private final Runnable tick;
  private final java.util.concurrent.atomic.AtomicReference<GameState> state =
      new AtomicReference<>(GameState.STOPPED);

  /**
   * @param periodMillis interval between ticks in milliseconds (must be &gt; 0).
   * @param tick         action executed on each tick while the clock is running.
   */
  public GameClock(long periodMillis, Runnable tick) {
    if (periodMillis <= 0) throw new IllegalArgumentException("periodMillis must be > 0");
    this.periodMillis = periodMillis;
    this.tick = java.util.Objects.requireNonNull(tick, "tick");
  }

  /** Starts the clock. Has no effect if already started. */
  public void start() {
    if (state.compareAndSet(GameState.STOPPED, GameState.RUNNING)) {
      scheduler.scheduleAtFixedRate(() -> {
        if (state.get() == GameState.RUNNING) tick.run();
      }, 0, periodMillis, TimeUnit.MILLISECONDS);
    }
  }

  /** Pauses tick execution without stopping the scheduler. */
  public void pause()  { state.set(GameState.PAUSED); }

  /** Resumes tick execution. */
  public void resume() { state.set(GameState.RUNNING); }

  /** Stops tick execution. */
  public void stop()   { state.set(GameState.STOPPED); }

  /** Shuts down the underlying scheduler. */
  @Override public void close() { scheduler.shutdownNow(); }
}
