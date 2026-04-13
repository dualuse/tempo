package io.github.dualuse.tempo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Demonstrates common Tempo usage patterns.
 * Run with: ./gradlew test --tests "io.github.dualuse.tempo.TempoDemo"
 * (or just run the main method directly)
 */
public class TempoDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== 1. Simple sequential phases ===");
        simplePhases();

        System.out.println("\n=== 2. Post-named phases (done) ===");
        postNamedPhases();

        System.out.println("\n=== 3. Nested scopes ===");
        nestedScopes();

        System.out.println("\n=== 4. Parallel forks ===");
        parallelForks();

        System.out.println("\n=== 5. Realistic request handler ===");
        realisticHandler();
    }

    // ---------------------------------------------------------------
    // 1. Simple sequential phases
    //    Use phase() to mark the start of each step.
    //    Each phase ends when the next one begins (or the scope closes).
    // ---------------------------------------------------------------

    static void simplePhases() throws Exception {
        Scope root;
        try (var s = Tempo.scope("image-resize")) {
            s.phase("load");
            Thread.sleep(40);     // simulate loading an image

            s.phase("resize");
            Thread.sleep(80);     // simulate resizing

            s.phase("encode");
            Thread.sleep(30);     // simulate encoding to JPEG

            root = s;
        }
        // prints: 0.15...s (image-resize) = 0.04...s (load) + 0.08...s (resize) + 0.03...s (encode)
        System.out.println(root);
    }

    // ---------------------------------------------------------------
    // 2. Post-named phases (done)
    //    Name each phase AFTER the work, not before.
    //    Useful when the name depends on the result.
    // ---------------------------------------------------------------

    static void postNamedPhases() throws Exception {
        Scope root;
        try (var s = Tempo.scope("pipeline")) {
            Thread.sleep(50);
            s.done("fetch");      // the work above is now named "fetch"

            Thread.sleep(30);
            s.done("transform");  // the work above is now named "transform"

            Thread.sleep(20);
            s.done("store");

            root = s;
        }
        System.out.println(root);
    }

    // ---------------------------------------------------------------
    // 3. Nested scopes
    //    Scopes can contain other scopes for hierarchical structure.
    //    Sub-scopes appear as children in the output.
    // ---------------------------------------------------------------

    static void nestedScopes() throws Exception {
        Scope root;
        try (var s = Tempo.scope("build")) {

            // A sub-scope groups related phases together
            try (var compile = s.scope("compile")) {
                compile.phase("parse");
                Thread.sleep(20);
                compile.phase("typecheck");
                Thread.sleep(30);
                compile.phase("codegen");
                Thread.sleep(25);
            }

            // Callable form — shorter for simple cases
            s.scope("link", () -> { Thread.sleep(40); return null; });

            s.scope("package", () -> { Thread.sleep(15); return null; });

            root = s;
        }
        System.out.println(root);
        System.out.println();
        System.out.println("JSON:");
        System.out.println(root.toJSON());
    }

    // ---------------------------------------------------------------
    // 4. Parallel forks
    //    Use fork() to run work on an executor.
    //    Each fork creates a scope on the worker thread.
    // ---------------------------------------------------------------

    static void parallelForks() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);

        Scope root;
        try (var s = Tempo.scope("batch-download")) {
            String[] urls = {"file-A", "file-B", "file-C"};
            List<Future<?>> futures = new ArrayList<>();

            for (String url : urls) {
                // fork() submits to the executor and returns a Future.
                // The lambda receives the forked Scope for adding phases.
                futures.add(s.fork("download " + url, pool, (Scope fork) -> {
                    fork.phase("connect");
                    Thread.sleep(10 + (int)(Math.random() * 30));

                    fork.phase("transfer");
                    Thread.sleep(20 + (int)(Math.random() * 40));
                }));
            }

            // Wait for all downloads to complete
            for (Future<?> f : futures) f.get();

            root = s;
        }
        pool.shutdown();

        // toString shows the flat summary
        System.out.println(root);

        // toJSON shows per-thread lanes — each fork on its own thread
        System.out.println();
        System.out.println("JSON (per-thread lanes):");
        System.out.println(root.toJSON());

        // toServerTiming shows deep dot-paths for HTTP headers
        System.out.println();
        System.out.println("Server-Timing:");
        System.out.println(root.toServerTiming());
    }

    // ---------------------------------------------------------------
    // 5. Realistic request handler
    //    Combines phases, nested scopes, and parallel forks
    //    in a pattern typical for a cloud compute service.
    // ---------------------------------------------------------------

    static void realisticHandler() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);

        Scope request;
        try (var s = Tempo.scope("POST /api/render")) {

            // Sequential preprocessing on the main thread
            s.phase("parse request");
            Thread.sleep(5);

            s.phase("validate");
            Thread.sleep(3);

            // Parallel loading phase — download resources while running inference
            try (var load = s.scope("load")) {
                Future<byte[]> model = load.fork("model", workers, () -> {
                    Thread.sleep(80);  // download neural network weights
                    return new byte[1024];
                });

                Future<byte[]> texture = load.fork("texture", workers, () -> {
                    Thread.sleep(60);  // download texture atlas
                    return new byte[512];
                });

                // Both run in parallel; load scope ends when both complete
                model.get();
                texture.get();
            }

            // Back to sequential work on the main thread
            s.phase("render");
            Thread.sleep(120);

            s.phase("encode");
            Thread.sleep(25);

            request = s;
        }
        workers.shutdown();

        // Human-readable summary
        System.out.println(request);

        // Per-thread JSON for detailed analysis
        System.out.println();
        System.out.println("JSON:");
        System.out.println(request.toJSON());

        // Server-Timing header value (paste into HTTP response)
        System.out.println();
        System.out.println("Server-Timing: " + request.toServerTiming());
    }
}
