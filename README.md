# Snake Race — ARSW Lab #2 (Java 21, Virtual Threads)

**Escuela Colombiana de Ingeniería – Arquitecturas de Software**  
Laboratorio de programación concurrente: condiciones de carrera, sincronización y colecciones seguras.

---

## Requisitos

- **JDK 21** (Temurin recomendado)
- **Maven 3.9+**
- SO: Windows, macOS o Linux

---

## Cómo ejecutar

```bash
mvn clean verify
mvn -q -DskipTests exec:java -Dsnakes=4
```

- `-Dsnakes=N` → inicia el juego con **N** serpientes (por defecto 2).
- **Controles**:
  - **Flechas**: serpiente **0** (Jugador 1).
  - **WASD**: serpiente **1** (si existe).
  - **Espacio** o botón **Action**: Pausar / Reanudar.

---

## Reglas del juego (resumen)

- **N serpientes** corren de forma autónoma (cada una en su propio hilo).
- **Ratones**: al comer uno, la serpiente **crece** y aparece un **nuevo obstáculo**.
- **Obstáculos**: si la cabeza entra en un obstáculo hay **rebote**.
- **Colisiones**: si la cabeza choca con el cuerpo de otra serpiente o con su **propio cuerpo**, la serpiente **muere** y desaparece del tablero.
- **Teletransportadores** (flechas rojas): entrar por uno te **saca por su par**.
- **Rayos (Turbo)**: al pisarlos, la serpiente obtiene **velocidad aumentada** temporal.
- Movimiento con **wrap-around** (el tablero "se repite" en los bordes).

---

# Part II — Lab Report: Concurrent SnakeRace

## 1. Thread Architecture

The application creates **N virtual threads** (Java 21 virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`), one per snake, each running a `SnakeRunner` instance. Every runner loops through three steps: it optionally turns the snake with `maybeTurn()` → calls `board.step(snake, allSnakes)` to move the snake, check body collisions against all alive snakes, and resolve board events → then sleeps for the configured interval (80 ms normally, 40 ms in turbo mode). All runners share the same `Board` instance as a shared monitor, so each call to `board.step()` acquires the board lock → performs the full move atomically → releases the lock before the next runner can enter.

In parallel, the **Swing EDT** fires every 60 ms triggered by `GameClock` → calls `SwingUtilities.invokeLater(gamePanel::repaint)` → `paintComponent()` reads each `snake.snapshot()` to draw the bodies on screen. The EDT never modifies the board or any snake body; it only reads snapshots.

The `PauseControl` monitor acts as a third shared object. Runners call `pauseControl.sleepOrPause(ms)` instead of `Thread.sleep(ms)`, which allows the UI thread to block all runners by calling `pauseControl.pause()` from the button handler.

---

## 2. Data Races Found and Their Solution

### 2.1 Race on `Snake.body` — critical

| | |
|---|---|
| **Structure** | `Deque<Position> body` — an `ArrayDeque` inside each snake |
| **Writers** | Runner thread → `snake.advance()` (called inside `board.step()`) |
| **Readers** | Swing EDT → `snake.snapshot()` inside `paintComponent()` |
| **Risk** | `ArrayDeque` is **not thread-safe**. Concurrent read and write produce `ConcurrentModificationException` or visual tearing (partially updated body) |

**Fix:** `synchronized` on the `Snake` object itself for every method that accesses `body`:

```java
public synchronized Position head()            { return body.peekFirst(); }
public synchronized void    advance(...)       { body.addFirst(newHead); ... }
public synchronized Deque<Position> snapshot() { return new ArrayDeque<>(body); }
public synchronized int     length()           { return body.size(); }
```

`snapshot()` returns a **defensive copy**: the EDT iterates over its own local copy and releases the lock immediately, keeping contention minimal.

---

### 2.2 Race on `Snake.turn()` — minor

| | |
|---|---|
| **Field** | `volatile Direction direction` |
| **Writers** | Runner thread (`randomTurn()`) **and** EDT (player keyboard) simultaneously |
| **Risk** | The read → check 180° → write sequence is not atomic even though `direction` is `volatile`. Under a race, both threads can read the same old value, pass the reversal filter, and produce a 180° flip |

**Fix:** `synchronized` on `this` in `turn()`:

```java
public synchronized void turn(Direction dir) {
    if ((direction == UP && dir == DOWN) || ...) return;
    this.direction = dir;
}
```

The check-then-act is now atomic. This does not create new lock cycles because the EDT calls `turn()` and `snapshot()` at different moments, never while holding the `Board` lock.

---

### 2.3 Race on `Snake.alive` / `deathNanos` — single write

| | |
|---|---|
| **Fields** | `volatile boolean alive`, `volatile long deathNanos` |
| **Writer** | Runner thread when calling `snake.die()` upon colliding with any snake body (`HIT_SNAKE`) |
| **Risk** | Both fields must be written atomically: if the EDT reads `alive=false` while `deathNanos` is still `-1`, the pause statistics are incoherent |

**Fix:** double-checked locking with `volatile` + inner `synchronized` block:

```java
public void die() {
    if (!alive) return;              // cheap volatile read, no lock
    synchronized (this) {
        if (!alive) return;          // re-check inside the lock
        alive      = false;
        deathNanos = System.nanoTime();
    }
}
```

`alive` stays `volatile` so that `isAlive()`, called in the runner's hot loop, remains a cheap lock-free read.

---

## 3. Unsafe Collections and How They Were Protected

| Collection | Class | Original problem | Fix applied |
|---|---|---|---|
| `Snake.body` | `ArrayDeque` | Write (runner) and read (EDT) without protection → CME possible | `synchronized` on all access methods; `snapshot()` returns a defensive copy |
| `Board.mice`, `Board.obstacles`, `Board.turbo` | `HashSet` | Multiple runners modify while EDT reads for painting | `step()` was already `synchronized` on `Board`; getters return `new HashSet<>(...)` |
| `Board.teleports` | `HashMap` | Same scenario as the sets | `teleports()` returns `new HashMap<>(...)` |
| `SnakeApp.snakes` | `ArrayList` | Accessed from EDT and runners | Written only in the constructor **before** any thread starts. The _happens-before_ guarantee of `Thread.start()` ensures correct visibility; no additional synchronization needed |

---

## 4. Active Waits Eliminated and Mechanism Used

### 4.1 Runners with no pause mechanism — main problem

**Original situation:** `SnakeRunner.run()` used `Thread.sleep(80)`. Pressing "Pause" stopped the `GameClock` repaints, but runners **kept executing game steps** — the pause was visual only, not real.

**Fix — `PauseControl` class:**

A single shared monitor (`PauseControl`) replaces `Thread.sleep()` with `sleepOrPause()`:

```java
// SnakeRunner — replaces Thread.sleep(sleep):
pauseControl.sleepOrPause(sleep);
```

```java
// PauseControl
public synchronized void sleepOrPause(long millis) throws InterruptedException {
    if (paused) {
        while (paused) wait();   // already paused → block without consuming CPU
        return;
    }
    wait(millis);                // normal tick sleep (equivalent to Thread.sleep)
    while (paused) wait();       // if paused during sleep → block
}

public synchronized void pause() {
    paused = true;
    notifyAll();  // wake runners sleeping in wait(ms)
}

public synchronized void resume() {
    paused = false;
    notifyAll();  // release runners blocked in wait()
}
```

When the user pauses, `notifyAll()` wakes runners sleeping in `wait(millis)`. They check `while(paused)` → `true` and enter `wait()` with no timeout, **blocking without consuming CPU**. The pause takes effect within less than one game cycle (< 80 ms).

**No lost wakeups:** the runner evaluates `while (paused)` inside the `synchronized` block **before** calling `wait()`. If `resume()` arrives before the runner enters `wait()`, the runner sees `paused=false` and does not block. The signal is never lost because the condition check and the wait are atomic with respect to the same monitor.

---

### 4.2 `GameClock` — low-frequency polling

**Situation:** the `GameClock` scheduler fires every 60 ms even when paused, running `if (state.get() == RUNNING) tick.run()`.

This is **not busy-waiting** in the strict sense — the OS blocks the scheduler thread for 60 ms between firings; there is no tight loop. It is accepted because the period is long (16 firings per second) and the cost of each skipped tick is a single atomic read. Furthermore, `clock.pause()` is always called alongside `pauseControl.pause()`, so the tick skips its work immediately.

---

## 5. Critical Regions — Definition and Minimum Scope Justification

### 5.1 `Board.step()` — lock on `Board`

**What it protects:** the atomic read and write of the full board state (mice, obstacles, turbo, teleports) and the snake body collision check in a single step: detecting board and snake collisions, consuming mice/turbo, adding new elements, and advancing the snake head. The signature is `step(Snake snake, List<Snake> allSnakes)` — all alive snake bodies are checked inside the same synchronized region.

**Why this scope:** if the region were split, another runner could move its head to the same mouse cell or the same body cell between operations (TOCTOU race). Holding the board lock for the entire step also ensures that two runners can never detect the same collision simultaneously — whichever gets the lock first moves safely; the second one then sees the first snake's new head position. The minimum scope is exactly **one complete step**.

**Why not wider:** the lock does not need to be held between steps. Each `step()` call is self-contained.

---

### 5.2 `Snake.advance()` / `snapshot()` / `head()` / `length()` / `containsPosition()` — lock on `Snake`

**What it protects:** prevents the EDT from seeing the snake body in a partial state (e.g., new head already added but tail not yet removed). Also ensures that `containsPosition()`, called inside `board.step()` to detect snake-body collisions, always reads a consistent body snapshot.

**Why this scope:** only the `body` deque access. The fields `direction`, `alive`, and `deathNanos` have their own independent protection (volatile / DCL). There is no reason to widen the lock.

**Lock ordering — no deadlock:** the runner thread always acquires locks in the order `Board` first (entering `board.step()`) → then `Snake` (calling `snake.head()` and `snake.advance()` inside). The EDT acquires only the `Snake` lock (for `snapshot()` and `turn()`) or only the `Board` lock (for getters like `obstacles()`, which return a defensive copy and release immediately). `PauseControl` is always acquired independently, never while holding `Board` or `Snake`. Because no thread ever holds `Snake` and then tries to acquire `Board`, there is no lock cycle → deadlock is impossible.

---

### 5.3 `Snake.turn()` — lock on `Snake`

**What it protects:** the check-then-act on `direction` (verify no 180° reversal and then write).

**Why this scope:** only the `turn()` method. `advance()` and `snapshot()` are independent and do not need to coincide with `turn()`.

---

### 5.4 `PauseControl` — lock on `PauseControl`

**What it protects:** the condition variable `paused` and the associated `wait()`/`notifyAll()` operations.

**Why this scope:** `wait()` releases the lock while blocking, so multiple runners can sleep on the same monitor simultaneously without contending with each other. The lock is held briefly only when waking up to re-evaluate `while(paused)`.

---

## 6. UI: Start / Pause / Resume and Statistics

### Button flow

The game starts with `PauseControl` initialized as `paused=true`, so all runners block inside `sleepOrPause()` immediately after being submitted to the executor. The button initially shows **Start**.

When the user clicks **Start** → `pauseControl.resume()` unblocks all waiting runners and `clock.start()` begins the repaint loop → the button changes to **Pause**. Clicking **Pause** → `pauseControl.pause()` signals all runners to block and `clock.pause()` stops repaints → the button changes to **Resume** and the statistics bar is populated. Clicking **Resume** → `pauseControl.resume()` wakes all waiting runners and `clock.resume()` restarts repaints → the button returns to **Pause** and the statistics bar is cleared.

### Statistics on pause

When paused, the bottom bar displays:

```
PAUSED  |  Longest alive: Snake #2 (14 cells)  |  First to die: Snake #0
```

| Statistic | How it is computed |
|---|---|
| **Longest alive snake** | Filters `snakes` by `snake.isAlive()` (volatile read), compares `snake.length()` (synchronized), reports the index and length of the maximum |
| **First to die** | Filters by `!snake.isAlive()`, compares `snake.deathNanos()` (volatile read), reports the index with the smallest timestamp |

**Consistency without tearing:** every `snake.length()` and `snake.snapshot()` call is atomic (synchronized on the snake object). The EDT will never see a partially updated body. The values may reflect a state up to one game step before the exact pause click, which is acceptable for a game UI.
