# Clustered Smart Home Alarm System

This module can run as separate Pekko Cluster node processes or as a local demo.

## Control unit node

```bash
./run-cshas.sh control-unit --host 127.0.0.1 --port 2551 --seed-nodes 127.0.0.1:2551,127.0.0.1:2552,127.0.0.1:2553
```

PowerShell:

```powershell
.\run-cshas.ps1 control-unit --host 127.0.0.1 --port 2551 --seed-nodes 127.0.0.1:2551,127.0.0.1:2552,127.0.0.1:2553
```

## Keypad node

```bash
./run-cshas.sh keypad --host 127.0.0.1 --port 2552 --seed-nodes 127.0.0.1:2551,127.0.0.1:2552,127.0.0.1:2553
```

PowerShell:

```powershell
.\run-cshas.ps1 keypad --host 127.0.0.1 --port 2552 --seed-nodes 127.0.0.1:2551,127.0.0.1:2552,127.0.0.1:2553
```

## Sensor node

```bash
./run-cshas.sh sensor --host 127.0.0.1 --port 2553 --sensor-id front_door --sensor-type DOOR_WINDOW --zone PERIMETER --seed-nodes 127.0.0.1:2551,127.0.0.1:2552,127.0.0.1:2553
```

PowerShell:

```powershell
.\run-cshas.ps1 sensor --host 127.0.0.1 --port 2553 --sensor-id front_door --sensor-type DOOR_WINDOW --zone PERIMETER --seed-nodes 127.0.0.1:2551,127.0.0.1:2552,127.0.0.1:2553
```

## Local demo

```bash
./run-cshas.sh demo
```

PowerShell:

```powershell
.\run-cshas.ps1 demo
```

The local demo starts three local nodes on `127.0.0.1` using ports `2551`, `2552`, and `2553`.
