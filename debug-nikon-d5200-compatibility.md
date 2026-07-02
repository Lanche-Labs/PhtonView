# Debug Session: Nikon D5200 Compatibility

## Status
[OPEN]

## Environment
- Device: Android 10 phone
- Camera: Nikon D5200
- App: PhtonView
- Connection: USB via OTG / USB cable

## Symptoms
User reports "没有几个功能是能用的" — most camera control features do not work with Nikon D5200.

## Hypotheses (to be falsified)
1. Nikon D5200 does not support live view via standard PTP, or requires a different opcode/session initialization.
2. Exposure parameter mapping (ISO/aperture/shutter speed/EV) uses Canon-oriented PTP property codes that do not match Nikon's device properties.
3. Metering mode / focus mode / capture opcodes are vendor-specific and the current PtpValueMapper lacks Nikon entries.
4. USB connection layer detects camera but fails handshake due to missing Nikon-specific initialization sequence.
5. The app assumes certain property value formats (e.g., aperture as f-number * 100) that Nikon reports differently.

## Next Steps
1. Start debug server.
2. Add instrumentation to PTP connection, property read/write, and capture paths.
3. Collect logs while app attempts to connect/control Nikon D5200.
4. Analyze evidence and implement minimal Nikon-specific fixes.
