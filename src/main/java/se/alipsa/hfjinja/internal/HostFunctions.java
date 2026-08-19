package se.alipsa.hfjinja.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.HostFunction;
import se.alipsa.hfjinja.RenderOptions;
import se.alipsa.hfjinja.SourceLocation;
import se.alipsa.hfjinja.TemplateRenderException;

/** Dispatches the closed, explicitly registered host-function boundary. */
final class HostFunctions {
  private HostFunctions() {}

  static Value invoke(
      RenderOptions options,
      String name,
      List<Value> positionalArguments,
      boolean hasKeywordArguments,
      SourceLocation location) {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(positionalArguments, "positionalArguments");
    Objects.requireNonNull(location, "location");
    if (hasKeywordArguments) {
      throw failure("Host function '" + name + "' does not accept keyword arguments", null, location);
    }
    var function = options.hostFunctions().get(name);
    if (function == null) {
      throw failure("Unknown host function: " + name, null, location);
    }

    final Object result;
    try {
      result = function.apply(hostArguments(positionalArguments));
    } catch (RuntimeException exception) {
      throw failure("Host function '" + name + "' failed", exception, location);
    }
    try {
      return Values.fromHost(result);
    } catch (TemplateRenderException exception) {
      throw failure("Host function '" + name + "' returned an unsupported value", exception, location);
    }
  }

  private static List<Object> hostArguments(List<Value> positionalArguments) {
    var arguments = new ArrayList<Object>(positionalArguments.size());
    for (var argument : positionalArguments) {
      arguments.add(Values.toHost(Objects.requireNonNull(argument, "positional argument")));
    }
    return Collections.unmodifiableList(arguments);
  }

  private static TemplateRenderException failure(
      String message, Throwable cause, SourceLocation location) {
    return cause == null
        ? new TemplateRenderException(message, ErrorCategory.HOST_FUNCTION, location)
        : new TemplateRenderException(message, cause, ErrorCategory.HOST_FUNCTION, location);
  }
}
