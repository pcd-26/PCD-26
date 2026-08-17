package pcd.dttt.common;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class GameStatusTest {

    @Test
    public void waitingStatusReportsOnlyWaiting() {
        assertTrue(GameStatus.WAITING.isWaiting());
        assertFalse(GameStatus.WAITING.isActive());
        assertFalse(GameStatus.WAITING.isTerminal());
    }

    @Test
    public void activeStatusReportsOnlyActive() {
        assertFalse(GameStatus.ACTIVE.isWaiting());
        assertTrue(GameStatus.ACTIVE.isActive());
        assertFalse(GameStatus.ACTIVE.isTerminal());
    }

    @Test
    public void finishedStatusesReportTerminal() {
        assertAll(
                () -> assertTrue(GameStatus.WON_X.isTerminal()),
                () -> assertTrue(GameStatus.WON_O.isTerminal()),
                () -> assertTrue(GameStatus.DRAW.isTerminal()),
                () -> assertTrue(GameStatus.ABANDONED.isTerminal()));
    }
}
