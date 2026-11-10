package com.wannaverse.service;

import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StatsConfig;

/**
 * Container resource figures computed the way `docker stats` computes them.
 *
 * <p>Memory: the kernel counts reclaimable page cache as "used". `docker stats` subtracts it
 * (inactive_file on cgroup v2, total_inactive_file on cgroup v1), and so do we; without this a
 * database container could show gigabytes of "usage" that is really disk cache.
 *
 * <p>CPU: (container CPU delta / system CPU delta) x online CPUs x 100, so one fully used core is
 * 100%. When online_cpus is not reported, the number of per-CPU samples is used.
 */
public final class StatsCalculator {
    private StatsCalculator() {}

    public static long memoryUsage(Statistics stats) {
        MemoryStatsConfig mem = stats.getMemoryStats();
        if (mem == null || mem.getUsage() == null) return 0;
        long usage = mem.getUsage();
        StatsConfig s = mem.getStats();
        if (s != null) {
            long cache;
            if (s.getInactiveFile() != null) {
                cache = s.getInactiveFile(); // cgroup v2
            } else if (s.getTotalInactiveFile() != null) {
                cache = s.getTotalInactiveFile(); // cgroup v1
            } else {
                cache = 0;
            }
            if (cache < usage) usage -= cache;
        }
        return usage;
    }

    public static long memoryLimit(Statistics stats) {
        MemoryStatsConfig mem = stats.getMemoryStats();
        return mem == null || mem.getLimit() == null ? 0 : mem.getLimit();
    }

    public static double memoryPercent(Statistics stats) {
        long limit = memoryLimit(stats);
        return limit > 0 ? memoryUsage(stats) * 100.0 / limit : 0;
    }

    public static int onlineCpus(Statistics stats) {
        CpuStatsConfig cpu = stats.getCpuStats();
        if (cpu == null) return 1;
        if (cpu.getOnlineCpus() != null && cpu.getOnlineCpus() > 0) {
            return cpu.getOnlineCpus().intValue();
        }
        if (cpu.getCpuUsage() != null && cpu.getCpuUsage().getPercpuUsage() != null) {
            int n = cpu.getCpuUsage().getPercpuUsage().size();
            if (n > 0) return n;
        }
        return 1;
    }

    /** Percent where 100 is one full core (can exceed 100 on multi-core hosts). */
    public static double cpuPercent(Statistics stats) {
        CpuStatsConfig cpu = stats.getCpuStats();
        CpuStatsConfig pre = stats.getPreCpuStats();
        if (cpu == null || pre == null || cpu.getCpuUsage() == null || pre.getCpuUsage() == null) {
            return 0;
        }
        long cpuDelta = n(cpu.getCpuUsage().getTotalUsage()) - n(pre.getCpuUsage().getTotalUsage());
        long systemDelta = n(cpu.getSystemCpuUsage()) - n(pre.getSystemCpuUsage());
        if (cpuDelta <= 0 || systemDelta <= 0) return 0;
        return (double) cpuDelta / systemDelta * onlineCpus(stats) * 100.0;
    }

    public static long networkRx(Statistics stats) {
        if (stats.getNetworks() == null) return 0;
        return stats.getNetworks().values().stream().mapToLong(v -> n(v.getRxBytes())).sum();
    }

    public static long networkTx(Statistics stats) {
        if (stats.getNetworks() == null) return 0;
        return stats.getNetworks().values().stream().mapToLong(v -> n(v.getTxBytes())).sum();
    }

    public static long blockRead(Statistics stats) {
        return blkio(stats, "read");
    }

    public static long blockWrite(Statistics stats) {
        return blkio(stats, "write");
    }

    private static long blkio(Statistics stats, String op) {
        if (stats.getBlkioStats() == null
                || stats.getBlkioStats().getIoServiceBytesRecursive() == null) {
            return 0;
        }
        return stats.getBlkioStats().getIoServiceBytesRecursive().stream()
                .filter(e -> op.equalsIgnoreCase(e.getOp()))
                .mapToLong(e -> n(e.getValue()))
                .sum();
    }

    public static int pids(Statistics stats) {
        return stats.getPidsStats() != null && stats.getPidsStats().getCurrent() != null
                ? stats.getPidsStats().getCurrent().intValue()
                : 0;
    }

    private static long n(Long v) {
        return v == null ? 0 : v;
    }
}
