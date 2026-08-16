package domain

import "fmt"

// Player identifies a championship participant.
type Player struct {
	id   int
	name string
}

// NewPlayer creates a validated player.
func NewPlayer(id int, name string) (Player, error) {
	if id <= 0 {
		return Player{}, fmt.Errorf("player ID must be positive: %d", id)
	}
	if name == "" {
		return Player{}, fmt.Errorf("player name must not be empty")
	}

	return Player{id: id, name: name}, nil
}

// ID returns the player identifier.
func (p Player) ID() int {
	return p.id
}

// Name returns the player name.
func (p Player) Name() string {
	return p.name
}
