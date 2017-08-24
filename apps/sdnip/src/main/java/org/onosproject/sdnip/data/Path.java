package org.onosproject.sdnip.data;

import org.onosproject.net.DeviceId;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class Path {
    public List<DeviceId> path;
    public float weight;

    @JsonCreator
    public Path(@JsonProperty("path") List<String> path,
                @JsonProperty("weight") float weight) {
        this.path = new ArrayList<>();
        path.forEach(deviceName -> this.path.add(DeviceId.deviceId(deviceName)));
        this.weight = weight;
    }
}
