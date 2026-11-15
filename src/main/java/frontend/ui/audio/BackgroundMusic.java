package frontend.ui.audio;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import java.net.URL;

/**
 * Globale Hintergrundmusik-Steuerung für JavaFX-Screens.
 * 
 * Diese Klasse verwaltet einen einzelnen MediaPlayer für Hintergrundmusik,
 * der über verschiedene Screens hinweg persistiert. Die Lautstärke wird
 * global gespeichert und beim Wechsel zwischen Screens beibehalten.
 */
public final class BackgroundMusic {

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISCHE FELDER
    // ═══════════════════════════════════════════════════════════════════════════

    /** Aktuell aktiver MediaPlayer (null wenn keine Musik läuft) */
    private static MediaPlayer player;
    
    /** Pfad der aktuell spielenden Ressource (für Duplikat-Erkennung) */
    private static String currentResource;
    
    /** Benutzerdefinierte Lautstärke im Bereich [0,1] - UI-Slider bindet hieran */
    private static double volume = 0.35;
    
    /** 
     * Maximale effektive Wiedergabelautstärke.
     * Begrenzt die tatsächliche Lautstärke, sodass Slider bei 100% nicht zu laut ist.
     */
    private static final double MAX_EFFECTIVE_VOLUME = 0.35;

    // ═══════════════════════════════════════════════════════════════════════════
    // KONSTRUKTOR
    // ═══════════════════════════════════════════════════════════════════════════

    /** Privater Konstruktor verhindert Instanziierung (Utility-Klasse) */
    private BackgroundMusic() {}

    // ═══════════════════════════════════════════════════════════════════════════
    // ÖFFENTLICHE METHODEN
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Startet die Endlosschleife einer Musikdatei.
     * 
     * Falls derselbe Track bereits läuft, wird nur sichergestellt dass er 
     * weiter spielt (z.B. nach Fokus-Verlust). Bei neuem Track wird der alte
     * gestoppt und der neue gestartet.
     *
     * @param resourcePath Classpath-Ressource, z.B. "/music/track.mp3"
     */
    public static synchronized void playLoop(String resourcePath) {
        // Leere/null Pfade ignorieren
        if (resourcePath == null || resourcePath.isBlank()) return;
        
        // Gleicher Track läuft bereits - nur sicherstellen dass er spielt
        if (resourcePath.equals(currentResource) && player != null) {
            if (player.getStatus() != MediaPlayer.Status.PLAYING) {
                player.play();
            }
            return;
        }

        // Alten Player stoppen und aufräumen
        stop();

        // Ressource aus Classpath laden
        URL url = BackgroundMusic.class.getResource(resourcePath);
        if (url == null) {
            System.err.println("[BackgroundMusic] Ressource nicht gefunden: " + resourcePath);
            return;
        }

        // Neuen MediaPlayer erstellen und konfigurieren
        try {
            Media media = new Media(url.toExternalForm());
            player = new MediaPlayer(media);
            player.setCycleCount(MediaPlayer.INDEFINITE);  // Endlosschleife
            
            // Effektive Lautstärke = Benutzer-Volume * max. Grenzwert
            player.setVolume(Math.max(0.0, Math.min(1.0, volume)) * MAX_EFFECTIVE_VOLUME);
            player.play();
            currentResource = resourcePath;
            
        } catch (Exception ex) {
            System.err.println("[BackgroundMusic] Wiedergabe fehlgeschlagen: " + resourcePath 
                    + " -> " + ex.getMessage());
            ex.printStackTrace();
            player = null;
            currentResource = null;
        }
    }

    /**
     * Stoppt die aktuelle Musikwiedergabe und gibt Ressourcen frei.
     * Sicher aufzurufen auch wenn keine Musik läuft.
     */
    public static synchronized void stop() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {}
            
            try {
                player.dispose();
            } catch (Exception ignored) {}
        }
        player = null;
        currentResource = null;
    }

    /**
     * Setzt die Lautstärke (0.0 = stumm, 1.0 = maximum).
     * Der Wert wird gespeichert und sofort auf den aktuellen Track angewendet.
     *
     * @param v Lautstärke im Bereich [0,1]
     */
    public static synchronized void setVolume(double v) {
        // Ungültige Werte ignorieren
        if (Double.isNaN(v) || Double.isInfinite(v)) return;
        
        // Wert auf gültigen Bereich beschränken und speichern
        volume = Math.max(0.0, Math.min(1.0, v));
        
        // Auf aktuellen Player anwenden (mit Grenzwert-Skalierung)
        if (player != null) {
            try {
                player.setVolume(volume * MAX_EFFECTIVE_VOLUME);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Gibt die aktuelle Benutzer-Lautstärke zurück.
     * @return Lautstärke im Bereich [0,1]
     */
    public static synchronized double getVolume() {
        return volume;
    }
}
