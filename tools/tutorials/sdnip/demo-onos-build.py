#!/usr/bin/python

from mininet.cli import CLI
from mininet.log import info, debug, setLogLevel
from mininet.net import Mininet
from mininet.node import Host, RemoteController
from mininet.topo import Topo
import os, subprocess, distutils.spawn, socket, json, glob, urllib2, base64, time

QUAGGA_DIR = '/usr/lib/quagga'
# Must exist and be owned by quagga user (quagga:quagga by default on Ubuntu)
QUAGGA_RUN_DIR = '/var/run/quagga'
CONFIG_DIR = 'configs'

def is_installed(name):
    return distutils.spawn.find_executable(name) is not None

if not is_installed('iperf3'):
    subprocess.call("sudo apt-get -q -y install iperf3".split())

if not is_installed('ITGRecv'):
    subprocess.call("sudo apt-get -q -y install d-itg".split())


def json_GET_req(url):
    try:
        request = urllib2.Request(url)
        base64string = base64.encodestring('%s:%s' % ('onos', 'rocks')).replace('\n', '')
        request.add_header("Authorization", "Basic %s" % base64string)
        response = urllib2.urlopen(request)
        return json.loads(response.read())
    except IOError as e:
        print e
        return ""

class SdnIpHost(Host):
    def __init__(self, name, ip, route, *args, **kwargs):
        Host.__init__(self, name, ip=ip, *args, **kwargs)

        self.route = route

    def config(self, **kwargs):
        Host.config(self, **kwargs)

        debug("configuring route %s" % self.route)

        self.cmd('ip route add default via %s' % self.route)

class Router(Host):
    def __init__(self, name, quaggaConfFile, zebraConfFile, intfDict, *args, **kwargs):
        Host.__init__(self, name, *args, **kwargs)

        self.quaggaConfFile = quaggaConfFile
        self.zebraConfFile = zebraConfFile
        self.intfDict = intfDict

    def config(self, **kwargs):
        Host.config(self, **kwargs)
        self.cmd('sysctl net.ipv4.ip_forward=1')

        for intf, attrs in self.intfDict.items():
            self.cmd('ip addr flush dev %s' % intf)
            if 'mac' in attrs:
                self.cmd('ip link set %s down' % intf)
                self.cmd('ip link set %s address %s' % (intf, attrs['mac']))
                self.cmd('ip link set %s up ' % intf)
            for addr in attrs['ipAddrs']:
                self.cmd('ip addr add %s dev %s' % (addr, intf))

        self.cmd('/usr/lib/quagga/zebra -d -f %s -z %s/zebra%s.api -i %s/zebra%s.pid' % (self.zebraConfFile, QUAGGA_RUN_DIR, self.name, QUAGGA_RUN_DIR, self.name))
        self.cmd('/usr/lib/quagga/bgpd -d -f %s -z %s/zebra%s.api -i %s/bgpd%s.pid' % (self.quaggaConfFile, QUAGGA_RUN_DIR, self.name, QUAGGA_RUN_DIR, self.name))


    def terminate(self):
        self.cmd("ps ax | egrep 'bgpd%s.pid|zebra%s.pid' | awk '{print $1}' | xargs kill" % (self.name, self.name))

        Host.terminate(self)


class SdnIpTopo( Topo ):
    "SDN-IP tutorial topology"

    def build( self ):
        s1 = self.addSwitch('s1', dpid='00000000000000a1')
        s2 = self.addSwitch('s2', dpid='00000000000000a2')
        s3 = self.addSwitch('s3', dpid='00000000000000a3')
        s4 = self.addSwitch('s4', dpid='00000000000000a4')
        s5 = self.addSwitch('s5', dpid='00000000000000a5')
        s6 = self.addSwitch('s6', dpid='00000000000000a6')

        zebraConf = '%s/zebra.conf' % CONFIG_DIR

        # Switches we want to attach our routers to, in the correct order
        attachmentSwitches = [s1, s2, s5, s6]

        for i in range(1, 5+1):
            name = 'r%s' % i
	    # Use 10.0.9.1 instead of 10.0.2.1 to avoid conflicts with VirtualBox iface
	    if (i==2):
		 eth0 = { 'mac' : '0a:00:00:00:0%s:01' % 9,
                     'ipAddrs' : ['10.0.%s.1/24' % 9] }
	    else:
		 eth0 = { 'mac' : '0a:00:00:00:0%s:01' % i,
                     'ipAddrs' : ['10.0.%s.1/24' % i] }

            eth1 = { 'ipAddrs' : ['192.168.%s.254/24' % i] }

	    # Add a third interface to router R1
	    if (i==1):
		eth2 = { 'ipAddrs' : ['192.168.%s0.254/24' % i] }
		intfs = { '%s-eth0' % name : eth0,
                          '%s-eth1' % name : eth1,
                          '%s-eth2' % name : eth2 }
	    else:
                intfs = { '%s-eth0' % name : eth0,
                          '%s-eth1' % name : eth1 }

            quaggaConf = '%s/quagga%s.conf' % (CONFIG_DIR, i)

            router = self.addHost(name, cls=Router, quaggaConfFile=quaggaConf,
                                  zebraConfFile=zebraConf, intfDict=intfs)

            host = self.addHost('h%s' % i, cls=SdnIpHost,
                                ip='192.168.%s.1/24' % i,
                                route='192.168.%s.254' % i)

	    # Add a second host to router R1
	    if (i==1):
		host2 = self.addHost('h%s0' % i, cls=SdnIpHost,
                        ip='192.168.%s0.1/24' % i,
                        route='192.168.%s0.254' % i)

	    # Attach router R5 to switch S1
            if (i==5):
		self.addLink(router, attachmentSwitches[0])
            else:
		self.addLink(router, attachmentSwitches[i-1])

            self.addLink(router, host)
            if (i==1):
		self.addLink(router, host2)

        # Set up the internal BGP speaker
        bgpEth0 = { 'mac':'00:00:00:00:00:01',
                    'ipAddrs' : ['10.0.1.101/24',
                                 '10.0.9.101/24',
                                 '10.0.3.101/24',
                                 '10.0.4.101/24',
                                 '10.0.5.101/24',] }
        bgpEth1 = { 'ipAddrs' : ['10.10.10.20/24'] }
        bgpIntfs = { 'bgp-eth0' : bgpEth0}#,
        #             'bgp-eth1' : bgpEth1 }

        bgp = self.addHost( "bgp", cls=Router,
                             quaggaConfFile = '%s/quagga-sdn.conf' % CONFIG_DIR,
                             zebraConfFile = zebraConf,
                             intfDict=bgpIntfs,
                             inNamespace=False )

        self.addLink( bgp, s3 )

        # Connect BGP speaker to the root namespace so it can peer with ONOS
        #root = self.addHost( 'root', inNamespace=False, ip='10.10.10.2/24' )
        #self.addLink( root, bgp )


        # Wire up the switches in the topology
        self.addLink( s1, s2 )
        self.addLink( s1, s3 )
        self.addLink( s2, s4 )
        self.addLink( s3, s4 )
        self.addLink( s3, s5 )
        self.addLink( s4, s6 )
        self.addLink( s5, s6 )

topos = { 'sdnip' : SdnIpTopo }

if __name__ == '__main__':
    IPERF_OUTPUT_TO_FILE = False # save stdout/stderr to hx-from-hy file
    RUN_INTO_XTERM = True
    XTERM_GEOMETRY = '-geometry 80x20+100+100'
    TRAFFIC_GEN_TOOL = 'IPERF3'
    assert TRAFFIC_GEN_TOOL in ['D-ITG', 'IPERF2', 'IPERF3']

    '''
    iperf3 has TCP bandwith configurable but does not allow concurrent clients (sometimes it hangs and results busy)
    iperf2 has only UDP bandwith configurable but does allow concurrent clients (even if we connect to it sequentially)
    D-ITG allows to configure duration, pkt/sec, byte/pkt and supports multiple clients in parallel
    ITGRecv
    ITGSend -T UDP -a 127.0.0.1 -C 77000 -c 2048 -t 10000 -l sender.log; ITGDec sender.log | grep bitrate

    ###################################################################################################################

    [Instructions]

    Set these parameters in ~/robust-routing/onos/config.py
        PORT_STATS_POLLING_INTERVAL = 5
        POLLING_INTERVAL = 5
        AGGREGATION_INTERVAL = 10
        Tstop = 10*60
        LINK_CAPACITY = 1e8
        startOnFirstNonZeroSample = True

        MIN_LEN = 10
        K = 3
        EXACTLY_K = True
        ITERATIONS = 5
        UNSPLITTABLE = True
        OLD_RR_POLICY = 2
        OBJ_FX = 'min_avg_MLU'
        INITIALIZATION = 'sequential'
        CIRCULAR_CLUSTERS = False
        AUTO_CACHING = False
        ir = IR_min_avg_MLU
        rr = RR_min_avg_MLU
        delta_def = 'MLU'
        sp = SP.min_sum_delta
        flp = FLP.min_sum_delta
        obj_fx_RTA = SP.sum_obj_fx
        obj_fx_RA = FLP.sum_obj_fx

    Set these parameters in ~/robust-routing/onos/TMtoIperf.py
        ABILENA_DATA_TO_SDNIP_TOPO = True

        MAX_NUM_OF_TM = 288
        day = 1
        max_TM_value = 1e8
        min_TM_value = 2e6

    Run ~/robust-routing/onos/TMtoIperf.py and copy 'TM_per_demand' in this file
    
    In 2 terminals run:
    sudo ip addr add 10.10.10.1/24 dev enp0s3
    cd ~/onos
    tools/build/onos-buck build onos --show-output; tools/build/onos-buck run onos-local -- clean debug

    cd ~/onos/tools/tutorials/sdnip
    sudo mn -c; sudo -E python demo-onos-build.py
    
    Run ~/robust-routing/onos/main.py
    '''

    #setLogLevel('debug')
    topo = SdnIpTopo()

    net = Mininet(topo=topo, controller=RemoteController)

    net.start()

    # check if ONOS_ROOT is available in the environment variables
    if 'ONOS_ROOT' not in os.environ:
        print 'You must run "sudo -E python %s" to preserve environment variables!' % __file__
        net.stop()
        exit()

    # configure ONOS applications
    os.system("$ONOS_ROOT/tools/test/bin/onos-netcfg localhost $ONOS_ROOT/tools/tutorials/sdnip/configs/network-cfg.json")

    # run multiple iperf3 server instances on each host, one for any other host on port is 5000 + host number
    hostList = filter(lambda host: 'h' in host.name, net.hosts)
    for dstHost in hostList:
        if TRAFFIC_GEN_TOOL == 'D-ITG':
            cmd = "ITGRecv &"
            if RUN_INTO_XTERM:
                cmd = 'xterm %s -xrm \'XTerm.vt100.allowTitleOps: false\' -T %s -e "%s; bash"&' % (XTERM_GEOMETRY, dstHost.params['ip'], cmd.replace('&',''))
            dstHost.cmd(cmd)
        else:
            for srcHost in filter(lambda host: host != dstHost, hostList):
                if IPERF_OUTPUT_TO_FILE:
                    if TRAFFIC_GEN_TOOL == 'IPERF3':
                        cmd = "iperf3 -s -p %d > %s-from-%s.log 2>&1 &" % (5000 + int(srcHost.name[1:]), dstHost.name, srcHost.name)
                    else:
                        cmd = "iperf -u -s -p %d > %s-from-%s.log 2>&1 &" % (5000 + int(srcHost.name[1:]), dstHost.name, srcHost.name)
                else:
                    if TRAFFIC_GEN_TOOL == 'IPERF3':
                        cmd = "iperf3 -s -p %d &" % (5000 + int(srcHost.name[1:]))
                    else:
                        cmd = "iperf -u -s -p %d &" % (5000 + int(srcHost.name[1:]))
                if RUN_INTO_XTERM:
                    cmd = 'xterm %s -xrm \'XTerm.vt100.allowTitleOps: false\' -T %s -e "%s; bash"&' % (XTERM_GEOMETRY, dstHost.params['ip'], cmd.replace('&',''))
                dstHost.cmd(cmd)

    # in Mbit/s
    TM_per_demand = {('192.168.4.1', '192.168.3.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.3.1', '192.168.5.1'): [2, 2, 2, 2, 2, 2, 4, 2, 2, 4, 2, 2, 4, 2, 2, 3, 2, 2, 4, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 5, 2, 3, 2, 4, 2, 3, 2, 5, 2, 2, 2, 2, 2, 2, 2, 2, 2, 4, 2, 2, 4, 2, 2, 4, 2, 2, 3, 2, 2, 4, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 5, 2, 3, 2, 4, 2, 3, 2, 5, 2, 2, 2], ('192.168.5.1', '192.168.3.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.4.1', '192.168.5.1'): [11, 4, 2, 5, 4, 8, 4, 5, 2, 2, 3, 2, 9, 3, 2, 2, 3, 4, 2, 2, 2, 3, 2, 2, 3, 3, 3, 3, 3, 2, 2, 2, 12, 2, 4, 3, 3, 9, 4, 2, 3, 9, 5, 2, 3, 2, 3, 2, 3, 4, 2, 2, 5, 2, 9, 2, 6, 2, 12, 2, 11, 4, 2, 5, 4, 8, 4, 5, 2, 2, 3, 2, 9, 3, 2, 2, 3, 4, 2, 2, 2, 3, 2, 2, 3, 3, 3, 3, 3, 2, 2, 2, 12, 2, 4, 3, 3, 9, 4, 2, 3, 9, 5, 2, 3, 2, 3, 2, 3, 4, 2, 2, 5, 2, 9, 2, 6, 2, 12, 2], ('192.168.2.1', '192.168.4.1'): [2, 3, 2, 3, 3, 2, 3, 2, 2, 3, 2, 2, 3, 2, 2, 2, 2, 2, 3, 2, 2, 3, 2, 3, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 4, 2, 4, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 3, 2, 3, 3, 2, 3, 2, 2, 3, 2, 2, 3, 2, 2, 2, 2, 2, 3, 2, 2, 3, 2, 3, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 4, 2, 4, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2], ('192.168.2.1', '192.168.3.1'): [2, 3, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2], ('192.168.5.1', '192.168.4.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.3.1', '192.168.4.1'): [6, 7, 2, 5, 8, 3, 5, 4, 3, 5, 3, 3, 5, 3, 4, 4, 3, 3, 5, 3, 4, 5, 3, 5, 4, 4, 8, 4, 4, 3, 4, 4, 2, 4, 3, 4, 4, 3, 4, 3, 4, 4, 4, 4, 5, 5, 3, 4, 4, 4, 3, 4, 5, 4, 4, 4, 3, 4, 3, 4, 6, 7, 2, 5, 8, 3, 5, 4, 3, 5, 3, 3, 5, 3, 4, 4, 3, 3, 5, 3, 4, 5, 3, 5, 4, 4, 8, 4, 4, 3, 4, 4, 2, 4, 3, 4, 4, 3, 4, 3, 4, 4, 4, 4, 5, 5, 3, 4, 4, 4, 3, 4, 5, 4, 4, 4, 3, 4, 3, 4], ('192.168.5.1', '192.168.2.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.4.1', '192.168.2.1'): [2, 5, 2, 2, 5, 2, 2, 4, 2, 2, 4, 3, 2, 2, 2, 2, 2, 4, 2, 3, 5, 2, 6, 2, 7, 2, 7, 2, 3, 2, 4, 2, 3, 2, 4, 2, 3, 2, 7, 2, 4, 2, 5, 2, 4, 5, 5, 2, 5, 2, 4, 3, 3, 3, 6, 3, 5, 3, 5, 2, 2, 5, 2, 2, 5, 2, 2, 4, 2, 2, 4, 3, 2, 2, 2, 2, 2, 4, 2, 3, 5, 2, 6, 2, 7, 2, 7, 2, 3, 2, 4, 2, 3, 2, 4, 2, 3, 2, 7, 2, 4, 2, 5, 2, 4, 5, 5, 2, 5, 2, 4, 3, 3, 3, 6, 3, 5, 3, 5, 2], ('192.168.2.1', '192.168.5.1'): [2, 4, 2, 2, 2, 2, 2, 4, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 3, 2, 7, 2, 2, 2, 3, 2, 3, 2, 2, 2, 8, 2, 4, 2, 2, 2, 5, 2, 4, 2, 2, 2, 3, 2, 4, 2, 3, 2, 4, 2, 2, 4, 2, 2, 2, 2, 2, 4, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 3, 2, 7, 2, 2, 2, 3, 2, 3, 2, 2, 2, 8, 2, 4, 2, 2, 2, 5, 2, 4, 2, 2, 2, 3, 2, 4, 2, 3, 2, 4, 2], ('192.168.3.1', '192.168.2.1'): [16, 16, 18, 17, 15, 20, 16, 17, 18, 18, 13, 18, 16, 14, 18, 17, 15, 20, 17, 15, 16, 17, 16, 17, 18, 17, 18, 17, 17, 14, 17, 15, 17, 16, 16, 18, 14, 18, 16, 17, 15, 18, 18, 18, 16, 19, 15, 18, 15, 18, 15, 17, 18, 19, 17, 18, 17, 17, 16, 18, 16, 16, 18, 17, 15, 20, 16, 17, 18, 18, 13, 18, 16, 14, 18, 17, 15, 20, 17, 15, 16, 17, 16, 17, 18, 17, 18, 17, 17, 14, 17, 15, 17, 16, 16, 18, 14, 18, 16, 17, 15, 18, 18, 18, 16, 19, 15, 18, 15, 18, 15, 17, 18, 19, 17, 18, 17, 17, 16, 18]}
    AGGREGATION_INTERVAL = 10

    def getHostFromIP(ip):
        return filter(lambda host: ip in host.params['ip'], net.hosts)[0]

    # create the list of iperf3 commands to be executed by each host
    commands = {}
    for demand in TM_per_demand:
        cmd = '('
        srcHost = getHostFromIP(demand[0] + '/24')
        port = 5000 + int(srcHost.name[1:])
        for bw_index, bw in enumerate(TM_per_demand[demand]):
            if TRAFFIC_GEN_TOOL == 'IPERF3':
                cmd += ('iperf3 -c %s -b %dM -p %d -t %d -V; ' % (demand[1] , bw, port, AGGREGATION_INTERVAL if bw_index != len(TM_per_demand[demand])-1 else 3*AGGREGATION_INTERVAL))
            elif TRAFFIC_GEN_TOOL == 'IPERF2':
                cmd += ('iperf -u -c %s -b %dM -p %d -t %d -V; ' % (demand[1] , bw, port, AGGREGATION_INTERVAL if bw_index != len(TM_per_demand[demand])-1 else 3*AGGREGATION_INTERVAL))
            else:
                bw = bw*1e6
                cmd += ('ITGSend -T UDP -a %s -C %d -c 512 -t %d; ' % (demand[1], int(bw/8/512)+1, AGGREGATION_INTERVAL if bw_index != len(TM_per_demand[demand])-1 else 3*AGGREGATION_INTERVAL))
        cmd += ') &'
        if demand[0] + '/24' not in commands:
            commands[demand[0] + '/24'] = []
        commands[demand[0] + '/24'].append(cmd)

    # Parse SDN-IP configuration files to estimate the number of expected intents (read via ONOS REST API),
    # so that it can automatically wait for the BGP prefixes propagation before starting the traffic!
    SDNIP_CONF_DIR = '%s/tools/tutorials/sdnip/configs/' % os.popen("echo $ONOS_ROOT").read().strip()
    # Parse the number of peering interfaces from network-cfg.json
    with open('%snetwork-cfg.json' % SDNIP_CONF_DIR) as data_file:
        data = json.load(data_file)
    peering_if = len(data['apps']['org.onosproject.router']['bgp']['bgpSpeakers'][0]['peers'])
    # Parse the number of prefixes to be announced from quagga files
    prefixes_per_peer = []
    for f in glob.glob('%squagga*.conf' % SDNIP_CONF_DIR):
        prefixes = int(os.popen("cat %s | grep network | wc -l" % f).read()) - int(os.popen("cat %s | grep \"\!network\" | wc -l" % f).read())
        if prefixes > 0:
            prefixes_per_peer.append(prefixes)
    flows = 0
    for idx1 in range(len(prefixes_per_peer)):
        for idx2 in filter(lambda x: x != idx1, range(len(prefixes_per_peer))):
            for _ in range(prefixes_per_peer[idx1]):
                for _ in range(prefixes_per_peer[idx2]):
                 flows += 1
    intents = 0
    while intents < peering_if*2*3 + flows:
        intents = len(json_GET_req('http://localhost:8181/onos/v1/intents')['intents'])
        print 'Waiting for BGP announcements (%d more intents expected)...' % ((peering_if*2*3 + flows) - intents)
        time.sleep(1)
    # Wait for a magic packet (any UDP pkt rx on port 12345) to generate the traffic
    print 'Ready! Send the magic UDP packet on port 12345 to generate traffic from TMs'
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(('',12345))
    s.recvfrom(1024)
    s.close()

    # execute, for each host, its sequence of commands, sequentially but in background so that each sequence of each host is
    # started in parallel
    for hostIP in commands:
        for command_list in commands[hostIP]:
            if RUN_INTO_XTERM:
                command_list = 'xterm %s -xrm \'XTerm.vt100.allowTitleOps: false\' -T %s -e "%s; bash"&' % (XTERM_GEOMETRY, hostIP, command_list.replace('&',''))
            getHostFromIP(hostIP).cmd(command_list)

    CLI(net)

    net.stop()

    if IPERF_OUTPUT_TO_FILE:
        for dstHost in hostList:
            for srcHost in filter(lambda host: host != dstHost, hostList):
                os.system('echo; echo iperf %s-from-%s.log; cat %s-from-%s.log' % (dstHost.name, srcHost.name, dstHost.name, srcHost.name))
        os.system('rm *-from-*.log')
    if RUN_INTO_XTERM:
        os.system('kill -9 `pidof xterm`')
