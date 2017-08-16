package org.onosproject.sdnip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class TMSample {
    private long timestamp;
    private String demand;
    private long bytes;

    TMSample(long timestamp, String demand, long bytes) {
        this.timestamp = timestamp;
        this.demand = demand;
        this.bytes = bytes;
    }

    ObjectNode toJSONnode() {
        return new ObjectMapper().createObjectNode()
                .put("timestamp", this.timestamp)
                .put("demand", this.demand)
                .put("bytes", this.bytes);
    }
}
