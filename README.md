# Snake Race — ARSW Lab #2 (Java 21, Virtual Threads)

**Escuela Colombiana de Ingeniería – Arquitecturas de Software**  
Concurrent programming lab: race conditions, synchronization, and thread-safe collections.

---

## Requirements

- **JDK 21** (Temurin recommended)
- **Maven 3.9+**
- OS: Windows, macOS or Linux

---

## How to Run

```bash
mvn clean verify
mvn -q -DskipTests exec:java -Dsnakes=4
```

- `-Dsnakes=N` → launches the game with **N** snakes (default 2).
- **Controls**: Arrow keys → snake 0 · WASD → snake 1 · Space / button → Pause/Resume.

---

## Game Rules (summary)

- **N snakes** run autonomously, each in its own thread.
- **Mice**: eating one makes the snake **grow** and spawns a **new obstacle**.
- **Obstacles**: hitting one causes a **bounce** (random turn).
- **Collisions**: if a snake's head hits another snake's body or its **own body**, it **dies** and disappears from the board.
- **Teleporters** (red arrows): entering one exits through its pair.
- **Turbo (lightning)**: stepping on one grants a temporary **speed boost**.
- Movement with **wrap-around** (the board wraps at the edges).

---

# Part II — Lab Report: Concurrent SnakeRace

## 1. Thread Architecture

The application spawns **N virtual threads** (one per snake) using `Executors.newVirtualThreadPerTaskExecutor()`. Each thread runs a `SnakeRunner` instance that loops indefinitely while the snake is alive:

1. **`maybeTurn()`** — randomly decides whether to change direction (10 % chance normally, 5 % in turbo mode).
2. **`board.step(snake, allSnakes)`** — acquires the board monitor, resolves the full move (obstacle bounce, teleport, snake-body collision, mouse/turbo consumption, body advance), then releases the monitor.
3. **`pauseControl.sleepOrPause(ms)`** — replaces `Thread.sleep()`. If the game is running it sleeps for 80 ms (40 ms in turbo); if the game is paused it blocks on `wait()` until resumed.

In parallel, the **Swing Event Dispatch Thread (EDT)** fires every 60 ms via `GameClock`, calls `SwingUtilities.invokeLater(gamePanel::repaint)`, and paints the board by reading each `snake.snapshot()`. The EDT never modifies any game state; it only reads defensive copies.

Three monitors coordinate concurrency:
- **`Board`** — serialises all game-state mutations.
- **`Snake`** (per instance) — protects the body deque from concurrent reads/writes.
- **`PauseControl`** — implements pause/resume for all runners using `wait()`/`notifyAll()`.

---

## 2. Data Races Found and Their Solution

### 2.1 Race on `Snake.body` — critical

| | |
|---|---|
| **Structure** | `Deque<Position> body` — `ArrayDeque` inside each `Snake` |
| **Writer** | Runner thread → `snake.advance()` called inside `board.step()` |
| **Reader** | Swing EDT → `snake.snapshot()` inside `paintComponent()` |
| **Risk** | `ArrayDeque` is **not thread-safe**. A concurrent write and read causes `ConcurrentModificationException` or visual tearing (partially updated body drawn on screen) |

**Fix:** every method that touches `body` is declared `synchronized` on the `Snake` instance:

```java
public synchronized Position         head()            { return body.peekFirst(); }
public synchronized void             advance(...)      { body.addFirst(newHead); ... }
public synchronized Deque<Position>  snapshot()        { return new ArrayDeque<>(body); }
public synchronized int              length()          { return body.size(); }
public synchronized boolean          containsPosition(Position p) { return body.contains(p); }
```

`snapshot()` returns a **defensive copy** so the EDT iterates its own local list and releases the snake lock immediately, keeping contention minimal.

---

### 2.2 Race on `Snake.turn()` — minor

| | |
|---|---|
| **Field** | `volatile Direction direction` |
| **Writers** | Runner thread via `randomTurn()` **and** Swing EDT via player keyboard handler — simultaneously |
| **Risk** | The read → check 180° → write sequence is not atomic even though `direction` is `volatile`. Both threads can read the same stale value, both pass the reversal filter, and the last write wins — potentially producing a forbidden 180° flip |

**Fix:** `synchronized` on `this` inside `turn()` makes the entire check-then-act atomic:

```java
public synchronized void turn(Direction dir) {
    if ((direction == UP && dir == DOWN) || ...) return;
    this.direction = dir;
}
```

This does not introduce new lock cycles: the EDT calls `turn()` and `snapshot()` at different moments and never while holding the `Board` lock.

---

### 2.3 Race on `Snake.alive` / `deathNanos` — compound write

| | |
|---|---|
| **Fields** | `volatile boolean alive`, `volatile long deathNanos` |
| **Writer** | Runner thread via `snake.die()` upon `HIT_SNAKE` result |
| **Reader** | Swing EDT via `buildStats()` when the game is paused |
| **Risk** | Both fields must be written together atomically. If the EDT reads `alive=false` while `deathNanos` is still `-1`, the "first to die" statistic becomes incoherent |

**Fix:** double-checked locking with `volatile` + inner `synchronized` block:

```java
public void die() {
    if (!alive) return;           // cheap volatile fast-path, no lock acquired
    synchronized (this) {
        if (!alive) return;       // re-check inside the lock to avoid redundant writes
        alive      = false;
        deathNanos = System.nanoTime();
    }
}
```

`alive` remains `volatile` so that `isAlive()`, called on every iteration of the runner loop, stays a cheap lock-free read with no monitor overhead.

---

## 3. Unsafe Collections and How They Were Protected

| Collection | Class | Original problem | Fix applied |
|---|---|---|---|
| `Snake.body` | `ArrayDeque` | Concurrent write (runner) and read (EDT) — no protection | `synchronized` on all five body-access methods; `snapshot()` returns a defensive copy |
| `Board.mice`, `Board.obstacles`, `Board.turbo` | `HashSet` | Multiple runners call `step()` while EDT calls the getters for painting | `step()` already `synchronized` on `Board`; each getter returns `new HashSet<>(...)` |
| `Board.teleports` | `HashMap` | Runners read/write during `step()`; EDT reads in `paintComponent()` | `teleports()` returns `new HashMap<>(...)` — EDT always iterates a snapshot |
| `SnakeApp.snakes` | `ArrayList` | Read by runner threads and EDT after construction | Written exclusively in the constructor **before** any thread is started. Java's `Thread.start()` establishes a _happens-before_ edge, so all threads see the fully populated list without extra synchronization |

---

## 4. Active Waits Eliminated and Mechanism Used

### 4.1 Runner pause — main problem

**Original situation:** `SnakeRunner.run()` used `Thread.sleep(80)`. When the user pressed Pause, `GameClock` stopped triggering repaints but the runners **kept advancing the game state** — the pause was purely visual, not real.

**Fix — `PauseControl` class:**

A single shared monitor replaces `Thread.sleep()` with `sleepOrPause(millis)`, combining the sleep and the pause check into one atomic operation:

```java
public synchronized void sleepOrPause(long millis) throws InterruptedException {
    if (paused) {
        while (paused) wait();   // already paused: block immediately, release CPU
        return;
    }
    wait(millis);                // sleep for one game tick (replaces Thread.sleep)
    while (paused) wait();       // woken early by pause(): re-block until resume()
}

public synchronized void pause() {
    paused = true;
    notifyAll();   // wakes runners sleeping in wait(millis) so they reach wait() fast
}

public synchronized void resume() {
    paused = false;
    notifyAll();   // releases all runners blocked in wait()
}
```

When the user pauses, `notifyAll()` immediately wakes any runner sleeping in `wait(millis)`. Those runners re-check `while (paused)` → `true` and enter `wait()` with no timeout, **releasing the CPU completely**. The game is fully paused within at most one game tick (< 80 ms).

**No lost wakeups:** the `while (paused)` guard is evaluated inside the `synchronized` block before each `wait()` call. If `resume()` is called before a runner enters `wait()`, the runner sees `paused=false` and continues without blocking. The signal cannot be missed because the condition check and the wait are atomic with respect to the same monitor.

**`PauseControl` starts paused** (`new PauseControl(true)`): runners block immediately on submission and only unblock when the user clicks **Start**, ensuring no movement happens before the user initiates the game.

---

### 4.2 `GameClock` — low-frequency scheduled check

The `GameClock` uses a `ScheduledExecutorService` that fires every 60 ms and runs `if (state.get() == RUNNING) tick.run()`. When paused, the tick fires but skips its work immediately.

This is **not busy-waiting** in the traditional sense — there is no tight loop consuming CPU. The scheduler thread sleeps between firings (managed by the OS). The cost is one atomic read every 60 ms, which is negligible. A more complete fix would replace the scheduler with a dedicated thread using `wait()`/`notifyAll()`, but this exceeds the minimum corrections required and the impact is immeasurable.

---

## 5. Critical Regions — Definition and Minimum Scope Justification

### 5.1 `Board.step()` — lock on `Board`

**What it protects:** the entire sequence of one game step: computing the next position, checking board obstacles, applying teleports, checking snake-body collisions (`containsPosition` on each alive snake), consuming mice/turbo, spawning new items, and advancing the snake's body. All of this executes atomically under the board monitor.

**Why this scope:** splitting the region (e.g., read state → release → write state) would allow another runner to slip in between, creating TOCTOU races — two snakes eating the same mouse, or two snakes moving to the same cell without detecting each other. Holding the lock for one complete step is the minimum necessary to prevent these races.

**Why not wider:** the lock is released between steps. Each call to `step()` is self-contained; no state needs to be held across calls.

---

### 5.2 `Snake` body methods — lock on `Snake`

**What it protects:** the `body` deque against concurrent access between the runner thread (writing via `advance()`) and the EDT (reading via `snapshot()`). Also protects `containsPosition()`, called inside `board.step()` to detect collisions, so it always reads a consistent body state.

**Why this scope:** only the `body` deque requires this lock. The fields `direction`, `alive`, and `deathNanos` are protected independently via `volatile` and DCL and do not need to be covered by the same region.

**Lock ordering — deadlock analysis:** runners always acquire locks in a fixed order: `Board` first (entering `board.step()`) → then `Snake` (calling `head()`, `advance()`, or `containsPosition()` on any snake inside the step). The EDT acquires only the `Snake` lock (for `snapshot()` and `turn()`) or only the `Board` lock (for getters, which return copies and release immediately). `PauseControl` is always acquired independently, never while `Board` or `Snake` is held. Since no thread ever holds `Snake` and then tries to acquire `Board`, there is no lock cycle and **deadlock is impossible**.

---

### 5.3 `Snake.turn()` — lock on `Snake`

**What it protects:** the check-then-act sequence on `direction`: read current direction → check for 180° reversal → write new direction. Without `synchronized`, both the EDT and the runner can race through this sequence simultaneously, producing an invalid reversal.

**Why this scope:** only the reversal check and the write. `advance()`, `snapshot()`, and `containsPosition()` operate on `body`, a completely independent field, and do not need to be covered by the same critical region as `turn()`.

---

### 5.4 `PauseControl` methods — lock on `PauseControl`

**What it protects:** the boolean condition variable `paused` and all associated `wait()`/`notifyAll()` operations. Every read and write of `paused` happens inside a `synchronized` block, ensuring the condition is always evaluated with a consistent view of memory.

**Why this scope:** `wait()` atomically releases the `PauseControl` monitor and suspends the thread, so multiple runners can all sleep on the same object simultaneously without contending with each other. The lock is held only briefly when a thread wakes up to re-evaluate `while (paused)`.

---

## 6. UI: Start / Pause / Resume and Statistics

### Button states

The button progresses through three distinct states managed by `togglePause()`:

- **Start (initial):** `PauseControl` is created with `paused=true`, so all runners block inside `sleepOrPause()` immediately. Clicking **Start** → `pauseControl.resume()` unblocks all runners → `clock.start()` begins the 60 ms repaint loop → button becomes **Pause**.

- **Pause:** clicking **Pause** → `pauseControl.pause()` signals all runners to block → `clock.pause()` stops repaints → button becomes **Resume** → `buildStats()` populates the stats bar.

- **Resume:** clicking **Resume** → `pauseControl.resume()` wakes all waiting runners → `clock.resume()` restarts repaints → button returns to **Pause** → stats bar is cleared.

### Statistics on pause

When paused, the bottom bar displays:

```
PAUSED  |  Longest alive: Snake #2 (14 cells)  |  First to die: Snake #0
```

| Statistic | How it is computed |
|---|---|
| **Longest alive snake** | Iterates `snakes`, filters by `isAlive()` (volatile read), compares `length()` (synchronized), reports the index and cell count of the maximum |
| **First to die** | Filters dead snakes, compares `deathNanos()` (volatile read), reports the index with the earliest timestamp |

**Consistency without tearing:** `length()` and `snapshot()` are `synchronized` on the snake object, so the EDT always reads a complete, non-partial state for each snake individually. Values may lag by at most one game step from the exact moment of the pause click, which is imperceptible and acceptable for a game UI.
