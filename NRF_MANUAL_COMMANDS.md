# nRF Connect Manual Fallback

Use this only for research/debugging if an APK or clean-room utility is not available.

## BLE UUIDs

Service:

```text
00002760-08c2-11e1-9073-0e8ac72e1011
```

Notify/TX:

```text
00002760-08c2-11e1-9073-0e8ac72e0012
```

PowerWatch X write/RX candidate:

```text
00002760-08c2-11e1-9073-0e8ac72e0013
```

Classic write/RX candidate:

```text
00002760-08c2-11e1-9073-0e8ac72e0011
```

## General process

1. Open nRF Connect.
2. Connect to the watch.
3. Enable notifications on `0012`.
4. Write commands to the appropriate RX/write characteristic.
5. Watch notifications and the physical watch screen.

## Warning

Manual command entry is error-prone. Avoid destructive commands:

```text
8422 = Factory Reset / Unpair
8425 = Enter Shipmode
8430522C5E08 = Debugger Lock
```

## Useful commands before framing/chunking

Raw command payloads:

```text
8410                    query/init
840107{UTC}{TZ}          set time/timezone
84010811223344           pair code example
8421                    save data to memory
8440                    show sync animation
8441                    hide sync animation
810F050082              SOFF / normal exit
```

Depending on the characteristic and app flow, these may need the PowerWatch packet framing/chunking used by the original app rather than being written as bare bytes.

## Recommended public-tool behavior

A clean rescue tool should generate the framed BLE writes automatically. Manual nRF entry should remain an advanced fallback, not the main user path.
