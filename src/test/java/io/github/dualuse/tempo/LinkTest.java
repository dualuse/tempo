package io.github.dualuse.tempo;

import io.github.dualuse.tempo.Tempo.Options;
import io.github.dualuse.tempo.Tempo.Span;
import io.github.dualuse.tempo.Tempo.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

/** {@code Tempo-Link}: the base baked in at build time, its overrides, and the packing of FORMAT.md section 9. */
class LinkTest {

    @AfterEach
    void noTraceLeftOnThread() {
        TempoTest.assertNothingLeftOpen();
    }

    final TempoTest.Clock clock = new TempoTest.Clock();

    /**
     * The build bakes a base into the jar: {@code -PlinkBase}, else the GitHub Pages site of
     * GITHUB_REPOSITORY, else of the git remote origin, else the constant; so a fork links to its own
     * viewer. Derived here on its own from what the build saw; run with {@code GITHUB_REPOSITORY=owner/repo}
     * or {@code -PlinkBase=url} to try the others.
     */
    @Test
    void theBakedBaseFollowsTheBuild() throws Exception {
        String property = System.getProperty("tempo.linkBaseProperty", ""), repository = System.getProperty("tempo.githubRepository", ""),
                remote = System.getProperty("tempo.gitOrigin", "");
        String expected = !property.isEmpty() ? property
                : pagesOf(repository) != null ? pagesOf(repository)
                : pagesOf(repositoryOfRemote(remote)) != null ? pagesOf(repositoryOfRemote(remote)) : Tempo.Link.DEFAULT_LINK_BASE;
        try (InputStream in = Tempo.class.getResourceAsStream(Tempo.Link.RESOURCE)) {
            assertNotNull(in, "the build writes " + Tempo.Link.RESOURCE);
            assertEquals(expected + "\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(expected, Tempo.Link.Built.BASE, property + " " + repository + " " + remote);
        if (System.getProperty("tempo.linkBase") == null) {
            Trace t = Tempo.trace(TempoTest.SEARCH, new Options().clock(clock));
            t.close();
            assertTrue(t.toLink().startsWith(expected + "#t=z."), t.toLink());
        }
    }

    @Test
    void remotesAndRepositoriesMapToPagesSites() {
        assertEquals("https://some-owner.github.io/tempo-fork/", pagesOf(repositoryOfRemote("git@github.com:Some-Owner/tempo-fork.git")));
        assertEquals("https://some-owner.github.io/tempo-fork/", pagesOf(repositoryOfRemote("https://github.com/some-owner/tempo-fork")));
        assertEquals("https://some-owner.github.io/", pagesOf("some-owner/Some-Owner.github.io"));
        assertNull(repositoryOfRemote("git@gitlab.com:some-owner/tempo-fork.git"));
        assertNull(pagesOf("not a repository"));
    }

    private static String pagesOf(String repository) {
        if (repository == null || !repository.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+")) return null;
        int slash = repository.indexOf('/');
        String owner = repository.substring(0, slash).toLowerCase(java.util.Locale.ROOT), repo = repository.substring(slash + 1);
        return repo.equalsIgnoreCase(owner + ".github.io") ? "https://" + owner + ".github.io/" : "https://" + owner + ".github.io/" + repo + "/";
    }

    private static String repositoryOfRemote(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:(?:https?|ssh|git)://(?:[^@/]+@)?github\\.com(?::\\d+)?/|(?:[^@/]+@)?github\\.com:)([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?")
                .matcher(url.trim());
        return m.matches() ? m.group(1) + "/" + m.group(2) : null;
    }

    @Test
    void optionsWinOverTheSystemPropertyWhichWinsOverTheBakedBase() {
        Trace t = Tempo.trace(TempoTest.SEARCH, new Options().clock(clock));
        t.close(); // one at a time: a trace started inside another joins it
        Trace u = Tempo.trace(TempoTest.SEARCH, new Options().clock(clock).linkBase("file:///opt/viewer/index.html"));
        u.close();
        String saved = System.getProperty("tempo.linkBase");
        try {
            System.setProperty("tempo.linkBase", "https://viewer.example/tempo/");
            assertTrue(t.toLink().startsWith("https://viewer.example/tempo/#t=z."), t.toLink());
            assertTrue(u.toLink().startsWith("file:///opt/viewer/index.html#t=z."), u.toLink());
            System.setProperty("tempo.linkBase", "not a base");                      // ignored
            assertTrue(t.toLink().startsWith(Tempo.Link.Built.BASE + "#t=z."), t.toLink());
        } finally {
            if (saved == null) System.clearProperty("tempo.linkBase");
            else System.setProperty("tempo.linkBase", saved);
        }
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
        Trace t = Tempo.trace(TempoTest.SEARCH, new Options().clock(clock).wallClock(() -> 1794567890123L)
                .linkBase("https://viewer.example/tempo/"));
        Span s = t.span(TempoTest.DECODE);
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
        assertEquals(value, unpack(golden, Tempo.Link.DEFAULT_LINK_BASE));
        String link = Tempo.Link.of(Tempo.Link.DEFAULT_LINK_BASE, value);
        assertEquals(value, unpack(link, Tempo.Link.DEFAULT_LINK_BASE));
        assertTrue(link.length() < value.length() + 40, link);
        assertEquals("", Tempo.Link.of(Tempo.Link.DEFAULT_LINK_BASE, ""));
    }

    /** Every golden link (written by tools/tempo_ref.py with zlib) inflates to its vector's value. */
    @Test
    void everyGoldenLinkCarriesItsVector() throws Exception {
        List<Path> links;
        try (Stream<Path> files = Files.list(GoldenTest.DATA.resolve("golden"))) {
            links = files.filter(p -> p.toString().endsWith(".link")).sorted().collect(Collectors.toList());
        }
        assertEquals(18, links.size(), "canonical vectors");
        for (Path p : links) {
            String name = p.getFileName().toString().replace(".link", "");
            String link = new String(Files.readAllBytes(p), StandardCharsets.US_ASCII).trim();
            assertEquals(GoldenTest.golden(name), unpack(link, Tempo.Link.DEFAULT_LINK_BASE), name);
        }
    }

    /** Without the resource, as in a copy of the source file, or with a bad one, links go to the constant. */
    @Test
    void withoutTheBuiltResourceTheConstantIsTheBase() {
        assertEquals(Tempo.Link.DEFAULT_LINK_BASE, Tempo.Link.read(null));
        assertEquals(Tempo.Link.DEFAULT_LINK_BASE, Tempo.Link.read(new ByteArrayInputStream(new byte[0])));
        assertEquals(Tempo.Link.DEFAULT_LINK_BASE, Tempo.Link.read(new ByteArrayInputStream("no base\n".getBytes(StandardCharsets.UTF_8))));
        assertEquals("https://someone.github.io/fork/",
                Tempo.Link.read(new ByteArrayInputStream("https://someone.github.io/fork/\n".getBytes(StandardCharsets.UTF_8))));
    }

    /** An explicit base wins over the options, which win over the default; a bad one is refused. */
    @Test
    void anExplicitBaseWins() throws Exception {
        Trace t = Tempo.trace(TempoTest.SEARCH, new Options().clock(clock).linkBase("https://viewer.example/tempo/"));
        clock.add(1_000_000);
        t.close();
        assertTrue(t.toLink().startsWith("https://viewer.example/tempo/#t=z."), t.toLink());
        assertTrue(t.toLink(null).startsWith("https://viewer.example/tempo/#t=z."));
        assertEquals(t.toHeader(), unpack(t.toLink("file:///v/index.html"), "file:///v/index.html"));
        assertEquals(t.toHeader(), unpack(t.toLink("file:///v/index.html", 1000), "file:///v/index.html"));
        assertThrows(IllegalArgumentException.class, () -> t.toLink("https://x/#"));
        assertThrows(IllegalArgumentException.class, () -> t.toLink("relative/", 1000));
        t.release();
        assertEquals("", t.toLink(null, 1000));
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
