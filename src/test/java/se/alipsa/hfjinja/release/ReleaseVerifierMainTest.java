package se.alipsa.hfjinja.release;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReleaseVerifierMainTest {
  @Test
  void requiresGradleUserHome() {
    assertThrows(
        IllegalStateException.class,
        () -> ReleaseVerifierMain.requireIsolation(List.of("gradlew", "check"), false));
  }

  @Test
  void requiresOfflineWhenRequested() {
    assertThrows(
        IllegalStateException.class,
        () ->
            ReleaseVerifierMain.requireIsolation(
                List.of("gradlew", "--gradle-user-home", "/tmp/home", "check"), true));
  }

  @Test
  void acceptsCompleteIsolatedCommand() {
    assertDoesNotThrow(
        () ->
            ReleaseVerifierMain.requireIsolation(
                List.of("gradlew", "--gradle-user-home", "/tmp/home", "--offline", "check"), true));
  }

  @Test
  void readsContractPolicyInputs() throws Exception {
    var contract = Files.createTempFile("release-contract", ".json");
    Files.writeString(contract, "{\"policyInputs\":[\"NOTICE\",\"CHANGELOG.md\"]}");
    assertEquals(
        List.of("NOTICE", "CHANGELOG.md"),
        ReleaseVerifierMain.stringArray(contract, "policyInputs"));
    Files.deleteIfExists(contract);
  }
}
