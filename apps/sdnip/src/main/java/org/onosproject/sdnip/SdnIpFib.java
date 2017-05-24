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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import javafx.util.Pair;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
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
public class SdnIpFib {
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

    //of:00000000000000a4

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

    private final Map<ConnectPoint,ArrayList<IpPrefix>> announcements
            = new ConcurrentHashMap<>();

    private final Map<ConnectPoint,MacAddress> macPrefix
            = new ConcurrentHashMap<>();

    private final Map<Pair<IpPrefix,IpPrefix>, ArrayList<Long[]>> TM
            = new ConcurrentHashMap<>();

    //Mappa di supporto per riferimento Route SRC-DST
    private final Map<IpPrefix,ArrayList<IpPrefix>> SRCDST
            = new ConcurrentHashMap<>();

    private ApplicationId appId;

    @Activate
    public void activate() {
        appId = coreService.getAppId(SdnIp.SDN_IP_APP);
        log.info("attivazione SDNIP");
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
            IpPrefix prefix = route.prefix();
            log.info("Arrivato update per {}", prefix.toString());


            //MultiPointToSinglePointIntent intent =
            //        generateRouteIntent(prefix, route.nextHop(), route.nextHopMac());

            ArrayList<Intent> arrayIntent =
                    generateSrcDstRouteIntent(prefix, route.nextHop(), route.nextHopMac());

            for(int i=0; i<arrayIntent.size();i++)
            {
                routeIntentsSingle.put(arrayIntent.get(i).key(), arrayIntent.get(i));
                intentSynchronizer.submit(arrayIntent.get(i));
            }

        }
    }

    private void withdraw(ResolvedRoute route) {
        synchronized (this) {

            IpPrefix prefix = route.prefix();

            ArrayList<IpPrefix> DST = SRCDST.get(prefix);
            for (int i=0;i<DST.size();i++){
                String keyStringAB = prefix.toString().concat("-").concat(DST.get(i).toString());
                String keyStringBA = DST.get(i).toString().concat("-").concat(prefix.toString());
                Key keyRemovedAB = Key.of(keyStringAB, appId);
                Key keyRemovedBA = Key.of(keyStringBA, appId);

                Intent intentAB = routeIntentsSingle.remove(keyRemovedAB);
                Intent intentBA = routeIntentsSingle.remove(keyRemovedBA);
                intentSynchronizer.withdraw(intentAB);
                intentSynchronizer.withdraw(intentBA);

                //Elimino da SRCDST la combinazione DST-SRC
                SRCDST.get(DST.get(i)).remove(prefix);

            }
            //Elimino entry che ha SRC con prefix della route
            SRCDST.remove(prefix);
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
            // Get ony ingress interfaces with IPs configured
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

    private Intent generateIntent(Interface ingressInterface, IpPrefix ingressPrefix, ConnectPoint ingressCP,
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


    // Nuova funzine generazione Intents
    private ArrayList<Intent> generateSrcDstRouteIntent(
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

        //non uso lo stesso temp di connectpoint perchè sul link potrei avere più
        //di un router quindi evito eventuallli mismatch
        if(announcements.containsKey(announceInterface.connectPoint()))
        {
            announcements.get(announceInterface.connectPoint()).add(announcedPrefix);
        }
        else
        {
            ArrayList<IpPrefix> temp = new ArrayList<IpPrefix>();
            temp.add(announcedPrefix);
            announcements.put(announceInterface.connectPoint(),temp);
        }

        macPrefix.put(announceInterface.connectPoint(),nextHopMacAddress);

        ArrayList<Intent> intentsArrayList = new ArrayList<Intent>();

        // TODO this should be only peering interfaces
        interfaceService.getInterfaces().forEach(ingressInterface -> {
            // Get ony ingress interfaces with IPs configured
            //dentro non viene visto SRC=DST
            if (validIngressIntf(ingressInterface, announceInterface)) {

                for (Map.Entry<ConnectPoint, ArrayList<IpPrefix>> entry : announcements.entrySet())
                {
                    ConnectPoint ingressCP = entry.getKey();
                    ArrayList<IpPrefix> prefixSubnet = entry.getValue();

                    if(ingressCP.equals(announceInterface.connectPoint())==false && ingressInterface.connectPoint().equals(ingressCP))
                    {

                        for (int j=0;j<prefixSubnet.size();j++)
                        {

                            if(prefixSubnet.get(j).equals(announcedPrefix)==false) {
                                List<Link> linksAB = null;
                                List<Link> linksBA = null;

                                if (ingressCP.deviceId().equals(DeviceId.deviceId(Dev1)) && announceInterface.connectPoint().deviceId().equals(DeviceId.deviceId(Dev5))) {

                                    //Get path using ONOS
                                    //links = store.getPaths(store.currentTopology(), DeviceId.deviceId(Dev1), DeviceId.deviceId(Dev6)).iterator().next().links();
                                    //TODO data una sequenza di DeviceId, tornare la lista di Links facendo intersezione getDeviceEgressLinks e getDeviceIngressLinks.
                                    //fare una funzione che ha come parametro arraylist<DeviId> e ritorna una List<Link>
                                    ArrayList<DeviceId> listDevice = new ArrayList<DeviceId>();
                                    listDevice.add(DeviceId.deviceId(Dev1));
                                    listDevice.add(DeviceId.deviceId(Dev2));
                                    listDevice.add(DeviceId.deviceId(Dev4));
                                    listDevice.add(DeviceId.deviceId(Dev3));
                                    listDevice.add(DeviceId.deviceId(Dev5));
                                    linksAB = CreateManualPath(listDevice);
                                    //modifico l'ordine dei DeviceID per creare anche il path inverso usando gli stessi link
                                    Collections.reverse(listDevice);
                                    linksBA = CreateManualPath(listDevice);
                                }

                                //B is the device I received the announce from
                                Intent singleIntentAB = generateIntent(ingressInterface, prefixSubnet.get(j), ingressCP,
                                        announceInterface, announcedPrefix, announceInterface.connectPoint(), nextHopMacAddress, linksAB);

                                intentsArrayList.add(singleIntentAB);

                                Intent singleIntentBA = generateIntent(announceInterface, announcedPrefix, announceInterface.connectPoint(),
                                        ingressInterface, prefixSubnet.get(j), ingressCP, macPrefix.get(ingressCP), linksBA);

                                intentsArrayList.add(singleIntentBA);

                                //Salvataggio in TM della chiave SRC-DST e DST-SRC appena creata
                                ArrayList<Long[]> temp = new ArrayList<Long[]>();
                                //se uso lo stesso Arraylist, viene indicizzato in entrambe le chiavi e quindi qunado aggiungo
                                //ad una chiave anche l'opposto aumenta
                                ArrayList<Long[]> temp2 = new ArrayList<Long[]>();


                                //SRC-DST
                                Pair <IpPrefix,IpPrefix> key1 = new Pair(prefixSubnet.get(j), announcedPrefix);
                                TM.put(key1, temp);
                                //DST-SRC
                                Pair <IpPrefix,IpPrefix> key2 = new Pair(announcedPrefix, prefixSubnet.get(j));
                                TM.put(key2, temp2);


                                //SRC-DST
                                if(SRCDST.containsKey(announcedPrefix))
                                {
                                    SRCDST.get(announcedPrefix).add(prefixSubnet.get(j));
                                }
                                else
                                {
                                    ArrayList<IpPrefix> tempIP = new ArrayList<IpPrefix>();
                                    tempIP.add(prefixSubnet.get(j));
                                    SRCDST.put(announcedPrefix,tempIP);
                                }

                                //DST-SRC
                                if(SRCDST.containsKey(prefixSubnet.get(j)))
                                {
                                    SRCDST.get(prefixSubnet.get(j)).add(announcedPrefix);
                                }
                                else
                                {
                                    ArrayList<IpPrefix> temp2IP = new ArrayList<IpPrefix>();
                                    temp2IP.add(announcedPrefix);
                                    SRCDST.put(prefixSubnet.get(j),temp2IP);
                                }
                            }
                        }
                    }
                }
            }
        });

        return intentsArrayList;

    }

    private List<Link> CreateManualPath (ArrayList<DeviceId> listDevice)
    {

        List<Link> path = new ArrayList<Link>();
        for(int i=0;i<listDevice.size()-1;i++)
        {
            DeviceId devEgress = listDevice.get(i);
            DeviceId devIngress = listDevice.get(i+1);
            // The common Link between DevEgress and DevIngress is the intersection of their links
            Set<Link> common_links = new HashSet<Link>(linkService.getDeviceEgressLinks(devEgress));
            common_links.retainAll(linkService.getDeviceIngressLinks(devIngress));
            //if size 0, error
            //if size 1, path.add()
            //if size 2, path.add() ma poi informare che ne ha trovati più di 1 e che ha preso il primo
            if (common_links.size()==1) {
                path.add(common_links.iterator().next());
            } else {
                log.warn("%d links found between node %s and node %s!",
                        common_links.size(), devEgress.toString(), devIngress.toString());
            }
        }
        return path;
    }


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
            FlowRule rule = event.subject();
            switch (event.type()) {
                case RULE_ADDED:
                case RULE_UPDATED:
                    //istanceof: indica se l'oggetto è istanza di un determinato tipo.
                    if (rule instanceof FlowEntry) {
                        Criterion SRCRule = rule.selector().getCriterion(Criterion.Type.IPV4_SRC);
                        Criterion DSTRule = rule.selector().getCriterion(Criterion.Type.IPV4_DST);
                        if (SRCRule != null && DSTRule != null) {

                            //Tramuto Criterion in IPPrefix
                            IpPrefix SRC = IpPrefix.valueOf(removePrefixString(SRCRule));
                            IpPrefix DST = IpPrefix.valueOf(removePrefixString(DSTRule));

                            Pair<IpPrefix, IpPrefix> chiave = new Pair(SRC, DST);

                            if (TM.containsKey(chiave)) {
                                Long[] data = new Long[2];
                                data[0] = (((FlowEntry) rule).bytes());
                                data[1] = (((FlowEntry) rule).life());
                                //aggiunge ad entrambe le chiavi
                                TM.get(chiave).add(data);
                            }
                        }
                    }
                    break;

                    /* TODO capire come gestire eventualmente gli altri tipi di
                     eventi che troviamo anche in Statistic Manager che generano
                     casi di Warning */
                default:
                    //log.warn("Unknown flow rule event {}", event);

            }
        }
    }


    //Rimuove la parte di stringa iniziale di Criterion. Returna Ip completo di
    //Address e PrefixLenght.
    private String removePrefixString (Criterion rule)
    {
        String temp = rule.toString();
        String sub = temp.substring(9);
        return sub;
    }

    //Estrae PrefixLenght
    private int ExtractPrefix (Criterion Rule)
    {
        String temp = removePrefixString(Rule);
        int index = temp.indexOf("/");
        int prefixLenght = Integer.parseInt(temp.substring(index+1));
        return prefixLenght;

    }

    //estrae IpAddress senza PrefixLength
    private String extractAddress(Criterion rule)
    {
        String temp = removePrefixString(rule);
        int index = temp.indexOf("/");
        String address = temp.substring(0,(index));
        return address;
    }

    private void stampaTM (Pair<IpPrefix, IpPrefix> chiave, IpPrefix SRC, IpPrefix DST){
            log.info("SRC : {} - DST: {} , {} ", SRC,DST,chiave.hashCode());
            StringBuffer tmp = new StringBuffer();
            tmp.append(String.format("\nTM[%s]\n", chiave));
            tmp.append("bytes\tlife\n");
            for (int i = 0; i < TM.get(chiave).size(); i++) {
                tmp.append(String.format("%s\t%s\n", TM.get(chiave).get(i)[0].toString(),
                                         TM.get(chiave).get(i)[1].toString()));
            }

            log.info(tmp.toString());
    }

}