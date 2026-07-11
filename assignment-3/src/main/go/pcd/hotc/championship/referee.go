package championship

import (
	"fmt"
	"math/rand"
)

// RunMatch coordinates a Heads-or-Tails match between two players.
// It requests choices from both players, generates a random coin flip,
// and determines the winner. In case of a tie (both players make the same choice),
// it notifies the players and retries until a winner is decided.
// Once a winner is found, the referee notifies both players and sends the winner's Info
// to the winnerChan.
func RunMatch(p1 PlayerInfo, p2 PlayerInfo, winnerChan chan<- PlayerInfo) {
	respChan1 := make(chan Choice)
	respChan2 := make(chan Choice)
	feedChan1 := make(chan GameResult)
	feedChan2 := make(chan GameResult)

	for {
		// Send game requests concurrently to avoid blocking/deadlock
		go func() {
			p1.ReqChan <- Request{ResponseChan: respChan1, FeedbackChan: feedChan1}
		}()
		go func() {
			p2.ReqChan <- Request{ResponseChan: respChan2, FeedbackChan: feedChan2}
		}()

		// Await choices from both players
		choice1 := <-respChan1
		choice2 := <-respChan2

		// Flip the referee coin
		coin := Choice(rand.Intn(2))

		fmt.Printf("[Match P%d vs P%d] P%d chose %v, P%d chose %v. Referee flipped %v.\n",
			p1.ID, p2.ID, p1.ID, choice1, p2.ID, choice2, coin)

		// Determine the outcome
		p1Matches := choice1 == coin
		p2Matches := choice2 == coin

		if p1Matches && !p2Matches {
			// Player 1 wins, Player 2 loses
			fmt.Printf("[Match P%d vs P%d] -> Player %d WINS!\n", p1.ID, p2.ID, p1.ID)
			feedChan1 <- Win
			feedChan2 <- Lose
			winnerChan <- p1
			return
		} else if p2Matches && !p1Matches {
			// Player 2 wins, Player 1 loses
			fmt.Printf("[Match P%d vs P%d] -> Player %d WINS!\n", p1.ID, p2.ID, p2.ID)
			feedChan1 <- Lose
			feedChan2 <- Win
			winnerChan <- p2
			return
		} else {
			// Tie (both matched or both missed)
			fmt.Printf("[Match P%d vs P%d] -> TIE! Retrying match...\n", p1.ID, p2.ID)
			feedChan1 <- Tie
			feedChan2 <- Tie
		}
	}
}
