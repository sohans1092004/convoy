package convoy.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.lang.Nullable;

/**
 * Records serialise every component, so each one is required in OpenAPI unless it is {@code @JsonInclude(NON_NULL)};
 * only components marked {@code @Nullable} may be null. The generated TypeScript client then matches the wire exactly.
 */
public class RecordSchemas implements ModelConverter {
    private static final PropertyNamingStrategies.SnakeCaseStrategy SNAKE = new PropertyNamingStrategies.SnakeCaseStrategy();

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Schema resolve(AnnotatedType type, ModelConverterContext ctx, Iterator<ModelConverter> chain) {
        Schema s = chain.hasNext() ? chain.next().resolve(type, ctx, chain) : null;
        Class<?> c = Json.mapper().constructType(type.getType()).getRawClass();
        if (s == null || !c.isRecord()) return s;
        Schema model = s.get$ref() == null ? s : ctx.getDefinedModels().get(s.get$ref().substring(s.get$ref().lastIndexOf('/') + 1));
        if (model == null || model.getProperties() == null) return s;
        for (RecordComponent rc : c.getRecordComponents()) {
            String name = SNAKE.translate(rc.getName());
            Schema prop = (Schema) model.getProperties().get(name);
            if (prop == null) continue;
            java.lang.reflect.Field f;
            try {
                f = c.getDeclaredField(rc.getName());
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
            JsonInclude inc = f.isAnnotationPresent(JsonInclude.class) ? f.getAnnotation(JsonInclude.class) : c.getAnnotation(JsonInclude.class);
            if (inc == null || inc.value() != JsonInclude.Include.NON_NULL) {
                if (model.getRequired() == null || !model.getRequired().contains(name)) model.addRequiredItem(name);
            }
            if (f.isAnnotationPresent(Nullable.class)) {
                // OpenAPI 3.1: null is one of the types (there is no "nullable").
                if (prop.get$ref() != null) {
                    model.getProperties().put(name, new JsonSchema().anyOf(List.of(prop, new JsonSchema().types(Set.of("null")))));
                } else {
                    Set<String> types = new LinkedHashSet<>(prop.getTypes() != null ? prop.getTypes() : Set.of(prop.getType()));
                    types.add("null");
                    prop.setTypes(types);
                }
            }
        }
        return s;
    }
}
