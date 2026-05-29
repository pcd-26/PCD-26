package pcd.poool.model.game;

/**
 * Terminal condition that explains why a finished game ended.
 *
 * <p>The winner is score-based only when all small balls are cleared. If a cue
 * ball is pocketed, the opponent wins regardless of the current score.
 */
public enum GameOverReason {
    /** All small balls were pocketed; score decides the winner or draw. */
    SMALL_BALLS_CLEARED,
    /** The human cue ball was pocketed, so the bot wins. */
    HUMAN_CUE_BALL_POCKETED,
    /** The bot cue ball was pocketed, so the human wins. */
    BOT_CUE_BALL_POCKETED
}
