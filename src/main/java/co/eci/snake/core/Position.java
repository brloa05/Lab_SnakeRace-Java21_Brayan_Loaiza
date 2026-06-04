package co.eci.snake.core;

/**
 * Immutable (x, y) coordinate on the board.
 * Equality and hashing are value-based (record semantics).
 */
public record Position(int x, int y) {

  /**
   * Returns the equivalent position wrapped within board bounds,
   * supporting negative coordinates for reverse wrap-around.
   *
   * @param width  board width.
   * @param height board height.
   * @return wrapped position in [0, width) × [0, height).
   */
  public Position wrap(int width, int height) {
    int nx = ((x % width)  + width)  % width;
    int ny = ((y % height) + height) % height;
    return new Position(nx, ny);
  }
}
