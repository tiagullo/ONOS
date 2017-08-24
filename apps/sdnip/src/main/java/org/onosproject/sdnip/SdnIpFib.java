/*
 * Copyright 2015-present Open Networking Laboratory
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import javafx.util.Pair;
import org.apache.felix.scr.annotations.*;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.incubator.net.intf.Interface;
import org.onosproject.incubator.net.intf.InterfaceEvent;
import org.onosproject.incubator.net.intf.InterfaceListener;
import org.onosproject.incubator.net.intf.InterfaceService;
import org.onosproject.incubator.net.routing.ResolvedRoute;
import org.onosproject.incubator.net.routing.RouteEvent;
import org.onosproject.incubator.net.routing.RouteListener;
import org.onosproject.incubator.net.routing.RouteService;
import org.onosproject.net.*;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleEvent;
import org.onosproject.net.flow.FlowRuleListener;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.IPCriterion;
import org.onosproject.net.intent.*;
import org.onosproject.net.intent.constraint.PartialFailureConstraint;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.statistic.StatisticStore;
import org.onosproject.net.topology.TopologyStore;
import org.onosproject.routing.IntentSynchronizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.onosproject.sdnip.data.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


/**
 * FIB component of SDN-IP.
 */
@Component(immediate = true, enabled = false)
@Service
public class SdnIpFib implements SdnIpFibService {
    private Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected InterfaceService interfaceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected IntentSynchronizationService intentSynchronizer;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected RouteService routeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected StatisticStore statisticStore;

    //TODO remove
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected TopologyStore store;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LinkService linkService;

    private final InternalRouteListener routeListener
            = new InternalRouteListener();
    private final InternalInterfaceListener interfaceListener
            = new InternalInterfaceListener();
    private final InternalFlowRuleListener flowStatsListener
            = new InternalFlowRuleListener();

    private static final int PRIORITY_OFFSET = 100;
    private static final int PRIORITY_MULTIPLIER = 5;
    protected static final ImmutableList<Constraint> CONSTRAINTS
            = ImmutableList.of(new PartialFailureConstraint());

    private final Map<IpPrefix, MultiPointToSinglePointIntent> routeIntents
            = new ConcurrentHashMap<>();

    private final Map<Key, Intent> routeIntentsSingle
            = new ConcurrentHashMap<>();

    private final Map<ConnectPoint,Set<IpPrefix>> announcedPrefixesFromCP
            = new ConcurrentHashMap<>();

    private final Map<ConnectPoint,MacAddress> MACFromCP
            = new ConcurrentHashMap<>();

    private final Map<Pair<IpPrefix,IpPrefix>, List<Long[]>> TM
            = new ConcurrentHashMap<>();

    //LinkedList has add() and Iterator.remove() in O(1)
    private final List<TMSample> TMSamples = new LinkedList<>();

    private final Map<Integer, RoutingConfiguration> routingConfigurations
            = new ConcurrentHashMap<>();

    //Auxiliary Map to store pairs of non-local prefixes
    private final Map<IpPrefix,Set<IpPrefix>> prefixPairs
            = new ConcurrentHashMap<>();

    private ApplicationId appId;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final boolean KEEP_FLOWS_OF_WITHDRAWN_ROUTES = true;

    @Activate
    public void activate() {
        appId = coreService.getAppId(SdnIp.SDN_IP_APP);
        log.info("SDN-IP activation");
        interfaceService.addListener(interfaceListener);
        routeService.addListener(routeListener);
        flowRuleService.addListener(flowStatsListener);
    }

    @Deactivate
    public void deactivate() {
        interfaceService.removeListener(interfaceListener);
        routeService.removeListener(routeListener);
        flowRuleService.removeListener(flowStatsListener);
    }

    private void update(ResolvedRoute route) {
        synchronized (this) {
            IpPrefix announcedPrefix = route.prefix();
            log.info("Received BGP announcement for {}", announcedPrefix.toString());

            List<Intent> arrayIntent =
                    generateSrcDstRouteIntents(announcedPrefix, route.nextHop(), route.nextHopMac());

            arrayIntent.forEach(intent -> {
                routeIntentsSingle.put(intent.key(), intent);
                intentSynchronizer.submit(intent);
            });
        }
    }

    private void withdraw(ResolvedRoute route) {
        synchronized (this) {
            /*
            We can test this without touching quagga conf file simply with
            mininet> r1 ifconfig r1-eth0 down
            mininet> r1 ifconfig r1-eth0 up
            provided that KEEP_FLOWS_OF_WITHDRAWN_ROUTES = false
            */
            IpPrefix withdrawnPrefix = route.prefix();
            log.info("Withdrawn BGP announcement for {}", withdrawnPrefix.toString());
            if (KEEP_FLOWS_OF_WITHDRAWN_ROUTES) {
                /*
                Why should we put KEEP_FLOWS_OF_WITHDRAWN_ROUTES = True?
                When an IpPrefix is withdrawn we should:
                a) uninstall the intents involved
                b) remove the all the flow pairs (*, IpPrefix) and (IpPrefix, *)
                   from prefixPairs Map
                c) forget about the source CP of the announce
                because:
                a) we do not want connectivity to/from witdrawn IpPrefix
                b) we do not want connectivity to/from witdrawn IpPrefix when a
                   new IpPrefix is announced (we exploit prefixPairs Map)
                c) we might want to support mobility of IpPrefixes between
                   different BGP speakers
                However:
                1) if a flow is present in at least 1 TM sample, CRR still
                   consider a 0 bps contribute iwhen no sample is received
                2) CRR assumes fixed IpPrefixes-CP association (i.e. each demand
                   does not change its attachment point, otherwise). To allow
                   mobility We might consider it as a different demand, for ex.)
                3) the day after the training day, we apply routing also to the
                   flows which just lived 1 TM sampling period
                Thus:
                CRR needs KEEP_FLOWS_OF_WITHDRAWN_ROUTES = True
                 */
                return;
            }
            if (prefixPairs.containsKey(withdrawnPrefix)) {
                prefixPairs.get(withdrawnPrefix).forEach(dstPrefix -> {
                    // remove all the intents involving (withdrawnPrefix, *) and
                    // (*, withdrawnPrefix) flows
                    String keyStringAB = withdrawnPrefix.toString().concat("-").concat(dstPrefix.toString());
                    String keyStringBA = dstPrefix.toString().concat("-").concat(withdrawnPrefix.toString());
                    Key keyRemovedAB = Key.of(keyStringAB, appId);
                    Key keyRemovedBA = Key.of(keyStringBA, appId);

                    Intent intentAB = routeIntentsSingle.remove(keyRemovedAB);
                    Intent intentBA = routeIntentsSingle.remove(keyRemovedBA);
                    intentSynchronizer.withdraw(intentAB);
                    intentSynchronizer.withdraw(intentBA);

                    //Unlearn the flows (*, withdrawnPrefix)
                    if (prefixPairs.containsKey(dstPrefix))
                        prefixPairs.get(dstPrefix).remove(withdrawnPrefix);

                });
                //Unlearn the flows (withdrawnPrefix, *)
                prefixPairs.remove(withdrawnPrefix);
            }

            //forget the source CP of the withdrawnPrefix
            Interface announceInterface =
                    interfaceService.getMatchingInterface(route.nextHop());
            if (announceInterface != null &&
                    announcedPrefixesFromCP.containsKey(announceInterface.connectPoint())) {
                announcedPrefixesFromCP.get(announceInterface.connectPoint()).remove(withdrawnPrefix);
            }
        }
    }

    /**
     * Generates a route intent for a prefix, the next hop IP address, and
     * the next hop MAC address.
     * <p/>
     * This method will find the egress interface for the intent.
     * Intent will match dst IP prefix and rewrite dst MAC address at all other
     * border switches, then forward packets according to dst MAC address.
     *
     * @param prefix            IP prefix of the route to add
     * @param nextHopIpAddress  IP address of the next hop
     * @param nextHopMacAddress MAC address of the next hop
     * @return the generated intent, or null if no intent should be submitted
     */
    private MultiPointToSinglePointIntent generateRouteIntent(
            IpPrefix prefix,
            IpAddress nextHopIpAddress,
            MacAddress nextHopMacAddress) {

        // Find the attachment point (egress interface) of the next hop
        Interface egressInterface =
                interfaceService.getMatchingInterface(nextHopIpAddress);
        if (egressInterface == null) {
            log.warn("No outgoing interface found for {}",
                    nextHopIpAddress);
            return null;
        }
        ConnectPoint egressPort = egressInterface.connectPoint();

        log.debug("Generating intent for prefix {}, next hop mac {}",
                prefix, nextHopMacAddress);

        log.info("GenerateRouteIntent");

        Set<FilteredConnectPoint> ingressFilteredCPs = Sets.newHashSet();

        // TODO this should be only peering interfaces
        interfaceService.getInterfaces().forEach(intf -> {
            // Get only ingress interfaces with IPs configured
            if (validIngressIntf(intf, egressInterface)) {
                TrafficSelector.Builder selector =
                        buildIngressTrafficSelector(intf, prefix);
                FilteredConnectPoint ingressFilteredCP =
                        new FilteredConnectPoint(intf.connectPoint(), selector.build());
                ingressFilteredCPs.add(ingressFilteredCP);
                //source ?
                IpPrefix srcPrefix = intf.ipAddressesList().get(0).subnetAddress();
                log.info("Iterating from prefix {}", srcPrefix.toString());
            }
        });

        // Build treatment: rewrite the destination MAC address
        TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder()
                .setEthDst(nextHopMacAddress);

        // Build the egress selector for VLAN Id
        TrafficSelector.Builder selector =
                buildTrafficSelector(egressInterface);
        FilteredConnectPoint egressFilteredCP =
                new FilteredConnectPoint(egressPort, selector.build());

        // Set priority
        int priority =
                prefix.prefixLength() * PRIORITY_MULTIPLIER + PRIORITY_OFFSET;

        // Set key
        Key key = Key.of(prefix.toString(), appId);

        return MultiPointToSinglePointIntent.builder()
                .appId(appId)
                .key(key)
                .filteredIngressPoints(ingressFilteredCPs)
                .filteredEgressPoint(egressFilteredCP)
                .treatment(treatment.build())
                .priority(priority)
                .constraints(CONSTRAINTS)
                .build();
    }

    //Returns a proper Intent among PointToPointIntent and LinkCollectionIntent
    private Intent generateConnectivityIntent(ApplicationId appId, Key key,
                                     TrafficSelector selector,
                                     FilteredConnectPoint ingressFilteredCP,
                                     FilteredConnectPoint egressFilteredCP,
                                     TrafficTreatment treatment, int priority,
                                     List<Constraint> constraints,
                                     List<Link> links) {
        if (links == null) {
            return PointToPointIntent.builder()
                    .appId(appId)
                    .key(key)
                    .selector(selector)
                    .filteredIngressPoint(ingressFilteredCP)
                    .filteredEgressPoint(egressFilteredCP)
                    .treatment(treatment)
                    .priority(priority)
                    .constraints(constraints)
                    .build();
        } else {
            //We don't use a PointToPointIntent with a WaypointConstraint
            //to avoid filtering the result of pathService.getPaths() when our
            //Path is already computed and available as a List<Link>!
            return LinkCollectionIntent.builder()
                    .appId(appId)
                    .key(key)
                    .selector(selector)
                    .filteredIngressPoints(ImmutableSet.of(ingressFilteredCP))
                    .filteredEgressPoints(ImmutableSet.of(egressFilteredCP))
                    .treatment(treatment)
                    .priority(priority)
                    .constraints(constraints)
                    .links(ImmutableSet.copyOf(links))
                    .applyTreatmentOnEgress(true)
                    .build();
        }
    }

    //TODO add doc
    private Intent generateSrcDstIntent(Interface ingressInterface, IpPrefix ingressPrefix, ConnectPoint ingressCP,
                                        Interface egressInterface, IpPrefix egressPrefix, ConnectPoint egressCP,
                                        MacAddress egressMAC, List<Link> links) {
        //Filtered ingress ConnectPoint
        TrafficSelector.Builder selectorIn =
                buildIngressTrafficSelector(ingressInterface, ingressPrefix);
        FilteredConnectPoint ingressFilteredCP =
                new FilteredConnectPoint(ingressCP, selectorIn.build());

        //Filtered egress ConnectPoint
        TrafficSelector.Builder selectorOut =
                buildEgressTrafficSelector(egressInterface, egressPrefix);
        FilteredConnectPoint egressFilteredCP =
                new FilteredConnectPoint(egressCP, selectorOut.build());

        //Match on IpSrc+IpDst
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchIPSrc(ingressPrefix).matchIPDst(egressPrefix);

        // Build treatment: rewrite the destination MAC address
        TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder()
                .setEthDst(egressMAC);

        // Priority
        int priority =
                egressPrefix.prefixLength() * PRIORITY_MULTIPLIER + PRIORITY_OFFSET;

        // Intent key
        Key key = Key.of(ingressPrefix.toString().concat("-").concat(egressPrefix.toString()), appId);

        return generateConnectivityIntent(appId,
                                 key,
                                 selector.build(),
                                 ingressFilteredCP,
                                 egressFilteredCP,
                                 treatment.build(),
                                 priority,
                                 CONSTRAINTS,
                                 links);
    }


    //TODO add documentation (as generateRouteIntent)
    private List<Intent> generateSrcDstRouteIntents(
            IpPrefix announcedPrefix,
            IpAddress nextHopIpAddress,
            MacAddress nextHopMacAddress) {

        // Find the attachment point (egress interface) of the next hop
        Interface announceInterface =
                interfaceService.getMatchingInterface(nextHopIpAddress);
        if (announceInterface == null) {
            log.warn("No outgoing interface found for {}",
                     nextHopIpAddress);
            return null;
        }

        //Update the list of announcements received from the CP
        ConnectPoint announceCP = announceInterface.connectPoint();
        if (announcedPrefixesFromCP.containsKey(announceCP)) {
            announcedPrefixesFromCP.get(announceCP).add(announcedPrefix);
        }
        else {
            //HashSet has no duplicates, is un-ordered and O(1) add()/remove()
            announcedPrefixesFromCP.put(announceCP, new HashSet<IpPrefix>(Arrays.asList(announcedPrefix)));
        }

        //Update the MAC of the BGP speaker we received the announcement from
        MACFromCP.put(announceCP, nextHopMacAddress);

        List<Intent> intentsList = new ArrayList<Intent>();

        /*
        We received a new announcement for 'announcedPrefix' from 'announceInterface'.
        We need to iterate over all the other peering interfaces 'ingressInterface' and, for each for them,
        over all the prefixes announced from such interfaces.
         */
        // TODO this should be only peering interfaces
        interfaceService.getInterfaces().forEach(ingressInterface -> {
            // Get only ingress interfaces with IPs configured
            if (validIngressIntf(ingressInterface, announceInterface)) {

                if (announcedPrefixesFromCP.containsKey(ingressInterface.connectPoint())) {
                    announcedPrefixesFromCP.get(ingressInterface.connectPoint()).forEach(otherAnnouncedPrefix -> {
                        //A is the device I'm currently iterating over as source device
                        //B is the device I received the announce from (destination device)
                        Intent singleIntentAB = generateSrcDstIntent(ingressInterface, otherAnnouncedPrefix, ingressInterface.connectPoint(),
                                                               announceInterface, announcedPrefix, announceCP, nextHopMacAddress, null);

                        intentsList.add(singleIntentAB);

                        Intent singleIntentBA = generateSrcDstIntent(announceInterface, announcedPrefix, announceCP,
                                                               ingressInterface, otherAnnouncedPrefix, ingressInterface.connectPoint(), MACFromCP.get(ingressInterface.connectPoint()), null);

                        intentsList.add(singleIntentBA);

                        //Initialize the TM for demands otherAnnouncedPrefix -> announcedPrefix ...
                        TM.put(new Pair(otherAnnouncedPrefix, announcedPrefix), new ArrayList<Long[]>());
                        //... and announcedPrefix -> otherAnnouncedPrefix
                        TM.put(new Pair(announcedPrefix, otherAnnouncedPrefix), new ArrayList<Long[]>());


                        //Add 'otherAnnouncedPrefix' to the list of pairs starting from 'announcedPrefix', i.e. (announcedPrefix, *)
                        if (prefixPairs.containsKey(announcedPrefix)) {
                            prefixPairs.get(announcedPrefix).add(otherAnnouncedPrefix);
                        }
                        else
                            prefixPairs.put(announcedPrefix, new HashSet<IpPrefix>(Arrays.asList(otherAnnouncedPrefix)));

                        //Add 'announcedPrefix' to the list of pairs starting from 'otherAnnouncedPrefix', i.e. (otherAnnouncedPrefix, *)
                        if (prefixPairs.containsKey(otherAnnouncedPrefix)) {
                            prefixPairs.get(otherAnnouncedPrefix).add(announcedPrefix);
                        }
                        else
                            prefixPairs.put(otherAnnouncedPrefix, new HashSet<IpPrefix>(Arrays.asList(announcedPrefix)));
                    });
                }
            }
        });

        return intentsList;
    }

    private List<Link> createPathFromDeviceList(List<DeviceId> deviceList) {
        List<Link> path = new ArrayList<Link>();
        for (int i=0; i<deviceList.size()-1; i++)
        {
            DeviceId devEgress = deviceList.get(i);
            DeviceId devIngress = deviceList.get(i+1);
            // The common Link between DevEgress and DevIngress is the intersection of their links
            Set<Link> commonLinks = new HashSet<Link>(linkService.getDeviceEgressLinks(devEgress));
            commonLinks.retainAll(linkService.getDeviceIngressLinks(devIngress));
            if (commonLinks.size() == 0) {
                log.error("No link found between node %s and node %s!",
                         devEgress.toString(), devIngress.toString());
            }
            else if (commonLinks.size() == 1) {
                path.add(commonLinks.iterator().next());
            } else {
                log.warn("%d links found between node %s and node %s: taking the first one!",
                        commonLinks.size(), devEgress.toString(), devIngress.toString());
                path.add(commonLinks.iterator().next());
            }
        }
        return path;
    }

    //TODO check 'addInterface' in the legacy SDN-IP
    private void addInterface(Interface intf) {
        synchronized (this) {
                //map.entry Returns the key corresponding to this entry. in questo caso
                //ipprefix è la key e multipoint è il value.
            for (Map.Entry<IpPrefix, MultiPointToSinglePointIntent> entry : routeIntents.entrySet()) {
                // Retrieve the IP prefix and affected intent
                IpPrefix prefix = entry.getKey();
                MultiPointToSinglePointIntent intent = entry.getValue();


                // Add new ingress FilteredConnectPoint
                Set<FilteredConnectPoint> ingressFilteredCPs =
                        Sets.newHashSet(intent.filteredIngressPoints());

                // Create the new traffic selector
                TrafficSelector.Builder selector =
                        buildIngressTrafficSelector(intf, prefix);

                // Create the Filtered ConnectPoint and add it to the existing set
                FilteredConnectPoint newIngressFilteredCP =
                        new FilteredConnectPoint(intf.connectPoint(), selector.build());
                ingressFilteredCPs.add(newIngressFilteredCP);


                // Create new intent
                MultiPointToSinglePointIntent newIntent =
                        MultiPointToSinglePointIntent.builder(intent)
                                .filteredIngressPoints(ingressFilteredCPs)
                                .build();

                routeIntents.put(entry.getKey(), newIntent);
                intentSynchronizer.submit(newIntent);
            }
        }
    }

    /*
     * Handles the case in which an existing interface gets removed.
     */
    //TODO check 'addInterface' in the legacy SDN-IP
    private void removeInterface(Interface intf) {
        synchronized (this) {
            for (Map.Entry<IpPrefix, MultiPointToSinglePointIntent> entry : routeIntents.entrySet()) {
                // Retrieve the IP prefix and intent possibly affected
                IpPrefix prefix = entry.getKey();
                MultiPointToSinglePointIntent intent = entry.getValue();



                // The interface removed might be an ingress interface, so the
                // selector needs to match on the interface tagging params and
                // on the prefix
                TrafficSelector.Builder ingressSelector =
                        buildIngressTrafficSelector(intf, prefix);
                FilteredConnectPoint removedIngressFilteredCP =
                        new FilteredConnectPoint(intf.connectPoint(),
                                                 ingressSelector.build());

                // The interface removed might be an egress interface, so the
                // selector needs to match only on the interface tagging params
                TrafficSelector.Builder selector = buildTrafficSelector(intf);
                FilteredConnectPoint removedEgressFilteredCP =
                        new FilteredConnectPoint(intf.connectPoint(), selector.build());

                if (intent.filteredEgressPoint().equals(removedEgressFilteredCP)) {
                     // The interface is an egress interface for the intent.
                     // This intent just lost its head. Remove it and let higher
                     // layer routing reroute
                    intentSynchronizer.withdraw(routeIntents.remove(entry.getKey()));
                } else {
                    if (intent.filteredIngressPoints().contains(removedIngressFilteredCP)) {
                         // The FilteredConnectPoint is an ingress
                         // FilteredConnectPoint for the intent
                        Set<FilteredConnectPoint> ingressFilteredCPs =
                                Sets.newHashSet(intent.filteredIngressPoints());

                        // Remove FilteredConnectPoint from the existing set
                        ingressFilteredCPs.remove(removedIngressFilteredCP);

                        if (!ingressFilteredCPs.isEmpty()) {
                             // There are still ingress points. Create a new
                             // intent and resubmit
                            MultiPointToSinglePointIntent newIntent =
                                    MultiPointToSinglePointIntent.builder(intent)
                                            .filteredIngressPoints(ingressFilteredCPs)
                                            .build();

                            routeIntents.put(entry.getKey(), newIntent);
                            intentSynchronizer.submit(newIntent);
                        } else {
                             // No more ingress FilteredConnectPoint. Withdraw
                             //the intent
                            intentSynchronizer.withdraw(routeIntents.remove(entry.getKey()));
                        }
                    }
                }
            }
        }
    }

    /*
     * Builds an ingress traffic selector builder given an ingress interface and
     * the IP prefix to be reached.
     */
    private TrafficSelector.Builder buildEgressTrafficSelector(Interface intf, IpPrefix prefix) {
        TrafficSelector.Builder selector = buildTrafficSelector(intf);

        // Match the destination IP prefix at the first hop
        if (prefix.isIp4()) {
            selector.matchEthType(Ethernet.TYPE_IPV4);
            // if it is default route, then we do not need match destination
            // IP address
            if (prefix.prefixLength() != 0) {
                selector.matchIPDst(prefix);
            }
        } else {
            selector.matchEthType(Ethernet.TYPE_IPV6);
            // if it is default route, then we do not need match destination
            // IP address
            if (prefix.prefixLength() != 0) {
                selector.matchIPv6Dst(prefix);
            }
        }
        return selector;
    }

    private TrafficSelector.Builder buildIngressTrafficSelector(Interface intf, IpPrefix prefix) {
        TrafficSelector.Builder selector = buildTrafficSelector(intf);

        // Match the source IP prefix at the first hop
        if (prefix.isIp4()) {
            selector.matchEthType(Ethernet.TYPE_IPV4);
            // if it is default route, then we do not need match source
            // IP address
            if (prefix.prefixLength() != 0) {
                selector.matchIPSrc(prefix);
            }
        } else {
            selector.matchEthType(Ethernet.TYPE_IPV6);
            // if it is default route, then we do not need match source
            // IP address
            if (prefix.prefixLength() != 0) {
                selector.matchIPv6Src(prefix);
            }
        }
        return selector;
    }

    /*
     * Builds a traffic selector builder based on interface tagging settings.
     */
    private TrafficSelector.Builder buildTrafficSelector(Interface intf) {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();

        // TODO: Consider other tag types
        // Match the VlanId if specified in the network interface configuration
        VlanId vlanId = intf.vlan();
        if (!vlanId.equals(VlanId.NONE)) {
            selector.matchVlanId(vlanId);
        }
        return selector;
    }

    // Check if the interface is an ingress interface with IPs configured
    private boolean validIngressIntf(Interface intf, Interface egressInterface) {
        if (!intf.equals(egressInterface) &&
                !intf.ipAddressesList().isEmpty() &&
                // TODO: An egress point might have two routers connected on different interfaces
                !intf.connectPoint().equals(egressInterface.connectPoint())) {
            return true;
        }
        return false;
    }

    private class InternalRouteListener implements RouteListener {
        @Override
        public void event(RouteEvent event) {
            switch (event.type()) {
            case ROUTE_ADDED:
            case ROUTE_UPDATED:
                update(event.subject());
                break;
            case ROUTE_REMOVED:
                withdraw(event.subject());
                break;
            default:
                break;
            }
        }
    }

    private class InternalInterfaceListener implements InterfaceListener {

        @Override
        public void event(InterfaceEvent event) {
            switch (event.type()) {
            case INTERFACE_ADDED:
                addInterface(event.subject());
                break;
            case INTERFACE_UPDATED:
                removeInterface(event.prevSubject());
                addInterface(event.subject());
                break;
            case INTERFACE_REMOVED:
                removeInterface(event.subject());
                break;
            default:
                break;
            }
        }
    }

    /**
     * Internal flow rule event listener.
     */
    private class InternalFlowRuleListener implements FlowRuleListener {
        @Override
        public void event(FlowRuleEvent event) {
            //TODO add syncronized?
            //TODO take a look at DistributedFlowStatisticStore class
            FlowRule rule = event.subject();
            switch (event.type()) {
                case RULE_ADDED:
                case RULE_UPDATED:
                    if (rule instanceof FlowEntry) {
                        Criterion IPSrcMatch = rule.selector().getCriterion(Criterion.Type.IPV4_SRC);
                        Criterion IPDstMatch = rule.selector().getCriterion(Criterion.Type.IPV4_DST);
                        // check if 'rule' matches IPv4 SRC and IPv4 DST
                        if (IPSrcMatch != null && IPDstMatch != null) {
                            //TODO variable names must be lower case!!! (everywhere!!!)
                            //TODO avoid underscore in variable names
                            IpPrefix IpPrefixSrc = ((IPCriterion) IPSrcMatch).ip();
                            IpPrefix IpPrefixDst = ((IPCriterion) IPDstMatch).ip();

                            Pair<IpPrefix, IpPrefix> demand = new Pair(IpPrefixSrc, IpPrefixDst);

                            //stats are updated only for demands already in TM
                            // (i.e. the ones added according to BGP announcements)
                            if (TM.containsKey(demand)) {
                                TM.get(demand).add(new Long[]{((FlowEntry) rule).bytes(), ((FlowEntry) rule).life()});
                                //printTM(demand, IpPrefixSrc, IpPrefixDst);
                            }

                            //TODO InternalFlowRuleListener is fired 5 times! Milliseconds are slighter different
                            // Up to now this problem is hanlded on the Python side. Check other apps which "implements FlowRuleListener"
                            /*
                            2017-08-15 17:17:56,791 | SdnIpFib | Update 192.168.1.0/24=192.168.3.0/24: 370048 @1502810276
                            2017-08-15 17:17:56,794 | SdnIpFib | Update 192.168.1.0/24=192.168.3.0/24: 370048 @1502810276
                            2017-08-15 17:17:56,799 | SdnIpFib | Update 192.168.1.0/24=192.168.3.0/24: 370048 @1502810276
                            2017-08-15 17:17:56,802 | SdnIpFib | Update 192.168.1.0/24=192.168.3.0/24: 370048 @1502810276
                            2017-08-15 17:17:56,873 | SdnIpFib | Update 192.168.1.0/24=192.168.3.0/24: 370048 @1502810276
                             */

                            //only flows from/to announced IP prefixes are tracked and added to TMSamples
                            if (prefixPairs.containsKey(IpPrefixSrc) && prefixPairs.containsKey(IpPrefixDst)) {
                                //NB FlowEntry's life is ignored since we are more interested in the timestamp to be able to align measurements!
                                TMSamples.add(new TMSample(System.currentTimeMillis()/1000, demand.toString(), ((FlowEntry) rule).bytes()));
                                //log.info("Update {}: {} @{}", demand.toString(), (((FlowEntry) rule).bytes()), System.currentTimeMillis()/1000);
                            }
                        }
                    }
                    break;
                //TODO should we handle other event types?
                default:
                    log.warn("Unknown flow rule event {}", event);
            }
        }
    }

    private void printTM(Pair<IpPrefix, IpPrefix> demand, IpPrefix IpPrefixSrc, IpPrefix IpPrefixDst){
            log.info("SRC : {} - DST: {} , {} ", IpPrefixSrc,IpPrefixDst,demand.hashCode());
            StringBuffer tmp = new StringBuffer();
            tmp.append(String.format("\nTM[%s]\n", demand));
            tmp.append("bytes\tlife\n");
            if (TM.containsKey(demand)) {
                TM.get(demand).forEach(TMsample -> {
                    tmp.append(String.format("%d\t%d\n", TMsample[0], TMsample[1]));
                });
            }
            log.info(tmp.toString());
    }

    public ArrayNode getTMs() {
        //TODO check synchronization with multiple threads etc...

        ArrayNode TMSamplesArray = mapper.createArrayNode();

        //TODO probably jackson can automagically create the JSON avoiding .toJSONnode()
        ListIterator<TMSample> iter = TMSamples.listIterator();
        while (iter.hasNext()){
            TMSamplesArray.add(iter.next().toJSONnode(mapper));
            //NB TM samples are consumed by the client (i.e. deleted in ONOS)
            iter.remove();
        }

        return TMSamplesArray;
    }

    public String setRouting(RoutingConfiguration r) {
        if (routingConfigurations.containsKey(r.r_ID)) {
            String msg = String.format("setRouting() failed: routing #%d has been already configured!", r.r_ID);
            log.info(msg);
            return msg;
        } else {
            routingConfigurations.put(r.r_ID, r);
            log.info("{}", r.toString());
            return "OK";
        }
    }

    public String applyRouting(int routingID) {
        StringBuilder resultString = new StringBuilder();
        if (routingConfigurations.containsKey(routingID)) {
            RoutingConfiguration r = routingConfigurations.get(routingID);
            for (Route route: r.r_config) {
                Key intentKey = Key.of(
                    route.demand.get(0).concat("-").concat(route.demand.get(1))
                    , appId);
                Intent modifiedIntent = null;
                if (routeIntentsSingle.containsKey(intentKey)) {
                    /* We cannot modify the attributes (e.g. links) of an Intent
                    because all of them are defined as final, so we are forced
                    to create a brand new Intent, modifiedIntent, copying the
                    attributes value and eventually forcing a Path. Eventually
                    because if path is the extreme 1-hop case we rely on
                    generateConnectivityIntent() to produce a PointToPointIntent
                    rather than LinkCollectionIntent. */
                    Intent intent = routeIntentsSingle.get(intentKey);
                    if (intent instanceof LinkCollectionIntent ||
                            intent instanceof PointToPointIntent) {
                        //TODO up to now we just support unsplittable routing
                        List<DeviceId> path = route.paths.get(0).path;

                        FilteredConnectPoint ingressCP;
                        FilteredConnectPoint egressCP;
                        List<Link> links;

                        if (intent instanceof LinkCollectionIntent) {
                            ingressCP = ((LinkCollectionIntent) intent).filteredIngressPoints().iterator().next();
                            egressCP = ((LinkCollectionIntent) intent).filteredEgressPoints().iterator().next();
                        } else {
                            ingressCP = ((PointToPointIntent) intent).filteredIngressPoint();
                            egressCP = ((PointToPointIntent) intent).filteredEgressPoint();
                        }

                        //handle the 1-hop case
                        if (path.size() == 1) {
                            links = null;
                        } else {
                            links = createPathFromDeviceList(path);
                        }

                        modifiedIntent = generateConnectivityIntent(
                                intent.appId(),
                                intent.key(),
                                ((ConnectivityIntent) intent).selector(),
                                ingressCP,
                                egressCP,
                                ((ConnectivityIntent) intent).treatment(),
                                intent.priority(),
                                ((ConnectivityIntent) intent).constraints(),
                                links);
                    } else {
                        //TODO should we create a brand new LinkCollectionIntent?
                        //All the attributes might be just copied...provided
                        //that intent is a ConnectivityIntent
                        resultString.append(String.format("unable to handle a %s intent for demand %s. ", intent.getClass().toString(), intentKey.toString()));
                    }
                } else {
                    /* TODO intentKey not found. Should we create a brand new LinkCollectionIntent?
                    It's like adding a brand new flow so we need to get
                    -ingressInterface/egressInterface from announcedPrefixesFromCP [*]
                    -egressMAC from MACFromCP
                    and call generateSrcDstIntent()

                    [*] provided that we know it! But if we know it, a PP/LCIntent
                    would already exists! */
                    resultString.append(String.format("no intent found for demand %s. ", intentKey.toString()));
                }

                if (modifiedIntent != null) {
                    routeIntentsSingle.put(intentKey, modifiedIntent);
                    intentSynchronizer.submit(modifiedIntent);
                }
            }
        } else {
            resultString.append(String.format("unknown routing #%d!", routingID));
        }

        if (resultString.length()==0) {
            resultString.append("OK");
        } else {
            resultString.insert(0, String.format("applyRouting(%d) failed: ", routingID));
        }

        log.info(resultString.toString());
        return resultString.toString();
    }

    public ArrayNode getAnnouncedPrefixesFromCP() {
        ArrayNode announcedPrefixes = mapper.createArrayNode();
        announcedPrefixesFromCP.forEach((CP, IpPrefixList) -> {
            ArrayNode IpPrefixArray = mapper.createArrayNode();
            IpPrefixList.forEach(IpPrefix -> {
                IpPrefixArray.add(IpPrefix.toString());
            });
            announcedPrefixes.add(mapper.createObjectNode()
              .put("CP", CP.toString())
              .putPOJO("IpPrefixList", IpPrefixArray));
        });
        return announcedPrefixes;
    }

}