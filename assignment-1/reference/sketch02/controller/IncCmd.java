package pcd.sketch02.controller;

import pcd.sketch02.model.Counter;

public class IncCmd implements Cmd {

	@Override
	public void execute(Counter c) {
		c.inc();
	}

}
