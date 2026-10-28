package com.evpnc.hub.core;

/** Commands the platform sends to a hub on behalf of a driver. Implemented by an adapter. */
public interface RemoteSessionPort {
    RemoteResult start(RemoteStart command);

    RemoteResult stop(RemoteStop command);
}
