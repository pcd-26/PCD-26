package pcd.dttt.common;

// Represents the lifecycle of a distributed match.
public enum GameStatus {
    WAITING,
    ACTIVE,
    WON_X,
    WON_O,
    DRAW,
    ABANDONED;

    // Returns true while the room is still waiting for a join.
    public boolean isWaiting() {
        return this == WAITING;
    }

    // Returns true while moves are still allowed.
    public boolean isActive() {
        return this == ACTIVE;
    }

    // Returns true after a win, draw, or abandonment.
    public boolean isTerminal() {
        return switch (this) {
            case WON_X, WON_O, DRAW, ABANDONED -> true;
            default -> false;
        };
    }
}
