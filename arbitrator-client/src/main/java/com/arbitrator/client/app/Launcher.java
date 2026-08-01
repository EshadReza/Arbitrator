package com.arbitrator.client.app;

/**
 * Entry point for running the client from the CLASSPATH instead of the module
 * path — which is how you should normally start it.
 *
 * Two problems disappear by launching this way:
 *
 *  1. "JavaFX runtime components are missing" — the JVM only performs that
 *     check when the main class itself extends Application. Going through a
 *     plain class sidesteps it, so no --module-path/--add-modules is needed
 *     and Eclipse's "Run As → Java Application" works with zero VM arguments.
 *
 *  2. "Module jdk.jsobject not found, required by javafx.web" — jdk.jsobject
 *     was removed from the JDK in Java 11 and is not published as a Maven
 *     artifact, so javafx-web can never resolve on the module path from Maven
 *     dependencies alone. On the classpath the module graph is not consulted
 *     at all, so the requirement never applies.
 *
 * Run this class, not {@link ArbitratorApp}.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        ArbitratorApp.main(args);
    }
}
