package co.eci.snake.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Represents a single snake: its body positions, movement direction, and
 * alive/death state.
 *
 * <p>All methods that access {@code body} are {@code synchronized} on {@code this}
 * to prevent data races between the runner thread (writer) and the Swing EDT
 * (reader). {@code direction}, {@code alive}, and {@code deathNanos} use
 * {@code volatile} for cheap lock-free reads in hot paths.
 */
public final class Snake {

    private final Deque<Position> body = new ArrayDeque<>();
    private volatile Direction direction;
    private int maxLength = 5;

    private volatile boolean alive = true;
    private volatile long deathNanos = -1;

    private Snake(Position start, Direction dir) {
        body.addFirst(start);
        this.direction = dir;
    }

    /**
     * Factory method — creates a snake at the given position facing {@code dir}.
     *
     * @param x   column on the board.
     * @param y   row on the board.
     * @param dir initial direction.
     */
    public static Snake of(int x, int y, Direction dir) {
        return new Snake(new Position(x, y), dir);
    }

    /** @return the current movement direction (volatile read). */
    public Direction direction() { return direction; }

    /**
     * Attempts to change direction, ignoring 180° reversals.
     * Synchronized because both the runner thread and the EDT call this method.
     *
     * @param dir requested new direction.
     */
    public synchronized void turn(Direction dir) {
        if ((direction == Direction.UP    && dir == Direction.DOWN)  ||
            (direction == Direction.DOWN  && dir == Direction.UP)    ||
            (direction == Direction.LEFT  && dir == Direction.RIGHT) ||
            (direction == Direction.RIGHT && dir == Direction.LEFT)) {
            return;
        }
        this.direction = dir;
    }

    /**
     * @return the current head position.
     * Only called from {@code Board.step()} (which already holds the board lock),
     * but synchronized here to prevent a concurrent read by the EDT via
     * {@link #snapshot()}.
     */
    public synchronized Position head() { return body.peekFirst(); }

    /**
     * Returns a consistent defensive copy of the body for rendering.
     * The EDT iterates the copy without holding the lock.
     */
    public synchronized Deque<Position> snapshot() { return new ArrayDeque<>(body); }

    /** @return current body length (used for pause statistics). */
    public synchronized int length() { return body.size(); }

    /**
     * Advances the snake by adding {@code newHead} and trimming the tail if not growing.
     * Called exclusively from {@code Board.step()}.
     *
     * @param newHead position of the new head.
     * @param grow    {@code true} if the snake ate a mouse and should grow.
     */
    public synchronized void advance(Position newHead, boolean grow) {
        body.addFirst(newHead);
        if (grow) maxLength++;
        while (body.size() > maxLength) body.removeLast();
    }

    /**
     * @return {@code true} if this snake is still alive (volatile read, no lock).
     */
    public boolean isAlive() { return alive; }

    /**
     * Marks the snake as dead and records the death timestamp.
     * Uses double-checked locking: the outer volatile read avoids lock
     * acquisition in the common case; the inner block writes both fields atomically.
     */
    public void die() {
        if (!alive) return;
        synchronized (this) {
            if (!alive) return;
            alive      = false;
            deathNanos = System.nanoTime();
        }
    }

    /**
     * @return the {@link System#nanoTime()} timestamp of death,
     *         or {@code -1} if the snake is still alive.
     */
    public long deathNanos() { return deathNanos; }

    /**
     * Checks whether the given position is currently occupied by any cell of
     * this snake's body. Used inside {@code Board.step()} for collision detection.
     *
     * @param p position to check.
     * @return {@code true} if {@code p} is in the body.
     */
    public synchronized boolean containsPosition(Position p) {
        return body.contains(p);
    }
}
