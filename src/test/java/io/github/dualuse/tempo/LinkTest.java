package io.github.dualuse.tempo;

import io.github.dualuse.tempo.Tempo.Options;
import io.github.dualuse.tempo.Tempo.Span;
import io.github.dualuse.tempo.Tempo.Trace;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

/** {@code Tempo-Link}: the base baked in at build time, its overrides, and the packing of FORMAT.md section 9. */
class LinkTest {

    final TempoTest.Clock clock = new TempoTest.Clock();

    /**
     * The build bakes the GitHub Pages site of GITHUB_REPOSITORY into the jar, so a fork links to its own
     * viewer. Derived here independently of the build script; run with {@code GITHUB_REPOSITORY=owner/repo}.
     */
    @Test
    void theBakedBaseIsThePagesSiteOfTheRepositoryBuilt() throws Exception {
        String repository = System.getProperty("tempo.githubRepository", "");
        String expected = pagesOf(repository);
        try (InputStream in = Tempo.class.getResourceAsStream(Tempo.Link.RESOURCE)) {
            assertNotNull(in, "the build writes " + Tempo.Link.RESOURCE);
            assertEquals(expected + "\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(expected, Tempo.Link.BUILT, "GITHUB_REPOSITORY=" + repository);
        if (System.getProperty("tempo.linkBase") == null) {
            Trace t = Tempo.start(TempoTest.SEARCH, new Options().clock(clock));
            t.close();
            assertTrue(t.toLink().startsWith(expected + "#t=z."), t.toLink());
        }
    }

    private static String pagesOf(String repository) {
        int slash = repository.indexOf('/');
        if (slash <= 0 || !repository.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+")) return Tempo.Link.UPSTREAM;
        String owner = repository.substring(0, slash).toLowerCase(java.util.Locale.ROOT), repo = repository.substring(slash + 1);
        return repo.equalsIgnoreCase(owner + ".github.io") ? "https://" + owner + ".github.io/" : "https://" + owner + ".github.io/" + repo + "/";
    }

    @Test
    void optionsWinOverTheSystemPropertyWhichWinsOverTheBakedBase() {
        Trace t = Tempo.start(TempoTest.SEARCH, new Options().clock(clock));
        Trace u = Tempo.start(TempoTest.SEARCH, new Options().clock(clock).linkBase("file:///opt/viewer/index.html"));
        String saved = System.getProperty("tempo.linkBase");
        try {
            System.setProperty("tempo.linkBase", "https://viewer.example/tempo/");
            assertTrue(t.toLink().startsWith("https://viewer.example/tempo/#t=z."), t.toLink());
            assertTrue(u.toLink().startsWith("file:///opt/viewer/index.html#t=z."), u.toLink());
            System.setProperty("tempo.linkBase", "not a base");                      // ignored
            assertTrue(t.toLink().startsWith(Tempo.Link.BUILT + "#t=z."), t.toLink());
        } finally {
            if (saved == null) System.clearProperty("tempo.linkBase");
            else System.setProperty("tempo.linkBase", saved);
        }
        u.close();
        t.close();
    }

    @Test
    void aBaseIsAnAbsoluteUrlWithoutFragmentCommaOrWhitespace() {
        for (String ok : new String[]{"https://dualuse.github.io/tempo/", "file:///tmp/index.html", "http://127.0.0.1/viewer/?x=1"}) {
            assertEquals(ok, new Options().linkBase(ok).linkBase);
        }
        for (String bad : new String[]{"", "tempo/", "//host/", "1http://x/", "https://x/#", "https://x/a,b", "https://x /",
                "https://x/\t", "https://über/"}) {
            assertThrows(IllegalArgumentException.class, () -> new Options().linkBase(bad), bad);
        }
        assertNull(new Options().linkBase("https://x/").linkBase(null).linkBase);
    }

    /** The fragment inflates to the header value; after release both are empty. */
    @Test
    void theLinkCarriesTheHeaderValue() throws Exception {
        Trace t = Tempo.start(TempoTest.SEARCH, new Options().clock(clock).wallClock(() -> 1794567890123L)
                .linkBase("https://viewer.example/tempo/"));
        Span s = t.root().span(TempoTest.DECODE);
        clock.add(3_000_000);
        s.close();
        clock.add(1_000_000);
        t.close();
        String header = t.toHeader(), link = t.toLink();
        assertEquals(header, unpack(link, "https://viewer.example/tempo/"));
        assertEquals(t.snapshot().toLink(), link);
        t.release();
        assertEquals("", t.toLink());
    }

    /** The worked example (FORMAT.md section 10) round-trips; its golden link decodes to the same value. */
    @Test
    void theWorkedExampleRoundTrips() throws Exception {
        String value = new String(Files.readAllBytes(GoldenTest.DATA.resolve("golden/search-example.header")),
                StandardCharsets.US_ASCII).trim();
        String golden = new String(Files.readAllBytes(GoldenTest.DATA.resolve("golden/search-example.link")),
                StandardCharsets.US_ASCII).trim();
        assertEquals(value, unpack(golden, Tempo.Link.UPSTREAM));
        String link = Tempo.Link.of(Tempo.Link.UPSTREAM, value);
        assertEquals(value, unpack(link, Tempo.Link.UPSTREAM));
        assertTrue(link.length() < value.length() + 40, link);
        assertEquals("", Tempo.Link.of(Tempo.Link.UPSTREAM, ""));
    }

    /** The value of a {@code <base>#t=z.<payload>} link, by raw INFLATE; fails on anything else. */
    static String unpack(String link, String base) throws DataFormatException {
        assertTrue(link.startsWith(base + "#t=z."), link);
        String payload = link.substring(base.length() + 5);
        assertTrue(payload.matches("[A-Za-z0-9_-]+"), "base64url without padding: " + payload);
        Inflater in = new Inflater(true);
        try {
            in.setInput(Base64.getUrlDecoder().decode(payload));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!in.finished()) {
                int n = in.inflate(buf);
                if (n == 0 && (in.needsInput() || in.needsDictionary())) fail("incomplete DEFLATE stream");
                out.write(buf, 0, n);
            }
            assertEquals(0, in.getRemaining(), "bytes after the DEFLATE stream");
            return new String(out.toByteArray(), StandardCharsets.US_ASCII);
        } finally {
            in.end();
        }
    }
}
