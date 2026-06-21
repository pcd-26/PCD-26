package pcd.poool.benchmark;

import java.util.Locale;
import javax.swing.SwingUtilities;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.sequential.PhysicsEngine;
import pcd.poool.model.physics.taskbased.TaskBasedPhysicsEngine;
import pcd.poool.model.physics.threaded.ThreadedPhysicsEngine;
import pcd.poool.view.board.View;
import pcd.poool.view.board.ViewModel;

/**
 * Benchmarks Swing GUI responsiveness separately from headless throughput.
 */
public final class GuiResponsivenessBenchmark {

    private static final int VIEW_WIDTH = 1200;
    private static final int VIEW_HEIGHT = 800;
    private static final long EDT_DELAY_THRESHOLD_NANOS = PhysicsDefaults.FIXED_STEP_MILLIS * 1_000_000L;
    private static volatile long blackhole;

    private GuiResponsivenessBenchmark() {
    }

    /**
     * Runs a GUI responsiveness benchmark from the command line.
     *
     * @param args optional CLI arguments in the same order used by the headless runner
     */
    public static void main(String[] args) {
        var config = BenchmarkConfig.defaults()
                .withGuiEnabled(true)
                .withInstrumentationEnabled(false);
        if (args.length > 0) {
            config = config.withImplementation(BenchmarkConfig.ImplementationType.parse(args[0]));
        }
        if (args.length > 1) {
            config = config.withBalls(Integer.parseInt(args[1]));
        }
        if (args.length > 2) {
            config = config.withThreads(Integer.parseInt(args[2]));
        }
        if (args.length > 3) {
            config = config.withSteps(Integer.parseInt(args[3]));
        }
        if (args.length > 4) {
            config = config.withSeed(Long.parseLong(args[4]));
        }

        try {
            var result = run(config);
            System.out.printf(Locale.US,
                    "config=%s elapsed_ms=%.3f completed_updates=%d mean_latency_ms=%.3f max_latency_ms=%.3f update_rate=%.3f%n",
                    config.toKeyValueString(),
                    result.elapsedMillis(),
                    result.completedUpdates(),
                    result.meanUpdateLatencyMillis(),
                    result.maxUpdateLatencyMillis(),
                    result.updateRatePerSecond());
        } catch (Exception ex) {
            System.err.printf(Locale.US, "gui_benchmark_failed message=%s%n", ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * Runs the benchmark and returns the collected metrics.
     *
     * @param config GUI benchmark configuration
     * @return collected GUI responsiveness measurements
     * @throws Exception if the GUI cannot be created or rendered
     */
    public static GuiResponsivenessResult run(BenchmarkConfig config) throws Exception {
        if (!config.guiEnabled()) {
            throw new IllegalArgumentException("GUI benchmark requires guiEnabled=true");
        }

        var stepper = createStepper(config);
        AutoCloseable closeable = stepper instanceof AutoCloseable autoCloseable ? autoCloseable : null;
        View view = null;
        try {
            var board = new Board(stepper);
            board.init(new SeededBenchmarkBoardConf(config.balls(), config.seed()));
            var viewModel = new ViewModel();
            view = new View(viewModel, VIEW_WIDTH, VIEW_HEIGHT);
            var monitor = new GuiResponsivenessMonitor(EDT_DELAY_THRESHOLD_NANOS);
            long startNanos = System.nanoTime();
            for (int i = 0; i < config.steps(); i++) {
                long requestNanos = monitor.recordUpdateRequest();
                SwingUtilities.invokeLater(() -> monitor.recordEdtDispatch(requestNanos));

                board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
                viewModel.update(board, 0);
                view.render();
                monitor.recordUpdateCompleted(requestNanos);
            }
            SwingUtilities.invokeAndWait(() -> {
            });
            var result = monitor.snapshot();
            blackhole = Double.doubleToLongBits(result.updateRatePerSecond()) ^ System.nanoTime() ^ startNanos;
            GuiResponsivenessCsvWriter.export(config, result);
            return result;
        } finally {
            if (view != null) {
                view.close();
            }
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (Exception ex) {
                    throw new IllegalStateException("failed to close GUI benchmark engine", ex);
                }
            }
        }
    }

    private static PhysicsStepper createStepper(BenchmarkConfig config) {
        return switch (config.implementation()) {
            case SEQUENTIAL -> new PhysicsEngine();
            case THREADS -> new ThreadedPhysicsEngine(config.effectiveThreads());
            case EXECUTOR -> new TaskBasedPhysicsEngine(config.effectiveThreads());
        };
    }
}
