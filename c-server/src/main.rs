use axum::{
    extract::State,
    http::StatusCode,
    routing::get,
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{info, warn};
use web3::Web3;
use std::collections::HashMap;

mod blockchain;
mod event;
mod wallet;

use blockchain::BlockchainListener;
use event::EventPublisher;
use wallet::WalletManager;

#[derive(Clone)]
struct AppState {
    listener: Arc<BlockchainListener>,
    publisher: Arc<EventPublisher>,
    wallet: Arc<WalletManager>,
    metrics: Arc<RwLock<Metrics>>,
}

#[derive(Default, Clone)]
struct Metrics {
    deposits_detected: u64,
    events_published: u64,
    errors: u64,
}

#[derive(Serialize)]
struct HealthResponse {
    status: String,
    blockchain: String,
    rabbitmq: String,
    wallet: String,
    deposits_detected: u64,
    events_published: u64,
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt::init();
    info!("Starting C-Server (Wallet + Blockchain Listener)");

    let config = load_config();

    // Initialize blockchain listener
    let listener = Arc::new(
        BlockchainListener::new(
            &config.rpc_url,
            &config.usdt_contract,
            &config.usdc_contract,
            &config.custody_wallet,
        )
        .await,
    );
    info!("✓ Blockchain listener initialized: {}", config.rpc_url);

    // Initialize RabbitMQ publisher
    let publisher = Arc::new(
        EventPublisher::new(&config.rabbitmq_url, &config.exchange, &config.routing_key)
            .await,
    );
    info!("✓ RabbitMQ publisher connected");

    // Initialize wallet manager
    let wallet = Arc::new(WalletManager::new());
    info!("✓ Wallet manager initialized");

    let state = AppState {
        listener,
        publisher,
        wallet,
        metrics: Arc::new(RwLock::new(Metrics::default())),
    };

    // HTTP server
    let app = Router::new()
        .route("/health", get(health_check))
        .route("/metrics", get(metrics))
        .with_state(state.clone());

    let addr = format!("0.0.0.0:{}", config.port);
    info!("HTTP server listening on {}", addr);

    let listener_handle = tokio::spawn(async move {
        state
            .listener
            .start(state.publisher.clone(), state.metrics.clone())
            .await
    });

    let listener = tokio::net::TcpListener::bind(&addr).await.unwrap();
    info!("Server listening on {}", addr);

    if let Err(e) = axum::serve(
        listener,
        app.into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .await
    {
        warn!("Server error: {}", e);
    }

    listener_handle.abort();
}

async fn health_check(State(state): State<AppState>) -> (StatusCode, Json<HealthResponse>) {
    let metrics = state.metrics.read().await;
    let blockchain_status = if state.listener.is_connected().await {
        "UP".to_string()
    } else {
        "DOWN".to_string()
    };
    let rabbitmq_status = if state.publisher.is_connected().await {
        "UP".to_string()
    } else {
        "DOWN".to_string()
    };

    let response = HealthResponse {
        status: "UP".to_string(),
        blockchain: blockchain_status,
        rabbitmq: rabbitmq_status,
        wallet: "UP".to_string(),
        deposits_detected: metrics.deposits_detected,
        events_published: metrics.events_published,
    };

    (StatusCode::OK, Json(response))
}

async fn metrics(State(state): State<AppState>) -> String {
    let metrics = state.metrics.read().await;
    format!(
        "# HELP c_server_deposits_detected Total deposits detected\n\
         # TYPE c_server_deposits_detected counter\n\
         c_server_deposits_detected {}\n\n\
         # HELP c_server_events_published Total events published to RabbitMQ\n\
         # TYPE c_server_events_published counter\n\
         c_server_events_published {}\n\n\
         # HELP c_server_errors Total errors\n\
         # TYPE c_server_errors counter\n\
         c_server_errors {}\n",
        metrics.deposits_detected, metrics.events_published, metrics.errors
    )
}

fn load_config() -> Config {
    dotenv::dotenv().ok();
    Config {
        port: std::env::var("C_SERVER_PORT").unwrap_or_else(|_| "8080".to_string()),
        rpc_url: std::env::var("BLOCKCHAIN_RPC_URL")
            .unwrap_or_else(|_| "https://eth-mainnet.g.alchemy.com/v2/demo".to_string()),
        usdt_contract: std::env::var("USDT_CONTRACT")
            .unwrap_or_else(|_| "0xdac17f958d2ee523a2206206994597c13d831ec7".to_string()),
        usdc_contract: std::env::var("USDC_CONTRACT")
            .unwrap_or_else(|_| "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48".to_string()),
        custody_wallet: std::env::var("CUSTODY_WALLET")
            .unwrap_or_else(|_| "0x1234567890123456789012345678901234567890".to_string()),
        rabbitmq_url: std::env::var("RABBITMQ_URL")
            .unwrap_or_else(|_| "amqp://gtel-c-server:password@localhost:5672/gtel-prod".to_string()),
        exchange: std::env::var("RABBITMQ_EXCHANGE")
            .unwrap_or_else(|_| "gtel-events".to_string()),
        routing_key: std::env::var("RABBITMQ_ROUTING_KEY")
            .unwrap_or_else(|_| "ledger.crypto".to_string()),
    }
}

struct Config {
    port: String,
    rpc_url: String,
    usdt_contract: String,
    usdc_contract: String,
    custody_wallet: String,
    rabbitmq_url: String,
    exchange: String,
    routing_key: String,
}
