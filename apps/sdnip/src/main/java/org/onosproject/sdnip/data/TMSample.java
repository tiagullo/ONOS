package org.onosproject.sdnip.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class TMSample {
    private long timestamp;
    private String demand;
    private long bytes;

    public TMSample(long timestamp, String demand, long bytes) {
        this.timestamp = timestamp;
        this.demand = demand;
        this.bytes = bytes;
    }

    public ObjectNode toJSONnode(ObjectMapper mapper) {
        return mapper.createObjectNode()
                .put("timestamp", this.timestamp)
                .put("demand", this.demand)
                .put("bytes", this.bytes);
    }
}
