TESTING - LingeringDragonBreathFix

Java client
- Lingering Potion visible
- Lingering Potion effects work
- Particles visible
- Dragon Breath visible and damages

Eaglercraft client
- No crash on Lingering Potion/Dragon Breath
- Effects/damage still applied server-side
- Particles not sent to Eagler (client-side suppressed)

Mixed server
- Java players see AEC normally
- Eagler players do not receive spawn/metadata packets for AEC

Edge cases
- Multiple worlds
- Teleport/respawn/reconnect
- Existing AEC when player joins
- Plugin disable/enable

Notes: Some behaviors require integration testing on a real Paper 1.12.2 + EaglerXServer environment.

Java client
- Lingering Potion visible
- Lingering Potion effects work
- Particles visible
- Dragon Breath visible and damages

Eaglercraft client
- No crash on Lingering Potion/Dragon Breath
- Effects/damage still applied server-side
- Particles not sent to Eagler (client-side suppressed)

Mixed server
- Java players see AEC normally
- Eagler players do not receive spawn/metadata packets for AEC

Edge cases
- Multiple worlds
- Teleport/respawn/reconnect
- Existing AEC when player joins
- Plugin disable/enable

Notes: Some behaviors require integration testing on a real Paper 1.12.2 + EaglerXServer environment.