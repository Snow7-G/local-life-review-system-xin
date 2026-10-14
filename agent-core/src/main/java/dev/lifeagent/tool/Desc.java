package dev.lifeagent.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Field-level documentation copied into the generated JSON Schema.
 *
 * <p>Placed on a record component (or on the argument record type itself), the text becomes the
 * {@code description} of that property in the schema the model reads. Written in Chinese for the
 * same reason as {@link AgentTool#description()}: it is prompt text.</p>
 *
 * <pre>{@code
 * public record SearchNearbyArgs(
 *         @Desc("品类，比如火锅、KTV") String category,
 *         @Desc("半径，单位米，最大 5000") int radius) { }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
        ElementType.RECORD_COMPONENT,
        ElementType.FIELD,
        ElementType.METHOD,
        ElementType.PARAMETER,
        ElementType.TYPE
})
public @interface Desc {

    /** Model-facing description of the annotated field or record. */
    String value();
}
