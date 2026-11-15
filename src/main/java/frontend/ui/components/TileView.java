package frontend.ui.components;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.CubicCurveTo;
import javafx.scene.shape.Line;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Path;
import javafx.scene.shape.Rectangle;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * JavaFX-View für die Darstellung eines Tiles.
 */
public class TileView extends StackPane {

    private static final Logger log=
            LogManager.getLogger(TileView.class);

    private static final int EDGE_THICKNESS_PX = 10;
    private static final int INNER_INSET_PX = 8;
    private static final Color ROAD_STROKE = Color.web("#7a4b2a");

    private static final Object TILE_SHEET_LOCK = new Object();
    private static volatile Image tileSheetImage;
    private static volatile int tileSheetSpritePx = -1;
    private static volatile boolean tileSheetInitialized = false;

    private final int tileId;
    private final int sizePx;

    private enum Side {
        TOP, RIGHT, DOWN, LEFT
    }

    /**
     * Erstellt eine Tile-Ansicht aus dem JSON-Modell.
     *
     * @param tileJson Tile-JSON
     * @param sizePx Kantenlänge in Pixel
     */
    public TileView(JsonObject tileJson, int sizePx) {
        this.sizePx = sizePx;

        this.tileId = (tileJson != null && tileJson.has("id")) ? tileJson.get("id").getAsInt() : Integer.MIN_VALUE;
        String top = getString(tileJson, "top");
        String right = getString(tileJson, "right");
        String down = getString(tileJson, "down");
        String left = getString(tileJson, "left");

        JsonArray roads = (tileJson != null && tileJson.has("roads") && tileJson.get("roads").isJsonArray())
                ? tileJson.getAsJsonArray("roads")
                : new JsonArray();
        JsonArray elements = (tileJson != null && tileJson.has("elements") && tileJson.get("elements").isJsonArray())
                ? tileJson.getAsJsonArray("elements")
                : new JsonArray();
        JsonObject scroll = (tileJson != null && tileJson.has("scroll") && tileJson.get("scroll").isJsonObject())
                ? tileJson.getAsJsonObject("scroll")
                : null;

        setMinSize(sizePx, sizePx);
        setPrefSize(sizePx, sizePx);
        setMaxSize(sizePx, sizePx);

        Rectangle base = new Rectangle(sizePx, sizePx);
        base.setFill(Color.web("#f3efe3"));
        base.setStroke(Color.web("#1A171C"));
        base.setStrokeWidth(1.2);
        base.setArcWidth(10);
        base.setArcHeight(10);

        ImageView spriteView = buildSpriteView(tileJson, sizePx);

        Label idLabel = new Label(String.valueOf(tileId));
        idLabel.setMouseTransparent(true);
        idLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: rgba(26,23,28,0.70); -fx-font-weight: bold;");
        StackPane.setAlignment(idLabel, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(idLabel, new javafx.geometry.Insets(0, 6, 4, 0));
        idLabel.rotateProperty().bind(Bindings.multiply(rotateProperty(), -1));

        Tooltip.install(this, new Tooltip(buildTooltipText(top, right, down, left, elements, scroll)));

        if (spriteView != null) {
            getChildren().addAll(base, spriteView, idLabel);
        } else {
            Pane terrainPane = buildTerrainWedges(sizePx, top, right, down, left);
            Pane roadPane = buildRoadOverlay(sizePx, roads);
            Pane elementPane = buildElementOverlay(sizePx, elements, scroll);

            Rectangle topEdge = new Rectangle(sizePx - 12, EDGE_THICKNESS_PX);
            topEdge.setFill(colorForEdge(top));
            topEdge.setArcWidth(8);
            topEdge.setArcHeight(8);
            StackPane.setAlignment(topEdge, Pos.TOP_CENTER);
            StackPane.setMargin(topEdge, new javafx.geometry.Insets(6, 0, 0, 0));

            Rectangle rightEdge = new Rectangle(EDGE_THICKNESS_PX, sizePx - 12);
            rightEdge.setFill(colorForEdge(right));
            rightEdge.setArcWidth(8);
            rightEdge.setArcHeight(8);
            StackPane.setAlignment(rightEdge, Pos.CENTER_RIGHT);
            StackPane.setMargin(rightEdge, new javafx.geometry.Insets(0, 6, 0, 0));

            Rectangle bottomEdge = new Rectangle(sizePx - 12, EDGE_THICKNESS_PX);
            bottomEdge.setFill(colorForEdge(down));
            bottomEdge.setArcWidth(8);
            bottomEdge.setArcHeight(8);
            StackPane.setAlignment(bottomEdge, Pos.BOTTOM_CENTER);
            StackPane.setMargin(bottomEdge, new javafx.geometry.Insets(0, 0, 6, 0));

            Rectangle leftEdge = new Rectangle(EDGE_THICKNESS_PX, sizePx - 12);
            leftEdge.setFill(colorForEdge(left));
            leftEdge.setArcWidth(8);
            leftEdge.setArcHeight(8);
            StackPane.setAlignment(leftEdge, Pos.CENTER_LEFT);
            StackPane.setMargin(leftEdge, new javafx.geometry.Insets(0, 0, 0, 6));

            getChildren().addAll(base, terrainPane, topEdge, rightEdge, bottomEdge, leftEdge, roadPane, elementPane, idLabel);
        }
    }

    /**
     * Gibt die Tile-ID zurück.
     *
     * @return Tile-ID
     */
    public int getTileId() {
        return tileId;
    }

    /**
     * Gibt die Größe in Pixel zurück.
     *
     * @return Größe in Pixel
     */
    public int getSizePx() {
        return sizePx;
    }

    /**
     * Markiert das Tile als ausgewählt oder entfernt die Markierung.
     *
     * @param selected {@code true} wenn ausgewählt
     */
    public void setSelected(boolean selected) {
        if (selected) {
            setStyle("-fx-border-color: #D5A649; -fx-border-width: 3; -fx-border-radius: 10;");
        } else {
            setStyle("");
        }
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    private static Pane buildTerrainWedges(int sizePx, String top, String right, String down, String left) {
        double size = sizePx;
        double inset = INNER_INSET_PX;
        double c = size / 2.0;

        Polygon pTop = new Polygon(inset, inset, size - inset, inset, c, c);
        Polygon pRight = new Polygon(size - inset, inset, size - inset, size - inset, c, c);
        Polygon pDown = new Polygon(inset, size - inset, size - inset, size - inset, c, c);
        Polygon pLeft = new Polygon(inset, inset, inset, size - inset, c, c);

        pTop.setFill(colorForEdge(top).deriveColor(0, 1, 1, 0.35));
        pRight.setFill(colorForEdge(right).deriveColor(0, 1, 1, 0.35));
        pDown.setFill(colorForEdge(down).deriveColor(0, 1, 1, 0.35));
        pLeft.setFill(colorForEdge(left).deriveColor(0, 1, 1, 0.35));

        Pane topPattern = buildTerrainPattern(sizePx, Side.TOP, pTop, top);
        Pane rightPattern = buildTerrainPattern(sizePx, Side.RIGHT, pRight, right);
        Pane downPattern = buildTerrainPattern(sizePx, Side.DOWN, pDown, down);
        Pane leftPattern = buildTerrainPattern(sizePx, Side.LEFT, pLeft, left);

        Pane pane = new Pane(pTop, topPattern, pRight, rightPattern, pDown, downPattern, pLeft, leftPattern);
        pane.setMouseTransparent(true);
        pane.setMinSize(sizePx, sizePx);
        pane.setPrefSize(sizePx, sizePx);
        pane.setMaxSize(sizePx, sizePx);
        return pane;
    }

    private static Pane buildTerrainPattern(int sizePx, Side side, Polygon clipSource, String terrain) {
        String key = terrain != null ? terrain.trim().toLowerCase() : "";
        if (key.isEmpty() || key.equals("none")) {
            return emptyOverlay(sizePx);
        }

        Pane pane = new Pane();
        pane.setMouseTransparent(true);
        pane.setMinSize(sizePx, sizePx);
        pane.setPrefSize(sizePx, sizePx);
        pane.setMaxSize(sizePx, sizePx);

        Polygon clip = new Polygon();
        clip.getPoints().addAll(clipSource.getPoints());
        pane.setClip(clip);

        double size = sizePx;
        double c = size / 2.0;
        double offset = Math.max(8, size * 0.20);

        double cx = c;
        double cy = c;
        switch (side) {
            case TOP -> cy = INNER_INSET_PX + offset;
            case DOWN -> cy = size - INNER_INSET_PX - offset;
            case LEFT -> cx = INNER_INSET_PX + offset;
            case RIGHT -> cx = size - INNER_INSET_PX - offset;
        }

        Color base = colorForEdge(key);
        Color stroke = base.darker().deriveColor(0, 1, 1, 0.45);

        switch (key) {
            case "water" -> addWaterPattern(pane, side, cx, cy, size, stroke);
            case "mountain" -> addMountainPattern(pane, side, cx, cy, size, stroke);
            case "field" -> addFieldPattern(pane, side, cx, cy, size, stroke);
            default -> {
            }
        }

        return pane;
    }

    private static Pane emptyOverlay(int sizePx) {
        Pane pane = new Pane();
        pane.setMouseTransparent(true);
        pane.setMinSize(sizePx, sizePx);
        pane.setPrefSize(sizePx, sizePx);
        pane.setMaxSize(sizePx, sizePx);
        return pane;
    }

    private static void addWaterPattern(Pane pane, Side side, double cx, double cy, double size, Color stroke) {
        double len = Math.max(10, size * 0.30);
        double amp = Math.max(2, size * 0.06);
        double gap = Math.max(3, size * 0.08);

        Path w1;
        Path w2;
        if (side == Side.LEFT || side == Side.RIGHT) {
            w1 = verticalWave(cx - gap / 2.0, cy, len, amp);
            w2 = verticalWave(cx + gap / 2.0, cy, len, amp);
        } else {
            w1 = horizontalWave(cx, cy - gap / 2.0, len, amp);
            w2 = horizontalWave(cx, cy + gap / 2.0, len, amp);
        }

        for (Path w : new Path[] { w1, w2 }) {
            w.setStroke(stroke);
            w.setStrokeWidth(Math.max(1.0, size * 0.03));
            w.setFill(Color.TRANSPARENT);
            w.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            pane.getChildren().add(w);
        }
    }

    private static Path horizontalWave(double cx, double cy, double length, double amplitude) {
        double x1 = cx - length / 2.0;
        double x2 = cx + length / 2.0;
        Path p = new Path();
        p.getElements().add(new MoveTo(x1, cy));
        p.getElements().add(new CubicCurveTo(cx - length / 4.0, cy - amplitude, cx + length / 4.0, cy + amplitude, x2, cy));
        return p;
    }

    private static Path verticalWave(double cx, double cy, double length, double amplitude) {
        double y1 = cy - length / 2.0;
        double y2 = cy + length / 2.0;
        Path p = new Path();
        p.getElements().add(new MoveTo(cx, y1));
        p.getElements().add(new CubicCurveTo(cx - amplitude, cy - length / 4.0, cx + amplitude, cy + length / 4.0, cx, y2));
        return p;
    }

    private static void addMountainPattern(Pane pane, Side side, double cx, double cy, double size, Color stroke) {
        double w = Math.max(6, size * 0.15);
        double h = Math.max(5, size * 0.12);
        double gap = Math.max(4, size * 0.10);

        Polygon t1 = mountainTriangle(side, cx - gap / 2.0, cy, w, h);
        Polygon t2 = mountainTriangle(side, cx + gap / 2.0, cy + h * 0.35, w * 0.85, h * 0.85);

        for (Polygon t : new Polygon[] { t1, t2 }) {
            t.setFill(stroke.deriveColor(0, 1, 1, 0.20));
            t.setStroke(stroke);
            t.setStrokeWidth(Math.max(0.8, size * 0.02));
            pane.getChildren().add(t);
        }
    }

    private static Polygon mountainTriangle(Side side, double cx, double cy, double w, double h) {
        return switch (side) {
            case TOP -> new Polygon(cx - w, cy + h, cx, cy - h, cx + w, cy + h);
            case DOWN -> new Polygon(cx - w, cy - h, cx, cy + h, cx + w, cy - h);
            case LEFT -> new Polygon(cx + h, cy - w, cx - h, cy, cx + h, cy + w);
            case RIGHT -> new Polygon(cx - h, cy - w, cx + h, cy, cx - h, cy + w);
        };
    }

    private static void addFieldPattern(Pane pane, Side side, double cx, double cy, double size, Color stroke) {
        double len = Math.max(5, size * 0.12);
        double gap = Math.max(3, size * 0.07);
        double sw = Math.max(0.9, size * 0.02);

        boolean vertical = (side == Side.TOP || side == Side.DOWN);
        Line l1 = vertical
                ? new Line(cx - gap, cy + len * 0.6, cx - gap * 0.5, cy - len * 0.6)
                : new Line(cx + len * 0.6, cy - gap, cx - len * 0.6, cy - gap * 0.5);
        Line l2 = vertical
                ? new Line(cx, cy + len * 0.6, cx, cy - len * 0.6)
                : new Line(cx + len * 0.6, cy, cx - len * 0.6, cy);
        Line l3 = vertical
                ? new Line(cx + gap, cy + len * 0.6, cx + gap * 0.5, cy - len * 0.6)
                : new Line(cx + len * 0.6, cy + gap, cx - len * 0.6, cy + gap * 0.5);

        for (Line l : new Line[] { l1, l2, l3 }) {
            l.setStroke(stroke);
            l.setStrokeWidth(sw);
            l.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            pane.getChildren().add(l);
        }
    }

    private static Pane buildRoadOverlay(int sizePx, JsonArray roads) {
        Pane pane = new Pane();
        pane.setMouseTransparent(true);
        pane.setMinSize(sizePx, sizePx);
        pane.setPrefSize(sizePx, sizePx);
        pane.setMaxSize(sizePx, sizePx);

        if (roads == null || roads.isEmpty()) return pane;

        double size = sizePx;
        double c = size / 2.0;
        double a = INNER_INSET_PX + 1;

        for (JsonElement roadEl : roads) {
            if (!roadEl.isJsonArray()) continue;
            JsonArray road = roadEl.getAsJsonArray();
            if (road.isEmpty()) continue;
            for (JsonElement sideEl : road) {
                if (!sideEl.isJsonPrimitive()) continue;
                String side = sideEl.getAsString();
                double x2 = c;
                double y2 = c;
                switch (side.toLowerCase()) {
                    case "top" -> {
                        x2 = c;
                        y2 = a;
                    }
                    case "down", "bottom" -> {
                        x2 = c;
                        y2 = size - a;
                    }
                    case "left" -> {
                        x2 = a;
                        y2 = c;
                    }
                    case "right" -> {
                        x2 = size - a;
                        y2 = c;
                    }
                    default -> {
                        continue;
                    }
                }

                Line line = new Line(c, c, x2, y2);
                line.setStroke(ROAD_STROKE);
                line.setStrokeWidth(Math.max(2.6, size * 0.09));
                line.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
                pane.getChildren().add(line);
            }
        }

        Circle hub = new Circle(c, c, Math.max(2, size * 0.06));
        hub.setFill(Color.web("#f7efe3", 0.9));
        hub.setStroke(ROAD_STROKE.darker());
        hub.setStrokeWidth(1.0);
        pane.getChildren().add(hub);

        return pane;
    }

    private static Pane buildElementOverlay(int sizePx, JsonArray elements, JsonObject scroll) {
        Pane pane = new Pane();
        pane.setMouseTransparent(true);
        pane.setMinSize(sizePx, sizePx);
        pane.setPrefSize(sizePx, sizePx);
        pane.setMaxSize(sizePx, sizePx);

        Map<String, Integer> counts = new LinkedHashMap<>();
        if (elements != null) {
            for (JsonElement el : elements) {
                if (!el.isJsonPrimitive()) continue;
                String raw = el.getAsString();
                if (raw == null) continue;
                String key = raw.trim().toLowerCase();
                if (key.isEmpty()) continue;
                if (key.equals("water") || key.equals("mountain") || key.equals("field")) continue;
                counts.put(key, counts.getOrDefault(key, 0) + 1);
            }
        }

        java.util.List<String> keys = new java.util.ArrayList<>(counts.keySet());
        if (keys.size() > 4) {
            keys = keys.subList(0, 4);
        }

        double size = sizePx;
        double badge = Math.max(10, size * 0.28);
        double pad = INNER_INSET_PX + 2;

        double[][] positions = switch (keys.size()) {
            case 0 -> new double[][]{};
            case 1 -> new double[][]{{size / 2.0, size / 2.0}};
            case 2 -> new double[][]{{size * 0.34, size / 2.0}, {size * 0.66, size / 2.0}};
            case 3 -> new double[][]{{size / 2.0, size * 0.34}, {size * 0.34, size * 0.66}, {size * 0.66, size * 0.66}};
            default -> new double[][]{{pad, pad}, {size - pad, pad}, {pad, size - pad}, {size - pad, size - pad}};
        };

        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            Integer count = counts.getOrDefault(key, 1);
            StackPane badgeNode = elementBadge(key, count, badge);
            double x = positions[i][0] - badge / 2.0;
            double y = positions[i][1] - badge / 2.0;
            badgeNode.relocate(x, y);
            pane.getChildren().add(badgeNode);
        }

        if (scroll != null && scroll.has("points") && scroll.get("points").isJsonPrimitive()) {
            int points = scroll.get("points").getAsInt();
            StackPane scrollBadge = scrollBadge(points, Math.max(10, size * 0.26));
            scrollBadge.relocate(size / 2.0 - scrollBadge.getPrefWidth() / 2.0, size - scrollBadge.getPrefHeight() - 3);
            pane.getChildren().add(scrollBadge);
        }

        return pane;
    }

    private static StackPane elementBadge(String elementKey, int count, double sizePx) {
        String key = elementKey != null ? elementKey.toLowerCase() : "";
        Color fill = colorForElement(key);
        String labelText = abbrevForElement(key);

        Circle bg = new Circle(sizePx / 2.0);
        bg.setFill(fill);
        bg.setStroke(Color.web("#1A171C", 0.75));
        bg.setStrokeWidth(1.0);

        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 9px; -fx-font-weight: bold; -fx-text-fill: #1A171C;");
        label.setMouseTransparent(true);

        StackPane root = new StackPane(bg, label);
        root.setPrefSize(sizePx, sizePx);

        if (count > 1) {
            Label countLabel = new Label(String.valueOf(count));
            countLabel.setStyle("-fx-font-size: 8px; -fx-font-weight: bold; -fx-text-fill: #1A171C;");
            StackPane.setAlignment(countLabel, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(countLabel, new javafx.geometry.Insets(0, 2, 1, 0));
            countLabel.setMouseTransparent(true);
            root.getChildren().add(countLabel);
        }

        return root;
    }

    private static StackPane scrollBadge(int points, double heightPx) {
        double w = Math.max(18, heightPx * 1.35);
        Rectangle bg = new Rectangle(w, heightPx);
        bg.setArcWidth(8);
        bg.setArcHeight(8);
        bg.setFill(Color.web("#f7e7c6", 0.95));
        bg.setStroke(Color.web("#8b5e34", 0.9));
        bg.setStrokeWidth(1.0);

        Label label = new Label("+" + points);
        label.setStyle("-fx-font-size: 9px; -fx-font-weight: bold; -fx-text-fill: #1A171C;");
        label.setMouseTransparent(true);

        StackPane root = new StackPane(bg, label);
        root.setPrefSize(w, heightPx);
        root.setMouseTransparent(true);
        return root;
    }

    private static String buildTooltipText(String top, String right, String down, String left, JsonArray elements, JsonObject scroll) {
        StringBuilder sb = new StringBuilder();
        sb.append("Edges: ")
                .append(Objects.toString(top, "?")).append(" / ")
                .append(Objects.toString(right, "?")).append(" / ")
                .append(Objects.toString(down, "?")).append(" / ")
                .append(Objects.toString(left, "?"));

        if (elements != null && !elements.isEmpty()) {
            sb.append("\nElements: ");
            boolean first = true;
            for (JsonElement el : elements) {
                if (!el.isJsonPrimitive()) continue;
                String v = el.getAsString();
                if (v == null || v.isBlank()) continue;
                if (!first) sb.append(", ");
                sb.append(v);
                first = false;
            }
        }

        if (scroll != null && scroll.has("points") && scroll.get("points").isJsonPrimitive()) {
            sb.append("\nScroll: +").append(scroll.get("points").getAsInt());
        }

        return sb.toString();
    }

    private static String abbrevForElement(String key) {
        return switch (key) {
            case "castle" -> "C";
            case "lighthouse" -> "L";
            case "tower" -> "T";
            case "farm" -> "F";
            case "sheep" -> "S";
            case "whiskey", "barrel" -> "W";
            case "ship" -> "Sh";
            case "ox" -> "O";
            default -> key.isEmpty() ? "?" : key.substring(0, Math.min(2, key.length())).toUpperCase();
        };
    }

    private static Color colorForElement(String key) {
        if (key == null) return Color.web("#bfbfbf");
        return switch (key.toLowerCase()) {
            case "castle" -> Color.web("#c9c9c9");
            case "lighthouse" -> Color.web("#f5f5f5");
            case "tower" -> Color.web("#b0b0b0");
            case "farm" -> Color.web("#f3c1a2");
            case "sheep" -> Color.web("#ffffff");
            case "whiskey", "barrel" -> Color.web("#d0a36a");
            case "ship" -> Color.web("#b5d4ff");
            case "ox" -> Color.web("#d9b08c");
            default -> Color.web("#e1e1e1");
        };
    }

    private static Color colorForEdge(String edge) {
        if (edge == null) return Color.web("#999999");
        return switch (edge.toLowerCase()) {
            case "water" -> Color.web("#2b7de9");
            case "mountain" -> Color.web("#7b7b7b");
            case "field" -> Color.web("#2ecc71");
            case "none" -> Color.web("#bfbfbf");
            default -> Color.web("#bfbfbf");
        };
    }

    private static ImageView buildSpriteView(JsonObject tileJson, int sizePx) {
        if (tileJson == null || !tileJson.has("spriteX") || !tileJson.has("spriteY")) return null;
        if (!tileJson.get("spriteX").isJsonPrimitive() || !tileJson.get("spriteY").isJsonPrimitive()) return null;

        Image sheet = getTileSheet();
        if (sheet == null) return null;

        int spriteX = tileJson.get("spriteX").getAsInt();
        int spriteY = tileJson.get("spriteY").getAsInt();
        int spritePx = tileSheetSpritePx > 0 ? tileSheetSpritePx : inferSpritePx(sheet);

        try {
            log.error(
                    "TileView.buildSpriteView: id={} spriteX={} spriteY={} spritePx={} sheetW={} sheetH={}",
                    tileJson.has("id") ? tileJson.get("id").getAsInt() : -1,
                    spriteX,
                    spriteY,
                    spritePx,
                    sheet.getWidth(),
                    sheet.getHeight()
            );
        } catch (Exception e) {
        }

        double sx = (double) spriteX * spritePx;
        double sy = (double) spriteY * spritePx;
        if (sx + spritePx > sheet.getWidth() || sy + spritePx > sheet.getHeight()) {
            log.error(
                    "TileView.buildSpriteView: viewport out of bounds for tile id={}",
                    tileJson.has("id") ? tileJson.get("id").getAsInt() : -1
            );
            return null;
        }

        ImageView view = new ImageView(sheet);
        view.setViewport(new Rectangle2D(sx, sy, spritePx, spritePx));
        view.setFitWidth(sizePx);
        view.setFitHeight(sizePx);
        view.setPreserveRatio(false);
        view.setSmooth(true);
        view.setMouseTransparent(true);

        Rectangle clip = new Rectangle(sizePx, sizePx);
        clip.setArcWidth(10);
        clip.setArcHeight(10);
        view.setClip(clip);

        return view;
    }

    private static Image getTileSheet() {
        if (tileSheetInitialized) return tileSheetImage;
        synchronized (TILE_SHEET_LOCK) {
            if (tileSheetInitialized) return tileSheetImage;
            tileSheetInitialized = true;

            try {
                java.nio.file.Path path = resolveTileSheetPath();
                Image img = null;
                if (path != null) {
                    log.debug("TileView: attempting to load tile sheet from path: {}", path);
                    img = new Image(path.toUri().toString(), false);
                    log.debug("Tileview: loaded image from path. width= {} height= {}", img.getWidth(), img.getHeight());
                } else {
                    // Fallback: try loading tiles.png from classpath resources (/tiles/tiles.png)
                    try (InputStream in = TileView.class.getResourceAsStream("/tiles/tiles.png")) {
                        if (in != null) {
                            log.debug("attempting to load tile sheet from classpath /tiles/tiles.png");
                            img = new Image(in);
                            log.debug("loaded image from classpath, width= {} height= {}", img.getWidth(), img.getHeight());
                        }
                    }
                }

                if (img == null || img.isError() || img.getWidth() <= 0 || img.getHeight() <= 0) {
                    tileSheetImage = null;
                    tileSheetSpritePx = -1;
                    log.error("failed to load tile sheet image (img==null or error)");
                    return null;
                }

                tileSheetImage = img;
                tileSheetSpritePx = inferSpritePx(img);
                log.warn("inferred spritePx= {}", tileSheetSpritePx);
            } catch (Exception ignored) {
                log.warn("exeption while loading tile sheet: {}", ignored);
                tileSheetImage = null;
                tileSheetSpritePx = -1;
            }
            return tileSheetImage;
        }
    }

    private static java.nio.file.Path resolveTileSheetPath() {
        String direct = System.getProperty("isle.tileSheet");
        if (direct == null || direct.isBlank()) {
            direct = System.getenv("ISLE_TILE_SHEET");
        }
        if (direct != null && !direct.isBlank()) {
            java.nio.file.Path p = safePath(direct);
            if (p != null && Files.isRegularFile(p)) return p;
        }

        String assetsDir = System.getProperty("isle.assetsDir");
        if (assetsDir == null || assetsDir.isBlank()) {
            assetsDir = System.getenv("ISLE_ASSETS_DIR");
        }
        if (assetsDir == null || assetsDir.isBlank()) {
            assetsDir = System.getenv("ISLE_OF_SKYE_ASSETS_DIR");
        }
        if (assetsDir != null && !assetsDir.isBlank()) {
            java.nio.file.Path base = safePath(assetsDir);
            if (base != null) {
                java.nio.file.Path p = base.resolve("tiles").resolve("tiles.png");
                if (Files.isRegularFile(p)) return p;
            }
        }

        java.nio.file.Path local = safePath("assets", "tiles", "tiles.png");
        if (local != null && Files.isRegularFile(local)) return local;

        return null;
    }

    private static java.nio.file.Path safePath(String first, String... more) {
        try {
            String cleanedFirst = stripOptionalQuotes(first);
            String[] cleanedMore = new String[more != null ? more.length : 0];
            for (int i = 0; i < cleanedMore.length; i++) {
                cleanedMore[i] = stripOptionalQuotes(more[i]);
            }
            return java.nio.file.Path.of(cleanedFirst, cleanedMore);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String stripOptionalQuotes(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    private static int inferSpritePx(Image sheet) {
        Integer override = parsePositiveInt(System.getProperty("isle.spritePx"));
        if (override == null) {
            override = parsePositiveInt(System.getenv("ISLE_SPRITE_PX"));
        }
        if (override != null) return override;

        int w = (int) Math.round(sheet.getWidth());
        int h = (int) Math.round(sheet.getHeight());
        if (w <= 0 || h <= 0) return 180;

        Integer fromDefs = inferSpritePxFromTileDefinitions(sheet);
        if (fromDefs != null) return fromDefs;

        for (int grid : new int[] { 9, 8, 10, 12, 13, 14, 15, 16, 18, 20, 24, 26 }) {
            if (w % grid == 0 && h % grid == 0) return w / grid;
        }

        int approx = (int) Math.round(w / 26.0);
        return approx > 0 ? approx : 180;
    }

    private static Integer inferSpritePxFromTileDefinitions(Image sheet) {
        java.nio.file.Path jsonPath = resolveTilesJsonPath();
        if (jsonPath != null) {
            try (var reader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
                var root = JsonParser.parseReader(reader);
                if (root != null && root.isJsonArray()) {
                    Integer px = spritePxFromJsonArray(sheet, root.getAsJsonArray());
                    if (px != null) return px;
                }
            } catch (Exception ignored) {
            }
        }

        for (String name : new String[] { "tiles-v3.json", "tiles-v2.json", "tiles.json" }) {
            try (InputStream in = TileView.class.getResourceAsStream("/tiles/" + name)) {
                if (in == null) continue;
                var root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                if (root == null || !root.isJsonArray()) continue;
                Integer px = spritePxFromJsonArray(sheet, root.getAsJsonArray());
                if (px != null) return px;
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }

    private static Integer spritePxFromJsonArray(Image sheet, JsonArray arr) {
        if (arr == null || arr.isEmpty()) return null;
        int maxX = -1;
        int maxY = -1;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("spriteX") && obj.get("spriteX").isJsonPrimitive()) {
                maxX = Math.max(maxX, obj.get("spriteX").getAsInt());
            }
            if (obj.has("spriteY") && obj.get("spriteY").isJsonPrimitive()) {
                maxY = Math.max(maxY, obj.get("spriteY").getAsInt());
            }
        }
        if (maxX < 0 || maxY < 0) return null;

        int cols = maxX + 1;
        int rows = maxY + 1;
        int w = (int) Math.round(sheet.getWidth());
        int h = (int) Math.round(sheet.getHeight());
        if (cols <= 0 || rows <= 0 || w <= 0 || h <= 0) return null;
        if (w % cols != 0 || h % rows != 0) return null;
        int tileW = w / cols;
        int tileH = h / rows;
        return tileW == tileH ? tileW : null;
    }

    private static java.nio.file.Path resolveTilesJsonPath() {
        String direct = System.getProperty("isle.tilesJson");
        if (direct == null || direct.isBlank()) {
            direct = System.getenv("ISLE_TILES_JSON");
        }
        if (direct != null && !direct.isBlank()) {
            java.nio.file.Path p = safePath(direct);
            if (p != null && Files.isRegularFile(p)) return p;
        }

        String assetsDir = System.getProperty("isle.assetsDir");
        if (assetsDir == null || assetsDir.isBlank()) {
            assetsDir = System.getenv("ISLE_ASSETS_DIR");
        }
        if (assetsDir == null || assetsDir.isBlank()) {
            assetsDir = System.getenv("ISLE_OF_SKYE_ASSETS_DIR");
        }
        if (assetsDir != null && !assetsDir.isBlank()) {
            java.nio.file.Path base = safePath(assetsDir);
            if (base != null) {
                for (String name : new String[] { "tiles-v3.json", "tiles-v2.json", "tiles.json" }) {
                    java.nio.file.Path p = base.resolve("tiles").resolve(name);
                    if (Files.isRegularFile(p)) return p;
                }
            }
        }

        return null;
    }

    private static Integer parsePositiveInt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
