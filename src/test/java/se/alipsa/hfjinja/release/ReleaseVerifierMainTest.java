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

  @Test
  void rejectsWrongDaemonJdk() throws Exception {
    var source = Files.createTempDirectory("release-environment");
    Files.createDirectories(source.resolve("req"));
    Files.createDirectories(source.resolve("upstream"));
    Files.writeString(source.resolve("req/release-verification.json"), "{\"jdkMajor\":21}");
    Files.writeString(
        source.resolve("upstream/upstream-lock.json"), "{\"nodeVersion\":\"v26.7.0\"}");
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> ReleaseVerifierMain.verifyEnvironment(source, "25.0.4"));
    assertEquals("required Gradle daemon JDK major 21, got 25", failure.getMessage());
  }

  @Test
  void rejectsConsumerWithPluginRepository() throws Exception {
    var consumer = Files.createTempDirectory("consumer");
    var candidate = Files.createTempDirectory("candidate");
    var repository = Files.createTempDirectory("repository");
    Files.writeString(candidate.resolve("gradlew"), "#!/bin/sh\n");
    Files.writeString(
        consumer.resolve("settings.gradle"),
        "pluginManagement { repositories { mavenCentral() } }");
    Files.writeString(
        consumer.resolve("build.gradle"),
        "repositories { maven { url = uri('" + repository.toUri() + "') } }");
    Files.writeString(
        consumer.resolve("gradle.properties"),
        "org.gradle.java.installations.auto-download=false\n");
    assertThrows(
        IllegalStateException.class,
        () -> ReleaseVerifierMain.verifyConsumerStructure(consumer, candidate, repository));
  }

  @Test
  void rejectsArchiveEvidenceWithoutMainJar() throws Exception {
    var evidence = Files.createTempFile("archive-evidence", ".json");
    Files.writeString(evidence, "{\"archives\":[]}");
    assertThrows(
        IllegalStateException.class, () -> ReleaseVerifierMain.mainArchiveDigest(evidence));
  }
}
