package se.alipsa.hfjinja.internal.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.alipsa.hfjinja.ErrorCategory;
import se.alipsa.hfjinja.SourceLocation;
import se.alipsa.hfjinja.TemplateRenderException;
import se.alipsa.hfjinja.internal.Value;

final class Environment {
  private final Environment parent;
  private final Map<String, Value> variables = new LinkedHashMap<>();
  Environment(Environment parent) {
    this.parent = parent;
    variables.put("namespace", new Value.CallableValue(this::namespace));
  }
  void set(String name, Value value) {
    if (variables.containsKey(name)) throw new IllegalStateException("Variable already declared: " + name);
    variables.put(name, value);
  }
  void setVariable(String name, Value value) { variables.put(name, value); }
  Value lookupVariable(String name) {
    for (var env = this; env != null; env = env.parent) { var value = env.variables.get(name); if (value != null) return value; }
    return Value.UndefinedValue.INSTANCE;
  }
  private Value namespace(List<Value> args, boolean keywords, SourceLocation location) {
    if (args.size() > 1
        || (!args.isEmpty()
            && !(args.get(0) instanceof Value.ObjectValue)
            && !(args.get(0) instanceof Value.KeywordArgumentsValue)))
      throw new TemplateRenderException("`namespace` expects either zero arguments or a single object argument", ErrorCategory.TYPE, location);
    return args.isEmpty() ? new Value.ObjectValue(new LinkedHashMap<>()) : args.get(0);
  }
}
