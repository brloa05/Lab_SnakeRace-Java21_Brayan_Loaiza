package co.eci.snake.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared game board holding all mutable game-world state: mice, obstacles,
 * turbo items, and teleporter pairs.
 *
 * <p>Every method that reads or modifies state is {@code synchronized} on
 * {@code this} so that concurrent {@link Snake} runners see a consistent view.
 * Getters return defensive copies so callers (e.g. the Swing EDT) can iterate
 * safely without holding the board lock.
 */
public final class Board {

  private final int width;
  private final int height;

  private final Set<Position>          mice      = new HashSet<>();
  private final Set<Position>          obstacles = new HashSet<>();
  private final Set<Position>          turbo     = new HashSet<>();
  private final Map<Position, Position> teleports = new HashMap<>();

  /** Possible outcomes of a single {@link #step} call. */
  public enum MoveResult {
    MOVED, ATE_MOUSE, HIT_OBSTACLE, ATE_TURBO, TELEPORTED, HIT_SNAKE
  }

  /**
   * Creates a board of the given dimensions and populates it with mice,
   * obstacles, turbo items, and teleporter pairs.
   *
   * @param width  number of columns (must be positive).
   * @param height number of rows (must be positive).
   */
  public Board(int width, int height) {
    if (width <= 0 || height <= 0) throw new IllegalArgumentException("Board dimensions must be positive");
    this.width = width;
    this.height = height;
    for (int i=0;i<6;i++) mice.add(randomEmpty());
    for (int i=0;i<4;i++) obstacles.add(randomEmpty());
    for (int i=0;i<3;i++) turbo.add(randomEmpty());
    createTeleportPairs(2);
  }

  /** @return board width in cells. */
  public int width()  { return width; }

  /** @return board height in cells. */
  public int height() { return height; }

  /** @return a snapshot copy of the current mouse positions. */
  public synchronized Set<Position>          mice()      { return new HashSet<>(mice); }

  /** @return a snapshot copy of the current obstacle positions. */
  public synchronized Set<Position>          obstacles() { return new HashSet<>(obstacles); }

  /** @return a snapshot copy of the current turbo item positions. */
  public synchronized Set<Position>          turbo()     { return new HashSet<>(turbo); }

  /** @return a snapshot copy of the current teleporter map. */
  public synchronized Map<Position, Position> teleports() { return new HashMap<>(teleports); }

  /**
   * Executes one full move for {@code snake} atomically:
   * <ol>
   *   <li>Computes the next head position (with wrap-around).</li>
   *   <li>Returns {@link MoveResult#HIT_OBSTACLE} (bounce) if blocked.</li>
   *   <li>Applies teleport if applicable.</li>
   *   <li>Returns {@link MoveResult#HIT_SNAKE} if the next cell is occupied
   *       by any alive snake's body (including own body).</li>
   *   <li>Consumes mouse/turbo, advances the body, and spawns new items.</li>
   * </ol>
   *
   * <p>The entire method is {@code synchronized} on {@code Board} to prevent
   * TOCTOU races between concurrent runners (e.g. two snakes eating the same
   * mouse or colliding with each other undetected).
   *
   * @param snake     the snake to move.
   * @param allSnakes list of all snakes for body-collision detection.
   * @return the result of the move.
   */
  public synchronized MoveResult step(Snake snake, List<Snake> allSnakes) {
    Objects.requireNonNull(snake, "snake");
    var head = snake.head();
    var dir  = snake.direction();
    Position next = new Position(head.x() + dir.dx, head.y() + dir.dy).wrap(width, height);

    if (obstacles.contains(next)) return MoveResult.HIT_OBSTACLE;

    boolean teleported = false;
    if (teleports.containsKey(next)) {
      next = teleports.get(next);
      teleported = true;
    }

    // Die on collision with any alive snake body (own or other)
    for (Snake other : allSnakes) {
      if (other.isAlive() && other.containsPosition(next)) {
        return MoveResult.HIT_SNAKE;
      }
    }

    boolean ateMouse = mice.remove(next);
    boolean ateTurbo = turbo.remove(next);

    snake.advance(next, ateMouse);

    if (ateMouse) {
      mice.add(randomEmpty());
      obstacles.add(randomEmpty());
      if (ThreadLocalRandom.current().nextDouble() < 0.2) turbo.add(randomEmpty());
    }

    if (ateTurbo)   return MoveResult.ATE_TURBO;
    if (ateMouse)   return MoveResult.ATE_MOUSE;
    if (teleported) return MoveResult.TELEPORTED;
    return MoveResult.MOVED;
  }

  /** Creates {@code pairs} bidirectional teleporter pairs at random empty cells. */
  private void createTeleportPairs(int pairs) {
    for (int i=0;i<pairs;i++) {
      Position a = randomEmpty();
      Position b = randomEmpty();
      teleports.put(a, b);
      teleports.put(b, a);
    }
  }

  /**
   * Returns a random position not already occupied by any game item.
   * Gives up after {@code width * height * 2} attempts to avoid infinite loops
   * on heavily populated boards.
   */
  private Position randomEmpty() {
    var rnd = ThreadLocalRandom.current();
    Position p;
    int guard = 0;
    do {
      p = new Position(rnd.nextInt(width), rnd.nextInt(height));
      guard++;
      if (guard > width*height*2) break;
    } while (mice.contains(p) || obstacles.contains(p) || turbo.contains(p) || teleports.containsKey(p));
    return p;
  }
}
