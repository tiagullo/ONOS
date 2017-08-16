/*
 * Copyright 2017-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.sdnip;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.onosproject.rest.AbstractWebResource;

import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

@Path("")
public class AppWebResource extends AbstractWebResource {

    private SdnIpFibService sdnIpFibService;

    /*
    http://localhost:8181/onos/v1/sdnip/get_tm
    $ curl --user onos:rocks http://localhost:8181/onos/v1/sdnip/get_tm
    Returns the list of TM samples as JSON
    {
        "response": [
            {
                "timestamp": 1502806046,
                "demand": "192.168.1.0/24=192.168.2.0/24",
                "bytes": 0
            },
            {
                "timestamp": 1502806046,
                "demand": "192.168.10.0/24=192.168.2.0/24",
                "bytes": 0
            }
        ]
    }
    */
    @GET
    @Path("get_tm")
    public Response getTMs() {
        sdnIpFibService = get(SdnIpFibService.class);
        ObjectNode result = mapper().createObjectNode()
                .putPOJO("response", sdnIpFibService.getTMs());
        return ok(result).build();
    }

    @GET
    @Path("get_announced_prefix_from_cp")
    public Response getAnnouncedPrefixesFromCP() {
        sdnIpFibService = get(SdnIpFibService.class);
        ObjectNode result = mapper().createObjectNode()
                .putPOJO("response", sdnIpFibService.getAnnouncedPrefixesFromCP());
        return ok(result).build();
    }

    @PUT
    @Path("set_routing")
    public Response setRouting() {
        sdnIpFibService = get(SdnIpFibService.class);
        ObjectNode node = mapper().createObjectNode()
                .put("response", sdnIpFibService.setRouting());
        return ok(node).build();
    }

}
