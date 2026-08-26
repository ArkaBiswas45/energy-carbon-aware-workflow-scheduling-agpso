package org.example;

import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.schedulers.vm.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.vms.VmSimple;

import java.util.ArrayList;
import java.util.List;


public class InfrastructureFactory {

    public static final double[] SLA_DEADLINE = { 3.0, 8.0, 15.0 };
    public static final int HIGH   = 0;
    public static final int MEDIUM = 1;
    public static final int LOW    = 2;
    // ── Power model (Watts) ──────────────────────────────────────────────────
// Active power when executing tasks; idle = IDLE_FACTOR × active
// ── New constants (add after existing SLA_DEADLINE line) ─────────────────
    public static final long[]   VM_MIPS          = { 2000, 1500, 1000, 750, 500 };
    public static final double[] VM_POWER_WATTS   = { 100.0, 85.0, 65.0, 52.0, 40.0 };
    public static final double   SCALED_VM_POWER_WATTS = 80.0;
    public static final double   IDLE_POWER_FACTOR     = 0.20;
    public static final double   COST_PER_KWH          = 0.05;
    public static final double   COST_PER_TASK         = 0.001;
    
    // ── Hosts: 3 hosts with enough capacity for 5 VMs ─────────────────────
    public static List<Host> createHosts() {
        List<Host> hosts = new ArrayList<>();
        hosts.add(buildHost(16384, 200_000L, 2_000_000L, 6, 3000)); // handles VM-0, VM-1
        hosts.add(buildHost(8192,  100_000L, 1_000_000L, 4, 2500)); // handles VM-2, VM-3
        hosts.add(buildHost(4096,   50_000L, 1_000_000L, 2, 2000)); // handles VM-4
        return hosts;
    }
    private static Host buildHost(long ramMB, long bwMbps, long storageMB, int peCount, long mips) {
        List<Pe> peList = new ArrayList<>();
        for (int i = 0; i < peCount; i++) peList.add(new PeSimple(mips));
        HostSimple host = new HostSimple(ramMB, bwMbps, storageMB, peList);
        host.setVmScheduler(new VmSchedulerTimeShared());
        return host;
    }

    // ── 5 VMs ──────────────────────────────────────────────────────────────
    public static List<VmSimple> createVMs() {
        List<VmSimple> list = new ArrayList<>();
        list.add(buildVm(2000, 2, 1024, 2000));  // VM-0  fastest
        list.add(buildVm(1500, 2,  896, 1800));  // VM-1
        list.add(buildVm(1000, 1,  512, 1200));  // VM-2
        list.add(buildVm( 750, 1,  384,  800));  // VM-3
        list.add(buildVm( 500, 1,  256,  500));  // VM-4  slowest
        return list;
    }
    private static VmSimple buildVm(long mips, int pes, long ramMB, long bwMbps) {
        VmSimple vm = new VmSimple(mips, pes);
        vm.setRam(ramMB).setBw(bwMbps).setSize(10_000);
        vm.setCloudletScheduler(new CloudletSchedulerTimeShared());
        return vm;
    }


    // ── 15 cloudlets (5 HIGH + 5 MEDIUM + 5 LOW) ───────────────────────────
    public static List<CloudletSimple> createCloudlets() {
        List<CloudletSimple> list = new ArrayList<>();
        for (int i = 0; i < 5; i++) list.add(buildCloudlet(2000, HIGH));
        for (int i = 0; i < 5; i++) list.add(buildCloudlet(5000, MEDIUM));
        for (int i = 0; i < 5; i++) list.add(buildCloudlet(9000, LOW));
        return list;
    }
    private static CloudletSimple buildCloudlet(long lengthMI, int priority) {
        CloudletSimple c = new CloudletSimple(lengthMI, 1);
        c.setSizes(300);
        c.setNetServiceLevel(priority);
        return c;
    }

    // Simulation bundle
    public static SimulationBundle createSimulation() {
        CloudSim sim = new CloudSim();
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(sim);
        new DatacenterSimple(sim, createHosts());
        return new SimulationBundle(sim, broker);
    }

    public static class SimulationBundle {
        public final CloudSim sim;
        public final DatacenterBrokerSimple broker;
        public SimulationBundle(CloudSim sim, DatacenterBrokerSimple broker) {
            this.sim    = sim;
            this.broker = broker;
        }
    }
}