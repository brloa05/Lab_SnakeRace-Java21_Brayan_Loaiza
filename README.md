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
- **Controles**: Flechas → serpiente 0 · WASD → serpiente 1 · Espacio / botón → Pausar/Reanudar.

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

The application creates **N virtual threads** (one per snake via `Executors.newVirtualThreadPerTaskExecutor()`), each running a `SnakeRunner`. Every runner calls `maybeTurn()` → `board.step(snake, allSnakes)` (acquires the board lock, checks collisions, advances the snake, releases the lock) → `pauseControl.sleepOrPause(ms)` (sleeps or blocks if paused). In parallel, the **Swing EDT** fires every 60 ms via `GameClock` → reads `snake.snapshot()` to paint. A shared `PauseControl` monitor allows the UI to block all runners simultaneously using `wait()`/`notifyAll()`.

---

## 2. Data Races Found and Their Solution

### 2.1 Race on `Snake.body` — critical

`ArrayDeque` is not thread-safe. The runner thread writes via `advance()` inside `board.step()`, while the EDT reads via `snapshot()` in `paintComponent()` — a concurrent modification that causes `ConcurrentModificationException` or visual tearing.

**Fix:** `synchronized` on the `Snake` object for every body access (`head`, `advance`, `snapshot`, `length`, `containsPosition`). `snapshot()` returns a defensive copy so the EDT iterates its own local list and releases the lock immediately.

### 2.2 Race on `Snake.turn()` — minor

`direction` is `volatile`, but the read → check-180° → write sequence is not atomic. Both the runner (`randomTurn()`) and the EDT (player keys) call `turn()` simultaneously, which can produce an invalid 180° reversal.

**Fix:** `synchronized` on `this` in `turn()` makes the check-then-act atomic without introducing new lock cycles.

### 2.3 Race on `Snake.alive` / `deathNanos` — single write

Both fields must be written together atomically. If the EDT reads `alive=false` while `deathNanos` is still `-1`, the pause statistics become incoherent.

**Fix:** double-checked locking — `alive` is `volatile` for cheap reads in the runner's hot loop; `die()` uses an inner `synchronized (this)` block to write both fields atomically.

---

## 3. Unsafe Collections and How They Were Protected

| Collection | Problem | Fix |
|---|---|---|
| `Snake.body` (`ArrayDeque`) | Concurrent write (runner) / read (EDT) | `synchronized` on all body methods; defensive copy in `snapshot()` |
| `Board.mice`, `obstacles`, `turbo` (`HashSet`) | Runners modify while EDT reads | `step()` synchronized on `Board`; getters return `new HashSet<>(...)` |
| `Board.teleports` (`HashMap`) | Same as above | `teleports()` returns `new HashMap<>(...)` |
| `SnakeApp.snakes` (`ArrayList`) | Read by runners and EDT | Written only in constructor before any thread starts; `Thread.start()` happens-before guarantees visibility |

---

## 4. Active Waits Eliminated and Mechanism Used

**Problem:** `SnakeRunner` used `Thread.sleep(80)`. Pressing Pause stopped repaints but runners kept executing game steps — the pause was visual only.

**Fix — `PauseControl`:** a single monitor replaces `Thread.sleep()` with `sleepOrPause(millis)`:

```java
public synchronized void sleepOrPause(long millis) throws InterruptedException {
    if (paused) { while (paused) wait(); return; }
    wait(millis);          // normal sleep, wakes early on pause()
    while (paused) wait(); // blocks until resume()
}
public synchronized void pause()  { paused = true;  notifyAll(); }
public synchronized void resume() { paused = false; notifyAll(); }
```

`pause()` calls `notifyAll()` to immediately wake runners sleeping in `wait(millis)`, which then block in `wait()` — releasing the CPU entirely. **No lost wakeups:** the `while (paused)` condition is always re-evaluated inside the `synchronized` block before blocking, so a `resume()` that arrives early is never missed.

**`GameClock`:** the scheduler fires every 60 ms and checks `state.get() == RUNNING` — not a tight loop, so not true busy-waiting. Accepted because `clock.pause()` is always called alongside `pauseControl.pause()`.

---

## 5. Critical Regions — Minimum Scope Justification

| Region | Lock | What it protects | Why this scope |
|---|---|---|---|
| `Board.step()` | `Board` | Full move: collision check, board state read/write, snake advance | Must be one atomic step to prevent TOCTOU races (two runners eating the same mouse or hitting the same body cell) |
| `Snake` body methods | `Snake` | `body` deque — prevent EDT from seeing a partial advance | Only the deque access; `direction`, `alive`, `deathNanos` have independent protection |
| `Snake.turn()` | `Snake` | check-then-act on `direction` | Only the reversal check + write; not wider because `advance()` and `snapshot()` are independent |
| `PauseControl` methods | `PauseControl` | Condition variable `paused` + `wait()`/`notifyAll()` | Independent of `Board` and `Snake`; `wait()` releases the lock while blocking |

**Lock ordering (no deadlock):** runners always acquire `Board` first → then `Snake` (inside `step()`). The EDT acquires only `Snake` (snapshot/turn) or only `Board` (getters, which return copies and release immediately). `PauseControl` is never held alongside `Board` or `Snake`. No cycle exists → deadlock impossible.

---

## 6. UI: Start / Pause / Resume and Statistics

`PauseControl` is initialized with `paused=true`, so runners block immediately on start. The button begins as **Start**.

Clicking **Start** → `pauseControl.resume()` + `clock.start()` → button becomes **Pause**. Clicking **Pause** → `pauseControl.pause()` + `clock.pause()` → button becomes **Resume** and the stats bar is shown. Clicking **Resume** → `pauseControl.resume()` + `clock.resume()` → button returns to **Pause** and stats are cleared.

When paused, the bottom bar displays:

```
PAUSED  |  Longest alive: Snake #2 (14 cells)  |  First to die: Snake #0
```

- **Longest alive:** iterates `snakes`, filters by `isAlive()` (volatile), picks max `length()` (synchronized).
- **First to die:** filters dead snakes, picks the one with the smallest `deathNanos()` (volatile).

Each read is individually atomic (volatile or synchronized), so no partial state is ever visible.
