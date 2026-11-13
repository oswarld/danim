# Pleos Vehicle SDK ships its own consumer rules; keep the (obfuscated) exception type that the
# collector inspects reflectively for its ErrorCode.
-keep class ai.pleos.playground.vehicle.callback.exception.** { *; }
-keep class ai.pleos.playground.vehicle.constant.ErrorCode { *; }
