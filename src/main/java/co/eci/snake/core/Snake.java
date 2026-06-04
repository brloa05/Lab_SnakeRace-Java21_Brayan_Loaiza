package co.eci.snake.core;

import java.util.ArrayDeque;
import java.util.Deque;

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

    public static Snake of(int x, int y, Direction dir) {
        return new Snake(new Position(x, y), dir);
    }

    public Direction direction() { return direction; }

    /**
     * Sincronizado: el EDT (teclas) y el runner (giros aleatorios) llaman a este
     * método desde hilos distintos; sin sync el par read+write sobre direction no
     * es atómico y puede producir una inversión de 180°.
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
     * Solo se llama desde Board.step() (ya protegido por synchronized(board)).
     * Sincronizamos también aquí para mantener el orden de locks board -> snake
     * y evitar que snapshot() del hilo EDT vea el deque a medias.
     */
    public synchronized Position head() { return body.peekFirst(); }

    /**
     * Devuelve una copia defensiva del cuerpo.
     * Sincronizado sobre this para no entrar en conflicto con advance().
     */
    public synchronized Deque<Position> snapshot() { return new ArrayDeque<>(body); }

    /** Longitud actual; usada para estadísticas al pausar. */
    public synchronized int length() { return body.size(); }

    /**
     * Avanza la cabeza a newHead y, opcionalmente, crece.
     * Sincronizado sobre this: la misma región que snapshot(), por eso nunca
     * habrá una vista parcial del deque desde el hilo de pintura.
     */
    public synchronized void advance(Position newHead, boolean grow) {
        body.addFirst(newHead);
        if (grow) maxLength++;
        while (body.size() > maxLength) body.removeLast();
    }

    /** Lectura volatile: sin costo de lock para el hot-path del runner. */
    public boolean isAlive() { return alive; }

    /**
     * Marca la serpiente como muerta. Double-checked locking sobre volatile
     * para escribir alive y deathNanos de forma atómica y solo una vez.
     */
    public void die() {
        if (!alive) return;
        synchronized (this) {
            if (!alive) return;
            alive = false;
            deathNanos = System.nanoTime();
        }
    }

    /** Instante de muerte (nanoTime), o -1 si aún vive. */
    public long deathNanos() { return deathNanos; }

    /** Returns true if the given position is currently inside this snake's body. */
    public synchronized boolean containsPosition(Position p) {
        return body.contains(p);
    }
}
