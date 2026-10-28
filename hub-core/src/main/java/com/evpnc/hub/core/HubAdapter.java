package com.evpnc.hub.core;

import com.evpnc.hub.core.profile.ProfileDocument;
import java.util.Set;

public interface HubAdapter {
    Hub hub();

    Set<HubCapability> capabilities();

    ProfileDocument profile();
}
