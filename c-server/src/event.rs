use serde::Serialize;
use std::time::{SystemTime, UNIX_EPOCH};
use tracing::{info, warn};
use lapin::{Channel, Connection, ConnectionProperties};

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
    channel: Option<Channel>,
    exchange: String,
    routing_key: String,
}

impl EventPublisher {
    pub async fn new(rabbitmq_url: &str, exchange: &str, routing_key: &str) -> Self {
        match Connection::connect(rabbitmq_url, ConnectionProperties::default()).await {
            Ok(conn) => {
                match conn.create_channel().await {
                    Ok(channel) => {
                        if let Err(e) = channel
                            .exchange_declare(
                                exchange,
                                lapin::ExchangeKind::Topic,
                                lapin::options::ExchangeDeclareOptions {
                                    durable: true,
                                    ..Default::default()
                                },
                                lapin::types::FieldTable::default(),
                            )
                            .await
                        {
                            warn!("Failed to declare exchange: {}", e);
                        }
                        info!("✓ RabbitMQ connected: {} → {}.{}", rabbitmq_url, exchange, routing_key);
                        EventPublisher {
                            channel: Some(channel),
                            exchange: exchange.to_string(),
                            routing_key: routing_key.to_string(),
                        }
                    }
                    Err(e) => {
                        warn!("Failed to create channel: {}", e);
                        EventPublisher {
                            channel: None,
                            exchange: exchange.to_string(),
                            routing_key: routing_key.to_string(),
                        }
                    }
                }
            }
            Err(e) => {
                warn!("Failed to connect to RabbitMQ: {}", e);
                EventPublisher {
                    channel: None,
                    exchange: exchange.to_string(),
                    routing_key: routing_key.to_string(),
                }
            }
        }
    }

    pub async fn is_connected(&self) -> bool {
        self.channel.is_some()
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

        if let Some(channel) = &self.channel {
            channel
                .basic_publish(
                    &self.exchange,
                    &self.routing_key,
                    lapin::options::BasicPublishOptions::default(),
                    json.as_bytes(),
                    lapin::BasicProperties::default(),
                )
                .await?;
            info!("✓ Published CRYPTO_DEPOSIT_INITIATED: {} (correlation_id: {})",
                deposit_id, event.correlation_id);
        } else {
            warn!("RabbitMQ not connected, event not published: {}", deposit_id);
        }
        Ok(())
    }
}

fn hash_correlation(deposit_id: &str) -> i64 {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};

    let mut hasher = DefaultHasher::new();
    deposit_id.hash(&mut hasher);
    hasher.finish() as i64
}
