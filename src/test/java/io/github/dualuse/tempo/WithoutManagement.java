package io.github.dualuse.tempo;

/**
 * Run by {@code TempoTest} in a JVM limited to {@code java.base}: {@code Tempo} must load and record
 * without {@code java.management}, with every CPU time "not measured".
 */
public final class WithoutManagement {

    public static void main(String[] args) {
        boolean management;
        try {
            Class.forName("java.lang.management.ManagementFactory");
            management = true;
        } catch (ClassNotFoundException e) {
            management = false;
        }
        Tempo.Trace t = Tempo.trace(Tempo.name("search"), new Tempo.Options().cpu(true)).records(Tempo.STEP);
        t.span(Tempo.name("decode")).cpu().close();
        t.close();
        StringBuilder b = new StringBuilder("management=" + management);
        t.forEachSpan(s -> b.append(' ').append(s.name()).append(" cpu=").append(s.cpuNanos()));
        System.out.println(b);
    }
}
