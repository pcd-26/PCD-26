package pcd.sketch02.model;

public class AutonomousUpdater extends Thread {

	private Counter count;
	
	public AutonomousUpdater(Counter count) {
		this.count = count;	
	}
	
	public void run() {
		while (true) {
			sleepAbit();
			count.inc();
		}		
	}
	
	private void sleepAbit() {
		try {
			Thread.sleep(1000);
		} catch (Exception ex) {}
	}
}
