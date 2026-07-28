# PumpRadar v4.3.9

Reviewed patch built on the verified PumpRadar v4.3.8 source artifact.

Changes:
- MC5 paper primary challenger `MC_HOLD_120`;
- TRADE3 challenger `D_TARGET1_HOLD_300`;
- thin/stale entry defer and composite veto telemetry;
- independent 500 ms position watcher;
- forward outcomes at 90, 150, 180 and 240 seconds.

The existing TRADE3 and MC5 control policies remain available for comparison. Real orders are not enabled.
