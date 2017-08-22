package org.onosproject.sdnip.data;

import java.util.List;

public class RoutingConfiguration {
    public int r_ID;
    public List<Route> r_config;

    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append(String.format("\nrouting_id: %d", this.r_ID));
        this.r_config.forEach(route -> {
            s.append(String.format("\ndemand: %s", route.demand.toString()));
            for (Path path: route.paths) {
                s.append(String.format("\npath: %s", path.path));
                s.append(String.format("\nweight: %f", path.weight));
            }
        });
        return s.toString();
    }
}
