package frontend.ui.audio;

import javafx.scene.media.AudioClip;

import java.net.URL;

/**
 * Zentrale Verwaltung für kurze UI-Soundeffekte.
 * 
 * Verwendet AudioClip statt MediaPlayer für minimale Latenz und 
 * die Möglichkeit, mehrere Sounds gleichzeitig abzuspielen.
 * 
 * Alle Clips werden beim ersten Aufruf lazy geladen und gecacht.
 * Die warmUp()-Methode kann beim Screen-Start aufgerufen werden,
 * um First-Play-Verzögerungen zu vermeiden.
 */
public final class SoundEffects {

    /**
     * Globaler Faktor, um alle Soundeffekte im Spiel etwas leiser zu machen.
     * 
     * Hintergrund: Einige Clips wirken subjektiv deutlich lauter als die Musik.
     * Der UI-Slider steuert weiterhin die gewünschte Lautstärke im Bereich [0,1],
     * dieser Faktor wird zusätzlich multipliziert.
     */
    private static final double SFX_GLOBAL_GAIN = 0.70;

    // ═══════════════════════════════════════════════════════════════════════════
    // GECACHTE AUDIO-CLIPS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Sound für Preis-Anpassung (+/-) in der DISCARD-Phase */
    private static AudioClip priceTick;
    
    /** Sound beim Karten-Ziehen in der DRAW-Phase */
    private static AudioClip drawCard;
    
    /** Generischer Klick-Sound für die meisten Buttons */
    private static AudioClip uiClick;
    
    /** Erfolgs-Sound beim Kauf eines Tiles in der BUY-Phase */
    private static AudioClip purchase;
    
    /** Globale SFX-Lautstärke [0,1] */
    private static double sfxVolume = 0.60;

    /**
     * Effektive Abspiel-Lautstärke nach Anwendung des globalen Gains.
     */
    private static double effectiveVolume() {
        return Math.max(0.0, Math.min(1.0, sfxVolume * SFX_GLOBAL_GAIN));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // KONSTRUKTOR
    // ═══════════════════════════════════════════════════════════════════════════

    /** Privater Konstruktor verhindert Instanziierung (Utility-Klasse) */
    private SoundEffects() {}

    // ═══════════════════════════════════════════════════════════════════════════
    // WARM-UP (VORINITIALISIERUNG)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Lädt und initialisiert alle SFX-Clips im Voraus.
     * 
     * Sollte einmal beim Screen-Start aufgerufen werden, um die Audio-Pipeline
     * zu initialisieren. Ohne Warm-up gibt es beim ersten echten Abspielen
     * oft eine spürbare Verzögerung (100-300ms).
     * 
     * Die Clips werden mit Lautstärke 0 kurz angetriggert, was den nativen
     * Audio-Stack aktiviert ohne hörbaren Output.
     */
    public static void warmUp() {
        // Alle Clips laden
        ensurePriceTick();
        ensureDrawCard();
        ensureUiClick();
        ensurePurchase();

        // Stille Wiedergabe zum Aufwärmen der Audio-Pipeline
        try { if (priceTick != null) priceTick.play(0.0); } catch (Exception ignored) {}
        try { if (drawCard != null) drawCard.play(0.0); } catch (Exception ignored) {}
        try { if (uiClick != null) uiClick.play(0.0); } catch (Exception ignored) {}
        try { if (purchase != null) purchase.play(0.0); } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ÖFFENTLICHE ABSPIEL-METHODEN
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Spielt den generischen UI-Klick-Sound.
     * Verwendet für die meisten Buttons außer speziellen (Draw, Preis-Stepper).
     */
    public static void playUiClick() {
        AudioClip clip = ensureUiClick();
        if (clip == null) return;
        try {
            clip.play(effectiveVolume());
        } catch (Exception ignored) {}
    }

    /**
     * Spielt den Münz-/Drop-Sound für Preis-Anpassungen (+/-).
     * Wird bei jedem Klick auf die Preis-Stepper-Buttons verwendet.
     */
    public static void playPriceTick() {
        AudioClip clip = ensurePriceTick();
        if (clip == null) return;
        try {
            clip.play(effectiveVolume());
        } catch (Exception ignored) {}
    }

    /**
     * Spielt den Karten-Sound beim Ziehen in der DRAW-Phase.
     */
    public static void playDrawCard() {
        AudioClip clip = ensureDrawCard();
        if (clip == null) return;
        try {
            clip.play(effectiveVolume());
        } catch (Exception ignored) {}
    }

    /**
     * Spielt den Kauf-Erfolgs-Sound beim Erwerb eines Tiles in der BUY-Phase.
     */
    public static void playPurchase() {
        AudioClip clip = ensurePurchase();
        if (clip == null) return;
        try {
            clip.play(effectiveVolume());
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LAUTSTÄRKE-STEUERUNG
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Setzt die globale SFX-Lautstärke.
     * @param v Lautstärke im Bereich [0,1]
     */
    public static synchronized void setSfxVolume(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return;
        sfxVolume = Math.max(0.0, Math.min(1.0, v));
        
        // Lautstärke auf alle bereits geladenen Clips anwenden
        double vol = effectiveVolume();
        if (priceTick != null) priceTick.setVolume(vol);
        if (drawCard != null) drawCard.setVolume(vol);
        if (uiClick != null) uiClick.setVolume(vol);
        if (purchase != null) purchase.setVolume(vol);
    }

    /**
     * Gibt die aktuelle SFX-Lautstärke zurück.
     * @return Lautstärke im Bereich [0,1]
     */
    public static synchronized double getSfxVolume() {
        return sfxVolume;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE LAZY-LOADING METHODEN
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Lädt den Preis-Tick-Sound falls noch nicht geladen.
     * @return AudioClip oder null bei Fehler
     */
    private static synchronized AudioClip ensurePriceTick() {
        if (priceTick != null) return priceTick;

        String resourcePath = "/soundeffects/freesound_crunchpixstudio-drop-coin-384921.mp3";
        URL url = SoundEffects.class.getResource(resourcePath);
        if (url == null) {
            System.err.println("[SoundEffects] Ressource nicht gefunden: " + resourcePath);
            return null;
        }

        try {
            priceTick = new AudioClip(url.toExternalForm());
            priceTick.setVolume(effectiveVolume());
        } catch (Exception ex) {
            System.err.println("[SoundEffects] Laden fehlgeschlagen: " + resourcePath + " -> " + ex.getMessage());
            priceTick = null;
        }

        return priceTick;
    }

    /**
     * Lädt den Karten-Zieh-Sound falls noch nicht geladen.
     * @return AudioClip oder null bei Fehler
     */
    private static synchronized AudioClip ensureDrawCard() {
        if (drawCard != null) return drawCard;

        String resourcePath = "/soundeffects/freesound_community-card-sounds-35956.mp3";
        URL url = SoundEffects.class.getResource(resourcePath);
        if (url == null) {
            System.err.println("[SoundEffects] Ressource nicht gefunden: " + resourcePath);
            return null;
        }

        try {
            drawCard = new AudioClip(url.toExternalForm());
            drawCard.setVolume(effectiveVolume());
        } catch (Exception ex) {
            System.err.println("[SoundEffects] Laden fehlgeschlagen: " + resourcePath + " -> " + ex.getMessage());
            drawCard = null;
        }

        return drawCard;
    }

    /**
     * Lädt den generischen UI-Klick-Sound falls noch nicht geladen.
     * @return AudioClip oder null bei Fehler
     */
    private static synchronized AudioClip ensureUiClick() {
        if (uiClick != null) return uiClick;

        String resourcePath = "/soundeffects/creatorshome-keyboard-click-327728.mp3";
        URL url = SoundEffects.class.getResource(resourcePath);
        if (url == null) {
            System.err.println("[SoundEffects] Ressource nicht gefunden: " + resourcePath);
            return null;
        }

        try {
            uiClick = new AudioClip(url.toExternalForm());
            uiClick.setVolume(effectiveVolume());
        } catch (Exception ex) {
            System.err.println("[SoundEffects] Laden fehlgeschlagen: " + resourcePath + " -> " + ex.getMessage());
            uiClick = null;
        }

        return uiClick;
    }

    /**
     * Lädt den Kauf-Erfolgs-Sound falls noch nicht geladen.
     * @return AudioClip oder null bei Fehler
     */
    private static synchronized AudioClip ensurePurchase() {
        if (purchase != null) return purchase;

        String resourcePath = "/soundeffects/freesound_crunchpixstudio-purchase-success-384963.mp3";
        URL url = SoundEffects.class.getResource(resourcePath);
        if (url == null) {
            System.err.println("[SoundEffects] Ressource nicht gefunden: " + resourcePath);
            return null;
        }

        try {
            purchase = new AudioClip(url.toExternalForm());
            purchase.setVolume(effectiveVolume());
        } catch (Exception ex) {
            System.err.println("[SoundEffects] Laden fehlgeschlagen: " + resourcePath + " -> " + ex.getMessage());
            purchase = null;
        }

        return purchase;
    }
}
