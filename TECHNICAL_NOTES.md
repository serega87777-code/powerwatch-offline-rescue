# PowerWatch X BLE Technical Notes

These notes summarize the local BLE rescue findings in a privacy-safe form.

## Scope

Goal: restore basic local operation of a legacy PowerWatch / PowerWatch X without the original cloud services.

Confirmed basic operations:

- BLE connection;
- query/init response parsing;
- UTC/timezone write;
- save-to-memory;
- exit from sync/pairing UI to the normal watch face.

## Protocol family

The protocol is a MATRIX data-transfer style BLE protocol. User configuration commands use the `84` command family.

### Query commands

```text
8410 -> query basic info: software version, hardware info, battery, UTC, lock status
8411 -> query user/timezone config
8412 -> query activity/goal-related values
```

### Time command

```text
840107{8 hex digits Unix UTC}{4 hex digits timezone minutes}
```

The UTC value is Unix epoch seconds. Timezone is minutes offset from UTC encoded as a 16-bit value.

Examples:

```text
UTC+1  = 003C
UTC+2  = 0078
UTC+0  = 0000
UTC-5  = FED4  # signed 16-bit representation of -300
```

### Pair/config commands

```text
840108{value}       write pair code
84010811223344      example/default pair code observed in legacy notes
840103{G}{A}{W}{H}  basic user profile values
840109{value}       goal distance
84010A{value}       goal calories
84010B{value}       goal sleep
8421                save data to memory
```

### Sync / visibility / exit commands

```text
8440        show sync animation
8441        hide sync animation
844201      auto-disconnect related command observed in app flow
810F050082  SOFF / normal exit command observed in app flow
```

### Dangerous system commands

```text
8422          Factory Reset / Unpair
8425          Enter Shipmode
8430522C5E08  Debugger Lock
```

Do not include these in automatic rescue flows unless the UI makes the destructive effect very explicit.

## BLE UUIDs

Service:

```text
00002760-08c2-11e1-9073-0e8ac72e1011
```

Characteristics:

```text
00002760-08c2-11e1-9073-0e8ac72e0011
00002760-08c2-11e1-9073-0e8ac72e0012
00002760-08c2-11e1-9073-0e8ac72e0013
```

Observed role:

- notify/TX: `0012`
- PowerWatch X write/RX: `0013`
- classic write/RX candidate: `0011`

## Successful high-level flow

```text
connect
subscribe to notify
query/init
send time/timezone
send required config/pair values
save: 8421
normal exit: 810F050082
verify physical watch screen
```

## Success criteria

Do not call the rescue successful just because the Android app says pairing succeeded. Verify on the physical watch:

- it leaves failed/pairing/sync screens;
- it displays the normal watch face;
- it displays the expected time;
- it continues keeping time after the app disconnects.

## Clean-room app recommendation

A public rescue app should be newly written and should not redistribute the original proprietary APK or assets. Minimal features:

1. BLE scan and device selection.
2. Connect and subscribe to notify.
3. Set current time based on phone timezone.
4. Save and exit.
5. Optional expert panel for manual commands with warnings.
