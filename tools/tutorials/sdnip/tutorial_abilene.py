#!/usr/bin/python

from mininet.cli import CLI
from mininet.log import info, debug, setLogLevel
from mininet.net import Mininet
from mininet.node import Host, RemoteController
from mininet.topo import Topo
import os, subprocess, distutils.spawn, socket, json, glob, urllib2, base64, time
import create_abilene_conf as abilene

QUAGGA_DIR = '/usr/lib/quagga'
# Must exist and be owned by quagga user (quagga:quagga by default on Ubuntu)
QUAGGA_RUN_DIR = '/var/run/quagga'
CONFIG_DIR = 'configs-abilene'

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
    def __init__(self, name, quaggaConfFile, zebraConfFile, intfDict, route, *args, **kwargs):
        Host.__init__(self, name, *args, **kwargs)

        self.quaggaConfFile = quaggaConfFile
        self.zebraConfFile = zebraConfFile
        self.intfDict = intfDict
        self.route = route

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

	if self.route is not None:
            self.cmd('ip route add default via %s' % self.route)

        self.cmd('/usr/lib/quagga/zebra -d -f %s -z %s/zebra%s.api -i %s/zebra%s.pid' % (self.zebraConfFile, QUAGGA_RUN_DIR, self.name, QUAGGA_RUN_DIR, self.name))
        self.cmd('/usr/lib/quagga/bgpd -d -f %s -z %s/zebra%s.api -i %s/bgpd%s.pid' % (self.quaggaConfFile, QUAGGA_RUN_DIR, self.name, QUAGGA_RUN_DIR, self.name))


    def terminate(self):
        self.cmd("ps ax | egrep 'bgpd%s.pid|zebra%s.pid' | awk '{print $1}' | xargs kill" % (self.name, self.name))

        Host.terminate(self)


class SdnIpTopo( Topo ):
    "SDN-IP tutorial topology"

    def build( self ):
        switches = {}
        for x, node in enumerate(abilene.nodes):
            switches['s%d' % (x+1)] = self.addSwitch('s%d' % (x+1), dpid='00000000000000%0.2x' % (0xa0+x+1))

        zebraConf = '%s/zebra.conf' % CONFIG_DIR

        # Switches we want to attach our routers to, in the correct order
        attachmentSwitches = sorted(switches.keys())

        for i in range(1, len(attachmentSwitches)+1):
            name = 'r%s' % i
            # Using 10.0.2.1 creates conflicts with VirtualBox iface
            if (i==2):
                eth0 = { 'mac' : '0a:00:00:00:%0.2X:01' % (len(attachmentSwitches) + 1),
                        'ipAddrs' : ['10.0.%s.1/24' % (len(attachmentSwitches) + 1)] }
            else:
                eth0 = { 'mac' : '0a:00:00:00:%0.2X:01' % i,
                         'ipAddrs' : ['10.0.%s.1/24' % i] }
            eth1 = { 'ipAddrs' : ['192.168.%s.254/24' % i] }

            intfs = { '%s-eth0' % name : eth0,
                      '%s-eth1' % name : eth1 }

            quaggaConf = '%s/quagga%s.conf' % (CONFIG_DIR, i)

            router = self.addHost(name, cls=Router, quaggaConfFile=quaggaConf,
                                  zebraConfFile=zebraConf, intfDict=intfs,
                                  route='10.0.%d.101' % (i if i!=2 else len(attachmentSwitches) + 1))

            host = self.addHost('h%s' % i, cls=SdnIpHost,
                                ip='192.168.%s.1/24' % i,
                                route='192.168.%s.254' % i)
            self.addLink(router, switches['s'+name[1:]])

            self.addLink(router, host)

        # Set up the internal BGP speaker
        bgpEth0 = { 'mac':'00:00:00:00:00:01',
                    'ipAddrs' : ['10.0.%d.101/24' % ((x+1) if x+1 != 2 else len(attachmentSwitches) + 1) for x in range(len(attachmentSwitches)) ] }
        bgpEth1 = { 'ipAddrs' : ['10.10.10.20/24'] }
        bgpIntfs = { 'bgp-eth0' : bgpEth0}#,
        #             'bgp-eth1' : bgpEth1 }

        bgp = self.addHost( "bgp", cls=Router,
                             quaggaConfFile = '%s/quagga-sdn.conf' % CONFIG_DIR,
                             zebraConfFile = zebraConf,
                             intfDict=bgpIntfs,
                             inNamespace=False,
                             route=None)

        self.addLink( bgp, switches['s%d' % abilene.internal_bgp_speaker] )

        mapping = {node: 's%d' % (x+1) for x, node in enumerate(sorted(abilene.nodes))}
        print mapping

        # Wire up the switches in the topology
        capacity_dict_single_link = set([tuple(sorted(link)) for link in abilene.capacity_dict])
        for link in capacity_dict_single_link:
            self.addLink( mapping[link[0]], mapping[link[1]] )

        # Connect BGP speaker to the root namespace so it can peer with ONOS
        #root = self.addHost( 'root', inNamespace=False, ip='10.10.10.2/24' )
        #self.addLink( root, bgp )




topos = { 'sdnip' : SdnIpTopo }

if __name__ == '__main__':
    IPERF_OUTPUT_TO_FILE = False # save stdout/stderr to hx-from-hy file
    DEMO_ONOS_BUILD = True
    RUN_INTO_XTERM = True
    XTERM_GEOMETRY = '-geometry 80x20+100+100'
    TRAFFIC_GEN_TOOL = 'IPERF3'
    V2 = True
    assert TRAFFIC_GEN_TOOL in ['D-ITG', 'IPERF2', 'IPERF3']

    # iperf3 has TCP bandwith configurable but does not allow concurrent clients (sometimes it hangs and results busy)
    # iperf2 has only UDP bandwith configurable but does allow concurrent clients (even if we connect to it sequentially)
    # D-ITG allows to configure duration, pkt/sec, byte/pkt and supports multiple clients in parallel
    # ITGRecv
    # ITGSend -T UDP -a 127.0.0.1 -C 77000 -c 2048 -t 10000 -l sender.log; ITGDec sender.log | grep bitrate

    #setLogLevel('debug')
    topo = SdnIpTopo()

    net = Mininet(topo=topo, controller=RemoteController)

    net.start()

    if DEMO_ONOS_BUILD:
        # check if ONOS_ROOT is available in the environment variables
        if 'ONOS_ROOT' not in os.environ:
            print 'You must run "sudo -E python %s" to preserve environment variables!' % __file__
            net.stop()
            exit()

        # configure ONOS applications
        os.system("$ONOS_ROOT/tools/test/bin/onos-netcfg localhost $ONOS_ROOT/tools/tutorials/sdnip/%s/network-cfg.json" % CONFIG_DIR)


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
        TM_per_demand = {('192.168.11.1', '192.168.2.1'): [13, 26, 31, 24, 31, 22, 25, 14, 24, 26, 31, 26, 22, 29, 29, 24, 20, 21, 20, 25, 22, 19, 20, 23, 22, 23, 23, 30, 30, 23, 21, 28, 29, 19, 24, 18, 16, 29, 25, 22], ('192.168.8.1', '192.168.5.1'): [5, 4, 5, 8, 3, 2, 2, 3, 4, 4, 4, 9, 9, 5, 6, 3, 2, 3, 5, 3, 5, 4, 6, 2, 4, 2, 2, 6, 4, 7, 7, 5, 5, 5, 3, 3, 4, 5, 4, 5], ('192.168.8.1', '192.168.9.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.9.1', '192.168.6.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.8.1', '192.168.6.1'): [2, 3, 2, 3, 2, 2, 2, 2, 2, 2, 4, 3, 3, 2, 2, 2, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.11.1', '192.168.9.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.6.1', '192.168.9.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.9.1', '192.168.2.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.8.1', '192.168.2.1'): [15, 10, 11, 11, 12, 14, 14, 16, 17, 15, 9, 11, 13, 14, 13, 13, 13, 11, 12, 10, 8, 12, 12, 16, 13, 10, 18, 16, 19, 17, 13, 11, 14, 12, 13, 16, 9, 15, 20, 15], ('192.168.9.1', '192.168.11.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.6.1', '192.168.11.1'): [2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 2, 4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 4, 3, 2, 2, 2, 2, 2, 2], ('192.168.5.1', '192.168.11.1'): [12, 8, 3, 8, 3, 2, 2, 3, 3, 7, 6, 2, 8, 3, 5, 5, 2, 2, 5, 3, 5, 7, 4, 3, 3, 2, 2, 2, 2, 4, 12, 10, 6, 4, 2, 5, 2, 5, 2, 9], ('192.168.6.1', '192.168.2.1'): [3, 4, 2, 5, 4, 4, 2, 2, 3, 2, 5, 3, 3, 3, 4, 3, 2, 2, 3, 5, 3, 2, 3, 4, 2, 3, 2, 2, 3, 3, 4, 4, 5, 2, 2, 4, 2, 2, 2, 5], ('192.168.9.1', '192.168.8.1'): [2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.9.1', '192.168.5.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2], ('192.168.8.1', '192.168.3.1'): [6, 6, 6, 5, 6, 7, 5, 6, 7, 6, 6, 8, 6, 6, 6, 5, 5, 7, 6, 7, 9, 7, 6, 6, 6, 5, 6, 6, 7, 6, 7, 7, 8, 6, 6, 5, 7, 7, 6, 7], ('192.168.2.1', '192.168.6.1'): [2, 2, 6, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 5, 2, 2, 2], ('192.168.11.1', '192.168.8.1'): [69, 29, 66, 35, 59, 40, 70, 54, 79, 56, 41, 37, 48, 42, 68, 37, 49, 99, 87, 41, 24, 36, 58, 42, 74, 57, 32, 25, 29, 32, 43, 33, 46, 51, 46, 32, 55, 49, 27, 42], ('192.168.11.1', '192.168.5.1'): [11, 16, 24, 18, 13, 9, 12, 10, 9, 17, 15, 11, 17, 27, 17, 20, 11, 18, 9, 15, 17, 20, 20, 12, 17, 11, 9, 5, 18, 22, 15, 15, 13, 24, 9, 16, 9, 12, 22, 19], ('192.168.2.1', '192.168.5.1'): [2, 3, 6, 3, 7, 3, 7, 7, 3, 2, 2, 3, 3, 2, 4, 2, 3, 2, 3, 3, 2, 2, 2, 3, 2, 7, 5, 6, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 8], ('192.168.2.1', '192.168.11.1'): [7, 5, 5, 7, 2, 2, 2, 2, 4, 4, 7, 4, 3, 2, 3, 3, 2, 2, 2, 5, 6, 8, 8, 3, 5, 4, 8, 2, 5, 5, 6, 7, 6, 6, 7, 5, 5, 5, 5, 4], ('192.168.2.1', '192.168.8.1'): [3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.3.1', '192.168.2.1'): [16, 16, 16, 11, 17, 16, 19, 17, 18, 15, 15, 18, 14, 16, 17, 20, 19, 16, 16, 16, 16, 15, 17, 16, 15, 15, 17, 15, 13, 14, 15, 15, 18, 17, 16, 20, 17, 13, 16, 14], ('192.168.3.1', '192.168.5.1'): [27, 9, 9, 16, 8, 7, 8, 10, 10, 12, 8, 11, 8, 11, 9, 9, 8, 11, 26, 11, 12, 11, 9, 10, 9, 9, 10, 28, 10, 11, 23, 10, 11, 12, 9, 11, 11, 26, 9, 15], ('192.168.6.1', '192.168.3.1'): [2, 3, 3, 4, 4, 3, 3, 3, 3, 3, 2, 2, 3, 2, 2, 2, 3, 3, 3, 4, 3, 2, 2, 2, 4, 3, 3, 2, 3, 3, 2, 2, 2, 2, 2, 2, 3, 2, 4, 2], ('192.168.5.1', '192.168.3.1'): [2, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 4, 2, 2, 3, 2, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.3.1', '192.168.6.1'): [9, 7, 10, 8, 11, 11, 9, 8, 10, 12, 9, 9, 10, 9, 9, 10, 9, 11, 10, 8, 8, 7, 10, 10, 11, 12, 12, 7, 11, 11, 9, 10, 9, 12, 10, 12, 9, 10, 9, 8], ('192.168.2.1', '192.168.9.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.2.1', '192.168.3.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.5.1', '192.168.9.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.5.1', '192.168.2.1'): [25, 6, 8, 13, 8, 8, 8, 12, 71, 18, 7, 5, 21, 14, 10, 11, 8, 13, 19, 8, 8, 9, 15, 11, 9, 12, 11, 10, 8, 10, 12, 11, 9, 9, 10, 13, 7, 6, 10, 14], ('192.168.11.1', '192.168.3.1'): [15, 12, 15, 13, 23, 29, 18, 9, 15, 23, 14, 13, 12, 12, 25, 24, 21, 13, 14, 12, 9, 11, 13, 13, 15, 19, 14, 7, 15, 17, 10, 8, 13, 9, 16, 20, 8, 11, 12, 13], ('192.168.5.1', '192.168.8.1'): [2, 2, 4, 2, 2, 2, 2, 6, 2, 2, 2, 2, 4, 2, 2, 2, 2, 11, 2, 2, 2, 4, 4, 2, 3, 2, 3, 4, 2, 2, 2, 2, 3, 2, 2, 3, 5, 2, 2, 3], ('192.168.8.1', '192.168.11.1'): [11, 6, 11, 17, 9, 6, 6, 10, 8, 10, 9, 10, 14, 8, 7, 6, 8, 9, 8, 9, 8, 6, 7, 5, 7, 9, 5, 6, 8, 10, 12, 7, 9, 14, 5, 6, 5, 7, 5, 8], ('192.168.5.1', '192.168.6.1'): [2, 2, 2, 2, 2, 2, 2, 6, 2, 2, 2, 2, 2, 16, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 5, 2, 2, 2], ('192.168.6.1', '192.168.5.1'): [2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 3, 3, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2], ('192.168.6.1', '192.168.8.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.9.1', '192.168.3.1'): [8, 2, 10, 5, 3, 7, 2, 2, 3, 6, 5, 2, 10, 2, 7, 3, 4, 2, 4, 2, 2, 3, 3, 3, 4, 3, 2, 3, 2, 2, 4, 2, 2, 2, 2, 3, 4, 2, 4, 2], ('192.168.3.1', '192.168.9.1'): [2, 3, 3, 3, 2, 2, 2, 2, 2, 3, 2, 3, 2, 3, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 5, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2], ('192.168.11.1', '192.168.6.1'): [5, 3, 5, 5, 2, 3, 4, 2, 3, 3, 5, 3, 2, 7, 2, 5, 2, 5, 2, 4, 2, 2, 2, 3, 3, 2, 3, 2, 2, 2, 4, 2, 3, 6, 2, 2, 2, 5, 2, 2], ('192.168.3.1', '192.168.8.1'): [5, 7, 7, 10, 8, 7, 8, 5, 5, 5, 9, 8, 11, 8, 7, 7, 7, 6, 5, 6, 4, 7, 6, 6, 7, 6, 5, 5, 8, 7, 8, 7, 8, 5, 7, 4, 5, 7, 7, 5], ('192.168.3.1', '192.168.11.1'): [10, 12, 14, 15, 13, 14, 13, 11, 12, 23, 14, 13, 14, 12, 11, 14, 11, 17, 12, 8, 8, 10, 12, 12, 14, 13, 11, 9, 12, 15, 14, 11, 11, 12, 9, 13, 9, 13, 10, 8]}
        if V2:
            for dem in TM_per_demand:
                TM_per_demand[dem].extend(TM_per_demand[dem])
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
        SDNIP_CONF_DIR = '%s/tools/tutorials/sdnip/%s/' % (os.popen("echo $ONOS_ROOT").read().strip(), CONFIG_DIR)
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

    if DEMO_ONOS_BUILD:
        if IPERF_OUTPUT_TO_FILE:
            for dstHost in hostList:
                for srcHost in filter(lambda host: host != dstHost, hostList):
                    os.system('echo; echo iperf %s-from-%s.log; cat %s-from-%s.log' % (dstHost.name, srcHost.name, dstHost.name, srcHost.name))
            os.system('rm *-from-*.log')
    if RUN_INTO_XTERM:
        os.system('kill -9 `pidof xterm`')
