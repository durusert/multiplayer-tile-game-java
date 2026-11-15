package frontend.ui.components;

import com.google.gson.JsonObject;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;

/**
 * API für UI-Logik zur Platzierung von Tiles im Spiel.
 */
public interface PlaceTileInGameApi {
    /**
     * Registriert eine Board-Zelle.
     *
     * @param x x-Koordinate
     * @param y y-Koordinate
     * @param cell Zell-Node
     */
    void registerCell(int x, int y, StackPane cell);
    /**
     * Löscht alle Board-Zellen und Zustände.
     */
    void clearBoard();
    /**
     * Mouse-Move-Handler.
     *
     * @param e Mouse-Event
     */
    void handleMouseMoved(MouseEvent e);
    /**
     * Mouse-Exit-Handler.
     */
    void handleMouseExited();
    /**
     * Handler für Klick auf eine Board-Zelle.
     *
     * @param x x-Koordinate
     * @param y y-Koordinate
     */
    void onBoardCellClicked(int x, int y);
    /**
     * Platziert ein Tile visuell im Board.
     *
     * @param tileId Tile-ID
     * @param x x-Koordinate
     * @param y y-Koordinate
     * @param rotation Rotation in Grad
     * @param tileJson Tile-JSON
     */
    void placeTileOnBoard(int tileId, int x, int y, int rotation, JsonObject tileJson);
    /**
     * Aktualisiert Platzierungshinweise.
     */
    void updatePlacementHints();
    /**
     * Aktiviert oder deaktiviert die Build-Interaktion.
     *
     * @param buildActive {@code true} wenn aktiv
     */
    void setBuildActive(boolean buildActive);
    /**
     * Setzt das ausgewählte Tile.
     *
     * @param tileId Tile-ID
     * @param tileJson Tile-JSON
     */
    void setSelectedTile(Integer tileId, JsonObject tileJson);
    /**
     * Gibt die aktuelle Rotation zurück.
     *
     * @return Rotation in Grad
     */
    int getSelectedRotation();
    /**
     * Setzt die Rotation zurück.
     */
    void resetRotation();
}
