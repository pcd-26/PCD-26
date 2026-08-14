package main

import (
	"os"

	"odds-and-evens-game/championship"
)

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr, championship.NewRandomCoinTosserFactory()))
}
