package io.github.dualuse.tempo;

import java.lang.ref.Reference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Estimated bytes strongly reachable from a root through Tempo's own objects and arrays, with compressed
 * oops (12-byte headers, 4-byte references, 8-byte alignment). Threads, names, classes, the shared
 * sentinels and, unless it is the root, a thread's slot (with its pool of handles) are shared and not
 * counted; a {@link Reference} counts as one small object whose referent is not followed; any other
 * JDK object counts 16 bytes and is not followed.
 */
final class Retained {
    final Map<String, Integer> instances = new TreeMap<>();
    long bytes;

    static Retained of(Object root) {
        Retained r = new Retained();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        Deque<Object> todo = new ArrayDeque<>();
        todo.push(root);
        while (!todo.isEmpty()) {
            Object o = todo.pop();
            if (seen.put(o, Boolean.TRUE) != null || o instanceof Thread || o instanceof Tempo.Name || o instanceof Class
                    || o == Tempo.Lane.POOLED || o == Tempo.Context.NONE || o instanceof Tempo.ThreadSlot && o != root) continue;
            Class<?> c = o.getClass();
            r.instances.merge(c.getSimpleName(), 1, Integer::sum);
            if (c.isArray()) {
                int n = Array.getLength(o);
                Class<?> e = c.getComponentType();
                r.bytes += align(16 + (long) n * size(e));
                if (!e.isPrimitive()) for (int i = 0; i < n; i++) push(todo, Array.get(o, i));
            } else if (o instanceof Reference) {
                r.bytes += 32;
            } else if (c.getName().startsWith(Tempo.class.getName() + "$")) {
                long shallow = 12;
                for (Class<?> k = c; k != Object.class; k = k.getSuperclass()) { // a Trace's fields are mostly its Span's
                    for (Field f : k.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        shallow += size(f.getType());
                        if (f.getType().isPrimitive()) continue;
                        f.setAccessible(true);
                        try {
                            push(todo, f.get(o));
                        } catch (IllegalAccessException x) {
                            throw new AssertionError(x);
                        }
                    }
                }
                r.bytes += align(shallow);
            } else {
                r.bytes += 16;
            }
        }
        return r;
    }

    int count(Class<?> c) {
        return instances.getOrDefault(c.getSimpleName(), 0);
    }

    private static void push(Deque<Object> todo, Object o) {
        if (o != null) todo.push(o);
    }

    private static long size(Class<?> t) {
        return t == long.class || t == double.class ? 8 : t == int.class || t == float.class ? 4
                : t == short.class || t == char.class ? 2 : t == byte.class || t == boolean.class ? 1 : 4;
    }

    private static long align(long n) {
        return (n + 7) & ~7L;
    }

    @Override
    public String toString() {
        return bytes + " B " + instances;
    }
}
