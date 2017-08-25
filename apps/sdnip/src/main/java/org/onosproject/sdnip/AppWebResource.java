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
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.onosproject.rest.AbstractWebResource;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.InputStream;

import org.onosproject.sdnip.data.*;

@Path("")
public class AppWebResource extends AbstractWebResource {

    private SdnIpFibService sdnIpFibService;

    /*
    http://localhost:8181/onos/v1/sdnip/get_tm
    $ curl --user onos:rocks http://localhost:8181/onos/v1/sdnip/get_tm
    Returns the list of TM samples as a JSON
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
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTMs() {
        sdnIpFibService = get(SdnIpFibService.class);
        ObjectNode result = mapper().createObjectNode()
                .putPOJO("response", sdnIpFibService.getTMs());
        return ok(result).build();
    }

    /*
    Returns a list of associations between a ConnectPoint of BGP neighbors and
    the list of IpPrefix announcements received from that ConnectPoint as a JSON
    {
        "response": [
            {
                "CP": "of:00000000000000a2/1",
                "IpPrefixList": ["192.168.2.0/24"]
            },
            {
                "CP": "of:00000000000000a1/1",
                "IpPrefixList": ["192.168.1.0/24", "192.168.10.0/24"]
            }
        ]
    }
     */
    @GET
    @Path("get_announced_prefix_from_cp")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAnnouncedPrefixesFromCP() {
        sdnIpFibService = get(SdnIpFibService.class);
        ObjectNode result = mapper().createObjectNode()
                .putPOJO("response", sdnIpFibService.getAnnouncedPrefixesFromCP());
        return ok(result).build();
    }

    /*
    Configure a list of routing configurations. Each configuration specifies, for
    each demand, the list of weighted paths. The body of HTTP POST is a JSON
    {
        "routing_list": [
            {
                "r_config": [
                    {
                        "demand":
                            ["192.168.1.0/24",
                            "192.168.2.0/24"],
                        "paths": [
                            {
                                "path":
                                    ["of:00000000000000a1",
                                    "of:00000000000000a2"],
                                "weight": 1.0
                            }
                        ]
                    }
                ],
                "r_ID": 0
            }
        ]
    }
    */
    @POST
    @Path("add_routing")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addRouting(InputStream stream) {
        sdnIpFibService = get(SdnIpFibService.class);
        ObjectNode result = mapper().createObjectNode();
        StringBuilder resultString = new StringBuilder();

        mapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            AddRoutingMsg msg = mapper().readValue(stream, AddRoutingMsg.class);
            for (RoutingConfiguration routingConfiguration: msg.routing_list) {
                String outcome = sdnIpFibService.addRouting(routingConfiguration);
                if (!outcome.equals("OK")) {
                    if (resultString.length() > 0)
                        resultString.append(" ");
                    resultString.append(outcome);
                }
            }
            if (resultString.length() > 0)
                result.put("response", resultString.toString());
            else
                result.put("response", "OK");
            return ok(result).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).
                    entity(e.toString())
                    .build();
        }
    }

    /*
    Request the activation of a routing configurations.
    The body of HTTP POST is a JSON
    {
        "r_ID": 0
    }
    */
    @POST
    @Path("apply_routing")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response applyRouting(InputStream stream) {
        sdnIpFibService = get(SdnIpFibService.class);
        ObjectNode result = mapper().createObjectNode();
        try {
            ApplyRoutingMsg msg = mapper().readValue(stream, ApplyRoutingMsg.class);
            String outcome = sdnIpFibService.applyRouting(msg.r_ID);
            result.put("response", outcome);
            return ok(result).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).
                    entity(e.toString())
                    .build();
        }
    }

}
