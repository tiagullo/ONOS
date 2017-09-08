#!/usr/bin/python

from mininet.cli import CLI
from mininet.log import info, debug, setLogLevel
from mininet.net import Mininet
from mininet.node import Host, RemoteController
from mininet.topo import Topo
import os, subprocess, distutils.spawn

QUAGGA_DIR = '/usr/lib/quagga'
# Must exist and be owned by quagga user (quagga:quagga by default on Ubuntu)
QUAGGA_RUN_DIR = '/var/run/quagga'
CONFIG_DIR = 'configs'

def is_installed(name):
    return distutils.spawn.find_executable(name) is not None

if not is_installed('iperf3'):
	subprocess.call("sudo apt-get -q -y install iperf3".split())

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
    IPERF_OUTPUT_TO_FILE = True # save stdout/stderr to hx-from-hy file
    DEMO_ONOS_BUILD = True
    IPERF_VERSION = 3
    # iperf3 has TCP bandwith configurable but does not allow concurrent clients (sometimes it hangs and results busy)
    # iperf2 has only UDP bandwith configurable but does allow concurrent clients (even if we connect to it sequentially)

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
        os.system("$ONOS_ROOT/tools/test/bin/onos-netcfg localhost $ONOS_ROOT/tools/tutorials/sdnip/configs/network-cfg.json")

        # run multiple iperf3 server instances on each host, one for any other host on port is 5000 + host number
        hostList = filter(lambda host: 'h' in host.name, net.hosts)
        for dstHost in hostList:
            for srcHost in filter(lambda host: host != dstHost, hostList):
                if IPERF_OUTPUT_TO_FILE:
                    if IPERF_VERSION == 3:
                        cmd = "iperf3 -s -p %d > %s-from-%s.log 2>&1 &" % (5000 + int(srcHost.name[1:]), dstHost.name, srcHost.name)
                    else:
                        cmd = "iperf -u -s -p %d > %s-from-%s.log 2>&1 &" % (5000 + int(srcHost.name[1:]), dstHost.name, srcHost.name)
                else:
                    if IPERF_VERSION == 3:
                        cmd = "iperf3 -s -p %d &" % (5000 + int(srcHost.name[1:]))
                    else:
                        cmd = "iperf -u -s -p %d &" % (5000 + int(srcHost.name[1:]))
                dstHost.cmd(cmd)

        # in Mbit/s
        TM_per_demand = {('192.168.4.1', '192.168.3.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.3.1', '192.168.5.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.5.1', '192.168.3.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.4.1', '192.168.5.1'): [10, 4, 9, 2, 2, 4, 10, 4, 9, 2, 2, 4], ('192.168.2.1', '192.168.4.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.2.1', '192.168.3.1'): [2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 3], ('192.168.5.1', '192.168.4.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.3.1', '192.168.4.1'): [3, 4, 5, 3, 4, 6, 3, 4, 5, 3, 4, 6], ('192.168.5.1', '192.168.2.1'): [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2], ('192.168.4.1', '192.168.2.1'): [2, 2, 5, 4, 2, 2, 2, 2, 5, 4, 2, 2], ('192.168.2.1', '192.168.5.1'): [2, 6, 2, 2, 2, 2, 2, 6, 2, 2, 2, 2], ('192.168.3.1', '192.168.2.1'): [20, 24, 23, 22, 14, 15, 20, 24, 23, 22, 14, 15]}
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
                if IPERF_VERSION == 3:
                    cmd += ('iperf3 -c %s -b %dM -p %d -t %d -V; ' % (demand[1] , bw, port, AGGREGATION_INTERVAL if bw_index != len(TM_per_demand[demand])-1 else 2*AGGREGATION_INTERVAL))
                else:
                    cmd += ('iperf -u -c %s -b %dM -p %d -t %d -V; ' % (demand[1] , bw, port, AGGREGATION_INTERVAL if bw_index != len(TM_per_demand[demand])-1 else 2*AGGREGATION_INTERVAL))
            cmd += ') &'
            commands[demand[0] + '/24'] = cmd

        # TODO wait for routes announcements
        raw_input('Wait for BGP announcement, then press enter to generate traffic from TMs\n')

        # execute, for each host, its sequence of commands, sequentially but in background so that each sequence is
        # started in parallel
        for hostIP in commands:
            getHostFromIP(hostIP).cmd(commands[hostIP])

    CLI(net)

    net.stop()

    if DEMO_ONOS_BUILD:
        if IPERF_OUTPUT_TO_FILE:
            for dstHost in hostList:
                for srcHost in filter(lambda host: host != dstHost, hostList):
                    os.system('echo; echo iperf %s-from-%s.log; cat %s-from-%s.log' % (dstHost.name, srcHost.name, dstHost.name, srcHost.name))
            os.system('rm *-from-*.log')
        #os.system("$ONOS_ROOT/tools/dev/bin/onos-app localhost deactivate org.onosproject.sdnip")

    info("done\n")
