package co.eci.snake.concurrency;

import co.eci.snake.core.Board;
import co.eci.snake.core.Direction;
import co.eci.snake.core.Snake;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class SnakeRunner implements Runnable {

    private final Snake snake;
    private final Board board;
    private final PauseControl pauseControl;
    private final List<Snake> allSnakes;
    private final int baseSleepMs = 80;
    private final int turboSleepMs = 40;
    private int turboTicks = 0;

    public SnakeRunner(Snake snake, Board board, PauseControl pauseControl, List<Snake> allSnakes) {
        this.snake = snake;
        this.board = board;
        this.pauseControl = pauseControl;
        this.allSnakes = allSnakes;
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted() && snake.isAlive()) {
                maybeTurn();
                var res = board.step(snake, allSnakes);

                if (res == Board.MoveResult.HIT_SNAKE) {
                    snake.die();
                    break;
                }
                if (res == Board.MoveResult.HIT_OBSTACLE) {
                    randomTurn();
                } else if (res == Board.MoveResult.ATE_TURBO) {
                    turboTicks = 100;
                }

                int sleep = (turboTicks > 0) ? turboSleepMs : baseSleepMs;
                if (turboTicks > 0) turboTicks--;
                pauseControl.sleepOrPause(sleep);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void maybeTurn() {
        double p = (turboTicks > 0) ? 0.05 : 0.10;
        if (ThreadLocalRandom.current().nextDouble() < p) randomTurn();
    }

    private void randomTurn() {
        var dirs = Direction.values();
        snake.turn(dirs[ThreadLocalRandom.current().nextInt(dirs.length)]);
    }
}
