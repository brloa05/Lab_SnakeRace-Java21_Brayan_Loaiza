package co.eci.snake.ui.legacy;

import co.eci.snake.concurrency.PauseControl;
import co.eci.snake.concurrency.SnakeRunner;
import co.eci.snake.core.Board;
import co.eci.snake.core.Direction;
import co.eci.snake.core.Position;
import co.eci.snake.core.Snake;
import co.eci.snake.core.engine.GameClock;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Main application window for the Snake Race game.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Creates the {@link Board} and all {@link Snake} instances.</li>
 *   <li>Submits one {@link SnakeRunner} virtual thread per snake.</li>
 *   <li>Manages the Start / Pause / Resume lifecycle via {@link PauseControl}
 *       and {@link co.eci.snake.core.engine.GameClock}.</li>
 *   <li>Displays pause statistics (longest alive snake, first to die).</li>
 * </ul>
 */
public final class SnakeApp extends JFrame {

    private final Board board;
    private final List<Snake> snakes = new java.util.ArrayList<>();
    private final PauseControl pauseControl = new PauseControl(true);
    private final GamePanel gamePanel;
    private final JButton actionButton;
    private final JLabel statsLabel;
    private final GameClock clock;
    private boolean started = false;

    public SnakeApp() {
        super("The Snake Race");
        this.board         = new Board(35, 28);
        this.gamePanel     = new GamePanel(board, () -> snakes);
        this.actionButton  = new JButton("Start");
        this.statsLabel    = new JLabel(" ");
        this.clock         = new GameClock(60, () -> SwingUtilities.invokeLater(gamePanel::repaint));

        initSnakes(Integer.getInteger("snakes", 2));
        initLayout();
        initThreads();
        initKeyBindings();
        setVisible(true);
    }

    // -------------------------------------------------------------------------
    // Initialization helpers
    // -------------------------------------------------------------------------

    private void initSnakes(int n) {
        for (int i = 0; i < n; i++) {
            int x = 2 + (i * 3) % board.width();
            int y = 2 + (i * 2) % board.height();
            snakes.add(Snake.of(x, y, Direction.values()[i % Direction.values().length]));
        }
    }

    private void initLayout() {
        statsLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        statsLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));

        JPanel south = new JPanel(new BorderLayout(4, 0));
        south.add(actionButton, BorderLayout.WEST);
        south.add(statsLabel,   BorderLayout.CENTER);

        setLayout(new BorderLayout());
        add(gamePanel, BorderLayout.CENTER);
        add(south,     BorderLayout.SOUTH);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
    }

    private void initThreads() {
        var exec = Executors.newVirtualThreadPerTaskExecutor();
        snakes.forEach(s -> exec.submit(new SnakeRunner(s, board, pauseControl, snakes)));
    }

    private void initKeyBindings() {
        actionButton.addActionListener(e -> togglePause());

        InputMap  im = gamePanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = gamePanel.getActionMap();

        im.put(KeyStroke.getKeyStroke("SPACE"), "pause");
        am.put("pause", action(e -> togglePause()));

        // Player 1 — arrow keys
        Snake p1 = snakes.get(0);
        im.put(KeyStroke.getKeyStroke("LEFT"),  "p1-left");
        im.put(KeyStroke.getKeyStroke("RIGHT"), "p1-right");
        im.put(KeyStroke.getKeyStroke("UP"),    "p1-up");
        im.put(KeyStroke.getKeyStroke("DOWN"),  "p1-down");
        am.put("p1-left",  action(e -> p1.turn(Direction.LEFT)));
        am.put("p1-right", action(e -> p1.turn(Direction.RIGHT)));
        am.put("p1-up",    action(e -> p1.turn(Direction.UP)));
        am.put("p1-down",  action(e -> p1.turn(Direction.DOWN)));

        // Player 2 — WASD (only if a second snake exists)
        if (snakes.size() > 1) {
            Snake p2 = snakes.get(1);
            im.put(KeyStroke.getKeyStroke('A'), "p2-left");
            im.put(KeyStroke.getKeyStroke('D'), "p2-right");
            im.put(KeyStroke.getKeyStroke('W'), "p2-up");
            im.put(KeyStroke.getKeyStroke('S'), "p2-down");
            am.put("p2-left",  action(e -> p2.turn(Direction.LEFT)));
            am.put("p2-right", action(e -> p2.turn(Direction.RIGHT)));
            am.put("p2-up",    action(e -> p2.turn(Direction.UP)));
            am.put("p2-down",  action(e -> p2.turn(Direction.DOWN)));
        }
    }

    /** Wraps a lambda as an AbstractAction to reduce boilerplate in key bindings. */
    private static AbstractAction action(java.util.function.Consumer<ActionEvent> handler) {
        return new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { handler.accept(e); }
        };
    }

    // -------------------------------------------------------------------------
    // Pause / Resume logic
    // -------------------------------------------------------------------------

    private void togglePause() {
        if (!started) {
            started = true;
            pauseControl.resume();
            clock.start();
            actionButton.setText("Pause");
        } else if (pauseControl.isPaused()) {
            statsLabel.setText(" ");
            pauseControl.resume();
            clock.resume();
            actionButton.setText("Pause");
        } else {
            pauseControl.pause();
            clock.pause();
            actionButton.setText("Resume");
            statsLabel.setText(buildStats());
        }
    }

    private String buildStats() {
        Snake longest   = null;
        int   maxLen    = -1;
        Snake firstDead = null;
        long  minDeath  = Long.MAX_VALUE;

        for (Snake s : snakes) {
            if (s.isAlive()) {
                int len = s.length();
                if (len > maxLen) { maxLen = len; longest = s; }
            } else {
                long dn = s.deathNanos();
                if (dn >= 0 && dn < minDeath) { minDeath = dn; firstDead = s; }
            }
        }

        String alive = (longest  != null)
                ? "Longest alive: Snake #" + snakes.indexOf(longest)  + " (" + maxLen + " cells)"
                : "All snakes are dead";
        String dead  = (firstDead != null)
                ? "First to die: Snake #" + snakes.indexOf(firstDead)
                : "No deaths yet";

        return "  PAUSED  |  " + alive + "  |  " + dead;
    }

    // -------------------------------------------------------------------------
    // Game panel
    // -------------------------------------------------------------------------

    /**
     * Custom panel that renders the board state every repaint cycle.
     * Reads board collections via defensive-copy getters and snake bodies
     * via {@link Snake#snapshot()} — both are safe to call from the EDT.
     */
    public static final class GamePanel extends JPanel {

        private static final Color[] PALETTE = {
            new Color(0,   170,   0),
            new Color(0,   160, 180),
            new Color(200,  80,   0),
            new Color(150,   0, 200),
            new Color(180, 160,   0),
            new Color(0,   100, 200),
        };

        @FunctionalInterface
        public interface Supplier { List<Snake> get(); }

        private final Board    board;
        private final Supplier snakesSupplier;
        private final int      cell = 20;

        public GamePanel(Board board, Supplier snakesSupplier) {
            this.board          = board;
            this.snakesSupplier = snakesSupplier;
            setPreferredSize(new Dimension(board.width() * cell + 1, board.height() * cell + 40));
            setBackground(Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            drawGrid(g2);
            drawObstacles(g2);
            drawMice(g2);
            drawTeleports(g2);
            drawTurbo(g2);
            drawSnakes(g2);

            g2.dispose();
        }

        private void drawGrid(Graphics2D g2) {
            g2.setColor(new Color(220, 220, 220));
            for (int x = 0; x <= board.width();  x++) g2.drawLine(x * cell, 0, x * cell, board.height() * cell);
            for (int y = 0; y <= board.height(); y++) g2.drawLine(0, y * cell, board.width() * cell, y * cell);
        }

        private void drawObstacles(Graphics2D g2) {
            g2.setColor(new Color(255, 102, 0));
            for (var p : board.obstacles()) {
                int x = p.x() * cell, y = p.y() * cell;
                g2.fillRect(x + 2, y + 2, cell - 4, cell - 4);
                g2.setColor(Color.RED);
                g2.drawLine(x + 4, y + 4,  x + cell - 6, y + 4);
                g2.drawLine(x + 4, y + 8,  x + cell - 6, y + 8);
                g2.drawLine(x + 4, y + 12, x + cell - 6, y + 12);
                g2.setColor(new Color(255, 102, 0));
            }
        }

        private void drawMice(Graphics2D g2) {
            for (var p : board.mice()) {
                int x = p.x() * cell, y = p.y() * cell;
                g2.setColor(Color.BLACK);
                g2.fillOval(x + 4, y + 4, cell - 8, cell - 8);
                g2.setColor(Color.WHITE);
                g2.fillOval(x + 8, y + 8, cell - 16, cell - 16);
            }
        }

        private void drawTeleports(Graphics2D g2) {
            g2.setColor(Color.RED);
            for (var entry : board.teleports().entrySet()) {
                Position from = entry.getKey();
                int x = from.x() * cell, y = from.y() * cell;
                int[] xs = { x + 4, x + cell - 4, x + cell - 10, x + cell - 10, x + 4 };
                int[] ys = { y + cell / 2, y + cell / 2, y + 4, y + cell - 4, y + cell / 2 };
                g2.fillPolygon(xs, ys, xs.length);
            }
        }

        private void drawTurbo(Graphics2D g2) {
            g2.setColor(Color.BLACK);
            for (var p : board.turbo()) {
                int x = p.x() * cell, y = p.y() * cell;
                int[] xs = { x + 8, x + 12, x + 10, x + 14, x + 6, x + 10 };
                int[] ys = { y + 2,  y + 2,  y + 8,  y + 8,  y + 16, y + 10 };
                g2.fillPolygon(xs, ys, xs.length);
            }
        }

        private void drawSnakes(Graphics2D g2) {
            List<Snake> snakes = snakesSupplier.get();
            for (int idx = 0; idx < snakes.size(); idx++) {
                Snake s = snakes.get(idx);
                if (!s.isAlive()) continue;
                Color base = PALETTE[idx % PALETTE.length];
                Position[] body = s.snapshot().toArray(new Position[0]);
                for (int i = 0; i < body.length; i++) {
                    int shade = Math.max(0, 40 - i * 4);
                    g2.setColor(new Color(
                        Math.min(255, base.getRed()   + shade),
                        Math.min(255, base.getGreen() + shade),
                        Math.min(255, base.getBlue()  + shade)));
                    g2.fillRect(body[i].x() * cell + 2, body[i].y() * cell + 2, cell - 4, cell - 4);
                }
            }
        }
    }

    public static void launch() {
        SwingUtilities.invokeLater(SnakeApp::new);
    }
}
