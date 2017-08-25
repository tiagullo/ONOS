package org.onosproject.sdnip;

import org.onosproject.sdnip.data.*;
import com.fasterxml.jackson.databind.node.ArrayNode;

public interface SdnIpFibService {
    ArrayNode getTMs();
    ArrayNode getAnnouncedPrefixesFromCP();
    void resetRoutings();
    String addRouting(RoutingConfiguration r);
    String applyRouting(int routingID);
}
