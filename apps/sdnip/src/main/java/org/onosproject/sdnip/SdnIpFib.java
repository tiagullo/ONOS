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
import org.onosproject.net.intent.*;
import org.onosproject.net.intent.constraint.PartialFailureConstraint;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.statistic.StatisticStore;
import org.onosproject.net.topology.TopologyStore;
import org.onosproject.routing.IntentSynchronizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected TopologyStore store;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LinkService linkService;

    private final InternalRouteListener routeListener = new InternalRouteListener();
    private final InternalInterfaceListener interfaceListener = new InternalInterfaceListener();
    private final InternalFlowRuleListener flowStatsListener = new InternalFlowRuleListener();

    private static final int PRIORITY_OFFSET = 100;
    private static final int PRIORITY_MULTIPLIER = 5;
    protected static final ImmutableList<Constraint> CONSTRAINTS
            = ImmutableList.of(new PartialFailureConstraint());

    public final static String Dev1 = "of:00000000000000a1";
    public final static String Dev2 = "of:00000000000000a2";
    public final static String Dev3 = "of:00000000000000a3";
    public final static String Dev4 = "of:00000000000000a4";
    public final static String Dev5 = "of:00000000000000a5";
    public final static String Dev6 = "of:00000000000000a6";


    private final Map<IpPrefix, MultiPointToSinglePointIntent> routeIntents
            = new ConcurrentHashMap<>();

    private final Map<Key, Intent> routeIntentsSingle
            = new ConcurrentHashMap<>();

    private final Map<ConnectPoint,List<IpPrefix>> announcedPrefixesFromCP
            = new ConcurrentHashMap<>();

    private final Map<ConnectPoint,MacAddress> MACFromCP
            = new ConcurrentHashMap<>();

    private final Map<Pair<IpPrefix,IpPrefix>, List<Long[]>> TM
            = new ConcurrentHashMap<>();

    private final List<TMSample> TMSamples = new LinkedList<>();

    //Auxiliary Map to store pairs of non-local prefixes
    private final Map<IpPrefix,List<IpPrefix>> prefixPairs
            = new ConcurrentHashMap<>();

    private ApplicationId appId;

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
            IpPrefix announcedPrefix = route.prefix();
            //TODO test
            log.info("Withdrawn BGP announcement for {}", announcedPrefix.toString());

            prefixPairs.get(announcedPrefix).forEach(dstPrefix -> {
                String keyStringAB = announcedPrefix.toString().concat("-").concat(dstPrefix.toString());
                String keyStringBA = dstPrefix.toString().concat("-").concat(announcedPrefix.toString());
                Key keyRemovedAB = Key.of(keyStringAB, appId);
                Key keyRemovedBA = Key.of(keyStringBA, appId);

                Intent intentAB = routeIntentsSingle.remove(keyRemovedAB);
                Intent intentBA = routeIntentsSingle.remove(keyRemovedBA);
                intentSynchronizer.withdraw(intentAB);
                intentSynchronizer.withdraw(intentBA);

                //Unlearn the flows (announcedPrefix , *)
                prefixPairs.get(dstPrefix).remove(announcedPrefix);
            });
            //Unlearn the announcedPrefix
            prefixPairs.remove(announcedPrefix);
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

    //TODO add doc
    private Intent generateSrcDstIntent(Interface ingressInterface, IpPrefix ingressPrefix, ConnectPoint ingressCP,
                                              Interface egressInterface, IpPrefix egressPrefix, ConnectPoint egressCP,
                                              MacAddress egressMAC, List<Link> links) {

        //Ingress
        TrafficSelector.Builder selectorIn =
                buildIngressTrafficSelector(ingressInterface, ingressPrefix);
        FilteredConnectPoint ingressFilteredCP =
                new FilteredConnectPoint(ingressCP, selectorIn.build());


        //Egress
        TrafficSelector.Builder selectorOut =
                buildEgressTrafficSelector(egressInterface, egressPrefix);
        FilteredConnectPoint egressFilteredCP =
                new FilteredConnectPoint(egressCP, selectorOut.build());


        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchIPSrc(ingressPrefix).matchIPDst(egressPrefix);

        // Build treatment: rewrite the destination MAC address
        TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder()
                .setEthDst(egressMAC);

        // Set priority
        int priority =
                egressPrefix.prefixLength() * PRIORITY_MULTIPLIER + PRIORITY_OFFSET;

        // Set key
        Key key = Key.of(ingressPrefix.toString().concat("-").concat(egressPrefix.toString()), appId);

        if (links == null) {
            return PointToPointIntent.builder()
                    .appId(appId)
                    .key(key)
                    .selector(selector.build())
                    .filteredIngressPoint(ingressFilteredCP)
                    .filteredEgressPoint(egressFilteredCP)
                    .treatment(treatment.build())
                    .priority(priority)
                    .constraints(CONSTRAINTS)
                    .build();
        } else {
            return LinkCollectionIntent.builder()
                    .appId(appId)
                    .key(key)
                    .selector(selector.build())
                    .filteredIngressPoints(ImmutableSet.of(ingressFilteredCP))
                    .filteredEgressPoints(ImmutableSet.of(egressFilteredCP))
                    .treatment(treatment.build())
                    .priority(priority)
                    .constraints(CONSTRAINTS)
                    .links(ImmutableSet.copyOf(links))
                    .applyTreatmentOnEgress(true)
                    .cost(1)
                    .build();
        }
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

        //Update the list of announcments received from the CP
        if (announcedPrefixesFromCP.containsKey(announceInterface.connectPoint()))
            announcedPrefixesFromCP.get(announceInterface.connectPoint()).add(announcedPrefix);
        else
            announcedPrefixesFromCP.put(announceInterface.connectPoint(), new ArrayList<IpPrefix>(Arrays.asList(announcedPrefix)));

        MACFromCP.put(announceInterface.connectPoint(), nextHopMacAddress);

        List<Intent> intentsArrayList = new ArrayList<Intent>();

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
                        List<Link> linksAB = null;
                        List<Link> linksBA = null;

                        //TODO remove this hardcoded test from Dev1 to Dev6
                        if (ingressInterface.connectPoint().deviceId().equals(DeviceId.deviceId(Dev1)) && announceInterface.connectPoint().deviceId().equals(DeviceId.deviceId(Dev5))) {

                            //Get the first shortes path using ONOS
                            //links = store.getPaths(store.currentTopology(), DeviceId.deviceId(Dev1), DeviceId.deviceId(Dev6)).iterator().next().links();

                            List<DeviceId> deviceList = new ArrayList<DeviceId>();
                            deviceList.add(DeviceId.deviceId(Dev1));
                            deviceList.add(DeviceId.deviceId(Dev2));
                            deviceList.add(DeviceId.deviceId(Dev4));
                            deviceList.add(DeviceId.deviceId(Dev3));
                            deviceList.add(DeviceId.deviceId(Dev5));
                            linksAB = createPathFromDeviceList(deviceList);

                            Collections.reverse(deviceList);
                            linksBA = createPathFromDeviceList(deviceList);
                        }

                        //A is the device I'm currently iterating over as source device
                        //B is the device I received the announce from (destination device)
                        Intent singleIntentAB = generateSrcDstIntent(ingressInterface, otherAnnouncedPrefix, ingressInterface.connectPoint(),
                                                               announceInterface, announcedPrefix, announceInterface.connectPoint(), nextHopMacAddress, linksAB);

                        intentsArrayList.add(singleIntentAB);

                        Intent singleIntentBA = generateSrcDstIntent(announceInterface, announcedPrefix, announceInterface.connectPoint(),
                                                               ingressInterface, otherAnnouncedPrefix, ingressInterface.connectPoint(), MACFromCP.get(ingressInterface.connectPoint()), linksBA);

                        intentsArrayList.add(singleIntentBA);

                        //Initialize the TM for demands otherAnnouncedPrefix -> announcedPrefix ...
                        TM.put(new Pair(otherAnnouncedPrefix, announcedPrefix), new ArrayList<Long[]>());
                        //... and announcedPrefix -> otherAnnouncedPrefix
                        TM.put(new Pair(announcedPrefix, otherAnnouncedPrefix), new ArrayList<Long[]>());


                        //Add 'otherAnnouncedPrefix' to the list of pairs starting from 'announcedPrefix'
                        if (prefixPairs.containsKey(announcedPrefix))
                            prefixPairs.get(announcedPrefix).add(otherAnnouncedPrefix);
                        else
                            prefixPairs.put(announcedPrefix, new ArrayList<IpPrefix>(Arrays.asList(otherAnnouncedPrefix)));

                        //Add 'announcedPrefix' to the list of pairs starting from 'otherAnnouncedPrefix'
                        if (prefixPairs.containsKey(otherAnnouncedPrefix))
                            prefixPairs.get(otherAnnouncedPrefix).add(announcedPrefix);
                        else
                            prefixPairs.put(otherAnnouncedPrefix, new ArrayList<IpPrefix>(Arrays.asList(announcedPrefix)));
                    });
                }
            }
        });

        return intentsArrayList;
    }

    private List<Link> createPathFromDeviceList(List<DeviceId> deviceList) {
        List<Link> path = new ArrayList<Link>();
        for (int i=0; i<deviceList.size()-1; i++)
        {
            DeviceId devEgress = deviceList.get(i);
            DeviceId devIngress = deviceList.get(i+1);
            // The common Link between DevEgress and DevIngress is the intersection of their links
            Set<Link> common_links = new HashSet<Link>(linkService.getDeviceEgressLinks(devEgress));
            common_links.retainAll(linkService.getDeviceIngressLinks(devIngress));
            if (common_links.size() == 0) {
                log.error("No link found between node %s and node %s!",
                         devEgress.toString(), devIngress.toString());
            }
            else if (common_links.size() == 1) {
                path.add(common_links.iterator().next());
            } else {
                log.warn("%d links found between node %s and node %s: taking the first one!",
                        common_links.size(), devEgress.toString(), devIngress.toString());
                path.add(common_links.iterator().next());
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
                            IpPrefix IpPrefixSrc = IPMatchToIpPrefix(IPSrcMatch);
                            IpPrefix IpPrefixDst = IPMatchToIpPrefix(IPDstMatch);

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
                                log.info("Update {}: {} @{}", demand.toString(), (((FlowEntry) rule).bytes()), System.currentTimeMillis()/1000);
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

    private String removePrefixString(Criterion IPMatch) {
        // removePrefixString(IPV4_SRC:10.0.4.101/32) returns "10.0.4.101/32"
        return IPMatch.toString().substring(9);
    }

    private IpPrefix IPMatchToIpPrefix(Criterion IPMatch) {
        return IpPrefix.valueOf(removePrefixString(IPMatch));
    }

    //TODO is this needed?
    //Extract PrefixLenght
    private int ExtractPrefix(Criterion Rule)
    {
        String temp = removePrefixString(Rule);
        int index = temp.indexOf("/");
        int prefixLenght = Integer.parseInt(temp.substring(index+1));
        return prefixLenght;

    }

    //TODO is this needed?
    //Extract IpAddress without PrefixLength
    private String extractAddress(Criterion rule)
    {
        String temp = removePrefixString(rule);
        int index = temp.indexOf("/");
        String address = temp.substring(0,(index));
        return address;
    }

    private void printTM(Pair<IpPrefix, IpPrefix> demand, IpPrefix IpPrefixSrc, IpPrefix IpPrefixDst){
            log.info("SRC : {} - DST: {} , {} ", IpPrefixSrc,IpPrefixDst,demand.hashCode());
            StringBuffer tmp = new StringBuffer();
            tmp.append(String.format("\nTM[%s]\n", demand));
            tmp.append("bytes\tlife\n");
            TM.get(demand).forEach(TMsample -> {
                tmp.append(String.format("%d\t%d\n", TMsample[0], TMsample[1]));
            });
            log.info(tmp.toString());
    }

    public ArrayNode getTMs() {
        //TODO check synchronization with multiple threads etc...

        ArrayNode TMs = new ObjectMapper().createArrayNode();

        ListIterator<TMSample> iter = TMSamples.listIterator();
        while (iter.hasNext()){
            TMs.add(iter.next().toJSONnode());
            //NB TM samples are consumed by the client (i.e. deleted in ONOS)
            iter.remove();
        }

        return TMs;
    }

    public String setRouting() {
        log.info("setRouting()");
        return "setRouting!!!";
    }

}