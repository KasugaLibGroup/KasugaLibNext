package lib.kasuga.shader;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks shader APIs that bypass typed-IR validation and can emit invalid or backend-specific GLSL.
 * Prefer typed builder operations whenever they can express the same program.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface DelicateShaderApi {}
