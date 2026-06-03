use lapin::{
    channel::Channel, options::BasicPublishOptions, Connection, ConnectionProperties,
};
use serde::Serialize;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::RwLock;
use tracing::{info, warn};

#[derive(Serialize)]
pub struct CryptoDepositEvent {
    pub event_id: i64,
    pub event_type: String,
    pub timestamp: i64,
    pub source: String,
    pub correlation_id: i64,
    pub data: serde_json::Value,
    pub retry_count: i32,
}

pub struct EventPublisher {
    channel: Arc<RwLock<Option<Channel>>>,
    exchange: String,
    routing_key: String,
    connected: Arc<RwLock<bool>>,
}

impl EventPublisher {
    pub async fn new(rabbitmq_url: &str, exchange: &str, routing_key: &str) -> Self {
        let mut channel_opt = None;
        let mut is_connected = false;

        match Connection::connect(
            rabbitmq_url,
            ConnectionProperties::default(),
        )
        .await
        {
            Ok(conn) => match conn.create_channel().await {
                Ok(ch) => {
                    info!("✓ Connected to RabbitMQ: {}", rabbitmq_url);
                    if let Err(e) = ch
                        .exchange_declare(
                            exchange,
                            lapin::ExchangeKind::Topic,
                            lapin::options::ExchangeDeclareOptions::default(),
                            lapin::types::FieldTable::default(),
                        )
                        .await
                    {
                        warn!("Error declaring exchange {}: {}", exchange, e);
                    } else {
                        info!("✓ Exchange declared: {}", exchange);
                        channel_opt = Some(ch);
                        is_connected = true;
                    }
                }
                Err(e) => {
                    warn!("Failed to create RabbitMQ channel: {}", e);
                }
            },
            Err(e) => {
                warn!("Failed to connect to RabbitMQ: {}", e);
            }
        }

        EventPublisher {
            channel: Arc::new(RwLock::new(channel_opt)),
            exchange: exchange.to_string(),
            routing_key: routing_key.to_string(),
            connected: Arc::new(RwLock::new(is_connected)),
        }
    }

    pub async fn is_connected(&self) -> bool {
        *self.connected.read().await
    }

    pub async fn publish_deposit_initiated(
        &self,
        deposit_id: &str,
        token_type: &str,
        amount: &str,
        tx_hash: &str,
        block_height: u64,
    ) -> Result<(), Box<dyn std::error::Error>> {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_millis() as i64;

        let event = CryptoDepositEvent {
            event_id: now,
            event_type: "CRYPTO_DEPOSIT_INITIATED".to_string(),
            timestamp: now,
            source: "c-server".to_string(),
            correlation_id: hash_correlation(deposit_id),
            data: serde_json::json!({
                "deposit_id": deposit_id,
                "crypto_currency": token_type,
                "crypto_amount": amount,
                "tx_hash": tx_hash,
                "block_height": block_height,
            }),
            retry_count: 0,
        };

        let json = serde_json::to_string(&event)?;
        let channel_lock = self.channel.read().await;

        if let Some(channel) = channel_lock.as_ref() {
            channel
                .basic_publish(
                    &self.exchange,
                    &self.routing_key,
                    BasicPublishOptions::default(),
                    json.as_bytes(),
                    lapin::types::FieldTable::default(),
                )
                .await?;

            info!("Published CRYPTO_DEPOSIT_INITIATED: {} (correlation_id: {})",
                deposit_id, event.correlation_id);
            Ok(())
        } else {
            warn!("RabbitMQ channel not connected");
            Err("RabbitMQ not connected".into())
        }
    }
}

fn hash_correlation(deposit_id: &str) -> i64 {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};

    let mut hasher = DefaultHasher::new();
    deposit_id.hash(&mut hasher);
    hasher.finish() as i64
}
