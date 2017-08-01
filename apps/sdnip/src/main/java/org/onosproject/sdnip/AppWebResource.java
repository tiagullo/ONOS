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
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onosproject.app.ApplicationService;
import org.onosproject.core.CoreService;
import org.onosproject.rest.AbstractWebResource;

import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

import static org.onlab.util.Tools.nullIsNotFound;

@Path("")
public class AppWebResource extends AbstractWebResource {

    private SdnIpFibService service;

    // http://localhost:8181/onos/v1/sdnip/get_tm
    @GET
    @Path("get_tm")
    public Response getTMs() {
        service = get(SdnIpFibService.class);
        ObjectNode node = mapper().createObjectNode().put("response", service.getTMs());
        return ok(node).build();
    }

    @PUT
    @Path("set_routing")
    public Response setRouting() {
        service = get(SdnIpFibService.class);
        ObjectNode node = mapper().createObjectNode().put("response", service.setRouting());
        return ok(node).build();
    }

}
