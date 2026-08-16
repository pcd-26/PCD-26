package pcd.shas.siren;

public interface AlertDevice {

    interface Command {}

    record Activate() implements Command {}

    record Deactivate() implements Command {}
}
