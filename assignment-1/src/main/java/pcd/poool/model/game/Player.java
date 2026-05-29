package pcd.poool.model.game;

public enum Player {
    HUMAN,
    BOT;

    public Player opponent() {
        return this == HUMAN ? BOT : HUMAN;
    }
}
