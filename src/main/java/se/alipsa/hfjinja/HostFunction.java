package se.alipsa.hfjinja;

import java.util.List;

/**
 * An explicitly registered callable available to a template render.
 *
 * <p>Arguments are immutable, inert Java scalars and recursively immutable lists or maps. Return
 * values must satisfy the same closed host-value boundary as render context values.
 */
@FunctionalInterface
public interface HostFunction {
  Object apply(List<Object> arguments);
}
