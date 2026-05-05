package pcd.sketch02.controller;

import pcd.sketch02.model.Counter;

public interface Cmd {
	
	void execute(Counter c);
}
