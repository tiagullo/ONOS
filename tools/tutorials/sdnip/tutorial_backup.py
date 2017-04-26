#!/usr/bin/python

from mininet.cli import CLI
from mininet.log import info, debug
from mininet.net import Mininet
from mininet.node import Host, RemoteController
from mininet.topo import Topo

QUAGGA_DIR = '/usr/lib/quagga'
# Must exist and be owned by quagga user (quagga:quagga by default on Ubuntu)
QUAGGA_RUN_DIR = '/var/run/quagga'
CONFIG_DIR = 'configs'

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

        for i in range(1, 4+1):
            name = 'r%s' % i
	    if (i==2):
		 eth0 = { 'mac' : '0a:00:00:00:0%s:01' % 5,
                     'ipAddrs' : ['10.0.%s.1/24' % 5] }
	    else:
		 eth0 = { 'mac' : '0a:00:00:00:0%s:01' % i,
                     'ipAddrs' : ['10.0.%s.1/24' % i] }

            eth1 = { 'ipAddrs' : ['192.168.%s.254/24' % i] }
            eth2 = { 'ipAddrs' : ['192.168.%s0.1/24' % i] }
            intfs = { '%s-eth0' % name : eth0,
                      '%s-eth1' % name : eth1,
		      '%s-eth2' % name : eth2 }

	    # Add a third interface to router R1
	    '''if i==1:
                 intfs['r1-eth2']={ 'ipAddrs' : ['192.168.10.254/24'] }
	    if i==2:
                 intfs['r2-eth2']={ 'ipAddrs' : ['192.168.20.254/24'] }
	    if i==3:
                 intfs['r3-eth2']={ 'ipAddrs' : ['192.168.30.254/24'] }
	    if i==4:
                 intfs['r4-eth2']={ 'ipAddrs' : ['192.168.40.254/24'] }'''


            quaggaConf = '%s/quagga%s.conf' % (CONFIG_DIR, i)

            router = self.addHost(name, cls=Router, quaggaConfFile=quaggaConf,
                                  zebraConfFile=zebraConf, intfDict=intfs)

            host = self.addHost('h%s' % i, cls=SdnIpHost,
                                ip='192.168.%s.1/24' % i,
                                route='192.168.%s.254' % i)

            host2 = self.addHost('h%s0' % i, cls=SdnIpHost,
                                ip='192.168.%s0.1/24' % i,
                                route='192.168.%s0.254' % i)

	    '''# Add an additional host connected to router 
            if i==1:
	         host2 = self.addHost('h10', cls=SdnIpHost,
                               ip='192.168.10.1/24',
                               route='192.168.10.254')
	    if i==2:
	         host2 = self.addHost('h20', cls=SdnIpHost,
                               ip='192.168.20.1/24',
                               route='192.168.20.254')
	    if i==3:
	         host2 = self.addHost('h30', cls=SdnIpHost,
                               ip='192.168.30.1/24',
                               route='192.168.30.254')
	    if i==4:
	         host2 = self.addHost('h40', cls=SdnIpHost,
                               ip='192.168.40.1/24',
                               route='192.168.40.254')'''

            self.addLink(router, attachmentSwitches[i-1])
            self.addLink(router, host) 
            self.addLink(router, host2)

        # Set up the internal BGP speaker
        bgpEth0 = { 'mac':'00:00:00:00:00:01',
                    'ipAddrs' : ['10.0.1.101/24',
                                 '10.0.5.101/24',
                                 '10.0.3.101/24',
                                 '10.0.4.101/24',] }
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
#    setLogLevel('debug')
    topo = SdnIpTopo()

    net = Mininet(topo=topo, controller=RemoteController)

    net.start()

    CLI(net)

    net.stop()

    info("done\n")
