package se.alipsa.hfjinja.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipFile;

/** Runs the release-only, independent archive reproducibility builds. */
public final class ArchiveReproducibilityMain {
  private ArchiveReproducibilityMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException(
          "usage: ArchiveReproducibilityMain <project-dir> <gradle-user-home>");
    }
    Path project = Path.of(args[0]).toAbsolutePath().normalize();
    Path userHome = Path.of(args[1]).toAbsolutePath().normalize();
    Path evidence = Files.createTempDirectory("hfjinja-archive-evidence-");
    List<Path> first = buildAndCopy(project, userHome, evidence.resolve("first"));
    List<Path> second = buildAndCopy(project, userHome, evidence.resolve("second"));
    for (int index = 0; index < first.size(); index++) {
      if (!sha256(first.get(index)).equals(sha256(second.get(index)))) {
        throw new IllegalStateException(
            "archive is not reproducible: " + first.get(index).getFileName());
      }
    }
    verifyModule(first.get(0));
    System.out.println("archive evidence: " + evidence);
    for (Path archive : first) {
      System.out.println(archive.getFileName() + " " + sha256(archive));
    }
  }

  private static List<Path> buildAndCopy(Path project, Path userHome, Path destination)
      throws Exception {
    run(project, userHome, "clean", "jar", "sourcesJar", "javadocJar");
    Files.createDirectories(destination);
    List<Path> result = new ArrayList<>();
    for (String suffix : List.of(".jar", "-sources.jar", "-javadoc.jar")) {
      try (var files = Files.list(project.resolve("build/libs"))) {
        Path archive =
            files
                .filter(path -> matchesArchive(path, suffix))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing archive ending " + suffix));
        Path copy = destination.resolve(archive.getFileName());
        Files.copy(archive, copy);
        result.add(copy);
      }
    }
    return result;
  }

  private static boolean matchesArchive(Path path, String suffix) {
    String name = path.getFileName().toString();
    return suffix.equals(".jar")
        ? name.endsWith(suffix) && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")
        : name.endsWith(suffix);
  }

  private static void run(Path project, Path userHome, String... tasks)
      throws IOException, InterruptedException {
    String wrapper =
        System.getProperty("os.name").toLowerCase().contains("win") ? "gradlew.bat" : "gradlew";
    List<String> command =
        new ArrayList<>(
            List.of(
                project.resolve(wrapper).toString(),
                "--offline",
                "--gradle-user-home",
                userHome.toString()));
    command.addAll(List.of(tasks));
    Process process = new ProcessBuilder(command).directory(project.toFile()).inheritIO().start();
    if (process.waitFor() != 0) {
      throw new IllegalStateException("archive build failed: " + String.join(" ", command));
    }
  }

  private static void verifyModule(Path archive) throws IOException {
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      byte[] moduleInfo = zip.getInputStream(zip.getEntry("module-info.class")).readAllBytes();
      int major = ((moduleInfo[6] & 0xff) << 8) | (moduleInfo[7] & 0xff);
      if (major != 65) throw new IllegalStateException("expected bytecode major 65, got " + major);
    }
  }

  static String sha256(Path file) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }
}
