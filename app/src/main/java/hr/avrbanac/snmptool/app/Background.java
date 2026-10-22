package hr.avrbanac.snmptool.app;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.concurrent.Task;

/**
 * Runs work off the JavaFX application thread and hands the result back on it.
 */
public final class Background {

    private Background() {
    }

    /**
     * Runs {@code work} on a daemon thread; {@code onSuccess} or {@code onFailure} runs on the JavaFX
     * application thread afterwards.
     */
    public static <T> void run(String name, Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(_ -> onSuccess.accept(task.getValue()));
        task.setOnFailed(_ -> onFailure.accept(task.getException()));
        Thread.ofPlatform().daemon().name(name).start(task);
    }
}
