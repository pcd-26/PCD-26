package main

import (
	"os"

	"odds-and-evens-game/app"
)

func main() {
	os.Exit(app.Run(os.Args[1:], os.Stdout, os.Stderr))
}
