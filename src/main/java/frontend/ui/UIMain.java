package frontend.ui;

/**
 * Bootstrap main class for running JavaFX from a fat-jar via `java -jar`.
 *
 * If the jar's Main-Class is a subclass of {@code javafx.application.Application},
 * the Java launcher may require JavaFX to be present as a runtime module.
 * This indirection keeps the Main-Class non-JavaFX, while still launching the app.
 */
public final class UIMain {
    private UIMain() {}

    /**
     * Bootstrap-Main für den Start der JavaFX-App aus dem Fat-Jar.
     *
     * @param args Programmargumente
     */
    public static void main(String[] args) {
        IsleOfSkyeApp.main(args);
    }
}
