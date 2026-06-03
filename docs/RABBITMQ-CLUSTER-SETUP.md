# RabbitMQ Cluster Setup for High Availability

Production-grade RabbitMQ cluster with 3 nodes, automatic failover, and HAProxy load balancing for the GtelPay ledger system.

## Architecture

```
┌─────────────────────────────────────────────────┐
│         Clients (Saving-Gateway, Java Ledger)   │
└──────────────────┬──────────────────────────────┘
                   │
        ┌──────────┴──────────┐
        │                     │
    ┌─────────┐          ┌──────────┐
    │ HAProxy │          │  Stats   │
    │ (5672)  │          │ (8404)   │
    │(15672)  │          └──────────┘
    └────┬────┘
         │
    ┌────┴─────────────────────────────┐
    │                                  │
┌───────┐  ┌───────┐  ┌───────┐
│Node 1 │  │Node 2 │  │Node 3 │
│(5672) │  │(5673) │  │(5674) │
│(15672)│  │(15673)│  │(15674)│
└───┬───┘  └───┬───┘  └───┬───┘
    │          │          │
    └──────────┴──────────┘
    (Erlang clustering)
    
    HA Queues (replicated to all nodes)
    Automatic failover on node failure
```

## Quick Start

### 1. Start the Cluster

```bash
chmod +x infra/rabbitmq-cluster/setup-cluster.sh
./infra/rabbitmq-cluster/setup-cluster.sh
```

Or manually:

```bash
docker-compose -f docker-compose.rabbitmq-cluster.yml up -d
# Wait 30 seconds for startup
docker exec rabbitmq-2 rabbitmqctl stop_app
docker exec rabbitmq-2 rabbitmqctl reset
docker exec rabbitmq-2 rabbitmqctl join_cluster rabbit@rabbitmq-1
docker exec rabbitmq-2 rabbitmqctl start_app

docker exec rabbitmq-3 rabbitmqctl stop_app
docker exec rabbitmq-3 rabbitmqctl reset
docker exec rabbitmq-3 rabbitmqctl join_cluster rabbit@rabbitmq-1
docker exec rabbitmq-3 rabbitmqctl start_app
```

### 2. Verify Cluster Status

```bash
docker exec rabbitmq-1 rabbitmqctl cluster_status
```

Output should show:
```
Cluster status of node rabbit@rabbitmq-1 ...
Nodes in cluster: [rabbit@rabbitmq-1,rabbit@rabbitmq-2,rabbit@rabbitmq-3]
```

### 3. Configure HA Policies

```bash
# All queues replicated to all nodes
docker exec rabbitmq-1 rabbitmqctl set_policy -p /gtel-prod HA-all "^" \
  '{"ha-mode":"all","ha-sync-mode":"automatic","ha-sync-batch-size":5}' \
  --apply-to queues
```

### 4. Access the Cluster

- **AMQP Endpoint**: `amqp://gtel-c-server:password@localhost:5672/gtel-prod` (via HAProxy)
- **Management UI**: http://localhost:15672 (admin/admin)
- **HAProxy Stats**: http://localhost:8404/stats
- **Individual Nodes**: 
  - Node 1: http://localhost:15672
  - Node 2: http://localhost:15673
  - Node 3: http://localhost:15674

## Configuration

### RabbitMQ Config (`rabbitmq.conf`)

**Cluster Formation**
```
cluster_formation.peer_discovery_backend = rabbit_peer_discovery_classic_config
cluster_formation.classic_config.nodes.1 = rabbit@rabbitmq-1
cluster_formation.classic_config.nodes.2 = rabbit@rabbitmq-2
cluster_formation.classic_config.nodes.3 = rabbit@rabbitmq-3
```

**Partition Handling**
```
cluster_partition_handling = autoheal  # Automatic recovery from network splits
```

**Memory Management**
```
vm_memory_high_watermark.relative = 0.8   # 80% heap triggers memory alarm
total_memory_available_override_value = 750MB
```

**HA Settings**
```
queue_master_location = all  # Queue masters distributed across nodes
channel_max = 2048           # Channels per connection
heartbeat = 15               # Health check interval (seconds)
```

### HAProxy Config (`haproxy.cfg`)

**Load Balancing Strategy**
- Round-robin across 3 nodes
- TCP health checks every 5 seconds
- Fail over to healthy nodes on connection drop
- Statistics endpoint on port 8404

**AMQP (5672)**
- TCP mode load balancing
- Direct connection to available node
- Automatic failover on node failure

**Management UI (15672)**
- HTTP mode load balancing
- Health check via `/api/health`
- Sticky sessions (optional)

## High Availability Features

### 1. Automatic Failover

If a node goes down:
1. HAProxy detects failure (health check timeout)
2. Routes new connections to remaining nodes
3. Queues replicated on other nodes remain available
4. No message loss (if published with `delivery_mode: 2` - persistent)

**Test failover**:
```bash
docker stop rabbitmq-1
# Wait 5 seconds for HAProxy to detect failure
docker exec rabbitmq-2 rabbitmqctl list_queues  # Should still work
docker start rabbitmq-1
```

### 2. Queue Replication

All queues are replicated to all 3 nodes:
```
HA Policy: {"ha-mode":"all","ha-sync-mode":"automatic"}
```

Benefits:
- Queue master load distributed
- Any node can serve queue operations
- Zero message loss on node failure
- Automatic synchronization

**Verify replication**:
```bash
docker exec rabbitmq-1 rabbitmqctl list_queues name slave_nodes
```

### 3. Erlang Clustering

3 nodes communicate via Erlang distribution protocol:
- Port 25672 (node-to-node communication)
- Shared Erlang cookie: `gtel-secret-cookie`
- Gossip protocol for cluster state
- Automatic node discovery via configured peers

### 4. Memory Alarm System

- High watermark at 80% heap usage
- Total memory limit: 750MB per node
- Triggers flow control on publishers
- Prevents out-of-memory crashes

## Monitoring

### Cluster Health

```bash
# Check cluster status
docker exec rabbitmq-1 rabbitmqctl cluster_status

# List all connections
docker exec rabbitmq-1 rabbitmqctl list_connections

# List queues and replication
docker exec rabbitmq-1 rabbitmqctl list_queues name slave_nodes

# Check memory usage
docker exec rabbitmq-1 rabbitmqctl status
```

### HAProxy Stats

```bash
# View load balancer stats
curl http://localhost:8404/stats

# Monitor in real-time
watch -n 1 'curl -s http://localhost:8404/stats | grep -A 50 "AMQP"'
```

### Prometheus Metrics

RabbitMQ exposes metrics on `/metrics`:
```bash
curl http://localhost:15672/metrics
```

Key metrics:
- `rabbitmq_queue_messages_ready`
- `rabbitmq_queue_messages_unacked`
- `rabbitmq_connections`
- `rabbitmq_channels`
- `process_resident_memory_bytes`

## Failure Scenarios

### Scenario 1: Single Node Failure

**What happens:**
- HAProxy removes failed node from rotation
- Other 2 nodes handle all traffic
- Replicated queues still available
- No message loss

**Recovery:**
```bash
docker start rabbitmq-1
# Waits for health check to pass
# Automatically rejoins cluster
```

### Scenario 2: Dual Node Failure

**What happens:**
- Surviving node has majority (1 of 3)
- Can serve all queue operations
- New messages can be published/consumed

**Recovery:**
```bash
docker start rabbitmq-1 rabbitmq-2
# Both rejoin and sync state
```

### Scenario 3: All Nodes Down

**Recovery:**
```bash
docker-compose -f docker-compose.rabbitmq-cluster.yml up -d
# Cluster reforms from persistent disk data
# All messages and configs restored
```

## Performance Tuning

### Connection Pool Size

For Saving-Gateway, adjust in `application-gateway.yml`:
```yaml
spring:
  rabbitmq:
    cache:
      channel:
        size: 20  # Increase for higher throughput
```

### Batch Processing

Batch event publishing to reduce connection overhead:
```yaml
gateway:
  batch:
    size: 1000      # Larger batches
    window-ms: 100  # 100ms window
```

### Memory per Node

Edit `docker-compose.rabbitmq-cluster.yml`:
```yaml
environment:
  # Adjust based on available memory
  RABBITMQ_MEMORY_LIMIT: "1000000000"  # 1GB per node
```

## Production Checklist

- [ ] All 3 nodes healthy and clustered
- [ ] HA policy set for all queues
- [ ] HAProxy load balancer verified working
- [ ] Failover tested (stop one node)
- [ ] Message persistence enabled (delivery_mode=2)
- [ ] Monitoring/alerts configured
- [ ] Backup strategy defined
- [ ] Network isolation between nodes tested
- [ ] Memory limits appropriate for workload
- [ ] Prometheus scraping RabbitMQ metrics

## Troubleshooting

### Node Won't Join Cluster

```bash
# Reset the node first
docker exec rabbitmq-2 rabbitmqctl reset
# Then try joining again
docker exec rabbitmq-2 rabbitmqctl join_cluster rabbit@rabbitmq-1
docker exec rabbitmq-2 rabbitmqctl start_app
```

### Network Partition Detected

HAProxy will isolate the unreachable node. Monitor with:
```bash
docker exec rabbitmq-1 rabbitmqctl eval 'erlang:element(4, rabbit_clustering:status()).'
```

### Queue Master Down

If queue master node fails, a replica automatically becomes master:
```bash
# View current masters and replicas
docker exec rabbitmq-1 rabbitmqctl list_queues name master_node slave_nodes
```

### Memory Pressure

Check memory usage:
```bash
docker exec rabbitmq-1 rabbitmqctl status | grep memory
```

If approaching limit:
1. Reduce batch sizes to clear queues faster
2. Increase `RABBITMQ_MEMORY_LIMIT`
3. Add queue expiration policies

## Upgrade Path

To add/remove nodes from cluster:

**Add Node 4:**
1. Start new RabbitMQ container
2. Join to existing cluster
3. Update HAProxy config
4. Restart HAProxy

**Remove Node:**
1. Remove from HAProxy
2. Drain messages (optional)
3. `rabbitmqctl forget_cluster_node rabbit@old-node`

## See Also

- [RabbitMQ Clustering Guide](https://www.rabbitmq.com/clustering.html)
- [RabbitMQ High Availability](https://www.rabbitmq.com/ha.html)
- [HAProxy Configuration](http://www.haproxy.org/#docs)
- [Saving-Gateway RabbitMQ Config](docs/INTEGRATION-TEST-GUIDE.md)
