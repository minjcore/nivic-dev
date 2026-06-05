# 🔍 Production Monitoring Setup Guide

**Date**: June 5, 2026  
**Status**: Ready for Deployment  
**Components**: Prometheus, Grafana, AlertManager, Node Exporter

---

## 📋 Overview

Complete production monitoring stack for the crypto settlement system. Monitors all critical components:
- **C-Server**: Blockchain listener, deposit detection, RabbitMQ publishing
- **Java Ledger**: Settlement processing, database operations, API latency
- **RabbitMQ**: Queue depth, message throughput, connection health
- **PostgreSQL**: Connection pool, query performance, replication status
- **System**: CPU, memory, disk, network I/O

---

## 🚀 Quick Start (5 minutes)

### Prerequisites
```bash
# Verify all services running
curl http://localhost:8090/actuator/health    # Java Ledger
curl http://localhost:8081/health             # C-Server
curl http://localhost:5672                    # RabbitMQ
psql -U postgres -d gtelpay_prod -c "SELECT 1"  # PostgreSQL
```

### Step 1: Start Monitoring Stack
```bash
cd /Users/khangdc/Desktop/nivic-dev/monitoring
docker-compose up -d
```

Wait for services to start (~30 seconds).

### Step 2: Verify Services Running
```bash
docker-compose ps

# Expected output:
# prometheus      Running on :9090
# grafana         Running on :3000
# alertmanager    Running on :9093
# node_exporter   Running on :9100
```

### Step 3: Access Dashboards

| Service | URL | Login |
|---------|-----|-------|
| Prometheus | http://localhost:9090 | None |
| Grafana | http://localhost:3000 | admin/admin |
| AlertManager | http://localhost:9093 | None |

---

## 📊 Monitoring Stack Components

### Prometheus (Port 9090)
- Time-series database for metrics
- Scrapes endpoints every 15 seconds
- 30-day data retention
- Alert evaluation engine

**Scrape Targets**:
```
- C-Server metrics (http://localhost:8081/metrics)
- Java Ledger (http://localhost:8090/actuator/prometheus)
- Node Exporter (http://localhost:9100/metrics)
- RabbitMQ (http://localhost:15672/api/metrics)
```

### Grafana (Port 3000)
- Visualization and dashboarding
- Queries Prometheus data
- Custom alerts and notifications
- Admin dashboard

**Access**: http://localhost:3000
**Credentials**: admin/admin

### AlertManager (Port 9093)
- Alert deduplication and routing
- Manages alert notifications
- Routes to email, PagerDuty, webhooks
- Alert grouping and inhibition

### Node Exporter (Port 9100)
- System metrics collection
- CPU, memory, disk, network
- Process-level metrics
- Hardware monitoring

---

## 📈 Key Metrics Being Collected

### C-Server Metrics
```
c_server_deposits_detected        Total deposits found on blockchain
c_server_events_published         Events sent to RabbitMQ
c_server_errors                   Processing errors
c_server_blockchain_status        RPC connection (1=up, 0=down)
```

### Java Ledger Metrics
```
settlement_initiated_total        Settlements created
settlement_confirmed_total        Settlements completed  
settlement_failures_total         Failed settlements
settlement_processing_duration_seconds  Latency histogram
db_connection_pool_active         Active database connections
jvm_memory_used_bytes             JVM memory usage
http_requests_total               API request count
http_request_duration_seconds     API latency
```

### RabbitMQ Metrics
```
rabbitmq_queue_messages_ready     Messages waiting in queue
rabbitmq_connections              Active connections
rabbitmq_channels                 Active channels
rabbitmq_messages_published_total Total published messages
rabbitmq_messages_acked_total     Total acknowledged messages
```

### System Metrics (Node Exporter)
```
node_cpu_seconds_total            CPU time (for percentage calculation)
node_memory_MemAvailable_bytes    Available memory
node_memory_MemTotal_bytes        Total memory
node_filesystem_avail_bytes       Free disk space
node_filesystem_size_bytes        Total disk space
node_network_receive_bytes_total  Network input
node_network_transmit_bytes_total Network output
```

---

## 🚨 Alert Rules

### Critical Alerts (Page On-Call)

**1. Blockchain Connection Down**
```
Alert fires when: C-Server cannot reach RPC endpoint for 2 minutes
Severity: CRITICAL
Action: Immediate investigation of RPC endpoint health
```

**2. Settlement Failure Rate High**
```
Alert fires when: > 10% of settlements fail for 5 minutes
Severity: CRITICAL  
Action: Check database, RabbitMQ, bank integration
```

**3. Database Connection Pool Exhausted**
```
Alert fires when: 90%+ of connection pool in use for 2 minutes
Severity: CRITICAL
Action: Increase pool size or reduce connection hold time
```

**4. Disk Usage Critical**
```
Alert fires when: Disk usage > 85% for 5 minutes
Severity: CRITICAL
Action: Free up disk space, archive old data
```

### Warning Alerts (Email Notification)

**1. High Settlement Latency**
```
Alert fires when: P95 latency > 2 seconds for 5 minutes
Severity: WARNING
Action: Profile settlement processing, check database
```

**2. RabbitMQ Queue Backlog**
```
Alert fires when: Queue depth > 1000 messages for 5 minutes
Severity: WARNING
Action: Check message consumption rate, scale consumers
```

**3. High Memory Usage**
```
Alert fires when: > 85% memory used for 5 minutes
Severity: WARNING
Action: Check for memory leaks, increase capacity
```

**4. High CPU Usage**
```
Alert fires when: > 80% CPU for 5 minutes
Severity: WARNING
Action: Profile for hot spots, consider scaling
```

---

## 🔧 Configuration Files

### prometheus.yml
Main Prometheus configuration file that defines:
- Global scrape interval (15 seconds)
- Scrape targets and ports
- Alert rules file location
- AlertManager endpoint

**Edit to**:
- Add new scrape targets
- Change scrape intervals
- Adjust evaluation frequency
- Configure external labels

### alert-rules.yml
Alert rule definitions for all monitoring scenarios.

**Key sections**:
- `crypto_settlement_alerts`: Settlement system alerts
- Alert conditions (Prometheus queries)
- Annotations (summary, description)

**To add new alert**:
```yaml
- alert: MyAlertName
  expr: metric_name > threshold
  for: 5m
  annotations:
    summary: "Alert description"
    description: "Detailed explanation"
```

### alertmanager.yml
Routes alerts to notification channels.

**Receivers**:
- `default`: Webhook to localhost:5001
- `email`: SMTP notifications
- `pagerduty`: PagerDuty integration

**To enable email alerts**:
```yaml
- name: 'email'
  email_configs:
    - to: 'ops@example.com'
      from: 'alerts@example.com'
      smarthost: 'smtp.gmail.com:587'
      auth_username: 'alerts@gmail.com'
      auth_password: '${SMTP_PASSWORD}'
```

### docker-compose.yml
Orchestrates all monitoring services.

**Services**:
- prometheus: Time-series database
- grafana: Dashboarding
- alertmanager: Alert routing
- node_exporter: System metrics

---

## 📊 Dashboards

### Settlement Overview
Shows key settlement metrics:
- Deposits detected (24-hour rate)
- Settlement success rate (%)
- Processing latency (P95)
- RabbitMQ queue depth
- Database connections

### System Health
Displays system resource usage:
- CPU usage (%)
- Memory usage (%)
- Disk usage (%)
- Network I/O (bytes/sec)
- Disk I/O (bytes/sec)

### RabbitMQ Monitoring
RabbitMQ operational metrics:
- Queue depth by queue
- Message publish/consume rates
- Connection count
- Channel count
- Memory usage

### Database Performance
PostgreSQL metrics:
- Active connections
- Slow query count
- Query latency distribution
- Table sizes
- Index usage

---

## 🔧 Operational Procedures

### Viewing Alerts

**In Prometheus**:
1. Go to http://localhost:9090/alerts
2. See firing and pending alerts
3. Check alert rules and their status

**In AlertManager**:
1. Go to http://localhost:9093
2. See grouped alerts
3. View notification status

### Creating Custom Dashboard

**In Grafana**:
1. Click "+" → Dashboard → New Dashboard
2. Add Panel → Metrics (Prometheus)
3. Enter PromQL query (e.g., `rate(settlement_initiated_total[5m])`)
4. Choose visualization (graph, gauge, table)
5. Set title and save

### Adding New Metric

**1. Ensure exporter publishes metric**:
```
# Check C-Server metrics endpoint
curl http://localhost:8081/metrics | grep metric_name

# Check Java Ledger metrics  
curl http://localhost:8090/actuator/prometheus | grep metric_name
```

**2. Add scrape job in prometheus.yml** (if new endpoint):
```yaml
- job_name: 'my-new-service'
  static_configs:
    - targets: ['localhost:9999']
  metrics_path: '/metrics'
```

**3. Restart Prometheus**:
```bash
docker-compose restart prometheus
```

**4. Query metric in Prometheus**:
```
http://localhost:9090/graph
# Enter: my_metric_name
```

**5. Add to dashboard in Grafana**:
- Create new panel
- Use metric name in query
- Save dashboard

### Scaling Database Metrics

If PostgreSQL metrics not appearing:

```bash
# Install postgres_exporter (requires manual setup outside Docker)
# For now, use basic connection monitoring

# Query active connections via psql:
psql -U postgres -d gtelpay_prod -c "
  SELECT count(*) 
  FROM pg_stat_activity;"
```

---

## 🛠️ Troubleshooting

### Prometheus Not Scraping Targets

**Issue**: Targets showing "DOWN" in Prometheus UI

**Solution**:
```bash
# Check if services are running
curl http://localhost:8090/actuator/health
curl http://localhost:8081/health
curl http://localhost:9100/metrics

# Check Prometheus logs
docker-compose logs prometheus | tail -20

# Verify configuration syntax
docker-compose exec prometheus promtool check config /etc/prometheus/prometheus.yml
```

### Grafana Not Showing Data

**Issue**: Dashboards show "No data"

**Solution**:
```bash
# Verify Prometheus datasource
1. Go to http://localhost:3000
2. Settings → Data Sources
3. Select "Prometheus"
4. Click "Save & Test"
5. Should show "Data source is working"

# Check metric exists in Prometheus
curl 'http://localhost:9090/api/v1/query?query=up'

# Restart Grafana
docker-compose restart grafana
```

### Alerts Not Firing

**Issue**: Alert rules exist but not firing

**Solution**:
```bash
# Verify alert rule syntax
docker-compose exec prometheus promtool check rules /etc/prometheus/alert_rules.yml

# Check alert status
curl 'http://localhost:9090/api/v1/alerts' | jq '.data.alerts'

# Verify AlertManager is running
curl http://localhost:9093/api/v1/alerts

# Check AlertManager config
docker-compose logs alertmanager | tail -20
```

### High Memory Usage in Prometheus

**Issue**: Prometheus container using lots of memory

**Solution**:
```yaml
# Edit docker-compose.yml, add to prometheus service:
environment:
  - --storage.tsdb.retention.size=5GB

# This limits storage to 5GB instead of unlimited
docker-compose up -d prometheus
```

---

## 📈 Performance Tuning

### Reduce Storage Usage
```yaml
# In docker-compose.yml, prometheus command:
- '--storage.tsdb.retention.time=7d'  # Keep 7 days instead of 30
- '--storage.tsdb.retention.size=10GB' # Or limit by size
```

### Increase Scrape Frequency
```yaml
# In prometheus.yml, global section:
scrape_interval: 5s  # Change from 15s for more frequent updates
```

### Add More Retention
```yaml
# In prometheus.yml:
global:
  external_labels:
    cluster: 'production'
    region: 'us-east-1'
```

---

## 🔐 Security Considerations

### For Production
1. **Change Grafana admin password**:
   ```bash
   docker-compose exec grafana grafana-cli admin reset-admin-password newpassword
   ```

2. **Enable HTTPS/TLS** (optional):
   - Configure reverse proxy (nginx)
   - Add SSL certificates
   - Route through proxy to services

3. **Restrict access** (optional):
   - Firewall ports 9090, 3000, 9093 to internal IPs only
   - Use VPN/bastion host for external access

4. **Backup Grafana dashboards**:
   ```bash
   docker-compose exec grafana grafana-cli admin export-dashboard > dashboards-backup.json
   ```

---

## 📞 Support & Documentation

### Useful URLs
- Prometheus Docs: https://prometheus.io/docs
- Grafana Docs: https://grafana.com/docs
- PromQL Guide: https://prometheus.io/docs/prometheus/latest/querying/basics/
- AlertManager Docs: https://prometheus.io/docs/alerting/latest/overview/

### Key PromQL Queries

**Settlement success rate**:
```promql
rate(settlement_confirmed_total[5m]) / (rate(settlement_initiated_total[5m]) + 0.001)
```

**Deposit detection latency**:
```promql
histogram_quantile(0.95, rate(c_server_deposit_processing_duration_seconds_bucket[5m]))
```

**Database connection pool usage**:
```promql
db_connection_pool_active / db_connection_pool_max * 100
```

**RabbitMQ message throughput**:
```promql
rate(rabbitmq_messages_published_total[5m])
```

---

## ✅ Production Checklist

- [ ] All services running and healthy
- [ ] Prometheus scraping all targets
- [ ] Grafana dashboards displaying data
- [ ] Alert rules configured and evaluating
- [ ] AlertManager routing alerts correctly
- [ ] Email/PagerDuty notifications tested
- [ ] Data retention set to 30 days
- [ ] Grafana admin password changed
- [ ] Backup strategy documented
- [ ] On-call escalation configured
- [ ] Runbooks created for common alerts
- [ ] Monitoring URLs documented for team

---

**Status**: Ready for Production  
**Last Updated**: 2026-06-05  
**Next Review**: After production deployment validation
