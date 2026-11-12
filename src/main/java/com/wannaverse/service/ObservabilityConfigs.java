package com.wannaverse.service;

import com.wannaverse.persistence.ObservabilityStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The configuration files of an observability stack. Every component reaches the others by
 * container name on the stack's own network, so nothing here depends on the host.
 *
 * <p>Checked against the single-binary examples each project ships at the pinned versions (see
 * {@link ObservabilityService}): Mimir's demo.yaml, Loki's loki-local-config.yaml and Tempo's
 * single-binary example.
 */
final class ObservabilityConfigs {
    static final String GRAFANA = "dm-obs-grafana";
    static final String PROMETHEUS = "dm-obs-prometheus";
    static final String MIMIR = "dm-obs-mimir";
    static final String LOKI = "dm-obs-loki";
    static final String TEMPO = "dm-obs-tempo";
    static final String ALLOY = "dm-obs-alloy";
    static final String CADVISOR = "dm-obs-cadvisor";
    static final String NODE_EXPORTER = "dm-obs-node-exporter";

    /** Datasource ids the provisioned dashboards refer to. */
    static final String METRICS_UID = "dm-metrics";

    static final String PROMETHEUS_UID = "dm-prometheus";
    static final String LOKI_UID = "dm-loki";
    static final String TEMPO_UID = "dm-tempo";

    private ObservabilityConfigs() {}

    /** Where metrics end up: Mimir when it runs, otherwise Prometheus itself. */
    static String metricsQueryUrl(ObservabilityStack s) {
        return s.isMimirEnabled()
                ? "http://" + MIMIR + ":9009/prometheus"
                : "http://" + PROMETHEUS + ":9090";
    }

    /** Prometheus remote-write endpoint for other components (Tempo's span metrics). */
    static String metricsWriteUrl(ObservabilityStack s) {
        return s.isMimirEnabled()
                ? "http://" + MIMIR + ":9009/api/v1/push"
                : "http://" + PROMETHEUS + ":9090/api/v1/write";
    }

    /** OTLP/HTTP base for metrics from apps (the exporter adds /v1/metrics). */
    static String metricsOtlpUrl(ObservabilityStack s) {
        return s.isMimirEnabled()
                ? "http://" + MIMIR + ":9009/otlp"
                : "http://" + PROMETHEUS + ":9090/api/v1/otlp";
    }

    static boolean alloyNeeded(ObservabilityStack s) {
        return (s.isCollectLogs() && s.isLokiEnabled()) || s.isOtlpEnabled();
    }

    // --- Prometheus ------------------------------------------------------------------------------

    static String prometheus(ObservabilityStack s) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Managed by Docker Manager. Changes made here are overwritten.\n");
        sb.append("global:\n");
        sb.append("  scrape_interval: 15s\n");
        sb.append("  evaluation_interval: 15s\n");
        if (s.isMimirEnabled()) {
            sb.append("remote_write:\n");
            sb.append("  - url: http://").append(MIMIR).append(":9009/api/v1/push\n");
            sb.append("    send_exemplars: true\n");
        }
        sb.append("scrape_configs:\n");
        job(sb, "prometheus", "localhost:9090", null);
        job(sb, "grafana", GRAFANA + ":3000", null);
        if (s.isCollectMetrics()) {
            job(sb, "cadvisor", CADVISOR + ":8080", null);
            job(sb, "node", NODE_EXPORTER + ":9100", null);
        }
        if (s.isMimirEnabled()) job(sb, "mimir", MIMIR + ":9009", null);
        if (s.isLokiEnabled()) job(sb, "loki", LOKI + ":3100", null);
        if (s.isTempoEnabled()) job(sb, "tempo", TEMPO + ":3200", null);
        return sb.toString();
    }

    private static void job(StringBuilder sb, String name, String target, String path) {
        sb.append("  - job_name: ").append(name).append('\n');
        if (path != null) sb.append("    metrics_path: ").append(path).append('\n');
        sb.append("    static_configs:\n");
        sb.append("      - targets: [\"").append(target).append("\"]\n");
    }

    /** Command-line flags (the image's entrypoint is prometheus itself). */
    static List<String> prometheusArgs(ObservabilityStack s) {
        // With Mimir keeping the history, Prometheus only needs a few days locally.
        int days = s.isMimirEnabled() ? 2 : s.getMetricsRetentionDays();
        List<String> args = new ArrayList<>();
        args.add("--config.file=/etc/prometheus/prometheus.yml");
        args.add("--storage.tsdb.path=/prometheus");
        args.add("--storage.tsdb.retention.time=" + days + "d");
        args.add("--web.enable-lifecycle");
        if (!s.isMimirEnabled()) {
            // Tempo's span metrics and apps' OTLP metrics are written into Prometheus directly.
            args.add("--web.enable-remote-write-receiver");
            args.add("--web.enable-otlp-receiver");
        }
        return args;
    }

    // --- Mimir -----------------------------------------------------------------------------------

    static String mimir(ObservabilityStack s) {
        return """
        # Managed by Docker Manager. Changes made here are overwritten.
        # Mimir in monolithic mode with its blocks on the local volume.
        target: all
        multitenancy_enabled: false

        server:
          http_listen_port: 9009
          log_level: warn

        blocks_storage:
          backend: filesystem
          bucket_store:
            sync_dir: /data/tsdb-sync
          filesystem:
            dir: /data/blocks
          tsdb:
            dir: /data/tsdb

        compactor:
          data_dir: /data/compactor
          sharding_ring:
            kvstore:
              store: memberlist

        distributor:
          ring:
            instance_addr: 127.0.0.1
            kvstore:
              store: memberlist

        ingester:
          ring:
            instance_addr: 127.0.0.1
            kvstore:
              store: memberlist
            replication_factor: 1

        ruler_storage:
          backend: filesystem
          filesystem:
            dir: /data/rules

        store_gateway:
          sharding_ring:
            replication_factor: 1

        limits:
          compactor_blocks_retention_period: %dd
          max_global_series_per_user: 1000000
          ingestion_rate: 50000
          ingestion_burst_size: 500000

        usage_stats:
          enabled: false
        """
                .formatted(s.getMetricsRetentionDays());
    }

    // --- Loki ------------------------------------------------------------------------------------

    static String loki(ObservabilityStack s) {
        return """
        # Managed by Docker Manager. Changes made here are overwritten.
        auth_enabled: false

        server:
          http_listen_port: 3100
          grpc_listen_port: 9096
          log_level: warn

        common:
          instance_addr: 127.0.0.1
          path_prefix: /loki
          storage:
            filesystem:
              chunks_directory: /loki/chunks
              rules_directory: /loki/rules
          replication_factor: 1
          ring:
            kvstore:
              store: inmemory

        query_range:
          results_cache:
            cache:
              embedded_cache:
                enabled: true
                max_size_mb: 100

        schema_config:
          configs:
            - from: 2024-01-01
              store: tsdb
              object_store: filesystem
              schema: v13
              index:
                prefix: index_
                period: 24h

        limits_config:
          retention_period: %dh
          volume_enabled: true
          allow_structured_metadata: true

        compactor:
          working_directory: /loki/compactor
          retention_enabled: true
          delete_request_store: filesystem

        analytics:
          reporting_enabled: false
        """
                .formatted(s.getLogsRetentionDays() * 24);
    }

    // --- Tempo -----------------------------------------------------------------------------------

    static String tempo(ObservabilityStack s) {
        return """
        # Managed by Docker Manager. Changes made here are overwritten.
        # Tempo 3 in monolithic mode (target all): no Kafka needed.
        stream_over_http_enabled: true

        server:
          http_listen_port: 3200
          log_level: warn

        distributor:
          receivers:
            otlp:
              protocols:
                grpc:
                  endpoint: "0.0.0.0:4317"
                http:
                  endpoint: "0.0.0.0:4318"

        metrics_generator:
          registry:
            external_labels:
              source: tempo
          storage:
            path: /var/tempo/generator/wal
            remote_write:
              - url: %s
                send_exemplars: true

        storage:
          trace:
            backend: local
            wal:
              path: /var/tempo/wal
            local:
              path: /var/tempo/blocks

        overrides:
          defaults:
            metrics_generator:
              processors: ["service-graphs", "span-metrics"]
            compaction:
              block_retention: %dh

        usage_report:
          reporting_enabled: false
        """
                .formatted(metricsWriteUrl(s), s.getTracesRetentionDays() * 24);
    }

    // --- Grafana Alloy ---------------------------------------------------------------------------

    static String alloy(ObservabilityStack s) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Managed by Docker Manager. Changes made here are overwritten.\n");
        sb.append("logging {\n  level = \"warn\"\n}\n");
        if (s.isCollectLogs() && s.isLokiEnabled()) {
            sb.append(
                    """

                    // Every container's logs, labelled with its name and Compose project/service.
                    discovery.docker "containers" {
                      host = "unix:///var/run/docker.sock"
                    }

                    discovery.relabel "containers" {
                      targets = []

                      rule {
                        source_labels = ["__meta_docker_container_name"]
                        regex         = "/(.*)"
                        target_label  = "container"
                      }
                      rule {
                        source_labels = ["__meta_docker_container_label_com_docker_compose_project"]
                        target_label  = "compose_project"
                      }
                      rule {
                        source_labels = ["__meta_docker_container_label_com_docker_compose_service"]
                        target_label  = "compose_service"
                      }
                    }

                    loki.source.docker "containers" {
                      host          = "unix:///var/run/docker.sock"
                      targets       = discovery.docker.containers.targets
                      relabel_rules = discovery.relabel.containers.rules
                      labels        = {"source" = "docker"}
                      forward_to    = [loki.write.default.receiver]
                    }

                    loki.write "default" {
                      endpoint {
                        url = "http://%s:3100/loki/api/v1/push"
                      }
                    }
                    """
                            .formatted(LOKI));
        }
        if (s.isOtlpEnabled()) {
            List<String> traces = new ArrayList<>();
            List<String> logs = new ArrayList<>();
            if (s.isTempoEnabled()) traces.add("otelcol.exporter.otlphttp.tempo.input");
            if (s.isLokiEnabled()) logs.add("otelcol.exporter.otlphttp.loki.input");
            sb.append(
                    """

                    // OpenTelemetry from apps on the stack's OTLP network.
                    otelcol.receiver.otlp "apps" {
                      grpc {
                        endpoint = "0.0.0.0:4317"
                      }
                      http {
                        endpoint = "0.0.0.0:4318"
                      }
                      output {
                        metrics = [otelcol.processor.batch.apps.input]
                        logs    = [otelcol.processor.batch.apps.input]
                        traces  = [otelcol.processor.batch.apps.input]
                      }
                    }

                    otelcol.processor.batch "apps" {
                      output {
                        metrics = [otelcol.exporter.otlphttp.metrics.input]
                        logs    = [%s]
                        traces  = [%s]
                      }
                    }

                    otelcol.exporter.otlphttp "metrics" {
                      client {
                        endpoint = "%s"
                      }
                    }
                    """
                            .formatted(
                                    String.join(", ", logs),
                                    String.join(", ", traces),
                                    metricsOtlpUrl(s)));
            if (s.isTempoEnabled()) {
                sb.append(
                        """

                        otelcol.exporter.otlphttp "tempo" {
                          client {
                            endpoint = "http://%s:4318"
                          }
                        }
                        """
                                .formatted(TEMPO));
            }
            if (s.isLokiEnabled()) {
                sb.append(
                        """

                        otelcol.exporter.otlphttp "loki" {
                          client {
                            endpoint = "http://%s:3100/otlp"
                          }
                        }
                        """
                                .formatted(LOKI));
            }
        }
        return sb.toString();
    }

    // --- Grafana ---------------------------------------------------------------------------------

    static String grafanaDatasources(ObservabilityStack s) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Managed by Docker Manager. Changes made here are overwritten.\n");
        sb.append("apiVersion: 1\n");
        sb.append("prune: true\n");
        sb.append("datasources:\n");
        sb.append(
                """
                  - name: %s
                    type: prometheus
                    uid: %s
                    access: proxy
                    url: %s
                    isDefault: true
                    editable: false
                    jsonData:
                      httpMethod: POST
                      timeInterval: 15s
                      prometheusType: %s
                """
                        .formatted(
                                s.isMimirEnabled() ? "Mimir" : "Prometheus",
                                METRICS_UID,
                                metricsQueryUrl(s),
                                s.isMimirEnabled() ? "Mimir" : "Prometheus"));
        if (s.isMimirEnabled()) {
            sb.append(
                    """
                      - name: Prometheus
                        type: prometheus
                        uid: %s
                        access: proxy
                        url: http://%s:9090
                        editable: false
                        jsonData:
                          httpMethod: POST
                          timeInterval: 15s
                          prometheusType: Prometheus
                    """
                            .formatted(PROMETHEUS_UID, PROMETHEUS));
        }
        if (s.isLokiEnabled()) {
            sb.append(
                    """
                      - name: Loki
                        type: loki
                        uid: %s
                        access: proxy
                        url: http://%s:3100
                        editable: false
                    """
                            .formatted(LOKI_UID, LOKI));
            if (s.isTempoEnabled()) {
                // Trace ids in log lines link to the trace.
                sb.append(
                        """
                            jsonData:
                              derivedFields:
                                - name: TraceID
                                  datasourceUid: %s
                                  matcherRegex: '[Tt]race_?[Ii][Dd]["=:\\s]+"?(\\w+)'
                                  url: '$${__value.raw}'
                        """
                                .formatted(TEMPO_UID));
            }
        }
        if (s.isTempoEnabled()) {
            sb.append(
                    """
                      - name: Tempo
                        type: tempo
                        uid: %s
                        access: proxy
                        url: http://%s:3200
                        editable: false
                        jsonData:
                          serviceMap:
                            datasourceUid: %s
                          nodeGraph:
                            enabled: true
                          streamingEnabled:
                            search: true
                    """
                            .formatted(TEMPO_UID, TEMPO, METRICS_UID));
            if (s.isLokiEnabled()) {
                sb.append(
                        """
                              tracesToLogsV2:
                                datasourceUid: %s
                                spanStartTimeShift: -5m
                                spanEndTimeShift: 5m
                                filterByTraceID: true
                              lokiSearch:
                                datasourceUid: %s
                        """
                                .formatted(LOKI_UID, LOKI_UID));
            }
        }
        return sb.toString();
    }

    static String grafanaDashboardProvider() {
        return """
        # Managed by Docker Manager. Changes made here are overwritten.
        apiVersion: 1
        providers:
          - name: docker-manager
            folder: Docker Manager
            type: file
            disableDeletion: true
            allowUiUpdates: false
            options:
              path: /etc/grafana/dashboards
        """;
    }

    /** Which provisioned dashboards (files under resources/observability/dashboards) apply. */
    static List<String> dashboards(ObservabilityStack s) {
        List<String> names = new ArrayList<>();
        if (s.isCollectMetrics()) {
            names.add("containers.json");
            names.add("host.json");
        }
        if (s.isLokiEnabled() && s.isCollectLogs()) names.add("logs.json");
        return names;
    }
}
