package pcd.poool.model.game;

/**
 * Owner of one cue ball and score counter.
 */
public enum Player {
    /** Human-controlled player. */
    HUMAN,
    /** Computer-controlled player. */
    BOT;

    /**
     * Gets the opponent of this player.
     *
     * @return the other player
     */
    public Player opponent() {
        return this == HUMAN ? BOT : HUMAN;
    }
}
