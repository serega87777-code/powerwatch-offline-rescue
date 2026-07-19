# Disclaimer

This repository contains community repair and interoperability notes for legacy PowerWatch hardware.

It is not affiliated with, endorsed by, or supported by MATRIX Industries, PowerWatch, or any original vendor entity.

## Safety

Some BLE commands can reset, unpair, lock, or place a watch into a low-power shipping mode. Do not send commands unless you understand the effect.

Particularly dangerous commands:

```text
8422 = Factory Reset / Unpair
8425 = Enter Shipmode
8430522C5E08 = Debugger Lock
```

## Privacy

Do not publish private logs or screenshots without checking for:

- device MAC addresses;
- serial numbers;
- phone model and OS identifiers;
- local usernames and filesystem paths;
- account/email/notification data.

## Distribution

For public releases, prefer a clean-room implementation. Do not redistribute proprietary APKs, copied vendor assets, or private documents unless you have permission.

The notes are provided as-is for preservation, research, and repair of user-owned devices.
