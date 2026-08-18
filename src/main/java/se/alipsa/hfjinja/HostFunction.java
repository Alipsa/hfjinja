package se.alipsa.hfjinja;

import java.util.List;

/** An explicitly registered callable available to a template render. */
@FunctionalInterface
public interface HostFunction {
  Object apply(List<Object> arguments);
}
