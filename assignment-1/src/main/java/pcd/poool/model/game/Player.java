package pcd.poool.model.game;

/**
 * Owner of one cue ball and score counter.
 */
public enum Player {
    HUMAN,
    BOT;

    /**
     * @return the other player
     */
    public Player opponent() {
        return this == HUMAN ? BOT : HUMAN;
    }
}
