package io.github.dualuse.tempo;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * Jackson serializer that produces the same per-thread-lane format as
 * {@link Scope#toJSON()}. Activated automatically when Jackson is on the
 * classpath via {@code @JsonSerialize} on {@link Scope}.
 *
 * <p>If Jackson is not on the classpath, this class is never loaded.
 */
public class ScopeSerializer extends JsonSerializer<Scope> {
    @Override
    public void serialize(Scope scope, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeRawValue(scope.toJSON());
    }
}
