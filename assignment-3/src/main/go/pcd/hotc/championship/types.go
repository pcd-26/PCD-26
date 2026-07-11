package championship

// Choice represents the coin flip choice (Heads or Tails).
type Choice int

const (
	// Heads represents the heads side of the coin.
	Heads Choice = iota
	// Tails represents the tails side of the coin.
	Tails
)

// String returns the string representation of the Choice.
func (c Choice) String() string {
	if c == Heads {
		return "Heads"
	}
	return "Tails"
}

// GameResult represents the outcome of a game/match for a player.
type GameResult int

const (
	// Win indicates the player won the match.
	Win GameResult = iota
	// Lose indicates the player lost the match and is eliminated.
	Lose
	// Tie indicates a tie, requiring a rematch.
	Tie
)

// String returns the string representation of the GameResult.
func (g GameResult) String() string {
	switch g {
	case Win:
		return "Win"
	case Lose:
		return "Lose"
	case Tie:
		return "Tie"
	default:
		return "Unknown"
	}
}

// Request represents a request sent by a Referee to a Player to make a choice.
// It contains reply channels for the player to send back their choice and
// receive the match result.
type Request struct {
	// ResponseChan is the channel where the player sends their choice.
	ResponseChan chan<- Choice
	// FeedbackChan is the channel where the player receives the result of their choice.
	FeedbackChan <-chan GameResult
}

// PlayerInfo holds information about a player, including their ID and communication channel.
type PlayerInfo struct {
	// ID is the unique identifier of the player.
	ID int
	// ReqChan is the channel the player listens to for game invitations/requests.
	ReqChan chan Request
}
