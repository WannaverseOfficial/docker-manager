package com.wannaverse.service;

import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Network;
import com.wannaverse.persistence.StackRepository;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Removes what Docker is no longer using, and previews it first.
 *
 * <p>The previous cleanup only removed dangling images, so stopped containers, unused tagged
 * images, volumes, networks and the build cache all stayed behind. Docker's own `volume prune` also
 * skips named volumes since Engine 23, so volumes are removed one by one here.
 *
 * <p>Never removed: anything belonging to Docker Manager's ingress proxy, containers created in the
 * last few minutes (one may be about to start), backups made while a container is being replaced,
 * and the volumes of compose stacks that still exist (a stack that is down keeps its data).
 */
@Service
public class CleanupService {
    private static final Set<String> BUILTIN_NETWORKS = Set.of("bridge", "host", "none");
    private static final long RECENT_SECONDS = 10 * 60;
    private static final java.util.regex.Pattern RECREATE_BACKUP =
            java.util.regex.Pattern.compile(".*-old-\\d{13}$");

    private final StackRepository stackRepository;

    public CleanupService(StackRepository stackRepository) {
        this.stackRepository = stackRepository;
    }

    public record Options(
            boolean containers,
            boolean images,
            /** true: every image no container uses; false: only untagged (dangling) images. */
            boolean allImages,
            boolean volumes,
            boolean networks,
            boolean buildCache) {}

    public record Item(String kind, String id, String name, long size) {}

    public record Preview(List<Item> items, long reclaimableBytes) {}

    public record Result(
            int containersRemoved,
            int imagesRemoved,
            int volumesRemoved,
            int networksRemoved,
            long bytesReclaimed,
            List<String> errors) {}

    public Preview preview(String hostId, DockerAPI api, Options options) {
        List<Item> items = new ArrayList<>();
        List<Container> all = api.listAllContainers();
        VolumeGuard guard = new VolumeGuard(hostId, api, all);

        Set<String> removedContainerIds = new HashSet<>();
        if (options.containers()) {
            for (Container c : stoppedContainers(all)) {
                removedContainerIds.add(c.getId());
                items.add(new Item("container", c.getId(), name(c), 0));
            }
        }

        if (options.images()) {
            Set<String> used = usedImageIds(all, removedContainerIds);
            for (Image img : api.listImages()) {
                if (used.contains(img.getId())) continue;
                boolean dangling = isDangling(img);
                if (!options.allImages() && !dangling) continue;
                items.add(
                        new Item(
                                "image",
                                img.getId(),
                                dangling ? "<untagged>" : String.join(", ", img.getRepoTags()),
                                img.getSize() == null ? 0 : img.getSize()));
            }
        }

        if (options.volumes()) {
            Set<String> stillMounted = mountedVolumes(all, removedContainerIds);
            for (var v : api.listUnusedVolumes()) {
                if (!stillMounted.contains(v.getName())
                        && !isManaged(v.getName())
                        && !guard.keep(v.getName())) {
                    items.add(new Item("volume", v.getName(), v.getName(), 0));
                }
            }
            // Volumes used only by containers that will be removed become unused too.
            Set<String> freed =
                    mountedVolumes(
                            all.stream()
                                    .filter(c -> removedContainerIds.contains(c.getId()))
                                    .toList(),
                            Set.of());
            Set<String> listed = new HashSet<>();
            items.stream().filter(i -> i.kind().equals("volume")).forEach(i -> listed.add(i.id()));
            for (String vol : freed) {
                if (!stillMounted.contains(vol)
                        && !listed.contains(vol)
                        && !isManaged(vol)
                        && !guard.keep(vol)) {
                    items.add(new Item("volume", vol, vol, 0));
                }
            }
        }

        if (options.networks()) {
            for (Network n : unusedNetworks(api, all, removedContainerIds)) {
                items.add(new Item("network", n.getId(), n.getName(), 0));
            }
        }

        if (options.buildCache()) {
            items.add(new Item("build-cache", "build-cache", "Build cache", 0));
        }

        long bytes = items.stream().mapToLong(Item::size).sum();
        return new Preview(items, bytes);
    }

    public Result run(String hostId, DockerAPI api, Options options) {
        List<String> errors = new ArrayList<>();
        int containers = 0, images = 0, volumes = 0, networks = 0;
        long bytes = 0;

        if (options.containers()) {
            for (Container c : stoppedContainers(api.listAllContainers())) {
                try {
                    // Keep named volumes; anonymous ones go with the container like `docker rm -v`.
                    api.client().removeContainerCmd(c.getId()).withRemoveVolumes(true).exec();
                    containers++;
                } catch (Exception e) {
                    errors.add("Container " + name(c) + ": " + e.getMessage());
                }
            }
        }

        if (options.images()) {
            // Removing an image can make its parent unused, so repeat until nothing changes.
            for (int pass = 0; pass < 5; pass++) {
                Set<String> used = usedImageIds(api.listAllContainers(), Set.of());
                int removedThisPass = 0;
                for (Image img : api.listImages()) {
                    if (used.contains(img.getId())) continue;
                    if (!options.allImages() && !isDangling(img)) continue;
                    try {
                        api.client().removeImageCmd(img.getId()).withForce(false).exec();
                        images++;
                        removedThisPass++;
                        bytes += img.getSize() == null ? 0 : img.getSize();
                    } catch (Exception e) {
                        if (pass == 4) {
                            errors.add("Image " + shortId(img.getId()) + ": " + e.getMessage());
                        }
                    }
                }
                if (removedThisPass == 0) break;
            }
        }

        if (options.volumes()) {
            VolumeGuard guard = new VolumeGuard(hostId, api, api.listAllContainers());
            for (var v : api.listUnusedVolumes()) {
                if (isManaged(v.getName()) || guard.keep(v.getName())) continue;
                try {
                    api.removeVolume(v.getName());
                    volumes++;
                } catch (Exception e) {
                    errors.add("Volume " + v.getName() + ": " + e.getMessage());
                }
            }
        }

        if (options.networks()) {
            for (Network n : unusedNetworks(api, api.listAllContainers(), Set.of())) {
                try {
                    api.removeNetwork(n.getId());
                    networks++;
                } catch (Exception e) {
                    errors.add("Network " + n.getName() + ": " + e.getMessage());
                }
            }
        }

        if (options.buildCache()) {
            try {
                bytes += api.pruneBuildCache();
            } catch (Exception e) {
                errors.add("Build cache: " + e.getMessage());
            }
        }

        return new Result(containers, images, volumes, networks, bytes, errors);
    }

    // --- helpers -----------------------------------------------------------------------------

    private static List<Container> stoppedContainers(List<Container> all) {
        long recentCutoff = System.currentTimeMillis() / 1000 - RECENT_SECONDS;
        return all.stream()
                .filter(
                        c -> {
                            String state = c.getState() == null ? "" : c.getState();
                            return state.equals("exited")
                                    || state.equals("created")
                                    || state.equals("dead");
                        })
                .filter(c -> !isManaged(name(c)))
                .filter(c -> c.getCreated() == null || c.getCreated() < recentCutoff)
                .filter(c -> !RECREATE_BACKUP.matcher(name(c)).matches())
                .toList();
    }

    /** Keeps volumes of compose projects that are still managed here or still have containers. */
    private final class VolumeGuard {
        private final Map<String, String> projectOf = new HashMap<>();
        private final Set<String> liveProjects = new HashSet<>();
        private final String hostId;

        VolumeGuard(String hostId, DockerAPI api, List<Container> all) {
            this.hostId = hostId;
            var response = api.client().listVolumesCmd().exec();
            List<InspectVolumeResponse> volumes =
                    response.getVolumes() == null ? List.of() : response.getVolumes();
            for (InspectVolumeResponse v : volumes) {
                String project =
                        v.getLabels() == null
                                ? null
                                : v.getLabels().get(ContainerSpecService.COMPOSE_PROJECT_LABEL);
                if (project != null) projectOf.put(v.getName(), project);
            }
            for (Container c : all) {
                String project =
                        c.getLabels() == null
                                ? null
                                : c.getLabels().get(ContainerSpecService.COMPOSE_PROJECT_LABEL);
                if (project != null) liveProjects.add(project);
            }
        }

        boolean keep(String volume) {
            String project = projectOf.get(volume);
            if (project == null) return false;
            return liveProjects.contains(project)
                    || stackRepository.findByDockerHostIdAndName(hostId, project).isPresent();
        }
    }

    private static Set<String> usedImageIds(List<Container> all, Set<String> ignoring) {
        Set<String> used = new HashSet<>();
        for (Container c : all) {
            if (ignoring.contains(c.getId())) continue;
            if (c.getImageId() != null) used.add(c.getImageId());
        }
        return used;
    }

    private static Set<String> mountedVolumes(List<Container> all, Set<String> ignoring) {
        Set<String> names = new HashSet<>();
        for (Container c : all) {
            if (ignoring.contains(c.getId()) || c.getMounts() == null) continue;
            c.getMounts()
                    .forEach(
                            m -> {
                                if (m.getName() != null) names.add(m.getName());
                            });
        }
        return names;
    }

    private static List<Network> unusedNetworks(
            DockerAPI api, List<Container> all, Set<String> ignoring) {
        Set<String> inUse = new HashSet<>();
        for (Container c : all) {
            if (ignoring.contains(c.getId())) continue;
            if (c.getNetworkSettings() != null && c.getNetworkSettings().getNetworks() != null) {
                c.getNetworkSettings()
                        .getNetworks()
                        .values()
                        .forEach(n -> inUse.add(n.getNetworkID()));
            }
        }
        List<Network> result = new ArrayList<>();
        for (Network n : api.listNetworks()) {
            if (BUILTIN_NETWORKS.contains(n.getName())) continue;
            if (isManaged(n.getName())) continue;
            if ("swarm".equals(n.getScope())) continue;
            if (n.getName().equals("ingress") || n.getName().equals("docker_gwbridge")) continue;
            if (!inUse.contains(n.getId())) result.add(n);
        }
        return result;
    }

    private static boolean isDangling(Image img) {
        String[] tags = img.getRepoTags();
        return tags == null
                || tags.length == 0
                || Arrays.stream(tags).allMatch(t -> t.equals("<none>:<none>"));
    }

    private static boolean isManaged(String name) {
        return name != null
                && (name.startsWith(IngressService.NGINX_CONTAINER_PREFIX)
                        || name.startsWith(IngressService.INGRESS_NETWORK_PREFIX)
                        || name.startsWith("managed-ingress-"));
    }

    private static String name(Container c) {
        return c.getNames() != null && c.getNames().length > 0
                ? c.getNames()[0].replaceFirst("^/", "")
                : shortId(c.getId());
    }

    private static String shortId(String id) {
        String clean = id.startsWith("sha256:") ? id.substring(7) : id;
        return clean.substring(0, Math.min(12, clean.length()));
    }
}
