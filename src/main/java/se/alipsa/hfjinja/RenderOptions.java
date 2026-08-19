package se.alipsa.hfjinja;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable render-time clock, zone, and explicitly named host functions. */
public final class RenderOptions {
  private static final Set<String> BUILTIN_GLOBALS =
      Set.of("false", "true", "none", "raise_exception", "range", "strftime_now", "True", "False", "None");

  public static final RenderOptions DEFAULT = new RenderOptions(null, null, Map.of());

  private final Clock clock;
  private final ZoneId zoneId;
  private final Map<String, HostFunction> hostFunctions;

  private RenderOptions(Clock clock, ZoneId zoneId, Map<String, HostFunction> hostFunctions) {
    this.clock = clock;
    this.zoneId = zoneId;
    this.hostFunctions = Collections.unmodifiableMap(new LinkedHashMap<>(hostFunctions));
  }

  /** Starts construction of immutable render options. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns the optional caller-supplied clock. */
  public Optional<Clock> clock() {
    return Optional.ofNullable(clock);
  }

  /** Returns the optional caller-supplied time zone. */
  public Optional<ZoneId> zoneId() {
    return Optional.ofNullable(zoneId);
  }

  /** Returns the immutable functions that are callable by name from a template. */
  public Map<String, HostFunction> hostFunctions() {
    return hostFunctions;
  }

  /** Builder for {@link RenderOptions}. */
  public static final class Builder {
    private Clock clock;
    private ZoneId zoneId;
    private final List<HostFunctionRegistration> hostFunctions = new ArrayList<>();

    private Builder() {}

    public Builder clock(Clock clock) {
      this.clock = Objects.requireNonNull(clock, "clock");
      return this;
    }

    public Builder zoneId(ZoneId zoneId) {
      this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
      return this;
    }

    /** Registers a function under a template-visible name. */
    public Builder hostFunction(String name, HostFunction function) {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("Host function name must be non-blank");
      }
      hostFunctions.add(new HostFunctionRegistration(name, Objects.requireNonNull(function, "function")));
      return this;
    }

    /** Validates registrations and creates immutable render options. */
    public RenderOptions build() {
      var functions = new LinkedHashMap<String, HostFunction>();
      for (var registration : hostFunctions) {
        if (BUILTIN_GLOBALS.contains(registration.name)) {
          throw new IllegalArgumentException("Host function name collides with built-in global: " + registration.name);
        }
        if (functions.putIfAbsent(registration.name, registration.function) != null) {
          throw new IllegalArgumentException("Duplicate host function name: " + registration.name);
        }
      }
      return new RenderOptions(clock, zoneId, functions);
    }
  }

  private record HostFunctionRegistration(String name, HostFunction function) {}
}
