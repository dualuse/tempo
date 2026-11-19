package io.github.dualuse.tempo.demo.medium;

import io.github.dualuse.tempo.Tempo;
import io.github.dualuse.tempo.demo.Demo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Getting a trace out of a service. A search request is recorded and turned into what its response
 * carries: {@code Runtime}, {@code Server-Timing} and {@code Tempo-Timing} under one shared byte limit,
 * a {@code Tempo-Link} to a self-hosted viewer, and the trace as JSON in the body. A second request,
 * an index update of 2,000 spans, shows what a byte limit does to a trace that does not fit.
 *
 * <p>It shows
 * <ul>
 * <li>one limit for all timing headers: render {@code Runtime} and {@code Server-Timing} first, give {@code toHeader(left)} the rest,
 * and the link what is left after that;</li>
 * <li>the ladder: one large trace at three limits, and {@code x=folded.dropped} counting what was left out;</li>
 * <li>{@code toLink(base, maxBytes)} under a byte limit: a null base opens the viewer of
 * {@code Options.linkBase}, a base given per call opens another one;</li>
 * <li>a response object that holds a {@code Snapshot}, so its body renders after the trace is released;</li>
 * <li>reading a trace in code: {@code forEachSpan} for the tree, {@code forEachTop} for the labelled phases.</li>
 * </ul>
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=ResponseHeaders                  the story, then the search response and its body
 * ./gradlew -q demo -Pdemo=ResponseHeaders | tools/tempo    the search request drawn as lanes, from the body
 * </pre>
 *
 * Look for: the four header sizes adding up to less than 3,000 bytes; the tree read back from the
 * snapshot after release, with the four {@code match} tasks on {@code cpu-1} and {@code cpu-2} and
 * their queue waits; the ladder table, where fewer records go on the wire as the limit shrinks, while
 * the records that are not aggregates, the folded and the dropped spans always add up to the 2,002
 * spans recorded; and in the lanes, the phases {@code decode} and {@code rerank} on the bar of
 * {@code GET /search}, with {@code load} and the waiting {@code join} below it, during {@code rerank}. The printed {@code Tempo-Link}
 * points at {@code tempo.example.org}, which does not exist: {@code tools/tempo '<link>'} decodes a
 * link without opening it.
 */
public final class ResponseHeaders {
    static final Tempo.Name SEARCH = Tempo.name("GET /search"), DECODE = Tempo.name("decode"),
            RERANK = Tempo.name("rerank"), LOAD = Tempo.name("load"), MATCH = Tempo.name("match"),
            JOIN = Tempo.name("join"), BYTES = Tempo.name("bytes"), HITS = Tempo.name("hits");

    static final Tempo.Name UPDATE = Tempo.name("POST /index"), SHARD = Tempo.name("shard"),
            TOKENIZE = Tempo.name("tokenize"), DOCS = Tempo.name("docs"), TERMS = Tempo.name("terms");

    /**
     * What the proxy in front of the service lets through for the four timing headers together, in bytes
     * of their values (the header names are not counted here; subtract them too if your limit does).
     */
    static final int HEADER_LIMIT = 3_000;

    /** A self-hosted copy of Tempo Timeline ({@code site/index.html}), say on an internal host. */
    static final String VIEWER = "https://tempo.example.org/viewer/";

    static final int SHARDS = 40, DOCS_PER_SHARD = 49; // 1 + 40 + 40 * 49 + 1 = 2,002 spans

    private ResponseHeaders() {
    }

    public static void main(String[] args) throws Exception {
        ThreadPoolExecutor cpu = Demo.pool("cpu", 2), index = Demo.pool("index", 4);
        // Each pool wrapped once: every submit to it is a task of that name.
        ExecutorService matcher = Tempo.executor(cpu, MATCH), indexer = Tempo.executor(index, SHARD);
        try {
            Response response = search(matcher);
            readBack(response.tempo);
            ladder(indexer);
            send(response);
        } finally {
            Demo.shutdown(cpu, index);
        }
    }

    // ------------------------------------------------------------------ 1. a request and its response

    /**
     * What the service sends. It holds a {@link Tempo.Snapshot}, never the {@link Tempo.Trace}: the
     * framework serializes the body later, when the request has long released its trace.
     */
    static final class Response {
        final String runtime, serverTiming, tempoTiming, tempoLink;
        final List<String> hits;
        final Tempo.Snapshot tempo;

        Response(String runtime, String serverTiming, String tempoTiming, String tempoLink,
                 List<String> hits, Tempo.Snapshot tempo) {
            this.runtime = runtime;
            this.serverTiming = serverTiming;
            this.tempoTiming = tempoTiming;
            this.tempoLink = tempoLink;
            this.hits = hits;
            this.tempo = tempo;
        }

        /** {"hits":[...],"tempo":{...}}, written by hand: Tempo needs no JSON library for its part. */
        String body() throws IOException {
            StringBuilder b = new StringBuilder("{\"hits\":[");
            for (int i = 0; i < hits.size(); i++) b.append(i == 0 ? "\"" : ",\"").append(hits.get(i)).append('"');
            b.append("],\"tempo\":");
            tempo.writeJson(b);
            return b.append('}').toString();
        }
    }

    static Response search(ExecutorService matcher) throws Exception {
        // linkBase: every Tempo-Link of this trace opens our own viewer instead of the public one.
        Tempo.Trace t = Tempo.trace(SEARCH, new Tempo.Options().linkBase(VIEWER).strict(true)).records(Tempo.STEP);
        List<String> hits = new ArrayList<>();
        try (t) {
            // Labelled phases of the root are what Runtime and Server-Timing print.
            t.phase(DECODE).label("Decode Query");
            Demo.cpu(2_000); // parse the query
            t.phase(RERANK).label("rerank");
            try (Tempo.Span load = Tempo.step(LOAD)) {
                Demo.io(3); // read the candidates
                load.set(BYTES, 48_213);
            }
            List<Future<String>> matches = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String doc = "doc-" + (17 + 9 * i);
                matches.add(matcher.submit(() -> {
                    Demo.cpu(1_500); // match one candidate
                    return doc;
                }));
            }
            try (Tempo.Span join = Tempo.span(JOIN).waiting()) {
                for (Future<String> f : matches) hits.add(f.get());
                join.set(HITS, hits.size());
            }
        }

        // Every render of a Trace copies it first. The response renders it four times and keeps it for
        // the body, so it copies once, while the response is built, and renders everything from that.
        Tempo.Snapshot snap = t.snapshot();

        // One limit for the timing headers: Runtime and Server-Timing are short and come first, the whole tree
        // gets what is left. Count UTF-8 bytes, not chars: a label may hold any character.
        String runtime = snap.toRuntime(3), serverTiming = snap.toServerTiming();
        int left = HEADER_LIMIT - Demo.bytes(runtime) - Demo.bytes(serverTiming);
        String tempoTiming = snap.toHeader(left);
        // The link carries the same value again (FORMAT.md §9.4), so a shared limit must cover it too:
        // it gets what Tempo-Timing left. A null base means Options.linkBase, our own viewer.
        // The trace travels in the link's fragment (after '#'), which a browser never sends to a
        // server: the viewer's host serves the page and learns nothing of the trace.
        int linkLeft = left - Demo.bytes(tempoTiming);
        String tempoLink = snap.toLink(null, linkLeft);

        // The response holds the snapshot, never the trace, so the body can be written at any time.
        Response response = new Response(runtime, serverTiming, tempoTiming, tempoLink, hits, snap);
        String timingBefore = response.tempo.toHeader(), bodyBefore = response.body();
        t.release(); // the buffers go back to the pool; the trace reads empty from now on

        int total = Demo.bytes(runtime) + Demo.bytes(serverTiming) + Demo.bytes(tempoTiming) + Demo.bytes(tempoLink);
        Demo.say(String.format(Locale.ROOT,
                "%s: Runtime %d + Server-Timing %d + Tempo-Timing %d + Tempo-Link %d = %d of %d bytes",
                SEARCH, Demo.bytes(runtime), Demo.bytes(serverTiming), Demo.bytes(tempoTiming), Demo.bytes(tempoLink),
                total, HEADER_LIMIT));

        Demo.check(total <= HEADER_LIMIT, "the four timing headers fit the shared limit together");
        Demo.check(Demo.bytes(tempoTiming) <= left, "Tempo-Timing fits what Runtime and Server-Timing left");
        Demo.check(Demo.bytes(tempoLink) <= linkLeft, "Tempo-Link fits what Tempo-Timing left");
        Demo.check(Demo.field(tempoTiming, "x") == null, "the small search trace fits whole: no x=, nothing folded or dropped");
        Demo.check(tempoLink.startsWith(VIEWER) && tempoLink.contains("#t="),
                "Tempo-Link opens the viewer of Options.linkBase and carries the trace after #t=");
        StringBuilder json = new StringBuilder();
        response.tempo.writeJson(json);
        Demo.check(json.toString().startsWith("{\"v\":1"), "the JSON form starts with {\"v\":1");
        Demo.check(balanced(bodyBefore), "the hand-built body is one balanced JSON object");
        Demo.check(t.toHeader().isEmpty(), "a released trace renders empty");
        Demo.check(response.tempo.toHeader().equals(timingBefore), "the snapshot renders the same header after release");
        Demo.check(response.body().equals(bodyBefore), "the snapshot writes the same JSON after release");
        return response;
    }

    // ------------------------------------------------------------------ 2. reading a trace in code

    /** Walks the snapshot, after the trace was released, the way a log line or a test would. */
    static void readBack(Tempo.Snapshot tempo) {
        Demo.say("the trace, read back from the snapshot after release:");
        int[] matches = {0}, onPool = {0}, waits = {0};
        tempo.forEachSpan(v -> {
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-18s %-6s %7.3f ms",
                    "  ".repeat(v.depth()) + v.name(), v.thread(), ms(v.durationNanos())));
            if (v.phase()) line.append("  phase");
            if (v.handoff()) line.append(String.format(Locale.ROOT, "  queue wait %.3f ms", ms(v.waitNanos())));
            if (v.waiting()) line.append("  waiting");
            for (int i = 0; i < v.attributes(); i++) line.append("  ").append(v.attributeKey(i)).append('=').append(v.longAttribute(i));
            Demo.say("  " + line);

            if (v.name() == MATCH) matches[0]++;
            if (v.name() == MATCH && v.handoff() && v.thread() != null && v.thread().startsWith("cpu-")) onPool[0]++;
            if (v.name() == JOIN && v.waiting()) waits[0]++;
        });

        // The labelled phases are what Runtime and Server-Timing print: a log line can use them too.
        List<String> steps = new ArrayList<>();
        tempo.forEachTop((name, label, start, duration) -> {
            steps.add(name + (label == null ? "" : " \"" + label + "\""));
            Demo.say(String.format(Locale.ROOT, "  labelled phase %-22s %7.3f ms", steps.get(steps.size() - 1), ms(duration)));
        });

        Demo.check(matches[0] == 4 && onPool[0] == 4, "the four match tasks ran on cpu-* threads, each after its queue wait");
        Demo.check(waits[0] == 1, "join is marked as a blocking wait");
        Demo.check(steps.equals(List.of("decode \"Decode Query\"", "rerank \"rerank\"")),
                "the labelled phases are decode and rerank, in that order");
    }

    // ------------------------------------------------------------------ 3. a trace too large for its limit

    static void ladder(ExecutorService indexer) throws Exception {
        // A large budget records all 2,000 spans as rows, so that rendering, not recording, decides what
        // to fold: the same trace can then go out whole as JSON and trimmed in a header.
        Tempo.Trace t = Tempo.trace(UPDATE, new Tempo.Options().budget(4_096).strict(true)).records(Tempo.STEP);
        try (t) {
            List<Future<?>> shards = new ArrayList<>();
            for (int i = 0; i < SHARDS; i++) {
                shards.add(indexer.submit(() -> {
                    for (int d = 0; d < DOCS_PER_SHARD; d++) {
                        try (Tempo.Span tokenize = Tempo.step(TOKENIZE)) {   // under the shard task
                            Demo.cpu(5);
                            tokenize.set(TERMS, 40 + d);
                        }
                    }
                }));
            }
            try (Tempo.Span join = Tempo.span(JOIN).waiting()) {
                for (Future<?> f : shards) f.get();
                join.set(DOCS, SHARDS * DOCS_PER_SHARD);
            }
        }
        // Each render of a Trace copies it first; to render it several times, copy once.
        Tempo.Snapshot snap = t.snapshot();
        t.release();
        int recorded = Demo.count(snap, v -> true);

        Demo.say(String.format(Locale.ROOT, "%s: %d spans; its Tempo-Timing value at three limits:", UPDATE, recorded));
        Demo.say("     limit    bytes  records  of them aggregates  folded  dropped");
        int[] limits = {Integer.MAX_VALUE, HEADER_LIMIT, 600};
        int previous = Integer.MAX_VALUE;
        for (int limit : limits) {
            String value = snap.toHeader(limit);
            int records = Integer.parseInt(Demo.field(value, "e"), 36), singles = records - aggregates(value);
            String x = Demo.field(value, "x");
            int folded = x == null ? 0 : Integer.parseInt(x.substring(0, x.indexOf('.')), 36);
            int dropped = x == null ? 0 : Integer.parseInt(x.substring(x.indexOf('.') + 1), 36);
            Demo.say(String.format(Locale.ROOT, "  %8s %8d %8d %19d %7d %8d", limit == Integer.MAX_VALUE ? "none" : limit,
                    Demo.bytes(value), records, records - singles, folded, dropped));

            Demo.check(Demo.bytes(value) <= limit, "the value at limit " + limit + " fits it");
            Demo.check(singles + folded + dropped == recorded,
                    "every span is a record of its own, folded into an aggregate, or counted dropped (limit " + limit + ")");
            Demo.check(limit == Integer.MAX_VALUE ? x == null && records == recorded : x != null && records < previous,
                    "a smaller limit puts fewer records on the wire and says so in x= (limit " + limit + ")");
            previous = records;
        }
        Demo.say("x=folded.dropped: spans inside aggregates, and spans not on the wire at all; with the");
        Demo.say("records that are not aggregates they add up to every span recorded, at every limit");

        // A link with a limit of its own, base included, to a viewer given per call.
        String link = snap.toLink(VIEWER, 1_000);
        Demo.say(String.format(Locale.ROOT, "toLink(VIEWER, 1000): %d bytes, %s...", Demo.bytes(link), link.substring(0, VIEWER.length() + 8)));
        Demo.check(Demo.bytes(link) <= 1_000, "toLink(base, 1000) is at most 1,000 bytes, base included");
        Demo.check(link.startsWith(VIEWER) && link.contains("#t="), "toLink(base, maxBytes) opens the given viewer");
    }

    // ------------------------------------------------------------------ 4. sending it

    static void send(Response response) throws IOException {
        String body = response.body(); // long after release: the snapshot needs no trace
        Demo.say(String.format(Locale.ROOT, "the body, %d bytes, follows the headers: the hits and the whole trace as JSON",
                Demo.bytes(body)));
        Demo.respond(response.runtime, response.serverTiming, response.tempoTiming, response.tempoLink);
        Demo.body(body);
    }

    // ------------------------------------------------------------------ helpers

    static double ms(long nanos) {
        return nanos / 1e6;
    }

    /** Total records of a value: those with {@code +count.min.max.extent.maxAt}. */
    static int aggregates(String value) {
        int n = 0;
        for (String record : Demo.field(value, "S").split("/")) if (record.indexOf('+') >= 0) n++;
        return n;
    }

    /** Braces and brackets pair up outside strings: enough to tell one JSON value, without a parser. */
    static boolean balanced(String json) {
        StringBuilder open = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                open.append(c == '{' ? '}' : ']');
            } else if (c == '}' || c == ']') {
                if (open.length() == 0 || open.charAt(open.length() - 1) != c) return false;
                open.setLength(open.length() - 1);
                if (open.length() == 0 && i != json.length() - 1) return false;
            }
        }
        return open.length() == 0 && !inString && json.startsWith("{");
    }
}
