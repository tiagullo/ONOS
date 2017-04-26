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
import com.google.common.collect.Sets;
import javafx.util.Pair;
import org.apache.commons.lang.ObjectUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.packet.Data;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IP;
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
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.FilteredConnectPoint;
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
import org.onosproject.net.statistic.StatisticStore;
import org.onosproject.routing.IntentSynchronizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.print.IPPPrintService;

import javax.crypto.Mac;
import java.lang.reflect.Array;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;

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

    private final InternalRouteListener routeListener = new InternalRouteListener();
    private final InternalInterfaceListener interfaceListener = new InternalInterfaceListener();
    private final InternalFlowRuleListener flowStatsListener = new InternalFlowRuleListener();

    private static final int PRIORITY_OFFSET = 100;
    private static final int PRIORITY_MULTIPLIER = 5;
    protected static final ImmutableList<Constraint> CONSTRAINTS
            = ImmutableList.of(new PartialFailureConstraint());

    private final Map<IpPrefix, MultiPointToSinglePointIntent> routeIntents
            = new ConcurrentHashMap<>();

    private final Map<Key, PointToPointIntent> routeIntentsSingle
            = new ConcurrentHashMap<>();

    private final Map<ConnectPoint,ArrayList<IpPrefix>> Announcements
            = new ConcurrentHashMap<>();

    private final Map<ConnectPoint,MacAddress> MacPrefix
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

            ArrayList<PointToPointIntent> ArrayIntent =
                    generateSrcDstRouteIntent(prefix, route.nextHop(), route.nextHopMac());

            for(int i=0; i<ArrayIntent.size();i++)
            {
                routeIntentsSingle.put(ArrayIntent.get(i).key(), ArrayIntent.get(i));
                intentSynchronizer.submit(ArrayIntent.get(i));
            }

        }
    }

    private void withdraw(ResolvedRoute route) {
        synchronized (this) {
            /*IpPrefix prefix = route.prefix();
            //TODO Mattia: handle removal of PointToPoint with routeIntentsSingle
            MultiPointToSinglePointIntent intent = routeIntents.remove(prefix);
            if (intent == null) {
                log.trace("SDN-IP no intent in routeIntents to delete " +
                        "for prefix: {}", prefix);
                return;
            }
            intentSynchronizer.withdraw(intent);*/

            IpPrefix prefix = route.prefix();

            ArrayList<IpPrefix> DST = SRCDST.get(prefix);
            for (int i=0;i<DST.size();i++){
                String KeyStringAB = prefix.toString().concat("-").concat(DST.get(i).toString());
                String KeyStringBA = DST.get(i).toString().concat("-").concat(prefix.toString());
                Key KeyRemovedAB = Key.of(KeyStringAB, appId);
                Key KeyRemovedBA = Key.of(KeyStringBA, appId);

                PointToPointIntent intentAB = routeIntentsSingle.remove(KeyRemovedAB);
                PointToPointIntent intentBA = routeIntentsSingle.remove(KeyRemovedBA);
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




    // Nuova funzine generazione Intent PointToPoint
    private ArrayList<PointToPointIntent> generateSrcDstRouteIntent(
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
        if(Announcements.containsKey(announceInterface.connectPoint()))
        {
            Announcements.get(announceInterface.connectPoint()).add(announcedPrefix);
        }
        else
        {
            ArrayList<IpPrefix> temp = new ArrayList<IpPrefix>();
            temp.add(announcedPrefix);
            Announcements.put(announceInterface.connectPoint(),temp);
        }

        MacPrefix.put(announceInterface.connectPoint(),nextHopMacAddress);

        ArrayList<PointToPointIntent> ArrayProva = new ArrayList<PointToPointIntent>();

        // TODO this should be only peering interfaces
        interfaceService.getInterfaces().forEach(ingressInterface -> {
            // Get ony ingress interfaces with IPs configured
            //dentro non viene visto SRC=DST
            if (validIngressIntf(ingressInterface, announceInterface)) {

                for (Map.Entry<ConnectPoint, ArrayList<IpPrefix>> entry : Announcements.entrySet())
                {
                    ConnectPoint ingressCP = entry.getKey();
                    ArrayList<IpPrefix> prefixSubnet = entry.getValue();

                    if(ingressCP.equals(announceInterface.connectPoint())==false && ingressInterface.connectPoint().equals(ingressCP))
                    {

                        for (int j=0;j<prefixSubnet.size();j++)
                        {

                            if(prefixSubnet.get(j).equals(announcedPrefix)==false) {

                                //Ingress
                                TrafficSelector.Builder selectorInAB =
                                        buildIngressTrafficSelector(ingressInterface, prefixSubnet.get(j));
                                FilteredConnectPoint ingressFilteredCPAB =
                                        new FilteredConnectPoint(ingressCP, selectorInAB.build());


                                //Egress
                                TrafficSelector.Builder selectorOutAB =
                                        buildEgressTrafficSelector(announceInterface, announcedPrefix);
                                FilteredConnectPoint egressFilteredCPAB =
                                        new FilteredConnectPoint(announceInterface.connectPoint(), selectorOutAB.build());


                                TrafficSelector.Builder selectorAB = DefaultTrafficSelector.builder();
                                selectorAB.matchIPSrc(prefixSubnet.get(j)).matchIPDst(announcedPrefix);

                                // Build treatment: rewrite the destination MAC address
                                TrafficTreatment.Builder treatmentAB = DefaultTrafficTreatment.builder()
                                        .setEthDst(nextHopMacAddress);

                                // Set priority
                                int priority =
                                        announcedPrefix.prefixLength() * PRIORITY_MULTIPLIER + PRIORITY_OFFSET;

                                // Set key
                                String key2 = prefixSubnet.get(j).toString().concat("-").concat(announcedPrefix.toString());
                                Key keyAB = Key.of(key2, appId);

                                // Intent A->B
                                PointToPointIntent SingleIntentAB = PointToPointIntent.builder()
                                        .appId(appId)
                                        .key(keyAB)
                                        .selector(selectorAB.build())
                                        .filteredIngressPoint(ingressFilteredCPAB)
                                        .filteredEgressPoint(egressFilteredCPAB)
                                        .treatment(treatmentAB.build())
                                        .priority(priority)
                                        .constraints(CONSTRAINTS)
                                        .build();

                                ArrayProva.add(SingleIntentAB);

                                //Ingress
                                TrafficSelector.Builder selectorInBA =
                                        buildIngressTrafficSelector(announceInterface, announcedPrefix);
                                FilteredConnectPoint ingressFilteredCPBA =
                                        new FilteredConnectPoint(announceInterface.connectPoint(), selectorInBA.build());

                                //Egress
                                TrafficSelector.Builder selectorOutBA =
                                        buildEgressTrafficSelector(ingressInterface, prefixSubnet.get(j));
                                FilteredConnectPoint egressFilteredCPBA =
                                        new FilteredConnectPoint(ingressCP, selectorOutBA.build());


                                TrafficSelector.Builder selectorBA = DefaultTrafficSelector.builder();
                                selectorBA.matchIPDst(prefixSubnet.get(j)).matchIPSrc(announcedPrefix);

                                // Build treatment: rewrite the destination MAC address


                                TrafficTreatment.Builder treatmentBA = DefaultTrafficTreatment.builder()
                                        .setEthDst(MacPrefix.get(ingressCP));

                                // Set key
                                String key3 = announcedPrefix.toString().concat("-").concat(prefixSubnet.get(j).toString());
                                Key keyBA = Key.of(key3, appId);

                                // Intent B->A
                                PointToPointIntent SingleIntentBA = PointToPointIntent.builder()
                                        .appId(appId)
                                        .key(keyBA)
                                        .selector(selectorBA.build())
                                        .filteredIngressPoint(ingressFilteredCPBA)
                                        .filteredEgressPoint(egressFilteredCPBA)
                                        .treatment(treatmentBA.build())
                                        .priority(priority)
                                        .constraints(CONSTRAINTS)
                                        .build();

                                ArrayProva.add(SingleIntentBA);

                                //Salvataggio in TM della chiave SRC-DST e DST-SRC appena creata
                                ArrayList<Long[]> Temp = new ArrayList<Long[]>();
                                //se uso lo stesso Arraylist, viene indicizzato in entrambe le chiavi e quindi qunado aggiungo
                                //ad una chiave anche l'opposto aumenta
                                ArrayList<Long[]> Temp2 = new ArrayList<Long[]>();


                                //SRC-DST
                                Pair <IpPrefix,IpPrefix> Key1 = new Pair(prefixSubnet.get(j), announcedPrefix);
                                TM.put(Key1, Temp);
                                //DST-SRC
                                Pair <IpPrefix,IpPrefix> Key2 = new Pair(announcedPrefix, prefixSubnet.get(j));
                                TM.put(Key2, Temp2);


                                //SRC-DST
                                if(SRCDST.containsKey(announcedPrefix))
                                {
                                    SRCDST.get(announcedPrefix).add(prefixSubnet.get(j));
                                }
                                else
                                {
                                    ArrayList<IpPrefix> temp = new ArrayList<IpPrefix>();
                                    temp.add(prefixSubnet.get(j));
                                    SRCDST.put(announcedPrefix,temp);
                                }

                                //DST-SRC
                                if(SRCDST.containsKey(prefixSubnet.get(j)))
                                {
                                    SRCDST.get(prefixSubnet.get(j)).add(announcedPrefix);
                                }
                                else
                                {
                                    ArrayList<IpPrefix> temp2 = new ArrayList<IpPrefix>();
                                    temp.add(announcedPrefix);
                                    SRCDST.put(prefixSubnet.get(j),temp2);
                                }
                            }
                        }
                    }
                }
            }
        });

        return ArrayProva;

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
                            IpPrefix SRC = IpPrefix.valueOf(RemovePrefixString(SRCRule));
                            IpPrefix DST = IpPrefix.valueOf(RemovePrefixString(DSTRule));

                            Pair<IpPrefix, IpPrefix> Chiave = new Pair(SRC, DST);

                            if (TM.containsKey(Chiave)) {
                                Long[] Data = new Long[2];
                                Data[0] = (((FlowEntry) rule).bytes());
                                Data[1] = (((FlowEntry) rule).life());
                                //aggiunge ad entrambe le chiavi
                                TM.get(Chiave).add(Data);
                                //log.info("SRC : {} - DST: {} , {} ", SRC,DST,Chiave.hashCode());


                                StringBuffer tmp = new StringBuffer();
                                tmp.append(String.format("\nTM[%s]\n", Chiave));
                                tmp.append("bytes\tlife\n");
                                for (int i = 0; i < TM.get(Chiave).size(); i++) {
                                    tmp.append(String.format("%s\t%s\n", TM.get(Chiave).get(i)[0].toString(),
                                                             TM.get(Chiave).get(i)[1].toString()));
                                }

                                log.info(tmp.toString());

                            }

                            /* for (Map.Entry<Pair<IpPrefix, IpPrefix>, ArrayList<Long[]>> Stats : TM.entrySet()) {
                                IpPrefix TMSRC = Stats.getKey().getKey();
                                IpPrefix TMDST = Stats.getKey().getValue();
                                if (SRC.equals(TMSRC.toString()) && DST.equals(TMDST.toString())) {
                                    Long[] Data = new Long[2];
                                    Data[0] = (((FlowEntry) rule).bytes());
                                    Data[1] = (((FlowEntry) rule).life());
                                    Stats.getValue().add(Data);
                                    StringBuffer tmp = new StringBuffer();
                                    tmp.append(String.format("\nTM[%s]\n", Stats.getKey()));
                                    tmp.append("bytes\tlife\n");
                                    for (int i =0; i< Stats.getValue().size(); i++) {
                                        tmp.append(String.format("%s\t%s\n", Stats.getValue().get(i)[0].toString(),Stats.getValue().get(i)[1].toString()));
                                    }
                                    log.info(tmp.toString());*/
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
    private String RemovePrefixString (Criterion Rule)
    {
        String Temp = Rule.toString();
        String Sub = Temp.substring(9);
        return Sub;
    }

    //Estrae PrefixLenght
    private int ExtractPrefix (Criterion Rule)
    {
        String Temp = RemovePrefixString(Rule);
        int index = Temp.indexOf("/");
        int PrefixLenght = Integer.parseInt(Temp.substring(index+1));
        return PrefixLenght;

    }

    //estrae IpAddress senza PrefixLength
    private String ExtractAddress (Criterion Rule)
    {
        String Temp = RemovePrefixString(Rule);
        int index = Temp.indexOf("/");
        String Address = Temp.substring(0,(index));
        return Address;
    }

}
