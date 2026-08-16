package pcd.shas.siren;

import pcd.shas.common.MySerializable;

public interface AlertDevice {

    interface Command extends MySerializable {}

    record Activate() implements Command {}

    record Deactivate() implements Command {}
}
