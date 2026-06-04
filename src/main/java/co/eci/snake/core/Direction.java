package co.eci.snake.core;

/**
 * Cardinal movement directions with their corresponding (dx, dy) deltas.
 * Y increases downward (screen coordinates).
 */
public enum Direction {
  UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

  /** Column delta per step. */
  public final int dx;
  /** Row delta per step. */
  public final int dy;

  Direction(int dx, int dy) { this.dx = dx; this.dy = dy; }
}
