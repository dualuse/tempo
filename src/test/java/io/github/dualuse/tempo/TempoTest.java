package io.github.dualuse.tempo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TempoTest {

    @AfterEach
    void clearThreadLocal() {
        while (Tempo.CURRENT.get() != null) {
            Tempo.CURRENT.get().close();
        }
    }

    // ---------------------------------------------------------------
    // Construction & ThreadLocal
    // ---------------------------------------------------------------

    @Test
    void tempoScopeCreatesRoot() {
        assertNull(Tempo.CURRENT.get());
        try (Scope s = Tempo.scope("root")) {
            assertSame(s, Tempo.CURRENT.get());
            assertEquals("root", s.getName());
            assertNull(s.parent);
        }
        assertNull(Tempo.CURRENT.get());
    }

    @Test
    void nestedScopesAutoNest() {
        try (Scope outer = Tempo.scope("outer")) {
            try (Scope inner = Tempo.scope("inner")) {
                assertSame(inner, Tempo.CURRENT.get());
                assertSame(outer, inner.parent);
            }
            assertSame(outer, Tempo.CURRENT.get());
        }
    }

    @Test
    void scopeInstanceMethodCreatesChild() {
        try (Scope s = Tempo.scope("root")) {
            try (Scope c = s.scope("child")) {
                assertSame(s, c.parent);
                assertSame(c, Tempo.CURRENT.get());
            }
            assertSame(s, Tempo.CURRENT.get());
        }
    }

    // ---------------------------------------------------------------
    // Sequential phases (pre-named)
    // ---------------------------------------------------------------

    @Test
    void sequentialPreNamedPhases() throws Exception {
        try (Scope s = Tempo.scope("root")) {
            s.phase("a");
            Thread.sleep(5);
            s.phase("b");
            Thread.sleep(5);
            s.phase("c");
            Thread.sleep(5);
        }
        // After close, we can't inspect children easily without holding a ref,
        // so keep the reference:
    }

    @Test
    void preNamedPhasesCreateChildren() throws Exception {
        Scope root;
        try (Scope s = Tempo.scope("root")) {
            s.phase("a"); Thread.sleep(2);
            s.phase("b"); Thread.sleep(2);
            s.phase("c"); Thread.sleep(2);
            root = s;
        }
        assertEquals(3, root.getChildren().size());
        assertEquals("a", root.getChildren().get(0).getName());
        assertEquals("b", root.getChildren().get(1).getName());
        assertEquals("c", root.getChildren().get(2).getName());
        for (Scope child : root.getChildren()) {
            assertTrue(child.getElapsedNanos() >= 0, "child should have non-negative elapsed");
            assertTrue(child.getEndNanos() > 0, "child should be ended after parent close");
        }
    }

    // ---------------------------------------------------------------
    // Post-named phases (done)
    // ---------------------------------------------------------------

    @Test
    void postNamedPhasesCreateChildren() throws Exception {
        Scope root;
        try (Scope s = Tempo.scope("root")) {
            Thread.sleep(2); s.done("a");
            Thread.sleep(2); s.done("b");
            Thread.sleep(2); s.done("c");
            root = s;
        }
        assertEquals(3, root.getChildren().size());
        assertEquals("a", root.getChildren().get(0).getName());
        assertEquals("b", root.getChildren().get(1).getName());
        assertEquals("c", root.getChildren().get(2).getName());
    }

    @Test
    void preAndPostNamedEquivalent() throws Exception {
        Scope preRoot;
        try (Scope s = Tempo.scope("r")) {
            s.phase("x"); Thread.sleep(2);
            s.phase("y"); Thread.sleep(2);
            preRoot = s;
        }

        Scope postRoot;
        try (Scope s = Tempo.scope("r")) {
            Thread.sleep(2); s.done("x");
            Thread.sleep(2); s.done("y");
            postRoot = s;
        }

        assertEquals(preRoot.getChildren().size(), postRoot.getChildren().size());
        for (int i = 0; i < preRoot.getChildren().size(); i++) {
            assertEquals(
                preRoot.getChildren().get(i).getName(),
                postRoot.getChildren().get(i).getName());
        }
    }

    @Test
    void doneWithMismatchedNameThrows() {
        try (Scope s = Tempo.scope("root")) {
            s.phase("step1");
            assertThrows(IllegalStateException.class, () -> s.done("other"));
        }
    }

    @Test
    void doneWithMatchingNameSucceeds() throws Exception {
        Scope root;
        try (Scope s = Tempo.scope("root")) {
            s.phase("step1");
            Thread.sleep(2);
            s.done("step1"); // matches, should succeed
            Thread.sleep(2);
            s.done("step2"); // retroactive post-named phase
            root = s;
        }
        assertEquals(2, root.getChildren().size());
        assertEquals("step1", root.getChildren().get(0).getName());
        assertEquals("step2", root.getChildren().get(1).getName());
    }

    // ---------------------------------------------------------------
    // Chain form
    // ---------------------------------------------------------------

    @Test
    void chainedDoneNext() throws Exception {
        Scope root;
        try (Scope s = Tempo.scope("root")) {
            Thread.sleep(2);
            s.done("a").next("b");
            Thread.sleep(2);
            s.done("b");
            root = s;
        }
        assertEquals(2, root.getChildren().size());
        assertEquals("a", root.getChildren().get(0).getName());
        assertEquals("b", root.getChildren().get(1).getName());
    }

    // ---------------------------------------------------------------
    // Sub-scope equivalence
    // ---------------------------------------------------------------

    @Test
    void nestedScopesProduceSameTopLevelChildCount() throws Exception {
        Scope viaPhases;
        try (Scope s = Tempo.scope("r")) {
            s.phase("a"); Thread.sleep(1);
            s.phase("b"); Thread.sleep(1);
            viaPhases = s;
        }

        Scope viaScopes;
        try (Scope s = Tempo.scope("r")) {
            try (Scope a = s.scope("a")) { Thread.sleep(1); }
            try (Scope b = s.scope("b")) { Thread.sleep(1); }
            viaScopes = s;
        }

        assertEquals(viaPhases.getChildren().size(), viaScopes.getChildren().size());
        assertEquals("a", viaScopes.getChildren().get(0).getName());
        assertEquals("b", viaScopes.getChildren().get(1).getName());
    }

    @Test
    void scopeWithLambdaRunnable() {
        Scope root;
        try (Scope s = Tempo.scope("root")) {
            final boolean[] ran = {false};
            s.scope("work", () -> ran[0] = true);
            assertTrue(ran[0]);
            root = s;
        }
        assertEquals(1, root.getChildren().size());
        assertEquals("work", root.getChildren().get(0).getName());
    }

    @Test
    void scopeWithLambdaCallableReturnsValue() {
        try (Scope s = Tempo.scope("root")) {
            String result = s.scope("compute", () -> "hello");
            assertEquals("hello", result);
            assertEquals(1, s.getChildren().size());
        }
    }

    @Test
    void scopeWithCallableWrapsCheckedException() {
        try (Scope s = Tempo.scope("root")) {
            RuntimeException ex = assertThrows(RuntimeException.class,
                () -> s.scope("bad", () -> { throw new Exception("boom"); }));
            assertEquals("boom", ex.getCause().getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Fork
    // ---------------------------------------------------------------

    @Test
    void forkRunnableRecordsChildOnWorkerThread() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try (Scope s = Tempo.scope("root")) {
            final long callerThread = Thread.currentThread().getId();
            final AtomicLong workerThread = new AtomicLong();
            Future<?> f = s.fork("worker", exec, () -> {
                workerThread.set(Thread.currentThread().getId());
                Scope current = Tempo.CURRENT.get();
                assertNotNull(current);
                assertEquals("worker", current.getName());
                assertSame(s, current.parent);
            });
            f.get(5, TimeUnit.SECONDS);
            // worker should have been on a different thread
            assertNotEquals(callerThread, workerThread.get());
            assertEquals(1, s.getChildren().size());
            Scope workerScope = s.getChildren().get(0);
            assertEquals("worker", workerScope.getName());
            assertEquals(workerThread.get(), workerScope.getThreadId());
        } finally {
            exec.shutdown();
        }
    }

    @Test
    void forkCallableReturnsValue() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try (Scope s = Tempo.scope("root")) {
            Future<String> f = s.fork("worker", exec, () -> "hi");
            assertEquals("hi", f.get(5, TimeUnit.SECONDS));
        } finally {
            exec.shutdown();
        }
    }

    @Test
    void forkScopedCallableReceivesHandle() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try (Scope s = Tempo.scope("root")) {
            Future<Integer> f = s.fork("worker", exec, (Scope handle) -> {
                handle.phase("download");
                Thread.sleep(2);
                handle.phase("unpack");
                Thread.sleep(2);
                return 42;
            });
            assertEquals(42, f.get(5, TimeUnit.SECONDS));
            assertEquals(1, s.getChildren().size());
            Scope worker = s.getChildren().get(0);
            assertEquals("worker", worker.getName());
            assertEquals(2, worker.getChildren().size());
            assertEquals("download", worker.getChildren().get(0).getName());
            assertEquals("unpack", worker.getChildren().get(1).getName());
        } finally {
            exec.shutdown();
        }
    }

    @Test
    void parallelForksOverlap() throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try (Scope s = Tempo.scope("root")) {
            long t0 = System.nanoTime();
            Future<?> a = s.fork("a", exec, () -> { try { Thread.sleep(50); } catch (InterruptedException e) {} });
            Future<?> b = s.fork("b", exec, () -> { try { Thread.sleep(50); } catch (InterruptedException e) {} });
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
            long elapsed = System.nanoTime() - t0;
            // parallel execution: elapsed should be well under the sum (100ms),
            // allowing for some scheduling overhead
            assertTrue(elapsed < 90_000_000L, "expected parallel execution, elapsed=" + elapsed + "ns");
            assertEquals(2, s.getChildren().size());
        } finally {
            exec.shutdown();
        }
    }

    // ---------------------------------------------------------------
    // Tree traversal
    // ---------------------------------------------------------------

    @Test
    void rootWalksUpParentPointers() {
        try (Scope a = Tempo.scope("a")) {
            try (Scope b = a.scope("b")) {
                try (Scope c = b.scope("c")) {
                    assertSame(a, c.root());
                    assertSame(a, b.root());
                    assertSame(a, a.root());
                }
            }
        }
    }

    @Test
    void iteratorYieldsChildren() throws Exception {
        Scope root;
        try (Scope s = Tempo.scope("root")) {
            s.phase("x");
            s.phase("y");
            s.phase("z");
            root = s;
        }
        StringBuilder names = new StringBuilder();
        for (Scope child : root) {
            names.append(child.getName());
        }
        assertEquals("xyz", names.toString());
    }

    // ---------------------------------------------------------------
    // Close semantics
    // ---------------------------------------------------------------

    @Test
    void useAfterCloseThrows() {
        Scope s = Tempo.scope("root");
        s.close();
        assertThrows(IllegalStateException.class, () -> s.phase("x"));
        assertThrows(IllegalStateException.class, () -> s.scope("x"));
        assertThrows(IllegalStateException.class, () -> s.done("x"));
    }

    @Test
    void closeIsIdempotent() {
        Scope s = Tempo.scope("root");
        s.close();
        s.close(); // should not throw
        s.close();
    }

    @Test
    void closingParentEndsOpenChildPhase() throws Exception {
        Scope root;
        try (Scope s = Tempo.scope("root")) {
            s.phase("open");
            Thread.sleep(2);
            root = s;
        }
        assertEquals(1, root.getChildren().size());
        Scope child = root.getChildren().get(0);
        assertTrue(child.getEndNanos() > 0, "child phase should have been ended by parent close");
    }

    // ---------------------------------------------------------------
    // Output: toString (unchanged format)
    // ---------------------------------------------------------------

    @Test
    void toStringHasExpectedShape() throws Exception {
        Scope root;
        try (Scope s = Tempo.scope("Request")) {
            s.phase("A"); Thread.sleep(2);
            s.phase("B"); Thread.sleep(2);
            root = s;
        }
        String out = root.toString();
        assertTrue(out.contains("(Request)"));
        assertTrue(out.contains("(A)"));
        assertTrue(out.contains("(B)"));
        assertTrue(out.contains(" = "));
        assertTrue(out.contains(" + "));
        assertTrue(out.endsWith("(B)"));
    }

    // ---------------------------------------------------------------
    // Output: toServerTiming (deep flat-path)
    // ---------------------------------------------------------------

    @Test
    void toServerTimingDeepPaths() throws Exception {
        Scope root;
        try (Scope s = Tempo.scope("root")) {
            try (Scope load = s.scope("load")) {
                load.phase("fetch"); Thread.sleep(1);
                load.phase("decode"); Thread.sleep(1);
            }
            s.phase("render"); Thread.sleep(1);
            root = s;
        }
        String st = root.toServerTiming();
        // Should contain deep paths: load, load.fetch, load.decode, render
        assertTrue(st.contains("load;dur="), "missing load: " + st);
        assertTrue(st.contains("load.fetch;dur="), "missing load.fetch: " + st);
        assertTrue(st.contains("load.decode;dur="), "missing load.decode: " + st);
        assertTrue(st.contains("render;dur="), "missing render: " + st);
    }

    @Test
    void toServerTimingPercentEncodesSpecialChars() throws Exception {
        Scope root;
        try (Scope s = Tempo.scope("root")) {
            s.phase("load data,x"); Thread.sleep(1);
            root = s;
        }
        String st = root.toServerTiming();
        // Spaces → %20, commas → %2C in metric name (before ;dur=)
        String metricName = st.substring(0, st.indexOf(";"));
        assertTrue(metricName.contains("%20"), "space should be percent-encoded: " + metricName);
        assertTrue(metricName.contains("%2C"), "comma should be percent-encoded: " + metricName);
        assertFalse(metricName.contains(" "), "literal space should not appear: " + metricName);
    }

    @Test
    void toServerTimingEncodesDotsInNames() throws Exception {
        Scope root;
        try (Scope s = Tempo.scope("root")) {
            s.phase("v2.0"); Thread.sleep(1);
            root = s;
        }
        String st = root.toServerTiming();
        // The dot in "v2.0" must be encoded as %2E so it doesn't look like a path separator
        assertTrue(st.contains("v2%2E0;dur="), "dot in name should be encoded: " + st);
    }

    @Test
    void toServerTimingCrossesThreadBoundaries() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Scope root;
        try (Scope s = Tempo.scope("root")) {
            Future<?> f = s.fork("worker", exec, (Scope h) -> {
                h.phase("step"); Thread.sleep(2);
            });
            f.get(5, TimeUnit.SECONDS);
            root = s;
        }
        exec.shutdown();
        String st = root.toServerTiming();
        // worker and worker.step should both appear, crossing thread boundary
        assertTrue(st.contains("worker;dur="), "missing worker: " + st);
        assertTrue(st.contains("worker.step;dur="), "missing worker.step: " + st);
    }

    // ---------------------------------------------------------------
    // Output: toJSON (per-thread lane map)
    // ---------------------------------------------------------------

    @Test
    void toJSONSingleThreadLane() throws Exception {
        Scope root;
        try (Scope s = Tempo.scope("root")) {
            s.phase("A"); Thread.sleep(2);
            s.phase("B"); Thread.sleep(2);
            root = s;
        }
        String json = root.toJSON();
        // Should have a single thread lane key containing thread name and id
        String threadKey = Thread.currentThread().getName() + ":" + Thread.currentThread().getId();
        assertTrue(json.contains(jsonEscape(threadKey)), "missing thread key: " + json);
        // Should contain ^ and $ padding
        assertTrue(json.contains("\"^\":"), "missing head gap: " + json);
        assertTrue(json.contains("\"$\":"), "missing tail gap: " + json);
        // Should contain phase names
        assertTrue(json.contains("\"A\":"), "missing phase A: " + json);
        assertTrue(json.contains("\"B\":"), "missing phase B: " + json);
        // Balanced braces
        assertBalancedBraces(json);
    }

    @Test
    void toJSONForkCreatesIndependentLane() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Scope root;
        try (Scope s = Tempo.scope("root")) {
            s.phase("before"); Thread.sleep(1);
            Future<?> f = s.fork("worker", exec, (Scope h) -> {
                h.phase("download"); Thread.sleep(2);
                h.phase("unpack"); Thread.sleep(2);
            });
            f.get(5, TimeUnit.SECONDS);
            root = s;
        }
        exec.shutdown();
        String json = root.toJSON();
        // Should have two thread lanes
        String mainKey = Thread.currentThread().getName() + ":" + Thread.currentThread().getId();
        assertTrue(json.contains(jsonEscape(mainKey)), "missing main thread lane: " + json);
        // Worker thread lane should contain "worker" scope with children
        assertTrue(json.contains("\"worker\":{"), "worker should be object with children: " + json);
        assertTrue(json.contains("\"download\":"), "missing download in worker lane: " + json);
        assertTrue(json.contains("\"unpack\":"), "missing unpack in worker lane: " + json);
        // Worker phases should NOT appear under main thread's scope
        // (main thread shows "worker" as a bare number or doesn't show it at all)
        assertBalancedBraces(json);
    }

    @Test
    void toJSONPercentEncodesSlashInNames() throws Exception {
        Scope root;
        try (Scope s = Tempo.scope("POST /compute")) {
            s.phase("step"); Thread.sleep(1);
            root = s;
        }
        String json = root.toJSON();
        // The / in "POST /compute" should be encoded as %2F in the path key
        assertTrue(json.contains("POST %2Fcompute"), "slash should be percent-encoded: " + json);
    }

    @Test
    void toJSONLeafScopeIsBareNumber() throws Exception {
        Scope root;
        try (Scope s = Tempo.scope("root")) {
            s.phase("leaf"); Thread.sleep(2);
            root = s;
        }
        String json = root.toJSON();
        // "leaf" should be followed by a number, not a { object
        int idx = json.indexOf("\"leaf\":");
        assertTrue(idx >= 0, "missing leaf: " + json);
        char after = json.charAt(idx + "\"leaf\":".length());
        assertTrue(Character.isDigit(after), "leaf should be bare number, got: " + after + " in: " + json);
    }

    // ---------------------------------------------------------------
    // Jackson serialization
    // ---------------------------------------------------------------

    @Test
    void jacksonSerializationMatchesToJSON() throws Exception {
        var mapper = new ObjectMapper();
        Scope root;
        try (Scope s = Tempo.scope("root")) {
            s.phase("A"); Thread.sleep(2);
            s.phase("B"); Thread.sleep(2);
            root = s;
        }
        String jackson = mapper.writeValueAsString(root);
        String direct = root.toJSON();
        assertEquals(direct, jackson);
    }

    @Test
    void jacksonSerializationWithForksMatchesToJSON() throws Exception {
        var mapper = new ObjectMapper();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Scope root;
        try (Scope s = Tempo.scope("root")) {
            s.phase("before"); Thread.sleep(1);
            Future<?> f = s.fork("worker", exec, (Scope h) -> {
                h.phase("step"); Thread.sleep(2);
            });
            f.get(5, TimeUnit.SECONDS);
            root = s;
        }
        exec.shutdown();
        String jackson = mapper.writeValueAsString(root);
        String direct = root.toJSON();
        assertEquals(direct, jackson);
    }

    @Test
    void jacksonEmbeddedInLargerObject() throws Exception {
        var mapper = new ObjectMapper();
        Scope root;
        try (Scope s = Tempo.scope("request")) {
            s.phase("work"); Thread.sleep(2);
            root = s;
        }
        // Scope embedded as a field in a map
        var wrapper = Map.of("status", "ok", "profile", root);
        String json = mapper.writeValueAsString(wrapper);
        assertTrue(json.contains("\"status\":\"ok\""), "wrapper field missing: " + json);
        assertTrue(json.contains("\"profile\":{"), "profile should be an object: " + json);
        assertBalancedBraces(json);
    }

    // ---------------------------------------------------------------
    // Test helpers
    // ---------------------------------------------------------------

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void assertBalancedBraces(String json) {
        int open = 0, close = 0;
        for (int i = 0; i < json.length(); i++) {
            if (json.charAt(i) == '{') open++;
            else if (json.charAt(i) == '}') close++;
        }
        assertEquals(open, close, "unbalanced braces in JSON: " + json);
    }
}
