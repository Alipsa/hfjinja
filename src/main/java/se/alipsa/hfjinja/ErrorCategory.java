package se.alipsa.hfjinja;

/** Stable categories for template and host-boundary failures. */
public enum ErrorCategory {
  SYNTAX,
  UNDEFINED_OR_ACCESS,
  TYPE,
  ARITY,
  VALUE,
  EXPLICIT_RAISE,
  HOST_FUNCTION,
  HOST_CONVERSION,
  RESOURCE_LIMIT,
  OUTPUT
}
