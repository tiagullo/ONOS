package org.onosproject.sdnip;


import com.fasterxml.jackson.databind.node.ArrayNode;

public interface SdnIpFibService {
    ArrayNode getTMs();
    String setRouting();
    ArrayNode getAnnouncedPrefixesFromCP();
}
