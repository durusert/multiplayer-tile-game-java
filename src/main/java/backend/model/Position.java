package backend.model;

/**
 * Repräsentiert eine (x,y)-Koordinate im ClanTerritory.
 * <p>
 * (0,0) ist das Startfeld mit der Burg.
 * (x, y+1) liegt über (x,y)
 * (x, y-1) liegt unter (x,y)
 * (x+1, y) liegt rechts von (x,y)
 * (x-1, y) liegt links von (x,y)
 *
 * @param x x-Koordinate
 * @param y y-Koordinate
 */
public record Position(int x, int y) {

    /**
     * Vergleicht diese Position mit einem anderen Objekt.
     *
     * @param o Vergleichsobjekt
     * @return {@code true}, wenn beide Positionen gleich sind
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Position that)) return false;
        return x == that.x && y == that.y;
    }

    /**
     * Gibt die Position als String "(x,y)" zurück.
     *
     * @return String-Repräsentation
     */
    @Override
    public String toString() {
        return "(" + x + "," + y + ")";
    }
}

