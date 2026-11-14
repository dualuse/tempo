package io.github.dualuse.tempo;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;

/**
 * Jackson support for {@link Tempo}, in its own optional file: drop it when Jackson is not on the class
 * path, {@code Tempo.java} does not need it. It writes a {@link Tempo.Snapshot} in the JSON form of
 * {@code FORMAT.md} section 8 through {@link JsonGenerator} calls, so the same structure goes to JSON
 * (as text equal to {@link Tempo.Snapshot#writeJson}), CBOR, Smile, MessagePack or any other Jackson
 * format.
 *
 * <pre>{@code
 * ObjectMapper mapper = new ObjectMapper().registerModule(TempoJackson.module());
 *
 * class Response {
 *     public Tempo.Snapshot tempo; // t.snapshot(), taken while the response is built; never the Trace
 * }
 * }</pre>
 *
 * Without the module, a field can name the serializer itself:
 * {@code @JsonSerialize(using = TempoJackson.SnapshotSerializer.class)}. A response object holds a
 * {@code Snapshot}, never a {@link Tempo.Trace}: the body is often serialized after the request released
 * its trace, which would then be empty. Uses the Jackson 2 API as of 2.16.
 */
public final class TempoJackson {

    private TempoJackson() {}

    /** A module that serializes {@link Tempo.Snapshot} with {@link SnapshotSerializer}. */
    public static SimpleModule module() {
        return new SimpleModule("Tempo").addSerializer(Tempo.Snapshot.class, new SnapshotSerializer());
    }

    /** Writes a {@link Tempo.Snapshot} in the JSON form; stateless, so one instance serves every thread. */
    public static final class SnapshotSerializer extends StdSerializer<Tempo.Snapshot> {
        private static final long serialVersionUID = 1L;

        public SnapshotSerializer() {
            super(Tempo.Snapshot.class);
        }

        @Override
        public void serialize(Tempo.Snapshot snapshot, JsonGenerator generator, SerializerProvider provider) throws IOException {
            Tempo.Header.json(snapshot, new Generator(generator));
        }
    }

    /**
     * The JSON form's tokens as generator calls. A double attribute goes to a text format as the wire
     * writes it, to a binary one as the number that text stands for: a long when it has no point and fits
     * one, else a double.
     */
    static final class Generator extends Tempo.JsonSink {
        private final JsonGenerator g;
        private StringBuilder number;

        Generator(JsonGenerator g) {
            this.g = g;
        }

        @Override
        void begin(char bracket) throws IOException {
            if (bracket == '{') g.writeStartObject();
            else g.writeStartArray();
        }

        @Override
        void end(char bracket) throws IOException {
            if (bracket == '}') g.writeEndObject();
            else g.writeEndArray();
        }

        @Override
        void key(String key) throws IOException {
            g.writeFieldName(key);
        }

        @Override
        void string(String s) throws IOException {
            g.writeString(s);
        }

        @Override
        void number(long v) throws IOException {
            g.writeNumber(v);
        }

        @Override
        void decimal(double v) throws IOException {
            if (number == null) number = new StringBuilder(24);
            number.setLength(0);
            Tempo.Header.number(number, v);
            String text = number.toString();
            if (g.canWriteFormattedNumbers()) g.writeNumber(text);
            else if (text.indexOf('.') < 0 && Math.abs(v) < 0x1p63) g.writeNumber(Long.parseLong(text));
            else g.writeNumber(Double.parseDouble(text)); // past a long (2^63 and up, written in full) too
        }

        @Override
        void bool(boolean v) throws IOException {
            g.writeBoolean(v);
        }
    }
}
