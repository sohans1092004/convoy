package convoy.platform.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The endpoint requires an Idempotency-Key header (428 if absent). The first completed response per
 * (key, caller) is stored and replayed; the same key with a different body is 422 (api-outline.md).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {}
