# Local tokenizer-config example

Compile with Java 21 against a built hfjinja JAR:

    javac --release 21 --module-path ../../build/libs/hfjinja-0.5.0-SNAPSHOT.jar --add-modules se.alipsa.hfjinja -d . TokenizerConfigExample.java
    java --module-path ../../build/libs/hfjinja-0.5.0-SNAPSHOT.jar --add-modules se.alipsa.hfjinja -cp . example.TokenizerConfigExample

It reads this local tokenizer_config.json, extracts its string chat_template, parses it once, and
renders a prompt. Real applications should use their chosen JSON parser to read the config; the
small extractor keeps this dependency-free example runnable with only the JDK and hfjinja.
